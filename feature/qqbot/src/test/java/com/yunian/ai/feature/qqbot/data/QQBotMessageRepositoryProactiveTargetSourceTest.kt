package com.yunian.ai.feature.qqbot.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 源码级护栏：锁定 Gateway 入站群路由 → DataStore → 主动发送别名的完整接线。 */
class QQBotMessageRepositoryProactiveTargetSourceTest {
    private val projectRoot = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(path: String): String = File(projectRoot, path).readText()

    @Test
    fun bridgeCapturesTrustedGroupOpenIdBeforeAutoReplyGate() {
        val source = source("feature/qqbot/src/main/java/com/yunian/ai/feature/qqbot/data/QQBotChatBridge.kt")
        val capture = source.indexOf("rememberRecentGroupOpenId(event.groupOpenid)")
        val gate = source.indexOf("val autoReply = tokenStore.getAutoReply()")
        assertTrue("群路由必须由 GroupAtMessage 捕获", capture >= 0)
        assertTrue("即使关闭自动回复也应捕获路由", capture < gate)
    }

    @Test
    fun tokenStorePersistsAndClearsRecentGroupTarget() {
        val source = source("feature/qqbot/src/main/java/com/yunian/ai/feature/qqbot/data/QQBotTokenStore.kt")
        assertTrue(source.contains("qqbot_recent_group_openid"))
        assertTrue(source.contains("prefs.remove(RECENT_GROUP_OPENID_KEY)"))
        assertTrue(source.contains("override suspend fun getRecentGroupOpenId()"))
        assertTrue(source.contains("override suspend fun setRecentGroupOpenId"))
    }

    @Test
    fun toolContractOffersDiscoverableGroupAlias() {
        val source = source("core/agent/src/main/kotlin/com/yunian/ai/agent/tools/ChannelSendTool.kt")
        assertTrue(source.contains("QQ 最近群聊传 group"))
        assertTrue(source.contains("target=group 表示最近一次与机器人说话的群"))
    }
}
