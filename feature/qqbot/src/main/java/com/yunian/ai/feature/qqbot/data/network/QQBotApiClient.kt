package com.yunian.ai.feature.qqbot.data.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.yunian.ai.feature.qqbot.data.QQBotTokenStore
import com.yunian.ai.feature.qqbot.data.model.QQBotAccount
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class QQBotApiClient(private val tokenStore: QQBotTokenStore) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // 主动消息的 msg_id/msg_seq/message_reference 必须真正缺席；null 字段不能进请求体。
        explicitNulls = false
    }
    private val tokenMutex = Mutex()

    private val baseOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)

            .build()
    }

    val authApi: QQBotAuthApi by lazy {
        Retrofit.Builder()
            .baseUrl(AUTH_BASE_URL)
            .client(baseOkHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(QQBotAuthApi::class.java)
    }

    @Volatile
    private var cachedRestApi: QQBotRestApi? = null
    @Volatile
    private var cachedToken: String? = null
    @Volatile
    private var cachedAccount: QQBotAccount? = null
    @Volatile
    private var cachedTokenExpireAt: Long = 0L

    suspend fun getOrRefreshToken(account: QQBotAccount): String {
        val cached = tokenStore.getAccessToken()
        val expireAt = tokenStore.getTokenExpireAt()
        if (!cached.isNullOrBlank() && System.currentTimeMillis() < expireAt - TOKEN_REFRESH_MARGIN_MS) {
            return cached
        }
        return tokenMutex.withLock {
            val doubleCheck = tokenStore.getAccessToken()
            val doubleCheckExpire = tokenStore.getTokenExpireAt()
            if (!doubleCheck.isNullOrBlank() && System.currentTimeMillis() < doubleCheckExpire - TOKEN_REFRESH_MARGIN_MS) {
                return@withLock doubleCheck
            }
            val response = authApi.getAppAccessToken(
                TokenRequest(appId = account.appId, clientSecret = account.clientSecret)
            )
            if (!response.isSuccessful || response.body() == null) {
                throw IllegalStateException("获取 QQ Bot AccessToken 失败: ${response.code()} ${response.errorBody()?.string()}")
            }
            val body = response.body()!!
            val expireAtMs = System.currentTimeMillis() + body.expiresIn * 1000
            tokenStore.setAccessToken(body.accessToken)
            tokenStore.setTokenExpireAt(expireAtMs)

            // clearApiCache() 会把 cachedToken 一并置空，因此回填必须在它之后。
            //
            // 不回填的后果（回归根因）：cachedToken 此前只由 createAuthenticatedRestApi() 赋值，
            // 而它只在「发起一次 REST 调用」时才被调到。重启后尚无任何 REST 调用，
            // cachedToken 恒为 null → QQBotWebSocketClient 的 sendIdentify()/sendResume()
            // 走 getCachedToken() 全部拿到 null 并返回 false → refreshTokenAndRetryHandshake()
            // 虽然刷新出了新 token，却只丢弃返回值（握手只认 getCachedToken()）
            // → 刷新后依旧发不出握手 → reconnect() → 无限重连且永远连不上。
            clearApiCache()
            cachedToken = body.accessToken
            cachedTokenExpireAt = expireAtMs

            body.accessToken
        }
    }

    suspend fun createAuthenticatedRestApi(): QQBotRestApi {
        val account = tokenStore.getAccount() ?: throw IllegalStateException("未配置 QQ Bot 账号")
        val cachedAccountLocal = cachedAccount
        val cachedTokenLocal = cachedToken
        val cachedApiLocal = cachedRestApi
        if (cachedAccountLocal == account && cachedTokenLocal != null && cachedApiLocal != null) {
            if (System.currentTimeMillis() < cachedTokenExpireAt - TOKEN_REFRESH_MARGIN_MS) {
                return cachedApiLocal
            }
        }
        val token = getOrRefreshToken(account)
        val expireAt = tokenStore.getTokenExpireAt()
        val api = buildRestApi(account, token)
        cachedAccount = account
        cachedToken = token
        cachedTokenExpireAt = expireAt
        cachedRestApi = api
        return api
    }

    fun clearApiCache() {
        cachedRestApi = null
        cachedToken = null
        cachedAccount = null
        cachedTokenExpireAt = 0L
    }

    fun getCachedToken(): String? {
        val token = cachedToken ?: return null
        if (token.isBlank() || System.currentTimeMillis() >= cachedTokenExpireAt - TOKEN_REFRESH_MARGIN_MS) return null
        return token
    }

    private fun buildRestApi(account: QQBotAccount, token: String): QQBotRestApi {
        val client = baseOkHttpClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", "QQBot $token")
                    .header("X-Union-Appid", account.appId)
                    .build()
                chain.proceed(request)
            }

            .build()

        return Retrofit.Builder()
            .baseUrl(API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(QQBotRestApi::class.java)
    }

    private suspend fun refreshToken(account: QQBotAccount): String {
        val response = authApi.getAppAccessToken(
            TokenRequest(appId = account.appId, clientSecret = account.clientSecret)
        )
        if (!response.isSuccessful || response.body() == null) {
            throw IllegalStateException("刷新 QQ Bot AccessToken 失败: ${response.code()}")
        }
        val body = response.body()!!
        val expireAtMs = System.currentTimeMillis() + body.expiresIn * 1000
        tokenStore.setAccessToken(body.accessToken)
        tokenStore.setTokenExpireAt(expireAtMs)
        clearApiCache()
        // 与 getOrRefreshToken 同一坑：clearApiCache() 之后必须回填，否则握手读不到 token。
        cachedToken = body.accessToken
        cachedTokenExpireAt = expireAtMs
        return body.accessToken
    }

    private val okhttp3.Response.responseCount: Int
        get() {
            var count = 1
            var prior = this.priorResponse
            while (prior != null) {
                count++
                prior = prior.priorResponse
            }
            return count
        }

    companion object {
        // 官方 2026-08-10 起所有接口域名统一为 api.bot.qq.com（鉴权与 OpenAPI 同域）
        private const val AUTH_BASE_URL = "https://api.bot.qq.com/"
        private const val API_BASE_URL = "https://api.bot.qq.com/"
        private const val TOKEN_REFRESH_MARGIN_MS = 60_000L
    }
}
