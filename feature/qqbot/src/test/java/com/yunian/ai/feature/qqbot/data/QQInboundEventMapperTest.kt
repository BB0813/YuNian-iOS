package com.yunian.ai.feature.qqbot.data

import com.yunian.ai.feature.qqbot.data.model.QQInboundEvent
import com.yunian.ai.feature.qqbot.data.model.QQMessageAuthor
import com.yunian.ai.feature.qqbot.data.model.QQMessageEvent
import com.yunian.ai.feature.qqbot.data.model.QQUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖全量群消息模式（GROUP_MESSAGE_CREATE）的 @ 判定与事件白名单。
 * 事件语义依据官方文档：全量模式下群内每条消息都会推送，字段与群@消息完全一致。
 */
class QQInboundEventMapperTest {

    private val botOpenId = "BOT_OPENID_SELF"
    private val otherOpenId = "OPENID_OTHER"

    private fun groupEvent(
        id: String = "msg-1",
        groupOpenid: String? = "GROUP_1",
        memberOpenid: String? = "MEMBER_1",
        mentions: List<QQUser>? = null,
    ) = QQMessageEvent(
        id = id,
        groupOpenid = groupOpenid,
        author = QQMessageAuthor(memberOpenid = memberOpenid),
        content = "你好",
        mentions = mentions,
    )

    // ---- 白名单 ----

    @Test
    fun `whitelist accepts every documented message event`() {
        assertTrue(QQInboundEventMapper.supports(QQInboundEventMapper.C2C_MESSAGE))
        assertTrue(QQInboundEventMapper.supports(QQInboundEventMapper.GROUP_AT_MESSAGE))
        assertTrue(QQInboundEventMapper.supports(QQInboundEventMapper.GROUP_FULL_MESSAGE))
        assertTrue(QQInboundEventMapper.supports(QQInboundEventMapper.GUILD_MESSAGE))
        assertTrue(QQInboundEventMapper.supports(QQInboundEventMapper.DIRECT_MESSAGE))
    }

    @Test
    fun `whitelist rejects control and unhandled events`() {
        assertFalse(QQInboundEventMapper.supports(QQInboundEventMapper.READY))
        assertFalse(QQInboundEventMapper.supports("GROUP_ADD_ROBOT"))
        assertFalse(QQInboundEventMapper.supports(""))
    }

    @Test
    fun `unknown event type maps to null`() {
        assertNull(QQInboundEventMapper.map("GROUP_ADD_ROBOT", groupEvent(), botOpenId))
    }

    // ---- 群 @ 模式：平台只在被 @ 时推送，不再二次判定 ----

    @Test
    fun `group at message maps even when mentions is absent`() {
        val mapped = QQInboundEventMapper.map(
            QQInboundEventMapper.GROUP_AT_MESSAGE,
            groupEvent(mentions = null),
            botOpenId,
        )
        assertEquals(
            QQInboundEvent.GroupAtMessage("GROUP_1", "MEMBER_1", groupEvent(mentions = null)),
            mapped,
        )
    }

    @Test
    fun `group at message maps even when bot openid is unknown`() {
        val mapped = QQInboundEventMapper.map(
            QQInboundEventMapper.GROUP_AT_MESSAGE,
            groupEvent(),
            botOpenId = null,
        )
        assertTrue(mapped is QQInboundEvent.GroupAtMessage)
    }

    // ---- 全量模式：只回 @ 了机器人的那些 ----

    @Test
    fun `full mode maps when bot itself is mentioned`() {
        val mapped = QQInboundEventMapper.map(
            QQInboundEventMapper.GROUP_FULL_MESSAGE,
            groupEvent(mentions = listOf(QQUser(id = botOpenId, username = "予念"))),
            botOpenId,
        )
        assertTrue(mapped is QQInboundEvent.GroupAtMessage)
    }

    @Test
    fun `full mode drops message without any mention`() {
        assertNull(
            QQInboundEventMapper.map(
                QQInboundEventMapper.GROUP_FULL_MESSAGE,
                groupEvent(mentions = emptyList()),
                botOpenId,
            ),
        )
        assertNull(
            QQInboundEventMapper.map(
                QQInboundEventMapper.GROUP_FULL_MESSAGE,
                groupEvent(mentions = null),
                botOpenId,
            ),
        )
    }

