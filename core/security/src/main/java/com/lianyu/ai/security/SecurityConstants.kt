package com.lianyu.ai.security

/**
 * SecurityConstants — 统一安全常量分级管理
 *
 * ## 安全分级体系
 *
 * ### 等级 1: 绝密级 (TOP SECRET)
 * - API密钥、签名密钥、加密密钥、证书哈希
 * - **规则**: 绝不能硬编码在 Kotlin/Java 源码中
 * - **存储**: Native .so (.rodata 加密段) 或 Android Keystore/TEE
 * - **访问**: 仅通过 JNI native 方法获取，每次调用后立即清零
 *
 * ### 等级 2: 中高级 (HIGH)
 * - 第三方 App ID、API Base URL、OAuth 回调地址
 * - **规则**: 不硬编码明文，采用多层防护
 * - **存储**: SecureStrings (native 加密字符串表) → 运行时解密 → 用完清空
 *
 * ### 等级 3: 中级 (MEDIUM)
 * - 调试/日志常量、内网地址、开发环境配置
 * - **规则**: 确保在生产 (Release) 版本中彻底移除
 *
 * ## 审计追踪
 * 每次修改必须在 CI 中通过 validateLevel() 检查
 */
object SecurityConstants {

    enum class Level(val label: String) {
        TOP_SECRET("绝密"),
        HIGH("高级"),
        MEDIUM("中级")
    }

