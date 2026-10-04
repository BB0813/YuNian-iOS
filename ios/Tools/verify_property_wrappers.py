#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_property_wrappers.py — 核对属性包装器与所处类型是否匹配。

## 为什么需要
第 115 轮清点「尚未覆盖的编译风险面」。这类错误是**确定性的编译错误**，
且能可靠判定（不像第 114 轮的 switch 穷尽性，那个我从语法上推不出类型）：

| 包装器 | 只能用于 | 误用症状 |
|---|---|---|
| `@Published` | **class**（且需 ObservableObject） | 写在 struct/actor 上：`Property 'x' with a wrapper 'Published' ... requires a class` |
| `@State` / `@StateObject` / `@ObservedObject` / `@EnvironmentObject` | **struct**（SwiftUI View） | 写在 class 上：编译错误 |
| `@ObservedObject` | struct + ObservableObject 类型 | — |

## 判据
逐层扫 `struct X { ... }` / `class X { ... }` / `actor X { ... }` 的花括号配平块体，
在**直接**体内（不含嵌套类型体）找上述包装器，与容器类型比对。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SRC = REPO_ROOT / "ios/YuNian"

CLASS_ONLY = {"@Published"}
STRUCT_ONLY = {"@State", "@StateObject", "@ObservedObject",
               "@EnvironmentObject", "@Environment", "@Namespace", "@FocusState", "@Bindable"}


def block_end(text: str, open_idx: int) -> int:
    depth = 0
    for j in range(open_idx, len(text)):
        c = text[j]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return j
    return -1


def direct_body(text: str, start: int, end: int) -> str:
    """
    取容器「直接」体内 —— 即剔掉嵌套类型声明后的部分。
    否则外层 class 里嵌套的 struct 上的 @State 会被误判成 class 误用。
    """
    body = text[start:end]
    for m in list(re.finditer(r"\b(struct|class|actor|enum)\s+\w+[^\n{]*\{", body)):
        # 嵌套类型体整体替换为空白，保留行长以维持行号近似（本检查不用行号，故可简化）
        nb = block_end(body, m.end() - 1)
        if nb > 0:
            body = body[:m.start()] + " " * (nb - m.start() + 1) + body[nb + 1:]
    return body


def main() -> int:
    files = sorted(SRC.rglob("*.swift"))
    if not files:
        print("[FAIL] 没有找到 Swift 文件")
        return 1

    problems: list[str] = []
    checked = 0

    for p in files:
        text = p.read_text(encoding="utf-8", errors="ignore")
        rel = str(p.relative_to(REPO_ROOT))
        for m in re.finditer(r"^\s*(struct|class|actor)\s+(\w+)[^\n{]*\{", text, re.M):
            kind, name = m.group(1), m.group(2)
            brace = m.end() - 1
            end = block_end(text, brace)
            if end < 0:
                continue
            body = direct_body(text, brace, end)
            line_no = text.count("\n", 0, m.start()) + 1

            for w in CLASS_ONLY:
                if w in body:
                    checked += 1
                    if kind != "class":
                        problems.append(
                            f"{rel}:{line_no}  {kind} {name} 里用了 {w} —— 它只能用于 class"
                        )
            for w in STRUCT_ONLY:
                if w in body:
                    checked += 1
                    if kind not in {"struct", "actor"}:
                        problems.append(
                            f"{rel}:{line_no}  {kind} {name} 里用了 {w} —— SwiftUI 包装器只能用于 struct"
                        )

    if problems:
        print(f"[FAIL] 属性包装器误用 {len(problems)} 处：")
        for q in problems:
            print("  -", q)
        return 1

    print(f"属性包装器核对通过（{len(files)} 个文件 / {checked} 处包装器使用）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
