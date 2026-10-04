#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_golden_vectors_fresh.py — 核对 ContentFilterTests 的金标向量与当前种子一致。

## 为什么需要
第 131 轮发现一个真实漂移风险：

`golden_content_filter.py` 从 `SecuritySeed.json` **现算**金标向量，
并可用 `--swift` 输出与测试数组**逐字同形**的片段；
而 `ios/YuNianTests/ContentFilterTests.swift` 里是**硬编码**的那一份。

于是种子一变（新增过滤词 / 编码调整），重新生成的结果就与测试里的不符，
而我无法本地跑 Swift 测试 —— 两边不符只会在真机上以「测试失败」出现，
看不出是种子有意变更还是测试过期。

本关卡把它在本地闭合：生成 `--swift` 片段 → 与测试里的 case 块逐行比对。

## 用法
    python ios/Tools/verify_golden_vectors_fresh.py
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
GENERATOR = REPO_ROOT / "ios/Tools/golden_content_filter.py"
TESTS = REPO_ROOT / "ios/YuNianTests/ContentFilterTests.swift"
PY = sys.executable

CASE_RE = re.compile(
    r"\(text:\s*(?:(?:\"(?:[^\"\\]|\\.)*\")|(?:'(?:[^'\\]|\\.)*'))\s*,\s*"
    r"blockingViolating:\s*(?:true|false)\s*,\s*"
    r"blockingLevel:\s*\.\w+\s*,\s*"
    r"keywordLevel:\s*\.\w+\s*\)"
)


def normalize(line: str) -> str:
    r"""
    归一化：去首尾空白、压缩内部连续空白，并把 \uXXXX 反转义为字面字符。

    第 131 轮：生成器用 json.dumps(..., ensure_ascii=True) 输出转义序列，
    而测试文件里是字面 UTF-8 中文。不反转义就说 6 处"不符"，其实全是编码形态差异。

    注意本 docstring 必须是 raw 字符串：里面出现的 \uXXXX 若不当字面量，
    Python 会在编译期尝试解码它并抛 SyntaxError（与第 52 轮 swift_text.py
    的 docstring 三引号自终止同型的第二次）。
    """
    s = re.sub(r"\s+", " ", line.strip())
    s = re.sub(r"\\u([0-9a-fA-F]{4})",
               lambda m: chr(int(m.group(1), 16)), s)
    s = s.replace('\\"', '"').replace("\\\\", "\\")
    return s


def main() -> int:
    # ① 生成期望片段
    r = subprocess.run([PY, str(GENERATOR), "--swift"], cwd=REPO_ROOT,
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("[FAIL] 金标生成器失败")
        print((r.stderr or r.stdout)[:400])
        return 1
    generated = [normalize(l) for l in r.stdout.splitlines()
                 if l.strip().startswith("(text:")]

    # ② 测试里的实际片段
    text = TESTS.read_text(encoding="utf-8")
    m = re.search(r"func testGoldenVectors\(\)\s*\{(.*?)\n    \}", text, re.S)
    if not m:
        print("[FAIL] 找不到 ContentFilterTests.testGoldenVectors")
        return 1
    existing = [normalize(l) for l in m.group(1).splitlines()
                if l.strip().startswith("(text:")]

    if not generated or not existing:
        print(f"[FAIL] 无法提取 case（生成 {len(generated)} / 测试 {len(existing)}）")
        return 1

    # ③ 逐行比对
    problems: list[str] = []
    for i, (g, e) in enumerate(zip(generated, existing)):
        if g != e:
            problems.append(f"第 {i + 1} 个 case 不符：\n      生成: {g}\n      测试: {e}")
    if len(generated) != len(existing):
        problems.append(f"case 数量不符：生成 {len(generated)}，测试 {len(existing)}")

    if problems:
        print(f"[FAIL] 金标向量与当前种子不一致，{len(problems)} 处：")
        for p in problems:
            print("  -", p)
        print("\n处理：种子若为**有意**变更 → 跑 `python ios/Tools/golden_content_filter.py --swift`")
        print("      把产出替换进 ContentFilterTests 的 case 数组；否则回滚种子改动。")
        return 1

    print(f"金标向量与当前种子一致（{len(generated)} 个 case 逐行比对）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
