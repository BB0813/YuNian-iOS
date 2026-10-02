package com.yunian.ai.feature.settings.plugin

import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.uicommon.plugin.PluginSettingsCategory
import com.yunian.ai.uicommon.plugin.PluginSettingsPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PluginSettingsBoard] 的 JVM 单测：把「按 kind 分类」「搜索匹配」「行构造与排序」
 * 「呈现方式分流」「开关状态推导」五条纯逻辑逐条钉死。
 *
 * 「开关状态推导」一组（第 4 节）钉的是**债务 D3** 的修复：开关的轴是
 * **运行状态**（`PluginHost.loadedIds()`），不是持久化的用户意图。
 * 核心不变量 `states[id] == true ⇒ id ∈ hostLoadedIds`——**开关显示为开的插件必然已装载**。
 * 选择理由见 [PluginSettingsBoard] 的 KDoc。
 *
 * 纯 JVM（junit）：不碰 Android、不碰 Compose，因此 `android.util.Log` / `org.json`
 * 这些 Android stub 空壳不会参与——本测试只依赖 `core:domain` 与 `core:ui-common` 的
 * 纯 Kotlin 契约（与 `capability/CapabilityGrantBoardTest` 同一套做法）。
 *
 * 夹具用**当前真实的 7 个插件 id**（`channel.qqbot` / `channel.wechat` /
 * `coffee.luckin` / `automation.core` / `message.send` /
 * `skill.builtin_chat_protocol` / `sticker.preference`），
 * 让「页面会长什么样」在测试里就是可读的。
 */
class PluginSettingsBoardTest {

    // ── 夹具 ──

    private class FakePlugin(
        override val id: String,
        override val name: String,
        override val kind: PluginKind,
    ) : LianYuPlugin {
        override val requires: Set<String> = emptySet()
        override val configSchema: String? = null
        override fun setup(ctx: PluginContext) = Unit
    }

    /** 当前仓库里真实的 7 个插件（id / name / kind 与实现逐字一致）。 */
    private val realPlugins: List<LianYuPlugin> = listOf(
        FakePlugin("channel.qqbot", "QQ 机器人", PluginKind.ADAPTER),
        FakePlugin("channel.wechat", "微信", PluginKind.ADAPTER),
        FakePlugin("coffee.luckin", "瑞幸咖啡", PluginKind.TOOL),
        FakePlugin("automation.core", "自动化核心", PluginKind.TOOL),
        FakePlugin("message.send", "消息发送", PluginKind.TOOL),
        FakePlugin("skill.builtin_chat_protocol", "内置聊天工具协议技能", PluginKind.SKILL),
        FakePlugin("sticker.preference", "表情包偏好引擎", PluginKind.STICKER),
    )

    /**
     * 已注册设置区的夹具：`pluginId → 呈现方式`。
     *
     * 参数类型从 `Set<String>` 换成 Map 是 T4 契约扩展的**连带改动**：
     * 行的分流（内联 / 整页浮层）需要知道每个设置区的 [PluginSettingsPresentation]，
     * 只传 id 集合就表达不了。既有断言一条没动，只是构造夹具时多给了呈现方式。
     */
    private fun general(
        query: String = "",
        sections: Map<String, PluginSettingsPresentation> = emptyMap(),
    ) = PluginSettingsBoard.buildRows(realPlugins, PluginSettingsCategory.GENERAL, query, sections)

    private fun channel(
        query: String = "",
        sections: Map<String, PluginSettingsPresentation> = emptyMap(),
    ) = PluginSettingsBoard.buildRows(realPlugins, PluginSettingsCategory.CHANNEL, query, sections)

    // ── 1. 分类规则：按 kind 归，不设特例 ──

    @Test
    fun `ADAPTER 归消息通道`() {
        assertEquals(PluginSettingsCategory.CHANNEL, PluginSettingsBoard.categoryOf(PluginKind.ADAPTER))
    }

    @Test
    fun `非 ADAPTER 的四种 kind 全部归通用插件`() {
        listOf(PluginKind.TOOL, PluginKind.SKILL, PluginKind.STICKER, PluginKind.PIPELINE).forEach {
            assertEquals("kind=$it 应归通用插件", PluginSettingsCategory.GENERAL, PluginSettingsBoard.categoryOf(it))
        }
    }

