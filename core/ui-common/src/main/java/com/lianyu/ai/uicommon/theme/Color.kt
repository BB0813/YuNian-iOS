package com.lianyu.ai.uicommon.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// Pink Romance Theme - Dark Mode (Primary)
// ============================================================
// 层级（由深到浅）：Background → Surface(chrome) → Card(列表/菜单) → Elevated
// 深色主题必须保证 chrome / 卡片 / 页面底有清晰对比，避免“糊成一片”。

// Background
val WeChatDarkBackground = Color(0xFF141014)
val WeChatDarkSurface = Color(0xFF1E171B)
val WeChatDarkCard = Color(0xFF2A2126)
val WeChatDarkElevated = Color(0xFF342A30)
val WeChatDarkDivider = Color(0xFF46363E)

// Text
val WeChatDarkTextPrimary = Color(0xFFF7E9EE)
val WeChatDarkTextSecondary = Color(0xFFC4A9B2)
val WeChatDarkTextTertiary = Color(0xFF8F7680)

// Pink accent
val PinkPrimary = Color(0xFFF4A6B5)
val PinkLight = Color(0xFFF8C8D8)
val PinkDark = Color(0xFFD48494)
val PinkMuted = Color(0xFF6B4A52)
/** 深色主题选中/强调底：不透明，避免粉透底发脏 */
val PinkPrimaryContainerDark = Color(0xFF4A2E36)
val PinkOnPrimaryContainerDark = Color(0xFFFFD9E2)

// ============================================================
// Pink Romance Theme - Light Mode
// ============================================================

val WeChatLightBackground = Color(0xFFFFF5F7)
val WeChatLightSurface = Color(0xFFFFEEF2)
val WeChatLightCard = Color(0xFFFFFFFF)
val WeChatLightDivider = Color(0xFFF0D5DC)

val WeChatLightTextPrimary = Color(0xFF2D1F24)
val WeChatLightTextSecondary = Color(0xFF8A6B74)
val WeChatLightTextTertiary = Color(0xFFB89AA2)

// ============================================================
// Message Bubble Colors
// ============================================================

// 消息气泡必须不透明，避免聊天背景透出
// 自己气泡与 AI 气泡统一为天蓝色 #87CEFA
val BubbleSkyBlue = Color(0xFF87CEFA)
val AiBubbleDark = BubbleSkyBlue
val AiBubbleLight = BubbleSkyBlue
val AiBubbleBorderDark = Color(0xFF5BB8F0).copy(alpha = 0.35f)
val AiBubbleBorderLight = Color(0xFF4AA3D9).copy(alpha = 0.18f)
val BubbleOnPink = Color(0xFF1A2A33)

// ============================================================
// Navigation / Chrome
// ============================================================

val NavBackgroundDark = WeChatDarkSurface
val NavBackgroundLight = Color(0xFFFFF0F3)
val NavSelected = PinkPrimary
val NavUnselectedDark = WeChatDarkTextTertiary
val NavUnselectedLight = Color(0xFFB89AA2)

// 深色主题主界面预设背景（与浅色同 key，运行时按 isDark 切换）
val DarkBgDefault = WeChatDarkBackground
val DarkBgWarmPink = Color(0xFF24161C)
val DarkBgLavender = Color(0xFF1C1724)
val DarkBgOcean = Color(0xFF141C24)
val DarkBgForest = Color(0xFF141C16)
val DarkBgSunset = Color(0xFF241814)
val DarkBgNight = Color(0xFF101018)

// ============================================================
// Status Colors
// ============================================================

val OnlineStatus = PinkPrimary
val ErrorRed = Color(0xFFFA5151)
val WarningOrange = Color(0xFFFFA500)

// ============================================================
// Glass Effect Colors
// ============================================================

val GlassDarkBg = Color(0xFF2D2228).copy(alpha = 0.75f)
val GlassDarkBorder = Color(0xFFF4A6B5).copy(alpha = 0.12f)
val GlassLightBg = Color(0xFFFFFFFF).copy(alpha = 0.75f)
val GlassLightBorder = Color(0xFFF4A6B5).copy(alpha = 0.20f)

// ============================================================
// Legacy / Compatibility Colors
// ============================================================

val WeChatGreen = PinkPrimary
val WeChatGreenDark = PinkDark

val BlushPink = PinkPrimary
val SoftRose = PinkLight
val PetalPink = Color(0xFFFFE4EC)
val DeepWarm = Color(0xFF4A3F42)
val WarmGray = Color(0xFF8A7E82)
val WarmGray40 = Color(0xFF8A7E82).copy(alpha = 0.4f)
val WarmGray60 = Color(0xFF8A7E82).copy(alpha = 0.6f)
val RoseLight = Color(0xFFFADADD)
val RoseMedium = Color(0xFFF4C2C2)
val RoseDeep = Color(0xFFE8A0BF)
val PrimaryLight = Color(0xFFFADADD)
val BackgroundLight = Color(0xFFFFF8FA)
val ChatBubbleAi = Color(0xFFFFF0F5)
val GlassWhite30 = Color(0xFFFFFFFF).copy(alpha = 0.3f)
val GlassWhite50 = Color(0xFFFFFFFF).copy(alpha = 0.5f)

// Petal Soft UI Colors
val PetalPrimary = Color(0xFF894C5C)
val PetalPrimaryContainer = Color(0xFFF4A7B9)
val PetalOnPrimaryContainer = Color(0xFF733949)
val PetalSurface = Color(0xFFFFFFFF)
val PetalSurfaceContainer = Color(0xFFEFEDED)
val PetalOnSurfaceVariant = Color(0xFF524346)
val PetalGreen = Color(0xFF10A37F)
val PetalError = Color(0xFFBA1A1A)
