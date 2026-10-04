#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_interpolation_parens.py — Swift 字符串插值括号闭合检查。

## 为什么需要
CI 第一次真正编译时报了一个语法错误：

    Cannot find ')' to match opening '(' in string interpolation

定位到 BackupCrypto.swift:54：

    return "文件格式不正确（magic 为 \([UInt8](m)，非 LYBK）"

收尾用了**全角** `）`（U+FF09）而不是 ASCII `)`，
插值因此永远找不到闭合括号。

而 verify_swift_syntax_smoke 的整体括号配平检查抓不到它 ——
因为整体看是配平的（全角括号不计入），只有**插值内部**不对称。

## 实现要点
逐字符扫描：遇到 `\(` 进入插值后按括号深度前进，深度回 0 才算插值结束。
这样做才能正确处理嵌套插值，例如 `\(String(describing: error), privacy: .public)`。
（第一版用正则 `\\\((.*?)\)` 会在第一个 `)` 处截断，产出 45 条假阳性。）
"""
from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ROOTS = [
    REPO_ROOT / "ios/YuNian",
    REPO_ROOT / "ios/YuNianTests",
    REPO_ROOT / "ios/Generated",
]


def strip_comments(text: str) -> list[str]:
    """
    去掉 // 行注释与 /* */ 块注释，只保留代码行。

    ⚠️ 第 72 轮：第一版**没剥注释**，结果把我自己在备份代码里写的
    "这里原来写的是 `\\([UInt8](m)`" 这条说明当成了未闭合插值。
    这与第 50 轮 swift_text.py 的教训同型 ——
    **扫描代码前必须先剥注释**，否则注释里的示例代码会污染结果。
    """
    out: list[str] = []
    in_block = False
    for line in text.splitlines():
        i, n, res = 0, len(line), []
        while i < n:
            if in_block:
                if line.startswith("*/", i):
                    in_block = False
                    i += 2
                    continue
                i += 1
                continue
            if line.startswith("/*", i):
                in_block = True
                i += 2
                continue
            if line.startswith("//", i):
                break  # 行注释：其后全部丢弃
            # 字符串字面量原样保留（插值就在里面）
            res.append(line[i])
            i += 1
        out.append("".join(res))
    return out


def scan(text: str) -> list[tuple[int, str]]:
    bad: list[tuple[int, str]] = []
    for lineno, line in enumerate(strip_comments(text), 1):
        i, n = 0, len(line)
        while i < n:
            if line[i] == "\\" and i + 1 < n and line[i + 1] == "(":
                j, depth = i + 2, 1
                while j < n and depth > 0:
                    if line[j] == "\\" and j + 1 < n and line[j + 1] == "(":
                        depth += 1
                        j += 2
                        continue
                    if line[j] == "(":
                        depth += 1
                    elif line[j] == ")":
                        depth -= 1
                    j += 1
                if depth != 0:
                    bad.append((lineno, line.strip()))
                i = j
                continue
            i += 1
    return bad


def main() -> int:
    found: list[str] = []
    for r in ROOTS:
        if not r.exists():
            continue
        for p in sorted(r.rglob("*.swift")):
            for lineno, snippet in scan(p.read_text(encoding="utf-8", errors="ignore")):
                found.append(f"{p.relative_to(REPO_ROOT)}:{lineno}: {snippet[:90]}")
    if found:
        print(f"[FAIL] 字符串插值括号未闭合 {len(found)} 处：")
        for f in found:
            print("  -", f)
        print("\n最常见原因：插值收尾用了全角 ）而不是 ASCII )")
        return 1
    print("字符串插值括号全部闭合")
    return 0


if __name__ == "__main__":
    sys.exit(main())
