package com.yunian.ai.feature.qqbot.data

import com.yunian.ai.feature.qqbot.data.model.QQMessageType
import com.yunian.ai.feature.qqbot.data.model.QQProactiveTarget
import com.yunian.ai.feature.qqbot.data.model.SendTextRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 主动发送请求体的**纯函数**构造与序列化（无 Android 依赖，可在纯 JVM 单测里直接断言）。
 *
 * 单独成文件的理由：请求体形状是「主动发送」这条通路的**唯一协议契约**，
 * 必须能被单测直接钉住（`msg_id` 必须缺席），而 [QQBotMessageRepository] 依赖
 * Android Context / Retrofit，纯 JVM 单测构造不出来。
 *
 * ## 主动 vs 被动：回复锚点字段必须全部缺席
 *
 * 主动消息只发送 `content + msg_type`。`msg_id`、`msg_seq` 与
 * `message_reference` 都属于被动回复/引用语义；无锚点时发送 null 或全局序号会被 QQ
 * 判为非法请求（真机返回 HTTP 400）。
 *
 * 注意：kotlinx.serialization 的 `explicitNulls` 默认是 **true**，不是 false。
 * 因此必须显式配置 `explicitNulls = false`，才能保证 null 字段整个缺席。
 */
object QQBotProactiveRequests {

    /**
     * 序列化配置：与 [com.yunian.ai.feature.qqbot.data.network.QQBotApiClient] 保持
     * 同一组开关（`ignoreUnknownKeys` + `encodeDefaults`），`explicitNulls` 一律缺省
     * （= true 的省略行为），确保「null 即缺席」。
     */
    val JSON: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /**
     * 构造主动发送的文本请求体（**刻意缺省 `msgId`**）。
     *
     * @param target 目标；[QQProactiveTarget.id] 为空即明确失败（不猜目标）。
     * @param text 文本；空白即明确失败。
     * @throws IllegalArgumentException 目标为空 / 文本为空。
     */
    fun buildTextRequest(
        target: QQProactiveTarget,
        text: String,
    ): SendTextRequest {
        if (target.id.isBlank()) {
            throw IllegalArgumentException("主动发送目标为空：没有可用的 openid")
        }
        if (text.isBlank()) {
            throw IllegalArgumentException("主动发送内容为空")
        }
        return SendTextRequest(
            content = text,
            msgType = QQMessageType.TEXT.value,
            // 主动消息不携带 msgId / msgSeq / messageReference。
        )
    }

    /** 把请求体序列化成**真正会发出去的** JSON 文本（供单测逐字断言）。 */
    fun toJson(request: SendTextRequest): String = JSON.encodeToString(request)
}
