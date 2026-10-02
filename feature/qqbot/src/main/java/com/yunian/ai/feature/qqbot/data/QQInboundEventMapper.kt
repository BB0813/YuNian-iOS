package com.yunian.ai.feature.qqbot.data

import com.yunian.ai.feature.qqbot.data.model.QQInboundEvent
import com.yunian.ai.feature.qqbot.data.model.QQMessageEvent

/**
 * QQ 网关事件 → 内部入站事件的纯映射层（不依赖 Android / 网络，便于单测）。
 *
 * 事件来源参考官方文档「消息收发」：
 *  - [GROUP_AT_MESSAGE]   群@机器人消息：平台只在被 @ 时推送
 *  - [GROUP_FULL_MESSAGE] 群消息（全量模式）：开启「接收所有消息」后群内每条消息都推送
 *  - [C2C_MESSAGE]        QQ 单聊
 *  - [GUILD_MESSAGE]      频道消息
 *  - [DIRECT_MESSAGE]     频道私信
 *
 * 全量群消息模式下不能见消息就回：只在该条消息 @ 了机器人本身时才产出入站事件，
 * 其余消息直接丢弃——不进 AI 链路、不产生回复。
 *
 * **[MapOutcome] 存在的理由**：此前 `map()` 只返回 null，调用方无从区分
 * 「按设计丢弃」与「字段缺失导致丢弃」。群 @ 消息一旦因字段问题被丢弃，
 * 整条链路静默失效且无任何痕迹——这是不可接受的。现在每个丢弃分支都带原因，
 * 原因由本文件**唯一**产出，不会与真实判定漂移。
 */
internal object QQInboundEventMapper {

    const val READY = "READY"
    const val C2C_MESSAGE = "C2C_MESSAGE_CREATE"
    const val GROUP_AT_MESSAGE = "GROUP_AT_MESSAGE_CREATE"
    const val GROUP_FULL_MESSAGE = "GROUP_MESSAGE_CREATE"
    const val GUILD_MESSAGE = "GUILD_MESSAGE_CREATE"
    const val DIRECT_MESSAGE = "DIRECT_MESSAGE_CREATE"

    /** 需要进入业务链路的事件类型白名单。 */
    val SUPPORTED_EVENT_TYPES: Set<String> = setOf(
        C2C_MESSAGE,
        GROUP_AT_MESSAGE,
        GROUP_FULL_MESSAGE,
        GUILD_MESSAGE,
        DIRECT_MESSAGE,
    )

    fun supports(eventType: String): Boolean = eventType in SUPPORTED_EVENT_TYPES

    /**
     * 映射结果：要么产出事件，要么**带原因**地丢弃。
     *
     * [Dropped.reason] 是给人看的诊断文本，会被写进 logcat 与 QQ 通道的文件日志。
     */
    internal sealed class MapOutcome {
        data class Mapped(val event: QQInboundEvent) : MapOutcome()
        data class Dropped(val reason: String) : MapOutcome()
    }

    /**
     * 事件映射（保持既有签名，返回 null 表示未产出）。
     *
     * @param botOpenId 机器人自身 OpenID。全量群消息靠它判断是否被 @；
     *                  未知时一律不产出（fail-closed，宁可漏回也不刷屏）。
     */
    fun map(eventType: String, event: QQMessageEvent, botOpenId: String?): QQInboundEvent? =
        (outcome(eventType, event, botOpenId) as? MapOutcome.Mapped)?.event

    /**
     * 与 [map] 同一次判定，但**保留丢弃原因**，供调用方记录诊断。
     *
     * 调用方必须用这个函数（或 [map]）之一，不得自行复刻判定逻辑。
     */
    fun outcome(eventType: String, event: QQMessageEvent, botOpenId: String?): MapOutcome =
        when (eventType) {
            C2C_MESSAGE -> {
                val openid = event.author?.userOpenid?.takeIf { it.isNotBlank() }
                if (openid == null) {
                    MapOutcome.Dropped("c2c: author.user_openid 缺失")
                } else {
                    MapOutcome.Mapped(QQInboundEvent.C2CMessage(openid, event))
                }
            }

            // 平台保证该事件只在被 @ 时推送，无需再判定 mentions。
            GROUP_AT_MESSAGE -> groupAtMessage(event)

            // 全量模式：群内每条消息都会来，只认 @ 了机器人的那些。
            GROUP_FULL_MESSAGE -> when (val group = groupAtMessage(event)) {
                is MapOutcome.Dropped -> group
                is MapOutcome.Mapped ->
                    if (wasBotMentioned(event, botOpenId)) {
                        group
                    } else {
                        MapOutcome.Dropped("full-mode group: 未 @ 机器人" + botOpenIdHint(botOpenId))
                    }
            }

            GUILD_MESSAGE -> {
                val channelId = event.channelId?.takeIf { it.isNotBlank() }
                val authorId = event.author?.id?.takeIf { it.isNotBlank() }
                if (channelId == null) {
                    MapOutcome.Dropped("guild: channel_id 缺失")
                } else if (authorId == null) {
                    MapOutcome.Dropped("guild: author.id 缺失")
                } else {
                    MapOutcome.Mapped(QQInboundEvent.GuildMessage(channelId, event.guildId, authorId, event))
                }
            }

            DIRECT_MESSAGE -> {
                val guildId = event.guildId?.takeIf { it.isNotBlank() }
                val authorId = event.author?.id?.takeIf { it.isNotBlank() }
                if (guildId == null) {
                    MapOutcome.Dropped("dm: guild_id 缺失")
                } else if (authorId == null) {
                    MapOutcome.Dropped("dm: author.id 缺失")
                } else {
                    MapOutcome.Mapped(QQInboundEvent.DirectMessage(guildId, authorId, event))
                }
            }

            else -> MapOutcome.Dropped("未支持的事件类型: " + eventType)
        }

    /** 机器人本身是否在该消息的 @ 列表里。botOpenId 未知 → false。 */
    fun wasBotMentioned(event: QQMessageEvent, botOpenId: String?): Boolean {
        val selfId = botOpenId?.takeIf { it.isNotBlank() } ?: return false
        return event.mentions.orEmpty().any { it.id == selfId }
    }

    private fun botOpenIdHint(botOpenId: String?): String =
        if (botOpenId.isNullOrBlank()) "（botOpenId 未知，fail-closed）" else ""

    private fun groupAtMessage(event: QQMessageEvent): MapOutcome {
        val groupOpenid = event.groupOpenid?.takeIf { it.isNotBlank() }
            ?: return MapOutcome.Dropped("group: group_openid 缺失")
        val memberOpenid = event.author?.memberOpenid?.takeIf { it.isNotBlank() }
            ?: return MapOutcome.Dropped("group: author.member_openid 缺失")
        return MapOutcome.Mapped(QQInboundEvent.GroupAtMessage(groupOpenid, memberOpenid, event))
    }
}
