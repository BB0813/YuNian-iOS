package com.yunian.ai.agent.host

import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.ToolRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渠道隔离契约测试（注册表侧 = 第一道闸）。
 *
 * 断言 `appLocalOnly` 工具在**默认 / 未知渠道**下对全部「面向模型」的枚举面都不可见：
 * availableTools / all / toolDefinitionsJson / systemPromptSection / agentDirectiveSection /
 * toolsInToolset / toolsetNames；只有显式 `includeAppLocal = true` 才放行。
 * 普通工具两种渠道行为完全一致（不扩大既有可见性）。
 *
 * 位置说明：`core:domain` 没有 testImplementation（不得新增依赖），而 `:core:agent`
 * 的 JVM 单测可见 core:domain 的 implementation 依赖，故本测试放在 :core:agent。
 */
class ToolRegistryLocalOnlyTest {

    private class FakeTool(
        override val name: String,
        override val appLocalOnly: Boolean = false,
        override val toolsets: Set<String> = emptySet(),
        private val payload: String = "",
        private val available: Boolean = true,
    ) : AiTool {

        var availabilityProbes: Int = 0
            private set

        override val description: String = "fake: $name"
        override val parametersJsonSchema: String = "{\"type\":\"object\",\"properties\":{}}"

        override fun isAvailable(): Boolean {
            availabilityProbes++
            return available
        }

        override suspend fun execute(argumentsJson: String): String = payload

        override fun systemPrompt(): String = "SYS-PROMPT:$name:$payload"
    }

    private val appToolName = "test_app_local_tool"
    private val normalToolName = "test_normal_tool"
    private val appToolset = "test_app_local_toolset"
    private val normalToolset = "test_normal_toolset"
    private val secret = "SECRET-PROFILE-CONTENT"

    @After
    fun tearDown() {
        ToolRegistry.unregister(appToolName)
        ToolRegistry.unregister(normalToolName)
        ToolRegistry.invalidateAvailabilityCache()
    }

    private fun registerAppLocalTool(available: Boolean = true): FakeTool =
        FakeTool(
            name = appToolName,
            appLocalOnly = true,
            toolsets = setOf(appToolset),
            payload = secret,
            available = available,
        ).also { ToolRegistry.register(it) }

    private fun registerNormalTool(): FakeTool =
        FakeTool(
            name = normalToolName,
            toolsets = setOf(normalToolset),
            payload = "NORMAL-PAYLOAD",
        ).also { ToolRegistry.register(it) }

    @Test
    fun defaultChannelHidesAppLocalToolFromEveryModelFacingSurface() {
        val appLocal = registerAppLocalTool()
        registerNormalTool()

        assertFalse(
            "availableTools() 默认渠道必须排除本机敏感工具",
            ToolRegistry.availableTools().any { it.name == appToolName },
        )
        assertFalse(
            "all() 默认渠道必须排除本机敏感工具",
            ToolRegistry.all().any { it.name == appToolName },
        )
        assertFalse(
            "toolDefinitionsJson() 默认渠道必须排除本机敏感工具",
            ToolRegistry.toolDefinitionsJson().contains(appToolName),
        )
        assertFalse(
            "工具定义 JSON 不得泄漏本机资料内容",
            ToolRegistry.toolDefinitionsJson().contains(secret),
        )
        assertFalse(
            "systemPromptSection() 默认渠道必须排除本机敏感工具",
            ToolRegistry.systemPromptSection().contains(secret),
        )
        assertFalse(
            "toolsInToolset() 默认渠道必须排除本机敏感工具",
            ToolRegistry.toolsInToolset(appToolset).isNotEmpty(),
        )
        assertFalse(
            "toolsetNames() 默认渠道不得泄漏本机工具集名",
            ToolRegistry.toolsetNames().contains(appToolset),
        )
        assertEquals(
            "默认渠道连可用性都不应求值（可用性 ≠ 授权）",
            0,
            appLocal.availabilityProbes,
        )
    }

