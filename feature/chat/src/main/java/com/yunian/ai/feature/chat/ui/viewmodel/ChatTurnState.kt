package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.common.StickerInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex

class ChatTurnState {

    var stickerSentThisTurn: Boolean = false

    @Volatile var pendingSticker: StickerInfo? = null

    var lastStickerMsgId: Long = -1

    var lastStickerContent: String = ""

    @Volatile var sendMessageJob: Job? = null

    /** 查重窗口所属的轮次 key（TurnId.value）；与当前轮不一致时重建窗口。 */
    var dedupTurnKey: String? = null

    /** 本轮已发气泡 + 最近历史 AI 消息的归一化查重窗口（随本轮逐条送达累积）。 */
    var dedupWindow: MutableList<String>? = null

    val stickerMutex: Mutex = Mutex()

    fun reset() {
        stickerSentThisTurn = false
        pendingSticker = null
        lastStickerMsgId = -1
        lastStickerContent = ""
        dedupTurnKey = null
        dedupWindow = null
    }

    fun cancelSendJob() {
        sendMessageJob?.cancel()
    }

    fun replaceSendJob(job: Job) {
        sendMessageJob?.cancel()
        sendMessageJob = job
    }

    fun flushStaleSticker(broadcast: (Long, String) -> Unit): Boolean {
        if (lastStickerMsgId > 0) {
            broadcast(lastStickerMsgId, lastStickerContent)
            lastStickerMsgId = -1
            lastStickerContent = ""
            return true
        }
        return false
    }

    fun enqueueStaleSticker(msgId: Long, content: String) {
        lastStickerMsgId = msgId
        lastStickerContent = content
    }
}
