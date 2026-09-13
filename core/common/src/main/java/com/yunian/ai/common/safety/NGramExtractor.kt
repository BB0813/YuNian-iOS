package com.yunian.ai.common.safety

object NGramExtractor {

    fun extract(text: String): List<String> {
        val normalized = normalize(text)
        val cjkGrams = extractCjkCharGrams(normalized)
        val asciiGrams = extractAsciiWordGrams(normalized)
        return (cjkGrams + asciiGrams).distinct()
    }

    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\uFEFF' -> {

                }
                ch.isWhitespace() && ch != ' ' && ch != '\n' -> {

                }
                ch in '\uFF01'..'\uFF5E' -> {

                    sb.append((ch.code - 0xFEE0).toChar())
                }
                ch == '\u3000' -> {

                    sb.append(' ')
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun extractCjkCharGrams(text: String): List<String> {
        val chars = text.filter { it.isCjk() }
        if (chars.length < 2) return chars.map { it.toString() }

        val maxN = if (chars.length > 100) 2 else 3

        val result = mutableListOf<String>()
        for (n in 1..maxN) {
            for (i in 0..chars.length - n) {
                result.add(chars.substring(i, i + n))
            }
        }
        return result
    }

    private fun extractAsciiWordGrams(text: String): List<String> {
        val words = text.split(Regex("[^a-zA-Z0-9]+"))
            .filter { it.length >= 2 }
            .map { it.lowercase() }
        if (words.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        for (n in 1..2) {
            for (i in 0..words.size - n) {
                result.add(words.subList(i, i + n).joinToString(" "))
            }
        }
        return result
    }

    private fun Char.isCjk(): Boolean {
        val cp = this.code
        return cp in 0x4E00..0x9FFF
            || cp in 0x3400..0x4DBF
            || cp in 0x20000..0x2A6DF
            || cp in 0xF900..0xFAFF
            || cp in 0x3040..0x309F
            || cp in 0x30A0..0x30FF
            || cp in 0xAC00..0xD7AF
    }
}
