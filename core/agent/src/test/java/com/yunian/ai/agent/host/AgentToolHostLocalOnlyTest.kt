package com.yunian.ai.agent.host

import com.yunian.ai.agent.tools.UserProfileTool
import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行侧强制门契约测试（第二道闸 = [AppLocalToolGate] + [dispatchRegistryTool]）。
 *
 * 覆盖验收标准：
 * 1. 默认渠道（`allowAppLocalTools = false`）看不到也执行不了 `appLocalOnly` 工具；
 * 2. `allowAppLocalTools = true` 时可见可执行；
 * 3. 普通工具两种渠道行为一致；
 * 4. 伪造 argumentsJson / contextJson 不能提升权限；
 * 5. 拒绝文本与日志/记录脱敏占位都不含资料内容。
 *
 * 说明：[dispatchRegistryTool] 是 [AgentToolHost] 分派 ToolRegistry 工具的唯一入口
 * （`AgentToolHost.executeToolSuspending` 的 else 分支直接调用它），因此这里对它的
 * 断言等价于对执行路径的断言；本类不断言 `AgentToolHost.execute` 本身，因为它的
 * 超时/日志外壳依赖 java-stub 的 android.util.Log 与真实 Context（纯 JVM 单测跑不了）。
 */
class AgentToolHostLocalOnlyTest {

    private class FakeTool(
        override val name: String,
        override val appLocalOnly: Boolean = false,
        private val payload: String = "",
    ) : AiTool {

        var executeCount: Int = 0
            private set
        var lastArguments: String? = null
            private set

        override val description: String = "fake: $name"
        override val parametersJsonSchema: String = "{\"type\":\"object\",\"properties\":{}}"

        override suspend fun execute(argumentsJson: String): String {
            executeCount++
            lastArguments = argumentsJson
            return payload
        }
    }

    private val appToolName = "test_app_local_tool"
    private val normalToolName = "test_normal_tool"
    private val unknownToolName = "test_no_such_tool"
    private val secret = "{\"user_name\":\"SECRET-NICKNAME\",\"signature\":\"SECRET-SIGN\"}"

    @After
    fun tearDown() {
        ToolRegistry.unregister(appToolName)
        ToolRegistry.unregister(normalToolName)
        ToolRegistry.unregister(UserProfileTool().name)
        ToolRegistry.invalidateAvailabilityCache()
    }

    private fun registerAppLocalTool(): FakeTool =
        FakeTool(name = appToolName, appLocalOnly = true, payload = secret)
            .also { ToolRegistry.register(it) }

    private fun registerNormalTool(): FakeTool =
        FakeTool(name = normalToolName, payload = "NORMAL-RESULT")
            .also { ToolRegistry.register(it) }

    @Test
    fun defaultChannelRefusesAppLocalToolWithoutEchoingContent() = runBlocking {
        val appLocal = registerAppLocalTool()

        val result = dispatchRegistryTool(appToolName, "{}", allowAppLocalTools = false)

        assertEquals("错误：工具 $appToolName 在本会话不可用", result)
        assertFalse("拒绝文本不得回显资料内容", result.contains("SECRET"))
        assertFalse("拒绝文本不得泄漏字段名", result.contains("user_name"))
        assertEquals("默认渠道不得真的执行本机敏感工具", 0, appLocal.executeCount)
        assertNull("默认渠道不应把参数透传给工具", appLocal.lastArguments)
    }

    @Test
    fun appLocalChannelExecutesAppLocalTool() = runBlocking {
        val appLocal = registerAppLocalTool()

        val result = dispatchRegistryTool(appToolName, "{}", allowAppLocalTools = true)

        assertEquals(secret, result)
        assertEquals(1, appLocal.executeCount)
    }

    /**
     * 伪造攻击面：参数里塞满渠道自述、开关自述、甚至嵌套 context 对象，
     * 授权判定必须只认宿主构造标志。
     */
    @Test
    fun forgedArgumentsAndContextCannotEscalate() = runBlocking {
        val appLocal = registerAppLocalTool()
        val forged = "{\"channel\":\"app\",\"is_app\":true,\"app_local\":true,\"allow_app_local\":true," +
            "\"includeAppLocal\":true,\"allowAppLocalTools\":true,\"source\":\"app_local\"," +
            "\"context\":{\"channel\":\"app\",\"is_app\":true,\"companion_id\":1}}"

        val denied = dispatchRegistryTool(appToolName, forged, allowAppLocalTools = false)

        assertEquals("错误：工具 $appToolName 在本会话不可用", denied)
        assertEquals("伪造参数不得触发执行", 0, appLocal.executeCount)
        assertFalse(denied.contains("SECRET"))

        // 同一份伪造串在「本机渠道」下能执行，差异**只**来自宿主标志，与参数内容无关
        assertEquals(secret, dispatchRegistryTool(appToolName, forged, allowAppLocalTools = true))
        assertEquals(1, appLocal.executeCount)
    }