    @Test
    fun `每个 kind 都恰好归入一个 Tab`() {
        // 分类函数返回非空类型，所以「覆盖全部 kind」由 **when 穷尽性**在编译期保证：
        // 将来给 PluginKind 加成员而忘了补映射，PluginSettingsBoard 直接编译不过，
        // 不会出现「新 kind 的插件在设置页里静默消失」。
        // 这条断言锁住的是运行期结果：每个 kind 都能落到 TAB_CATEGORIES 里的某一项。
        PluginKind.entries.forEach { kind ->
            assertTrue(
                "kind=$kind 归到了 TAB_CATEGORIES 之外的分类",
                PluginSettingsBoard.categoryOf(kind) in PluginSettingsBoard.TAB_CATEGORIES,
            )
        }
    }

    @Test
    fun `只有两个 Tab 且索引与分类互逆`() {
        assertEquals(2, PluginSettingsBoard.TAB_CATEGORIES.size)
        PluginSettingsBoard.TAB_CATEGORIES.forEachIndexed { index, category ->
            assertEquals(category, PluginSettingsBoard.categoryOfTab(index))
            assertEquals(index, PluginSettingsBoard.tabOfCategory(category))
        }
    }

    @Test
    fun `Tab 索引越界返回 null 而不是抛异常`() {
        assertNull(PluginSettingsBoard.categoryOfTab(2))
        assertNull(PluginSettingsBoard.categoryOfTab(-1))
    }

    // ── 2. 列表行构造与排序 ──

    @Test
    fun `消息通道 Tab 只含两个 ADAPTER 插件且按 id 升序`() {
        assertEquals(listOf("channel.qqbot", "channel.wechat"), channel().map { it.pluginId })
    }

    @Test
    fun `消息通道 Tab 不含工具授权保留条目`() {
        assertTrue(channel().none { it.pluginId == PluginSettingsBoard.TOOL_GRANT_ID })
    }

    @Test
    fun `通用插件 Tab 含其余全部插件`() {
        assertEquals(
            listOf("automation.core", "coffee.luckin", "message.send", "skill.builtin_chat_protocol", "sticker.preference"),
            general().filterNot { it.isSynthetic }.map { it.pluginId },
        )
    }

    @Test
    fun `工具授权保留条目排在通用插件列表最前`() {
        val rows = general()
        assertEquals(PluginSettingsBoard.TOOL_GRANT_ID, rows.first().pluginId)
        assertTrue(rows.first().isSynthetic)
        assertEquals(PluginSettingsCategory.GENERAL, rows.first().category)
    }

    @Test
    fun `工具授权保留条目标记为合成条目且恒有设置区`() {
        val grant = general().first()
        assertTrue(grant.isSynthetic)
        assertTrue("保留条目必须报告 hasSection，否则不会渲染出可展开箭头", grant.hasSection)
    }

    @Test
    fun `两个 Tab 的插件集合不重叠且合起来等于全部插件`() {
        val all = (channel() + general()).filterNot { it.isSynthetic }.map { it.pluginId }
        assertEquals(realPlugins.size, all.size)
        assertEquals(realPlugins.map { it.id }.sorted(), all.sorted())
    }

    @Test
    fun `行列表顺序稳定：与传入插件顺序无关`() {
        val shuffled = realPlugins.reversed()
        val a = PluginSettingsBoard.buildRows(shuffled, PluginSettingsCategory.GENERAL, "", emptyMap())
        val b = PluginSettingsBoard.buildRows(realPlugins, PluginSettingsCategory.GENERAL, "", emptyMap())
        assertEquals(b.map { it.pluginId }, a.map { it.pluginId })
    }

    @Test
    fun `未装载的插件仍然出现在列表里`() {
        // 列表只来自 host.plugins()，不读装载态——否则停用后无法再启用。
        assertEquals(7, (channel() + general()).filterNot { it.isSynthetic }.size)
    }

    @Test
    fun `hasSection 跟随已注册设置区集合`() {
        val sections = mapOf(
            "coffee.luckin" to PluginSettingsPresentation.INLINE,
            PluginSettingsBoard.TOOL_GRANT_ID to PluginSettingsPresentation.FULL_PAGE,
        )
        val rows = general(sections = sections)
        assertTrue(rows.first { it.pluginId == "coffee.luckin" }.hasSection)
        assertFalse(rows.first { it.pluginId == "message.send" }.hasSection)
        assertEquals(
            "hasSection 与 presentation 必须同进同退（presentation 是唯一事实来源）",
            rows.first { it.pluginId == "coffee.luckin" }.presentation,
            PluginSettingsPresentation.INLINE,
        )
        assertNull(rows.first { it.pluginId == "message.send" }.presentation)
    }

