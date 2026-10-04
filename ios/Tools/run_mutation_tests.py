#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
run_mutation_tests.py — 对本地关卡做**可重复**的变异测试。

## 为什么需要
第 101–108 轮里，我四次把「检查器没坏」误判成「检查器坏了」
（或反过来），根因全在**我的临时测试脚本**：

| 轮次 | 我的脚本错在哪 |
|---|---|
| 101 | 编辑没生效就去测 → 测了个空 |
| 102 | `Out.Null`（应为 `Out-Null`）→ 退出码来自断掉的管道 |
| 107 | 变异设计得不构成错误（`///` 注释里插三引号） |
| 108 | PowerShell `-ne` **大小写不敏感** → 大小写变异显示"没替换" |

四次都是"测试设施出错"，而被测的关卡/代码其实没问题。
**结论：测试设施本身才最需要被测试。** 这个脚本就是把上面四类错误
一次性消除：Python 的 `str != str` 是大小写敏感的、退出码来自 subprocess
不会断管道、每个变异都先断言"字符串确实变了"再断言"检查器确实失败"。

用法：
    python ios/Tools/run_mutation_tests.py            # 全部
    python ios/Tools/run_mutation_tests.py literals  # 只跑某个
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PY = sys.executable

# 每个条目：检查器脚本 + (文件, 原文, 改文)
# 原文必须**存在**、改文必须与原文**不同**（大小写敏感）。
CASES: dict[str, dict] = {
    "literals": {
        "checker": "verify_literals.py",
        "mutations": [
            (
                "ios/YuNian/Data/Repositories/MessageRepository.swift",
                'case chat = "chat"',
                'case chat = "CHAT"',
            ),
        ],
    },
    "own_types": {
        "checker": "verify_own_types.py",
        "mutations": [
            (
                "ios/YuNian/Agent/ChatSession.swift",
                "@MainActor",
                "@MainActor\n    private let x = AgentHostTypo()",
            ),
        ],
    },
    "tool_contracts": {
        "checker": "verify_tool_contracts.py",
        "mutations": [
            (
                "ios/YuNian/Agent/DeviceTools.swift",
                "查询手机电量与充电状态（无参数）。",
                "查询手机电量。",
            ),
        ],
    },
    "docs_coverage": {
        "checker": "verify_docs_coverage.py",
        # 特殊：新增一个未列入 runbook 的测试文件
        "add_file_mutation": ("ios/YuNianTests/ZZZTempMutationProbeTests.swift"),
    },
    "smoke": {
        "checker": "verify_swift_syntax_smoke.py",
        "mutations": [
            (
                "ios/YuNian/Agent/ApiProbeService.swift",
                "    static func authHeaders(",
                '    static func authHeadersXXX("""',
            ),
        ],
    },
    "sql_check": {
        "checker": "verify_swift_sql.py",
        "mutations": [
            (
                "ios/YuNian/Data/BackupImporter.swift",
                "WHERE m.conversationId = ? AND m.conversationType = 'chat'",
                "WHERE m.conversationId = ? AND m.conversationType = ?",
            ),
        ],
    },
    "conformance": {
        "checker": "verify_swift_conformance.py",
        "mutations": [
            (
                "ios/YuNian/Agent/AgentHost.swift",
                "func onTextDelta(text: String)",
                "func onTextDeltaXXX(text: String)",
            ),
        ],
    },
    "m0_prereqs": {
        "checker": "verify_m0_prereqs.py",
        "mutations": [
            (
                "ios/YuNian/Resources/Assets.xcassets/avatar_xiaoyu.imageset/Contents.json",
                '"avatar_xiaoyu.png"',
                '"missing_avatar.png"',
            ),
        ],
    },
    # 特殊：dead-code 检查器的变异是「**制造**一处死代码」
    "dead_code": {
        "checker": "find_dead_swift.py",
        "inject_dead": (
            "ios/YuNian/Agent/ApiProbeService.swift",
            "    /// 测试连接：发一个最小请求，验证 key / baseUrl / 协议是否都通。",
            "    struct MutationProbeDeadCode { let x = 1 }\n\n"
            "    /// 测试连接：发一个最小请求，验证 key / baseUrl / 协议是否都通。",
        ),
    },
    # 生成物一致性：破坏生成的 Swift，--check 必须发现。
    # 这两项此前从未变异测过，而 schema 是最承重的关卡（它漂移则全线崩）。
    "schema_generated": {
        "checker": "generate_schema.py",
        "check_args": ["--check"],
        "mutations": [
            (
                "ios/YuNian/Data/Schema/YuNianSchema.swift",
                "conversationType",
                "conversationTtype",
            ),
            (
                "ios/YuNian/Data/Schema/YuNianSchema.swift",
                "static let version = 45",
                "static let version = 44",
            ),
        ],
    },
    # 初始数据种子（伴侣 / 世界书默认值）与安全基线种子（过滤词表/答题）。
    # 同 schema：变异破坏**生成物**，--check 应发现。
    "seeds_generated": {
        "checker": "generate_seeds.py",
        "check_args": ["--check"],
        "mutations": [
            (
                "ios/YuNian/Data/Seed/YuNianSeed.swift",
                "companions",
                "companionsXXX",
            ),
        ],
    },
    "security_seed_generated": {
        "checker": "generate_security_seed.py",
        "check_args": ["--check"],
        "mutations": [
            # 安全种子的产物是 **JSON 资源**（不是 Swift）：
            # ios/YuNian/Resources/SecuritySeed.json
            (
                "ios/YuNian/Resources/SecuritySeed.json",
                "filterPatterns",
                "filterPatternsXXX",
            ),
        ],
    },
    # 建库脚本对真实 SQLite 的断言（140 项）：变异 DDL 里的索引定义。
    "schema_sql": {
        "checker": "verify_schema_sql.py",
        "mutations": [
            (
                "ios/YuNian/Data/Schema/YuNianSchema.swift",
                "CREATE INDEX",
                "CREATE INDEX typo_",
            ),
        ],
    },
    # import 覆盖：删掉一个必需 import。
    "imports": {
        "checker": "verify_imports.py",
        "mutations": [
            (
                "ios/YuNian/Agent/DeviceTools.swift",
                "import UIKit              ",
                "// removed ",
            ),
        ],
    },
}


