package com.yunian.ai.feature.wechat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yunian.ai.feature.wechat.channel.WeChatChannelPlugin
import com.yunian.ai.uicommon.plugin.PluginSettingsCategory
import com.yunian.ai.uicommon.plugin.PluginSettingsPresentation
import com.yunian.ai.uicommon.plugin.PluginSettingsSection

/**
 * `channel.wechat` 的**自贡献设置区**（Cordis 式「插件自贡献 UI」）。
 *
 * 统一设置页（`feature:settings` 的 `PluginSettingsScreen`）只从
 * [com.yunian.ai.uicommon.plugin.PluginSettingsSections] 这张表里读设置区，
 * **从不 import 本模块**；本模块也只依赖 `core:ui-common` 的契约，不反向依赖设置模块。
 * 因此本类就是「微信通道」与「插件设置页」之间**唯一**的接触点。
 *
 * ## 注册与撤销
 *
 * 由 [WeChatChannelPlugin.setup] 注册、由该插件的 `ctx.effect` 撤销——插件一停用，
 * 设置区随之从页面上消失（生命周期语义见 [PluginSettingsSection] 的 KDoc）。
 *
 * [pluginId] 取 [WeChatChannelPlugin.ID]（`channel.wechat`）而**不是**通道键
 * `wechat`：两套 id 空间不同（插件 id vs `ChannelKeys`），写错会让
 * `PluginSettingsSections.forPlugin()` 永远查不到、设置区**静默不显示**。
 * 这里刻意引用 `ID` 常量而不是再写一遍字面量，杜绝两处漂移。
 *
 * ## 呈现方式：FULL_PAGE（整页）
 *
 * [presentation] 声明为 [PluginSettingsPresentation.FULL_PAGE]：本设置区渲染的
 * [WeChatSettingsScreen] 是**整页组件**，内部用 `GlassPageScaffold`
 * （背景层 + `Scaffold` + `Column(verticalScroll)`）。
 *
 * **这条声明不是风格选择，而是崩溃防线**：设置页曾经无条件把 `Content()` 放进
 * 外层 `LazyColumn` 的 item 里，内层滚动容器会拿到**无界最大高度约束**
 * → 运行期抛异常。声明 FULL_PAGE 之后设置页改走**页内全屏浮层**，那条路径不复存在。
 * 判定标准见 [PluginSettingsPresentation] 的 KDoc。
 *
 * ## 内部导航：为什么 `onNavigateBack` 是空 lambda
 *
 * 契约的 [PluginSettingsSection.Content] 是**无参**的：设置页没有向设置区传递
 * 「返回上一级」的能力。而既有 [WeChatSettingsScreen] / [WeChatBindScreen] 的签名
 * **不得改动**（`app/MainNavGraph.kt` 仍在按原签名调用它们），所以这里只能从
 * 三个方案里选：
 *
 * - (a) 传空 lambda `{}`；
 * - (b) 传 `{}` 并**隐藏**原页面的返回按钮；
 * - (c) 其他。
 *
 * **选 (a)**，理由：
 *
 * 1. **做不到 (b)**：两个 Screen 的顶栏都是 `GlassTopBar(title = …, onBack = onNavigateBack)`，
 *    返回按钮由**非空 lambda** 决定是否绘制（`GlassTopBar.kt` 的 `if (onBack != null)`）。
 *    要隐藏它必须改 Screen 的签名（把 `onNavigateBack` 变成可空）或改 Screen 内部，
 *    两条路都撞上「不得改签名」这条硬约束；
 * 2. **(a) 在本场景不是「退化为无操作」而是「正确」**：本设置区的可见性由**设置页的浮层
 *    状态**决定（`PluginSettingsScreen` 的 `overlayPluginId`），关掉它的是
 *    系统返回键（页面级 `BackHandler`）；设置区内部那个页面级返回按钮
 *    **本来就没有可返回的层级**。留一个点了没反应的返回按钮不好看，但比「点了把整个
 *    插件设置页弹掉」正确得多（后者会让用户以为设置没保存）；
 * 3. 绑定子页面的返回**不受影响**：见下面 [Content] 里的说明。
 *
 * 契约层若要真正解决这一点，需要让 `Content()` 能表达「返回上一级」的能力
 * （例如再给一个 `onNavigateBack` 参数）——那是契约的**另一次**扩展。
 * T4 只补「呈现方式」这一个标志，不顺手扩参数：扩参数会同时撞上
 * 「不得改 Screen 签名」与 `app/MainNavGraph.kt` 的调用点。
 */
object WeChatSettingsSection : PluginSettingsSection {

    /** 逐字等于 [WeChatChannelPlugin.ID]（`channel.wechat`），不是通道键 `wechat`。 */
    override val pluginId: String = WeChatChannelPlugin.ID

    /** 消息通道类插件，挂到设置页的「消息通道」段。 */
    override val category: PluginSettingsCategory = PluginSettingsCategory.CHANNEL

    /**
     * **整页**呈现（判定依据见本类 KDoc）：[WeChatSettingsScreen] 自带
     * `GlassPageScaffold`（背景层 + `Scaffold` + `Column(verticalScroll)`），
     * 内联进设置页的 `LazyColumn` item 会因无界高度约束在运行期抛异常。
     */
    override val presentation: PluginSettingsPresentation = PluginSettingsPresentation.FULL_PAGE

    /**
     * 渲染**既有**的 [WeChatSettingsScreen]，不复制它的实现——于是微信设置只有一份代码。
     *
     * ## 绑定流程移入设置区内部
     *
     * 用户裁定「微信扫码绑定这类功能也可以是插件」，所以扫码绑定页不再依赖 `app` 的路由：
     * 本设置区用自己的 Compose 状态 [showBind] 承接「设置 ⇄ 绑定」这一层子导航，
     * 与设置页（`PluginSettingsScreen` 用 `showToolGrants` 状态承载工具授权子页）
     * 同构。
     *
     * 两条返回路径都落在设置区内部：
     * - [WeChatBindScreen] 的**顶栏返回按钮**走它自己的 `onNavigateBack` → 收起绑定页；
     * - 扫码**登录成功**时 [WeChatBindScreen] 会调用同一个 `onNavigateBack`（它内部监听
     *   `WeChatEvent.LoginSuccess`），于是自动回到设置页——**不需要**外部路由介入。
     *
     * [WeChatSettingsScreen] 的 `onNavigateBack` 按上面的裁定传空 lambda；
     * 它的 `onBindClick` 打开本设置区内部的绑定页（`app/MainNavGraph.kt` 的调用点
     * 不受影响：那是另一条独立入口，两者共用同一份 Screen 代码）。
     *
     * ## 状态作用域
     *
     * [showBind] 用 `remember` 而非 `rememberSaveable`：浮层关闭时设置页不再
     * 组合本设置区（`PluginSettingsScreen` 的 `AnimatedVisibility` 内层有
     * `if (overlayId != null)` 守卫），本 Composition 随之整个销毁，下次打开自然回到
     * 设置页首页——这正是想要的语义，也避免把「绑定页开着」这个瞬时状态持久化到配置变更之后。
     */
    @Composable
    override fun Content() {
        var showBind by remember { mutableStateOf(false) }
        if (showBind) {
            WeChatBindScreen(onNavigateBack = { showBind = false })
        } else {
            WeChatSettingsScreen(
                onNavigateBack = {},
                onBindClick = { showBind = true },
            )
        }
    }
}
