package com.yunian.ai.feature.settings.plugin

import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.uicommon.plugin.PluginSettingsCategory
import com.yunian.ai.uicommon.plugin.PluginSettingsPresentation

/**
 * 「插件设置」页的**纯逻辑**：分类规则 / 搜索匹配 / 列表行构造 / 呈现方式分流 / 开关状态推导。
 *
 * 为什么单独成类：本仓库没有 Robolectric，Compose 也不做仪器测试，页面逻辑若写在
 * Composable 里就等于零覆盖（与 `capability/CapabilityGrantBoard.kt` 同一套理由）。
 * 这里全部是纯 Kotlin（只依赖 `core:domain` 的 [LianYuPlugin] / [PluginKind] 与
 * `core:ui-common` 的 [PluginSettingsCategory]，不碰 Android、不碰 Compose），
 * 因此可以在 `:feature:settings` 的 JVM 单测里逐条钉死。
 *
 * ## 分类规则：按 [PluginKind] 归，不设特例
 *
 * 用户裁定「cordis 万物皆是插件」，因此**没有任何按 id 写死的通道清单**：
 *
 * | [PluginKind] | 归属 Tab |
 * |---|---|
 * | [PluginKind.ADAPTER] | 消息通道 |
 * | [PluginKind.TOOL] / [SKILL] / [STICKER] / [PIPELINE] | 通用插件 |
 *
 * 这条规则同时覆盖**现在和将来**的插件——新增一个 TOOL 插件不需要动本文件，
 * 也不会因为漏改清单而在设置页里消失。
 *
 * ## 搜索范围：只在**当前 Tab 内**搜（不跨 Tab）
 *
 * 两种可选语义（任务书要求二选一）：
 *
 * 1. **跨 Tab 全局搜**：一搜就把两个 Tab 的命中合起来显示。省一次切 Tab，
 *    但会让 TabRow 变成「装饰」——当前高亮的 Tab 与列表内容不再对应，
 *    用户看到消息通道 Tab 亮着、列表里却是通用插件，这是比多点一次更坏的体验。
 * 2. **当前 Tab 内搜**（本实现）：查询词只过滤当前 Tab 的条目。
 *
 * 选 2 的理由：TabRow 与 HorizontalPager 是**同一份数据**的两个视图，
 * 过滤后每个 Tab 展示的都是「该分类 ∩ 查询词」，Tab 与内容始终一一对应，
 * 不会出现「高亮的 Tab 与列表不匹配」的错位。代价是搜一个不确定在哪个 Tab 的插件时
 * 可能需要切一次 Tab——但列表本身只有个位数条目（当前 7 个插件 + 1 个保留条目），
 * 且两个 Tab 各自都给出了空态提示（见 [EMPTY_SEARCH_HINT]），不会让人以为搜坏了。
 *
 * 匹配规则：对 [PluginSettingsRow.pluginId] **与** [PluginSettingsRow.title]
 * 做**不区分大小写**的包含匹配（[String.contains] + [ignoreCase]）；
 * 查询词 trim 后为空视为「未搜索」，全部通过。
 *
 * ## 开关只表达运行态
 *
 * ON 当且仅当宿主已装载。持久化意图不能覆盖运行态：保存失败或卸载失败时，
 * 仍在运行的插件必须保持 ON；未装载的插件无论存储是什么都必须 OFF。
 * 保存失败由切换事务单独反馈「当前生效但未保存，重启可能恢复」。
 */
object PluginSettingsBoard {

    /**
     * 「工具授权」保留条目的 id。
     *
     * 它**不是** [LianYuPlugin.id]——「能力授权」不是一个插件，而是并入通用插件分类的
     * 一个固定入口（用户裁定 Q2/Q6）。用 `tool.grant` 而不是 `capability.grant`：
     * 它的内容是「Agent 工具的能力授权清单」，`tool.` 前缀与 `tool` 语义对齐，
     * 也避免与 `skill.builtin_chat_protocol` 这类真实插件 id 前缀混淆。
     *
     * ⚠️ 该 id 位于**插件 id 的取值域内**，只是当前没有同名插件。若将来真的有插件
     * 注册成 `tool.grant`，[buildRows] 的 `availableSections` 会把保留条目让位给
     * 真实插件（真实插件优先），不会出现两行同名条目。
     */
    const val TOOL_GRANT_ID: String = "tool.grant"

