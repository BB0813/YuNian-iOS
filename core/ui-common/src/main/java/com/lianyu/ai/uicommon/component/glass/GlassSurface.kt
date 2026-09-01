/*
 * Adapted from RiseDiary (https://github.com/sky-shunfengjun/RiseDiary), Apache-2.0.
 */
package com.lianyu.ai.uicommon.component.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop

/** 玻璃容器色：深色 #20242B，浅色 0xF7FFFFFF（与 RiseDiary RiseCard 一致） */
val GlassSurfaceColor: Color
    @Composable get() =
        if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF20242B) else Color(0xF7FFFFFF)

/**
 * 液态玻璃表面修饰符（轻量版）。
 *
 * 目前仅保留半透明纯色填充，不再调用 drawBackdrop，避免在低端 GPU / 多组件场景下
 * 触发 Skia pinImages SIGSEGV。真实的液态玻璃仅用于底部导航栏 LiquidBottomTabs。
 *
 * @param backdrop 已废弃，仅保留参数兼容性；传入任意值都按纯色处理
 * @param surfaceColor 半透明填充色；为 null 时取当前主题玻璃色
 */
@Composable
fun Modifier.drawGlass(
    backdrop: Backdrop?,
    shape: Shape = RoundedCornerShape(24.dp),
    surfaceColor: Color? = null,
    isDark: Boolean? = null,
): Modifier {
    val isDarkResolved = isDark ?: (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
    val glassColor = surfaceColor
        ?: runCatching { GlassSurfaceColor }.getOrNull()
        ?: if (isDarkResolved) Color(0xFF20242B) else Color(0xF7FFFFFF)

    return background(glassColor, shape)
}
