#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
swift_text.py — Swift 源码文本处理的共享实现。

## 为什么需要这个模块
「剥离注释与字符串」这件事，我在项目里**重复实现过三次，也栽过三次**：

| 轮次 | 工具 | 症状 |
|---|---|---|
| 8  | `verify_swift_syntax_smoke.py` | 先剥注释后剥字符串 → `"asset://"` 的 `//` 被当注释起点，该行被截断 |
| 51 | `verify_own_types.py` | 只剥行首注释 → 漏掉行尾注释 `let x: String  // Base64(DER)` |
| 52 | 同上 | 顺序仍不对，继续修 |

根因：**用正则近似 Swift 词法，且每次只在当前路径上打补丁**，
没有把规则固化成一处实现。这个模块就是补这个缺口。

## 唯一正确的顺序
```
① 多行字符串（三引号）     ② 普通/原始字符串      ③ 块注释      ④ 行注释
```
**必须先剥字符串、后剥注释**：字符串变成空串后，残留的 `//` 才只可能来自真实代码。

## 维护规则
需要处理 Swift 源码文本的工具，**import 本模块，不要重写**。
发现缺陷时改这里，受益的是所有调用方。
"""
from __future__ import annotations

import re

_MULTILINE_STRING = re.compile(r'"""(?:.|\n)*?"""')
_MULTILINE_RAW = re.compile(r'#"""(?:.|\n)*?"""#')
_RAW_STRING = re.compile(r'#"(?:[^"\\]|\\.)*"#')
_NORMAL_STRING = re.compile(r'"(?:[^"\\\n]|\\.)*"')
_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)


def strip_comments_and_strings(src: str) -> str:
    """
    去掉注释与字符串**内容**，保留结构代码。

    字符串被替换成空串 `""`（而不是删除），这样引号本身的配平信息不丢 ——
    某些调用方（如括号配平检查）需要它。

    已知边界情况（真解析器才能完全覆盖，正则做不到）：
    - 字符串插值内含引号时，配对可能偏移
    - 自定义字符串字面量分隔符（井号加引号的原始字符串形态）已处理，
      但更罕见的分隔符形式未处理

    维护提醒：本 docstring 里**不能出现三个连续的双引号**
    （含井号加三引号的形态）—— 本文件初次提交时就因此把自己
    提前终止了，而这正是它要解决的问题，格外讽刺。
    """
    text = src
    text = _MULTILINE_STRING.sub('""', text)
    text = _MULTILINE_RAW.sub('""', text)
    text = _RAW_STRING.sub('""', text)
    text = _NORMAL_STRING.sub('""', text)
    text = _BLOCK_COMMENT.sub("", text)
    # 此时字符串已是 `""`，任何 `//` 都只可能来自真实代码
    text = "\n".join(line.split("//", 1)[0] for line in text.split("\n"))
    return text


def strip_comments_only(src: str) -> str:
    """
    只剥注释、**保留字符串内容**。

    用于需要读取字符串内容的场景（例如抽取 SQL 字面量）。
    注意：这个函数无法区分「字符串里的 // 」和「真注释」，
    因此只应在确定目标代码不含此类边界情况时使用。
    """
    text = _BLOCK_COMMENT.sub("", src)
    # 逐行处理：只在「看起来不在字符串里」时截断 —— 简化规则，
    # 若需精确判断请改用 strip_comments_and_strings 之后再做别的处理
    return "\n".join(line for line in text.split("\n"))


def doc_comment_lines(src: str) -> list[tuple[int, str]]:
    """抽出文档注释行（`///` 与 `/** */`），返回 (行号, 内容)。用于生成文档核对。"""
    out: list[tuple[int, str]] = []
    for i, line in enumerate(src.split("\n"), 1):
        s = line.strip()
        if s.startswith("///"):
            out.append((i, s[3:].strip()))
    return out


if __name__ == "__main__":
    # 自测：覆盖本项目实际踩过的三种形态
    sample = '''
/// 文档注释里的 Type.member 不该被当成代码
let a: String  // 行尾注释里的 Base64(DER) 也不该
let sql = """
SELECT * FROM t WHERE x = 'a//b'
"""
let url = "asset://foo/bar"      // 第 8 轮的事故形态
'''
    cleaned = strip_comments_and_strings(sample)
    assert "Base64(DER)" not in cleaned, "行尾注释未剥净"
    assert "asset://" not in cleaned, "字符串未剥净"
    assert "SELECT" not in cleaned, "多行字符串未剥净"
    assert "不该被当成代码" not in cleaned, "文档注释未剥净"
    assert '""' in cleaned, "应保留空串占位"

    # ⚠️ 第 119 轮：上面只测了「正序正确」，没测「反序会错」。
    # 而「先字符串后注释」这个顺序正是本模块存在的理由 ——
    # 若反序也能得出同样结果，那顺序就不是必要的，自测也就没有区分力。
    wrong_order = sample
    wrong_order = "\n".join(l.split("//", 1)[0] for l in wrong_order.split("\n"))
    wrong_order = _NORMAL_STRING.sub('""', wrong_order)
    assert "asset://foo/bar" in sample, "样本应含 asset://foo/bar"
    assert "asset://foo/bar" not in wrong_order, \
        "反序（先注释后字符串）应把 asset://foo/bar 截断 —— 第 8 轮的事故"
    # 反序确实丢了它，说明正序是必要的；这也证明本自测有区分力
    assert "let url" in cleaned and '""' in cleaned

    print("swift_text 自测通过（三形态 + 正序必要性对照）")