def run_checker(name: str) -> int:
    cfg = CASES[name]
    extra = cfg.get("check_args", [])
    r = subprocess.run(
        [PY, f"ios/Tools/{cfg['checker']}"] + extra,
        cwd=REPO_ROOT, capture_output=True,
    )
    return r.returncode


def try_one(name: str, rel: str, old: str, new: str) -> tuple[bool, str]:
    """做一个变异，返回 (是否如预期失败, 说明)。"""
    p = REPO_ROOT / rel
    if not p.exists():
        return False, f"文件不存在：{rel}"
    original = p.read_text(encoding="utf-8")

    # ① 变异前必须能跑过
    if run_checker(name) != 0:
        p.write_text(original, encoding="utf-8")
        return False, "变异前基线就是失败的，先修检查器"

    if old not in original:
        p.write_text(original, encoding="utf-8")
        return False, f"原文不在文件里：{old[:40]!r}"
    mutated = original.replace(old, new, 1)
    if mutated == original:          # 大小写敏感比较
        p.write_text(original, encoding="utf-8")
        return False, "替换后内容未变（大小写敏感比较）"

    p.write_text(mutated, encoding="utf-8")
    code = run_checker(name)
    p.write_text(original, encoding="utf-8")

    if code == 0:
        return False, "变异后检查器仍通过 → 检查器未覆盖该情形"
    return True, "变异被抓住"


def try_add_file(name: str, rel: str) -> tuple[bool, str]:
    p = REPO_ROOT / rel
    if run_checker(name) != 0:
        return False, "变异前基线失败"
    p.write_text("// mutation probe\n", encoding="utf-8")
    code = run_checker(name)
    p.unlink(missing_ok=True)
    return (code != 0), ("新增未列入文件被抓住" if code else "新增文件未被抓住")


def try_inject_dead(name: str, rel: str, anchor: str, injected: str) -> tuple[bool, str]:
    """制造一处死代码，检查器应当报出它。"""
    p = REPO_ROOT / rel
    if not p.exists():
        return False, f"文件不存在：{rel}"
    original = p.read_text(encoding="utf-8")

    # dead-code 检查器是 report-only，基线也可能是"有 32 处死代码"并通过。
    # 所以判据不是退出码，而是**输出里是否出现注入的那个名字**。
    base = subprocess.run([PY, f"ios/Tools/{CASES[name]['checker']}"],
                          cwd=REPO_ROOT, capture_output=True, text=True)
    if anchor not in original:
        return False, f"锚点不在文件里：{anchor[:40]!r}"
    mutated = original.replace(anchor, injected, 1)
    if mutated == original:
        return False, "注入后内容未变"
    p.write_text(mutated, encoding="utf-8")
    out = subprocess.run([PY, f"ios/Tools/{CASES[name]['checker']}"],
                         cwd=REPO_ROOT, capture_output=True, text=True)
    p.write_text(original, encoding="utf-8")

    stdout = (out.stdout or "") + (out.stderr or "")
    if "MutationProbeDeadCode" in stdout:
        return True, "制造的死代码被报出"
    return False, "注入的死代码未被报出 → 检查器漏报"


def main() -> int:
    only = sys.argv[1] if len(sys.argv) > 1 else None
    targets = [only] if only else list(CASES)
    if only and only not in CASES:
        print(f"未知关卡：{only}（可选：{', '.join(CASES)}）")
        return 2

    passed = failed = 0
    for name in targets:
        cfg = CASES[name]
        if "add_file_mutation" in cfg:
            ok, msg = try_add_file(name, cfg["add_file_mutation"])
        elif "inject_dead" in cfg:
            rel, anchor, injected = cfg["inject_dead"]
            ok, msg = try_inject_dead(name, rel, anchor, injected)
        else:
            ok, msg = True, ""
            for rel, old, new in cfg["mutations"]:
                ok, msg = try_one(name, rel, old, new)
                if not ok:
                    break
        status = "PASS" if ok else "FAIL"
        print(f"  [{status}] {name:16s} {msg}")
        if ok:
            passed += 1
        else:
            failed += 1

    print(f"\n变异测试：{passed} 通过 / {failed} 失败（共 {len(targets)} 个关卡）")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
