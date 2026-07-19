package com.lianyu.ai.uicommon.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.uicommon.theme.AppTheme
import com.lianyu.ai.uicommon.theme.PinkPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 产品级 Toast 事件。
 *
 * 架构约定：
 * - 运营/配置/网络等**非对话**提示走 Toast，不进入聊天消息库。
 * - 聊天模块通过 [ChatUiEvent] 转发到此通道，或直接 [LianYuToast.show]。
 */
data class LianYuToastEvent(
    val message: String,
    val type: Type = Type.Info,
    val durationMs: Long = 2_800L,
    val id: Long = nextId()
) {
    enum class Type { Info, Success, Warning, Error }

    companion object {
        private val seq = AtomicLong(0)
        private fun nextId(): Long = seq.incrementAndGet()
    }
}

/**
 * 全局 Toast 控制器（进程内单例）。
 * UI 层用 [LianYuToastHost] 订阅；业务层只发事件，不直接操作 View。
 */
@Stable
object LianYuToast {
    private val _events = MutableSharedFlow<LianYuToastEvent>(
        extraBufferCapacity = 8,
        replay = 0
    )
    val events: SharedFlow<LianYuToastEvent> = _events.asSharedFlow()

    fun show(message: String, type: LianYuToastEvent.Type = LianYuToastEvent.Type.Info, durationMs: Long = 2_800L) {
        val text = message.trim()
        if (text.isEmpty()) return
        _events.tryEmit(LianYuToastEvent(message = text, type = type, durationMs = durationMs))
    }

    fun info(message: String) = show(message, LianYuToastEvent.Type.Info)
    fun success(message: String) = show(message, LianYuToastEvent.Type.Success)
    fun warning(message: String) = show(message, LianYuToastEvent.Type.Warning)
    fun error(message: String) = show(message, LianYuToastEvent.Type.Error, durationMs = 3_600L)
}

/**
 * 产品色 Toast 宿主：不透明柔和卡片 + 主题色描边，贴合恋语 pastel 配色。
 * 放在页面根 Box 顶部即可；可与 Scaffold 并存。
 */
@Composable
fun LianYuToastHost(
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopCenter
) {
    var current by remember { mutableStateOf<LianYuToastEvent?>(null) }

    LaunchedEffect(Unit) {
        LianYuToast.events.collect { event ->
            current = event
            delay(event.durationMs)
            if (current?.id == event.id) {
                current = null
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        LianYuToastCard(
            event = current,
            alignment = alignment
        )
    }
}

@Composable
fun BoxScope.LianYuToastOverlay(
    alignment: Alignment = Alignment.TopCenter
) {
    var current by remember { mutableStateOf<LianYuToastEvent?>(null) }

    LaunchedEffect(Unit) {
        LianYuToast.events.collect { event ->
            current = event
            delay(event.durationMs)
            if (current?.id == event.id) {
                current = null
            }
        }
    }

    LianYuToastCard(event = current, alignment = alignment)
}

@Composable
private fun BoxScope.LianYuToastCard(
    event: LianYuToastEvent?,
    alignment: Alignment
) {
    val colors = AppTheme.colors
    val shape = RoundedCornerShape(16.dp)

    AnimatedVisibility(
        visible = event != null,
        modifier = Modifier
            .align(alignment)
            .padding(horizontal = 20.dp, vertical = 56.dp),
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 2 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(180)) { -it / 3 }
    ) {
        val toast = event ?: return@AnimatedVisibility
        val accent = when (toast.type) {
            LianYuToastEvent.Type.Info -> PinkPrimary
            LianYuToastEvent.Type.Success -> Color(0xFF10A37F)
            LianYuToastEvent.Type.Warning -> Color(0xFFFFA726)
            LianYuToastEvent.Type.Error -> colors.error
        }
        // 不透明表面，避免聊天背景透出；描边用产品主色
        val bg = colors.surface
        val fg = colors.onSurface

        Box(
            modifier = Modifier
                .widthIn(min = 160.dp, max = 360.dp)
                .shadow(10.dp, shape, clip = false)
                .clip(shape)
                .background(bg)
                .border(1.dp, accent.copy(alpha = 0.35f), shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = toast.message,
                color = fg,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}
