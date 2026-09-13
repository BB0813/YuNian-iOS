package com.yunian.ai.uicommon.component

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Window
import androidx.compose.ui.graphics.toArgb
import com.yunian.ai.uicommon.theme.WeChatDarkBackground
import com.yunian.ai.uicommon.theme.WeChatLightBackground

object WindowMainBackground {

    private const val THEME_PREFS = "theme_prefs"
    private const val THEME_MODE_KEY = "theme_mode"

    @Volatile
    private var appliedKey: String? = null

    @Volatile
    private var appliedDark: Boolean? = null

    fun applyFromPrefs(activity: Activity) {
        val key = getMainBackgroundKey(activity)
        val isDark = resolveIsDarkTheme(activity)
        forceApply(activity.window, activity, key, isDark)
    }

    fun apply(window: Window, context: Context, key: String, isDark: Boolean) {
        if (appliedKey == key && appliedDark == isDark) return
        val drawable = createDrawable(context, key, isDark)
        window.setBackgroundDrawable(drawable)
        appliedKey = key
        appliedDark = isDark
    }

    fun forceApply(window: Window, context: Context, key: String, isDark: Boolean) {
        appliedKey = null
        appliedDark = null
        apply(window, context, key, isDark)
    }

    fun createDrawable(context: Context, key: String, isDark: Boolean): Drawable {
        if (isCustomBackground(key)) {
            val bitmap = ChatBackgroundCache.loadBitmap(context, key)
            if (bitmap != null && !bitmap.isRecycled) {
                val covered = centerCropToDisplay(context, bitmap)
                return BitmapDrawable(context.resources, covered)
            }
            return ColorDrawable(fallbackSolid(isDark))
        }

        parseColorBackground(key)?.let { color ->
            return ColorDrawable(color.toArgb())
        }

        val gradientColors = presetGradientArgb(key, isDark)
        if (gradientColors != null && gradientColors.size >= 2) {
            return GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                gradientColors
            )
        }

        presetSolidArgb(key, isDark)?.let { argb ->
            return ColorDrawable(argb)
        }

        return ColorDrawable(fallbackSolid(isDark))
    }

    private fun fallbackSolid(isDark: Boolean): Int {
        return if (isDark) WeChatDarkBackground.toArgb() else WeChatLightBackground.toArgb()
    }

    private fun presetGradientArgb(key: String, isDark: Boolean): IntArray? {
        if (!isDark) {
            return when (key) {
                "warm_pink" -> intArrayOf(0xFFDEFCF9.toInt(), 0xFFE8F6FC.toInt(), 0xFFCADEFC.toInt())
                "lavender" -> intArrayOf(0xFFF0ECFC.toInt(), 0xFFE4E0F6.toInt(), 0xFFC3BEF0.toInt())
                "ocean" -> intArrayOf(0xFFDEFCF9.toInt(), 0xFFCADEFC.toInt(), 0xFFB8D4F8.toInt())
                "forest" -> intArrayOf(0xFFDEFCF9.toInt(), 0xFFD4F4F0.toInt(), 0xFFC8E8E4.toInt())
                "sunset" -> intArrayOf(0xFFF4ECFC.toInt(), 0xFFE8DCF8.toInt(), 0xFFCCA8E9.toInt())
                "night" -> intArrayOf(0xFFEEEAF8.toInt(), 0xFFE0DCF0.toInt(), 0xFFC3BEF0.toInt())
                else -> null
            }
        }

        return when (key) {
            "warm_pink" -> intArrayOf(0xFF2A2034.toInt(), 0xFF221A2C.toInt(), 0xFF1A1424.toInt())
            "lavender" -> intArrayOf(0xFF242030.toInt(), 0xFF1C1828.toInt(), 0xFF161220.toInt())
            "ocean" -> intArrayOf(0xFF1A2430.toInt(), 0xFF141C28.toInt(), 0xFF101820.toInt())
            "forest" -> intArrayOf(0xFF1A2420.toInt(), 0xFF141C1A.toInt(), 0xFF101614.toInt())
            "sunset" -> intArrayOf(0xFF2C2030.toInt(), 0xFF241820.toInt(), 0xFF1C1418.toInt())
            "night" -> intArrayOf(0xFF16161E.toInt(), 0xFF101018.toInt(), 0xFF0C0C12.toInt())
            else -> null
        }
    }

    private fun presetSolidArgb(key: String, isDark: Boolean): Int? {
        if (!isDark) {
            return when (key) {
                "default" -> 0xFFDEFCF9.toInt()
                "warm_pink" -> 0xFFE8F6FC.toInt()
                "lavender" -> 0xFFE8E4F8.toInt()
                "ocean" -> 0xFFE0F0FC.toInt()
                "forest" -> 0xFFE4F8F4.toInt()
                "sunset" -> 0xFFF0E8FC.toInt()
                "night" -> 0xFFE8E4F4.toInt()
                else -> null
            }
        }
        return when (key) {
            "default" -> WeChatDarkBackground.toArgb()
            "warm_pink" -> 0xFF24161C.toInt()
            "lavender" -> 0xFF1C1724.toInt()
            "ocean" -> 0xFF141C24.toInt()
            "forest" -> 0xFF141C16.toInt()
            "sunset" -> 0xFF241814.toInt()
            "night" -> 0xFF101018.toInt()
            else -> null
        }
    }

    private fun centerCropToDisplay(context: Context, source: Bitmap): Bitmap {
        val metrics = context.resources.displayMetrics
        val targetW = metrics.widthPixels.coerceAtLeast(1)
        val targetH = metrics.heightPixels.coerceAtLeast(1)
        val srcW = source.width.coerceAtLeast(1)
        val srcH = source.height.coerceAtLeast(1)

        val scale = maxOf(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
        val scaledW = (srcW * scale).toInt().coerceAtLeast(1)
        val scaledH = (srcH * scale).toInt().coerceAtLeast(1)
        val dx = (targetW - scaledW) / 2f
        val dy = (targetH - scaledH) / 2f

        val config = source.config ?: Bitmap.Config.RGB_565
        val output = Bitmap.createBitmap(targetW, targetH, config)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        canvas.drawColor(AndroidColor.BLACK)
        canvas.drawBitmap(source, matrix, paint)
        return output
    }

    fun resolveIsDarkTheme(context: Context): Boolean {
        val prefs = context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
        val modeName = prefs.getString(THEME_MODE_KEY, "SYSTEM") ?: "SYSTEM"
        return when (modeName) {
            "LIGHT" -> false
            "DARK" -> true
            else -> {
                val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                night == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }
}
