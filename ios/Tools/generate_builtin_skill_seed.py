#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
generate_builtin_skill_seed.py — 从 Kotlin 源码提取内置聊天协议技能，生成 Swift 种子。

## 为什么需要（本轮发现的真实缺口）
Rust 的 L4 技能注入层要求 `agent_skills` 里存在
`builtin_chat_tool_protocol` 这一行，否则：
  - `skill.load_content(BUILTIN_CHAT_TOOL_SKILL_ID, ..)` 返回 None
  - 聊天协议注不进 system prompt
  - 模型看不到「必须用 emit_bubble 输出气泡」，工具调用积极度不足
  （Rust 注释原文：「模型若不自发 load_skill 就看不到…导致工具调用积极度不足」）

Android 由 `BuiltinChatSkillPlugin` → `AgentFacade.seedBuiltinChatToolSkill(app)`
完成播种；iOS 侧此前**没有任何等价物**。

本脚本把正文与 meta 从 Kotlin 源码程序化提取（不手抄），生成
`ios/YuNian/Data/Seed/BuiltinChatToolSkill.swift`。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
FACADE_KT = REPO_ROOT / "core/agent/src/main/kotlin/com/yunian/ai/agent/AgentFacade.kt"
OUT_FILE = REPO_ROOT / "ios/YuNian/Data/Seed/BuiltinChatToolSkill.swift"


def read(p: Path) -> str:
    if not p.exists():
        sys.exit(f"找不到 {p}")
    return p.read_text(encoding="utf-8")


def extract() -> tuple[str, dict]:
    src = read(FACADE_KT)

    # ── 正文：Kotlin 的三引号原始字符串 ──
    m = re.search(
        r'BUILTIN_CHAT_TOOL_SKILL_CONTENT:\s*String\s*=\s*"""(.*?)"""', src, re.S)
    if not m:
        sys.exit("未能提取 BUILTIN_CHAT_TOOL_SKILL_CONTENT")
    content = m.group(1)
    # Kotlin 原始字符串的 `.trimIndent()`：去掉共同最小缩进
    lines = content.split("\n")
    indents = [len(l) - len(l.lstrip()) for l in lines if l.strip()]
    common = min(indents) if indents else 0
    content = "\n".join(l[common:] if l.strip() else "" for l in lines).strip("\n")

    # ── version ──
    vm = re.search(r"BUILTIN_CHAT_TOOL_SKILL_VERSION\s*=\s*(\d+)", src)
    version = int(vm.group(1)) if vm else None
    if version is None:
        sys.exit("未能提取 BUILTIN_CHAT_TOOL_SKILL_VERSION")

    # ── meta：seedBuiltinChatToolSkill 里的 put(...) 序列 ──
    fn = re.search(r"fun seedBuiltinChatToolSkill\(.*?\n    \}", src, re.S)
    if not fn:
        sys.exit("未能定位 seedBuiltinChatToolSkill")
    body = fn.group(0)

    def grab(key: str) -> str | None:
        # ⚠️ 允许尾随逗号：Kotlin 的 put(k, v,) 合法（多行 put 常这样写）
        km = re.search(rf'put\(\s*"{key}"\s*,\s*"((?:[^"\\]|\\.)*)"\s*,?\s*\)', body)
        return km.group(1) if km else None

    name = grab("name")
    description = grab("description")
    category = grab("category") or "CHAT"
    tags = grab("tags") or ""

    # tools 是 put("tools", JSONArray(listOf(...)))
    tm = re.search(r'put\("tools",\s*org\.json\.JSONArray\(listOf\((.*?)\)\)\)', body)
    tools = re.findall(r'"([^"]+)"', tm.group(1)) if tm else []

    if not name or not description:
        sys.exit("name/description 提取失败")

    meta = {
        "skill_id": "builtin_chat_tool_protocol",
        "name": name,
        "description": description,
        "category": category,
        "tags": tags,
        "tools": tools,
        "enabled": True,
        "companion_id": None,
        "version": version,
    }
    return content, meta


