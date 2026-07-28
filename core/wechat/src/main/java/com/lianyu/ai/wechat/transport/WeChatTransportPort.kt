package com.lianyu.ai.wechat.transport

/**
 * 出站传输端口（S1）。
 * feature 用 ilink SdkClientManager 实现；core:wechat 不依赖 SDK。
 */
interface WeChatTransportPort {
    suspend fun sendText(
        toUserId: String,
        text: String,
        contextToken: String? = null,
    ): Result<Unit>

    suspend fun sendTextSegments(
        toUserId: String,
        segments: List<String>,
        contextToken: String? = null,
    ): Result<Unit> = sendText(toUserId, segments.joinToString(""), contextToken)

    suspend fun sendImage(
        toUserId: String,
        imageBytes: ByteArray,
        fileName: String,
        description: String? = null,
        contextToken: String? = null,
    ): Result<Unit>
}
