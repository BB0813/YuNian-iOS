package com.yunian.ai.common.safety

class BayesianClassifier(
    private val alpha: Double = 1.0
) {

    private val termFreqByClass = mutableMapOf<Boolean, MutableMap<String, Int>>()

    private val totalTermsByClass = mutableMapOf<Boolean, Int>()

    private val sampleCountByClass = mutableMapOf<Boolean, Int>()

    @Volatile
    var totalSamples: Int = 0
        private set

    private var vocabSize: Int = 0

    init {
        termFreqByClass[true] = mutableMapOf()
        termFreqByClass[false] = mutableMapOf()
        totalTermsByClass[true] = 0
        totalTermsByClass[false] = 0
        sampleCountByClass[true] = 0
        sampleCountByClass[false] = 0
    }

    @Synchronized
    fun train(samples: List<SafetySample>) {
        for (sample in samples) {
            addSample(sample)
        }
        rebuildVocab()
    }

    @Synchronized
    fun addSample(sample: SafetySample) {
        val grams = NGramExtractor.extract(sample.text)
        addTokensInternal(grams, sample.isViolation)
    }

    @Synchronized
    fun addSampleDirect(tokens: List<String>, isViolation: Boolean) {
        val freq = termFreqByClass[isViolation]!!
        for (t in tokens) {
            freq[t] = (freq[t] ?: 0) + 1
        }
        totalTermsByClass[isViolation] = totalTermsByClass[isViolation]!! + tokens.size

        sampleCountByClass[isViolation] = sampleCountByClass[isViolation]!! + 1
        totalSamples++
        vocabSize = -1
    }

    @Synchronized
    fun addSampleDirect(token: String, isViolation: Boolean) {
        addSampleDirect(listOf(token), isViolation)
    }

    private fun addTokensInternal(tokens: List<String>, isViolation: Boolean) {
        val freq = termFreqByClass[isViolation]!!
        for (t in tokens) {
            freq[t] = (freq[t] ?: 0) + 1
        }
        totalTermsByClass[isViolation] = totalTermsByClass[isViolation]!! + tokens.size
        sampleCountByClass[isViolation] = sampleCountByClass[isViolation]!! + 1
        totalSamples++
        vocabSize = -1
    }

    private fun rebuildVocab() {
        val allTerms = mutableSetOf<String>()
        termFreqByClass.values.forEach { allTerms.addAll(it.keys) }
        vocabSize = allTerms.size
    }

    fun classify(text: String): Double {
        return classifyTokens(NGramExtractor.extract(text))
    }

    @Synchronized
    fun classifyTokens(tokens: List<String>): Double {
        if (totalSamples == 0) return 0.5
        if (vocabSize <= 0) rebuildVocab()
        if (tokens.isEmpty()) return priorViolation()

        val logProbViolation = Math.log(priorViolation()) +
            tokens.sumOf { Math.log(termProbability(it, true)) }
        val logProbSafe = Math.log(priorSafe()) +
            tokens.sumOf { Math.log(termProbability(it, false)) }

        val maxLog = Math.max(logProbViolation, logProbSafe)
        val probViolation = Math.exp(logProbViolation - maxLog)
        val probSafe = Math.exp(logProbSafe - maxLog)

        return probViolation / (probViolation + probSafe)
    }

    @Synchronized
    fun exportPriors(): FloatArray = floatArrayOf(
        priorSafe().toFloat(), priorViolation().toFloat()
    )

    @Synchronized
    fun exportLikelihoods(): Pair<FloatArray, List<String>> {
        val tokens = mutableListOf<String>()
        val values = mutableListOf<Float>()
        val allTokens = mutableSetOf<String>()
        termFreqByClass.values.forEach { allTokens.addAll(it.keys) }
        for (t in allTokens) {
            tokens.add(t)
            values.add(termProbability(t, false).toFloat())
            values.add(termProbability(t, true).toFloat())
        }
        return values.toFloatArray() to tokens
    }

    @Synchronized
    fun classifyTokensNative(features: FloatArray): Float {
        return com.yunian.ai.common.NativeSafetyFilter.bayesianPredict(
            features, exportPriors(), computeFeatureLikelihoods(features.size)
        )
    }

    private fun computeFeatureLikelihoods(numFeatures: Int): FloatArray {
        val likelihoods = FloatArray(numFeatures * 2)
        for (i in 0 until numFeatures) {
            likelihoods[i * 2] = 0.5f + 1e-4f
            likelihoods[i * 2 + 1] = 0.5f + 1e-4f
        }
        return likelihoods
    }

    @Synchronized
    fun topContributingTokens(tokens: List<String>, topN: Int = 5): List<Pair<String, Double>> {
        if (tokens.isEmpty()) return emptyList()
        return tokens
            .map { t ->
                val pV = termProbability(t, true)
                val pS = termProbability(t, false)
                val contrib = if (pV > pS) pV / (pV + pS) - 0.5 else -(pS / (pV + pS) - 0.5)
                t to contrib
            }
            .filter { Math.abs(it.second) > 0.01 }
            .sortedByDescending { Math.abs(it.second) }
            .take(topN)
    }

    @Synchronized
    fun topContributingTerms(text: String, topN: Int = 5): List<Pair<String, Double>> {
        val grams = NGramExtractor.extract(text)
        if (grams.isEmpty()) return emptyList()

        return grams
            .map { g ->
                val pViolation = termProbability(g, true)
                val pSafe = termProbability(g, false)
                val contribution = if (pViolation > pSafe) {
                    pViolation / (pViolation + pSafe) - 0.5
                } else {
                    -(pSafe / (pViolation + pSafe) - 0.5)
                }
                g to contribution
            }
            .filter { Math.abs(it.second) > 0.01 }
            .sortedByDescending { Math.abs(it.second) }
            .take(topN)
    }

    @Synchronized
    fun priorViolation(): Double {
        val total = sampleCountByClass[true]!! + sampleCountByClass[false]!!
        if (total == 0) return 0.5
        val raw = sampleCountByClass[true]!!.toDouble() / total
        return raw.coerceAtMost(0.3)
    }

    @Synchronized
    fun priorSafe(): Double = 1.0 - priorViolation()

    @Synchronized
    fun termProbability(term: String, isViolation: Boolean): Double {
        if (vocabSize <= 0) rebuildVocab()
        val count = termFreqByClass[isViolation]?.get(term) ?: 0
        val total = totalTermsByClass[isViolation] ?: 0
        val vSize = if (vocabSize > 0) vocabSize else 1
        return (count + alpha) / (total + alpha * vSize)
    }

    fun termCount(term: String, isViolation: Boolean): Int =
        termFreqByClass[isViolation]?.get(term) ?: 0

    @Synchronized
    fun exportState(): BayesianState {
        if (vocabSize <= 0) rebuildVocab()
        return BayesianState(
            termFreqViolation = termFreqByClass[true]!!.toMap(),
            termFreqSafe = termFreqByClass[false]!!.toMap(),
            totalTermsViolation = totalTermsByClass[true]!!,
            totalTermsSafe = totalTermsByClass[false]!!,
            sampleCountViolation = sampleCountByClass[true]!!,
            sampleCountSafe = sampleCountByClass[false]!!,
            vocabSize = vocabSize,
            totalSamples = totalSamples
        )
    }

    @Synchronized
    fun importState(state: BayesianState) {
        termFreqByClass[true] = state.termFreqViolation.toMutableMap()
        termFreqByClass[false] = state.termFreqSafe.toMutableMap()
        totalTermsByClass[true] = state.totalTermsViolation
        totalTermsByClass[false] = state.totalTermsSafe
        sampleCountByClass[true] = state.sampleCountViolation
        sampleCountByClass[false] = state.sampleCountSafe
        vocabSize = state.vocabSize
        totalSamples = state.totalSamples
    }
}

data class BayesianState(
    val termFreqViolation: Map<String, Int>,
    val termFreqSafe: Map<String, Int>,
    val totalTermsViolation: Int,
    val totalTermsSafe: Int,
    val sampleCountViolation: Int,
    val sampleCountSafe: Int,
    val vocabSize: Int,
    val totalSamples: Int
)