def swift_string_literal(s: str) -> str:
    """转成 Swift 多行字符串字面量。"""
    if '"""' in s:
        sys.exit("正文含三引号，需改用其它转义方案")
    return '"""\n' + s + '\n"""'


def main() -> int:
    content, meta = extract()

    # meta.json 由 Android 的 JSONObject 组装；这里用等价 JSON（键序无关）
    meta_json = json.dumps(meta, ensure_ascii=False, indent=2)

    # 校验：正文必须以 frontmatter 之外的正文开头（Android 存的是纯正文，无 frontmatter）
    checks = []
    if content.lstrip().startswith("---"):
        checks.append("正文以 --- 开头（异常）")
    if len(content) < 500:
        checks.append(f"正文过短（{len(content)} 字符）")
    for needle in ["emit_bubble", "emit_segmented", "send_sticker"]:
        if needle not in content:
            checks.append(f"正文缺少工具名 {needle}")
    if meta["tools"] != ["emit_bubble", "emit_segmented", "send_sticker"]:
        checks.append(f"tools 与预期不符：{meta['tools']}")
    if checks:
        for c in checks:
            print("[FAIL]", c)
        return 1

    out = f'''import Foundation

/// 内置聊天工具协议技能种子 —— `builtin_chat_tool_protocol`。
///
/// ⚠️ **由 `Tools/generate_builtin_skill_seed.py` 从
/// `core/agent/.../AgentFacade.kt` 的 `BUILTIN_CHAT_TOOL_SKILL_CONTENT` 程序化提取，
/// 请勿手改。**
///
/// ## 为什么 iOS 必须显式播种
/// Rust 的 L4 技能注入层（`prompt_orchestrator.rs:218`）要求 `agent_skills` 里
/// 存在 `builtin_chat_tool_protocol`：
/// ```rust
/// if options.available_tools.iter().any(|t| t == "emit_bubble") {{
///     if let Some(content) = skill.load_content(BUILTIN_CHAT_TOOL_SKILL_ID.to_string(), ...)
/// ```
/// Android 由 `BuiltinChatSkillPlugin` → `AgentFacade.seedBuiltinChatToolSkill(app)`
/// 完成；iOS 侧此前没有等价物，导致聊天协议注不进 system prompt ——
/// 模型看不到「必须用 emit_bubble 输出气泡」，工具调用积极度不足（Rust 注释原文）。
///
/// `available_tools` 之所以含 `emit_bubble`：内置工具不受 `request.tools` 门控
/// （见 ChatSession.swift 的说明），所以这个条件在 iOS 上恒真。
enum BuiltinChatToolSkill {{

    /// 固定 ID，同时是 `agent_skills.skillId` 与 Rust 的幂等种子键。
    static let skillId = "builtin_chat_tool_protocol"

    /// Room 里的 version（Android `BUILTIN_CHAT_TOOL_SKILL_VERSION`）。
    static let version = {meta["version"]}

    /// 技能正文（与 Android 逐字一致）。
    static let content = {swift_string_literal(content)}

    /// `saveSkill(metaJson:content:)` 所需的 meta JSON。
    static let metaJson = """
{meta_json}
"""

    /// 播种（幂等）：写入文件 + 索引。返回是否成功。
    ///
    /// 与 Android 的 `seedBuiltinChatToolSkill` 等价：每次都调
    /// `saveSkill(meta, CONTENT)`（`saveSkill` 自身按 skillId 做 insert/update 分支）。
    @discardableResult
    static func seed(into stores: AgentStores) -> Bool {{
        stores.saveSkill(metaJson: metaJson, content: content) > 0
    }}
}}
'''

    OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    OUT_FILE.write_text(out, encoding="utf-8")

    print(f"已生成 {OUT_FILE.relative_to(REPO_ROOT)}")
    print(f"  正文 {len(content)} 字符 / {len(content.splitlines())} 行")
    print(f"  version {meta['version']} / tools {meta['tools']}")
    print(f"  标签 {len(meta['tags'].split(','))} 个 / 描述 {len(meta['description'])} 字符")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
