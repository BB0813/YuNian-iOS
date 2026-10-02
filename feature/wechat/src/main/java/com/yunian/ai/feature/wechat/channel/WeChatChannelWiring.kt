package com.yunian.ai.feature.wechat.channel

import android.content.Context
import com.yunian.ai.feature.wechat.data.SdkWeChatTransport
import com.yunian.ai.feature.wechat.service.WeChatServiceLocator
import kotlinx.coroutines.flow.map

/**
 * 生产接线：把**既有的**微信通路包装成通道插件用的出站接缝。
 *
 * 只包装、不接管：**不**启动轮询、**不**发心跳、**不**碰重连、**不**改任何消息时序
 * （保活链路由既有的 `WeChatPollingService` / `WeChatRestartWorker` 掌握，一行未动）。
 *
 * 复用 `WeChatServiceLocator` 的**同一批单例**（同一个 `IlinkClientManager`、
 * 同一个 `WeChatTokenStore`），与 outbox 通路看到的是同一份会话状态——
 * 另起一套会造出第二个 token 来源。
 *
 * `SdkWeChatTransport` 与 outbox 用的是**同一个类**，因此两条出站通路的
 * 协议细节（`ret/errcode`、`requireContextToken`）完全一致，不存在分叉实现。
 */
fun weChatChannelSender(context: Context): WeChatChannelSender {
    val tokenStore = WeChatServiceLocator.tokenStore(context)
    return WeChatChannelSender.fromTransport(
        transport = SdkWeChatTransport(
            sdkClientManager = WeChatServiceLocator.sdkClientManager(context),
            tokenStore = tokenStore,
        ),
        sessionStore = tokenStore,
        // 登录态的唯一事实来源：既有 accountFlow（本批只读订阅，不写、不触发任何网络）。
        loggedIn = tokenStore.accountFlow.map { it != null },
    )
}