    @Test
    fun `full mode drops message that only mentions another user`() {
        assertNull(
            QQInboundEventMapper.map(
                QQInboundEventMapper.GROUP_FULL_MESSAGE,
                groupEvent(mentions = listOf(QQUser(id = otherOpenId))),
                botOpenId,
            ),
        )
    }

    @Test
    fun `full mode drops everything when bot openid is unknown`() {
        // fail-closed：判定不了是否被 @，就绝不回，避免在群里刷屏。
        val mentioned = groupEvent(mentions = listOf(QQUser(id = botOpenId)))
        assertNull(QQInboundEventMapper.map(QQInboundEventMapper.GROUP_FULL_MESSAGE, mentioned, null))
        assertNull(QQInboundEventMapper.map(QQInboundEventMapper.GROUP_FULL_MESSAGE, mentioned, ""))
        assertNull(QQInboundEventMapper.map(QQInboundEventMapper.GROUP_FULL_MESSAGE, mentioned, "   "))
    }

    @Test
    fun `wasBotMentioned ignores blank mention ids`() {
        val event = groupEvent(mentions = listOf(QQUser(id = null), QQUser(id = "")))
        assertFalse(QQInboundEventMapper.wasBotMentioned(event, botOpenId))
        assertTrue(
            QQInboundEventMapper.wasBotMentioned(
                groupEvent(mentions = listOf(QQUser(id = ""), QQUser(id = botOpenId))),
                botOpenId,
            ),
        )
    }

    // ---- 群消息字段缺失 ----

    @Test
    fun `group message without group openid is dropped`() {
        assertNull(
            QQInboundEventMapper.map(
                QQInboundEventMapper.GROUP_FULL_MESSAGE,
                groupEvent(groupOpenid = null, mentions = listOf(QQUser(id = botOpenId))),
                botOpenId,
            ),
        )
    }

    @Test
    fun `group message without member openid is dropped`() {
        assertNull(
            QQInboundEventMapper.map(
                QQInboundEventMapper.GROUP_AT_MESSAGE,
                groupEvent(memberOpenid = null),
                botOpenId,
            ),
        )
    }

    // ---- 其余场景未受影响 ----

    @Test
    fun `c2c message still maps`() {
        val event = QQMessageEvent(
            id = "c2c-1",
            author = QQMessageAuthor(userOpenid = "USER_1"),
            content = "在吗",
        )
        assertEquals(
            QQInboundEvent.C2CMessage("USER_1", event),
            QQInboundEventMapper.map(QQInboundEventMapper.C2C_MESSAGE, event, botOpenId),
        )
    }

    @Test
    fun `c2c message without user openid is dropped`() {
        val event = QQMessageEvent(id = "c2c-2", author = QQMessageAuthor(userOpenid = null))
        assertNull(QQInboundEventMapper.map(QQInboundEventMapper.C2C_MESSAGE, event, botOpenId))
    }

    @Test
    fun `guild and direct messages still map`() {
        val guild = QQMessageEvent(
            id = "g-1",
            channelId = "CH_1",
            guildId = "GUILD_1",
            author = QQMessageAuthor(id = "AUTHOR_1"),
        )
        assertEquals(
            QQInboundEvent.GuildMessage("CH_1", "GUILD_1", "AUTHOR_1", guild),
            QQInboundEventMapper.map(QQInboundEventMapper.GUILD_MESSAGE, guild, botOpenId),
        )

        val direct = QQMessageEvent(
            id = "d-1",
            guildId = "GUILD_2",
            author = QQMessageAuthor(id = "AUTHOR_2"),
        )
        assertEquals(
            QQInboundEvent.DirectMessage("GUILD_2", "AUTHOR_2", direct),
            QQInboundEventMapper.map(QQInboundEventMapper.DIRECT_MESSAGE, direct, botOpenId),
        )
    }

    // ---- 丢弃必须带原因（可观测性契约）----
    //
    // 背景：群 @ 分支此前在丢弃时一行日志都没有，群聊静默失效完全无法定位。
    // 这里锁定的不是「丢弃」本身（那是既有 fail-closed 设计，未改动），
    // 而是「丢弃必须能说出为什么」。

    private fun dropped(eventType: String, event: QQMessageEvent, bot: String? = botOpenId): String {
        val outcome = QQInboundEventMapper.outcome(eventType, event, bot)
        assertTrue("期望丢弃，实际=" + outcome, outcome is QQInboundEventMapper.MapOutcome.Dropped)
        return (outcome as QQInboundEventMapper.MapOutcome.Dropped).reason
    }

