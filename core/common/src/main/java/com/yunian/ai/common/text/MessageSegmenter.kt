package com.yunian.ai.common.text

object MessageSegmenter {

    enum class SplitMode {

        SIMPLE,

        GROUP
    }

    private val SPLIT_PARAGRAPH_REGEX = Regex("\\n{2,}")
    private val SPLIT_SENTENCE_REGEX = Regex("(?<=[。！？～…!?~])\\s*")
    private val TERMINAL_PUNCT_REGEX = Regex("[。！？!?～…]+$")

    private const val SOFT_TARGET_CHARS = 72

    private const val SOFT_MAX_SEGMENTS = 3

    private const val HARD_MAX_CHARS = 160

    private const val HARD_MAX_SEGMENTS = 5

    private val STANDALONE_SHORT_REPLIES = setOf(
        "嗯", "嗯嗯", "嗯哼", "好", "好的", "好呀", "好啊", "好啦", "行", "行吧", "行啊",
        "哦", "噢", "喔", "啊", "呀", "哈", "哈哈", "哈哈哈", "呵呵", "嘿", "嗨",
        "是", "是的", "对", "对的", "对呀", "可以", "可以啊", "没事", "没事的",
        "知道了", "收到", "了解", "明白", "好吧", "那行", "那好", "得了",
        "……", "…", "...", "？", "?", "！", "!"
    )

    private val BEHAVIOR_SHIFT_PREFIXES = listOf(
        "不过", "但是", "可是", "然而", "话说", "对了", "另外", "还有",
        "所以", "因此", "总之", "总而言之", "最后", "好啦", "好了", "行了",
        "要不", "不如", "建议", "你可以", "你要不要", "要不要", "记得", "别忘了",
        "先去", "先把", "我们先", "那你先", "那你就", "我觉得你可以", "我建议",
        "顺便", "其实", "说真的", "认真的", "换个话题", "不说这个了",
        "我有点", "我现在", "我心里", "我感觉", "我有点想", "我有点累",
        "抱抱", "来", "走吧", "睡吧", "吃点", "喝点"
    )

    private val WEAK_CONTINUATION_PREFIXES = listOf(
        "就", "才", "也", "还", "都", "再", "然后", "接着", "并且", "而且",
        "吧", "呢", "啊", "呀", "啦", "嘛", "哦", "噢",
        "的", "地", "得", "着", "了", "过"
    )

    fun split(text: String, mode: SplitMode = SplitMode.SIMPLE): List<String> {
        return when (mode) {
            SplitMode.SIMPLE -> splitSimple(text)
            SplitMode.GROUP -> splitGroup(text)
        }
    }

    private fun splitSimple(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return listOf(trimmed)

        val paragraphs = trimmed.split(SPLIT_PARAGRAPH_REGEX)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (paragraphs.size >= 2) {
            val units = paragraphs.flatMap { paragraph ->
                if (paragraph.length <= HARD_MAX_CHARS) {
                    listOf(paragraph)
                } else {
                    chunkBySemanticUnits(splitSentences(paragraph))
                }
            }
            return applySegmentLimits(units).ifEmpty { listOf(trimmed) }
        }

        val sentences = splitSentences(trimmed)
        if (sentences.isEmpty()) return listOf(trimmed)
        if (sentences.size == 1) {
            val only = sentences.first()
            return if (only.length <= HARD_MAX_CHARS) listOf(only) else forceSplitLong(only)
        }
        return applySegmentLimits(chunkBySemanticUnits(sentences))
    }

