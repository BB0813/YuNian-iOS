#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
golden_content_filter.py — 为 Swift 的 ContentFilter / SemanticDetector 生成金标向量。

## 目的
`ContentFilter.checkKeywords` 的「按等级从高到低、第一个命中即返回」语义，
以及 `SemanticDetector` 的等级判定链，都容易被移植成「取最严重等级」而走样。
本脚本用 Python 忠实转写 Kotlin 逻辑，对一组输入算出期望结果，
供 `ios/YuNianTests/ContentFilterTests.swift` 断言。

## ⚠️ 关于正则引擎差异
Kotlin 用 java.util.regex，Swift 用 ICU（NSRegularExpression），Python 用 re。
三者在这些模式上行为一致（都是 `(?i)`、字符类、交替、`\s`），但**不是形式等价的保证**。
因此本脚本产出的向量是**期望值的参考**，最终以 Swift 侧实跑为准；
若个别用例因引擎差异不符，应在 Swift 测试里显式标注，而不是改这条向量去迁就实现。

用法：
    python ios/Tools/golden_content_filter.py            # 打印向量
    python ios/Tools/golden_content_filter.py --swift    # 输出 Swift 测试数组片段
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SEED_JSON = REPO_ROOT / "ios/YuNian/Resources/SecuritySeed.json"

LEVELS_ORDER = ["EXTREME", "CRITICAL", "SEVERE", "HIGH", "MEDIUM", "LOW"]
# ↑ 第 137 轮与 Kotlin 源码逐字核对（ContentFilter.kt:236-237 的显式数组），同序。

# 与 Kotlin checkKeywords 的逐条对应（第 137 轮从源码确认，非拟合测试向量）：
#   ContentFilter.kt:236-237  显式等级数组        ↔ LEVELS_ORDER
#   ContentFilter.kt:241-244  收集**全部**匹配      ↔ for m in p.finditer
#   ContentFilter.kt:246-248  首个有命中的等级即返回 ↔ 我的循环在 found 非空时返回
#   ContentFilter.kt:247      found.distinct()     ↔ 保序去重（L68-73）
#   ContentFilter.kt:251      无命中 → NONE        ↔ 返回 (False, "NONE", "", [])
#
# ⚠️ 第 137 轮此处标注「语义层仍自证」——**第 138 轮已闭合**：
# `verify_semantic_rules_fresh.py` 从 SemanticDetector.kt 抽出三张 map 的 21 条正则，
# 与本文件的 EVASION / INTENT / MULTILINGUAL 逐条比对并通过。
# 因此本文件三组规则现已**他证**（对上 Kotlin 源码），不再是自证。

ORDINAL = {name: i for i, name in enumerate(
    ["NONE", "LOW", "MEDIUM", "HIGH", "SEVERE", "CRITICAL", "EXTREME"])}

LEVEL_NAME = {
    "NONE": "正常", "LOW": "轻度违规", "MEDIUM": "中度违规", "HIGH": "高度违规",
    "SEVERE": "严重违规", "CRITICAL": "极严重违规", "EXTREME": "极端违规",
}


def load_seed() -> dict:
    return json.loads(SEED_JSON.read_text(encoding="utf-8"))


def compile_patterns(seed: dict) -> dict[str, list[re.Pattern]]:
    out: dict[str, list[re.Pattern]] = {}
    for level, patterns in seed["filterPatterns"].items():
        compiled = []
        for p in patterns:
            try:
                compiled.append(re.compile(p))
            except re.error:
                pass  # 与 Kotlin 一致：编译失败静默跳过
        out[level] = compiled
    return out


# ── ContentFilter.checkKeywords ─────────────────────────────────────────
def check_keywords(text: str, compiled: dict[str, list[re.Pattern]]):
    """返回 (violating, level, reason, matches) —— 与 check_blocking 同为 4 元组，便于合并取最高。"""
    for level in LEVELS_ORDER:
        found = []
        for p in compiled.get(level, []):
            for m in p.finditer(text):
                found.append(m.group(0))
        if found:
            # Kotlin: found.distinct() —— 保留首次出现顺序
            seen, uniq = set(), []
            for f in found:
                if f not in seen:
                    seen.add(f)
                    uniq.append(f)
            return True, level, f"检测到{LEVEL_NAME[level]}内容", uniq
    return False, "NONE", "", []


