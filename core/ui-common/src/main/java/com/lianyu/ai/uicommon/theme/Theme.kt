package com.lianyu.ai.uicommon.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = PinkPrimary,
    onPrimary = Color(0xFF2D1F24),
    primaryContainer = PinkPrimary.copy(alpha = 0.2f),
    onPrimaryContainer = Color(0xFF2D1F24),
    secondary = Color(0xFF9B6B7A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9B6B7A).copy(alpha = 0.1f),
    onSecondaryContainer = Color(0xFF9B6B7A),
    tertiary = PinkDark,
    onTertiary = Color.White,
    background = WeChatLightBackground,
    onBackground = WeChatLightTextPrimary,
    surface = WeChatLightSurface,
    onSurface = WeChatLightTextPrimary,
    surfaceVariant = WeChatLightCard,
    onSurfaceVariant = WeChatLightTextSecondary,
    outline = WeChatLightDivider,
    error = ErrorRed,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = PinkPrimary,
    onPrimary = Color(0xFF2D1F24),
    primaryContainer = PinkPrimary.copy(alpha = 0.15f),
    onPrimaryContainer = Color(0xFFF5E6EB),
    secondary = PinkLight,
    onSecondary = Color(0xFF2D2C24),
    secondaryContainer = PinkLight.copy(alpha = 0.1f),
    onSecondaryContainer = PinkLight,
    tertiary = PinkDark,
    onTertiary = Color.White,
    background = WeChatDarkBackground,
    onBackground = WeChatDarkTextPrimary,
    surface = WeChatDarkSurface,
    onSurface = WeChatDarkTextPrimary,
    surfaceVariant = WeChatDarkCard,
    onSurfaceVariant = WeChatDarkTextSecondary,
    outline = WeChatDarkDivider,
    error = ErrorRed,
    onError = Color.White
)

@Composable
fun LianYuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("theme_prefs", android.content.Context.MODE_PRIVATE)
    val savedTheme = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"

    val effectiveDarkTheme = when (savedTheme) {
        "LIGHT" -> false
        "DARK" -> true
        else -> darkTheme
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (effectiveDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        effectiveDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(effectiveDarkTheme) {
            val window = (view.context as Activity).window
            window.statusBarColor = if (effectiveDarkTheme) WeChatDarkBackground.toArgb() else WeChatLightBackground.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !effectiveDarkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !effectiveDarkTheme
            onDispose { }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