    /** 「工具授权」保留条目的展示名。 */
    const val TOOL_GRANT_TITLE: String = "工具授权"

    /** 「工具授权」保留条目的副标题（说明它为什么在这里）。 */
    const val TOOL_GRANT_SUBTITLE: String = "管理需要确认的 Agent 工具能力"

    /** 空列表提示（该分类下确实没有插件）。 */
    const val EMPTY_HINT: String = "此分类下暂无插件"

    /** 空搜索提示（有插件但当前查询词没命中）。 */
    const val EMPTY_SEARCH_HINT: String = "没有匹配的插件"

    /**
     * Tab 顺序：索引 0 = 消息通道，索引 1 = 通用插件。
     *
     * [PluginSettingsCategory] 的**声明顺序**即 Tab 顺序，因此
     * [categoryOfTab] / [tabOfCategory] 互为逆映射，TabRow 与 HorizontalPager
     * 用的是同一个列表，不可能错位。
     */
    val TAB_CATEGORIES: List<PluginSettingsCategory> = PluginSettingsCategory.entries.toList()

    /**
     * 分类规则（唯一的 kind → 分类映射点）。
     *
     * 只有 [PluginKind.ADAPTER] 归「消息通道」，其余全部归「通用插件」——
     * 含 [PluginKind.PIPELINE]：管道是回合后处理逻辑，没有独立的用户界面概念，
     * 归到通用插件让「万物皆是插件」的裁定落到一个具体的、可见的位置。
     */
    fun categoryOf(kind: PluginKind): PluginSettingsCategory = when (kind) {
        PluginKind.ADAPTER -> PluginSettingsCategory.CHANNEL
        PluginKind.TOOL,
        PluginKind.SKILL,
        PluginKind.STICKER,
        PluginKind.PIPELINE -> PluginSettingsCategory.GENERAL
    }

    /** Tab 索引 → 分类；越界返回 null（Pager 与 TabRow 数量不一致时 fail-soft）。 */
    fun categoryOfTab(index: Int): PluginSettingsCategory? = TAB_CATEGORIES.getOrNull(index)

    /** 分类 → Tab 索引；分类缺失返回 0（回到第一个 Tab 而不是崩）。 */
    fun tabOfCategory(category: PluginSettingsCategory): Int =
        TAB_CATEGORIES.indexOf(category).takeIf { it >= 0 } ?: 0

    /** 该分类下的空态文案：有查询词时说「没匹配」，否则说「暂无」。 */
    fun emptyHint(query: String): String =
        if (query.isBlank()) EMPTY_HINT else EMPTY_SEARCH_HINT

    /**
     * 搜索匹配：对 id 与展示名做不区分大小写的包含匹配。
     *
     * 空白查询词（含只有空格）一律通过——用户在搜索框里敲了个空格不该看到空列表。
     */
    fun matches(id: String, title: String, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return id.contains(q, ignoreCase = true) || title.contains(q, ignoreCase = true)
    }

