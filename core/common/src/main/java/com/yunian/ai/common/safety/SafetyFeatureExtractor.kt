package com.yunian.ai.common.safety

private val URL_REGEX = Regex("https?://|www\\.")
private val PHONE_REGEX = Regex("1[3-9]\\d{9}")
private val SCORE_REGEX = Regex("\\(([0-9.]+)\\)")

object SafetyFeatureExtractor {

    fun extract(
        keywordResult: com.yunian.ai.common.ContentFilter.CheckResult,
        vectorResult: com.yunian.ai.common.ContentFilter.CheckResult?,
        rawText: String
    ): List<String> {
        val features = mutableListOf<String>()

        val hitCount = keywordResult.matchedKeywords.size
        features.add("kw_hit:" + when {
            hitCount == 0 -> "0"
            hitCount == 1 -> "1"
            hitCount == 2 -> "2"
            else -> "3p"
        })

        features.add("kw_max:" + keywordResult.level.name)

        if (vectorResult != null) {

            val score = extractScore(vectorResult.reason)
            features.add("vec_score:" + when {
                score < 0.3f -> "0"
                score < 0.5f -> "1"
                score < 0.7f -> "2"
                score < 0.85f -> "3"
                else -> "4"
            })
            features.add("vec_gray:" + if (vectorResult.reason.contains("灰区")) "Y" else "N")
        } else {
            features.add("vec_score:na")
            features.add("vec_gray:na")
        }

        val len = rawText.length
        features.add("len_bucket:" + when {
            len < 10 -> "0"
            len < 50 -> "1"
            len < 200 -> "2"
            else -> "3"
        })

        features.add("has_url:" + if (rawText.contains(URL_REGEX)) "Y" else "N")
        features.add("has_phone:" + if (rawText.contains(PHONE_REGEX)) "Y" else "N")

        return features
    }

    private fun extractScore(reason: String): Float {

        val match = SCORE_REGEX.find(reason)
        return match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
    }

    fun extractFloatVector(
        keywordResult: com.yunian.ai.common.ContentFilter.CheckResult,
        vectorResult: com.yunian.ai.common.ContentFilter.CheckResult?,
        rawText: String
    ): FloatArray {
        val vec = FloatArray(8)
        val len = rawText.length.coerceAtLeast(1)

        vec[0] = (keywordResult.matchedKeywords.size / 4f).coerceAtMost(1f)

        vec[1] = rawText.toSet().size.toFloat() / len

        vec[2] = keywordResult.matchedKeywords.size.toFloat() / len

        vec[3] = 1f - rawText.toSet().size.toFloat() / rawText.length.coerceAtLeast(1)

        vec[4] = if (rawText.any { it in '\u4e00'..'\u9fff' } && rawText.any { it in 'a'..'z' || it in 'A'..'Z' }) 1f else 0f

        vec[5] = (kotlin.math.ln(len.toDouble() + 1) / 10.0).toFloat()

        vec[6] = keywordResult.matchedKeywords.size.toFloat()

        vec[7] = (len - keywordResult.matchedKeywords.size).coerceAtLeast(0).toFloat()

        return vec
    }
}
