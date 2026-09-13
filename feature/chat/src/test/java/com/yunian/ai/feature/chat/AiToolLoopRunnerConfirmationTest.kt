package com.yunian.ai.feature.chat

import com.yunian.ai.domain.AiChatMessage
import com.yunian.ai.domain.AiCompanionInfo
import com.yunian.ai.domain.AiResponse
import com.yunian.ai.domain.AiServiceProvider
import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.AiToolCall
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.feature.chat.ui.viewmodel.AiToolLoopRunner
import com.yunian.ai.feature.chat.ui.viewmodel.ConfirmationGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiToolLoopRunnerConfirmationTest {

    private var gateCalls = 0
    private var gateResult = true
    private var toolExecuted = 0
    private var toolResultContent: String? = null
    private var sendMessageCalls = 0

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
            loopRunner.sendMessageCalls++

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
            turnId: com.yunian.ai.domain.timeline.TurnId,
            startedAtMs: Long
        ): kotlinx.coroutines.flow.Flow<com.yunian.ai.domain.stream.AssistantStreamEvent> = error("not used")

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

    @Test
    fun cancellationDuringGateAbortsLoop() = runBlocking {
        ToolRegistry.register(ConfirmMeTool(this@AiToolLoopRunnerConfirmationTest))

        val gate = ConfirmationGate { _, _ ->
            suspendCancellableCoroutine<Boolean> { }
        }
        val runner = AiToolLoopRunner(FakeAiService(this@AiToolLoopRunnerConfirmationTest), gate)
        val job = launch {
            runner.executeWithToolLoop(companion(), emptyList(), 0, false, ToolRegistry.all())
        }
        delay(200)
        job.cancel()
        job.join()
        delay(300)

        assertEquals(1, sendMessageCalls)
        assertEquals(0, toolExecuted)
    }
}
