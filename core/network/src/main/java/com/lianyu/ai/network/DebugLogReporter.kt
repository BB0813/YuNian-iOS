package com.lianyu.ai.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DebugLogReporter — 调试日志上报器
 *
 * 将关键调试日志发送到本地 Debug Server，用于远程诊断。
 * 仅在调试时使用，不影响生产环境。
 */
object DebugLogReporter {

    private const val TAG = "DebugLog"
    private const val SERVER_URL = "http://10.188.248.127:8765/log"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    @JvmStatic
    fun report(level: String, tag: String, message: String, extra: Map<String, String>? = null) {
        try {
            val json = JSONObject()
            json.put("timestamp", System.currentTimeMillis())
            json.put("level", level)
            json.put("tag", tag)
            json.put("message", message)
            extra?.let {
                val extraJson = JSONObject()
                it.forEach { (k, v) -> extraJson.put(k, v) }
                json.put("extra", extraJson)
            }

            val request = Request.Builder()
                .url(SERVER_URL)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    Log.d(TAG, "Report failed: ${e.message}")
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.d(TAG, "Report error: ${e.message}")
        }
    }

    @JvmStatic
    fun d(tag: String, message: String, extra: Map<String, String>? = null) {
        report("DEBUG", tag, message, extra)
    }

    @JvmStatic
    fun e(tag: String, message: String, extra: Map<String, String>? = null) {
        report("ERROR", tag, message, extra)
    }

    @JvmStatic
    fun w(tag: String, message: String, extra: Map<String, String>? = null) {
        report("WARN", tag, message, extra)
    }
}
