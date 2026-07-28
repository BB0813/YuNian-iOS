package com.lianyu.ai.domain.wechat

import com.lianyu.ai.domain.ServiceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * S6：App → 微信主动同步入口（替代 Broadcast）。
 *
 * feature/chat、notification、groupchat 只调用本对象；
 * 实际投递由 [WeChatOutboundPort]（app 绑定）完成。
 */
object WeChatProactiveSync {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 异步入队出站请求；未注册端口时静默跳过（例如测试环境）。
     */
    fun enqueue(
        companionId: Long,
        messageId: Long,
        finalContent: String? = null,
    ) {
        if (companionId <= 0L) return
        scope.launch {
            runCatching {
                val port = ServiceRegistry.get(WeChatOutboundPort::class.java) ?: return@runCatching
                port.enqueue(
                    WeChatOutboundRequest(
                        companionId = companionId,
                        sourceMessageId = messageId.takeIf { it > 0L },
                        text = finalContent?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }
}
