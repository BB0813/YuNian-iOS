package com.yunian.ai.domain

/**
 * 一条**显式的**工具授权决定。
 *
 * ## 为什么需要它
 * Rust 侧唯一确认门判定为 `definition.category == ToolCategory::Commerce`
 * （agent-native/src/agent.rs），而 `category` 是 Kotlin **装配期**由
 * `AgentFacade.deriveToolCategory` 算出来的。因此 `AiTool.requiresConfirmation = true` 的工具在
 * **无确认界面**的通道（QQ / 微信）上只会被确认门拦下 → 通道侧 fail-closed 自动拒绝
 * （AgentConfirmGuard），用户永远拿不到结果。显式授权就是把这批「用户已经逐条同意过」的
 * (伴侣 × 工具) 组合在装配期放行。
 *
 * ## 为什么**不**带通道维度（P2-2d 重构）
 * 授权对象是**工具能力本身**，不是「某个通道里的某个工具」：
 * 用户在同一张清单上按工具决定放行 / 需要确认，Agent 集合内自由组合；
 * 通道只影响「确认界面有没有」（App 内弹确认卡，无确认 UI 的通道直接拒绝），
 * 不参与「这个工具是否已被授权」的判定。因此 [channelKey] 字段被移除，
 * 通道标识（`ChannelKeys` / `DialogueRequest.channelKey`）仅作为通道自身的身份保留。
 *
 * ## 语义
 * - [companionId] = `null` 表示**通配**（对所有伴侣生效）；具体值时只对该伴侣生效，
 *   且该伴侣的显式决定**优先于**通配决定（见 [CapabilityGrantStore.decisionsFor]）；
 * - [allowed] = `true`  → 该工具可直接执行（不需要确认）；
 * - [allowed] = `false` → 该工具需要确认（App 内弹确认卡；无确认 UI 的通道直接拒绝）；
 * - 未出现在存储里的工具走**工具自身默认**，见 [CapabilityDefaults]。
 *
 * 字段刻意保持扁平（无 Map / 集合字段），便于设置 UI 与持久化编码。
 */
data class CapabilityGrant(
    val companionId: Long?,
    val toolName: String,
    val allowed: Boolean,
)

/**
 * 「未显式决定的工具是否需要确认」的**唯一真相**（core:domain）。
 *
 * 存在的理由：同一条规则有两个消费者——装配期折叠（core:agent 的 `AgentFacade.deriveToolCategory`）
 * 与设置页开关（feature:settings 的 `CapabilityGrantBoard`）。若两边各写一份三元表达式，
 * 一旦某天有人只改一边，设置页显示的开关状态就会与「装配期实际会不会拦」静默漂移
 * （用户看到已放行、实际仍被确认门拦下）。收敛到 core:domain 后两边共用同一实现。
 *
 * 三档语义（**全量覆盖，无第四种情况**）：
 * - `explicit = true`  ⇒ **不需要**确认（显式允许压过工具自身的声明）；
 * - `explicit = false` ⇒ **需要**确认（显式禁止同样压过工具自身的声明：
 *   连本来不需要确认的安全工具，也能被用户显式改成「必须先确认」）；
 * - `explicit = null`  ⇒ 回到**工具自身默认**（[AiTool.requiresConfirmation]）。
 *
 * @param explicit 存储里针对 (伴侣, 工具) 的显式决定；`null` = 没有决定。
 * @param toolRequiresConfirmation 工具自身的声明（`AiTool.requiresConfirmation`）。
 */
object CapabilityDefaults {

    /** 未显式决定的工具是否需要确认 = 工具自身是否标记为需要确认。 */
    fun requiresConfirm(explicit: Boolean?, toolRequiresConfirmation: Boolean): Boolean =
        when (explicit) {
            true -> false            // 显式允许 ⇒ 不需要确认
            false -> true            // 显式禁止 ⇒ 需要确认
            null -> toolRequiresConfirmation
        }
}

/**
 * 工具授权存储（core:domain 只放契约；实现在 core:agent：`CapabilityGrantStoreImpl`）。
 *
 * 全部方法为 `suspend`：实现走磁盘 / KV 持久化，**不得**在主线程阻塞式读取。
 *
 * 容错约定（实现方必须遵守，否则 fail-closed 会被破坏）：
 * - **读**（[decisions] / [decisionsFor]）遇到「无记录 / 内容损坏 / 解析失败」时
 *   必须返回**空结果**而不是抛异常（丢弃坏数据 = 视为无决定 = 回到工具默认 = 保守）；
 * - **写**（[decide] / [clear]）失败可以抛出，由调用方（设置 UI）决定如何提示。
 */
interface CapabilityGrantStore {

    /** 全部显式决定（原始表，UI 在「全部伴侣」视角下直接读它）。 */
    suspend fun decisions(): List<CapabilityGrant>

    /** 某伴侣视角下的有效决定表：通配 + 该伴侣，**该伴侣的显式决定优先**。 */
    suspend fun decisionsFor(companionId: Long): Map<String, Boolean>

    /** 写入一条显式决定（同键覆盖）。 */
    suspend fun decide(grant: CapabilityGrant)

    /** 清除一条显式决定（该键回到默认）。 */
    suspend fun clear(companionId: Long?, toolName: String)
}

/**
 * 通道标识常量（**唯一来源**，禁止各处散落字符串字面量）。
 *
 * 用途说明（P2-2d 之后）：这些常量标识**通道自身的身份**
 * （[DialogueRequest.channelKey]、通道侧日志与观测），
 * **不再参与能力授权判定**——授权只按 (伴侣 × 工具) 命中。
 * 保留它们是为了 P3 的通道插件化：每个通道插件显式声明自己是谁。
 */
object ChannelKeys {

    /** QQ 机器人通道（`QQBotChatBridge`）。 */
    const val QQBOT: String = "qqbot"

    /** 微信通道（`WeChatDialoguePortImpl`）。 */
    const val WECHAT: String = "wechat"

    /** App 内单聊通道（`ChatViewModel`）。 */
    const val APP_CHAT: String = "app.chat"

    /** App 内群聊通道。 */
    const val APP_GROUPCHAT: String = "app.groupchat"

    /**
     * 未声明通道：[DialogueRequest.channelKey] 的默认值。
     *
     * 刻意**不**复用 [APP_CHAT]：调用方忘记声明通道时应当能被识别出来
     * （通道身份用于观测与 P3 插件化），不要冒充成某条真实通道。
     */
    const val UNSPECIFIED: String = "unspecified"
}
