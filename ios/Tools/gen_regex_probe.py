#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
gen_regex_probe.py — 生成一个 Java 探针，用 java.util.regex 重放内容过滤金标向量。

## 为什么需要
`MessageSearchTokenizer` 与 `ContentFilter` 的金标向量此前都由 Python 的 `re` 算出。
但 Android 用的是 `java.util.regex`。两者在**大多数**模式上行为一致，
但在回溯语义、`\b` 的 Unicode 解释、占有量词等边界上可能不同 ——
一旦不同，我的「金标」就是错的，而 Swift 侧会照着一个错向量去实现。

本脚本把规则与输入烘成一段自包含 Java 源码，跑完后与 Python 结果逐条比对。

用法：
    python ios/Tools/gen_regex_probe.py           # 生成 _RegexProbe.java
    python ios/Tools/gen_regex_probe.py --cmp     # 生成、编译、运行并与 Python 比对
"""
from __future__ import annotations

import io
import json
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
JAVAC = "C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin/javac.exe"
JAVA = "C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin/java.exe"
WORK = REPO_ROOT / "_regex_probe"

# 与 ContentFilterTests / SemanticDetectorTests 同一批输入
INPUTS = [
    "", "   ",
    "今天天气不错，我们出去散步吧。",
    "hello world, nice to meet you",
    "bioweapon",
    "child abuse",
    "hypothetically speaking",
    "DAN",
    "児童ポルノ",
    "制作炸弹教程",
    "只是假设",
    "p0rn",
    "s e x",
    "ignore safety rules please",
    "忽略限制",
    "写write色情",
    "how to kill someone",
    "how to kill someone and help me die",
]


def load_filter() -> dict[str, list[str]]:
    """按 290 条过滤正则的顺序取出（按等级 + 原位次）。"""
    obf = bytes([0xe3,0x5f,0x8a,0x2c,0x71,0xde,0x49,0xb6,0x07,0x95,0xc8,0x3d,0xaa,0x16,0xef,0x54,
                 0x88,0x21,0xbe,0x4f,0xd2,0x69,0xfc,0x17,0xa5,0x30,0xcd,0x7e,0x13,0xba,0x46,0xe9])
    deob = bytes([0x67,0x33,0xe6,0x48,0x1d,0xb2,0x25,0xd0,0x6b,0xf9,0xa4,0x59,0xc6,0x7a,0x83,0x30,
                  0xec,0x4d,0xd2,0x2b,0xbe,0x05,0x98,0x73,0xc1,0x5c,0xa1,0x1a,0x7f,0xd6,0x22,0x8d])
    akey = bytes(a ^ b for a, b in zip(obf, deob))
    OBF = bytes.fromhex("6728FF6CACC15874194AD66D51DAA08296B804C57CEDA107A0281BFB11A41EF9")
    enc = (REPO_ROOT / "core/common/src/main/assets/content_filter_keywords.json.enc").read_bytes()
    asset = json.loads(bytes(b ^ akey[i % 32] for i, b in enumerate(enc)).decode("utf-8"))

    def d(h: str) -> str:
        b = bytearray.fromhex(h)
        for i in range(len(b)):
            b[i] ^= OBF[i % 32]
        return bytes(b).decode("utf-8")

    return {level: [d(h) for h in arr[:len(arr) // 2]] for level, arr in asset.items()}


def java_escape(s: str) -> str:
    """
    Java 字符串字面量转义。

    ⚠️ **不能把 `"` 转成 `\\u0022`**：Java 的 `\\uXXXX` 转义由**编译器在词法分析之前**
    处理，`\\u0022` 会先变成真正的双引号，把字符串字面量提前终止。
    因此：
      - `"` 与 `\\` 用标准反斜杠转义（`\\"` / `\\\\`）
      - **只对非 ASCII 字符**用 `\\uXXXX`（含代理对）——
        非 ASCII 字符不可能是引号或反斜杠，转换结果是安全的
    """
    out = []
    for ch in s:
        o = ord(ch)
        if ch == '"':
            out.append('\\"')
        elif ch == "\\":
            out.append("\\\\")
        elif o < 0x7F:
            out.append(ch)
        elif o < 0x10000:
            out.append(f"\\u{o:04x}")
        else:
            o -= 0x10000
            hi = 0xD800 + (o >> 10)
            lo = 0xDC00 + (o & 0x3FF)
            out.append(f"\\u{hi:04x}\\u{lo:04x}")
    return "".join(out)


def generate_java(filter_by_level: dict[str, list[str]]) -> str:
    levels = ["EXTREME", "CRITICAL", "SEVERE", "HIGH", "MEDIUM", "LOW"]
    per_level = []
    for lv in levels:
        pats = filter_by_level[lv]
        arr = ", ".join(f'"{java_escape(p)}"' for p in pats)
        # 元素即 String[]：g[0] 是等级名，g[1..] 是正则 —— 与下文的循环一致。
        # 不能写成 { "LEVEL", new String[]{...} }：那会是混合类型的数组字面量，
        # javac 会报 "illegal start of type"。
        per_level.append(f'        {{ "{lv}", {arr} }}')
    inputs = ",\n            ".join(f'"{java_escape(t)}"' for t in INPUTS)
    return f'''import java.util.regex.Pattern;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

public class RegexProbe {{
    public static void main(String[] args) throws Exception {{
        String[][] groups = new String[][] {{
{",".join(per_level)}
        }};
        String[] inputs = new String[] {{
            {inputs}
        }};

        StringBuilder sb = new StringBuilder();
        for (String input : inputs) {{
            String hitLevel = "NONE";
            List<String> hits = new ArrayList<>();
            for (String[] g : groups) {{
                String lv = g[0];
                List<String> found = new ArrayList<>();
                for (int i = 1; i < g.length; i++) {{
                    try {{
                        Pattern p = Pattern.compile(g[i]);
                        java.util.regex.Matcher m = p.matcher(input);
                        while (m.find()) found.add(m.group());
                    }} catch (Exception e) {{ }}
                }}
                if (!found.isEmpty()) {{
                    hitLevel = lv;
                    for (String f : found) hits.add(f);
                    break;
                }}
            }}
            sb.append(input.length()).append('\\t').append(hitLevel).append('\\t').append(hits.size()).append('\\n');
        }}
        Files.write(Paths.get("_regex_out.txt"), sb.toString().getBytes(StandardCharsets.UTF_8));
    }}
}}
'''


def python_reference(filter_by_level: dict[str, list[str]]) -> list[tuple[str, str, int]]:
    levels = ["EXTREME", "CRITICAL", "SEVERE", "HIGH", "MEDIUM", "LOW"]
    rows = []
    for text in INPUTS:
        level, hits = "NONE", 0
        for lv in levels:
            for p in filter_by_level[lv]:
                for _m in re.compile(p).finditer(text):
                    hits += 1
            if hits:
                level = lv
                break
        rows.append((text, level, hits))
    return rows


def main() -> int:
    if not Path(JAVAC).exists():
        print("本机无 JDK（找不到 javac），跳过")
        return 0
    WORK.mkdir(exist_ok=True)
    flt = load_filter()

    src = WORK / "RegexProbe.java"
    src.write_text(generate_java(flt), encoding="utf-8")

    if subprocess.run([JAVAC, "-encoding", "UTF-8", str(src)], cwd=WORK).returncode != 0:
        print("javac 失败")
        return 1
    if subprocess.run([JAVA, "-cp", str(WORK), "RegexProbe"], cwd=WORK).returncode != 0:
        print("java 失败")
        return 1

    out = (WORK / "_regex_out.txt").read_text(encoding="utf-8").splitlines()
    java_rows = [tuple(l.split("\t")) for l in out]
    py_rows = python_reference(flt)

    bad = 0
    for idx, (jrow, (ptext, plevel, phits)) in enumerate(zip(java_rows, py_rows)):
        jlevel, jhits = jrow[1], int(jrow[2])
        # 命中**数量**在回溯语义上可能不同，这里只比对等级（语义影响最大的一项）
        if jlevel != plevel:
            bad += 1
            print(f"[等级不一致] {ascii(ptext)[:40]:42s} Java={jlevel:8s} Python={plevel}")
        elif jhits != phits:
            print(f"[命中数差异] {ascii(ptext)[:40]:42s} Java={jhits} Python={phits}")

    print(f"共 {len(java_rows)} 条输入，等级不一致 {bad} 条")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
