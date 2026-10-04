#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_swift_syntax_smoke.py — Swift 源码的结构冒烟检查（无编译器可用时）。

## 它能做什么 / 不能做什么
Swift 编译器不在本机时（非 macOS），至少可以检查：
  - 圆括号 / 方括号 / 花括号是否配平
  - 多行字符串字面量（三个连续双引号）是否成对

**它不能替代编译器** —— 类型错误、缺失 import、Actor 隔离问题一概查不出来。
它的价值在于：在 CI 的便宜环节挡住「复制粘贴漏了个括号」这类低级错误，
避免把明显坏掉的代码交给昂贵的 macOS 任务。

## 为什么用词法扫描而不是正则替换
这个检查器先后栽过三次，都是「用正则剥离文本」造成的：

  1. 先剥行注释、后剥字符串 → `"asset://"` 里的双斜杠被当注释起点，该行被截断，
     括号计数失衡，误报 `AvatarResolver.swift` 有问题。
  2. 改成先剥字符串后，**文档注释里作为散文出现的那三个双引号**
     （例如解释「Kotlin 用的是原始字符串」时引用其定界符）被当成真的多行字符串定界符，
     导致计数为奇数，误报 `SemanticDetector.swift` 有问题。
  3. 连本文件自己的 docstring 都因同一原因被提前终止 —— 见下。

三次都是同一个根因：**用正则处理带嵌套与上下文的文本**。
因此这里改用逐字符状态机，区分：行注释、块注释（可嵌套）、普通字符串、
原始字符串（井号加引号）、多行字符串。只有状态机才能同时正确处理这些情形。

⚠️ 维护提醒：本模块的 docstring 里**不能出现三个连续的双引号**，
否则会把它自己提前终止（第 3 次踩坑就是这么来的）。需要提及时请用文字描述。
"""
from __future__ import annotations

import pathlib
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]


def scan(src: str) -> tuple[str, list[str]]:
    """
    返回 (剥离了字符串与注释的代码骨架, 问题列表)。

    只保留结构性字符（括号等），字符串内容与注释一律置空。
    """
    out: list[str] = []
    problems: list[str] = []

    i = 0
    n = len(src)
    long_string_open_line: int | None = None
    line = 1

    while i < n:
        c = src[i]

        if c == "\n":
            line += 1
            out.append(c)
            i += 1
            continue

        # ── 行注释 ──
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue

        # ── 块注释（Swift 支持嵌套）──
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            depth = 1
            i += 2
            while i < n and depth > 0:
                if src[i] == "\n":
                    line += 1
                if src.startswith("/*", i):
                    depth += 1
                    i += 2
                elif src.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
            if depth > 0:
                problems.append(f"第 {line} 行附近有未闭合的块注释")
            continue

        # ── 原始字符串 #"..."# / ##"..."## ──
        if c == "#":
            hashes = 0
            j = i
            while j < n and src[j] == "#":
                hashes += 1
                j += 1
            if j < n and src[j] == '"':
                terminator = '"' + "#" * hashes
                if src.startswith('"""', j):
                    # 原始多行字符串
                    end = src.find('"""' + "#" * hashes, j + 3)
                    if end < 0:
                        problems.append(f"第 {line} 行有未闭合的原始多行字符串")
                        i = n
                        continue
                    line += src.count("\n", i, end)
                    i = end + 3 + hashes
                else:
                    k = j + 1
                    while k < n and not src.startswith(terminator, k):
                        if src[k] == "\n":
                            break
                        k += 1
                    i = k + len(terminator) if k < n else n
                out.append('""')
                continue

        # ── 多行字符串 """...""" ──
        if src.startswith('"""', i):
            if long_string_open_line is None:
                long_string_open_line = line
                end = src.find('"""', i + 3)
                if end < 0:
                    problems.append(f"第 {line} 行有未闭合的多行字符串")
                    i = n
                    continue
                line += src.count("\n", i, end)
                i = end + 3
                long_string_open_line = None
            else:  # 理论上到不了这里
                long_string_open_line = None
                i += 3
            out.append('""')
            continue

        # ── 普通字符串 ──
        if c == '"':
            i += 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == '"':
                    i += 1
                    break
                if src[i] == "\n":
                    # 普通字符串不能跨行；视为未闭合
                    problems.append(f"第 {line} 行有未闭合的字符串字面量")
                    break
                i += 1
            out.append('""')
            continue

        out.append(c)
        i += 1

    return "".join(out), problems


def main() -> int:
    files = sorted(
        list((REPO_ROOT / "ios/YuNian").rglob("*.swift"))
        + list((REPO_ROOT / "ios/YuNianTests").rglob("*.swift"))
    )
    if not files:
        print("没有找到 Swift 文件")
        return 1

    problems: list[str] = []
    for path in files:
        src = path.read_text(encoding="utf-8")
        skeleton, scan_problems = scan(src)

        for p in scan_problems:
            problems.append(f"{path.name}: {p}")

        for opener, closer, label in (("{", "}", "花括号"), ("(", ")", "圆括号"), ("[", "]", "方括号")):
            a, b = skeleton.count(opener), skeleton.count(closer)
            if a != b:
                problems.append(f"{path.name}: {label}不配平（{a} vs {b}）")

    print(f"检查 {len(files)} 个 Swift 文件，问题 {len(problems)} 个")
    for p in problems:
        print("  -", p)
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
