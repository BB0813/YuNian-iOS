package com.yunian.ai.feature.qqbot.data.network

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码级回归护栏：不执行 WebSocket，也不证明运行时连通性。
 *
 * 锁定第二个重启回归：
 *
 * 应用重启后走的是 RESUME 分支，服务端回 op=0 / t="RESUMED"。
 * 而 opDispatch 原先只对 t=="READY" 结算握手，RESUMED 什么都不做，于是：
 *
 * - stateMachine 永远停在 CONNECTING/RECONNECTING（中间态），
 * - 握手看门狗在 HANDSHAKE_TIMEOUT_MS(30s) 后判定死链 → scheduleReconnect()，
 * - 下一个周期仍然只收到 RESUMED → 仍然停在中间态 → 每 30 秒无限重连，
 * - reconnectAttempt 永不归零，最终撞上 MAX_RECONNECT_ATTEMPTS 后永久放弃。
 *
 * 真机日志特征：[Repo] dispatch type=RESUMED 严格每 30 秒出现一次。
 */
class QQBotWebSocketHandshakeSourceTest {

    private val projectRoot = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val source = File(
        projectRoot,
        "feature/qqbot/src/main/java/com/yunian/ai/feature/qqbot/data/network/QQBotWebSocketClient.kt",
    ).readText()

    @Test
    fun resumedIsTreatedAsASettledHandshakeLikeReady() {
        val body = source.substringAfter("opDispatch -> {").substringBefore("opHello -> {")
        assertTrue(
            "opDispatch 必须同时接受 READY 与 RESUMED；只认 READY 会让重启后的 RESUMED " +
                "永不结算握手，握手看门狗每 30 秒强制断开一次。",
            Regex("payload\\.t == READY_EVENT \\|\\| payload\\.t == RESUMED_EVENT").containsMatchIn(body),
        )
    }

    @Test
    fun settledHandshakeResetsReconnectBudgetAndCancelsWatchdog() {
        val body = source
            .substringAfter("payload.t == READY_EVENT || payload.t == RESUMED_EVENT")
            .substringBefore("scope.launch { onEvent(payload) }")
        assertTrue("结算握手必须重置重连预算", Regex("reconnectAttempt\\.set\\(0\\)").containsMatchIn(body))
        assertTrue("结算握手必须取消握手看门狗", Regex("cancelHandshakeWatchdog\\(\\)").containsMatchIn(body))
        assertTrue("结算握手必须进入 CONNECTED 终态", Regex("stateMachine\\.onReady\\(").containsMatchIn(body))
    }

    @Test
    fun handshakeWatchdogDependsOnConnectedState() {
        val body = source.substringAfter("private fun startHandshakeWatchdog()")
            .substringBefore("private fun cancelHandshakeWatchdog()")
        assertTrue(
            "看门狗以 stateMachine.isConnected() 为唯一豁免条件——" +
                "这正是 RESUMED 必须结算握手的原因。",
            Regex("stateMachine\\.isConnected\\(\\)").containsMatchIn(body),
        )
    }

    @Test
    fun watchdogTimeoutIsThirtySecondsMatchingTheObservedLoop() {
        assertTrue(
            "真机实测 RESUMED 每 30 秒一次，与 HANDSHAKE_TIMEOUT_MS 一致。",
            Regex("HANDSHAKE_TIMEOUT_MS = 30_000L").containsMatchIn(source),
        )
    }
}