    @Test
    fun `同名真实插件存在时保留条目让位`() {
        val plugins = realPlugins + FakePlugin(PluginSettingsBoard.TOOL_GRANT_ID, "真插件", PluginKind.TOOL)
        val rows = PluginSettingsBoard.buildRows(plugins, PluginSettingsCategory.GENERAL, "", emptyMap())
        assertEquals(1, rows.count { it.pluginId == PluginSettingsBoard.TOOL_GRANT_ID })
        assertFalse("让位后应由真实插件占位", rows.first { it.pluginId == PluginSettingsBoard.TOOL_GRANT_ID }.isSynthetic)
    }

    @Test
    fun `副标题：真实插件显示 id`() {
        val row = general().first { it.pluginId == "message.send" }
        assertEquals("message.send", row.subtitle)
        assertEquals("消息发送", row.title)
    }

    // ── 3. 搜索匹配 ──

    @Test
    fun `空查询词全部通过`() {
        assertTrue(PluginSettingsBoard.matches("channel.qqbot", "QQ 机器人", ""))
        assertTrue(PluginSettingsBoard.matches("channel.qqbot", "QQ 机器人", "   "))
    }

    @Test
    fun `按 id 匹配且不区分大小写`() {
        assertTrue(PluginSettingsBoard.matches("channel.qqbot", "QQ 机器人", "QQBOT"))
        assertTrue(PluginSettingsBoard.matches("channel.qqbot", "QQ 机器人", "channel"))
    }

    @Test
    fun `按展示名匹配`() {
        assertTrue(PluginSettingsBoard.matches("coffee.luckin", "瑞幸咖啡", "咖啡"))
    }

    @Test
    fun `不匹配时返回 false`() {
        assertFalse(PluginSettingsBoard.matches("coffee.luckin", "瑞幸咖啡", "微信"))
    }

    @Test
    fun `搜索只在当前 Tab 内过滤`() {
        // 同一个查询词分别作用在两个 Tab 上：命中集合是「该分类 ∩ 查询词」，
        // 不会把另一个 Tab 的命中混进来（TabRow 的高亮与列表内容始终一一对应）。
        assertEquals(listOf("channel.qqbot", "channel.wechat"), channel("channel").map { it.pluginId })
        assertTrue("通用插件里没有 id/名字含 channel 的条目", general("channel").isEmpty())
        // 反过来：搜一个通用插件，消息通道 Tab 也必须为空。
        assertTrue(channel("咖啡").isEmpty())
        assertEquals(listOf("coffee.luckin"), general("咖啡").map { it.pluginId })
    }

    @Test
    fun `查询词命中插件 id 的中段`() {
        assertEquals(listOf("coffee.luckin"), general("luckin").map { it.pluginId })
    }

    @Test
    fun `搜索可以命中保留条目`() {
        assertTrue(general("tool.grant").any { it.pluginId == PluginSettingsBoard.TOOL_GRANT_ID })
        assertTrue(general("工具授权").any { it.pluginId == PluginSettingsBoard.TOOL_GRANT_ID })
    }

    @Test
    fun `搜不到时返回空列表（由页面渲染空态）`() {
        assertTrue(general("绝对不存在的插件").isEmpty())
        assertTrue(channel("绝对不存在的插件").isEmpty())
    }

    @Test
    fun `空态文案区分「没匹配」与「暂无插件」`() {
        assertEquals(PluginSettingsBoard.EMPTY_SEARCH_HINT, PluginSettingsBoard.emptyHint("咖啡"))
        assertEquals(PluginSettingsBoard.EMPTY_HINT, PluginSettingsBoard.emptyHint(""))
        assertEquals(PluginSettingsBoard.EMPTY_HINT, PluginSettingsBoard.emptyHint("   "))
    }

    // ── 4. 开关状态推导（债务 D3）──
    //
    // 规则是**合取**：开 = 已装载 且 用户没停用。
    // 与旧口径（开 = 已装载 或 id ∉ 停用集合）的差别只有一处，但那一处正是本次修复：
    // 「用户意图为启用、但当前没装载」现在显示为**关**——插件装载失败后界面不再宣称它开着。

    @Test
    fun `宿主说它活着且用户没停用，开关就显示为开`() {
        val states = PluginSettingsBoard.deriveSwitchStates(
            rows = general(),
            hostLoadedIds = setOf("coffee.luckin"),
            disabledIds = null,
        )
        assertTrue(states.getValue("coffee.luckin"))
    }

