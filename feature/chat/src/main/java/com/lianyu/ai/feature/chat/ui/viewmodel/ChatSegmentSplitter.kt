package com.lianyu.ai.feature.chat.ui.viewmodel

import com.lianyu.ai.common.text.MessageSegmenter

/**
 * Chat 侧分段入口：统一委托 [MessageSegmenter]，避免两套规则漂移。
 */
internal object ChatSegmentSplitter {
    fun splitIntoSegments(text: String): List<String> {
        return MessageSegmenter.split(text, MessageSegmenter.SplitMode.SIMPLE)
    }
}
