package com.lianyu.ai.feature.chat

import com.lianyu.ai.domain.AiChatMessage
import com.lianyu.ai.domain.AiCompanionInfo
import com.lianyu.ai.domain.AiResponse
import com.lianyu.ai.domain.AiServiceProvider
import com.lianyu.ai.domain.AiTool
import com.lianyu.ai.domain.AiToolCall
import com.lianyu.ai.domain.ToolRegistry
import com.lianyu.ai.feature.chat.ui.viewmodel.AiToolLoopRunner
import com.lianyu.ai.feature.chat.ui.viewmodel.ConfirmationGate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiToolLoopRunnerConfirmationTest {

    private var gateCalls = 0
    private var gateResult = true
    private var toolExecuted = 0
    private var toolResultContent: String? = null

    @After
    fun tearDown() {
        ToolRegistry.clear()
    }

    private class FakeAiService(private val loopRunner: AiToolLoopRunnerConfirmationTest) : AiServiceProvider {
        override suspend fun sendMessage(
            companion: AiCompanionInfo,
            history: List<AiChatMessage>,
            stickerProbability: Int,
            ntpTimeEnabled: Boolean,
            tools: List<AiTool>?,
            extraSystemRules: String
        ): AiResponse {
            // 第一轮返回 tool_call，之后返回正常回复
            val hasToolResult = history.any { it.toolName != null }
            return if (!hasToolResult) {
                AiResponse(
                    content = "",
                    reasoningContent = null,
                    toolCalls = listOf(AiToolCall(id = "call_1", name = "confirm_me", arguments = """{"v":1}""")),
                    finishReason = "tool_calls"
                )
            } else {
                loopRunner.toolResultContent = history.lastOrNull()?.content
                AiResponse(
                    content = "完成",
                    reasoningContent = null,
                    toolCalls = null,
                    finishReason = "stop"
                )
            }
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
            turnId: com.lianyu.ai.domain.timeline.TurnId,
            startedAtMs: Long
        ): kotlinx.coroutines.flow.Flow<com.lianyu.ai.domain.stream.AssistantStreamEvent> = error("not used")

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
            companionNameMap: Map<Long, String>
        ): String = ""

        override suspend fun generateFollowUpQuestion(
            companion: AiCompanionInfo,
            recentMessages: List<AiChatMessage>,
            lastAiContent: String
        ): String? = null

        override suspend fun callJudge(prompt: String): String = ""

        override suspend fun callGeneration(prompt: String): String = ""
    }

    private class ConfirmMeTool(private val owner: AiToolLoopRunnerConfirmationTest) : AiTool {
        override val name = "confirm_me"
        override val description = "test"
        override val parametersJsonSchema = """{"type":"object","properties":{}}"""
        override val requiresConfirmation: Boolean get() = true
        override suspend fun execute(argumentsJson: String): String {
            owner.toolExecuted++
            return """{"ok":true}"""
        }
    }

    private fun companion() = AiCompanionInfo(id = 1L, name = "测试", personality = "")

    @Test
    fun confirmedToolExecutesAndResultFeedsBack() = runBlocking {
        ToolRegistry.register(ConfirmMeTool(this@AiToolLoopRunnerConfirmationTest))
        gateResult = true
        val gate = ConfirmationGate { _, _ ->
            gateCalls++
            gateResult
        }
        val runner = AiToolLoopRunner(FakeAiService(this@AiToolLoopRunnerConfirmationTest), gate)
        val resp = runner.executeWithToolLoop(companion(), emptyList(), 0, false, ToolRegistry.all())
        assertEquals(1, gateCalls)
        assertEquals(1, toolExecuted)
        assertEquals("完成", resp.content)
        assertTrue(toolResultContent!!.contains("ok"))
    }

    @Test
    fun rejectedToolNotExecuted() = runBlocking {
        ToolRegistry.register(ConfirmMeTool(this@AiToolLoopRunnerConfirmationTest))
        gateResult = false
        val gate = ConfirmationGate { _, _ ->
            gateCalls++
            gateResult
        }
        val runner = AiToolLoopRunner(FakeAiService(this@AiToolLoopRunnerConfirmationTest), gate)
        val resp = runner.executeWithToolLoop(companion(), emptyList(), 0, false, ToolRegistry.all())
        assertEquals(1, gateCalls)
        assertEquals(0, toolExecuted)
        assertEquals("完成", resp.content)
        assertTrue(toolResultContent!!.contains("用户已取消"))
    }
}
