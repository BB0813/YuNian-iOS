#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_readme_accounting.py — 核对 ios/README.md 顶部的交付物统计与真实情况一致。

## 为什么需要
第 112 轮发现：README 顶部的「83 文件 / 21,652 行（Swift 55 / Python 18）」
在我这几十轮里从未更新，实际已是 **87 / 22,689 / 22**。

README 是**这个目录的状态源**——读它的人会拿这些数字判断规模与完整度。
一个过期的统计比没有统计更糟：它会让人误判"还有多少没做"。

这与第 67 轮的 `verify_docs_coverage`（防 runbook 漏列测试文件）是同一类
"文档漂移"，只是对象从清单换成了数字。

## 判据
README 第一段里形如 `N 文件 / M 行（Swift A / Python B / Markdown C）`
的三个数必须与实测一致。数字写错 → 失败。

## ⚠️ 统计的是 **git 跟踪集**，不是磁盘
第 203 轮改。原先扫磁盘，于是本机只要存在**任何被 `.gitignore` 的文件**
（Apple 文档抓取脚本、`agent-native/target/` 的中间产物），本地统计就比 CI 多；
我再用 `--fix` 把多的数字写进 README → **CI 立刻红**。
第 202 轮真实发生过（README 写 Python 43 / CI 实际 37）。

根因不是"忘了同步"，是**判据取错了树**：CI 只看 checkout 出来的那棵树。
所以本脚本以 `git ls-files` 为准 —— 本地与 CI 从此同一个判据。
"""
from __future__ import annotations

import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
README = REPO_ROOT / "ios/README.md"

EXTS = {".swift", ".py", ".md", ".plist", ".json", ".yml", ".sh", ".png"}
ROOTS = ("ios", "scripts", ".github")


def tracked_paths() -> list[Path]:
    """git 跟踪的绝对路径；git 不可用时退回扫磁盘（并打印警告）。

    静默换判据比统计漂移更危险，所以退回时一定会打一行字。
    """
    try:
        r = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "ls-files", "-z"],
            capture_output=True, text=True, check=True,
        )
    except (OSError, subprocess.CalledProcessError) as e:
        print(f"[warn] 取不到 git 跟踪集（{e}）；退回扫磁盘 —— 结果可能与 CI 不一致")
        fallback: list[Path] = []
        for root in ROOTS:
            base = REPO_ROOT / root
            if base.exists():
                fallback.extend(p for p in base.rglob("*") if p.is_file())
        return fallback
    return [REPO_ROOT / p for p in r.stdout.split("\0") if p]


def actual() -> tuple[int, int, int, int, int, int]:
    files = lines = 0
    by_ext: Counter[str] = Counter()
    for p in tracked_paths():
        if p.suffix not in EXTS or "Generated" in str(p):
            continue
        try:
            rel = p.relative_to(REPO_ROOT)
        except ValueError:
            continue
        if not rel.parts or rel.parts[0] not in ROOTS:
            continue
        files += 1
        by_ext[p.suffix] += 1
        if p.suffix != ".png":
            lines += len(p.read_text(encoding="utf-8", errors="ignore").splitlines())
    return files, lines, by_ext[".swift"], by_ext[".py"], by_ext[".md"], by_ext[".json"]


def main() -> int:
    if not README.exists():
        print("[FAIL] 找不到 ios/README.md")
        return 1
    text = README.read_text(encoding="utf-8")
    head = "\n".join(text.splitlines()[:12])

    af, al, asw, apy, _, _ = actual()

    # --fix：把 README 顶部的统计直接改写为实测值。
    #
    # 为什么需要这个模式（第 117 轮教训）：我连续两轮"按上一轮差值手算"新数字，
    # 每次都被本关卡抓住（差 6 行、差 8 行）。而在 README 里写"勿手改后不同步"
    # **拦不住我** —— 提示不改变默认动作。
    # 结构性解法：让"更新数字"这一步没有手算的空间。
    if "--fix" in sys.argv:
        # 替换范围必须吃到收尾的 `）`（可能有多个，来自上一次残缺替换）
        # 与随后的 `,`/`，`，否则原文后半段残留。
        # 第 118 轮第一次 --fix 产出了 `... Python 27）），` 这种残缺行，
        # 第二次正则没考虑重复右括号又修不动它。
        new = re.sub(
            r"(\d+)\s*文件\s*/\s*([\d,]+)\s*行[^)]*?Swift\s*(\d+)\s*/\s*Python\s*(\d+)\s*）+\s*[,，]?",
            lambda _m: f"{af} 文件 / {al:,} 行（Swift {asw} / Python {apy}），",
            head, count=1,
        )
        if new == head:
            print("没有可改写的统计（正则未匹配）")
            return 1
        rest = text.split("\n", 12)[12] if len(text.splitlines()) > 12 else ""
        README.write_text(new + "\n" + rest, encoding="utf-8")
        print(f"已按实测回填：{af} 文件 / {al:,} 行（Swift {asw} / Python {apy}）")
        return 0

    m = re.search(
        r"(\d+)\s*文件\s*/\s*([\d,]+)\s*行[^)]*?Swift\s*(\d+)\s*/\s*Python\s*(\d+)",
        head,
    )
    if not m:
        print("[FAIL] README 顶部找不到「N 文件 / M 行（Swift A / Python B）」形式的统计")
        print("       若格式已变，请同步修改本脚本的正则")
        return 1

    claimed_files = int(m.group(1))
    claimed_lines = int(m.group(2).replace(",", ""))
    claimed_swift = int(m.group(3))
    claimed_py = int(m.group(4))

    af, al, asw, apy, _, _ = actual()
    problems = []
    if claimed_files != af:
        problems.append(f"文件数：README 写 {claimed_files}，实际 {af}")
    if claimed_lines != al:
        problems.append(f"行数：README 写 {claimed_lines}，实际 {al}")
    if claimed_swift != asw:
        problems.append(f"Swift 文件数：README 写 {claimed_swift}，实际 {asw}")
    if claimed_py != apy:
        problems.append(f"Python 文件数：README 写 {claimed_py}，实际 {apy}")

    if problems:
        print(f"[FAIL] README 交付物统计已漂移（{len(problems)} 项）：")
        for p in problems:
            print("  -", p)
        return 1

    print(f"README 统计与实测一致：{af} 文件 / {al:,} 行（Swift {asw} / Python {apy}）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