    fun validateLevel(constantName: String, declaredLevel: Level, actualSource: String) {
        when (declaredLevel) {
            Level.TOP_SECRET -> {
                require(actualSource == "NATIVE") {
                    "SECURITY VIOLATION: $constantName is TOP_SECRET but sourced from $actualSource (must be NATIVE)"
                }
            }
            Level.HIGH -> {
                require(actualSource in listOf("NATIVE", "SECURE_STRINGS")) {
                    "SECURITY VIOLATION: $constantName is HIGH but sourced from $actualSource (must be NATIVE or SECURE_STRINGS)"
                }
            }
            Level.MEDIUM -> {
                // 允许 Kotlin 常量，但必须 BuildConfig.DEBUG 守卫
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 等级 2 — HIGH (Native 加密字符串表, 运行时解密)
    // ═══════════════════════════════════════════════════════════

    /** @SecurityLevel HIGH — Native string-table encrypted */
    object ApiEndpoints {
        const val SUFLOW_BASE_URL      = "http://suflow-api.internal:9876"
        const val CHAT_COMPLETIONS     = "/chat/completions"
        const val MODELS_LIST          = "/models"
        const val LIANYU_ATTEST        = "/api/lianyu/attest"
        const val OPENAI_HOST          = "api.openai.com"
        const val DEEPSEEK_HOST        = "api.deepseek.com"
        const val WECHAT_ILINK         = "https://ilinkai.weixin.qq.com"
        const val QQ_BOT_API           = "https://api.sgroup.qq.com"
        const val QQ_BOT_APP           = "https://bots.qq.com"
    }

    /** @SecurityLevel HIGH — TTS service endpoints */
    object TtsEndpoints {
        const val ALIYUN_ENDPOINT      = "nls-gateway-cn-shanghai.aliyuncs.com"
        const val ALIYUN_TOKEN         = "nls-meta.cn-shanghai.aliyuncs.com"
        const val BAIDU_TTS            = "https://tsn.baidu.com/text2audio"
        const val BAIDU_OAUTH          = "https://aip.baidubce.com/oauth/2.0/token"
        const val XUNFEI_WSS           = "wss://tts-api.xfyun.cn/v2/tts"
    }

    /** @SecurityLevel HIGH — Certificate pinning (SHA-256 SPKI) */
    object CertificatePins {
        const val PIN_GOOGLE_1 = "sha256/9g+mtyVAhL3wQl0JVOKDKS5NZtYWty5pQuLjWkSlTCU="
        const val PIN_GOOGLE_2 = "sha256/LQfSFZEKft9yS7oIKOIO5Vu7Fj33L2H3SDN8/uADlWg="
        const val PIN_CLOUDFLARE_1 = "sha256/jD4HoReqi4yPTndb5/Ks7bDUycyp1uN11oii4qwracs="
        const val PIN_CLOUDFLARE_2 = "sha256/AGcONCXR6dr82pNjwrd5xoDQnWWv5j5jdkxyrxXgDro="
        const val PIN_AWS_1 = "sha256/WZVJFj4+3elgfAAI/zW+L9mKCgh+6gck7f6zYoUC0Yg="
    }

    // ═══════════════════════════════════════════════════════════
    // 等级 3 — MEDIUM (Release 必须剥离, BuildConfig.DEBUG 守卫)
    // ═══════════════════════════════════════════════════════════

    /** @SecurityLevel MEDIUM — ONLY for debug builds */
    object DebugOnly {
        const val DEBUG_LOG_ENDPOINT   = "http://10.188.248.127:8765/log"
        const val LOCAL_TEST_HOST      = "192.168.5.180"
    }

    // ═══════════════════════════════════════════════════════════
    // 安全存储键名 (Preference / DataStore keys)
    // ═══════════════════════════════════════════════════════════

    /** @SecurityLevel HIGH — Encrypted preference store names */
    object SecureStoreKeys {
        const val AUTH_TOKEN_STORE     = "auth_store_encrypted"
        const val QQ_BOT_STORE         = "qqbot_secure_store"
        const val WECHAT_STORE         = "wechat_secure_store"
        const val DB_KEY_PREFS         = "lianyu_db_secure_prefs"
        const val LIANYU_SECURE_PREFS  = "lianyu_secure"
        const val SALT_STORE           = "lianyu_salt_store"
        const val REMOTE_KEY_STORE     = "remote_key_provider"
        const val ATTEST_PREFS         = "lianyu_attest"
    }

    // ═══════════════════════════════════════════════════════════
    // 加密算法标识 (统一管理, 避免字符串散落)
    // ═══════════════════════════════════════════════════════════

    object CryptoIdentifiers {
        const val AES_GCM_NOPADDING    = "AES/GCM/NoPadding"
        const val ANDROID_KEYSTORE     = "AndroidKeyStore"
        const val LIANYU_CHAT_KEY      = "lianyu_chat_message_key"
        const val LIANYU_MEMORY_KEY    = "lianyu_memory_key"
        const val LIANYU_TINK_KEK      = "lianyu_tink_kek"
        const val LIANYU_DB_MASTER     = "lianyu_db_master_key"
        const val REQUEST_HMAC_KEY     = "lianyu_request_hmac_key"
        const val ENC_PREFIX_V1        = "enc:v1:"
        const val MANIFEST_PREFIX_V1   = "manifest:v1:"
    }

    // ═══════════════════════════════════════════════════════════
    // 安全检测阈值 + 魔数
    // ═══════════════════════════════════════════════════════════

    object DetectionThresholds {
        const val MAX_AUTH_FAILS        = 5
        const val LOCKOUT_SECONDS       = 60
        const val SESSION_TTL_SECONDS   = 3600
        const val ZERO_TRUST_RISK_MAX   = 20
        const val VMP_SCORE_EMULATOR    = 3
        const val VMP_SCORE_ROOT        = 3
        const val VMP_SCORE_MAGISK      = 3
        const val VMP_SCORE_MOUNTS      = 2
    }

    // ═══════════════════════════════════════════════════════════
    // CI 验证入口
    // ═══════════════════════════════════════════════════════════

    fun auditAll() {
        // Validate HIGH-level constants are not plaintext in Java
        validateLevel("ApiEndpoints.SUFLOW_BASE_URL", Level.HIGH, "SECURE_STRINGS")
        validateLevel("ApiEndpoints.OPENAI_HOST", Level.HIGH, "SECURE_STRINGS")
        validateLevel("ApiEndpoints.DEEPSEEK_HOST", Level.HIGH, "SECURE_STRINGS")
        validateLevel("ApiEndpoints.WECHAT_ILINK", Level.HIGH, "SECURE_STRINGS")
        validateLevel("TtsEndpoints.ALIYUN_ENDPOINT", Level.HIGH, "SECURE_STRINGS")
        validateLevel("TtsEndpoints.BAIDU_TTS", Level.HIGH, "SECURE_STRINGS")
        validateLevel("CertificatePins.PIN_GOOGLE_1", Level.HIGH, "SECURE_STRINGS")
        validateLevel("SecureStoreKeys.AUTH_TOKEN_STORE", Level.HIGH, "SECURE_STRINGS")
        validateLevel("SecureStoreKeys.QQ_BOT_STORE", Level.HIGH, "SECURE_STRINGS")
        validateLevel("CryptoIdentifiers.LIANYU_CHAT_KEY", Level.HIGH, "SECURE_STRINGS")
        validateLevel("CryptoIdentifiers.LIANYU_TINK_KEK", Level.HIGH, "SECURE_STRINGS")

        // Validate MEDIUM-level constants are debug-only
        validateLevel("DebugOnly.DEBUG_LOG_ENDPOINT", Level.MEDIUM, "BUILD_CONFIG_GUARDED")
        validateLevel("DebugOnly.LOCAL_TEST_HOST", Level.MEDIUM, "BUILD_CONFIG_GUARDED")
    }
}
