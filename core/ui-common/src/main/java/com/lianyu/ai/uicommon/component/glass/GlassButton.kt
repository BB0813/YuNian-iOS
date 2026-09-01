/*
 * Adapted from RiseDiary (https://github.com/sky-shunfengjun/RiseDiary) / AndroidLiquidGlass
 * by Kyant0 (https://github.com/Kyant0/AndroidLiquidGlass), Apache-2.0.
 */
package com.lianyu.ai.uicommon.component.glass

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousCapsule

/**
 * 液态玻璃胶囊按钮（轻量版）。
 *
 * 为避免多个 drawBackdrop 叠加触发 GPU 崩溃，当前版本仅保留半透明纯色填充 +
 * 点击反馈，不再调用 drawBackdrop。真实液态玻璃仅保留在底部导航栏。
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    backdrop: com.kyant.backdrop.Backdrop? = LocalPageBackdrop.current,
    modifier: Modifier = Modifier,
    isInteractive: Boolean = true,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    height: Dp = 48.dp,
    horizontalPadding: Dp = 16.dp,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    val backgroundModifier = if (surfaceColor.isSpecified || tint.isSpecified) {
        val finalColor = when {
            tint.isSpecified && surfaceColor.isSpecified -> surfaceColor.copy(alpha = surfaceColor.alpha)
            surfaceColor.isSpecified -> surfaceColor
            else -> tint.copy(alpha = 0.15f)
        }
        Modifier.background(finalColor, ContinuousCapsule)
    } else {
        Modifier
    }

    Row(
        modifier
            .then(backgroundModifier)
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(
                        enabled = enabled,
                        interactionSource = null,
                        indication = if (isInteractive) null else LocalIndication.current,
                        role = Role.Button,
                        onClick = onClick
                    )
                } else {
                    Modifier.combinedClickable(
                        enabled = enabled,
                        interactionSource = null,
                        indication = if (isInteractive) null else LocalIndication.current,
                        role = Role.Button,
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                }
            )
            .height(height)
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
