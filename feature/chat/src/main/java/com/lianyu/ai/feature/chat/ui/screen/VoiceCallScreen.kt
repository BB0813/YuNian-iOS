package com.lianyu.ai.feature.chat.ui.screen

import android.app.Application
import android.media.MediaPlayer
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatViewModel
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatViewModelFactory
import com.lianyu.ai.network.tts.TtsProvider
import com.lianyu.ai.network.tts.TtsService
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 语音通话状态
 */
enum class CallState {
    DIALING,      // 拨号中
    CONNECTING,   // 连接中
    CONNECTED,    // 已接通
    ENDED         // 已结束
}

/**
 * 语音通话页面 - 模拟语音通话，接入TTS
 * 带有流动渐变背景效果
 */
@Composable
fun VoiceCallScreen(
    companionId: Long,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModelFactory(context.applicationContext as Application, companionId)
    )
    val companionData by viewModel.companionData.collectAsState()

    var callState by remember { mutableStateOf(CallState.DIALING) }
    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(true) }
    var callDuration by remember { mutableStateOf(0) }
    var currentSpeakingText by remember { mutableStateOf("") }
    var isAiSpeaking by remember { mutableStateOf(false) }

    val ttsService = remember { TtsService.getInstance(context) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // TTS settings - get from app settings
    var ttsEnabled by remember { mutableStateOf(false) }
    var ttsProvider by remember { mutableStateOf(TtsProvider.ANDROID) }

    // Call control: requires user action to connect
    fun acceptCall() {
        if (callState == CallState.DIALING || callState == CallState.CONNECTING) {
            callState = CallState.CONNECTED
            ttsEnabled = true
            ttsProvider = TtsProvider.ANDROID
            ttsService.setProvider(ttsProvider)
        }
    }

    fun rejectCall() {
        mediaPlayer?.release()
        callState = CallState.ENDED
        onNavigateBack()
    }

    // Timer
    LaunchedEffect(callState) {
        if (callState == CallState.CONNECTED) {
            while (callState == CallState.CONNECTED) {
                delay(1000)
                callDuration++
            }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Flowing gradient background
        FlowingGradientBackground()

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = {
                    mediaPlayer?.release()
                    callState = CallState.ENDED
                    onNavigateBack()
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.15f))

            // Call status
            Text(
                text = when (callState) {
                    CallState.DIALING -> "正在拨号..."
                    CallState.CONNECTING -> "正在连接..."
                    CallState.CONNECTED -> formatDuration(callDuration)
                    CallState.ENDED -> "通话结束"
                },
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Connection indicator
            when (callState) {
                CallState.DIALING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(3) { index ->
                            PulsingDot(delayMillis = index * 200)
                        }
                    }
                }
                CallState.CONNECTING -> {
                    PulsingDot()
                }
                CallState.CONNECTED -> {
                    Text(
                        text = if (isAiSpeaking) "对方正在说话..." else "已连接",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                CallState.ENDED -> {}
            }

            Spacer(modifier = Modifier.weight(0.2f))

            // Avatar with glow effect
            Box(
                modifier = Modifier.size(140.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outer glow rings
                if (callState == CallState.CONNECTED) {
                    PulsingGlowRing(
                        modifier = Modifier.size(180.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    )
                    PulsingGlowRing(
                        modifier = Modifier.size(220.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        delayMillis = 500
                    )
                }

                // Avatar
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (companionData?.avatarUrl != null) {
                        AsyncImage(
                            model = companionData?.avatarUrl,
                            contentDescription = companionData?.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = companionData?.name?.firstOrNull()?.toString() ?: "?",
                            fontSize = 48.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Name
            Text(
                text = companionData?.name ?: "",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // AI speaking text
            if (isAiSpeaking && currentSpeakingText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = currentSpeakingText,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }

            Spacer(modifier = Modifier.weight(0.3f))

            // Control buttons
            if (callState == CallState.DIALING || callState == CallState.CONNECTING) {
                // Accept/Reject buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Reject button
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF3B30))
                            .clickable { rejectCall() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CallEnd,
                            contentDescription = "挂断",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Accept button
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34C759))
                            .clickable {
                                callState = CallState.CONNECTING
                                ttsEnabled = true
                                ttsProvider = TtsProvider.ANDROID
                                ttsService.setProvider(ttsProvider)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Call,
                            contentDescription = "接听",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "点击接听开始通话",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )

                // Auto-connect after user accepts
                LaunchedEffect(callState) {
                    if (callState == CallState.CONNECTING) {
                        delay(800)
                        callState = CallState.CONNECTED

                        val greeting = "喂，你好呀~"
                        currentSpeakingText = greeting
                        isAiSpeaking = true

                        val audioPath = ttsService.synthesize(greeting)
                        if (audioPath != null) {
                            try {
                                mediaPlayer?.release()
                                mediaPlayer = MediaPlayer().apply {
                                    setDataSource(audioPath)
                                    prepare()
                                    start()
                                    setOnCompletionListener {
                                        isAiSpeaking = false
                                        currentSpeakingText = ""
                                    }
                                }
                            } catch (e: Exception) {
                                isAiSpeaking = false
                            }
                        } else {
                            isAiSpeaking = false
                        }
                    }
                }
            } else {
                // Connected state controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallControlButton(
                        icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        label = if (isMuted) "麦克风已关" else "麦克风已开",
                        isActive = !isMuted,
                        onClick = { isMuted = !isMuted }
                    )

                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF3B30))
                            .clickable {
                                mediaPlayer?.release()
                                callState = CallState.ENDED
                                onNavigateBack()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CallEnd,
                            contentDescription = "挂断",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    CallControlButton(
                        icon = Icons.Filled.VolumeUp,
                        label = "默认设备",
                        isActive = isSpeakerOn,
                        onClick = { isSpeakerOn = !isSpeakerOn }
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
private fun FlowingGradientBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "flow")

    val offset1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flow1"
    )

    val offset2 by infiniteTransition.animateFloat(
        initialValue = PI.toFloat(),
        targetValue = 3f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(25000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flow2"
    )

    val offset3 by infiniteTransition.animateFloat(
        initialValue = PI.toFloat() / 2,
        targetValue = 2.5f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flow3"
    )

    val background = MaterialTheme.colorScheme.background
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val onSurface = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Base gradient - theme aware
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    background,
                    background,
                    surfaceVariant
                )
            )
        )

        // Flowing blob 1 - primary
        val blob1X = size.width * 0.3f + cos(offset1) * size.width * 0.2f
        val blob1Y = size.height * 0.3f + sin(offset1 * 0.7f) * size.height * 0.15f
        drawFlowingBlob(
            centerX = blob1X,
            centerY = blob1Y,
            radius = size.width * 0.45f,
            color = primary.copy(alpha = 0.25f)
        )

        // Flowing blob 2 - secondary
        val blob2X = size.width * 0.7f + cos(offset2 * 0.8f) * size.width * 0.18f
        val blob2Y = size.height * 0.5f + sin(offset2) * size.height * 0.12f
        drawFlowingBlob(
            centerX = blob2X,
            centerY = blob2Y,
            radius = size.width * 0.4f,
            color = secondary.copy(alpha = 0.2f)
        )

        // Flowing blob 3 - tertiary
        val blob3X = size.width * 0.5f + cos(offset3 * 0.6f) * size.width * 0.15f
        val blob3Y = size.height * 0.7f + sin(offset3 * 0.9f) * size.height * 0.1f
        drawFlowingBlob(
            centerX = blob3X,
            centerY = blob3Y,
            radius = size.width * 0.5f,
            color = tertiary.copy(alpha = 0.15f)
        )

        // Subtle noise texture overlay
        drawRect(
            color = onSurface.copy(alpha = 0.03f)
        )
    }
}

private fun DrawScope.drawFlowingBlob(
    centerX: Float,
    centerY: Float,
    radius: Float,
    color: Color
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color,
                color.copy(alpha = color.alpha * 0.5f),
                Color.Transparent
            ),
            center = Offset(centerX, centerY),
            radius = radius
        ),
        radius = radius,
        center = Offset(centerX, centerY)
    )
}

@Composable
private fun PulsingGlowRing(
    modifier: Modifier = Modifier,
    color: Color,
    delayMillis: Int = 0
) {
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, delayMillis = delayMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, delayMillis = delayMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun PulsingDot(delayMillis: Int = 0) {
    val infiniteTransition = rememberInfiniteTransition(label = "dot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, delayMillis = delayMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
    )
}

@Composable
private fun CallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

private fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%02d:%02d", minutes, secs)
    }
}
