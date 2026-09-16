package com.yunian.ai.feature.localmodel

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.common.perf.PerfBoost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@OptIn(ExperimentalApi::class)
class LocalAiService private constructor(private val context: Context) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var engineModelId: String? = null
    private var refCount = 0

    /**
     * 本地 LLM 推理专用调度。
     *
     * 本地大模型推理是**纯 CPU 密集型**工作，原实现跑在 IO 池上存在调度失配：
     * IO 池默认上限 64 线程，CPU 密集任务挤进去既难以被调度到大核、又放大了线程竞争与
     * 上下文切换开销。现统一收敛到 [AppDispatchers.inference]（串行 view）：
     * 保证同一时刻只有一段推理在跑（引擎本身串行），且与 IO 池的阻塞任务相互隔离。
     */
    private val inferenceDispatcher: CoroutineDispatcher = AppDispatchers.inference

    @Volatile
    private var _activeModel: LocalModel = LocalModelCatalog.default
    val activeModel: LocalModel get() = _activeModel
    val applicationContext: Context get() = context.applicationContext

    companion object {
        private const val TAG = "LocalAiService"
        private const val PREFS_NAME = "local_ai_engine_state"
        private const val KEY_ENGINE_DISABLED = "engine_disabled"

        /** ADPF 推理目标工作时长：本地 LLM 单轮生成量级取 80ms（仅作系统提频参考）。 */
        private const val LOCAL_INFERENCE_TARGET_NANOS = 80_000_000L

        @Volatile
        private var instance: LocalAiService? = null
        private val INSTANCE_LOCK = Any()

        @Volatile
        var isNativeLibrarySupported: Boolean = true
            private set

        fun getInstance(context: Context): LocalAiService {
            return instance ?: synchronized(INSTANCE_LOCK) {
                instance ?: LocalAiService(context.applicationContext).also {
                    instance = it
                }
            }
        }

        private fun persistDisable(context: Context) {
            isNativeLibrarySupported = false
            runCatching {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_ENGINE_DISABLED, true)
                    .commit()
            }
            Log.w(TAG, "Local model engine permanently disabled due to previous crash")
        }
    }

    init {
        checkNativeLibrarySupport()
    }

    private fun checkNativeLibrarySupport() {

        val abis = runCatching { Build.SUPPORTED_ABIS }.getOrNull()
        val primaryAbi = abis?.firstOrNull()?.lowercase()
        if (primaryAbi != null && primaryAbi.startsWith("x86")) {
            isNativeLibrarySupported = false
            Log.w(TAG, "Local model disabled: x86 ABI ($primaryAbi) not supported")
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ENGINE_DISABLED, false)) {
            isNativeLibrarySupported = false
            Log.w(TAG, "Local model disabled: engine previously crashed on this device")
            return
        }
    }

    fun setActiveModel(model: LocalModel) {
        _activeModel = model
    }

    fun acquire() {
        synchronized(this) {
            refCount++
        }
    }

    suspend fun close() {
        val shouldShutdown = synchronized(this) {
            if (refCount <= 0) {
                false
            } else {
                refCount--
                refCount == 0
            }
        }
        if (shouldShutdown) {
            mutex.withLock {
                engine?.close()
                engine = null
                engineModelId = null
            }
        }
    }

    suspend fun shutdownEngine() {
        mutex.withLock {
            engine?.close()
            engine = null
            engineModelId = null
        }
    }

    suspend fun generate(
        systemPrompt: String,
        historyPrompt: String,
        userPrompt: String,
        model: LocalModel = _activeModel
    ): String = withContext(inferenceDispatcher) {
        if (!isNativeLibrarySupported) {
            throw IllegalStateException(
                "Local model is not supported on this device (${Build.SUPPORTED_ABIS.firstOrNull()})"
            )
        }

        val modelFile = model.modelFile(context)
        require(modelFile.exists()) { "${model.displayName} has not been downloaded." }

        val activeEngine = mutex.withLock {
            if (engine != null && engineModelId == model.id) {
                return@withLock engine!!
            }

            engine?.close()
            engine = null
            engineModelId = null
            try {
                Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = context.cacheDir.absolutePath
                    )
                ).also {
                    it.initialize()
                    engine = it
                    engineModelId = model.id
                }
            } catch (e: UnsatisfiedLinkError) {
                persistDisable(context)
                Log.e(TAG, "UnsatisfiedLinkError loading litertlm native library", e)
                throw IllegalStateException(
                    "Local model native library failed to load. It has been disabled for this device."
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize litertlm engine", e)
                throw IllegalStateException(
                    "Failed to initialize local model engine: ${e.message}"
                )
            }
        }

        val config = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            samplerConfig = SamplerConfig(
                topK = 1,
                topP = 0.95,
                temperature = 0.3
            )
        )

        // ADPF：把本次推理线程声明为关键工作负载，交由系统提频/摆核。
        // 该 API 是「一次性阻塞调用」（无分块回调），故在生成结束时上报一次总耗时，
        // 频率克制；仅在支持时创建，否则零开销。
        val perfSession = if (PerfBoost.isSupported) {
            PerfBoost.createSession(
                tag = "local-llm",
                targetWorkDurationNanos = LOCAL_INFERENCE_TARGET_NANOS,
                threadIds = intArrayOf(PerfBoost.currentThreadId()),
            )
        } else {
            null
        }
        val inferenceStartedNanos = SystemClock.elapsedRealtimeNanos()
        try {
            activeEngine.createConversation(config).use { conversation ->
                val prompt = buildString {
                    if (historyPrompt.isNotBlank()) {
                        appendLine(historyPrompt)
                        appendLine()
                    }
                    append(userPrompt)
                }.trim()

                val response = conversation.sendMessage(prompt)
                conversation.renderMessageIntoString(response).trim()
            }
        } finally {
            perfSession?.reportActual(SystemClock.elapsedRealtimeNanos() - inferenceStartedNanos)
            perfSession?.close()
        }
    }
}
