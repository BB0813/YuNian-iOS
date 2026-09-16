package com.yunian.ai.common.safety

import android.content.Context
import android.content.SharedPreferences
import com.yunian.ai.common.concurrent.AppDispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object ContentSafetyVerifier {

    private const val PREFS_NAME = "safety_bayesian"
    private const val KEY_USER_STATE = "user_classifier_state"
    private const val KEY_MODEL_STATE = "model_classifier_state"

    val userClassifier = BayesianClassifier()
    val modelClassifier = BayesianClassifier()

    private var prefs: SharedPreferences? = null
    @Volatile
    private var bootstrapped = false

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadUserState()
        loadModelState()
    }

    @Synchronized
    fun bootstrap(keywords: List<Pair<String, String>>) {
        if (bootstrapped || userClassifier.totalSamples > 0) return
        if (keywords.isEmpty()) return

        val samples = BayesianBootstrapper.bootstrap(keywords)

        val featureCache = samples.map { sample ->
            val kwResult = com.yunian.ai.common.ContentFilter.checkFull(sample.text)
            val vecResult = com.yunian.ai.common.ContentFilter.checkVector(sample.text)
            val features = SafetyFeatureExtractor.extract(kwResult, vecResult, sample.text)
            features to sample
        }

        featureCache.forEach { (features, sample) ->
            if (sample.source == SampleSource.USER_INPUT) {
                userClassifier.addSampleDirect(features, sample.isViolation)
            }
        }

        val safeFeatures = featureCache.filter { (_, sample) -> !sample.isViolation }
        safeFeatures.forEach { (features, _) ->
            modelClassifier.addSampleDirect(features, false)
        }
        bootstrapped = true
        android.util.Log.i("SafetyVerifier", "Bootstrapped ${samples.size} samples → ${userClassifier.totalSamples} feature-tokens")
    }

    fun trainUserInput(
        rawText: String, isViolation: Boolean, label: String = "",
        kwResult: com.yunian.ai.common.ContentFilter.CheckResult? = null,
        vecResult: com.yunian.ai.common.ContentFilter.CheckResult? = null
    ) {
        val kw = kwResult ?: com.yunian.ai.common.ContentFilter.checkFull(rawText)
        val vec = vecResult ?: com.yunian.ai.common.ContentFilter.checkVector(rawText)
        val features = SafetyFeatureExtractor.extract(kw, vec, rawText)

        userClassifier.addSampleDirect(features, isViolation)
        saveUserState()
    }

    fun trainModelOutput(
        rawText: String, isViolation: Boolean, label: String = "",
        kwResult: com.yunian.ai.common.ContentFilter.CheckResult? = null,
        vecResult: com.yunian.ai.common.ContentFilter.CheckResult? = null
    ) {
        val kw = kwResult ?: com.yunian.ai.common.ContentFilter.checkFull(rawText)
        val vec = vecResult ?: com.yunian.ai.common.ContentFilter.checkVector(rawText)
        val features = SafetyFeatureExtractor.extract(kw, vec, rawText)

        modelClassifier.addSampleDirect(features, isViolation)
        saveModelState()
    }

    fun trainBatch(samples: List<SafetySample>) {
        for (s in samples) {
            if (s.source == SampleSource.USER_INPUT) trainUserInput(s.text, s.isViolation, s.label)
            else trainModelOutput(s.text, s.isViolation, s.label)
        }
    }

    fun verifyUserInput(
        rawText: String,
        kwResult: com.yunian.ai.common.ContentFilter.CheckResult,
        vecResult: com.yunian.ai.common.ContentFilter.CheckResult?
    ): SafetyScore {
        if (rawText.isBlank()) return SafetyScore(0.0, ScoreSource.USER_INPUT, explanation = "空文本")
        if (userClassifier.totalSamples == 0) return SafetyScore.neutral(ScoreSource.USER_INPUT)

        val features = SafetyFeatureExtractor.extract(kwResult, vecResult, rawText)
        val score = userClassifier.classifyTokens(features)
        val topTokens = userClassifier.topContributingTokens(features)

        return SafetyScore(
            score = score,
            source = ScoreSource.USER_INPUT,
            topTerms = topTokens,
            explanation = buildExplanation(score, topTokens, "用户输入")
        )
    }

    fun verifyUserInputNative(
        rawText: String,
        kwResult: com.yunian.ai.common.ContentFilter.CheckResult,
        vecResult: com.yunian.ai.common.ContentFilter.CheckResult?
    ): SafetyScore {
        if (rawText.isBlank()) return SafetyScore(0.0, ScoreSource.USER_INPUT, explanation = "空文本")
        if (userClassifier.totalSamples == 0) return SafetyScore.neutral(ScoreSource.USER_INPUT)

        val floatVec = SafetyFeatureExtractor.extractFloatVector(kwResult, vecResult, rawText)
        val score = userClassifier.classifyTokensNative(floatVec).toDouble()

        return SafetyScore(
            score = score,
            source = ScoreSource.USER_INPUT,
            topTerms = emptyList(),
            explanation = "Native(${"%.3f".format(score)})"
        )
    }

    suspend fun verifyUserInputAsync(
        rawText: String,
        kwResult: com.yunian.ai.common.ContentFilter.CheckResult,
        vecResult: com.yunian.ai.common.ContentFilter.CheckResult?
    ): SafetyScore = withContext(AppDispatchers.cpu) {
        verifyUserInput(rawText, kwResult, vecResult)
    }

    fun verifyModelOutput(
        rawText: String,
        kwResult: com.yunian.ai.common.ContentFilter.CheckResult,
        vecResult: com.yunian.ai.common.ContentFilter.CheckResult?,
        userContext: String = ""
    ): SafetyScore {
        if (rawText.isBlank()) return SafetyScore(0.0, ScoreSource.MODEL_OUTPUT, explanation = "空文本")
        if (modelClassifier.totalSamples == 0) return SafetyScore.neutral(ScoreSource.MODEL_OUTPUT)

        val features = SafetyFeatureExtractor.extract(kwResult, vecResult, rawText)
        val score = modelClassifier.classifyTokens(features)
        val topTokens = modelClassifier.topContributingTokens(features)

        return SafetyScore(
            score = score,
            source = ScoreSource.MODEL_OUTPUT,
            topTerms = topTokens,
            explanation = buildExplanation(score, topTokens, "模型输出")
        )
    }

    suspend fun verifyModelOutputAsync(
        rawText: String,
        kwResult: com.yunian.ai.common.ContentFilter.CheckResult,
        vecResult: com.yunian.ai.common.ContentFilter.CheckResult?,
        userContext: String = ""
    ): SafetyScore = withContext(AppDispatchers.cpu) {
        verifyModelOutput(rawText, kwResult, vecResult, userContext)
    }

    fun verifyRoundTrip(
        userInput: String,
        modelOutput: String,
        userKw: com.yunian.ai.common.ContentFilter.CheckResult,
        userVec: com.yunian.ai.common.ContentFilter.CheckResult?,
        modelKw: com.yunian.ai.common.ContentFilter.CheckResult,
        modelVec: com.yunian.ai.common.ContentFilter.CheckResult?
    ): RoundTripVerdict {
        val userScore = verifyUserInput(userInput, userKw, userVec)
        val modelScore = verifyModelOutput(modelOutput, modelKw, modelVec, userInput)
        return RoundTripVerdict(userScore, modelScore)
    }

    fun userStats(): String = "用户输入: ${userClassifier.totalSamples} 样本, 先验=${"%.3f".format(userClassifier.priorViolation())}"
    fun modelStats(): String = "模型输出: ${modelClassifier.totalSamples} 样本, 先验=${"%.3f".format(modelClassifier.priorViolation())}"

    private fun buildExplanation(score: Double, terms: List<Pair<String, Double>>, target: String): String {
        if (terms.isEmpty()) return "$target 无显著特征"
        val top = terms.take(3).joinToString("、") { (term, contrib) ->
            val dir = if (contrib > 0) "推高" else "拉低"
            "$term($dir${"%.2f".format(Math.abs(contrib))})"
        }
        return "$target 评分${"%.2f".format(score)}: $top"
    }

    private fun saveUserState() {
        prefs?.edit()?.putString(KEY_USER_STATE, serializeState(userClassifier.exportState()))?.apply()
    }

    private fun saveModelState() {
        prefs?.edit()?.putString(KEY_MODEL_STATE, serializeState(modelClassifier.exportState()))?.apply()
    }

    private fun loadUserState() {
        prefs?.getString(KEY_USER_STATE, null)?.let { deserializeState(it)?.let { userClassifier.importState(it) } }
    }

    private fun loadModelState() {
        prefs?.getString(KEY_MODEL_STATE, null)?.let { deserializeState(it)?.let { modelClassifier.importState(it) } }
    }

    private fun serializeState(state: BayesianState): String = JSONObject().apply {
        put("totalSamples", state.totalSamples)
        put("vocabSize", state.vocabSize)
        put("totalTermsViolation", state.totalTermsViolation)
        put("totalTermsSafe", state.totalTermsSafe)
        put("sampleCountViolation", state.sampleCountViolation)
        put("sampleCountSafe", state.sampleCountSafe)
        put("termFreqViolation", JSONObject(state.termFreqViolation))
        put("termFreqSafe", JSONObject(state.termFreqSafe))
    }.toString()

    private fun deserializeState(json: String): BayesianState? = try {
        val obj = JSONObject(json)
        BayesianState(
            totalSamples = obj.getInt("totalSamples"), vocabSize = obj.getInt("vocabSize"),
            totalTermsViolation = obj.getInt("totalTermsViolation"), totalTermsSafe = obj.getInt("totalTermsSafe"),
            sampleCountViolation = obj.getInt("sampleCountViolation"), sampleCountSafe = obj.getInt("sampleCountSafe"),
            termFreqViolation = jsonObjectToMap(obj.getJSONObject("termFreqViolation")),
            termFreqSafe = jsonObjectToMap(obj.getJSONObject("termFreqSafe"))
        )
    } catch (e: Exception) { null }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        json.keys().forEach { map[it] = json.getInt(it) }
        return map
    }
}