    /**
     * 构造一个 Tab 的行列表：**分类过滤 → 搜索过滤 → 排序**。
     *
     * 排序（稳定，与注册顺序无关）：
     * 1. 「工具授权」保留条目**排在最前**——它是固定入口，位置固定才找得到；
     * 2. 其余按 [PluginSettingsRow.pluginId] 升序，与 `PluginHost.plugins()` 的排序一致。
     *
     * @param plugins 全部已注册插件（含未装载的——列表**不因未装载而消失**，
     *   否则用户没法把停用的插件重新打开）。
     * @param category 当前 Tab 的分类。
     * @param query 搜索词；空白 = 不过滤。
     * ## 呈现方式在这里进入行模型
     *
     * 「点这一行会发生什么」由设置区的 [PluginSettingsPresentation] 决定，而这个判断
     * **必须留在这里**（纯逻辑、JVM 可测），不能下沉到 Composable 里——否则
     * 「FULL_PAGE 走整页浮层 / INLINE 走内联展开」这条**唯一**的分流规则就零覆盖了
     * （与 `capability/CapabilityGrantBoard.kt` 同一套理由）。
     * 于是 [PluginSettingsRow.action] 是纯推导属性，Composable 只照着画。
     *
     * @param availableSections 当前**已注册设置区**的 `pluginId → 呈现方式`
     *   （`PluginSettingsSections.all().associate { it.pluginId to it.presentation }`）。
     *   **不是**「已装载」集合：设置区由插件 `setup()` 注册、由 `ctx.effect` 撤销，
     *   所以它天然等于「已完成装配」，比装载态更贴近「展开后有没有内容」。
     */
    fun buildRows(
        plugins: List<LianYuPlugin>,
        category: PluginSettingsCategory,
        query: String,
        availableSections: Map<String, PluginSettingsPresentation>,
    ): List<PluginSettingsRow> {
        val grantRow = PluginSettingsRow(
            pluginId = TOOL_GRANT_ID,
            title = TOOL_GRANT_TITLE,
            subtitle = TOOL_GRANT_SUBTITLE,
            category = PluginSettingsCategory.GENERAL,
            isSynthetic = true,
            // 保留条目由设置页自己在 DisposableEffect 里注册（见 PluginSettingsScreen），
            // 所以注册表里**正常**查得到它。查不到时（本页第一帧，DisposableEffect 还没
            // 跑完）按契约的安全默认 FULL_PAGE 处理，与 PluginSettingsSection.presentation
            // 的默认值同向：宁可整页，不可崩溃。
            presentation = availableSections[TOOL_GRANT_ID] ?: PluginSettingsPresentation.FULL_PAGE,
        )

        val pluginRows = plugins
            // 防御性去重：host.plugins() 已是按 id 的唯一集合，这里只保证行 key 唯一。
            .distinctBy { it.id }
            .map { plugin ->
                PluginSettingsRow(
                    pluginId = plugin.id,
                    title = plugin.name,
                    subtitle = plugin.id,
                    category = categoryOf(plugin.kind),
                    isSynthetic = false,
                    // 没注册设置区 → null：行仍可点开，展开区里给「无可配置项」的兜底提示。
                    presentation = availableSections[plugin.id],
                )
            }

        val selected = pluginRows.filter { it.category == category }
        val hasGrant = category == PluginSettingsCategory.GENERAL &&
            // 真实插件优先：真的有插件叫 tool.grant 时，保留条目让位（见 TOOL_GRANT_ID 注释）。
            pluginRows.none { it.pluginId == TOOL_GRANT_ID }

        val ordered = if (hasGrant) listOf(grantRow) + selected else selected
        return ordered
            .filter { matches(it.pluginId, it.title, query) }
            .sortedWith(compareBy({ !it.isSynthetic }, { it.pluginId }))
    }

    /** Runtime alone drives switches; disabledIds is retained for callers but never masks a running plugin. */
    @Suppress("UNUSED_PARAMETER")
    fun deriveSwitchStates(
        rows: List<PluginSettingsRow>,
        hostLoadedIds: Set<String>,
        disabledIds: Set<String>?,
    ): Map<String, Boolean> = rows
        .filterNot { it.isSynthetic }
        .associate { row -> row.pluginId to (row.pluginId in hostLoadedIds) }
}

/**
 * 点一行会发生什么——由该行设置区的 [PluginSettingsPresentation] 决定。
 *
 * 这是「呈现方式 → 页面行为」的**唯一**映射点（纯逻辑、JVM 可测）：
 * Composable 只读 [PluginSettingsRow.action]，不再自己判断走哪条路径。
 */
