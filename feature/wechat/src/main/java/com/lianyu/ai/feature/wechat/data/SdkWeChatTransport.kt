package com.lianyu.ai.feature.wechat.data

import com.lianyu.ai.wechat.transport.WeChatTransportPort
import com.lianyu.ai.wechat.ilink.IlinkClientManager

/**
 * ilink SDK 出站适配（S1）。core:wechat 只依赖 [WeChatTransportPort]。
 */
class SdkWeChatTransport(
    private val sdkClientManager: IlinkClientManager,
    private val tokenStore: WeChatTokenStore,
) : WeChatTransportPort {

    override suspend fun sendText(
        toUserId: String,
        text: String,
        contextToken: String?,
    ): Result<Unit> = runCatching {
        tokenStore.getAccount() ?: error("未登录微信")
        sdkClientManager.sendText(toUserId, text, contextToken)
    }

    override suspend fun sendTextSegments(
        toUserId: String,
        segments: List<String>,
        contextToken: String?,
    ): Result<Unit> = runCatching {
        tokenStore.getAccount() ?: error("未登录微信")
        sdkClientManager.sendTextSegments(toUserId, segments, contextToken)
    }

    override suspend fun sendImage(
        toUserId: String,
        imageBytes: ByteArray,
        fileName: String,
        description: String?,
        contextToken: String?,
    ): Result<Unit> = runCatching {
        tokenStore.getAccount() ?: error("未登录微信")
        sdkClientManager.sendImage(toUserId, imageBytes, fileName, description, contextToken)
    }
}
