package com.yunian.ai.uicommon.plugin

import androidx.compose.runtime.Composable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [PluginSettingsPresentation] 的纯 JVM 单测：把契约的**安全默认**钉死。
 *
 * 为什么值得单独一个测试文件：这个默认值是**崩溃风险的分界线**——
 * 实现方忘记声明 `presentation` 时，契约给的是 [PluginSettingsPresentation.FULL_PAGE]
 * （整页浮层，不崩）而不是 [PluginSettingsPresentation.INLINE]（内联进列表项，
 * 自带滚动容器的设置区会因无界高度约束抛异常）。默认值一旦被改成 INLINE，
 * 本文件立刻变红。
 *
 * 纯 JVM（junit）：不触碰 `android.util.Log` / `org.json`（Android stub 空壳），
 * 也不进入 Compose 运行时——[PluginSettingsSection.Content] 只被**实现**，从不被调用。
 */
class PluginSettingsPresentationTest {

    /** 故意**不**声明 `presentation` 的实现方：用来观察契约给的默认值。 */
    private class DefaultedSection(
        override val pluginId: String,
    ) : PluginSettingsSection {
        override val category: PluginSettingsCategory = PluginSettingsCategory.GENERAL

        @Composable
        override fun Content() = Unit
    }

    /** 显式声明呈现方式的实现方。 */
    private class DeclaredSection(
        override val pluginId: String,
        override val presentation: PluginSettingsPresentation,
    ) : PluginSettingsSection {
        override val category: PluginSettingsCategory = PluginSettingsCategory.GENERAL

        @Composable
        override fun Content() = Unit
    }

    @Test
    fun `未声明呈现方式的实现方拿到 FULL_PAGE 安全默认`() {
        assertEquals(
            "默认必须落在安全的那一侧：错选 INLINE 会崩，错选 FULL_PAGE 只是多一层浮层",
            PluginSettingsPresentation.FULL_PAGE,
            DefaultedSection("plugin.unknown").presentation,
        )
    }

    @Test
    fun `默认值不是 INLINE`() {
        assertNotEquals(
            "默认值退化成 INLINE 就等于把「忘记声明」变成一次崩溃风险",
            PluginSettingsPresentation.INLINE,
            DefaultedSection("plugin.unknown").presentation,
        )
    }

    @Test
    fun `显式声明的呈现方式被原样保留`() {
        assertEquals(
            PluginSettingsPresentation.INLINE,
            DeclaredSection("plugin.inline", PluginSettingsPresentation.INLINE).presentation,
        )
        assertEquals(
            PluginSettingsPresentation.FULL_PAGE,
            DeclaredSection("plugin.full", PluginSettingsPresentation.FULL_PAGE).presentation,
        )
    }

    @Test
    fun `枚举只有 INLINE 与 FULL_PAGE 两个取值`() {
        // 钉住契约表面：新增第三个取值必须是一次**有意的**契约扩展——
        // 设置页对呈现方式的分支是穷尽的 when，加成员会先编译不过，
        // 这条断言则保证「加成员」这件事不会在运行期悄悄发生。
        assertEquals(
            listOf(PluginSettingsPresentation.INLINE, PluginSettingsPresentation.FULL_PAGE),
            PluginSettingsPresentation.entries.toList(),
        )
    }

    @Test
    fun `注册表按插件 id 原样带回呈现方式`() {
        PluginSettingsSections.clear()
        try {
            val inline = DeclaredSection("plugin.inline", PluginSettingsPresentation.INLINE)
            val defaulted = DefaultedSection("plugin.defaulted")
            PluginSettingsSections.register(inline)
            PluginSettingsSections.register(defaulted)

            assertSame(inline, PluginSettingsSections.forPlugin("plugin.inline"))
            assertEquals(
                PluginSettingsPresentation.INLINE,
                PluginSettingsSections.forPlugin("plugin.inline")?.presentation,
            )
            assertEquals(
                "注册表不得改写实现方的呈现方式（含默认值）",
                PluginSettingsPresentation.FULL_PAGE,
                PluginSettingsSections.forPlugin("plugin.defaulted")?.presentation,
            )
        } finally {
            PluginSettingsSections.clear()
        }
    }
}
