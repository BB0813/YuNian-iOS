#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
run_all_gates.py — 一键跑完全部本地关卡，输出汇总。

## 为什么需要
第 102–122 轮里，我在 PowerShell 里读检查器退出码时出了六次错：
`Out.Null`（拼写，管道断）、`-ne` 大小写不敏感、`--check` 分支没跑到……
每次都是「我在读一个我关心的值，但用的方式不可靠」。

我先前的对策是「临时命令套 function」（第 108 轮的方子），
但本轮决定做结构性解决：**让 PowerShell 完全不参与退出码判断**。
本脚本跑完所有关卡，自己汇总成败并以退出码报告 ——
我此后只需要读**一个**退出码，且是在 Python 里读自己的。

## 用法
    python ios/Tools/run_all_gates.py            # 全部关卡
    python ios/Tools/run_all_gates.py --quick    # 跳过慢的（SQLite/golden）
"""
from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
TOOLS = REPO_ROOT / "ios/Tools"
PY = sys.executable
BASH = "C:/Program Files/Git/bin/bash.exe"

# (名字, 参数, 是否 --check 生成器)
GATES: list[tuple[str, list[str], bool]] = [
    ("swift_text 自测", [], False),
    ("schema 生成物一致", ["--check"], True),
    ("初始数据种子一致", ["--check"], True),
    ("安全基线种子一致", ["--check"], True),
    ("内置技能种子一致", ["--check"], True),
    ("字面量契约", [], False),
    ("生成绑定调用", [], False),
    ("Java 正则复核过滤金标", [], False),
    ("工具定义与 Android 一致", [], False),
    ("runbook 覆盖测试文件", [], False),
    ("自有类型引用", [], False),
    ("SQL 对真实 schema", [], False),
    ("SQLite 建库与 FTS", [], False),
    ("内容过滤金标", [], False),
    ("Swift conformance", [], False),
    ("Swift 冒烟", [], False),
    ("API 覆盖面", [], False),
    ("import 覆盖", [], False),
    ("属性包装器", [], False),
    ("枚举 rawValue", [], False),
    ("类型名唯一性", [], False),
    ("字符串插值括号", [], False),
    ("CI workflow 结构", [], False),
    ("M0 前提条件", [], False),
    ("README 统计", [], False),
    ("死代码扫描", [], False),
    ("金标向量与种子一致", [], False),
    ("分词向量与算法一致", [], False),
    ("签名载荷契约（V9）", [], False),
    ("clientId 推导（V9）", [], False),
    ("配置 JSON 键对齐", [], False),
    ("语义检测规则对齐", [], False),
    ("PARTNER 路径自洽（V9）", [], False),
]

SCRIPT_BY_INDEX = [
    "swift_text.py",
    "generate_schema.py", "generate_seeds.py", "generate_security_seed.py",
    "generate_builtin_skill_seed.py",
    "verify_literals.py", "verify_generated_api_usage.py", "gen_regex_probe.py",
    "verify_tool_contracts.py", "verify_docs_coverage.py", "verify_own_types.py",
    "verify_swift_sql.py", "verify_schema_sql.py", "golden_content_filter.py",
    "verify_swift_conformance.py", "verify_swift_syntax_smoke.py",
    "verify_api_coverage.py", "verify_imports.py", "verify_property_wrappers.py",
    "verify_enum_raw_values.py", "verify_unique_types.py", "verify_ci_workflow.py",
    # 第 72 轮：CI 第一次真正编译时报 "Cannot find ')' to match opening '('
    # in string interpolation"，根因是插值收尾用了全角 ）。整体括号配平抓不到它
    # （全角不计入），只有插值内部不对称才暴露。
    "verify_interpolation_parens.py",
    "verify_m0_prereqs.py", "verify_readme_accounting.py", "find_dead_swift.py",
    "verify_golden_vectors_fresh.py", "verify_tokenizer_vectors_fresh.py",
    "verify_signing_payload_contract.py", "verify_client_id_extraction.py",
    "verify_config_json_keys.py", "verify_semantic_rules_fresh.py",
    "verify_partner_path_consistency.py",
]

SLOW = {"SQLite 建库与 FTS", "SQL 对真实 schema", "内容过滤金标", "Java 正则复核过滤金标"}


def main() -> int:
    quick = "--quick" in sys.argv
    results: list[tuple[str, bool, float, str]] = []

    for (label, extra, _is_gen), script in zip(GATES, SCRIPT_BY_INDEX):
        if quick and label in SLOW:
            continue
        path = TOOLS / script
        if not path.exists():
            results.append((label, False, 0.0, "脚本缺失"))
            continue
        t0 = time.time()
        r = subprocess.run([PY, str(path)] + extra, cwd=REPO_ROOT,
                           capture_output=True, text=True)
        dt = time.time() - t0
        tail = ((r.stdout or "") + (r.stderr or "")).strip().splitlines()
        last = tail[-1] if tail else ""
        results.append((label, r.returncode == 0, dt, last[:70]))

    # build_agent_ios.sh 语法
    t0 = time.time()
    rb = subprocess.run([BASH, "-n", str(REPO_ROOT / "scripts/build_agent_ios.sh")],
                        capture_output=True, text=True)
    results.append(("build_agent_ios.sh 语法", rb.returncode == 0,
                    time.time() - t0, "" if rb.returncode == 0 else "bash -n 失败"))

    failed = [r for r in results if not r[1]]
    width = max(len(n) for n, *_ in results)
    for name, ok, dt, last in results:
        mark = "PASS" if ok else "FAIL"
        print(f"  [{mark}] {name:<{width}}  {dt:5.1f}s")

    print(f"\n共 {len(results)} 项，失败 {len(failed)} 项")
    if failed:
        print("\n失败项的最后一行输出：")
        for name, _ok, _dt, last in failed:
            print(f"  · {name}: {last}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
