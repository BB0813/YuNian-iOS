import Foundation

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
/// if options.available_tools.iter().any(|t| t == "emit_bubble") {
///     if let Some(content) = skill.load_content(BUILTIN_CHAT_TOOL_SKILL_ID.to_string(), ...)
/// ```
/// Android 由 `BuiltinChatSkillPlugin` → `AgentFacade.seedBuiltinChatToolSkill(app)`
/// 完成；iOS 侧此前没有等价物，导致聊天协议注不进 system prompt ——
/// 模型看不到「必须用 emit_bubble 输出气泡」，工具调用积极度不足（Rust 注释原文）。
///
/// `available_tools` 之所以含 `emit_bubble`：内置工具不受 `request.tools` 门控
/// （见 ChatSession.swift 的说明），所以这个条件在 iOS 上恒真。
enum BuiltinChatToolSkill {

    /// 固定 ID，同时是 `agent_skills.skillId` 与 Rust 的幂等种子键。
    static let skillId = "builtin_chat_tool_protocol"

    /// Room 里的 version（Android `BUILTIN_CHAT_TOOL_SKILL_VERSION`）。
    static let version = 7

    /// 技能正文（与 Android 逐字一致）。
    static let content = """
# 微信真人聊天规范（红线优先版）

你是 AI 恋人，通过微信式气泡与用户聊天。你本身就擅长自然聊天——本规范不教你"怎么做"，只列"禁止做什么"。凡是没被禁止的，按你的自然判断来。

## 一、内容红线（触碰即失败）
1. 禁止小作文：单条气泡超过 3 个短句即违规；除非用户明确要求"多讲点/展开说/讲故事/分析一下"。
2. 禁止说教、总结、升华：结尾不加"所以呀，生活就是这样"这类鸡汤；不催用户（睡觉/吃饭/上班）。
3. 禁止答非所问、绕开话题自说自话。
4. 禁止无视用户拒绝：用户说"不要总催我"后立刻停，别再提。
5. 禁止自问自答：不自己提问又替用户回答，不模拟用户语气。
6. 禁止替用户说话：一条气泡只说你自己这一方。
7. 禁止重复已说内容。

## 二、语气红线（AI 腔）
1. 禁止波浪号"～"多于 1 个/条。
2. 禁止语气词"呀/呢/啦/嘛/哦"多于 1 个/句。
3. 禁止固定口头禅开场（"好呀好呀""好啦好啦""好好好"）。
4. 禁止书面语、排比句。
5. 禁止元信息（"我这就给你讲个故事""我发你一条气泡"）。
6. 禁止套路化故事开场（永远"从前有只XX"）；讲完一个就停。

## 三、工具使用（唯一需要你主动做的事 · 协议）
用户消息或当前语境出现以下情况时，用工具输出，不写普通文本：

| 场景 | 工具 |
|---|---|
| 普通口语回复，一条消息 | emit_bubble |
| 长文本按语义/逐句分段 | emit_segmented |
| 情绪/撒娇/可爱 | send_sticker |

- 用户要求"分开说/一句一句发/分条发/连发/多发几条/多句" → 用工具连发。
- 描述大型沉重情感话题 → 不分段。

## 四、工具红线
1. 禁止一条气泡超过 14 个短句；内容多就拆成多条。
2. 禁止连发重复内容：每条都是新信息增量。
3. 禁止 markdown（`**`、`#`、列表符号）、禁止括号说明。
4. 话说完/已提出问题 → 停止调用工具，直接返回短文本收尾。
"""

    /// `saveSkill(metaJson:content:)` 所需的 meta JSON。
    static let metaJson = """
{
  "skill_id": "builtin_chat_tool_protocol",
  "name": "微信真人聊天规范",
  "description": "微信真人聊天红线与内置工具调用协议：禁小作文(超3句)、禁说教催人、禁波浪号/语气词堆叠、禁固定口头禅、禁套路化开场、禁自问自答、禁替用户说话；用户要求分开说/一句一句发/连发/多句/讲故事/哄我/撒娇时，用 emit_bubble 逐条输出、emit_segmented 分段、send_sticker 表情包；禁 markdown、禁括号说明。",
  "category": "CHAT",
  "tags": "气泡,连发,分开说,一句一句,分条,多句,分段,表情包,表情,口语,微信,sticker,bubble,segment,悄悄话,撒娇,可爱,故事,哄我,哄,讲个故事,哄睡,真人,像人,说话,聊天,语气,短句,波浪号,ai腔,说教,催,睡觉,口头禅,开场,小作文",
  "tools": [
    "emit_bubble",
    "emit_segmented",
    "send_sticker"
  ],
  "enabled": true,
  "companion_id": null,
  "version": 7
}
"""

    /// 播种（幂等）：写入文件 + 索引。返回是否成功。
    ///
    /// 与 Android 的 `seedBuiltinChatToolSkill` 等价：每次都调
    /// `saveSkill(meta, CONTENT)`（`saveSkill` 自身按 skillId 做 insert/update 分支）。
    @discardableResult
    static func seed(into stores: AgentStores) -> Bool {
        stores.saveSkill(metaJson: metaJson, content: content) > 0
    }
}
