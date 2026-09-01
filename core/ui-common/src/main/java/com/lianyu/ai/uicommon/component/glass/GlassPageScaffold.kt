/*
 * Adapted from RiseDiary (https://github.com/sky-shunfengjun/RiseDiary), Apache-2.0.
 */
package com.lianyu.ai.uicommon.component.glass

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.lianyu.ai.uicommon.component.WindowMainBackground
import com.lianyu.ai.uicommon.component.getMainBackgroundKey
import com.lianyu.ai.uicommon.component.resolveBackgroundPalette
import com.lianyu.ai.uicommon.theme.WeChatDarkBackground
import com.lianyu.ai.uicommon.theme.WeChatLightBackground

/**
 * 次级页面玻璃骨架 —— RiseDiary SecondaryPageScaffold 移植。
 *
 * - 页面级 backdrop 捕获块绘制 Compose 渐变背景（与主界面同 key 同 Brush），
 *   页面内容挂 layerBackdrop 被采样，页面内玻璃组件（GlassCard/GlassButton）磨砂生效；
 * - 注意：捕获块不绘制 window.decorView.background（framework drawable 进捕获层是闪退根因）；
 * - Scaffold 容器透明，背景贯穿状态栏/顶栏/内容区（沉浸式）；
 * - topBar / bottomBar / floatingActionButton 置于 Scaffold 捕获区之外，
 *   避免玻璃控件自采样（与 RiseDiary 结构一致）。
 */
@Composable
fun GlassPageScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable (() -> Unit)? = null,
    bottomBar: @Composable (() -> Unit)? = null,
    snackbarHost: @Composable (() -> Unit)? = null,
    floatingActionButton: @Composable (() -> Unit)? = null,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit
) {
    val context = LocalContext.current
    val isDark = remember { WindowMainBackground.resolveIsDarkTheme(context) }
    val bgBrush = remember {
        resolveBackgroundPalette(getMainBackgroundKey(context), isDark).second
    }
    val backdrop = rememberLayerBackdrop {
        if (bgBrush != null) {
            drawRect(brush = bgBrush)
        } else {
            drawRect(if (isDark) WeChatDarkBackground else WeChatLightBackground)
        }
        drawContent()
    }
    ProvidePageBackdrop(backdrop) {
        Box(modifier = modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { snackbarHost?.invoke() },
                content = content
            )
            topBar?.let { Box(modifier = Modifier.align(Alignment.TopCenter)) { it.invoke() } }
            bottomBar?.let { Box(modifier = Modifier.align(Alignment.BottomCenter)) { it.invoke() } }
            floatingActionButton?.let {
                Box(modifier = Modifier.align(Alignment.BottomEnd)) { it.invoke() }
            }
        }
    }
}