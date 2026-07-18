package com.lianyu.ai.uicommon.component

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 消息外框 + 长按菜单脚手架。
 *
 * [frame] 的 [menuExpanded] 可用于在菜单展开时同步选中正文等交互。
 */
@Composable
fun AppMessageScaffold(
    frame: @Composable (menuExpanded: Boolean, onLongClick: () -> Unit) -> Unit,
    menu: @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        frame(showMenu) { showMenu = true }
        menu(showMenu) { showMenu = false }
    }
}
