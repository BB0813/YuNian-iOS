package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.common.ChatConstants
import com.yunian.ai.domain.AiChatMessage
import com.yunian.ai.domain.AiCompanionInfo
import com.yunian.ai.domain.AiResponse
import com.yunian.ai.domain.AiServiceProvider
import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.AiToolCall
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.domain.stream.AssistantStreamEvent
import com.yunian.ai.domain.timeline.TurnId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AiToolLoopRunner 轮次预算与收尾文案单测。
 *
 * 回归背景（Bug ①）：旧默认 maxRounds=3，且轮次耗尽时输出写死的空话兜底模板，
 * 导致「上网找技能」来不及调用 skill_install 就被截断，且模型产出被丢弃。
 * 本测试锁定：默认 6 轮；耗尽时保留模型文本 + 「本轮已执行：xxx」摘要；skill_install 成功要标注。
 */
class AiToolLoopRunnerRoundBudgetTest {

    private var sendMessageCalls = 0

    @After
    fun tearDown() {
        ToolRegistry.clear()
        sendMessageCalls = 0
    }

    /**
     * 前 [toolRounds] 次响应带工具调用（模拟模型一直要调工具），之后不再带。
     * [textOnToolRounds] 用于模拟「轮次耗尽时模型仍有文本产出」。
     */
    private inner class ScriptedService(
        private val toolName: String,
        private val toolRounds: Int,
        private val textOnToolRounds: String = "",
        private val terminalText: String = "",
    ) : AiServiceProvider {
        override suspend fun sendMessage(
            companion: AiCompanionInfo,
            history: List<AiChatMessage>,
            stickerProbability: Int,
            ntpTimeEnabled: Boolean,
            tools: List<AiTool>?,
            extraSystemRules: String
        ): AiResponse {
            sendMessageCalls++
            val withTool = sendMessageCalls <= toolRounds
            return AiResponse(
                content = if (withTool) textOnToolRounds else terminalText,
                reasoningContent = null,
                toolCalls = if (withTool) {
                    listOf(AiToolCall(id = "c$sendMessageCalls", name = toolName, arguments = """{"q":"x"}"""))
                } else null,
                finishReason = if (withTool) "tool_calls" else "stop",
            )
        }

        override suspend fun sendMessage(
            companion: AiCompanionInfo,
            history: List<AiChatMessage>,
            stickerProbability: Int,
            ntpTimeEnabled: Boolean,
            extraSystemRules: String
        ): AiResponse =
            sendMessage(companion, history, stickerProbability, ntpTimeEnabled, null, extraSystemRules)

        override fun streamMessage(
            companion: AiCompanionInfo,
            history: List<AiChatMessage>,
            stickerProbability: Int,
            ntpTimeEnabled: Boolean,
            turnId: TurnId,
            startedAtMs: Long
        ): Flow<AssistantStreamEvent> = error("not used")

        override suspend fun sendMessageWithImage(
            companion: AiCompanionInfo,
            history: List<AiChatMessage>,
            imagePath: String,
            stickerProbability: Int,
            ntpTimeEnabled: Boolean
        ): AiResponse = error("not used")

        override fun shouldProactivelyMessage(
            companion: AiCompanionInfo,
            recentMessages: List<AiChatMessage>
        ): Boolean = false

        override suspend fun generateProactiveMessage(
            companion: AiCompanionInfo,
            recentMessages: List<AiChatMessage>
        ): String? = null

        override suspend fun sendMessageWithCustomSystem(
            companion: AiCompanionInfo,
            history: List<AiChatMessage>,
            customSystemPrompt: String,
            stickerProbability: Int,
            companionNameMap: Map<Long, String>,
            scope: com.yunian.ai.domain.ConversationScope?
        ): String = ""

        override suspend fun generateFollowUpQuestion(
            companion: AiCompanionInfo,
            recentMessages: List<AiChatMessage>,
            lastAiContent: String
        ): String? = null

        override suspend fun callJudge(prompt: String): String = ""

        override suspend fun callGeneration(prompt: String): String = ""
    }

    private class StubTool(
        override val name: String,
        private val result: String,
    ) : AiTool {
        override val description = "test"
        override val parametersJsonSchema = """{"type":"object","properties":{}}"""
        override suspend fun execute(argumentsJson: String): String = result
    }

    private fun companion() = AiCompanionInfo(id = 1L, name = "测试", personality = "")

    @Test
    fun defaultMaxRounds_isSix() {
        assertEquals(6, ChatConstants.CHAT_TOOL_LOOP_MAX_ROUNDS)
    }