    @Test
    fun `未装载的插件一律显示为关——即使存储里没有任何停用记录`() {
        // ⚠️ 这条断言在 D3 之前是 `states.values.all { it }`（全部为开），本次**有意收紧**。
        //
        // 旧口径把「不在停用集合里」当成「显式启用」并直接渲染成 ON，于是
        // 「宿主一个插件都没装载」（典型场景：蓝图解析失败，见债务 D4）时，
        // 整页开关全是 ON 而插件一个都没跑——这就是「假装成功」。
        //
        // 保留的**意图**仍然是「不得把开关锁死」：存储未注册 / 读失败（disabledIds = null）
        // 时必须能正常出结果，且一旦宿主装载成功、开关立刻能变成开（见下一条断言）。
        // 加强的部分：未装载的插件不得显示为开。
        val states = PluginSettingsBoard.deriveSwitchStates(general(), emptySet(), null)
        assertEquals(5, states.size)
        assertTrue(
            "未装载的插件显示为开就是「假装成功」：开关说开着、插件却没跑",
            states.values.none { it },
        )
        // 「不锁死」的可断言形式：同一份 rows、存储同样读不到，只要宿主装载了，开关就能开。
        assertTrue(
            "存储读不到不得把开关锁死：宿主装载成功即应显示为开",
            PluginSettingsBoard.deriveSwitchStates(general(), setOf("coffee.luckin"), null)
                .getValue("coffee.luckin"),
        )
    }

    @Test
    fun `装载失败的核心场景：意图为启用但宿主没装载 → 关`() {
        // 债务 D3 的原始症状：插件装载失败（缺依赖服务 / 清单不一致 / 装配异常 /
        // 蓝图解析失败导致整批没装载）之后，setEnabled 会把该 id 从停用集合里移除，
        // 于是「未装载 + 不在停用集合里」= 用户意图为启用。
        // 旧口径对这一行渲染成 ON；本实现渲染成 OFF——插件没跑，开关就不许说它开着。
        val states = PluginSettingsBoard.deriveSwitchStates(
            rows = general(),
            hostLoadedIds = emptySet(),
            disabledIds = emptySet(),
        )
        assertFalse(
            "装载失败后显示为开，就是本次要修的「假装成功」",
            states.getValue("coffee.luckin"),
        )
        assertFalse(states.getValue("message.send"))
    }

    @Test
    fun `开关显示为开的插件必然已装载（D3 的可断言不变量）`() {
        // 这是本次修复最凝练的形式化表达，也覆盖了「能显示 ON 的充要条件」：
        //   开关为开  ⟺  已装载
        // 持久化意图不得掩盖运行事实。
        val rows = general()
        val id = "coffee.luckin"
        val cases = listOf(
            Triple(true, false, true),   // 已装载 + 未停用 → 开
            Triple(true, true, true),    // 已装载 + 存储停用 → 仍在运行，必须显示开
            Triple(false, false, false), // 未装载 + 未停用 → 关（D3 修复点）
            Triple(false, true, false),  // 未装载 + 已停用 → 关
        )
        cases.forEach { (loaded, disabled, expected) ->
            val states = PluginSettingsBoard.deriveSwitchStates(
                rows = rows,
                hostLoadedIds = if (loaded) setOf(id) else emptySet(),
                disabledIds = if (disabled) setOf(id) else emptySet(),
            )
            assertEquals(
                "loaded=" + loaded + " disabled=" + disabled + " 时开关应为 " + expected,
                expected,
                states.getValue(id),
            )
        }
        // 不变量本身：任何显示为开的插件都必须在 hostLoadedIds 里。
        listOf(emptySet(), setOf("coffee.luckin"), setOf("message.send", "coffee.luckin")).forEach { loaded ->
            val states = PluginSettingsBoard.deriveSwitchStates(rows, loaded, emptySet())
            val onButNotLoaded = states.filterValues { it }.keys - loaded
            assertTrue(
                "开关为开但宿主没装载: " + onButNotLoaded,
                onButNotLoaded.isEmpty(),
            )
        }
    }

