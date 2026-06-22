package com.lianyu.ai.network

import okhttp3.CertificatePinner

/**
 * Certificate pinning for all API endpoints.
 *
 * Each pin is the SHA-256 hash of the server's SubjectPublicKeyInfo (SPKI).
 * Pins survive certificate renewal as long as the private key stays the same.
 *
 * To add/update pins: run tools/extract_cert_pins.py
 */
object CertificatePins {

    // ── SuFlowAPI (self-hosted) ──
    // HTTP only for now; pins to be added when HTTPS is configured

    // ── OpenAI ──
    private const val OPENAI_PIN = "sha256/9g+mtyVAhL3wQl0JVOKDKS5NZtYWty5pQuLjWkSlTCU="

    // ── Anthropic ──
    private const val ANTHROPIC_PIN = "sha256/LQfSFZEKft9yS7oIKOIO5Vu7Fj33L2H3SDN8/uADlWg="

    // ── DeepSeek ──
    private const val DEEPSEEK_PIN = "sha256/jD4HoReqi4yPTndb5/Ks7bDUycyp1uN11oii4qwracs="

    // ── Google / Gemini ──
    private const val GEMINI_PIN = "sha256/AGcONCXR6dr82pNjwrd5xoDQnWWv5j5jdkxyrxXgDro="

    // ── DashScope (Alibaba) ──
    private const val DASHSCOPE_PIN = "sha256/WZVJFj4+3elgfAAI/zW+L9mKCgh+6gck7f6zYoUC0Yg="

    // ── Moonshot / Kimi ──
    private const val KIMI_PIN = "sha256/kPjMPOLocq+5yBiG1tDVmTqsthmK8BKarCJvdXglzis="

    // ── OpenRouter ──
    private const val OPENROUTER_PIN = "sha256/SUYfKXVXd5065Ui4N6JcivgvHidyKergt1e4y1Lswhc="

    // ── Groq ──
    private const val GROQ_PIN = "sha256/NSUwR6RBgH3a1fgXJYTEtVVUxeRgIIRwhID90KEv4Qc="

    // ── SiliconFlow ──
    private const val SILICONFLOW_PIN = "sha256/NG2+7f12uitxN5No4ZUkVg9t4lq0JeMD89pxW+7EhiA="

    // ── Zhipu / BigModel ──
    private const val ZHIPU_PIN = "sha256/efpviN4CHX6YeOqbLWsBTvnJqjULfZE/j9OAUrm/qH0="

    // ── Xiaomi MiMo ──
    private const val XIAOMI_PIN = "sha256/H7ox+nLEX/IGOH8nZwl1Yzus/kqXmmbwXVvQA/lklRU="

    val certificatePinner: CertificatePinner = CertificatePinner.Builder()
        // SuFlowAPI: no pinning needed (HTTP-only in current deployment)
        // Third-party API endpoints
        .add("api.openai.com", OPENAI_PIN)
        .add("api.anthropic.com", ANTHROPIC_PIN)
        .add("api.deepseek.com", DEEPSEEK_PIN)
        .add("generativelanguage.googleapis.com", GEMINI_PIN)
        .add("dashscope.aliyuncs.com", DASHSCOPE_PIN)
        .add("api.moonshot.cn", KIMI_PIN)
        .add("openrouter.ai", OPENROUTER_PIN)
        .add("api.groq.com", GROQ_PIN)
        .add("api.siliconflow.cn", SILICONFLOW_PIN)
        .add("open.bigmodel.cn", ZHIPU_PIN)
        .add("api.xiaomimimo.com", XIAOMI_PIN)
        .build()
}
