package com.lianyu.ai.uicommon.picker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 选择器顶部栏 — 背景延伸到状态栏后方，内容自动避让系统栏。
 *
 * 三个子页面（Grid / AlbumSheet / Preview）共用此组件，
 * 各自只需传入背景 modifier 和内容，不再各自操心 WindowInsets。
 *
 * @param modifier        背景 Modifier（调用方通过此参数设置 background 等）
 * @param contentPadding  内容区内边距（默认水平 4dp 垂直 8dp）
 * @param content         顶部栏内容（RowScope）
 */
@Composable
internal fun PickerTopBar(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = PickerTopBarDefaults.Horizontal, vertical = PickerTopBarDefaults.Vertical),
    content: @Composable RowScope.() -> Unit
) {
    Box(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/** PickerTopBar 默认间距常量 */
internal object PickerTopBarDefaults {
    val Horizontal = 4.dp
    val Vertical = 8.dp
}
