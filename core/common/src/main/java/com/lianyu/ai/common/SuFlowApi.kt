package com.lianyu.ai.common

/**
 * SuFlow API 常量 — Clove API 对接
 * 统一管理 URL、端点路径、认证方式
 */
object SuFlowApi {
    /** SuFlow API 基础地址 — Clove API 服务器（ADB reverse 兼容） */
    const val BASE_URL = "http://127.0.0.1:9876"

    /** Auth 基础地址 */
    const val AUTH_BASE_URL = "http://127.0.0.1:9876"

    /** Chat API 基础地址 */
    const val CHAT_BASE_URL = "http://127.0.0.1:9876/v1"
    
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
