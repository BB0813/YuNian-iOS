#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
generate_security_seed.py — 从 Android 侧 SecurityDataSeeder 提取安全基线数据。

## 为什么必须程序化提取
`SecurityDataSeeder.kt`（473 行）里存的违禁词与正则**不是明文**，而是
**XOR 混淆的 hex 字符串**：

    OBF_KEY = "6728FF6C...A41EF9".chunked(2).map { it.toInt(16).toByte() }   // 32 字节
    fun d(enc: String): String {
        val b = enc.chunked(2).map { it.toInt(16).toByte() }
        for (i in b.indices) b[i] = (b[i] XOR OBF_KEY[i % 32])
        return String(b, UTF_8)
    }

手抄这份数据等于人工解码 300+ 条混淆串 —— 既不可行也必然出错。
本脚本复刻 `d()` 后批量解码，并把结果落成 JSON 资源。

## 产出
  ios/YuNian/Resources/SecuritySeed.json
    - keywords       : [{keyword, pattern, level, type, banDays}]   （对应 keywords 表）
    - quizQuestions  : [{question, options, correctIndex, category, difficulty}]

## 数据来源
  core/database/.../SecurityDataSeeder.kt
    - OBF_KEY / d()                     :18
    - 6 个 generate*Keywords()          :73,146,197,285,348,395
    - 实体构造（level / type / banDays）: 各函数末尾的 return 语句
    - generateSafetyQuestions()         :432
    - generateMentalHealthQuestions()   :459
  QuizQuestionEntity 的 difficulty 默认值 "MEDIUM"（构造函数未传）

用法：
    python ios/Tools/generate_security_seed.py
    python ios/Tools/generate_security_seed.py --check
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SEEDER_KT = REPO_ROOT / "core/database/src/main/java/com/yunian/ai/database/SecurityDataSeeder.kt"
FILTER_ASSET_ENC = REPO_ROOT / "core/common/src/main/assets/content_filter_keywords.json.enc"
ENCRYPTED_LOADER_KT = REPO_ROOT / "core/common/src/main/java/com/yunian/ai/common/EncryptedAssetLoader.kt"
OUT_FILE = REPO_ROOT / "ios/YuNian/Resources/SecuritySeed.json"

# 6 个等级函数的顺序与名称（顺序不影响结果，仅用于定位）
LEVEL_FUNCS = [
    "generateExtremeKeywords",
    "generateCriticalKeywords",
    "generateSevereKeywords",
    "generateHighKeywords",
    "generateMediumKeywords",
    "generateLowKeywords",
]

QUIZ_FUNCS = {
    "generateSafetyQuestions": "SAFETY",
    "generateMentalHealthQuestions": "MENTAL_HEALTH",
}


def read(p: Path) -> str:
    if not p.exists():
        sys.exit(f"找不到数据来源：{p}")
    return p.read_text(encoding="utf-8")


def parse_obf_key(src: str) -> bytes:
    m = re.search(r'OBF_KEY\s*=\s*"([0-9A-Fa-f]+)"', src)
    if not m:
        sys.exit("无法解析 OBF_KEY")
    hexstr = m.group(1)
    key = bytes.fromhex(hexstr)
    if len(key) != 32:
        sys.exit(f"OBF_KEY 应为 32 字节，实际 {len(key)}")
    return key


def make_decoder(key: bytes):
    """复刻 Kotlin 的 d(enc)。"""
    def d(enc: str) -> str:
        b = bytearray.fromhex(enc)
        for i in range(len(b)):
            b[i] = b[i] ^ key[i % 32]
        return bytes(b).decode("utf-8")
    return d


def decode_kotlin_string(lit: str) -> str:
    out, i = [], 0
    mapping = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\"}
    while i < len(lit):
        c = lit[i]
        if c == "\\" and i + 1 < len(lit):
            n = lit[i + 1]
            if n in mapping:
                out.append(mapping[n])
                i += 2
                continue
            if n == "u" and i + 5 < len(lit):
                out.append(chr(int(lit[i + 2 : i + 6], 16)))
                i += 6
                continue
        out.append(c)
        i += 1
    return "".join(out)


def func_block(src: str, name: str) -> str:
    """取出某个函数从签名到下一个 `private fun` 之间的正文。"""
    start = src.find(f"private fun {name}(")
    if start < 0:
        sys.exit(f"找不到函数 {name}")
    nxt = src.find("\n    private fun ", start + 1)
    return src[start : nxt if nxt > 0 else len(src)]


