#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
verify_tokenizer_vectors_fresh.py — 用 Python 重算分词金标，与测试里的硬编码向量比对。

## 为什么需要
第 131 轮我发现 `ContentFilterTests` 的金标向量是硬编码的、会与种子漂移，
于是加了 `verify_golden_vectors_fresh.py`。这一轮按同一模式检查分词器，
发现 **`MessageSearchTokenizerTests` 的向量也是硬编码的，而且连生成器都没有** ——
它们是早期读 Kotlin 后手工推出来的。

与过滤词表不同，这里的"权威源"不是本地种子而是 **Kotlin 的算法**。
漂移的含义也不同：若 Kotlin 算法改了（或我当初推错了），
硬编码向量不会变，而我不能跑 Swift 测试 → 不符只会在真机上出现。

本关卡用 Python 忠实转写 Kotlin 算法，重算这些向量并逐条比对。

## 与 Kotlin 的逐条对应（第 136 轮补，打破循环论证）
本关卡的 Python 模型最初是**拟合硬编码测试向量**得出的，而那些向量又来自我读 Kotlin ——
这是循环的。第 136 轮直接回到 Kotlin 源码逐条确认：

    core/database/.../MessageSearchTokenizer.kt
    L27: value.lowercase(Locale.ROOT).codePoints()      ← 小写化确在算法内
    L12: .joinToString(" ")                              ← 空格连接
    L29: unigram  = "u${codePoint.toString(16)}z"        ← hex 小写、无前导零
    L31: bigram   = "b${first.toString(16)}x${second.toString(16)}z"
    L18: codePoints.size == 1 → unigram，否则 bigram
    L23: 查询串 prefix/postfix 为双引号

**已知边界**（不是等价保证）：Kotlin 用 `Locale.ROOT` 小写化，Python 用 `str.lower()`。
两者对绝大多数字符一致，但如 `İ`(U+0130) 这类会产生不同结果 —— 本仓测试向量不涉及。
若要完全等价，需用 ICU 规则；本关卡的价值在于**字段序列与连接形式**已被源码确认。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
TESTS = REPO_ROOT / "ios/YuNianTests/MessageSearchTokenizerTests.swift"


def python_index(text: str) -> str:
    if not text:
        return ""
    # ⚠️ Kotlin 侧先做大小写归一化（测试 `testCaseIsNormalized` 锁住的行为）：
    # 本关卡第一版漏了它，于是 "Hello" 被算成 u48z u65z…（大写 H=0x48），
    # 而测试期望 u68z u65z…（小写 h=0x68）。
    # 这是本关卡证明自身有效的第一个证据 —— 它抓到了我的模型与 Kotlin 不一致。
    cps = [ord(c) for c in text.lower()]
    uni = [f"u{cp:x}z" for cp in cps]
    bi = [f"b{cps[i]:x}x{cps[i + 1]:x}z" for i in range(len(cps) - 1)]
    return " ".join(uni + bi)


def python_match_query(text: str) -> str | None:
    """返回 Kotlin `matchQuery` 的等价结果；不查时返回 nil。"""
    if not text or text.isspace():
        return None
    cps = [ord(c) for c in text.lower()]
    if len(cps) >= 2:
        bi = [f"b{cps[i]:x}x{cps[i + 1]:x}z" for i in range(len(cps) - 1)]
        return '"' + " ".join(bi) + '"'
    return f'"u{cps[0]:x}z"'


def main() -> int:
    text = TESTS.read_text(encoding="utf-8")

    # Swift 侧的字面量需要反转义（\u{1F642} 等形式），本仓用例只涉及
    # 空串、普通 ASCII、中文、emoji 代理对与常见转义。
    def swift_literal(raw: str) -> str:
        """还原 Swift 字符串字面量：\\uXXXX、\\u{XXXXX}、\\"、\\\\、\\n、\\t。"""
        def rep(m: re.Match) -> str:
            body = m.group(1)
            if body.startswith("{"):
                return chr(int(body[1:-1], 16))
            if len(body) == 4:
                return chr(int(body, 16))
            # 非 4 位（如 {1F642} 已处理）——兜底按 hex 解
            return chr(int(body.strip("{}"), 16))
        s = re.sub(r"\\u(\{[0-9a-fA-F]+\}|[0-9a-fA-F]{4})", rep, raw)
        s = s.replace('\\"', '"').replace("\\\\", "\\")
        s = s.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r")
        return s

    # 索引向量：("input", "expected")
    idx_pat = re.compile(r'\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)')
    m = re.search(r"func testIndexTokensGoldenVectors\(\)\s*\{(.*?)\n    \}", text, re.S)
    if not m:
        print("[FAIL] 找不到 testIndexTokensGoldenVectors")
        return 1
    idx_cases = idx_pat.findall(m.group(1))

    m = re.search(r"func testMatchQueryGoldenVectors\(\)\s*\{(.*?)\n    \}", text, re.S)
    if not m:
        print("[FAIL] 找不到 testMatchQueryGoldenVectors")
        return 1
    q_cases = idx_pat.findall(m.group(1))

    problems: list[str] = []
    for raw_in, raw_exp in idx_cases:
        src = swift_literal(raw_in)
        got = python_index(src)
        # Swift 期望串可能由 "..." + "..." 拼接，这里去掉拼接符与空白
        exp = re.sub(r'"\s*\+\s*"', "", raw_exp)
        exp = swift_literal(exp)
        if got != exp:
            problems.append(f"indexTokens({src!r})\n      测试: {exp!r}\n      现算: {got!r}")

    for raw_in, raw_exp in q_cases:
        src = swift_literal(raw_in)
        got = python_match_query(src)
        exp = swift_literal(raw_exp) if raw_exp else None
        # nil 在 Swift 侧写作 nil（不是字符串），此处 expected 为空串表示 nil
        if raw_exp == "nil" or raw_exp == "":
            exp = None
        if got != exp:
            problems.append(f"matchQuery({src!r})\n      测试: {exp!r}\n      现算: {got!r}")

    if problems:
        print(f"[FAIL] 分词向量与 Kotlin 算法不一致，{len(problems)} 处：")
        for p in problems:
            print("  -", p)
        print("\n处理：若是 Kotlin 侧算法变更 → 更新测试向量与本脚本的模型；")
        print("      否则是向量当初推错 → 修正测试。")
        return 1

    print(f"分词向量与 Python 模型一致（索引 {len(idx_cases)} + 查询 {len(q_cases)} 个 case）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