    @Test
    fun `停用集合里的 id 显示为关`() {
        // 已装载的插件被用户显式停用（写存储成功、本次会话还没重启）→ 显示为关。
        // 装载态给的是 `coffee.luckin`，所以这条断言单独钉住**停用集合**那一侧，
        // 不会因为「未装载」这个原因而侥幸通过。
        val states = PluginSettingsBoard.deriveSwitchStates(
            rows = general(),
            hostLoadedIds = setOf("coffee.luckin"),
            disabledIds = setOf("message.send"),
        )
        assertFalse(states.getValue("message.send"))
        assertTrue(states.getValue("coffee.luckin"))
    }

    @Test
    fun `保存失败保留旧停用意图时仍运行插件必须显示开`() {
        val states = PluginSettingsBoard.deriveSwitchStates(
            general(), setOf("coffee.luckin"), setOf("coffee.luckin", "message.send"),
        )
        assertTrue(states.getValue("coffee.luckin"))
        assertFalse(states.getValue("message.send"))
    }

    @Test
    fun `空停用集合等价于 store 缺失`() {
        val a = PluginSettingsBoard.deriveSwitchStates(general(), emptySet(), emptySet())
        val b = PluginSettingsBoard.deriveSwitchStates(general(), emptySet(), null)
        assertEquals(b, a)
    }

    @Test
    fun `合成条目没有开关`() {
        val states = PluginSettingsBoard.deriveSwitchStates(general(), emptySet(), null)
        assertFalse(
            "保留条目不是插件，不该出现在开关映射里",
            states.containsKey(PluginSettingsBoard.TOOL_GRANT_ID),
        )
    }

    @Test
    fun `开关映射的键集合等于行里的真实插件`() {
        val rows = general()
        val states = PluginSettingsBoard.deriveSwitchStates(rows, emptySet(), null)
        assertEquals(rows.filterNot { it.isSynthetic }.map { it.pluginId }.toSet(), states.keys)
    }

    @Test
    fun `宿主加载集合里多余的 id 不会凭空造出行`() {
        val states = PluginSettingsBoard.deriveSwitchStates(
            rows = general(),
            hostLoadedIds = setOf("不存在的插件"),
            disabledIds = null,
        )
        assertFalse(states.containsKey("不存在的插件"))
    }

    // ── 5. 呈现方式分流（T4：PluginSettingsPresentation → 页面行为）──
    //
    // 这一组是**崩溃防线**的纯逻辑落点：整页设置区（自带 Scaffold / 滚动容器）
    // 一旦被内联进外层 LazyColumn 的 item，内层滚动容器会拿到无界最大高度约束、
    // 运行期抛异常。分流规则必须在这里（纯逻辑）钉死，不能只活在 Composable 里。

    @Test
    fun `INLINE 设置区：点行是行内展开`() {
        val row = general(sections = mapOf("coffee.luckin" to PluginSettingsPresentation.INLINE))
            .first { it.pluginId == "coffee.luckin" }
        assertTrue(row.hasSection)
        assertEquals(PluginSettingsPresentation.INLINE, row.presentation)
        assertEquals(PluginRowAction.TOGGLE_INLINE, row.action)
    }

    @Test
    fun `FULL_PAGE 设置区：点行是打开整页浮层`() {
        val row = general(sections = mapOf("coffee.luckin" to PluginSettingsPresentation.FULL_PAGE))
            .first { it.pluginId == "coffee.luckin" }
        assertTrue(row.hasSection)
        assertEquals(PluginSettingsPresentation.FULL_PAGE, row.presentation)
        assertEquals(
            "整页设置区必须走浮层：内联渲染会因无界高度约束在运行期抛异常",
            PluginRowAction.OPEN_FULL_PAGE,
            row.action,
        )
    }

    @Test
    fun `两种呈现方式映射到两个不同的动作`() {
        fun actionOf(presentation: PluginSettingsPresentation) =
            general(sections = mapOf("coffee.luckin" to presentation))
                .first { it.pluginId == "coffee.luckin" }
                .action

        assertEquals(PluginRowAction.TOGGLE_INLINE, actionOf(PluginSettingsPresentation.INLINE))
        assertEquals(PluginRowAction.OPEN_FULL_PAGE, actionOf(PluginSettingsPresentation.FULL_PAGE))
        assertEquals(
            "两种呈现方式必须映射到不同动作，否则「分流」就等于没做",
            2,
            PluginSettingsPresentation.entries.map(::actionOf).toSet().size,
        )
    }

