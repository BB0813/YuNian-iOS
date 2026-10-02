package com.yunian.ai.feature.wechat.channel

/**
 * 一次微信出站的**收件人解析结果**（W1 修正后的收件人语义）。
 *
 * ## 为什么要有这一步
 *
 * 用户裁定（权威约束）：iLink 协议层**只允许给绑定的那个微信账号发消息**，
 * 「发给别人」和「群发」在协议上**根本不存在**。因此「[target] 为空时该发给谁」
 * **没有歧义**——就是那个绑定的微信用户。
 *
 * 旧实现把「target 为空」当成「需要显式收件人」直接失败，于是用户说
 * 「你给我微信发条消息」时模型省略 target → **必然失败**（本任务修的就是这个）。
 *
 * 但「**绝不静默地在多个候选里挑一个**」仍然是正确原则，因此收件人被显式建模成
 * 「要么**唯一确定**，要么**如实失败**」的两态，而不是一个可以随便兜底的可空字符串。
 *
 * @see WeChatChannelSender.resolveRecipient 唯一的解析入口
 */
sealed class WeChatRecipientResolution {

    /**
     * 唯一确定的收件人。
     *
     * [wechatUserId] 是**微信用户 id**：与 `IlinkAccount.ilinkUserId`、入站
     * `from_user_id`、出站 iLink `to_user_id` 同一 id 空间——可直接交给
     * [WeChatChannelSender.sendText]。
     */
    data class Resolved(val wechatUserId: String) : WeChatRecipientResolution()

    /**
     * 无法确定唯一收件人（未登录 / 绑定用户未知 / 候选不唯一 / 显式指定的不是绑定用户）。
     *
     * [reason] 必须具体、可读、可操作——它会一路回到模型与用户可见处。
     */
    data class Failed(val reason: String) : WeChatRecipientResolution()
}
