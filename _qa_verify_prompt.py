# -*- coding: utf-8 -*-
"""
QA temporary verification script (V2).
Parses SummaryService.kt to check:
  1. Each *_PROMPT_TEMPLATE constant's format-specifier count
  2. Each corresponding .format(...) call's top-level argument count
  3. Whether templates contain a bare '%' (would throw on String.format)
Not part of the business source tree. Safe to delete.
"""
import re
import sys

PATH = r"H:\susu\core\network\src\main\java\com\yunian\ai\network\SummaryService.kt"

with open(PATH, encoding="utf-8") as f:
    src = f.read()

# ---------- 1. extract triple-quoted *_PROMPT_TEMPLATE constants ----------
templates = {}
for m in re.finditer(
    r'private const val (\w+_PROMPT_TEMPLATE)\s*=\s*"""(.*?)"""', src, re.S
):
    templates[m.group(1)] = m.group(2)

VALID_CONV = set("sdxXofeEgGcbn%")

def find_specs(body):
    """Return list of raw '%...' specifiers, treating '%%' as escaped."""
    out = []
    i, n = 0, len(body)
    while i < n:
        if body[i] == "%":
            if i + 1 < n and body[i + 1] == "%":
                out.append("%%")
                i += 2
                continue
            seg = "%"
            j = i + 1
            while j < n and body[j] in "-+ 0#123456789.":
                seg += body[j]
                j += 1
            if j < n:
                seg += body[j]
                j += 1
            out.append(seg)
            i = j
        else:
            i += 1
    return out

def split_top(inner):
    """Split a .format(...) argument list on top-level commas."""
    args, depth, buf, i = [], 0, "", 0
    while i < len(inner):
        c = inner[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == "," and depth == 0:
            args.append(buf)
            buf = ""
        else:
            buf += c
        i += 1
    if buf.strip():
        args.append(buf)
    return [a for a in args if a.strip()]

# ---------- 2. extract .format(...) call arguments ----------
calls = []
for m in re.finditer(r"(\w+_PROMPT_TEMPLATE)\.format\s*\(", src):
    open_paren = m.end() - 1
    depth, i = 0, open_paren
    while i < len(src):
        if src[i] == "(":
            depth += 1
        elif src[i] == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    inner = src[open_paren + 1:i]
    calls.append((m.group(1), split_top(inner), src[:m.start()].count("\n") + 1))

print("=" * 68)
print("V2: SummaryService.kt prompt-template format consistency")
print("=" * 68)

# line numbers of template declarations
tpl_lines = {}
for m in re.finditer(r'private const val (\w+_PROMPT_TEMPLATE)\s*=\s*"""', src):
    tpl_lines[m.group(1)] = src[: m.start()].count("\n") + 1

all_ok = True
for name, body in templates.items():
    specs = find_specs(body)
    s_count = sum(1 for s in specs if s.endswith("s"))
    bad = [s for s in specs if s[0] == "%" and s != "%%" and s[-1] not in VALID_CONV]
    esc = sum(1 for s in specs if s == "%%")
    print(f"\n[{name}]  (declared at line {tpl_lines.get(name, '?')})")
    print(f"  total specifiers = {len(specs)} | %s = {s_count} | %% escaped = {esc}")
    if bad:
        all_ok = False
        print(f"  !! BARE/INVALID '%' FOUND: {bad}  -> String.format will THROW")
    else:
        print("  bare '%' check: OK (no invalid % outside %% and %s)")

print("\n" + "-" * 68)
print("format(...) call sites:")
call_map = {}
for name, args, line in calls:
    call_map[name] = len(args)
    print(f"  line {line}: {name}.format({len(args)} args) -> {[a.strip()[:38] for a in args]}")

print("\n" + "-" * 68)
print("MATCH RESULT:")
for name, body in templates.items():
    specs = find_specs(body)
    s_count = sum(1 for s in specs if s.endswith("s"))
    argn = call_map.get(name)
    if argn is None:
        print(f"  [SKIP] {name}: no .format() call found (may be used elsewhere)")
        continue
    status = "OK" if s_count == argn else "MISMATCH !!"
    if s_count != argn:
        all_ok = False
    print(f"  {name}: template %s={s_count} vs format args={argn} -> {status}")

print("\nOVERALL:", "PASS" if all_ok else "FAIL")
sys.exit(0 if all_ok else 1)
