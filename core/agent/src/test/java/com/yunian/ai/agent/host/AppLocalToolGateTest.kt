package com.yunian.ai.agent.host

import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本机敏感工具渠道隔离的纯 JVM 单测（可见性侧 + 执行侧双层）。
 *
 * 这是「聊天 AI 用户资料感知」功能的安全底线测试：
 * - 可见性：默认（未知 / 外部渠道）枚举与 tools JSON 都不得出现 appLocalOnly 工具；
 * - 执行：即使工具名被叫到，默认渠道也拿不到结果，且其 execute 根本不会被调用；
 * - 日志：本机敏感工具结果在 ToolCallRecord / Log 中一律脱敏。
 */
class AppLocalToolGateTest {

    private class FakeTool(
        override val name: String,
        override val appLocalOnly: Boolean = false,
        override val toolsets: Set<String> = setOf("chat"),
        private val payload: String = "{\"nickname\":\"云念\",\"has_avatar\":true}",
    ) : AiTool {
        var executeCount: Int = 0
            private set

        override val description: String = "fake tool $name"

        override val parametersJsonSchema: String =
            "{\"type\":\"object\",\"properties\":{}}"

        override suspend fun execute(argumentsJson: String): String {
            executeCount++
            return payload
        }
    }

    private val localTool = FakeTool("test_app_local_tool", appLocalOnly = true)
    private val normalTool = FakeTool("test_normal_tool", appLocalOnly = false)

    private fun withRegistry(block: () -> Unit) {
        ToolRegistry.clear()
        ToolRegistry.register(localTool)
        ToolRegistry.register(normalTool)
        try {
            block()
        } finally {
            ToolRegistry.clear()
        }
    }

    private fun names(includeAppLocal: Boolean): Set<String> =
        ToolRegistry.availableTools(includeAppLocal = includeAppLocal).map { it.name }.toSet()

    // ── 1. 可见性：默认渠道看不到 ────────────────────────────────────
    @Test
    fun default_enumeration_hides_app_local_tool() = withRegistry {
        val visible = names(includeAppLocal = false)
        assertTrue("普通工具应可见", visible.contains(normalTool.name))
        assertFalse("本机敏感工具不得出现在默认枚举中", visible.contains(localTool.name))
    }

    @Test
    fun local_session_enumeration_includes_app_local_tool() = withRegistry {
        val visible = names(includeAppLocal = true)
        assertTrue(visible.contains(normalTool.name))
        assertTrue(visible.contains(localTool.name))
    }

    @Test
    fun tool_definitions_json_excludes_app_local_by_default() = withRegistry {
        val defaultJson = ToolRegistry.toolDefinitionsJson()
        assertFalse("默认 tools JSON 不得泄漏本机敏感工具定义", defaultJson.contains(localTool.name))
        assertTrue(defaultJson.contains(normalTool.name))

        val localJson = ToolRegistry.toolDefinitionsJson(includeAppLocal = true)
        assertTrue(localJson.contains(localTool.name))
    }

    @Test
    fun tools_in_toolset_defaults_to_excluding_app_local() = withRegistry {
        val defaultChat = ToolRegistry.toolsInToolset("chat").map { it.name }
        assertFalse(defaultChat.contains(localTool.name))
        assertTrue(defaultChat.contains(normalTool.name))

        val localChat = ToolRegistry.toolsInToolset("chat", includeAppLocal = true).map { it.name }
        assertTrue(localChat.contains(localTool.name))
    }

    @Test
    fun system_prompt_section_is_excluded_when_only_app_local_tools_are_local() = withRegistry {
        // 假工具 systemPrompt() 默认空串，这里只断言不抛异常且默认不引入本机工具文本。
        val section = ToolRegistry.systemPromptSection(includeAppLocal = false)
        assertFalse(section.contains(localTool.name))
    }

    // ── 2. 执行侧：叫到名字也执行不了 ───────────────────────────────
    @Test
    fun execution_gate_denies_app_local_tool_and_never_invokes_execute() = withRegistry {
        val result = runBlocking { dispatchRegistryTool(localTool.name, "{}", allowAppLocalTools = false) }

        assertEquals(AppLocalToolGate.rejectionText(localTool.name), result)
        assertEquals("被拒绝的工具不得真的执行", 0, localTool.executeCount)
        assertFalse("拒绝文本不得回显资料内容", result.contains("云念"))
    }

    @Test
    fun forged_arguments_cannot_escalate_permissions() = withRegistry {
        val forged = "{\"channel\":\"app\",\"local\":true,\"user_id\":\"root\"}"
        val result = runBlocking { dispatchRegistryTool(localTool.name, forged, allowAppLocalTools = false) }

        assertEquals(AppLocalToolGate.rejectionText(localTool.name), result)
        assertEquals(0, localTool.executeCount)
    }

    @Test
    fun execution_gate_allows_app_local_tool_on_local_session() = withRegistry {
        val result = runBlocking { dispatchRegistryTool(localTool.name, "{}", allowAppLocalTools = true) }

        assertTrue(result.contains("云念"))
        assertEquals(1, localTool.executeCount)
    }

    @Test
    fun normal_tool_is_unaffected_in_both_channels() = withRegistry {
        val external = runBlocking { dispatchRegistryTool(normalTool.name, "{}", allowAppLocalTools = false) }
        val local = runBlocking { dispatchRegistryTool(normalTool.name, "{}", allowAppLocalTools = true) }

        assertEquals(normalTool.executeCount, 2)
        assertEquals(external, local)
    }

    @Test
    fun unregistered_tool_keeps_existing_error_text() = withRegistry {
        val result = runBlocking { dispatchRegistryTool("no_such_tool", "{}", allowAppLocalTools = true) }
        assertEquals("错误：未注册的工具 no_such_tool", result)
    }

    // ── 3. 日志脱敏 ─────────────────────────────────────────────────
    @Test
    fun app_local_tool_results_are_redacted_in_record_and_log() = withRegistry {
        val payload = "{\"nickname\":\"云念\"}"

        assertEquals(AppLocalToolGate.REDACTED_RESULT, AppLocalToolGate.resultForRecord(localTool.name, payload))
        assertEquals(AppLocalToolGate.REDACTED_RESULT, AppLocalToolGate.resultForLog(localTool.name, payload))
        assertFalse(AppLocalToolGate.resultForLog(localTool.name, payload).contains("云念"))
    }

    @Test
    fun normal_tool_results_keep_existing_log_behavior() = withRegistry {
        val payload = "x".repeat(200)

        assertEquals(payload, AppLocalToolGate.resultForRecord(normalTool.name, payload))
        assertEquals(120, AppLocalToolGate.resultForLog(normalTool.name, payload).length)
    }

    @Test
    fun unregistered_name_is_not_treated_as_app_local() = withRegistry {
        assertFalse(AppLocalToolGate.isAppLocalTool("no_such_tool"))
        assertTrue(AppLocalToolGate.isAppLocalTool(localTool.name))
        assertFalse(AppLocalToolGate.isAppLocalTool(normalTool.name))
    }
}
