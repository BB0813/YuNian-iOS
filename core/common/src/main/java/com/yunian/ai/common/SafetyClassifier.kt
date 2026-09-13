package com.yunian.ai.common

interface SafetyClassifier {

    suspend fun classify(text: String): ContentFilter.ViolationLevel
}
