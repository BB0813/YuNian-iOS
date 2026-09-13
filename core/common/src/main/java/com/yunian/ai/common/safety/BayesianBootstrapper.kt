package com.yunian.ai.common.safety

object BayesianBootstrapper {

    private val REGEX_META = Regex("""[\\\\^$.*+?()|[\\]{}]""")

    fun bootstrap(keywords: List<Pair<String, String>>): List<SafetySample> {
        val samples = mutableListOf<SafetySample>()

        for ((word, level) in keywords) {
            val w = word.trim()
            if (w.isBlank()) continue

            if (REGEX_META.containsMatchIn(w)) continue

            samples.add(SafetySample(w, true, SampleSource.USER_INPUT, level))

            if (w.length >= 2) {
                samples.add(SafetySample("我想要$w", true, SampleSource.USER_INPUT, level))
            }
            if (w.length >= 3) {
                samples.add(SafetySample("请问在哪里可以买到$w", true, SampleSource.USER_INPUT, level))
            }
        }

        for (text in SAFE_BASELINE) {
            samples.add(SafetySample(text, false, SampleSource.USER_INPUT, "safe"))
        }

        return samples
    }

    private val SAFE_BASELINE = listOf(
        "你好呀", "今天天气真好", "吃饭了吗", "在忙什么呢",
        "晚安好梦", "我想你了", "今天学到了很多", "谢谢你陪我聊天",
        "最近过得怎么样", "有什么好看的电影推荐吗",
        "分享一下今天的趣事", "我刚刚看到一只很可爱的小猫",
        "周末打算去哪里玩", "这本书很有意思", "音乐让人心情变好"
    )
}
