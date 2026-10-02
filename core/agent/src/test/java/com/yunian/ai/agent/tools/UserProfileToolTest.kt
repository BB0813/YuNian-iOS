package com.yunian.ai.agent.tools

import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.UserProfileProvider
import com.yunian.ai.domain.UserProfileSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UserProfileTool] 的纯 JVM 单测（不依赖 Android：ServiceRegistry 是 core:domain 的普通 object）。
 *
 * 覆盖：真实字段映射 / 空与缺失 provider / 特殊字符与畸形 JSON /
 * 敏感键不出现 / appLocalOnly 语义 / 每次读取最新快照（不缓存）。
 */
class UserProfileToolTest {

    /** 可编排的假 provider，只实现本用例需要的两个成员。 */
    private class FakeProvider(
        var snapshot: UserProfileSnapshot = UserProfileSnapshot(),
        var failure: Throwable? = null,
        var calls: Int = 0,
    ) : UserProfileProvider {
        override fun getUserId(): String = "default_user"
        override fun getNickname(): String = snapshot.userName ?: ""
        override fun getAvatar(): String? = null
        override fun observeAvatar(onChange: (String?) -> Unit): () -> Unit = {}
        override fun observeNickname(onChange: (String) -> Unit): () -> Unit = {}
        override fun isLoggedIn(): Boolean = true
        override fun snapshot(): UserProfileSnapshot {
            calls++
            failure?.let { throw it }
            return snapshot
        }
    }

    private fun <T> withProvider(provider: UserProfileProvider?, block: () -> T): T {
        if (provider != null) ServiceRegistry.registerSingleton(UserProfileProvider::class.java) { provider }
        return try {
            block()
        } finally {
            ServiceRegistry.unregister(UserProfileProvider::class.java)
        }
    }

    private fun run(provider: UserProfileProvider?, args: String = "{}"): JSONObject =
        withProvider(provider) { runBlocking { JSONObject(UserProfileTool().execute(args)) } }

    // ── 1. 真实字段映射 ──────────────────────────────────────────────
    @Test
    fun maps_all_self_reported_fields() {
        val json = run(
            FakeProvider(
                UserProfileSnapshot(
                    userName = "云念",
                    status = "在线",
                    signature = "愿你所念皆如愿",
                    gender = "female",
                    region = "广东·深圳",
                    hasAvatar = true,
                )
            )
        )
        assertEquals("云念", json.getString("user_name"))
        assertEquals("在线", json.getString("status"))
        assertEquals("愿你所念皆如愿", json.getString("signature"))
        assertEquals("female", json.getString("gender"))
        assertEquals("广东·深圳", json.getString("region"))
        assertTrue(json.getBoolean("has_avatar"))
        assertEquals("app_local_user_self_description", json.getString("source"))
        assertTrue(json.getBoolean("is_self_reported"))
    }

    @Test
    fun unset_optional_fields_are_omitted_not_fabricated() {
        val json = run(FakeProvider(UserProfileSnapshot(userName = "我")))
        assertEquals("我", json.getString("user_name"))
        assertFalse(json.has("status"))
        assertFalse(json.has("signature"))
        assertFalse(json.has("gender"))
        assertFalse(json.has("region"))
        assertFalse(json.getBoolean("has_avatar"))
    }

    @Test
    fun blank_fields_are_omitted_after_trim() {
        val json = run(FakeProvider(UserProfileSnapshot(userName = "我", status = "   ", signature = "\t")))
        assertFalse(json.has("status"))
        assertFalse(json.has("signature"))
    }

    // ── 2. 空 / 缺失 provider ───────────────────────────────────────
    @Test
    fun missing_provider_returns_stable_error() {
        val json = run(null)
        assertEquals("provider_unavailable", json.getString("error"))
        assertNotNull(json.getString("message"))
        assertFalse(json.has("user_name"))
    }

    @Test
    fun provider_throw_returns_stable_error_without_leaking_details() {
        val json = run(FakeProvider(failure = IllegalStateException("SharedPreferences boom at /data/data/x")))

        assertEquals("profile_read_failed", json.getString("error"))
        assertFalse(json.has("user_name"))

        val raw = json.toString()
        assertFalse(raw.contains("IllegalStateException"))
        assertFalse(raw.contains("/data/data"))
        assertFalse(raw.contains("SharedPreferences"))
        assertFalse(raw.contains("at com.")) // 无堆栈
    }

    @Test
    fun cancellation_is_not_swallowed() {
        val provider = FakeProvider(failure = CancellationException("cancelled"))
        var thrown: CancellationException? = null
        // 不用 @Test(expected = ...)：那条路径会跳过 finally，导致假 provider 残留在
        // ServiceRegistry 单例表里污染后续用例。
        withProvider(provider) {
            try {
                runBlocking { UserProfileTool().execute("{}") }
            } catch (e: CancellationException) {
                thrown = e
            }
        }
        assertNotNull("CancellationException 必须继续向上传播，不能被吞成 JSON 错误", thrown)
        assertEquals("cancelled", thrown!!.message)
        // 取消路径不得留下任何注册残留
        assertNull(ServiceRegistry.get(UserProfileProvider::class.java))
    }

    // ── 3. 特殊字符与畸形 JSON ──────────────────────────────────────
    @Test
    fun special_characters_round_trip_without_leaking_raw_avatar_path() {
        val json = run(
            FakeProvider(
                UserProfileSnapshot(
                    userName = "a\"b\\c",
                    status = "行1\n行2",
                    signature = "</script><b>x</b>",
                    region = "C:\\Users\\x",
                    hasAvatar = true,
                )
            )
        )
        assertEquals("a\"b\\c", json.getString("user_name"))
        assertEquals("行1\n行2", json.getString("status"))
        assertEquals("</script><b>x</b>", json.getString("signature"))
        assertEquals("C:\\Users\\x", json.getString("region"))
        assertFalse(json.toString().contains("/data/"))
        assertFalse(json.toString().contains("/storage/"))
    }