def extract_d_strings(block: str, container: str) -> list[str]:
    """提取 `val <container> = arrayOf(...)` / `setOf(...)` 里的所有 d("...") 调用。"""
    m = re.search(
        rf"val {container} = (?:arrayOf|setOf|listOf)\((.*?)\n\s*\)",
        block,
        re.S,
    )
    if not m:
        return []
    return re.findall(r'd\(\s*"([0-9A-Fa-f]+)"\s*\)', m.group(1))


def parse_level_meta(block: str) -> tuple[str, int]:
    """从 return 语句里取 level 与 banDays。"""
    m = re.search(r'level = "([A-Z]+)"', block)
    n = re.search(r"banDays = (\d+)", block)
    if not m or not n:
        sys.exit("无法从 return 语句解析 level / banDays")
    return m.group(1), int(n.group(1))


def parse_keywords(src: str, d) -> list[dict]:
    out: list[dict] = []
    for fname in LEVEL_FUNCS:
        block = func_block(src, fname)
        level, ban_days = parse_level_meta(block)

        patterns = extract_d_strings(block, "patterns")
        kwds = extract_d_strings(block, "kwds")
        if not patterns and not kwds:
            sys.exit(f"{fname} 未解析到任何数据")

        # 与 Kotlin 的 `patterns.map { ... } + kwds.map { ... }` 顺序一致
        for text in patterns:
            out.append({
                "keyword": d(text),
                "pattern": d(text),
                "level": level,
                "type": "PATTERN",
                "banDays": ban_days,
            })
        for text in kwds:
            out.append({
                "keyword": d(text),
                "pattern": None,
                "level": level,
                "type": "KEYWORD",
                "banDays": ban_days,
            })
    return out


# ── 过滤词表（加密资源）────────────────────────────────────────────────
def parse_asset_xor_key() -> bytes:
    """
    复刻 EncryptedAssetLoader 的运行时派生密钥：
        ByteArray(32) { i -> obfuscated[i] xor deobfuscate[i] }
    """
    src = read(ENCRYPTED_LOADER_KT)

    def take(name: str) -> bytes:
        m = re.search(rf"val {name} = byteArrayOf\((.*?)\n\s*\)", src, re.S)
        if not m:
            sys.exit(f"无法解析 {name}")
        vals = re.findall(r"(0x[0-9a-fA-F]+|\d+)\.toByte\(\)|(0x[0-9a-fA-F]+|\d+)(?=,|\s*$)",
                          m.group(1))
        out = []
        for a, b in vals:
            tok = a or b
            if tok:
                out.append(int(tok, 0) & 0xFF)
        if len(out) != 32:
            sys.exit(f"{name} 应为 32 字节，解析到 {len(out)}")
        return bytes(out)

    obf, deob = take("obfuscated"), take("deobfuscate")
    return bytes(a ^ b for a, b in zip(obf, deob))


def parse_filter_patterns(d) -> tuple[dict[str, list[str]], dict[str, list[str]]]:
    """
    解密 content_filter_keywords.json.enc，解出每个等级的 (patterns, keywords)。

    这是**两层 XOR**：资源级（EncryptedAssetLoader 的运行时密钥）
    + 字符串级（与 SecurityDataSeeder 相同的 OBF_KEY）。

    ⚠️ **每个等级的数组是「前半 patterns + 后半 kwds」的拼接**，
    必须按 `ContentFilter.loadFromAsset` 的方式从中间劈开：
        val mid = decrypted.size / 2
        val patterns = decrypted.subList(0, mid)      // → Pattern.compile 后用于匹配
        val kwds     = decrypted.subList(mid, size)   // → 交给 native AC 预筛
    误把整段都当 patterns 会导致大量纯关键词被当正则编译（且语义错误）。

    实测切分结果自洽：前半近乎都含 `(?i)(` 等元字符，后半 0 条含元字符。
    """
    if not FILTER_ASSET_ENC.exists():
        sys.exit(f"找不到过滤词表资源：{FILTER_ASSET_ENC}")
    key = parse_asset_xor_key()
    enc = FILTER_ASSET_ENC.read_bytes()
    plain = bytes(b ^ key[i % 32] for i, b in enumerate(enc))
    try:
        asset = json.loads(plain.decode("utf-8"))
    except Exception as e:
        sys.exit(f"过滤词表解密/解析失败：{e}")

    patterns: dict[str, list[str]] = {}
    keywords: dict[str, list[str]] = {}
    for level, arr in asset.items():
        decoded = [d(h) for h in arr]
        mid = len(decoded) // 2
        patterns[level] = decoded[:mid]
        keywords[level] = decoded[mid:]
    return patterns, keywords


