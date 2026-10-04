#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_signing_payload_contract.py — 核对 iOS 与 Android 的**签名载荷字段序列**一致。

## 为什么需要（V9 的直接本地防线）
请求签名是 fail-closed 的：payload 任何一处不同，服务端直接拒签，
而**验签代码不在本仓库** —— 我无法自查，只能靠与 Kotlin 逐字一致。

第 133 轮从 Kotlin 侧读到权威格式：
```kotlin
// core/network/.../RequestSecurityInterceptor.kt:181
val payload = "v1\n$method\n$path\n$bodyHash\n$timestamp\n$nonce\n$clientId\n$deviceId"
```
handshake 变体：
```kotlin
// 同文件
"v1\nhandshake\n$challenge\n$deviceId"
```

本关卡把两边的**字段序列**抽出来比对：
  · Kotlin：从 `"v1\n$a\n$b\n..."` 字符串模板里按序取 `$name`
  · Swift：从 `RequestSigner.swift` 的 payload 数组里按序取字段名
序列不同即失败。这比"人眼比对字符串"可靠，也比全等字符串比对更能容忍
无害的格式差异（分隔符写法、变量命名）。

## 注意
它只覆盖**字段序列与顺序**，不覆盖每个字段的**取值推导**（那需要真机签名端到端）。
但字段序列是最高风险的一项 —— 少一段、多一段、顺序错，服务端都会拒。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
KOTLIN = REPO_ROOT / "core/network/src/main/java/com/yunian/ai/network/RequestSecurityInterceptor.kt"
# handshake 模板**不在** interceptor 里，而在 RemoteKeyProvider.kt:452
KOTLIN_HANDSHAKE = REPO_ROOT / "core/common/src/main/java/com/yunian/ai/common/RemoteKeyProvider.kt"
SWIFT = REPO_ROOT / "ios/YuNian/Platform/RequestSigner.swift"


def kotlin_payload_fields() -> tuple[list[str], list[str]]:
    """返回 (主 payload 字段序列, handshake 字段序列)。"""
    src = KOTLIN.read_text(encoding="utf-8")
    main: list[str] = []
    handshake: list[str] = []

    m = re.search(r'val payload\s*=\s*"((?:[^"\\]|\\.)*)"', src)
    if not m:
        sys.exit("Kotlin 侧找不到主 payload 模板")
    tpl = m.group(1).replace("\\n", "\n").replace('\\"', '"')
    for seg in tpl.split("\n"):
        fm = re.search(r"\$(\w+)", seg)
        main.append(fm.group(1) if fm else seg.strip())

    # handshake 在另一个文件
    if KOTLIN_HANDSHAKE.exists():
        hsrc = KOTLIN_HANDSHAKE.read_text(encoding="utf-8")
        for hm in re.finditer(r'val payload\s*=\s*"((?:[^"\\]|\\.)*handshake(?:[^"\\]|\\.)*)"', hsrc):
            tpl2 = hm.group(1).replace("\\n", "\n").replace('\\"', '"')
            segs = []
            for seg in tpl2.split("\n"):
                fm = re.search(r"\$(\w+)", seg)
                segs.append(fm.group(1) if fm else seg.strip())
            handshake = segs
            break
    return main, handshake


def swift_payload_fields(k_main: list[str], k_hand: list[str]) -> tuple[list[str], list[str]]:
    """从 RequestSigner.swift 抽两个 payload 的字段序列（按 Kotlin 字段名匹配）。"""
    src = SWIFT.read_text(encoding="utf-8")
    main: list[str] = []
    handshake: list[str] = []

    # 字段抽取：对每个 Swift payload 行，取**与 Kotlin 字段名匹配**的那个标识符。
    # ⚠️ 不用"行末标识符"——`method.uppercased()` 的末标识符是 `uppercased`，
    # `String(timestamp)` 是 `String`，`DeviceIdentity.deviceId` 是 `DeviceIdentity`。
    # 第 133 轮就因这个把 method 段整段丢掉、误报"字段数不同"。
    KOTLIN_NAMES = {n.lower() for n in k_main} | {n.lower() for n in k_hand} | {"v1"}
    ALIAS = {"signatureversion": "v1", "handshakesegment": "handshake"}

    payload_blocks: list[list[str]] = []
    for m in re.finditer(r"let payload\s*=\s*\[(.*?)\]", src, re.S):
        body = m.group(1)
        seq: list[str] = []
        for l in body.splitlines():
            s = l.strip().rstrip(",")
            if not s or s.startswith("//"):
                continue
            ids = re.findall(r"[A-Za-z_]\w*", s)
            picked = None
            for i in ids:
                key = ALIAS.get(i.lower(), i.lower())
                if key in KOTLIN_NAMES:
                    picked = key
                    break
            seq.append(picked if picked else (ids[0] if ids else "?"))
        if seq:
            payload_blocks.append(seq)

    for seq in payload_blocks:
        if "handshake" in seq:
            handshake = seq
        elif len(seq) >= 6:
            main = seq

    return main, handshake


def main() -> int:
    k_main, k_hand = kotlin_payload_fields()
    s_main, s_hand = swift_payload_fields(k_main, k_hand)

    problems: list[str] = []
    if not k_main:
        problems.append("Kotlin 侧未能抽出主 payload 字段")
    if not s_main:
        problems.append("Swift 侧未能抽出主 payload 字段")
    if k_main and s_main:
        if len(k_main) != len(s_main):
            problems.append(
                f"主 payload 字段数不同：Kotlin {len(k_main)} 段 {k_main}，"
                f"Swift {len(s_main)} 段 {s_main}")
        else:
            for i, (k, s) in enumerate(zip(k_main, s_main)):
                if k.lower() != s.lower():
                    problems.append(
                        f"主 payload 第 {i + 1} 段不同：Kotlin={k!r} Swift={s!r}")
        # 首段必须是 "v1"、且无尾随空段
        if k_main and s_main and (k_main[0] != s_main[0]):
            problems.append("首段不同")

    if k_hand and s_hand:
        # ⚠️ 必须大小写不敏感比较：Kotlin 写 `deviceId`，Swift 侧为匹配 Kotlin 字段名
        # 已被我规整成小写 `deviceid`。第 133 轮第一版这里用了 `!=`（大小写敏感），
        # 于是一个规整差异被报成契约不符。
        if [x.lower() for x in k_hand] != [x.lower() for x in s_hand]:
            problems.append(f"handshake payload 不同：Kotlin={k_hand} Swift={s_hand}")
    elif k_hand or s_hand:
        problems.append(f"handshake payload 抽取不完整：Kotlin={k_hand} Swift={s_hand}")

    if problems:
        print(f"[FAIL] 签名载荷契约不一致，{len(problems)} 处：")
        for p in problems:
            print("  -", p)
        print("\n这是 V9（请求签名跨端一致性）的本地防线 —— 不一致则服务端必然拒签。")
        return 1

    print(f"签名载荷一致：主 payload {len(s_main)} 段（{'/'.join(s_main)}）")
    if s_hand:
        print(f"                  handshake {len(s_hand)} 段（{'/'.join(s_hand)}）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
