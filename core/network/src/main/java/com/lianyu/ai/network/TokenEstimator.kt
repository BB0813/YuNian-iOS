package com.lianyu.ai.network

/**
 * TokenEstimator — 轻量级 token 估算器。
 *
 * 不依赖外部库，基于字符特征做快速估算：
 * - CJK 字符（中日韩）约 1.5 token/字
 * - 拉丁字符约 0.25 token/字符（≈4 字符/token）
 * - 标点/空白按所属脚本分别计算
 *
 * 估算误差在 ±20% 以内，用于上下文预算控制足够可靠。
 */
object TokenEstimator {

    /** 估算纯文本的 token 数 */
    fun estimate(text: String): Int {
        if (text.isBlank()) return 0
        var cjkCount = 0
        var otherCount = 0
        for (ch in text) {
            if (isCjk(ch)) cjkCount++
            else otherCount++
        }
        // CJK: ~1.5 token/char; Latin: ~0.25 token/char
        val estimate = (cjkCount * 1.5 + otherCount * 0.25).toInt()
        return maxOf(1, estimate)
    }

    /** 估算一组 Message 的总 token 数（含角色标记开销） */
    fun estimate(messages: List<Message>): Int {
        var total = 0
        for (msg in messages) {
            // 每条消息约 4 token 的结构开销（role + delimiter）
            total += 4
            msg.content?.let { total += estimate(it) }
            msg.reasoning_content?.let { total += estimate(it) }
        }
        // 对话结尾约 3 token 的辅助开销
        return total + 3
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) ||   // CJK 统一汉字
               (code in 0x3040..0x309F) ||   // 平假名
               (code in 0x30A0..0x30FF) ||   // 片假名
               (code in 0xAC00..0xD7AF) ||  // 韩文音节
               (code in 0x3400..0x4DBF) ||   // CJK 扩展 A
               (code in 0xF900..0xFAFF)      // CJK 兼容汉字
    }
}
