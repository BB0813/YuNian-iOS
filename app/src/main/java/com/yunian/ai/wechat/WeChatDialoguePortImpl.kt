package com.yunian.ai.wechat

import android.content.Context
import com.yunian.ai.domain.ChannelKeys
import com.yunian.ai.domain.DialogueCoordinator
import com.yunian.ai.domain.DialogueRequest
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.dialogue.DialogueOutputEvent
import com.yunian.ai.domain.wechat.WeChatContentKind
import com.yunian.ai.domain.wechat.WeChatDialoguePort
import com.yunian.ai.domain.wechat.WeChatDialogueRequest
import com.yunian.ai.domain.wechat.WeChatDialogueResult
import com.yunian.ai.domain.wechat.WeChatInboundMessage
import com.yunian.ai.feature.wechat.WeChatDebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 微信入站 → AI 回复的 app 侧适配（纯净桥接层）。
 *
 * 职责：解析 inbound → 调用统一 AI 对话中间层 [DialogueCoordinator] → 组装
 * [WeChatDialogueResult]（stickerLabels 供 Outbox 表情链路；结构化回合快照原样转发，
 * 供 P3-3c 的通道投影消费）。
 *
 * P3-3c 起 stickerLabels 的**来源**改变：`turn != null` 时只取
 * [DialogueOutputEvent.Sticker] 事件携带的标签，不再从 replyText 正则抠；
 * 只有回退路径（`turn == null`）才继续用 [extractStickerLabels] 保持改动前的行为。
 *
 * AI 回合、输入/输出安全检查、封禁判定、落库、记忆提取全部收敛在中间层
 * （`core:agent` 的 `AgentDialogueCoordinator` 实现），本类不再触碰任何 AI 管线 /
 * 仓库 / 安全组件。通道侧（CDN、Outbox、表情字节）仍由 `feature:wechat` Bridge 负责。
 */
class WeChatDialoguePortImpl(
    private val appContext: Context,
) : WeChatDialoguePort {

    private val dialogueCoordinator: DialogueCoordinator
        get() = ServiceRegistry.getOrThrow(DialogueCoordinator::class.java)

    override suspend fun generateReply(request: WeChatDialogueRequest): WeChatDialogueResult =
        withContext(Dispatchers.IO) {
            val companionId = request.companionId
            val inbound = request.inbound
            WeChatDebugLog.log("[Dialogue] generateReply START companion=$companionId")

            val imagePath = inbound.primaryImagePath()
            val text = inbound.primaryText?.trim().orEmpty()

            // 纯净桥接：AI 回合 / 安全过滤 / 封禁判定 / 落库 / 记忆全部收敛在中间层。
            val result = dialogueCoordinator.generateReply(
                DialogueRequest(
                    companionId = companionId,
                    text = if (imagePath == null) text else null,
                    imagePath = imagePath,
                    // 通道身份（**不参与授权判定**，已核实）：工具授权只按 (伴侣 × 工具) 折叠，
                    // 唯一折叠点是 AgentFacade.toolDefinitionsFor —— 它调
                    // CapabilityGrantStore.decisionsFor(companionId)，签名里根本没有通道参数。
                    // 同一条授权在 App 内单聊 / 群聊 / QQ / 微信上得到完全相同的结果，
                    // 通道之间的差异只剩「有没有确认界面」。
                    // 这里显式声明 WECHAT 是为了通道身份 / 观测（P3 通道插件化）：
                    // 拼错它不会改变任何工具的放行结果。
                    channelKey = ChannelKeys.WECHAT,
                )
            )

            WeChatDebugLog.log(
                "[Dialogue] generateReply done companion=$companionId blocked=${result.blocked} reply_len=${result.replyText.length}"
            )
            WeChatDialogueResult(
                replyText = result.replyText,
                // P3-3c：表情标签**从结构化快照的 Sticker 事件取**（turn != null）；
                // 只有回退路径（turn == null）才继续用改动前的 replyText 正则粗提取。
                stickerLabels = result.turn
                    ?.events
                    ?.filterIsInstance<DialogueOutputEvent.Sticker>()
                    ?.map { it.label }
                    ?.distinct()
                    ?: extractStickerLabels(result.replyText),
                blocked = result.blocked,
                assistantMessageId = result.assistantMessageId,
                assistantMessageIds = result.assistantMessageId?.let { listOf(it) } ?: emptyList(),
                // 结构化快照原样转发（尾部新增可选字段）：replyText 的取值逐字未变；
                // stickerLabels 自 P3-3c 起优先取自快照（见上），turn == null 时逐字不变。
                turn = result.turn,
            )
        }

    private fun WeChatInboundMessage.primaryImagePath(): String? {
        parts.firstOrNull { it.kind == WeChatContentKind.IMAGE }
            ?.media
            ?.localPath
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return parts.firstNotNullOfOrNull { part ->
            part.media?.localPath?.takeIf { path ->
                path.isNotBlank() && part.kind == WeChatContentKind.IMAGE
            }
        }
    }

    /**
     * 粗提取 `[标签]`；通道侧仍可用完整 replyText 做 StickerManager 匹配。
     *
     * P3-3c 起**只用于回退路径**（`turn == null`，回合没跑起来 / 失败 / 被安全拦截），
     * 保持改动前的取值逐字不变；有结构化快照时标签一律取自 Sticker 事件。
     */
    private fun extractStickerLabels(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val systemTags = setOf("语音", "图片", "视频", "文件", "位置", "红包", "转账")
        return Regex("\\[([^\\[\\]]+?)\\]")
            .findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() && it !in systemTags }
            .distinct()
            .toList()
    }
}