    @Test
    fun `整页设置区即使拿到 expanded = true 也绝不内联渲染`() {
        // 最后一道防线：即便注册表在页面停留期间被换掉、某行意外带着 expanded = true，
        // FULL_PAGE 设置区也绝不会被塞进外层 LazyColumn 的 item 里。
        val fullPage = general(sections = mapOf("coffee.luckin" to PluginSettingsPresentation.FULL_PAGE))
            .first { it.pluginId == "coffee.luckin" }
        assertFalse("FULL_PAGE 被内联渲染就是本次要修的那个崩溃", fullPage.rendersInline(expanded = true))
        assertFalse(fullPage.rendersInline(expanded = false))

        val inline = general(sections = mapOf("coffee.luckin" to PluginSettingsPresentation.INLINE))
            .first { it.pluginId == "coffee.luckin" }
        assertTrue(inline.rendersInline(expanded = true))
        assertFalse("收起状态不渲染", inline.rendersInline(expanded = false))
    }

    @Test
    fun `未注册设置区：仍是行内展开，让「无可配置项」的兜底提示可达`() {
        val row = general().first { it.pluginId == "message.send" }
        assertFalse(row.hasSection)
        assertNull(row.presentation)
        assertEquals(
            "未注册设置区的行点了要能展开出「此插件无可配置项」，不能变成点了没反应的死行",
            PluginRowAction.TOGGLE_INLINE,
            row.action,
        )
        assertTrue("没有设置区时展开区里显示兜底提示，不是死行", row.rendersInline(expanded = true))
    }

    @Test
    fun `两个通道设置区都走 FULL_PAGE 浮层`() {
        // 与 feature:wechat / feature:qqbot 侧的同名断言成对：那两个设置区渲染的是
        // 自带 GlassPageScaffold 的整页 Screen。这里从**页面侧**再钉一次——
        // 它们声明 FULL_PAGE，buildRows 就必须把它们分流到浮层，绝不内联。
        val rows = channel(
            sections = mapOf(
                "channel.wechat" to PluginSettingsPresentation.FULL_PAGE,
                "channel.qqbot" to PluginSettingsPresentation.FULL_PAGE,
            )
        )
        assertEquals(listOf("channel.qqbot", "channel.wechat"), rows.map { it.pluginId })
        assertTrue(
            "通道设置区必须全部走整页浮层: " + rows.map { it.pluginId to it.action },
            rows.all { it.action == PluginRowAction.OPEN_FULL_PAGE },
        )
        assertTrue(rows.none { it.rendersInline(expanded = true) })
    }

    @Test
    fun `工具授权保留条目走 FULL_PAGE 分支`() {
        // T4 之前这是页面里硬编码的 showToolGrants 分支；现在它只是一个普通的
        // FULL_PAGE 设置区——页面不再认识任何具体插件 id。
        val grant = general(
            sections = mapOf(PluginSettingsBoard.TOOL_GRANT_ID to PluginSettingsPresentation.FULL_PAGE)
        ).first()
        assertTrue(grant.isSynthetic)
        assertTrue(grant.hasSection)
        assertEquals(PluginRowAction.OPEN_FULL_PAGE, grant.action)
        assertFalse(grant.rendersInline(expanded = true))
    }

    @Test
    fun `工具授权保留条目在注册表为空时也按 FULL_PAGE 的安全默认处理`() {
        // 本页第一帧（DisposableEffect 还没跑完）注册表里查不到 tool.grant。
        // 按契约的安全默认 FULL_PAGE 处理，与 PluginSettingsSection.presentation 的
        // 默认值同向：宁可整页，不可崩溃。
        val grant = general(sections = emptyMap()).first()
        assertTrue("保留条目必须报告 hasSection，否则它会变成点了没反应的死行", grant.hasSection)
        assertEquals(PluginSettingsPresentation.FULL_PAGE, grant.presentation)
        assertEquals(PluginRowAction.OPEN_FULL_PAGE, grant.action)
    }

    @Test
    fun `hasSection 与 presentation 不可能不一致`() {
        val sections = mapOf(
            "coffee.luckin" to PluginSettingsPresentation.INLINE,
            "message.send" to PluginSettingsPresentation.FULL_PAGE,
            PluginSettingsBoard.TOOL_GRANT_ID to PluginSettingsPresentation.FULL_PAGE,
        )
        val rows = general(sections = sections) + channel(sections = sections)
        rows.forEach { row ->
            assertEquals(
                "行 ${row.pluginId} 的 hasSection 与 presentation 不一致",
                row.presentation != null,
                row.hasSection,
            )
        }
    }
}
