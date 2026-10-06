#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_ci_workflow.py — 核对 ios-agent.yml 的结构自洽。

## 为什么需要
19 道关卡在本机全绿，**不等于它们在 CI 里会跑**。
若 workflow 的 job 依赖引用了不存在的 job、步骤名重复（GitHub 会用后者覆盖前者的显示）、
或 run 里写了不存在的脚本路径，CI 会以一种"看起来在跑"的方式失败或跳过，
而我只能在本机证明关卡通过。

本项目环境无 PyYAML，故用结构检查而非完整解析 —— 这本身就是一种妥协，
所以在文件末尾注明了它**不能**发现的问题。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = REPO_ROOT / ".github/workflows/ios-agent.yml"


def main() -> int:
    if not WORKFLOW.exists():
        print("[FAIL] 找不到 workflow")
        return 1
    text = WORKFLOW.read_text(encoding="utf-8")
    lines = text.splitlines()

    problems: list[str] = []

    # ① job 名：`jobs:` 之后、**恰好 2 空格缩进**的 key
    jobs: list[str] = []
    in_jobs = False
    for line in lines:
        if re.match(r"^jobs:\s*$", line):
            in_jobs = True
            continue
        if in_jobs:
            if re.match(r"^\S", line) and not line.startswith("jobs:"):
                # 遇到顶层 key（缩进 0）→ jobs 段结束
                if line.strip() and not line.startswith("#"):
                    break
            m = re.match(r"^  ([\w][\w-]*):\s*$", line)
            if m:
                jobs.append(m.group(1))
    if not jobs:
        print("[FAIL] 未识别出任何 job")
        return 1

    # ② needs 引用必须存在
    for line in lines:
        m = re.match(r"^\s+needs:\s*(.+)$", line)
        if not m:
            continue
        for need in re.findall(r"[\w-]+", m.group(1)):
            if need not in jobs:
                problems.append(f"needs 引用了不存在的 job：{need}")

    # ③ run 里的脚本路径必须存在
    for m in re.findall(r"python3\s+(ios/Tools/[\w.-]+)", text):
        if not (REPO_ROOT / m).exists():
            problems.append(f"workflow 引用了不存在的脚本：{m}")
        elif (REPO_ROOT / m).suffix != ".py":
            problems.append(f"workflow 引用的不是 .py：{m}")

    # ④ 同一 job 内步骤名不得重复（GitHub 会用后者覆盖前者）
    per_job_steps: dict[str, set[str]] = {}
    current = None
    for line in lines:
        jm = re.match(r"^  ([\w-]+):\s*$", line)
        if jm and jm.group(1) not in {"steps", "with", "env", "needs", "outputs", "strategy", "runs-on"}:
            current = jm.group(1)
            per_job_steps.setdefault(current, set())
        sm = re.match(r"^\s+- name:\s*(.+)$", line)
        if sm and current:
            name = sm.group(1).strip()
            if name in per_job_steps[current]:
                problems.append(f"job {current} 内步骤名重复：{name}")
            per_job_steps[current].add(name)

    # ── 第 180 轮新增：legacy 档那一遍必须存在 ──────────────────
    #
    # 起因：`#available(iOS 26.0, *)` 在 CI 的 iOS 26 模拟器上恒真，
    # 所以 **iOS 17–25 那条 `.ultraThinMaterial` 分支 CI 从来没跑过**。
    # 用户要的是"17 与 26 分水岭"，结果一半分支零验证。
    #
    # 加这条断言是为了防"将来有人（包括我）把第二遍删了"——
    # 删了不会立刻出问题，只会让一条分支悄悄失去验证。
    if "YUNIAN_GLASS_STYLE=legacy" not in text:
        problems.append(
            "workflow 缺少 YUNIAN_GLASS_STYLE=legacy 那一遍测试 —— "
            "iOS 17-25 分支将零验证（第 180 轮加的，别删）"
        )

    if problems:
        print(f"[FAIL] workflow 不自洽，{len(problems)} 处：")
        for p in problems:
            print("  -", p)
        return 1

    n_steps = sum(len(v) for v in per_job_steps.values())
    print(f"workflow 自洽（{len(jobs)} 个 job / {n_steps} 个具名步骤 / 脚本路径全部存在）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

# 本检查**不能**发现的问题（结构检查的固有边界）：
#  · YAML 语法错误（无 PyYAML，未做完整解析）
#  · `if:` 条件的逻辑错误
#  · step 的顺序依赖（前一步产物后一步才用）
#  · 表达式 `${{ }}` 的拼写
# 这些要靠 GitHub Actions 自己报，或将来引入 PyYAML 后补强。
