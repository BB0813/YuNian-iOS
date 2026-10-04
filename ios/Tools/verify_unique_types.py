#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_unique_types.py — 核对模块内没有重复声明的类型名。

## 为什么需要
第 116 轮清点「具体的、未覆盖的、能确定判定的失败模式」。
同模块内两个同名类型是**确定性编译错误**（`invalid redeclaration of 'X'`），
而判据完全可靠：把 `struct|class|enum|actor|protocol|typealias` 的声明名收集起来
看有没有重名。

我写了 55 个 Swift 文件、2 万多行，其中不少是分多轮增量加的 ——
这正是容易产生重名的场景。

## 边界
- 同名但**不同命名空间**是合法的（`A.Foo` 与 `B.Foo`），所以重名要看**限定路径**。
  本脚本按文件分组做初筛，再跨文件比对；嵌套类型按外层前缀限定。
- `extension` 不算声明（它不引入新类型名）。
"""
from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SRC = REPO_ROOT / "ios/YuNian"

DECL = re.compile(r"^\s*(?:public\s+|internal\s+|fileprivate\s+|private\s+|final\s+|open\s+)*"
                  r"(struct|class|enum|actor|protocol|typealias)\s+(\w+)", re.M)


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


def declarations(text: str) -> list[tuple[str, str]]:
    """
    返回 (kind, qualified_name)，嵌套类型带外层前缀。

    ⚠️ 第 116 轮：第一版没做嵌套限定，把 `ChatInputGuard.Outcome` 与
    `DatabaseMaintenance.Outcome` 判成重名 —— 二者是不同外层里的嵌套类型，
    在 Swift 里完全合法。必须按花括号配平维护外层栈。
    """
    out: list[tuple[str, str]] = []
    stack: list[tuple[str, int]] = []      # (outer_name, body_end)

    for m in DECL.finditer(text):
        # 弹出已结束的外层
        while stack and stack[-1][1] <= m.start():
            stack.pop()
        kind, name = m.group(1), m.group(2)
        qualified = ".".join([s[0] for s in stack] + [name]) if stack else name
        out.append((kind, qualified))

        brace = m.end()
        # 只有带 `{` 的声明才有体（typealias / 无体的 protocol 没有）
        nl = text.find("\n", m.end())
        head = text[m.end():nl if nl > 0 else m.end() + 80]
        if "{" in head:
            body_end = block_end(text, text.find("{", m.end()))
            if body_end > 0:
                stack.append((name, body_end))
    return out


def main() -> int:
    files = sorted(SRC.rglob("*.swift"))
    if not files:
        print("[FAIL] 没有找到 Swift 文件")
        return 1

    by_name: dict[str, list[str]] = defaultdict(list)
    total = 0
    for p in files:
        text = p.read_text(encoding="utf-8", errors="ignore")
        rel = str(p.relative_to(REPO_ROOT))
        for kind, name in declarations(text):
            by_name[name].append(f"{rel}（{kind}）")
            total += 1

    dups = {n: where for n, where in by_name.items() if len(where) > 1}

    if dups:
        print(f"[FAIL] 重复类型名 {len(dups)} 个（共 {total} 个声明）：")
        for n, where in sorted(dups.items()):
            print(f"  - {n}:")
            for w in where:
                print(f"      {w}")
        return 1

    print(f"类型名唯一性核对通过（{len(files)} 个文件 / {total} 个类型声明）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