    private fun splitSentences(text: String): List<String> {
        return text.split(SPLIT_SENTENCE_REGEX)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun chunkBySemanticUnits(sentences: List<String>): List<String> {
        if (sentences.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        var buffer = StringBuilder()

        fun flush() {
            val seg = buffer.toString().trim()
            if (seg.isNotEmpty()) chunks.add(seg)
            buffer = StringBuilder()
        }

        for (sentence in sentences) {
            val next = sentence.trim()
            if (next.isEmpty()) continue

            if (buffer.isEmpty()) {
                if (next.length > HARD_MAX_CHARS) {
                    chunks.addAll(forceSplitLong(next))
                } else {
                    buffer.append(next)
                }
                continue
            }

            val current = buffer.toString()
            val shouldContinue = shouldContinueSameBubble(current, next)
            val wouldExceedHard = current.length + next.length > HARD_MAX_CHARS

            if (!shouldContinue || wouldExceedHard) {
                flush()
                if (next.length > HARD_MAX_CHARS) {
                    chunks.addAll(forceSplitLong(next))
                } else {
                    buffer.append(next)
                }
            } else {
                buffer.append(next)
            }
        }
        flush()
        return chunks.ifEmpty { listOf(sentences.joinToString("")) }
    }

    private fun shouldContinueSameBubble(current: String, next: String): Boolean {
        val currentCore = stripTerminalPunct(current)
        val nextCore = stripTerminalPunct(next)

        if (isStandaloneShortReply(currentCore) || isStandaloneShortReply(nextCore)) {
            return false
        }

        if (looksLikeBehaviorShift(next)) {
            return false
        }

        val currentComplete = TERMINAL_PUNCT_REGEX.containsMatchIn(current.trim())
        val weakContinuation = looksLikeWeakContinuation(next)

        if (!currentComplete) {
            return true
        }

        if (weakContinuation && nextCore.length <= 6) {
            return true
        }

        return false
    }

    private fun applySegmentLimits(segments: List<String>): List<String> {
        if (segments.size <= 1) return segments
        val result = segments.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (result.isEmpty()) return segments

        while (result.size > SOFT_MAX_SEGMENTS) {
            val idx = findBestMergeIndex(result, force = false) ?: break
            mergeAt(result, idx)
        }

        while (result.size > HARD_MAX_SEGMENTS) {
            val idx = findBestMergeIndex(result, force = true)
                ?: findAnyMergeIndex(result)
                ?: break
            mergeAt(result, idx)
        }

        return result.ifEmpty { segments }
    }

    private fun findBestMergeIndex(segments: List<String>, force: Boolean): Int? {
        var bestIndex: Int? = null
        var bestScore = Int.MAX_VALUE

        for (i in 0 until segments.lastIndex) {
            val left = segments[i]
            val right = segments[i + 1]
            val leftCore = stripTerminalPunct(left)
            val rightCore = stripTerminalPunct(right)
            val leftStandalone = isStandaloneShortReply(leftCore)
            val rightStandalone = isStandaloneShortReply(rightCore)

            if (!force && (leftStandalone || rightStandalone)) continue

            if (force && leftStandalone && rightStandalone) continue

            val combinedLen = left.length + right.length
            if (combinedLen > HARD_MAX_CHARS) continue

            var score = combinedLen
            val distanceToSoft = kotlin.math.abs(combinedLen - SOFT_TARGET_CHARS)
            score += distanceToSoft
            if (looksLikeBehaviorShift(right)) score += 40
            if (leftStandalone || rightStandalone) score += 30
            if (combinedLen <= SOFT_TARGET_CHARS) score -= 10

            if (score < bestScore) {
                bestScore = score
                bestIndex = i
            }
        }
        return bestIndex
    }

    private fun findAnyMergeIndex(segments: List<String>): Int? {
        var bestFit: Int? = null
        var bestFitLen = Int.MAX_VALUE
        var shortestPair: Int? = null
        var shortestLen = Int.MAX_VALUE

        for (i in 0 until segments.lastIndex) {
            val combinedLen = segments[i].length + segments[i + 1].length
            if (combinedLen < shortestLen) {
                shortestLen = combinedLen
                shortestPair = i
            }
            if (combinedLen <= HARD_MAX_CHARS && combinedLen < bestFitLen) {
                bestFitLen = combinedLen
                bestFit = i
            }
        }
        return bestFit ?: shortestPair
    }

    private fun mergeAt(segments: MutableList<String>, index: Int) {
        if (index < 0 || index >= segments.lastIndex) return
        val merged = segments[index] + segments[index + 1]
        segments[index] = merged
        segments.removeAt(index + 1)
    }

    private fun stripTerminalPunct(text: String): String {
        return text.trim()
            .replace(TERMINAL_PUNCT_REGEX, "")
            .trim()
            .trim('，', ',', '；', ';', '、', ' ')
    }

    private fun isStandaloneShortReply(core: String): Boolean {
        if (core.isEmpty()) return true
        if (core in STANDALONE_SHORT_REPLIES) return true
        if (core.length <= 2 && core.all { it in "嗯啊呀哦噢喔哈呵嘿嗨行好对是吧呢嘛啦" }) {
            return true
        }
        return false
    }

    private fun looksLikeBehaviorShift(sentence: String): Boolean {
        val s = sentence.trimStart('，', ',', ' ', '　')
        return BEHAVIOR_SHIFT_PREFIXES.any { prefix -> s.startsWith(prefix) }
    }

    private fun looksLikeWeakContinuation(sentence: String): Boolean {
        val s = sentence.trimStart('，', ',', ' ', '　')
        if (s.isEmpty()) return false
        if (looksLikeBehaviorShift(s)) return false
        return WEAK_CONTINUATION_PREFIXES.any { prefix -> s.startsWith(prefix) }
    }

    private fun forceSplitLong(text: String): List<String> {
        if (text.length <= HARD_MAX_CHARS) return listOf(text)
        val result = mutableListOf<String>()
        var rest = text
        while (rest.length > HARD_MAX_CHARS) {
            val window = rest.take(HARD_MAX_CHARS)
            val cut = window.lastIndexOfAny(
                charArrayOf('。', '！', '？', '!', '?', '；', ';', '，', ',', '、', ' ')
            )
            val splitAt = if (cut >= 12) cut + 1 else HARD_MAX_CHARS
            val part = rest.take(splitAt).trim()
            if (part.isNotEmpty()) result.add(part)
            rest = rest.drop(splitAt).trimStart()
        }
        if (rest.isNotBlank()) result.add(rest)
        return result.ifEmpty { listOf(text) }
    }

    private fun splitGroup(text: String): List<String> {
        val cleaned = text.trim().replace(Regex("\\n{2,}"), "\n")
        if (cleaned.length <= 15) return listOf(cleaned)

        val rawSegments = cleaned.split("\n").map { it.trim() }.filter { it.isNotBlank() }

        if (rawSegments.size > 1) {
            val result = mutableListOf<String>()
            for (segment in rawSegments) {
                if (segment.length <= 20) {
                    result.add(segment)
                } else {
                    result.addAll(splitLongSegment(segment))
                }
            }
            return result.ifEmpty { listOf(cleaned) }
        }

        return splitLongSegment(cleaned)
    }

    private fun splitLongSegment(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val seg = current.toString().trim()
            if (seg.isNotEmpty()) sentences.add(seg)
            current.clear()
        }

        for (char in text) {
            current.append(char)
            when (char) {
                '。', '！', '？' -> flush()
                '…', '～' -> {
                    if (current.length >= 3) flush()
                }
                ',', '，' -> {
                    if (current.length >= 10 && current.contains(Regex("[！？。]"))) {
                        flush()
                    }
                }
            }
        }
        flush()

        if (sentences.isEmpty()) return listOf(text)

        val result = mutableListOf<String>()
        var buffer = StringBuilder()

        for (sentence in sentences) {
            val cleanSentence = sentence.trimStart('，', ',', '.', '。', ' ')
            if (cleanSentence.isEmpty()) continue

            if (buffer.length + cleanSentence.length <= 12) {
                if (buffer.isNotEmpty()) buffer.append("，")
                buffer.append(cleanSentence)
            } else {
                if (buffer.isNotEmpty()) {
                    result.add(buffer.toString())
                    buffer = StringBuilder()
                }
                if (cleanSentence.length <= 14) {
                    buffer.append(cleanSentence)
                } else if (cleanSentence.length <= 25) {
                    result.add(cleanSentence)
                } else {
                    val midPoint = cleanSentence.length / 2
                    val startIndex = midPoint.coerceAtLeast(0)
                    val endIndex = (midPoint + 10).coerceAtMost(cleanSentence.length)
                    val searchRange =
                        if (startIndex < endIndex) cleanSentence.substring(startIndex, endIndex) else ""
                    val splitPosInRange = searchRange.indexOfAny(charArrayOf('，', ',', '、'))
                    val splitPos = if (splitPosInRange >= 0) midPoint + splitPosInRange else -1
                    if (splitPos > 0) {
                        result.add(cleanSentence.take(splitPos + 1).trim())
                        buffer.append(cleanSentence.drop(splitPos + 1).trimStart())
                    } else {
                        result.add(cleanSentence.take(14).trimEnd('，', ','))
                        buffer.append(cleanSentence.drop(14).trimStart('，', ','))
                    }
                }
            }
        }
        if (buffer.isNotEmpty()) result.add(buffer.toString())

        return result.ifEmpty { listOf(text) }
    }

    fun isNoiseText(text: String): Boolean {
        if (text.isBlank()) return true
        val noiseOnly = Regex("^[.…·~～\u2026\u4E00-\u9FFF\u3000\\s!！?？、，,。]+$")
        if (!noiseOnly.matches(text)) return false

        val interjection = setOf(
            "嗯", "嗯嗯", "嗯哼", "唔", "啊", "哦", "噢", "喔", "哈", "哈哈", "呵呵", "嘿", "唉",
            "呀", "嘛", "呢", "吧", "啦", "咯", "呗", "哟", "哇", "诶", "哎", "啧", "嗯呐",
        )
        val core = text.filter { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }
        if (core.isEmpty()) return true

        return core.length <= 4 && core.all { it.toString() in interjection }
    }
}