    @Test
    fun `drop reason names the missing group member openid`() {
        val reason = dropped(QQInboundEventMapper.GROUP_AT_MESSAGE, groupEvent(memberOpenid = null))
        assertTrue("原因必须点名缺失字段，实际=" + reason, reason.contains("member_openid"))
    }

    @Test
    fun `drop reason names the missing group openid`() {
        val reason = dropped(QQInboundEventMapper.GROUP_AT_MESSAGE, groupEvent(groupOpenid = null))
        assertTrue("原因必须点名缺失字段，实际=" + reason, reason.contains("group_openid"))
    }

    @Test
    fun `drop reason names the missing c2c user openid`() {
        val reason = dropped(
            QQInboundEventMapper.C2C_MESSAGE,
            QQMessageEvent(id = "c2c-x", author = QQMessageAuthor(userOpenid = null)),
        )
        assertTrue("原因必须点名缺失字段，实际=" + reason, reason.contains("user_openid"))
    }

    @Test
    fun `drop reason names the unsupported event type`() {
        val reason = dropped("GROUP_ADD_ROBOT", groupEvent())
        assertTrue("原因必须带上事件类型，实际=" + reason, reason.contains("GROUP_ADD_ROBOT"))
    }

    @Test
    fun `drop reason distinguishes full mode not-mentioned from field missing`() {
        val notMentioned = dropped(
            QQInboundEventMapper.GROUP_FULL_MESSAGE,
            groupEvent(mentions = emptyList()),
        )
        assertTrue(
            "未 @ 机器人不应被说成字段缺失，实际=" + notMentioned,
            !notMentioned.contains("member_openid") && !notMentioned.contains("group_openid"),
        )
    }

    @Test
    fun `outcome never drifts from map on any branch`() {
        val cases: List<Triple<String, QQMessageEvent, String?>> = listOf(
            Triple(QQInboundEventMapper.C2C_MESSAGE, QQMessageEvent(id = "1", author = QQMessageAuthor(userOpenid = "U")), botOpenId),
            Triple(QQInboundEventMapper.C2C_MESSAGE, QQMessageEvent(id = "2", author = QQMessageAuthor(userOpenid = null)), botOpenId),
            Triple(QQInboundEventMapper.GROUP_AT_MESSAGE, groupEvent(), botOpenId),
            Triple(QQInboundEventMapper.GROUP_AT_MESSAGE, groupEvent(memberOpenid = null), botOpenId),
            Triple(QQInboundEventMapper.GROUP_AT_MESSAGE, groupEvent(groupOpenid = null), botOpenId),
            Triple(QQInboundEventMapper.GROUP_FULL_MESSAGE, groupEvent(mentions = listOf(QQUser(id = botOpenId))), botOpenId),
            Triple(QQInboundEventMapper.GROUP_FULL_MESSAGE, groupEvent(mentions = emptyList()), botOpenId),
            Triple(QQInboundEventMapper.GROUP_FULL_MESSAGE, groupEvent(mentions = listOf(QQUser(id = botOpenId))), null),
            Triple(QQInboundEventMapper.GUILD_MESSAGE, QQMessageEvent(id = "3", channelId = "C", author = QQMessageAuthor(id = "A")), botOpenId),
            Triple(QQInboundEventMapper.GUILD_MESSAGE, QQMessageEvent(id = "4", channelId = null, author = QQMessageAuthor(id = "A")), botOpenId),
            Triple(QQInboundEventMapper.DIRECT_MESSAGE, QQMessageEvent(id = "5", guildId = "G", author = QQMessageAuthor(id = "A")), botOpenId),
            Triple(QQInboundEventMapper.DIRECT_MESSAGE, QQMessageEvent(id = "6", guildId = "G", author = QQMessageAuthor(id = null)), botOpenId),
            Triple("NOPE", groupEvent(), botOpenId),
        )
        for ((type, ev, bot) in cases) {
            val mapped = QQInboundEventMapper.map(type, ev, bot)
            when (val outcome = QQInboundEventMapper.outcome(type, ev, bot)) {
                is QQInboundEventMapper.MapOutcome.Mapped ->
                    assertEquals("type=" + type, outcome.event, mapped)
                is QQInboundEventMapper.MapOutcome.Dropped -> {
                    assertNull("type=" + type, mapped)
                    assertTrue("丢弃原因不得为空 type=" + type, outcome.reason.isNotBlank())
                }
            }
        }
    }
}
