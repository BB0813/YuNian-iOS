package com.lianyu.ai.uicommon.component

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
import com.lianyu.ai.uicommon.theme.WeChatDarkBackground
import com.lianyu.ai.uicommon.theme.WeChatLightBackground

/**
 * 主界面全局背景的窗口层实现。
 *
 * 标准路径：
 * 1. 主题 [android:windowBackground] 提供冷启动/默认兜底（系统在 View 树下绘制）
 * 2. 运行时通过 [Window.setBackgroundDrawable] 切换用户选择的纯色 / 渐变 / 自定义图
 * 3. Compose 根 Surface 与主 Tab 保持透明，让窗口层透出，避免根层二次 overdraw
 *
 * 聊天背景仍由聊天页自己绘制，不写入 windowBackground。
 */
object WindowMainBackground {

    private const val THEME_PREFS = "theme_prefs"
    private const val THEME_MODE_KEY = "theme_mode"

    @Volatile
    private var appliedKey: String? = null

    @Volatile
    private var appliedDark: Boolean? = null

    /** 从 SharedPreferences 读取主背景 key 与当前深浅色，应用到 Activity 窗口。 */
    fun applyFromPrefs(activity: Activity) {
        val key = getMainBackgroundKey(activity)
        val isDark = resolveIsDarkTheme(activity)
        apply(activity.window, activity, key, isDark)
    }

    /**
     * 将主背景 key 解析为 Drawable 并设置到窗口。
     * 相同 key + 深浅色已应用时跳过，避免无意义重绘。
     */
    fun apply(window: Window, context: Context, key: String, isDark: Boolean) {
        if (appliedKey == key && appliedDark == isDark) return
        val drawable = createDrawable(context, key, isDark)
        window.setBackgroundDrawable(drawable)
        appliedKey = key
        appliedDark = isDark
    }

    /** 强制重新应用（例如自定义图文件被替换后）。 */
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

        // 预设背景按 isDark 解析，避免深色主题仍铺浅色渐变
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

    /**
     * 与 [chatBackgroundOptions] 中 Compose Brush 对齐的 Android 渐变色。
     * window 层无法直接使用 Compose Brush，因此在此维护同一套色标。
     */
    private fun presetGradientArgb(key: String, isDark: Boolean): IntArray? {
        if (!isDark) {
            return when (key) {
                "warm_pink" -> intArrayOf(0xFFFFF5F7.toInt(), 0xFFFFF0F3.toInt(), 0xFFFFE8EC.toInt())
                "lavender" -> intArrayOf(0xFFFBF8FF.toInt(), 0xFFF5F0FF.toInt(), 0xFFEDE5F8.toInt())
                "ocean" -> intArrayOf(0xFFF5FAFF.toInt(), 0xFFE8F2FC.toInt(), 0xFFDBEAF5.toInt())
                "forest" -> intArrayOf(0xFFF8FCF8.toInt(), 0xFFEEF5EE.toInt(), 0xFFE3EFE3.toInt())
                "sunset" -> intArrayOf(0xFFFFF8F5.toInt(), 0xFFFFF0E8.toInt(), 0xFFFFE8D8.toInt())
                "night" -> intArrayOf(0xFFF5F5FA.toInt(), 0xFFEEEEF5.toInt(), 0xFFE5E5F0.toInt())
                else -> null
            }
        }
        // 深色主题独立色标，与 resolveBackgroundPalette 对齐
        return when (key) {
            "warm_pink" -> intArrayOf(0xFF2A1A20.toInt(), 0xFF24161C.toInt(), 0xFF1C1218.toInt())
            "lavender" -> intArrayOf(0xFF221C2C.toInt(), 0xFF1C1724.toInt(), 0xFF16121C.toInt())
            "ocean" -> intArrayOf(0xFF1A2430.toInt(), 0xFF141C24.toInt(), 0xFF101820.toInt())
            "forest" -> intArrayOf(0xFF1A241C.toInt(), 0xFF141C16.toInt(), 0xFF101610.toInt())
            "sunset" -> intArrayOf(0xFF2C1C16.toInt(), 0xFF241814.toInt(), 0xFF1C1410.toInt())
            "night" -> intArrayOf(0xFF16161E.toInt(), 0xFF101018.toInt(), 0xFF0C0C12.toInt())
            else -> null
        }
    }

    private fun presetSolidArgb(key: String, isDark: Boolean): Int? {
        if (!isDark) {
            return when (key) {
                "default" -> 0xFFF5F5F5.toInt()
                "warm_pink" -> 0xFFFFF0F3.toInt()
                "lavender" -> 0xFFF8F5FF.toInt()
                "ocean" -> 0xFFF0F5FA.toInt()
                "forest" -> 0xFFF2F8F2.toInt()
                "sunset" -> 0xFFFFF5F0.toInt()
                "night" -> 0xFFF0F0F5.toInt()
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

    /**
     * 将位图按屏幕尺寸 center-crop，避免 BitmapDrawable 默认拉伸变形。
     * 输出独立副本，避免与 [ChatBackgroundCache] 回收共享同一 Bitmap。
     */
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
