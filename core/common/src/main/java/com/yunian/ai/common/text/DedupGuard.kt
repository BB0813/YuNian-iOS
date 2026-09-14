package com.yunian.ai.common.text

/**
 * 气泡查重守卫（单聊 / 群聊统一口径）。
 *
 * 归一化与判重规则与群聊 [GroupChatViewModel] 的既有实现逐字节对齐，
 * 从而保证「单聊补查重」不会引入与群聊不一致的判定口径。
 */
object DedupGuard {

    /** 比对时回看的最近 AI 消息条数。 */
    const val WINDOW_LAST_AI = 3

    /** 子串包含命中的最短长度门槛，避免「哈哈哈哈」类中文短句被误判为重复。 */
    private const val CONTAIN_MIN = 10

    /**
     * 查重规范化：剥 @、空白、标点、引号括号后小写，取前 40 字。
     *
     * 注意：正则与 `take(40)` 必须与群聊既有实现保持一致，任何改动都会改变判定结果。
     */
    fun normalize(text: String): String {
        return text
            .replace(Regex("[@\\s，。！？!?,.～~…、:：;；\"'「」『』()（）\\[\\]【】]"), "")
            .lowercase()
            .take(40)
    }

    /**
     * 判定 [candidate] 是否与 [window] 中任一历史项重复。
     *
     * 规则：精确相等，或「较短串长度 >= [CONTAIN_MIN] 且较长串包含较短串」——
     * 双向守卫，避免短串乱命中。
     *
     * 注意：[candidate] 与 [window] 中的字符串都应已通过 [normalize] 处理。
     */
    fun isDuplicate(candidate: String, window: Collection<String>): Boolean {
        if (candidate.isEmpty() || window.isEmpty()) return false
        return window.any { existing ->
            existing == candidate ||
                (existing.length >= CONTAIN_MIN && existing.length <= candidate.length && candidate.contains(existing)) ||
                (candidate.length >= CONTAIN_MIN && candidate.length <= existing.length && existing.contains(candidate))
        }
    }
}
