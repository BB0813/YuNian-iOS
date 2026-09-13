package com.yunian.ai.feature.chat.ui.viewmodel

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
import org.junit.Test

/**
 * F3 回归：工具结果 -> 卡片终态（DONE/FAILED）的判定矩阵。
 *
 * 修复前 `isToolFailure` 只做前缀匹配，`skill_install` 等返回 `{"ok":false,...}` 的失败
 * 会被显示成绿色成功态。修复后需同时满足：
 * - `{"ok":false,...}` 一律 FAILED；
 * - `{"ok":true,...}`（含空结果的 `{"ok":true,"empty":true}`）一律 DONE，不能被误伤；
 * - 原有前缀式失败（工具执行失败/超时/用户已取消/被拒绝）仍 FAILED。
 */
class AiToolLoopRunnerToolFailureStatusTest {

    @After
    fun tearDown() {
        ToolRegistry.clear()
    }

    /** 只走一轮工具调用，随后模型停止调用，便于拿到单张卡片的终态。 */
    private inner class OneRoundService(private val toolName: String) : AiServiceProvider {
        private var calls = 0

        override suspend fun sendMessage(
            companion: AiCompanionInfo,
            history: List<AiChatMessage>,
            stickerProbability: Int,
            ntpTimeEnabled: Boolean,
            tools: List<AiTool>?,
            extraSystemRules: String
        ): AiResponse {
            calls++
            return if (calls == 1) {
                AiResponse(
                    content = "",
                    toolCalls = listOf(AiToolCall(id = "c1", name = toolName, arguments = """{"q":"x"}""")),
                    finishReason = "tool_calls",
                )
            } else {
                AiResponse(content = "完成", toolCalls = null, finishReason = "stop")
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

    private class ResultTool(override val name: String, private val result: String) : AiTool {
        override val description = "test"
        override val parametersJsonSchema = """{"type":"object","properties":{}}"""
        override suspend fun execute(argumentsJson: String): String = result
    }

    private fun companion() = AiCompanionInfo(id = 1L, name = "测试", personality = "")

    private fun terminalStatus(toolName: String, result: String): ToolStatus {
        ToolRegistry.register(ResultTool(toolName, result))
        val runner = AiToolLoopRunner(OneRoundService(toolName))
        val seen = mutableListOf<ToolActivity>()
        runBlocking {
            runner.executeWithToolLoop(companion(), emptyList(), 0, false, ToolRegistry.all(), maxRounds = 6) {
                seen += it
            }
        }
        assertEquals("应各发射 RUNNING + 终态两次", 2, seen.size)
        assertEquals(ToolStatus.RUNNING, seen.first().status)
        return seen.last().status
    }

    @Test
    fun skillInstallFailureJson_isFailed() {
        assertEquals(
            ToolStatus.FAILED,
            terminalStatus("skill_install", """{"ok":false,"error":"下载失败: 404"}"""),
        )
    }

    @Test
    fun useSkillNotFound_isFailed() {
        assertEquals(
            ToolStatus.FAILED,
            terminalStatus("use_skill", """{"ok":false,"error":"Skill not found: foo"}"""),
        )
    }

    @Test
    fun failureJsonWithLeadingWhitespace_isFailed() {
        assertEquals(
            ToolStatus.FAILED,
            terminalStatus("web_fetch", "  \n{\"ok\":false,\"error\":\"boom\"}"),
        )
    }

    @Test
    fun successJson_isDone() {
        assertEquals(
            ToolStatus.DONE,
            terminalStatus("skill_install", """{"ok":true,"name":"pdf_processing","bytes":123}"""),
        )
    }

    @Test
    fun emptyResultSuccessJson_isNotMisflaggedAsFailure() {
        assertEquals(
            ToolStatus.DONE,
            terminalStatus("search_web", """{"ok":true,"empty":true}"""),
        )
    }

    @Test
    fun successJsonWithEmptyFalse_isDone() {
        assertEquals(
            ToolStatus.DONE,
            terminalStatus("search_web", """{"ok":true,"empty":false,"results":[]}"""),
        )
    }

    @Test
    fun prefixFailures_stillFailed() {
        assertEquals(ToolStatus.FAILED, terminalStatus("t", "工具执行失败: boom"))
        assertEquals(ToolStatus.FAILED, terminalStatus("t", "工具执行超时"))
        assertEquals(ToolStatus.FAILED, terminalStatus("t", "用户已取消操作"))
        assertEquals(ToolStatus.FAILED, terminalStatus("t", "工具执行被拒绝"))
    }

    @Test
    fun plainSuccessResult_isDone() {
        assertEquals(ToolStatus.DONE, terminalStatus("t", "已完成，结果如下"))
    }

    // ---- F3 健壮版（JSON 解析）专项 ----

    @Test
    fun failureJsonWithSpaceAfterColon_isFailed() {
        assertEquals(ToolStatus.FAILED, terminalStatus("skill_install", """{"ok": false, "error":"boom"}"""))
    }

    @Test
    fun prettyPrintedFailureJson_isFailed() {
        val pretty = "{\n  \"ok\": false,\n  \"error\": \"下载失败 404\"\n}"
        assertEquals(ToolStatus.FAILED, terminalStatus("skill_install", pretty))
    }

    @Test
    fun malformedJson_fallsBackToPrefixJudgement() {
        // 解析失败不应抛异常；前缀判定仍需生效
        assertEquals(ToolStatus.FAILED, terminalStatus("t", "工具执行失败 {ok:false 未闭合"))
    }

    @Test
    fun malformedNonJsonSuccessText_isDone() {
        assertEquals(ToolStatus.DONE, terminalStatus("web_fetch", "# SKILL.md 正文\n说明文字"))
    }

    @Test
    fun nonObjectJsonResult_isNotMisjudgedAsFailure() {
        assertEquals(ToolStatus.DONE, terminalStatus("search_web", "[1,2,3]"))
    }
}
