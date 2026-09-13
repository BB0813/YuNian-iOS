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

    val stickerMutex: Mutex = Mutex()

    fun reset() {
        stickerSentThisTurn = false
        pendingSticker = null
        lastStickerMsgId = -1
        lastStickerContent = ""
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
