package com.yunian.ai.common

class AhoCorasick private constructor(

    private val next: Array<IntArray>,

    private val output: Array<ContentFilter.ViolationLevel?>,

    private val keywordLen: IntArray,

    private val keywordText: Array<String?>,

    val stateCount: Int
) {
    companion object {
        fun build(
            keywords: Map<ContentFilter.ViolationLevel, List<String>>
        ): AhoCorasick {
            var totalChars = 0
            for ((_, words) in keywords) {
                for (w in words) totalChars += w.length
            }
            val maxStates = (totalChars * 2) + 1
            val goto = Array(maxStates) { IntArray(128) { -1 } }
            val output = arrayOfNulls<ContentFilter.ViolationLevel>(maxStates)
            val wordLen = IntArray(maxStates) { 0 }
            val wordText = arrayOfNulls<String>(maxStates)

            var stateCount = 1
            for ((level, words) in keywords) {
                for (word in words) {
                    var state = 0
                    for (ch in word.lowercase()) {
                        val ci = ch.code
                        if (ci >= 128) continue
                        if (goto[state][ci] == -1) {
                            goto[state][ci] = stateCount++
                        }
                        state = goto[state][ci]
                    }
                    if (output[state] == null || level.ordinal > output[state]!!.ordinal) {
                        output[state] = level
                        wordLen[state] = word.length
                        wordText[state] = word
                    }
                }
            }

            val fail = IntArray(maxStates) { 0 }
            val queue = ArrayDeque<Int>()

            for (c in 0 until 128) {
                if (goto[0][c] != -1) {
                    fail[goto[0][c]] = 0
                    queue.addLast(goto[0][c])
                } else {
                    goto[0][c] = 0
                }
            }

            while (queue.isNotEmpty()) {
                val r = queue.removeFirst()
                for (c in 0 until 128) {
                    if (goto[r][c] != -1) {
                        val s = goto[r][c]
                        queue.addLast(s)
                        fail[s] = goto[fail[r]][c]

                        if (output[fail[s]] != null) {
                            val inherited = output[fail[s]]!!
                            if (output[s] == null || inherited.ordinal > output[s]!!.ordinal) {
                                output[s] = inherited

                                if (wordText[s] == null || wordLen[fail[s]] < wordLen[s]) {
                                    wordLen[s] = wordLen[fail[s]]
                                    wordText[s] = wordText[fail[s]]
                                }
                            }
                        }
                    }
                }
            }

            val next = Array(stateCount) { IntArray(128) }
            for (state in 0 until stateCount) {
                for (c in 0 until 128) {
                    next[state][c] = goto[state][c]
                }
            }

            return AhoCorasick(next, output, wordLen, wordText, stateCount)
        }
    }

    data class Match(
        val level: ContentFilter.ViolationLevel,
        val keyword: String,
        val endPos: Int
    )

    fun search(text: String): List<Match> {
        val matches = mutableListOf<Match>()
        val chars = text.toCharArray()
        var state = 0

        for (i in chars.indices) {
            val ci = chars[i].lowercaseChar().code
            if (ci >= 128 || ci < 0) {
                state = 0
                continue
            }
            state = next[state][ci]

            output[state]?.let { level ->
                val kw = keywordText[state]
                val len = keywordLen[state]
                if (kw != null && len > 0) {
                    val start = i - len + 1
                    if (start >= 0) {
                        matches.add(Match(level, kw, i))
                    }
                }
            }
        }

        return matches
    }
}
