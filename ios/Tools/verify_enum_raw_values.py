#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_enum_raw_values.py — 核对同一枚举内没有重复的 rawValue。

## 为什么需要
第 117 轮清点。枚举原始值重复**不是编译错误**，而是让 `init(rawValue:)`
在运行时返回 nil —— 症状是"某个 case 明明存在却取不到"，极难归因。
而它能确定判定：同一 `enum X: T` 内收集所有 `case n = v`，看 v 有没有重复。

## 对本仓尤其相关
iOS 侧多个枚举的 rawValue 是**与 Android 跨端对齐的契约**
（`ConversationType.chat = "chat"`、`ViolationLevel`、"TEXT"/"IMAGE" 消息类型…）。
重复会让某一端写出的值在另一端读不出来。
"""
from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SRC = REPO_ROOT / "ios/YuNian"

# enum X: RawType { case a = "v" ... }
ENUM_DECL = re.compile(r"\benum\s+(\w+)\s*:\s*(\w+)\s*\{")
CASE_WITH_RAW = re.compile(r'\bcase\s+(\w+)\s*=\s*("(?:[^"\\]|\\.)*"|-?\d+)')


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


def main() -> int:
    files = sorted(SRC.rglob("*.swift"))
    if not files:
        print("[FAIL] 没有找到 Swift 文件")
        return 1

    problems: list[str] = []
    enums_seen = 0
    cases_seen = 0

    for p in files:
        text = p.read_text(encoding="utf-8", errors="ignore")
        rel = str(p.relative_to(REPO_ROOT))
        for m in ENUM_DECL.finditer(text):
            name, raw_type = m.group(1), m.group(2)
            brace = m.end() - 1
            end = block_end(text, brace)
            if end < 0:
                continue
            body = text[brace:end]
            # 只取直接体内（嵌套 enum 的 case 不算本枚举的）
            nested = list(ENUM_DECL.finditer(body))
            for nb in nested:
                nb_end = block_end(body, nb.end() - 1)
                if nb_end > 0:
                    body = body[:nb.start()] + " " * (nb_end - nb.start() + 1) + body[nb_end + 1:]

            enums_seen += 1
            by_value: dict[str, list[str]] = defaultdict(list)
            for cm in CASE_WITH_RAW.finditer(body):
                case_name, raw = cm.group(1), cm.group(2)
                by_value[raw].append(case_name)
                cases_seen += 1
            for raw, names in by_value.items():
                if len(names) > 1:
                    line_no = text.count("\n", 0, m.start()) + 1
                    problems.append(
                        f"{rel}:{line_no}  enum {name}: {raw_type} 的 rawValue {raw} "
                        f"被多个 case 占用：{', '.join(names)}"
                    )

    if problems:
        print(f"[FAIL] 重复 rawValue {len(problems)} 处：")
        for q in problems:
            print("  -", q)
        return 1

    print(f"枚举 rawValue 唯一性通过（{enums_seen} 个带原始值的枚举 / {cases_seen} 个 case）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
