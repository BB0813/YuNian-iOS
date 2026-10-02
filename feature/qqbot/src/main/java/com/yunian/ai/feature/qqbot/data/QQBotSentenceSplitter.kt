package com.yunian.ai.feature.qqbot.data

/**
 * QQ 出站分句：把一段待发文本切成若干条 QQ 消息（**纯函数**，无 Android 依赖）。
 *
 * ## 为什么单独抽出来
 *
 * 这段判定原本是 [QQBotChatBridge] 的 private 方法，而 `QQBotChatBridge` 的构造函数
 * 需要 Android `Context`（并立即 `AppDatabase.getDatabase`），纯 JVM 单测无法构造它
 * —— 于是「一句话会被切成几条 QQ 消息」这条判定**长期零覆盖**（修复前 feature:qqbot
 * 的 5 个测试类没有任何一个触及分句）。抽成 internal object 后与
 * [QQBotOutboundProjection] 同构：只做纯文本判定，可被纯 JVM 单测逐字驱动。
 *
 * ## 修的是什么 bug（连续句末标点）
 *
 * 原实现是「找到下一个分隔符 → `substring(start, idx + 1)` → 非空即入列 →
 * `start = idx + 1`」，**不跳过连续分隔符**。于是「你好！！」被切成
 * `["你好！", "！"]`：第二段 trim 后是「！」，**不是 blank**，因此
 * [QQBotChatBridge] 的发送循环（只 `continue` 掉 `isBlank()` 的片段）会
 * **真的向用户发出一条只含标点的 QQ 消息**。「什么？？」「好！！！」同理。
 *
 * ## 修法（两条规则，缺一不可）
 *
 * 1. **连续分隔符并进前一句**：分隔符游程（run）整体算作前一句的收尾，
 *    因此「你好！！」→ `["你好！！"]`。选「并入」而不是「只保留一个」，
 *    是为了不丢文本里写出来的语气强度（「什么？？？」的质问语气要留着）。
 * 2. **不含实义字符的片段一律丢弃**：见 [hasContent] —— 要求片段里至少有一个
 *    「既不是分隔符、也不是空白」的字符。这条覆盖规则 1 管不到的情况：
 *    **行首**的标点游程（前面没有句子可供并入），例如「。。你好」→ `["你好"]`、
 *    单独一条「！」→ `[]`。
 *
 * ## 换行不参与「连续」合并（**刻意**，不是遗漏）
 *
 * `'\n'` 是分隔符，但**不是**语气标点：它是消息边界。
 * [QQBotChatBridge] 的 `drainPending` 把合并窗口内的多条入站消息用
 * `joinToString("\n")` 拼成一段文本，所以换行在真实输入里非常常见。
 * 若让游程跨换行合并，「嗯？\n\n？」会被粘成一条带空行的消息；
 * 限制游程不跨换行后，该输入得到 `["嗯？"]`（第二段被规则 2 丢弃）。
 *
 * 这条限制同时让本次改动对**既有正常输入逐字保守**：
 * 「你好。\n\n世界。」改动前后都得到 `["你好。", "世界。"]`。
 *
 * ## 刻意不做的事（含一个**已知且有意保留**的边界）
 *
 * 分隔符集合与改动前**逐字相同**（`。！？!?` + 换行），**不**扩展 `，、；：`：
 * 逗号是句中标点，加进来会把一句话切碎，那是另一件事、另一个 bug。
 *
 * 由此留下一个边界：只含**非分隔符**标点的片段（如 `，。`、`……`）不在
 * [hasContent] 的丢弃范围内，仍会被保留并发送。这是**刻意的**——
 * 「……」在中文聊天里是一条合法消息（表示无语 / 停顿），丢掉它比发出去更糟。
 * 本 bug 的范围是「切分器自己切出来的纯标点碎片」，用分隔符集合判定恰好
 * 精确覆盖它，且不会误伤这类合法消息。
 *
 * @see QQBotChatBridge
 */
internal object QQBotSentenceSplitter {

    /** 句末分隔符（与改动前的字面集合逐字一致，未增未减）。 */
    private val DELIMITERS = charArrayOf('。', '！', '？', '!', '?', '\n')

    /**
     * 把一段待发文本切成若干条 QQ 消息。
     *
     * 不会切出仅含分隔符的碎片（见 [hasContent]）；非分隔符标点（如省略号）仍保留。
     * 空 / 全空白输入返回空列表。
     */
    fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val idx = text.indexOfAny(DELIMITERS, startIndex = start)
            if (idx < 0) {
                val remaining = text.substring(start).trim()
                if (hasContent(remaining)) result.add(remaining)
                break
            }
            // 规则 1：把从这里开始的连续分隔符整体并入本句。
            // 不跨换行 —— 换行是消息边界，不是可以叠加的语气标点。
            var end = idx + 1
            while (text[idx] != '\n' && end < text.length && text[end] != '\n' && text[end] in DELIMITERS) {
                end++
            }
            val sentence = text.substring(start, end).trim()
            // 规则 2：行首标点游程（前面没有实义内容）在这里被丢弃。
            if (hasContent(sentence)) result.add(sentence)
            start = end
        }
        return result
    }

    /**
     * 片段里是否存在「实义字符」（既不是分隔符、也不是空白）。
     *
     * 这是「不会切出仅含分隔符的碎片」的判据，也是与调用方
     * `isBlank()` 判定的区别所在：「！」不是 blank，但它没有实义字符。
     */
    private fun hasContent(sentence: String): Boolean =
        sentence.any { it !in DELIMITERS && !it.isWhitespace() }
}
