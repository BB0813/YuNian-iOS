package com.lianyu.ai.uicommon.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// Soft Pastel Palette (brand)
// #DEFCF9 mint · #CADEFC sky · #C3BEF0 lavender · #CCA8E9 lilac
// ============================================================

val PastelMint = Color(0xFFDEFCF9)
val PastelSky = Color(0xFFCADEFC)
val PastelLavender = Color(0xFFC3BEF0)
val PastelLilac = Color(0xFFCCA8E9)

// ============================================================
// Soft Pastel Theme - Dark Mode
// ============================================================
// 层级（由深到浅）：Background → Surface(chrome) → Card(列表/菜单) → Elevated
// 深色主题必须保证 chrome / 卡片 / 页面底有清晰对比，避免“糊成一片”。

// Background — 由 lilac/lavender 压暗得到的靛紫底
val WeChatDarkBackground = Color(0xFF16131F)
val WeChatDarkSurface = Color(0xFF1F1A2C)
val WeChatDarkCard = Color(0xFF2A2438)
val WeChatDarkElevated = Color(0xFF342E44)
val WeChatDarkDivider = Color(0xFF463E58)

// Text
val WeChatDarkTextPrimary = Color(0xFFF2EEF8)
val WeChatDarkTextSecondary = Color(0xFFB8B0C8)
val WeChatDarkTextTertiary = Color(0xFF8A829A)

// Accent — lilac 为主色，lavender 为浅强调
val PinkPrimary = PastelLilac
val PinkLight = PastelLavender
val PinkDark = Color(0xFFA888D0)
val PinkMuted = Color(0xFF5A4A70)
/** 深色主题选中/强调底：不透明，避免透底发脏 */
val PinkPrimaryContainerDark = Color(0xFF3A2E52)
val PinkOnPrimaryContainerDark = Color(0xFFE8DCF8)

// ============================================================
// Soft Pastel Theme - Light Mode
// ============================================================

val WeChatLightBackground = PastelMint
val WeChatLightSurface = Color(0xFFE8F6FC)
val WeChatLightCard = Color(0xFFFFFFFF)
val WeChatLightDivider = Color(0xFFC8D8F0)

val WeChatLightTextPrimary = Color(0xFF2A2440)
val WeChatLightTextSecondary = Color(0xFF5E5678)
val WeChatLightTextTertiary = Color(0xFF8A829A)

// ============================================================
// Message Bubble Colors
// ============================================================

// 消息气泡必须不透明，避免聊天背景透出
// 统一标准：sky #CADEFC（自己 / AI 同色，靠左右箭头区分归属）
val AiBubbleLight = PastelSky
val AiBubbleDark = PastelSky
val SelfBubbleLight = AiBubbleLight
val SelfBubbleDark = AiBubbleDark
val AiBubbleBorderDark = PastelLavender.copy(alpha = 0.40f)
val AiBubbleBorderLight = PastelLavender.copy(alpha = 0.28f)
/** 气泡上的深色文字，保证在 pastel 底上可读 */
val BubbleOnPink = Color(0xFF2A2440)

// 兼容旧命名
val BubbleSkyBlue = PastelSky

// ============================================================
// Navigation / Chrome
// ============================================================

val NavBackgroundDark = WeChatDarkSurface
val NavBackgroundLight = Color(0xFFF0FAFC)
val NavSelected = PinkPrimary
val NavUnselectedDark = WeChatDarkTextTertiary
val NavUnselectedLight = Color(0xFF8A829A)

// 深色主题主界面预设背景（与浅色同 key，运行时按 isDark 切换）
val DarkBgDefault = WeChatDarkBackground
val DarkBgWarmPink = Color(0xFF221A2C)
val DarkBgLavender = Color(0xFF1C1828)
val DarkBgOcean = Color(0xFF141C28)
val DarkBgForest = Color(0xFF141C1A)
val DarkBgSunset = Color(0xFF241820)
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

val GlassDarkBg = Color(0xFF2A2438).copy(alpha = 0.75f)
val GlassDarkBorder = PastelLilac.copy(alpha = 0.14f)
val GlassLightBorder = PastelLilac.copy(alpha = 0.22f)
val GlassLightBg = Color(0xFFFFFFFF).copy(alpha = 0.75f)

// ============================================================
// Legacy / Compatibility Colors
// ============================================================

val WeChatGreen = PinkPrimary
val WeChatGreenDark = PinkDark

val BlushPink = PinkPrimary
val SoftRose = PinkLight
val PetalPink = PastelLavender
val DeepWarm = Color(0xFF3A3450)
val WarmGray = Color(0xFF7A7490)
val WarmGray40 = Color(0xFF7A7490).copy(alpha = 0.4f)
val WarmGray60 = Color(0xFF7A7490).copy(alpha = 0.6f)
val RoseLight = PastelSky
val RoseMedium = PastelLavender
val RoseDeep = PastelLilac
val PrimaryLight = PastelSky
val BackgroundLight = PastelMint
val ChatBubbleAi = PastelSky
val GlassWhite30 = Color(0xFFFFFFFF).copy(alpha = 0.3f)
val GlassWhite50 = Color(0xFFFFFFFF).copy(alpha = 0.5f)

// Petal Soft UI Colors
val PetalPrimary = Color(0xFF5A4A80)
val PetalPrimaryContainer = PastelLilac
val PetalOnPrimaryContainer = Color(0xFF3A2E52)
val PetalSurface = Color(0xFFFFFFFF)
val PetalSurfaceContainer = Color(0xFFE8F0F8)
val PetalOnSurfaceVariant = Color(0xFF524A66)
val PetalGreen = Color(0xFF10A37F)
val PetalError = Color(0xFFBA1A1A)