    @Test
    fun exhaustedRounds_appendsActionSummary_andKeepsDefaultTemplate() = runBlocking {
        ToolRegistry.register(StubTool("web_fetch", "ok"))
        // 第 1..99 次响应都带 tool_calls，保证 3 轮耗尽时最后一响应仍带 tool_calls。
        val service = ScriptedService(toolName = "web_fetch", toolRounds = 99)
        val runner = AiToolLoopRunner(service)

        val resp = runner.executeWithToolLoop(companion(), emptyList(), 0, false, ToolRegistry.all(), maxRounds = 3)

        // 初始 1 次 + 每轮各 1 次 = 4
        assertEquals(4, sendMessageCalls)
        assertTrue("应包含动作摘要", resp.content.contains("本轮已执行：web_fetch"))
        assertTrue("无模型文本时应保留可读的默认说明", resp.content.contains("我尝试执行了你的请求"))
        assertEquals("stop", resp.finishReason)
        assertTrue(resp.toolCalls.isNullOrEmpty())
    }

    @Test
    fun exhaustedRounds_preservesModelTextAndRollsUpActions() = runBlocking {
        ToolRegistry.register(StubTool("web_fetch", "ok"))
        val service = ScriptedService(toolName = "web_fetch", toolRounds = 99, textOnToolRounds = "模型最终正文")
        val runner = AiToolLoopRunner(service)

        val resp = runner.executeWithToolLoop(companion(), emptyList(), 0, false, ToolRegistry.all(), maxRounds = 2)

        assertTrue("轮次耗尽不得丢弃模型文本", resp.content.startsWith("模型最终正文"))
        assertTrue(resp.content.contains("本轮已执行：web_fetch"))
    }

    @Test
    fun exhaustedRounds_afterSuccessfulInstall_marksInstalled() = runBlocking {
        ToolRegistry.register(StubTool("skill_install", """{"ok":true,"name":"pdf_processing","bytes":123}"""))
        val service = ScriptedService(toolName = "skill_install", toolRounds = 99, textOnToolRounds = "正在安装")
        val runner = AiToolLoopRunner(service)

        val resp = runner.executeWithToolLoop(companion(), emptyList(), 0, false, ToolRegistry.all(), maxRounds = 4)

        assertTrue("成功安装后轮次耗尽必须标注已安装", resp.content.contains("技能已安装成功"))
        assertTrue(resp.content.contains("本轮已执行：skill_install"))
        assertTrue("模型文本不被丢弃", resp.content.startsWith("正在安装"))
    }

    @Test
    fun exhaustedRounds_afterFailedInstall_doesNotClaimSuccess() = runBlocking {
        ToolRegistry.register(StubTool("skill_install", """{"ok":false,"error":"下载失败 404"}"""))
        val service = ScriptedService(toolName = "skill_install", toolRounds = 99)
        val runner = AiToolLoopRunner(service)

        val resp = runner.executeWithToolLoop(companion(), emptyList(), 0, false, ToolRegistry.all(), maxRounds = 4)

        assertFalse("安装失败绝不能标注已安装成功", resp.content.contains("已安装成功"))
        assertTrue(resp.content.contains("本轮已执行：skill_install"))
    }

    @Test
    fun stopsNormally_whenModelStopsCallingTools() = runBlocking {
        ToolRegistry.register(StubTool("web_fetch", "ok"))
        val service = ScriptedService(toolName = "web_fetch", toolRounds = 1, terminalText = "结束语")
        val runner = AiToolLoopRunner(service)

        val resp = runner.executeWithToolLoop(companion(), emptyList(), 0, false, ToolRegistry.all(), maxRounds = 6)

        assertEquals(2, sendMessageCalls)
        assertEquals("结束语", resp.content)
    }

    @Test
    fun emptyTools_returnsDirectResponseWithoutLoop() = runBlocking {
        val service = ScriptedService(toolName = "web_fetch", toolRounds = 0, terminalText = "纯聊天回复")
        val runner = AiToolLoopRunner(service)

        val resp = runner.executeWithToolLoop(companion(), emptyList(), 0, false, emptyList(), maxRounds = 6)

        assertEquals(1, sendMessageCalls)
        assertEquals("纯聊天回复", resp.content)
    }

    @Test
    fun emittedActivities_haveRunningThenTerminalSameId() = runBlocking {
        ToolRegistry.register(StubTool("web_fetch", "结果正文"))
        val service = ScriptedService(toolName = "web_fetch", toolRounds = 1, terminalText = "结束语")
        val runner = AiToolLoopRunner(service)
        val seen = mutableListOf<ToolActivity>()

        runner.executeWithToolLoop(companion(), emptyList(), 0, false, ToolRegistry.all(), maxRounds = 6) {
            seen += it
        }

        assertEquals(2, seen.size)
        assertEquals("同一工具调用应是 RUNNING 先发、终态后发（同 id）", seen[0].id, seen[1].id)
        assertEquals(ToolStatus.RUNNING, seen[0].status)
        assertEquals(ToolStatus.DONE, seen[1].status)
        assertEquals("结果正文", seen[1].resultSummary)
    }
}
