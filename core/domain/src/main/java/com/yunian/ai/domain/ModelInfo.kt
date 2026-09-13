package com.yunian.ai.domain

data class ModelInfo(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedBytes: Long
)
