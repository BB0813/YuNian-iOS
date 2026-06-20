package com.lianyu.ai.feature.qqbot.data.network

import android.util.Log
import com.lianyu.ai.feature.qqbot.data.QQBotTokenStore
import com.lianyu.ai.feature.qqbot.data.model.QQGatewayPayload
import com.lianyu.ai.feature.qqbot.data.model.QQHelloData
import com.lianyu.ai.feature.qqbot.data.model.QQReadyData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class QQBotWebSocketClient(
    private val tokenStore: QQBotTokenStore,
    private val apiClient: QQBotApiClient,
    private val onEvent: suspend (QQGatewayPayload) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val connectMutex = Mutex()
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private val lastSequence = AtomicLong(0)
    private val reconnectAttempt = AtomicInteger(0)

    @Volatile
    private var sessionId: String? = null

    private val opDispatch = 0
    private val opHeartbeat = 1
    private val opIdentify = 2
    private val opResume = 6
    private val opReconnect = 7
    private val opInvalidSession = 9
    private val opHello = 10
    private val opHeartbeatAck = 11

    // Intents: C2C(1<<25) | GROUP_AT_MESSAGE(1<<30) | GUILDS(1<<0) | GUILD_MESSAGES(1<<9) | DIRECT_MESSAGE(1<<12) | INTERACTION(1<<26)
    private val intents = (1 shl 25) or (1 shl 30) or (1 shl 0) or (1 shl 9) or (1 shl 12) or (1 shl 26)

    suspend fun connect() = connectMutex.withLock {
        if (isConnected.get() || isConnecting.get()) return
        isConnecting.set(true)
        try {
            tokenStore.getAccount() ?: throw IllegalStateException("未配置 QQ Bot 账号")
            val restApi = apiClient.createAuthenticatedRestApi()
            val gateway = restApi.getGateway()
            if (!gateway.isSuccessful || gateway.body() == null) {
                throw IllegalStateException("获取 QQ Gateway 失败: ${gateway.code()}")
            }
            val gatewayUrl = gateway.body()!!.url
            val request = Request.Builder().url(gatewayUrl).build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.i(TAG, "WebSocket connected")
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "WebSocket closing: $code $reason")
                    cleanupConnectionState()
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "WebSocket closed: $code $reason")
                    cleanupConnectionState()
                    scheduleReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure", t)
                    cleanupConnectionState()
                    scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            isConnecting.set(false)
            scheduleReconnect()
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        webSocket?.close(1000, "manual disconnect")
        webSocket = null
        isConnected.set(false)
        isConnecting.set(false)
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }

    private fun handleMessage(text: String) {
        try {
            val payload = json.decodeFromString<QQGatewayPayload>(text)
            payload.s?.let {
                lastSequence.set(it.toLong())
                scope.launch { tokenStore.setLastSequence(it.toLong()) }
            }
            when (payload.op) {
                opDispatch -> {
                    if (payload.t == "READY") {
                        payload.d?.let {
                            val ready = json.decodeFromJsonElement<QQReadyData>(it)
                            sessionId = ready.sessionId
                            // 内存已更新，异步持久化，不阻塞回调线程
                            scope.launch { tokenStore.setSessionId(ready.sessionId) }
                            Log.i(TAG, "READY: sessionId=${ready.sessionId}, bot=${ready.user?.username}")
                        }
                    }
                    scope.launch { onEvent(payload) }
                }
                opHello -> {
                    val hello = payload.d?.let { json.decodeFromJsonElement<QQHelloData>(it) }
                    // [PERF] 优先使用内存中的 sessionId/seq，避免在 WebSocket 回调线程 runBlocking 读 DataStore
                    val savedSessionId = sessionId
                    val savedSeq = lastSequence.get()
                    if (!savedSessionId.isNullOrBlank() && savedSeq > 0) {
                        sendResume(savedSessionId, savedSeq)
                    } else {
                        sendIdentify()
                    }
                    startHeartbeat(hello?.heartbeatInterval ?: 41250)
                    isConnected.set(true)
                    isConnecting.set(false)
                    reconnectAttempt.set(0)
                }
                opReconnect -> {
                    Log.w(TAG, "Server requested reconnect")
                    reconnect()
                }
                opInvalidSession -> {
                    Log.w(TAG, "Invalid session, clearing session state")
                    sessionId = null
                    scope.launch {
                        tokenStore.setSessionId(null)
                        tokenStore.setLastSequence(0)
                    }
                    reconnect()
                }
                opHeartbeatAck -> {
                    // ignore
                }
                else -> Log.d(TAG, "Unhandled op: ${payload.op}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle gateway message: $text", e)
        }
    }

    private fun sendIdentify() {
        // [PERF] 使用内存缓存的 token，避免在 WebSocket 回调线程 runBlocking 读 DataStore
        val token = apiClient.getCachedToken()
        if (token == null) {
            Log.e(TAG, "Cannot send identify: cached token is null/expired")
            return
        }
        val d = JsonObject(
            mapOf(
                "token" to JsonPrimitive("QQBot $token"),
                "intents" to JsonPrimitive(intents),
                "shard" to JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(1))),
                "properties" to JsonObject(
                    mapOf(
                        PROPERTY_OS to JsonPrimitive("android"),
                        PROPERTY_BROWSER to JsonPrimitive("LianYuQQBot"),
                        PROPERTY_DEVICE to JsonPrimitive("LianYuAndroid")
                    )
                )
            )
        )
        val payload = QQGatewayPayload(op = opIdentify, d = d)
        send(json.encodeToString(payload))
    }

    private fun sendResume(sessionIdValue: String, seq: Long) {
        val token = apiClient.getCachedToken()
        if (token == null) {
            Log.e(TAG, "Cannot send resume: cached token is null/expired")
            return
        }
        val d = JsonObject(
            mapOf(
                "token" to JsonPrimitive("QQBot $token"),
                "session_id" to JsonPrimitive(sessionIdValue),
                "seq" to JsonPrimitive(seq)
            )
        )
        val payload = QQGatewayPayload(op = opResume, d = d)
        send(json.encodeToString(payload))
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            val period = (intervalMs * 0.8).toLong().coerceAtLeast(5000)
            while (isActive && isConnected.get()) {
                delay(period)
                if (isConnected.get()) {
                    send(json.encodeToString(QQGatewayPayload(op = opHeartbeat, d = JsonPrimitive(lastSequence.get()))))
                }
            }
        }
    }

    private fun send(text: String) {
        val sent = webSocket?.send(text) ?: false
        if (!sent) Log.w(TAG, "Failed to send websocket message")
    }

    private fun cleanupConnectionState() {
        isConnected.set(false)
        isConnecting.set(false)
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val delayMs = (reconnectAttempt.incrementAndGet() * 2000L).coerceAtMost(30000L)
            delay(delayMs)
            if (isActive) {
                connect()
            }
        }
    }

    private fun reconnect() {
        disconnect()
        scheduleReconnect()
    }

    companion object {
        private const val TAG = "QQBotWS"
        private const val PROPERTY_OS = "\$os"
        private const val PROPERTY_BROWSER = "\$browser"
        private const val PROPERTY_DEVICE = "\$device"
    }
}
