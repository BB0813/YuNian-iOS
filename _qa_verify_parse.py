# -*- coding: utf-8 -*-
"""
QA temporary verification script (Round 2, R2).
Character-by-character replica of Kotlin logic in MemoryScreen.kt:
  - String.removeLeadingSpeakerLabel()   (lines 787-795)
  - parseTempMemoryContent()             (lines 766-781)
Kotlin semantics replicated:
  * split(delimiter: String, limit = 2) -> literal delimiter, at most 2 parts,
    last part keeps the remainder; no match -> single-element list.
  * String.trim() -> drops chars where Char.isWhitespace() (incl U+3000).
  * String.ifEmpty { block } -> fires only when isEmpty().
Not part of the business tree. Safe to delete.
"""
import sys

# ---- Kotlin-compatible helpers -------------------------------------------
KOTLIN_WS = " \t\n\r\u000b\u000c\u3000\u00a0"

def k_trim(s):
    # Kotlin Char.isWhitespace() covers these (good enough for our inputs)
    return s.strip(KOTLIN_WS)

def k_split_literal(s, delim, limit):
    """Replicate Kotlin String.split(delimiter, limit)."""
    if limit <= 0:
        return s.split(delim)
    parts, start = [], 0
    while len(parts) < limit - 1:
        idx = s.find(delim, start)
        if idx == -1:
            break
        parts.append(s[start:idx])
        start = idx + len(delim)
    parts.append(s[start:])
    return parts

def k_index_of(s, sub):
    return s.find(sub)

def k_substring(s, start, end=None):
    return s[start:] if end is None else s[start:end]

def k_is_whitespace(ch):
    return ch in KOTLIN_WS or ch.isspace()

# ---- replica of removeLeadingSpeakerLabel() (MemoryScreen.kt:787-795) ----
def removeLeadingSpeakerLabel(s):
    sep = k_index_of(s, ": ")
    if sep <= 0:
        return s
    label = k_substring(s, 0, sep)
    valid = (1 <= len(label) <= 12) and all(
        (not k_is_whitespace(c)) and c != ":" for c in label
    )
    if not valid:
        return s
    return k_trim(k_substring(s, sep + 2))

# ---- replica of parseTempMemoryContent() (MemoryScreen.kt:766-781) --------
def parseTempMemoryContent(content):
    parts = k_split_literal(content, " | ", 2)
    if len(parts) != 2:
        return (k_trim(content), "")

    user = k_trim(parts[0][len("用户:"):] if parts[0].startswith("用户:") else parts[0])
    if user == "":
        user = k_trim(parts[0])

    ai_raw = k_trim(parts[1])
    ai_text = removeLeadingSpeakerLabel(ai_raw)
    if ai_text == "":
        ai_text = ai_raw

    return (user, ai_text)

# ---- test cases -----------------------------------------------------------
CASES = [
    (1, "用户: 今天好累 | 小梓: 那早点休息", ("今天好累", "那早点休息")),
    (2, "用户: x | AI: y", ("x", "y")),
    (3, "用户: 他说注意: 休息 | 小梓: 嗯", ("他说注意: 休息", "嗯")),
    (4, "用户: x | 小梓: 注意: 记得吃饭", None),
    (5, "用户: x | AI: ", None),
    (6, "用户: x", ("x", "")),
    (7, "用户: x | 小梓: y | 小梓: z", None),
    (8, "用户:   | 小梓: y", None),
]

print("=" * 78)
print("R2: parseTempMemoryContent() / removeLeadingSpeakerLabel() replica output")
print("=" * 78)
for idx, content, expected in CASES:
    got = parseTempMemoryContent(content)
    # rendered card preview: user -> "你: {user}", ai -> "TA: {ai}" (ai only if not blank)
    preview_u = "你: " + (got[0] if got[0] != "" else content)
    preview_a = ("TA: " + got[1]) if got[1] != "" else "(AI 气泡不渲染)"
    flag = ""
    if expected is not None:
        flag = "  [MATCH]" if got == expected else "  [MISMATCH vs task-expected %r]" % (expected,)
    print(f"\ncase {idx}: input = {content!r}")
    print(f"   -> parsed  = {got!r}{flag}")
    print(f"   -> card UI = {preview_u!r} / {preview_a!r}")

# extra: raw label extraction diagnostics for case 4
print("\n" + "-" * 78)
print("diagnostic case 4: first ': ' index in AI segment and stripped label")
seg = k_split_literal("用户: x | 小梓: 注意: 记得吃饭", " | ", 2)[1].strip()
si = seg.find(": ")
print(f"   AI segment={seg!r} first ': ' at {si} -> label={seg[:si]!r} (stripped once)")
print("=" * 78)
