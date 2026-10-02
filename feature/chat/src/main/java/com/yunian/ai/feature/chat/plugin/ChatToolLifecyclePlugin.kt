package com.yunian.ai.feature.chat.plugin

import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.ToolFinishStatus
import com.yunian.ai.domain.plugin.ToolFinished
import com.yunian.ai.domain.plugin.ToolLifecycleEvents
import com.yunian.ai.domain.plugin.ToolStarted
import com.yunian.ai.domain.plugin.on

/** Chat 自己的工具生命周期订阅插件；不向 ToolHost 注入回调。 */
class ChatToolLifecyclePlugin(
    instanceId: String,
    private val subscriber: ChatToolLifecycleSubscriber,
) : LianYuPlugin {
    override val id: String = "$ID_PREFIX.$instanceId"
    override val name: String = "Chat tool lifecycle cards"
    override val kind: PluginKind = PluginKind.PIPELINE
    override val requires: Set<String> = emptySet()
    override val configSchema: String? = null

    override fun setup(ctx: PluginContext) {
        ctx.on(ToolLifecycleEvents.STARTED, subscriber::onStarted)
        ctx.on(ToolLifecycleEvents.FINISHED, subscriber::onFinished)
    }

    companion object {
        const val ID_PREFIX: String = "chat.tool-lifecycle-cards"
    }
}

/** 订阅插件与具体 ViewModel/卡片投影之间的 feature 内端口。 */
interface ChatToolLifecycleSubscriber {
    fun onStarted(event: ToolStarted)
    fun onFinished(event: ToolFinished)
}

/**
 * 一轮聊天的事件投影器。streamId 由本轮 ToolHost 随机构造，避免串台；
 * 输出只含工具名/状态/时间，不可能把参数或结果写进 TOOL_ACTIVITY。
 */
class ChatToolLifecycleProjection(
    private val streamId: String,
    private val onChanged: (List<ChatToolLifecycleItem>) -> Unit,
) : ChatToolLifecycleSubscriber {
    private val items = LinkedHashMap<String, ChatToolLifecycleItem>()

    @Synchronized
    override fun onStarted(event: ToolStarted) {
        if (event.streamId != streamId) return
        items[event.callId] = ChatToolLifecycleItem(
            id = event.callId,
            toolName = event.toolName,
            status = ChatToolLifecycleStatus.RUNNING,
            startedAtMs = event.startedAtMs,
        )
        onChanged(items.values.toList())
    }

    @Synchronized
    override fun onFinished(event: ToolFinished) {
        if (event.streamId != streamId) return
        val existing = items[event.callId]
        items[event.callId] = ChatToolLifecycleItem(
            id = event.callId,
            toolName = existing?.toolName ?: event.toolName,
            status = if (event.status == ToolFinishStatus.SUCCEEDED) {
                ChatToolLifecycleStatus.DONE
            } else {
                ChatToolLifecycleStatus.FAILED
            },
            startedAtMs = existing?.startedAtMs ?: event.startedAtMs,
        )
        onChanged(items.values.toList())
    }

    @Synchronized
    fun snapshot(): List<ChatToolLifecycleItem> = items.values.toList()
}

enum class ChatToolLifecycleStatus { RUNNING, DONE, FAILED }

data class ChatToolLifecycleItem(
    val id: String,
    val toolName: String,
    val status: ChatToolLifecycleStatus,
    val startedAtMs: Long,
)