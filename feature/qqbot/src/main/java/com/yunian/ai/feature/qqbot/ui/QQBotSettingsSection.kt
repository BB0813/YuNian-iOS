package com.yunian.ai.feature.qqbot.ui

import androidx.compose.runtime.Composable
import com.yunian.ai.feature.qqbot.channel.QQBotChannelPlugin
import com.yunian.ai.uicommon.plugin.PluginSettingsCategory
import com.yunian.ai.uicommon.plugin.PluginSettingsPresentation
import com.yunian.ai.uicommon.plugin.PluginSettingsSection

/**
 * `channel.qqbot` 的**自贡献设置区**（Cordis 式「插件自贡献 UI」）。
 *
 * 统一设置页（`feature:settings` 的 `PluginSettingsScreen`）只从
 * [com.yunian.ai.uicommon.plugin.PluginSettingsSections] 这张表里读设置区，
 * **从不 import 本模块**；本模块也只依赖 `core:ui-common` 的契约，不反向依赖设置模块。
 * 因此本类就是「QQ 机器人通道」与「插件设置页」之间**唯一**的接触点。
 *
 * ## 注册与撤销
 *
 * 由 [QQBotChannelPlugin.setup] 注册、由该插件的 `ctx.effect` 撤销——插件一停用，
 * 设置区随之从页面上消失（生命周期语义见 [PluginSettingsSection] 的 KDoc）。
 *
 * [pluginId] 取 [QQBotChannelPlugin.ID]（`channel.qqbot`）而**不是**通道键
 * `qqbot`：两套 id 空间不同（插件 id vs `ChannelKeys`），写错会让
 * `PluginSettingsSections.forPlugin()` 永远查不到、设置区**静默不显示**。
 * 这里刻意引用 `ID` 常量而不是再写一遍字面量，杜绝两处漂移。
 *
 * ## 呈现方式：FULL_PAGE（整页）
 *
 * [presentation] 声明为 [PluginSettingsPresentation.FULL_PAGE]：本设置区渲染的
 * [QQBotSettingsScreen] 是**整页组件**，内部用 `GlassPageScaffold`
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
 * 「返回上一级」的能力。而既有 [QQBotSettingsScreen] 的签名**不得改动**
 * （`app/MainNavGraph.kt` 仍在按原签名调用它），所以这里只能从三个方案里选：
 *
 * - (a) 传空 lambda `{}`；
 * - (b) 传 `{}` 并**隐藏**原页面的返回按钮；
 * - (c) 其他。
 *
 * **选 (a)**，理由：
 *
 * 1. **做不到 (b)**：[QQBotSettingsScreen] 的顶栏是
 *    `GlassTopBar(title = …, onBack = onNavigateBack)`，返回按钮由**非空 lambda** 决定
 *    是否绘制（`GlassTopBar.kt` 的 `if (onBack != null)`）。要隐藏它必须改 Screen 的
 *    签名（把 `onNavigateBack` 变成可空）或改 Screen 内部，两条路都撞上「不得改签名」；
 * 2. **(a) 在本场景是正确语义**：本设置区的可见性由**设置页的浮层状态**决定
 *    （`PluginSettingsScreen` 的 `overlayPluginId`），关掉它的是系统返回键
 *    （页面级 `BackHandler`）；设置区内部那个页面级返回按钮**本来就没有可返回的层级**；
 * 3. **QQ 不需要第二个子页面**：与微信不同，QQ 的绑定流程（扫码 / 手动填 AppID）
 *    本来就在 [QQBotSettingsScreen] **内部**（`BindMode` 的 FilterChip 切换 +
 *    `QQBotQrBindSection` / `QQBotBindForm`），没有独立的 `QQBotBindScreen`。
 *    因此本设置区**不需要**任何内部子导航状态，比微信侧更简单。
 *
 * 契约层若要真正解决这一点，需要让 `Content()` 能表达「返回上一级」的能力——
 * 那是契约的**另一次**扩展。T4 只补「呈现方式」这一个标志，不顺手扩参数：
 * 扩参数会同时撞上「不得改 Screen 签名」与 `app/MainNavGraph.kt` 的调用点。
 */
object QQBotSettingsSection : PluginSettingsSection {

    /** 逐字等于 [QQBotChannelPlugin.ID]（`channel.qqbot`），不是通道键 `qqbot`。 */
    override val pluginId: String = QQBotChannelPlugin.ID

    /** 消息通道类插件，挂到设置页的「消息通道」段。 */
    override val category: PluginSettingsCategory = PluginSettingsCategory.CHANNEL

    /**
     * **整页**呈现（判定依据见本类 KDoc）：[QQBotSettingsScreen] 自带
     * `GlassPageScaffold`（背景层 + `Scaffold` + `Column(verticalScroll)`），
     * 内联进设置页的 `LazyColumn` item 会因无界高度约束在运行期抛异常。
     */
    override val presentation: PluginSettingsPresentation = PluginSettingsPresentation.FULL_PAGE

    /**
     * 渲染**既有**的 [QQBotSettingsScreen]，不复制它的实现——于是 QQ 机器人设置
     * 只有一份代码。
     *
     * `onNavigateBack` 传空 lambda（裁定与理由见本类 KDoc）：绑定流程本来就在该 Screen
     * 内部，设置区不需要承接任何子页面导航。
     */
    @Composable
    override fun Content() {
        QQBotSettingsScreen(onNavigateBack = {})
    }
}