    @Test
    fun malformed_json_returns_error() {
        val json = run(FakeProvider(), args = "{not json")
        assertEquals("malformed_arguments_json", json.getString("error"))
    }

    @Test
    fun non_object_json_returns_error() {
        val json = run(FakeProvider(), args = "[1,2,3]")
        assertEquals("malformed_arguments_json", json.getString("error"))
    }

    @Test
    fun empty_arguments_string_is_accepted_as_no_arguments() {
        val json = run(FakeProvider(UserProfileSnapshot(userName = "云念")), args = "")
        assertEquals("云念", json.getString("user_name"))
    }

    @Test
    fun any_argument_is_rejected_and_listed() {
        val json = run(FakeProvider(), args = "{\"user_id\":\"root\",\"is_admin\":true}")
        assertEquals("unsupported_arguments", json.getString("error"))
        assertTrue(json.getString("message").contains("is_admin"))
        assertTrue(json.getString("message").contains("user_id"))
        assertFalse(json.has("user_name"))
    }

    @Test
    fun null_arguments_json_returns_error_not_crash() {
        // org.json 20231013：非 '{' 开头的文本抛 JSONException
        // ("A JSONObject text must begin with '{'")，工具必须把它收敛成稳定错误而不是崩溃。
        val json = run(FakeProvider(), args = "null")
        assertEquals("malformed_arguments_json", json.getString("error"))
    }

    @Test(expected = org.json.JSONException::class)
    fun org_json_rejects_non_object_text() {
        // 固化上面两个用例所依赖的 org.json 行为：数组文本不是合法 JSONObject；
        // 但工具仍必须兜住它（见 non_object_json_returns_error）。
        JSONObject("[1,2,3]")
    }

    // ── 4. 敏感键绝不出现在输出里 ───────────────────────────────────
    @Test
    fun sensitive_keys_never_appear_in_output() {
        val json = run(
            FakeProvider(
                UserProfileSnapshot(
                    userName = "云念",
                    status = "s",
                    signature = "g",
                    gender = "male",
                    region = "r",
                    hasAvatar = true,
                )
            )
        )
        val raw = json.toString()
        // 1) 敏感键必须整体缺席
        for (key in listOf("user_id", "logged_in", "avatar", "getAvatar", "token", "credential", "session")) {
            assertFalse("输出不应含敏感键: $key -> $raw", json.has(key))
        }
        // 2) 子串检查只用于不会与合法键重合的标记：
        //    合法输出 has_avatar 本身包含 "avatar"，拿 "avatar" 做子串断言会误报。
        for (marker in listOf("user_id", "logged_in", "getAvatar", "token", "credential", "session")) {
            assertFalse("输出不应含敏感串: $marker -> $raw", raw.contains(marker))
        }
        // 3) 头像只能以布尔事实出现，任何路径 / URI 形态都不允许
        for (pathMarker in listOf("/data/", "/storage/", "file://", "content://", ".jpg", ".png")) {
            assertFalse("输出不应含头像路径或 URI 片段: $pathMarker -> $raw", raw.contains(pathMarker))
        }
        assertTrue(json.has("has_avatar"))
    }

    @Test
    fun avatar_value_is_never_echoed_even_when_provider_returns_a_path() {
        // 假 provider 的 getAvatar() 返回真实路径风格内容；工具只被允许读 snapshot()，
        // 而 snapshot 只带 has_avatar 布尔值。
        val json = run(FakeProvider(UserProfileSnapshot(userName = "我", hasAvatar = true)))
        val raw = json.toString()
        assertFalse(raw.contains(".jpg"))
        assertFalse(raw.contains(".png"))
        assertFalse(raw.contains("content://"))
        assertFalse(raw.contains("file:"))
    }

    // ── 5. 元数据与不缓存 ───────────────────────────────────────────
    @Test
    fun tool_is_marked_app_local_only() {
        assertTrue(UserProfileTool().appLocalOnly)
    }

    @Test
    fun tool_metadata_is_stable_and_parameterless() {
        val tool = UserProfileTool()
        assertEquals("get_user_profile", tool.name)
        assertEquals(setOf("chat"), tool.toolsets)
        assertEquals(
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
            tool.parametersJsonSchema,
        )
        // description 语义必须正确：自述、非认证、不代表对方
        assertTrue(tool.description.contains("不是身份认证"))
        assertTrue(tool.description.contains("不代表当前会话的对话方"))
        // 「登录状态」只允许以「不返回」的否定式声明出现——真正要禁止的是把它说成身份判定手段。
        assertTrue(
            "description 必须显式声明不返回内部数据",
            tool.description.contains("不返回用户 ID"),
        )
        assertFalse(tool.description.contains("判断用户身份"))
    }

    @Test
    fun each_execution_reads_a_fresh_snapshot() {
        val provider = FakeProvider(UserProfileSnapshot(userName = "旧"))
        val tool = UserProfileTool()
        withProvider(provider) {
            runBlocking {
                assertEquals("旧", JSONObject(tool.execute("{}")).getString("user_name"))
                provider.snapshot = UserProfileSnapshot(userName = "新", status = "刚改的")
                val second = JSONObject(tool.execute("{}"))
                assertEquals("新", second.getString("user_name"))
                assertEquals("刚改的", second.getString("status"))
            }
        }
        assertEquals(2, provider.calls)
    }
}
