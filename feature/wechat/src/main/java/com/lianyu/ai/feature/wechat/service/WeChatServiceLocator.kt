package com.lianyu.ai.feature.wechat.service

import android.content.Context
import com.lianyu.ai.feature.wechat.data.WeChatChatBridge
import com.lianyu.ai.feature.wechat.data.WeChatMessageRepository
import com.lianyu.ai.feature.wechat.data.WeChatSdkClientManager
import com.lianyu.ai.feature.wechat.data.WeChatTokenStore

object WeChatServiceLocator {

    @Volatile
    private var tokenStore: WeChatTokenStore? = null

    @Volatile
    private var messageRepository: WeChatMessageRepository? = null

    @Volatile
    private var sdkClientManager: WeChatSdkClientManager? = null

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
        val repo = messageRepository(context)
        return WeChatChatBridge(context.applicationContext, repo)
    }

    fun sdkClientManager(context: Context): WeChatSdkClientManager {
        return sdkClientManager ?: synchronized(this) {
            sdkClientManager ?: WeChatSdkClientManager(tokenStore(context)).also {
                sdkClientManager = it
            }
        }
    }
}