    @Test
    fun appLocalChannelExposesAppLocalToolOnEverySurface() {
        registerAppLocalTool()
        registerNormalTool()

        assertTrue(ToolRegistry.availableTools(includeAppLocal = true).any { it.name == appToolName })
        assertTrue(ToolRegistry.all(includeAppLocal = true).any { it.name == appToolName })
        assertTrue(ToolRegistry.toolDefinitionsJson(includeAppLocal = true).contains(appToolName))
        assertTrue(ToolRegistry.systemPromptSection(includeAppLocal = true).contains(secret))
        assertTrue(
            ToolRegistry.toolsInToolset(appToolset, includeAppLocal = true).any { it.name == appToolName },
        )
        assertTrue(ToolRegistry.toolsetNames(includeAppLocal = true).contains(appToolset))
        assertTrue(
            ToolRegistry.toolDefinitionsJson(includeAppLocal = true)
                .contains("\"name\":\"$appToolName\""),
        )
    }

    @Test
    fun normalToolBehaviourIsIdenticalOnBothChannels() {
        registerNormalTool()

        assertTrue(ToolRegistry.availableTools().any { it.name == normalToolName })
        assertTrue(ToolRegistry.availableTools(includeAppLocal = true).any { it.name == normalToolName })
        assertTrue(ToolRegistry.all().any { it.name == normalToolName })
        assertTrue(ToolRegistry.toolDefinitionsJson().contains("\"name\":\"$normalToolName\""))
        assertTrue(
            ToolRegistry.toolDefinitionsJson(includeAppLocal = true).contains("\"name\":\"$normalToolName\""),
        )
        assertTrue(ToolRegistry.systemPromptSection().contains("SYS-PROMPT:$normalToolName"))
        assertTrue(
            ToolRegistry.systemPromptSection(includeAppLocal = true).contains("SYS-PROMPT:$normalToolName"),
        )
        assertEquals(
            "行动指令段是静态文本，两渠道必须逐字一致（只有本机工具才会改变其存在性）",
            ToolRegistry.agentDirectiveSection(),
            ToolRegistry.agentDirectiveSection(includeAppLocal = true),
        )
        assertTrue(ToolRegistry.agentDirectiveSection().isNotEmpty())
    }

    /**
     * 注册池里只有本机敏感工具时，默认渠道的「执行能力」指令段必须整体消失
     * （否则等于告诉模型"你有工具"却一个都不给）。
     *
     * 前提：本测试类之外的测试不向 [ToolRegistry] 注册工具（本仓库现状如此）。
     */
    @Test
    fun defaultChannelHasNoDirectiveWhenOnlyAppLocalToolsExist() {
        registerAppLocalTool()

        assertEquals("", ToolRegistry.agentDirectiveSection())
        assertTrue(ToolRegistry.agentDirectiveSection(includeAppLocal = true).isNotEmpty())
        assertEquals("[]", ToolRegistry.toolDefinitionsJson())
        assertTrue(ToolRegistry.toolDefinitionsJson(includeAppLocal = true).isNotEmpty())
    }

    @Test
    fun availabilityCacheIsNotAnAuthorizationCache() {
        val appLocal = registerAppLocalTool()

        // 1) 允许渠道求值一次 → 工具的 isAvailable() 被缓存为 true
        assertTrue(ToolRegistry.availableTools(includeAppLocal = true).any { it.name == appToolName })
        assertEquals(1, appLocal.availabilityProbes)

        // 2) 默认渠道仍然看不到：可用性缓存不构成授权
        assertFalse(ToolRegistry.availableTools().any { it.name == appToolName })
        assertFalse(ToolRegistry.toolDefinitionsJson().contains(appToolName))
        assertEquals("默认渠道不应再次求值可用性（授权不写进可用性缓存）", 1, appLocal.availabilityProbes)
    }

    @Test
    fun unavailableAppLocalToolStaysHiddenOnBothChannels() {
        registerAppLocalTool(available = false)

        assertFalse(ToolRegistry.availableTools().any { it.name == appToolName })
        assertFalse(ToolRegistry.availableTools(includeAppLocal = true).any { it.name == appToolName })
    }
}
