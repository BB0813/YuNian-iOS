#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_docs_coverage.py — 核对 M0-RUNBOOK 覆盖了全部测试文件。

## 为什么需要
第 67 轮发现：M0-RUNBOOK（供人在 macOS 上实跑的入口文档）在十几轮前写好，
期间新增了 4 个测试文件，但文档没有同步 —— **`DeviceToolsTests` 不在清单里**。

文档漂移的代价：跑 M0 的人拿着旧清单，看到莫名的测试失败会以为是自己环境的问题；
或者反过来，报告说「全部通过」时其实漏跑了新测试。

这类漂移无法靠"记得更新"防止，所以做一次便宜的门禁。
"""
from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
RUNBOOK = REPO_ROOT / "ios/M0-RUNBOOK.md"
TESTS_DIR = REPO_ROOT / "ios/YuNianTests"


def main() -> int:
    if not RUNBOOK.exists():
        print("[FAIL] 找不到 M0-RUNBOOK.md")
        return 1
    text = RUNBOOK.read_text(encoding="utf-8")

    files = sorted(p.stem for p in TESTS_DIR.glob("*.swift")) if TESTS_DIR.exists() else []
    if not files:
        print("[FAIL] YuNianTests 下没有测试文件")
        return 1

    missing = [f for f in files if f not in text]
    print(f"测试文件 {len(files)} 个，runbook 覆盖 {len(files) - len(missing)} 个")
    if missing:
        print("未被 runbook 提及（请补进「跑单元测试」一节的表格）：")
        for m in missing:
            print(f"  - {m}")
        return 1
    print("runbook 覆盖全部测试文件")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
