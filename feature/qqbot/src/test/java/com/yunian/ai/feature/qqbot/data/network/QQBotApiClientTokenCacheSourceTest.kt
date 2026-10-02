package com.yunian.ai.feature.qqbot.data.network

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码级回归护栏：不执行 Android / Retrofit / DataStore，也不证明运行时连通性。
 *
 * 锁定的是一个已发生的真实回归：
 *
 * cachedToken 是纯内存缓存，此前只由 createAuthenticatedRestApi() 赋值，
 * 而它只在「发起一次 REST 调用」时才会被调到。应用重启后尚未发起任何 REST 调用，
 * cachedToken 恒为 null → QQBotWebSocketClient 的 sendIdentify() / sendResume()
 * 经 getCachedToken() 全部拿到 null 并返回 false → refreshTokenAndRetryHandshake()
 * 虽然刷新出了新 token，却只丢弃返回值（握手只认 getCachedToken()）
 * → 握手永远发不出去 → reconnect() → 无限重连且永远连不上。
 *
 * 症状：首次扫码绑定可用（绑定流程会调 createAuthenticatedRestApi 填充缓存），
 * 退出应用再次登录后 QQ 通道一直重连但连不上。
 *
 * 不变量：每一次 clearApiCache() 之后都必须把新 token 回填进内存缓存。
 */
class QQBotApiClientTokenCacheSourceTest {

    private val projectRoot = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val source = File(
        projectRoot,
        "feature/qqbot/src/main/java/com/yunian/ai/feature/qqbot/data/network/QQBotApiClient.kt",
    ).readText()

    /** clearApiCache() 之后必须出现 cachedToken 回填（允许中间有注释与空行）。 */
    private val backfillAfterClear = Regex("clearApiCache\\(\\)[\\s\\S]{0,600}?cachedToken\\s*=\\s*\\S")

    @Test
    fun getOrRefreshTokenBackfillsInMemoryCacheAfterClearingIt() {
        val body = source.substringAfter("suspend fun getOrRefreshToken(")
            .substringBefore("suspend fun createAuthenticatedRestApi(")
        assertTrue(
            "getOrRefreshToken 必须在 clearApiCache() 之后回填 cachedToken，" +
                "否则重启后握手读不到 token，表现为无限重连。",
            backfillAfterClear.containsMatchIn(body),
        )
    }

    @Test
    fun privateRefreshTokenBackfillsInMemoryCacheAfterClearingIt() {
        val body = source.substringAfter("private suspend fun refreshToken(")
            .substringBefore("private val okhttp3.Response.responseCount")
        assertTrue(
            "refreshToken 与 getOrRefreshToken 是同一个坑，必须同样回填。",
            backfillAfterClear.containsMatchIn(body),
        )
    }

    @Test
    fun clearApiCacheStillNullsTheFieldSoOrderingMatters() {
        val body = source.substringAfter("fun clearApiCache()").substringBefore("fun getCachedToken()")
        assertTrue(
            "clearApiCache() 应当把 cachedToken 置空，否则本测试的前提不成立。",
            Regex("cachedToken\\s*=\\s*null").containsMatchIn(body),
        )
    }

    @Test
    fun handshakePathDependsOnTheInMemoryField() {
        val body = source.substringAfter("fun getCachedToken()").substringBefore("private fun buildRestApi(")
        assertTrue(
            "getCachedToken() 必须只依赖内存字段 cachedToken，" +
                "这正是「刷新后不握手」的直接原因。",
            Regex("val token = cachedToken").containsMatchIn(body),
        )
    }
}