# ── SemanticDetector ───────────────────────────────────────────────────
EVASION = {
    "CHAR_SUBSTITUTION": [
        r"(?i)(s$x|f$ck|sh\!t|@ss|b\!tch|d\!ck)",
        r"(?i)(p0rn|pr0n|s3x|n00d|f4gg0t|r3t4rd)",
        r"(?i)(c\.h\.i\.l\.d|p\.e\.d\.o|j\.i\.h\.a\.d)",
        r"(?i)(\*sex\*|\*porn\*|\*fuck\*|\*rape\*)",
    ],
    "SPACE_INSERTION": [
        r"(?i)(s\s+e\s+x|p\s+o\s+r\s+n|f\s+u\s+c\s+k|k\s+i\s+l\s+l)",
        r"(?i)(j\s+a\s+i\s+l\s+b\s+r\s+e\s+a\s+k)",
    ],
}
INTENT = {
    "SEXUAL_INTENT": [
        r"(?i)(write\s+(a|me)\s+(erotic|porn|nude|sex\s*ual)\s*(story|scene))",
        r"(?i)(describe\s+(explicitly|graphically)\s+(sex|nude|porn))",
        r"(?i)(make\s+it\s+(more\s+)?(explicit|erotic|sexual))",
    ],
    "VIOLENT_INTENT": [
        r"(?i)(how\s+to\s+(kill|hurt|murder|torture|poison|bomb))",
        r"(?i)(help\s+me\s+(die|kill\s+myself|end\s+my\s+life|suicide))",
        r"(?i)(want\s+to\s+(die|kill\s+myself|hurt\s+someone))",
    ],
    "ILLEGAL_INTENT": [
        r"(?i)(how\s+can\s+I\s+(get\s+away\s+with|avoid\s+detection|hide\s+evidence))",
        r"(?i)(black\s+market|dark\s+web|deep\s+web)",
        r"(?i)(money\s+laundering|tax\s+evasion|fraud\s+scheme|identity\s+theft)",
    ],
}
MULTILINGUAL = {
    "CHINESE_ENGLISH_MIX": [
        r"(?i)(写\s*(write|generate|create).{0,10}(色情|porn|hentai|性爱))",
        r"(?i)(忽略\s*(ignore|disregard).{0,10}(安全规则|限制))",
        r"(?i)((儿童|幼女|loli|child).{0,10}(色情|porn|性爱|sex))",
        r"(?i)((炸弹|bomb|炸药).{0,10}(制造|make|build).{0,10}(教程|guide))",
    ],
    "PINYIN_DETECTION": [
        r"(se\s+qing|huang\s+pian|lian\s+tong|xing\s+ai)",
        r"(yue\s+yu|po\s+xian|jie\s+jia)",
    ],
}


def _compile_group(group):
    out = {}
    for cat, pats in group.items():
        out[cat] = [p for p in (re.compile(x) for x in pats) if p is not None]
    return out


COMPILED_EVASION = _compile_group(EVASION)
COMPILED_INTENT = _compile_group(INTENT)
COMPILED_MULTILINGUAL = _compile_group(MULTILINGUAL)


def detect_semantic(text: str) -> tuple[bool, str, str, list[str]]:
    if not text.strip():
        return False, "NONE", "", []
    lowered = text.lower()
    matches: list[str] = []

    for cat in sorted(COMPILED_EVASION):
        for p in COMPILED_EVASION[cat]:
            for m in p.finditer(lowered):
                matches.append(f"[EVASION:{cat}] {m.group(0)}")

    for cat in sorted(COMPILED_INTENT):
        n = sum(1 for p in COMPILED_INTENT[cat] if p.search(lowered))
        if n >= 2:
            matches.append(f"[INTENT:{cat}] 检测到{n}个意图匹配")

    for cat in sorted(COMPILED_MULTILINGUAL):
        for p in COMPILED_MULTILINGUAL[cat]:
            for m in p.finditer(lowered):
                matches.append(f"[MULTILINGUAL:{cat}] {m.group(0)}")

    if not matches:
        return False, "NONE", "", []
    distinct = sorted(set(matches))

    def any_contains(needles):
        return any(n.lower() in line.lower() for line in matches for n in needles)

    if any_contains(["CHILD", "PORNO", "PEDO"]):
        return True, "EXTREME", "检测到极端违规内容", distinct
    if any_contains(["BOMB", "TERROR", "ENCODING"]):
        return True, "CRITICAL", "检测到极严重违规内容", distinct
    if any("intent" in l.lower() and ("violent" in l.lower() or "illegal" in l.lower()) for l in matches):
        return True, "SEVERE", "检测到严重违规意图", distinct
    if any_contains(["MULTILINGUAL"]):
        return True, "MEDIUM", "检测到可疑多语言混合", distinct
    if any_contains(["INTENT"]):
        return True, "LOW", "检测到不明确意图", distinct
    return True, "LOW", "检测到轻微绕过尝试", distinct


