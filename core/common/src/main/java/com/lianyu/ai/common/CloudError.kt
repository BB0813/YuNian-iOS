package com.lianyu.ai.common

import org.json.JSONObject

/**
 * 统一云端错误模型。
 *
 * 服务端错误有两种信封，本类统一解析：
 * 1. 标准 ErrorResponse:  `{"error": {"message": "...", "type": "forbidden", "code": "cloud_service_disabled"}}`
 * 2. handshake 信封:      `{"ok": false, "error": "cloud_service_disabled", "message": "云端服务尚未开启"}`
 */
data class CloudError(
    /** 机器可读错误码（如 `cloud_service_disabled`、`app_key_mismatch`）。 */
    val code: String,
    /** 服务端返回的人类可读信息（可能为 null）。 */
    val message: String?,
    /** 服务端错误类型（`auth_error` / `forbidden` / ...），标准信封才有。 */
    val type: String? = null
) {
    companion object {
        /** 服务器开关关闭时返回的错误码。 */
        const val CLOUD_SERVICE_DISABLED = "cloud_service_disabled"
        /** 兼容性路由 ID 不匹配（认证失败）。 */
        const val APP_KEY_MISMATCH = "app_key_mismatch"
        /** 内置云端访问策略拒绝（客户端本地安全策略）。 */
        const val BUILTIN_ACCESS_DENIED = "builtin_cloud_access_denied"
        const val NETWORK_ERROR = "network_error"

        /**
         * 从任意响应体文本解析 [CloudError]。无法解析时返回 null。
         */
        fun parse(body: String?, statusCode: Int = 0): CloudError? {
            if (body.isNullOrBlank()) return null
            return try {
                val json = JSONObject(body)

                // 1. 标准 ErrorResponse 信封：{error:{message,type,code}}
                json.optJSONObject("error")?.let { err ->
                    val code = err.optString("code").ifEmpty { err.optString("type") }
                    val message = err.optString("message").ifEmpty { null }
                    if (code.isNotBlank()) {
                        return CloudError(
                            code = code,
                            message = message,
                            type = err.optString("type").ifEmpty { null }
                        )
                    }
                }

                // 2. handshake 信封：{ok:false, error:"...", message:"..."}
                if (!json.optBoolean("ok", true)) {
                    val code = json.optString("error").ifEmpty { return null }
                    val message = json.optString("message").ifEmpty { null }
                    return CloudError(code = code, message = message)
                }

                null
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 从 HttpURLConnection 错误流读取并解析错误。无法读取时返回 null。
         */
        fun parseErrorStream(connection: java.net.HttpURLConnection, statusCode: Int): CloudError? {
            val body = try {
                connection.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
            return parse(body, statusCode)
        }
    }

    /** 面向用户的友好提示；云端未开启时优先返回明确文案。 */
    fun friendlyMessage(): String = when (code) {
        CLOUD_SERVICE_DISABLED -> "云端服务尚未开启"
        APP_KEY_MISMATCH -> "应用凭证校验失败"
        BUILTIN_ACCESS_DENIED -> "云端访问已被安全策略禁用"
        NETWORK_ERROR -> "网络连接失败"
        else -> message?.takeIf { it.isNotBlank() } ?: "请求失败（$code）"
    }

    /** 是否因服务器关闭了 LianYu 客户端访问。 */
    val isCloudServiceDisabled: Boolean get() = code == CLOUD_SERVICE_DISABLED
}
