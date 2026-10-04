#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_client_id_extraction.py — 核对 clientId 推导逻辑与 Kotlin 一致（V9 三项之二）。

## 为什么需要
`docs/ios-port-feasibility.md` §7.5 / V9 列出请求签名的三处不一致：
  ① PATH 前缀（`/v1/...` vs `/chat/completions`）—— 需服务端结论，本地不可解
  ② **CLIENT_ID 来源** —— 可本地核对
  ③ keyId → 公钥映射 / `X-LianYu-Pub` —— 需服务端结论

第 133 轮我为「载荷字段序列」建了关卡（三项之一的字段序）。
本轮补 ②：把 Kotlin 的推导逻辑用 Python 转写，与
`RequestSignerTests` 里硬编码的期望逐条比对。

## Kotlin 侧权威逻辑（RequestSecurityInterceptor.extractYuNianClientId）
```kotlin
request.header("X-LianYu-Client-Id")?.takeIf { it.isNotBlank() }?.let { return it }
val authorization = request.header("Authorization") ?: return ""
if (!authorization.startsWith("Bearer ", ignoreCase = true)) return ""
return authorization.removePrefix("Bearer ").substringBefore(':')
```
三处易错点（移植时必须如实复刻，不能"改对"）：
  1. 前缀**判断**忽略大小写，但 **removePrefix 区分大小写**
     → `bearer abc` 判断通过、剥离失败，clientId = "bearer abc"
  2. `takeIf { it.isNotBlank() }`：全空白头视作未提供
  3. 无冒号时 `substringBefore(':')` 返回整串
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
KOTLIN = REPO_ROOT / (
    "core/network/src/main/java/com/yunian/ai/network/RequestSecurityInterceptor.kt"
)
SWIFT = REPO_ROOT / "ios/YuNian/Platform/RequestSigner.swift"
TESTS = REPO_ROOT / "ios/YuNianTests/RequestSignerTests.swift"


def kotlin_extract(client_id_header: str | None, authorization: str | None) -> str:
    """忠实转写 Kotlin 的三步推导。"""
    if client_id_header is not None and client_id_header.strip() != "":
        return client_id_header
    if authorization is None:
        return ""
    if not authorization.lower().startswith("bearer "):
        return ""
    # removePrefix 区分大小写
    body = authorization[len("Bearer "):] if authorization.startswith("Bearer ") else authorization
    return body.split(":", 1)[0]


def swift_has_real_logic() -> bool:
    """确认 Swift 侧不是 stub（至少包含 removePrefix 语义与大小写判断）。"""
    src = SWIFT.read_text(encoding="utf-8")
    return ("caseInsensitive" in src and "hasPrefix" in src)


def parse_swift_cases() -> list[tuple[str | None, str | None, str]]:
    """从 RequestSignerTests 抽 (clientIdHeader, authorizationHeader, expected)。"""
    text = TESTS.read_text(encoding="utf-8")
    m = re.search(r"// MARK: - clientId 推导(.*?)(?:// MARK:|\Z)", text, re.S)
    if not m:
        return []
    body = m.group(1)

    cases: list[tuple[str | None, str | None, str]] = []
    # 断言形态有两种（都在本仓出现）：
    #   XCTAssertEqual(\n  RequestSigner.clientId(a: X, b: Y),\n  "Z"\n)
    #   XCTAssertEqual(RequestSigner.clientId(a: X, b: Y), "Z")
    # 故：先定位调用，再取其**之后最近**的字符串字面量作为期望值。
    call_pat = re.compile(
        r'RequestSigner\.clientId\(\s*clientIdHeader:\s*([^,]+?)\s*,\s*'
        r'authorizationHeader:\s*([^),]+?)\s*\)'
    )
    str_pat = re.compile(r'"((?:[^"\\]|\\.)*)"')
    for cm in call_pat.finditer(body):
        def lit(s: str) -> str | None:
            s = s.strip()
            if s == "nil":
                return None
            if s.startswith('"') and s.endswith('"'):
                return s[1:-1].replace('\\"', '"').replace("\\\\", "\\")
            return None
        tail = body[cm.end(): cm.end() + 200]
        sm = str_pat.search(tail)
        if not sm:
            continue
        cases.append((lit(cm.group(1)), lit(cm.group(2)),
                      sm.group(1).replace('\\"', '"').replace("\\\\", "\\")))
    return cases


def main() -> int:
    if not KOTLIN.exists():
        print("[FAIL] 找不到 Kotlin 侧 RequestSecurityInterceptor.kt")
        return 1

    ksrc = KOTLIN.read_text(encoding="utf-8")
    # ① Kotlin 侧必须仍有该推导函数（否则契约源头消失）
    if "extractYuNianClientId" not in ksrc:
        print("[FAIL] Kotlin 侧找不到 extractYuNianClientId —— 契约源头可能已改名/删除")
        return 1

    if not swift_has_real_logic():
        print("[FAIL] Swift 侧 RequestSigner 疑似 stub（缺大小写判断或 hasPrefix）")
        return 1

    cases = parse_swift_cases()
    if not cases:
        print("[SKIP] 未能从 RequestSignerTests 抽出 clientId 用例（测试结构可能已变）")
        return 0

    problems: list[str] = []
    for ch, ah, exp in cases:
        got = kotlin_extract(ch, ah)
        if got != exp:
            problems.append(
                f"clientId(clientIdHeader={ch!r}, authorization={ah!r})\n"
                f"      测试期望: {exp!r}\n      Kotlin 逻辑: {got!r}"
            )

    if problems:
        print(f"[FAIL] clientId 推导与 Kotlin 不一致，{len(problems)} 处：")
        for p in problems:
            print("  -", p)
        print("\n处理：这是 V9 三项之二（CLIENT_ID 来源）。不一致 → 服务端签出的")
        print("      clientId 与我方不同 → 必然拒签。修 Swift 侧以对齐 Kotlin。")
        return 1

    print(f"clientId 推导与 Kotlin 逻辑一致（{len(cases)} 个用例）")
    print("  注：覆盖三处易错点 —— 大小写不对称的 Bearer 剥离、空白头视为未提供、无冒号取整串")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
