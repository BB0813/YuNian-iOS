package com.lianyu.ai.feature.wechat.service

import android.content.Context
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.feature.wechat.data.SdkWeChatTransport
import com.lianyu.ai.feature.wechat.data.WeChatChatBridge
import com.lianyu.ai.feature.wechat.data.WeChatMessageRepository
import com.lianyu.ai.feature.wechat.data.WeChatStickerMaterializer
import com.lianyu.ai.feature.wechat.data.WeChatTokenStore
import com.lianyu.ai.wechat.inbox.WeChatInboxCoordinator
import com.lianyu.ai.wechat.ilink.IlinkClientManager
import com.lianyu.ai.wechat.outbox.WeChatOutboxCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

object WeChatServiceLocator {

    @Volatile
    private var tokenStore: WeChatTokenStore? = null

    @Volatile
    private var messageRepository: WeChatMessageRepository? = null

    @Volatile
    private var sdkClientManager: IlinkClientManager? = null

    // [R8 FIX] 缓存 chatBridge 单例：原每次 chatBridge() 返回新实例（各带 bridgeScope），
    // 用完不 close，每条消息泄漏一个 CoroutineScope。
    @Volatile
    private var chatBridge: WeChatChatBridge? = null

    @Volatile
    private var outboxCoordinator: WeChatOutboxCoordinator? = null

    @Volatile
    private var inboxCoordinator: WeChatInboxCoordinator? = null

    @Volatile
    private var channelScope: CoroutineScope? = null

    fun tokenStore(context: Context): WeChatTokenStore {
        return tokenStore ?: synchronized(this) {
            tokenStore ?: WeChatTokenStore(context.applicationContext).also {
                tokenStore = it
            }
        }
    }

    fun messageRepository(context: Context): WeChatMessageRepository {
        return messageRepository ?: synchronized(this) {
            messageRepository ?: run {
                val store = tokenStore(context)
                val manager = sdkClientManager(context)
                WeChatMessageRepository(context.applicationContext, manager, store).also {
                    messageRepository = it
                }
            }
        }
    }

    fun chatBridge(context: Context): WeChatChatBridge {
        // [R8 FIX] 双重检查锁定返回单例，不再每次新建
        return chatBridge ?: synchronized(this) {
            chatBridge ?: run {
                val repo = messageRepository(context)
                WeChatChatBridge(context.applicationContext, repo).also {
                    chatBridge = it
                }
            }
        }
    }

    /** S1：出站 Outbox（SIMPLE 分段 + 持久化 drain）。 */
    fun outboxCoordinator(context: Context): WeChatOutboxCoordinator {
        return outboxCoordinator ?: synchronized(this) {
            outboxCoordinator ?: run {
                val app = context.applicationContext
                val db = AppDatabase.getDatabase(app)
                val store = tokenStore(app)
                val transport = SdkWeChatTransport(sdkClientManager(app), store)
                WeChatOutboxCoordinator(
                    dao = db.weChatOutboxDao(),
                    transport = transport,
                    sessionStore = store,
                    managedMediaCacheDir = java.io.File(app.cacheDir, WeChatStickerMaterializer.CACHE_DIR),
                ).also { outboxCoordinator = it }
            }
        }
    }

    /** S2：入站去重 + per-user 串行。 */
    fun inboxCoordinator(context: Context): WeChatInboxCoordinator {
        return inboxCoordinator ?: synchronized(this) {
            inboxCoordinator ?: run {
                val app = context.applicationContext
                val db = AppDatabase.getDatabase(app)
                WeChatInboxCoordinator(
                    dedupeDao = db.weChatInboxDedupeDao(),
                    scope = channelScope(app),
                ).also { inboxCoordinator = it }
            }
        }
    }

    private fun channelScope(context: Context): CoroutineScope {
        // context 仅用于与其它工厂签名一致；scope 进程级
        return channelScope ?: synchronized(this) {
            channelScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also {
                channelScope = it
            }
        }
    }

    /**
     * [R8 FIX] 释放缓存的 bridge（关闭其内部 CoroutineScope）。
     * 在微信服务停止时调用，避免 scope 泄漏。
     */
    fun shutdown() {
        synchronized(this) {
            chatBridge?.close()
            chatBridge = null
            inboxCoordinator?.cancelAll()
            inboxCoordinator = null
            outboxCoordinator = null
            channelScope?.cancel()
            channelScope = null
            messageRepository?.destroy()
            messageRepository = null
            sdkClientManager = null
            tokenStore = null
            WeChatChannelRuntime.reset()
        }
    }

    fun sdkClientManager(context: Context): IlinkClientManager {
        return sdkClientManager ?: synchronized(this) {
            sdkClientManager ?: IlinkClientManager(tokenStore(context)).also {
                sdkClientManager = it
            }
        }
    }
}