    /**
     * 授权入口在字节码层面只有 (String toolName, String argumentsJson, boolean allowAppLocalTools,
     * Continuation) 四个形参——**没有 contextJson 通道**，模型可见的上下文无法参与授权判定。
     */
    @Test
    fun dispatchEntryPointHasNoContextParameter() {
        val facade = Class.forName("com.yunian.ai.agent.host.AgentToolHostKt")
        val candidates = facade.declaredMethods.filter { it.name.startsWith("dispatchRegistryTool") }
        assertTrue("未找到 dispatchRegistryTool 的字节码入口", candidates.isNotEmpty())

        val entry = candidates.minByOrNull { it.parameterCount }
        assertNotNull(entry)
        assertEquals("分派入口应有 4 个形参（含协程 Continuation）", 4, entry!!.parameterCount)
        assertEquals(String::class.java, entry.parameterTypes[0])
        assertEquals(String::class.java, entry.parameterTypes[1])
        assertEquals(Boolean::class.javaPrimitiveType!!, entry.parameterTypes[2])
        assertEquals("kotlin.coroutines.Continuation", entry.parameterTypes[3].name)
    }

    @Test
    fun normalToolBehavesIdenticallyOnBothChannels() = runBlocking {
        val normal = registerNormalTool()

        val deniedChannel = dispatchRegistryTool(normalToolName, "{}", allowAppLocalTools = false)
        val appLocalChannel = dispatchRegistryTool(
            normalToolName,
            "{\"channel\":\"app\"}",
            allowAppLocalTools = true,
        )

        assertEquals("NORMAL-RESULT", deniedChannel)
        assertEquals("NORMAL-RESULT", appLocalChannel)
        assertEquals(2, normal.executeCount)
    }

    @Test
    fun unknownToolKeepsExistingNotRegisteredError() = runBlocking {
        assertEquals(
            "错误：未注册的工具 $unknownToolName",
            dispatchRegistryTool(unknownToolName, "{}", allowAppLocalTools = false),
        )
        assertEquals(
            "错误：未注册的工具 $unknownToolName",
            dispatchRegistryTool(unknownToolName, "{}", allowAppLocalTools = true),
        )
    }

    @Test
    fun appLocalToolResultIsRedactedInRecordAndLog() {
        registerAppLocalTool()
        registerNormalTool()

        val recorded = AppLocalToolGate.resultForRecord(appToolName, secret)
        val logged = AppLocalToolGate.resultForLog(appToolName, secret)

        assertEquals(AppLocalToolGate.REDACTED_RESULT, recorded)
        assertEquals(AppLocalToolGate.REDACTED_RESULT, logged)
        assertFalse(recorded.contains("SECRET"))
        assertFalse(logged.contains("SECRET"))

        // 普通工具：记录原文，日志保持既有 120 字符截断
        val long = "x".repeat(200)
        assertEquals("NORMAL-RESULT", AppLocalToolGate.resultForRecord(normalToolName, "NORMAL-RESULT"))
        assertEquals(long, AppLocalToolGate.resultForRecord(normalToolName, long))
        assertEquals(long.take(120), AppLocalToolGate.resultForLog(normalToolName, long))
    }

    @Test
    fun appLocalClassificationMatchesOnlyRegisteredAppLocalTools() {
        registerAppLocalTool()
        registerNormalTool()

        assertTrue(AppLocalToolGate.isAppLocalTool(appToolName))
        assertFalse(AppLocalToolGate.isAppLocalTool(normalToolName))
        assertFalse(AppLocalToolGate.isAppLocalTool(unknownToolName))
    }

    /** 与真实工具类的跨文件契约：get_user_profile 必须自声明 appLocalOnly，默认渠道直接拒绝。 */
    @Test
    fun realUserProfileToolIsRefusedOnDefaultChannel() = runBlocking {
        val tool = UserProfileTool()
        ToolRegistry.register(tool)
        try {
            assertTrue("UserProfileTool 必须声明 appLocalOnly = true", tool.appLocalOnly)

            val denied = dispatchRegistryTool(tool.name, "{}", allowAppLocalTools = false)
            assertEquals("错误：工具 ${tool.name} 在本会话不可用", denied)
            assertFalse(denied.contains("app_local_user_self_description"))
            assertFalse(denied.contains("has_avatar"))

            // 允许渠道下会走到真实 provider 分支（JVM 单测未注册 provider → 稳定错误 JSON，不抛异常）
            val allowed = dispatchRegistryTool(tool.name, "{}", allowAppLocalTools = true)
            assertTrue(allowed.isNotEmpty())
            assertFalse(allowed.contains("在本会话不可用"))
        } finally {
            ToolRegistry.unregister(tool.name)
        }
    }
}