enum class PluginRowAction {
    /**
     * 在行内**展开 / 收起**设置区（[PluginSettingsPresentation.INLINE]）。
     *
     * 未注册设置区的行也走这里：展开区里显示「此插件无可配置项」而不是什么都不画——
     * 注册表变化但行未及时刷新时的兜底（见 [PluginSettingsRow.presentation]）。
     */
    TOGGLE_INLINE,

    /**
     * 打开**页内全屏浮层**渲染整个设置区（[PluginSettingsPresentation.FULL_PAGE]）。
     *
     * 为什么不能内联：这类设置区自带 `Scaffold` / 滚动容器，塞进外层
     * `LazyColumn` 的 item 里会拿到**无界最大高度约束** → 运行期抛异常
     * （判定标准见 [PluginSettingsPresentation] 的 KDoc）。
     */
    OPEN_FULL_PAGE,
}

/**
 * 列表里的一行。
 *
 * [isSynthetic] 区分「保留条目」与「真实插件」：合成条目没有 [PluginHost] 记录，
 * 因此**没有开关**（开关映射 [PluginSettingsBoard.deriveSwitchStates] 会跳过它）。
 *
 * ## 「点这一行会怎样」全部由 [action] 表达
 *
 * [action] 是从 [presentation] 推导出来的纯属性，**不是**第二个可写字段——
 * 于是「行说自己有设置区」与「行知道该走哪条渲染路径」在结构上不可能不一致。
 */
data class PluginSettingsRow(
    /** 插件 id；合成条目为 [PluginSettingsBoard.TOOL_GRANT_ID]。 */
    val pluginId: String,
    /** 主标题（插件展示名）。 */
    val title: String,
    /** 副标题（真实插件显示 id；合成条目显示用途说明）。 */
    val subtitle: String,
    /** 归属 Tab。 */
    val category: PluginSettingsCategory,
    /** true = 保留条目（工具授权），不是 [PluginHost] 里的插件。 */
    val isSynthetic: Boolean,
    /**
     * 该行**已注册设置区**的呈现方式；null = 没注册设置区
     * （`PluginSettingsSections.forPlugin(pluginId) == null`）。
     *
     * 未注册时行仍可点开：展开区里显示「此插件无可配置项」而不是留白，
     * 否则用户会以为「点了没反应」——这条提示是给「注册表变化但行未及时刷新」的兜底。
     */
    val presentation: PluginSettingsPresentation?,
) {
    /** 是否已注册设置区。由 [presentation] 推导，两者结构上不可能不一致。 */
    val hasSection: Boolean
        get() = presentation != null

    /**
     * 点这一行会发生什么（映射规则见 [PluginRowAction]）。
     *
     * 穷尽 `when`：将来给 [PluginSettingsPresentation] 加第三个取值，
     * 这里会**编译不过**，逼实现方明确它在页面上的行为，而不是悄悄落到某个分支。
     */
    val action: PluginRowAction
        get() = when (presentation) {
            PluginSettingsPresentation.FULL_PAGE -> PluginRowAction.OPEN_FULL_PAGE
            // INLINE → 内联展开；null（未注册）→ 也展开，让「无可配置项」的兜底提示可达。
            PluginSettingsPresentation.INLINE, null -> PluginRowAction.TOGGLE_INLINE
        }

    /**
     * 该行此刻是否要**内联**渲染设置区。
     *
     * 这是「FULL_PAGE 绝不内联」的**最后一道防线**（纯逻辑、JVM 可测）：即便某行在
     * FULL_PAGE 状态下意外拿到了 `expanded = true`（例如注册表在页面停留期间被换掉），
     * 也不会把整页设置区（自带 Scaffold / 滚动容器）塞进外层 `LazyColumn` 的 item 里
     * ——那正是本契约要修的崩溃。
     */
    fun rendersInline(expanded: Boolean): Boolean =
        expanded && action == PluginRowAction.TOGGLE_INLINE
}
