/*
 * Adapted from RiseDiary (https://github.com/sky-shunfengjun/RiseDiary), Apache-2.0.
 */
package com.lianyu.ai.uicommon.component.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import androidx.compose.ui.graphics.luminance

enum class GlassCardStyle {
    Standard,
    Emphasis,
    Metric,
}

/**
 * 液态玻璃卡片 —— RiseDiary RiseCard 的玻璃化移植。
 * 使用纯色 + 低透明度模拟磨砂玻璃效果，无 backdrop 采样。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    style: GlassCardStyle = GlassCardStyle.Standard,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    backdrop: Backdrop? = LocalPageBackdrop.current,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val containerColor = when (style) {
        GlassCardStyle.Standard ->
            if (isDark) Color(0xFF20242B) else Color(0xF7FFFFFF)
        GlassCardStyle.Emphasis ->
            if (isDark) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        GlassCardStyle.Metric ->
            if (isDark) Color.White.copy(alpha = 0.055f)
            else Color(0xFFF4F7FB)
    }
    val border = if (isDark) {
        BorderStroke(0.5.dp, Color.White.copy(alpha = 0.07f))
    } else {
        null
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "glassCardScale"
    )
    val cardModifier = modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    val shape = RoundedCornerShape(24.dp)

    if (onClick == null) {
        androidx.compose.material3.Card(
            modifier = cardModifier,
            shape = shape,
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = containerColor),
            border = border,
            elevation = androidx.compose.material3.CardDefaults.cardElevation(
                defaultElevation = if (isDark || style == GlassCardStyle.Metric) 0.dp else 1.dp
            ),
            content = content
        )
    } else {
        androidx.compose.material3.Card(
            onClick = onClick,
            modifier = cardModifier,
            enabled = enabled,
            shape = shape,
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = containerColor),
            border = border,
            elevation = androidx.compose.material3.CardDefaults.cardElevation(
                defaultElevation = if (isDark || style == GlassCardStyle.Metric) 0.dp else 1.dp
            ),
            interactionSource = interactionSource,
            content = content
        )
    }
}