def preprocess_and_detect(text: str):
    results = [detect_semantic(text)]
    no_spaces = re.sub(r"\s+", "", text)
    if no_spaces != text:
        results.append(detect_semantic(no_spaces))
    return [r for r in results if r[0]]


def check_blocking(text, compiled):
    if not text.strip():
        return False, "NONE", "", []
    results = [check_keywords(text, compiled)]
    sem = preprocess_and_detect(text)
    if sem:
        top = max(sem, key=lambda r: ORDINAL[r[1]])
        results.append((True, top[1], f"{top[2]} (预处理检测)", top[3]))
    violating = [r for r in results if r[0]]
    if not violating:
        return False, "NONE", "", []
    return max(violating, key=lambda r: ORDINAL[r[1]])


# ── 测试输入 ────────────────────────────────────────────────────────────
CASES = [
    ("", "空串"),
    ("   ", "纯空白"),
    ("今天天气不错，我们出去散步吧。", "正常中文"),
    ("hello world, nice to meet you", "正常英文"),
    # 英文违禁词应当命中（对应 Android 也命中）
    ("bioweapon", "CRITICAL 英文关键词"),
    ("child abuse", "EXTREME 英文关键词"),
    ("hypothetically speaking", "LOW 英文关键词"),
    ("DAN", "SEVERE 英文关键词"),
    # 中文/日文：因数据是双重编码乱码，**预期不命中**（与 Android 行为一致）
    ("児童ポルノ", "日文违禁词（预期不命中：数据为乱码）"),
    ("制作炸弹教程", "中文违禁词（预期不命中：数据为乱码）"),
    ("只是假设", "中文 LOW 词（预期不命中：数据为乱码）"),
    # SemanticDetector 路径
    ("p0rn", "字符替换绕过"),
    ("s e x", "空格插入绕过"),
    ("ignore safety rules please", "英文忽略规则（预期不命中：规则是中文混写）"),
    ("忽略限制", "中文忽略（预期不命中：规则需混英文）"),
    ("写write色情", "中英混写"),
    ("how to kill someone", "暴力意图（单条，不足 2 条）"),
    ("how to kill someone and help me die", "暴力意图（同组 ≥2 条）"),
]


def main() -> int:
    seed = load_seed()
    compiled = compile_patterns(seed)

    rows = []
    for text, note in CASES:
        kw = check_keywords(text, compiled)
        blk = check_blocking(text, compiled)
        sem = detect_semantic(text)
        rows.append({
            "text": text,
            "note": note,
            "keywords": {"violating": kw[0], "level": kw[1], "reason": kw[2], "matches": kw[3]},
            "blocking": {"violating": blk[0], "level": blk[1], "matches": blk[3]},
            "semantic": {"violating": sem[0], "level": sem[1], "reason": sem[2]},
        })

    if "--swift" in sys.argv:
        out = []
        for r in rows:
            lit = json.dumps(r["text"], ensure_ascii=True)
            out.append(
                f'        // {r["note"]}\n'
                f'        (text: {lit}, '
                f'blockingViolating: {"true" if r["blocking"]["violating"] else "false"}, '
                f'blockingLevel: .{r["blocking"]["level"].lower()}, '
                f'keywordLevel: .{r["keywords"]["level"].lower()}),'
            )
        print("\n".join(out))
        return 0

    print(f"过滤正则：{sum(len(v) for v in compiled.values())} 条")
    print()
    for r in rows:
        mark = "命中" if r["blocking"]["violating"] else "放行"
        print(f"[{mark}] {r['note']}")
        print(f"    输入      : {ascii(r['text'])}")
        print(f"    checkKeywords → {r['keywords']['level']}  {[ascii(m) for m in r['keywords']['matches'][:2]]}")
        print(f"    checkBlocking → {r['blocking']['level']}  {[ascii(m) for m in r['blocking']['matches'][:2]]}")
        if r["semantic"]["violating"]:
            print(f"    语义检测  → {r['semantic']['level']}  {ascii(r['semantic']['reason'])}")
        print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
