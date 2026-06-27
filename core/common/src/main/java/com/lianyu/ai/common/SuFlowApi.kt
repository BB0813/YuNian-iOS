package com.lianyu.ai.common

/**
 * SuFlow API 常量 — Clove API 对接
 * 统一管理 URL、端点路径、认证方式
 */
object SuFlowApi {
    /** SuFlow API 基础地址 — Clove API 服务器 */
    const val BASE_URL = "http://192.168.5.180:9876"

    /** Auth 基础地址 */
    const val AUTH_BASE_URL="http...

    /** Chat API 基础地址 */
    const val CHAT_BASE_URL="http...

    /** 请求超时(seconds) */
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