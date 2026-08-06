package com.lianyu.ai.common

/**
 * SuFlow API 常量 — Clove API 对接
 * 统一管理 URL、端点路径、认证方式
 */
object SuFlowApi {
    // ── 生产指向: suflow.cloud (经 nginx 443, TLS 证书固定) ──
    // 服务器 154.94.237.51, API 监听 127.0.0.1:9876, 由 nginx 80/443 转发
    const val BASE_URL = "https://suflow.cloud"

    /** Auth 基础地址 */
    const val AUTH_BASE_URL = "https://suflow.cloud"

    /** Chat API 基础地址 */
    const val CHAT_BASE_URL = "https://suflow.cloud/v1"

    /** Chat Completions 端点 */
    const val CHAT_PATH = "/chat/completions"

    /** 模型列表端点 */
    const val MODELS_PATH = "/models"

    /** 用量查询端点 */
    const val USAGE_PATH = "/usage"

    /** Handshake 端点（客户端设备绑定 + 无感注册） */
    const val HANDSHAKE_PATH = "/api/auth/handshake"

    /** Key 分发端点 */
    const val KEYS_FETCH_PATH = "/api/keys/fetch"

    /** Clove App 自动注册端点 */
    const val CLOVE_PROVISION = "/app/v1/provision"

    /** Clove 用户状态查询 */
    const val CLOVE_KEY_FETCH = "/api/keys/fetch"

    /** 请求超时（秒） */
    const val TIMEOUT_SECONDS = 30L

    /** 连接测试模型 */
    const val TEST_MODEL = "gpt-4o-mini"

    /** 设备指纹哈希前缀（用于无感注册） */
    fun deviceFingerprint(): String {
        val fp = android.os.Build.FINGERPRINT + "|" +
                 android.os.Build.MODEL + "|" +
                 android.os.Build.SERIAL
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(fp.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}