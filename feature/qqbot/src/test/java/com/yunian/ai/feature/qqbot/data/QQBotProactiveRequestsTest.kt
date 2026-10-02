package com.yunian.ai.feature.qqbot.data

import com.yunian.ai.feature.qqbot.data.model.QQProactiveTarget
import com.yunian.ai.feature.qqbot.data.model.QQProactiveTargetKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class QQBotProactiveRequestsTest {
    @Test
    fun `主动群消息请求体只包含 content 和 msg_type`() {
        val request = QQBotProactiveRequests.buildTextRequest(
            QQProactiveTarget(QQProactiveTargetKind.GROUP, "group-openid"),
            "最终测试成功",
        )
        val json = QQBotProactiveRequests.JSON
            .parseToJsonElement(QQBotProactiveRequests.toJson(request))
            .jsonObject

        assertEquals(setOf("content", "msg_type"), json.keys)
        assertEquals("最终测试成功", json["content"]?.toString()?.trim('"'))
        assertEquals("0", json["msg_type"]?.toString())
        assertReplyFieldsAbsent(json)
    }

    @Test
    fun `主动单聊同样不得携带任何回复锚点`() {
        val request = QQBotProactiveRequests.buildTextRequest(
            QQProactiveTarget(QQProactiveTargetKind.USER, "user-openid"),
            "你好",
        )
        val json = QQBotProactiveRequests.JSON
            .parseToJsonElement(QQBotProactiveRequests.toJson(request))
            .jsonObject
        assertReplyFieldsAbsent(json)
    }

    @Test
    fun `空目标和空文本仍然 fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            QQBotProactiveRequests.buildTextRequest(
                QQProactiveTarget(QQProactiveTargetKind.GROUP, " "),
                "测试",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            QQBotProactiveRequests.buildTextRequest(
                QQProactiveTarget(QQProactiveTargetKind.GROUP, "group-openid"),
                " ",
            )
        }
    }

    private fun assertReplyFieldsAbsent(json: JsonObject) {
        assertFalse("主动消息不得发送 msg_id: $json", json.containsKey("msg_id"))
        assertFalse("主动消息不得发送 msg_seq: $json", json.containsKey("msg_seq"))
        assertFalse("主动消息不得发送 message_reference: $json", json.containsKey("message_reference"))
    }
}
