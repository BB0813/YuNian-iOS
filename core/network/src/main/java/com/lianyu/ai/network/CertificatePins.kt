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

    // ── Clove (self-hosted) ──
    private const val CLOVE_PIN = "sha256/gjR+Zqma3Qv/1DhbeH/UpPoonupgZwYjN9zGOE/H7rY="
    private const val CLOVE_BACKUP_PIN = "sha256/gjR+Zqma3Qv/1DhbeH/UpPoonupgZwYjN9zGOE/H7rY="

    // ── OpenAI ──
    private const val OPENAI_PIN = "sha256/9LYV5mq0LcNGLO/QLoMxNs69wYMRKxkKWPSG3phd5s4="

    // ── Anthropic ──
    private const val ANTHROPIC_PIN = "sha256/GQxBJn4g6qXKIxmWciScnMyQ0EOZXiJjMR7DgWhVuNk="

    // ── DeepSeek ──
    private const val DEEPSEEK_PIN = "sha256/jD4HoReqi4yPTndb5/Ks7bDUycyp1uN11oii4qwracs="

    // ── Google / Gemini ──
    private const val GEMINI_PIN = "sha256/9LYV5mq0LcNGLO/QLoMxNs69wYMRKxkKWPSG3phd5s4="

    // ── DashScope (Alibaba) ──
    private const val DASHSCOPE_PIN = "sha256/nmIf6+o1f/RGC5G/iwtL/mVNLhAO28dHcOdlGdc0gw4="

    // ── Moonshot / Kimi ──
    private const val KIMI_PIN = "sha256/MQKN8XnIDLqFu4zsN5+d1jr2kNbYenAxIcn/Z5ORtQE="

    // ── OpenRouter ──
    private const val OPENROUTER_PIN = "sha256/hMBfVBKy9jV8IsH9P500W5rALpklRJGgW1ibRlcNO6k="

    // ── Groq ──
    private const val GROQ_PIN = "sha256/2d+R1d/l/Z5dMvMgPUHHhG1nGve6JUVgfEqt4kmxzYo="

    // ── SiliconFlow ──
    private const val SILICONFLOW_PIN = "sha256/vXB1qrsS4TN88P1SONUexEXmTcu7naD62k3xl+rdht8="

    // ── Zhipu / BigModel ──
    private const val ZHIPU_PIN = "sha256/tyW/LNnbjyel1+zlerg1728UiPpmYpVaDbGK6e+ySNI="

    // ── Xiaomi MiMo ──
    private const val XIAOMI_PIN = "sha256/9LYV5mq0LcNGLO/QLoMxNs69wYMRKxkKWPSG3phd5s4="

    val certificatePinner: CertificatePinner = CertificatePinner.Builder()
        // Self-hosted
        .add("clove.dpdns.org", CLOVE_PIN, CLOVE_BACKUP_PIN)
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