def is_double_encoded(s: str) -> bool:
    """是否为「UTF-8 字节被按 Latin-1 解读」的双重编码产物。"""
    if all(ord(c) < 128 for c in s):
        return False
    if not any(0x80 <= ord(c) <= 0xFF for c in s):
        return False
    try:
        s.encode("latin-1").decode("utf-8")
        return True
    except (UnicodeEncodeError, UnicodeDecodeError):
        return False


def parse_quiz(src: str) -> list[dict]:
    out: list[dict] = []
    for fname, category in QUIZ_FUNCS.items():
        block = func_block(src, fname)
        # 逐条解析 QuizQuestionEntity(question = "...", options = "...", correctIndex = N, ...)
        for m in re.finditer(
            r"QuizQuestionEntity\(\s*"
            r'question = "((?:\\.|[^"\\])*)",\s*'
            r'options = "((?:\\.|[^"\\])*)",\s*'
            r"correctIndex = (\d+)",
            block,
        ):
            out.append({
                "question": decode_kotlin_string(m.group(1)),
                "options": decode_kotlin_string(m.group(2)),
                "correctIndex": int(m.group(3)),
                "category": category,
                # QuizQuestionEntity 的 difficulty 有默认值 "MEDIUM"，构造函数未传
                "difficulty": "MEDIUM",
            })
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    src = read(SEEDER_KT)
    key = parse_obf_key(src)
    d = make_decoder(key)

    keywords = parse_keywords(src, d)
    quiz = parse_quiz(src)
    filter_patterns, filter_keywords = parse_filter_patterns(d)

    # 双重编码诊断：中文/日文条目在 Android 侧就是乱码，这里如实统计并如实保留。
    # 详见输出文件里的 _diagnostics 与 ios/README.md 的说明。
    def mojibake(items: list[str]) -> int:
        return sum(1 for s in items if is_double_encoded(s))

    kw_moji = mojibake([k["keyword"] for k in keywords])
    filter_all = [p for arr in filter_patterns.values() for p in arr]
    filter_kw_all = [p for arr in filter_keywords.values() for p in arr]
    filter_moji = mojibake(filter_all)
    filter_kw_moji = mojibake(filter_kw_all)

    # 自检：数量与结构必须合理，否则宁可失败也不要产出半个基线
    errors = []
    if not keywords:
        errors.append("未解析到任何违禁词")
    if not quiz:
        errors.append("未解析到任何题目")

    by_level: dict[str, int] = {}
    for k in keywords:
        by_level[k["level"]] = by_level.get(k["level"], 0) + 1
    by_cat: dict[str, int] = {}
    for q in quiz:
        by_cat[q["category"]] = by_cat.get(q["category"], 0) + 1

    # 解码失败会得到替换字符或控制字符，提前拦下
    bad = [k["keyword"] for k in keywords if "\ufffd" in k["keyword"] or any(ord(c) < 9 for c in k["keyword"])]
    if bad:
        errors.append(f"{len(bad)} 条解码结果含非法字符，示例：{bad[:3]}")
    bad_q = [q["question"] for q in quiz if "\ufffd" in q["question"]]
    if bad_q:
        errors.append(f"{len(bad_q)} 条题目解码异常")
    for k in keywords:
        if k["type"] == "PATTERN":
            try:
                re.compile(k["pattern"])
            except re.error as e:
                errors.append(f"正则无法编译（{k['level']}）：{k['pattern'][:40]}… → {e}")
                break
    if not {k["type"] for k in keywords} == {"PATTERN", "KEYWORD"}:
        errors.append("应同时存在 PATTERN 与 KEYWORD 两类")

    # 过滤词表：前半必须是可编译的正则，后半必须不含正则元字符（切分正确性）
    bad_pat = []
    for arr in filter_patterns.values():
        for p in arr:
            try:
                re.compile(p)
            except re.error as e:
                bad_pat.append((p[:40], str(e)))
    if bad_pat:
        errors.append(f"{len(bad_pat)} 条过滤正则无法编译，示例：{bad_pat[:2]}")
    # 过滤词表切分正确性判据：
    # 用「以 ( 开头」或「含 (?i)」作为正则的无歧义标志。
    # 不要用「是否含正则元字符」——纯关键词本来就可能含 + 之类的字符（实测有 "r18+"）。
    leaked = [s for s in filter_kw_all if s.startswith("(") or "(?i)" in s]
    if leaked:
        errors.append(
            f"过滤关键词里有 {len(leaked)} 条带正则标志（以(开头或含(?i)），"
            f"说明「前半 patterns / 后半 kwds」的切分可能不对：{leaked[:3]}"
        )

    if errors:
        for e in errors:
            print("  [FAIL]", e)
        return 1

    payload = {
        "_generated": "由 ios/Tools/generate_security_seed.py 从 SecurityDataSeeder.kt 与 content_filter_keywords.json.enc 生成，请勿手改",
        "_source": [
            "core/database/src/main/java/com/yunian/ai/database/SecurityDataSeeder.kt",
            "core/common/src/main/assets/content_filter_keywords.json.enc",
            "core/common/src/main/java/com/yunian/ai/common/EncryptedAssetLoader.kt",
        ],
        "_diagnostics": {
            "note": (
                "keywords 与 filterPatterns 中的非 ASCII 条目在 Android 侧即为"
                "「UTF-8 字节被按 Latin-1 解读」的双重编码乱码。本文件**如实保留**该形态，"
                "以保证 iOS 与 Android 的过滤行为一致 —— 不做单方面「修复」，"
                "否则会出现两端过滤强度不同的分歧。"
            ),
            "doubleEncodedCounts": {
                "keywords": kw_moji,
                "keywordsTotal": len(keywords),
                "filterPatterns": filter_moji,
                "filterPatternsTotal": len(filter_all),
                "filterKeywords": filter_kw_moji,
                "filterKeywordsTotal": len(filter_kw_all),
            },
            "restoreRule": (
                "可逆：s.encode('latin-1').decode('utf-8')。"
                "两端应同时修改各自的 d() 等价实现，并同步回归测试。"
            ),
            "evidence": (
                "ContentFilter.checkBlocking 用 p.matcher(text) 直接匹配原始输入，"
                "全程未对输入做归一化/转码；因此乱码模式无法匹配真实中日文输入。"
            ),
        },
        "keywords": keywords,
        "quizQuestions": quiz,
        "filterPatterns": filter_patterns,
        "filterKeywords": filter_keywords,
    }
    text = json.dumps(payload, ensure_ascii=False, indent=1, sort_keys=False) + "\n"

    if args.check:
        if not OUT_FILE.exists():
            sys.exit(f"--check 失败：{OUT_FILE} 不存在")
        if OUT_FILE.read_text(encoding="utf-8") != text:
            sys.exit("--check 失败：生成结果与磁盘文件不一致，请重新运行生成器")
        print(f"--check 通过：{OUT_FILE.relative_to(REPO_ROOT)}")
        return 0

    OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    OUT_FILE.write_text(text, encoding="utf-8")
    print(f"已生成 {OUT_FILE.relative_to(REPO_ROOT)}")
    print(f"  违禁词 {len(keywords)} 条：" + " / ".join(f"{k} {v}" for k, v in sorted(by_level.items())))
    print(f"  题目 {len(quiz)} 条：" + " / ".join(f"{k} {v}" for k, v in sorted(by_cat.items())))
    print(f"  过滤词表 {len(filter_all)} 条正则：" + " / ".join(f"{k} {len(v)}" for k, v in filter_patterns.items()))
    print(f"  过滤关键词 {len(filter_kw_all)} 条：" + " / ".join(f"{k} {len(v)}" for k, v in filter_keywords.items()))
    print(f"  文件大小 {OUT_FILE.stat().st_size} 字节")
    print()
    # 只用 ASCII 前缀：Windows 控制台默认 GBK，emoji 会直接抛 UnicodeEncodeError
    print("  [WARN] 双重编码诊断（如实保留，未修复）：")
    print(f"       违禁词       {kw_moji}/{len(keywords)} 条为非 ASCII 且双重编码")
    print(f"       过滤正则     {filter_moji}/{len(filter_all)} 条")
    print(f"       过滤关键词   {filter_kw_moji}/{len(filter_kw_all)} 条")
    print("       原因：这些条目在 Android 侧即为乱码，过滤时无法匹配真实中日文输入。")
    print("       本文件保持与 Android 一致的形态；是否修复需两端同步决定。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
