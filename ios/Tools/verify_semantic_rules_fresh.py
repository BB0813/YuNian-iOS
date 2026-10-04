#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_semantic_rules_fresh.py — 核对语义检测规则与 Kotlin `SemanticDetector.kt` 一致。

## 为什么需要
第 137 轮我发现 `golden_content_filter.py` 的语义层规则（EVASION / INTENT /
MULTILINGUAL）是**从测试向量与源码记忆转写的，未逐条对源码** —— 属于自证。

本关卡闭合它：直接从 `SemanticDetector.kt` 的三张 map 里抽出正则，
与 Python 模型里的逐条比对。不一致即失败。

## Kotlin 侧结构（SemanticDetector.kt:17-59）
    evasionPatterns      CHAR_SUBSTITUTION(4) / SPACE_INSERTION(2)
    intentPatterns       SEXUAL_INTENT(3) / VIOLENT_INTENT(3) / ILLEGAL_INTENT(3)
    multilingualPatterns CHINESE_ENGLISH_MIX(4) / PINYIN_DETECTION(2)
共 8 组 21 条正则。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
KOTLIN = REPO_ROOT / "core/common/src/main/java/com/yunian/ai/common/SemanticDetector.kt"
MODEL = REPO_ROOT / "ios/Tools/golden_content_filter.py"


def kotlin_rules() -> dict[str, list[str]]:
    """从 SemanticDetector.kt 抽三张 map：`"KEY" to arrayOf(...)`。"""
    src = KOTLIN.read_text(encoding="utf-8")
    out: dict[str, list[str]] = {}

    # ⚠️ 第 138 轮：不能用 `arrayOf\((.*?)\)` —— 正则里含 `)`，非贪婪会在
    # 第一个 `)` 处截断，导致整个数组只拿到前缀、`"""..."""` 一条都不匹配
    # （首次运行 Kotlin 侧抽到 0 条）。改为按 `"KEY" to arrayOf(` 切段。
    for mapname in ("evasionPatterns", "intentPatterns", "multilingualPatterns"):
        m = re.search(rf"val {mapname}\s*=\s*mapOf\((.*?)\n    \)", src, re.S)
        if not m:
            sys.exit(f"Kotlin 侧找不到 {mapname}")
        body = m.group(1)
        for km in re.finditer(r'"([A-Z_]+)"\s*to\s*arrayOf\(', body):
            key = km.group(1)
            seg = body[km.end():]
            # 该组到下一个 `"KEY" to arrayOf(` 或本 map 结束
            nxt = re.search(r'"[A-Z_]+"\s*to\s*arrayOf\(', seg)
            if nxt:
                seg = seg[:nxt.start()]
            pats: list[str] = []
            # Kotlin 两种写法（第 138 轮），**二选一**：
            #   evasionPatterns      → """..."""  raw string
            #   intent/multilingual  → "..."      普通字符串
            # ⚠️ 不能两个都跑：三引号模式的收尾/开头引号会被普通串正则再凑成一对，
            # 导致同一组翻倍（CHAR_SUBSTITUTION 4 → 16）。以三引号命中为准。
            triple = [m.group(1).replace("${'$'}", "$").strip()
                      for m in re.finditer(r'"""(.*?)"""', seg, re.S)]
            if triple:
                pats = triple
            else:
                for pm in re.finditer(r'"((?:[^"\\\n]|\\.)*)"', seg):
                    raw = pm.group(1)
                    raw = raw.replace('\\\\', '\\').replace('\\"', '"')
                    if re.fullmatch(r"[A-Z_]+", raw):   # 跳过 map 的 key
                        continue
                    pats.append(raw.strip())
            out[key] = pats
    return out


def model_rules() -> dict[str, list[str]]:
    """从 golden_content_filter.py 里的 EVASION / INTENT / MULTILINGUAL 常量抽。"""
    src = MODEL.read_text(encoding="utf-8")
    out: dict[str, list[str]] = {}
    for const in ("EVASION", "INTENT", "MULTILINGUAL"):
        m = re.search(rf"^{const}\s*=\s*\{{(.*?)^\}}", src, re.S | re.M)
        if not m:
            sys.exit(f"Python 模型里找不到 {const}")
        body = m.group(1)
        for km in re.finditer(r'"([A-Z_]+)"\s*:\s*\[(.*?)\]', body, re.S):
            key, arr = km.group(1), km.group(2)
            pats = re.findall(r'r"((?:[^"\\]|\\.)*)"|"((?:[^"\\]|\\.)*)"', arr)
            out[key] = [a or b for a, b in pats]
    return out


def norm(p: str) -> str:
    """
    归一化：两边对同一正则的写法可能有等价差异。
    Kotlin raw string 与 Python raw string 在**未转义**时是同一文本；
    这里只去首尾空白，不改变语义（宁可报"不同"让人工确认，不擅自归一）。
    """
    return p.strip()


def main() -> int:
    k = kotlin_rules()
    m = model_rules()

    problems: list[str] = []

    # ① 组名集合一致
    k_keys, m_keys = set(k), set(m)
    for key in sorted(k_keys - m_keys):
        problems.append(f"Kotlin 有规则组 `{key}`，Python 模型没有")
    for key in sorted(m_keys - k_keys):
        problems.append(f"Python 模型有规则组 `{key}`，Kotlin 没有")

    # ② 每组内的正则逐条比对
    for key in sorted(k_keys & m_keys):
        kp = [norm(x) for x in k[key]]
        mp = [norm(x) for x in m[key]]
        if kp == mp:
            continue
        if len(kp) != len(mp):
            problems.append(f"`{key}` 条数不同：Kotlin {len(kp)} 条，模型 {len(mp)} 条")
        for i, (a, b) in enumerate(zip(kp, mp)):
            if a != b:
                problems.append(f"`{key}` 第 {i + 1} 条不同：\n      Kotlin: {a}\n      模型  : {b}")

    if problems:
        print(f"[FAIL] 语义检测规则与 Kotlin 不一致，{len(problems)} 处：")
        for p in problems:
            print("  -", p)
        print("\n这是第 137 轮标注的「语义层仍自证」—— 现在可以逐条核对了。")
        return 1

    total = sum(len(v) for v in k.values())
    print(f"语义检测规则与 Kotlin 一致（{len(k)} 组 / {total} 条正则逐条比对）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
