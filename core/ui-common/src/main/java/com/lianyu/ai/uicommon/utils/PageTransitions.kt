package com.lianyu.ai.uicommon.utils

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import com.lianyu.ai.common.HardwareInfo

/**
 * 页面切换过渡动画规范
 *
 * 基于屏幕宽度的百分比偏移 + 淡入淡出，确保在不同分辨率上效果一致。
 * 根据设备硬件层级自动适配：
 * - LOW: 无动画（直接切换）
 * - MEDIUM: 简化动画（缩短时长）
 * - HIGH / ULTRA: 完整平滑动画
 */
object PageTransitions {

    /** 标准页面进入时长 (ms) */
    private const val DURATION_ENTER = 350

    /** 标准页面退出时长 (ms) */
    private const val DURATION_EXIT = 280

    /** 简化动画时长 (ms) — MEDIUM 设备 */
    private const val DURATION_SIMPLE = 180

    /** 滑动偏移占屏幕宽度的比例 */
    private const val SLIDE_FRACTION = 0.28f

    /**
     * 前向导航 — 进入动画
     * 从右侧滑入 + 淡入，使用 FastOutSlowIn 缓动曲线
     */
    fun enterTransition(): EnterTransition {
        val tier = HardwareInfo.tier
        if (tier == HardwareInfo.Tier.LOW) return EnterTransition.None

        val duration = if (tier == HardwareInfo.Tier.MEDIUM) DURATION_SIMPLE else DURATION_ENTER
        val easing = FastOutSlowInEasing

        return slideInHorizontally(
            animationSpec = tween(durationMillis = duration, easing = easing),
            initialOffsetX = { (it * SLIDE_FRACTION).toInt() }
        ) + fadeIn(
            animationSpec = tween(durationMillis = duration, easing = easing)
        )
    }

    /**
     * 前向导航 — 退出动画
     * 向左侧滑出 + 淡出
     */
    fun exitTransition(): ExitTransition {
        val tier = HardwareInfo.tier
        if (tier == HardwareInfo.Tier.LOW) return ExitTransition.None

        val duration = if (tier == HardwareInfo.Tier.MEDIUM) DURATION_SIMPLE else DURATION_EXIT
        val easing = FastOutSlowInEasing

        return slideOutHorizontally(
            animationSpec = tween(durationMillis = duration, easing = easing),
            targetOffsetX = { -(it * SLIDE_FRACTION).toInt() }
        ) + fadeOut(
            animationSpec = tween(durationMillis = duration, easing = easing)
        )
    }

    /**
     * 返回导航 — 进入动画（前页重新出现）
     * 从左侧滑入 + 淡入
     */
    fun popEnterTransition(): EnterTransition {
        val tier = HardwareInfo.tier
        if (tier == HardwareInfo.Tier.LOW) return EnterTransition.None

        val duration = if (tier == HardwareInfo.Tier.MEDIUM) DURATION_SIMPLE else DURATION_ENTER
        val easing = FastOutSlowInEasing

        return slideInHorizontally(
            animationSpec = tween(durationMillis = duration, easing = easing),
            initialOffsetX = { -(it * SLIDE_FRACTION).toInt() }
        ) + fadeIn(
            animationSpec = tween(durationMillis = duration, easing = easing)
        )
    }

    /**
     * 返回导航 — 退出动画（当前页消失）
     * 向右侧滑出 + 淡出
     */
    fun popExitTransition(): ExitTransition {
        val tier = HardwareInfo.tier
        if (tier == HardwareInfo.Tier.LOW) return ExitTransition.None

        val duration = if (tier == HardwareInfo.Tier.MEDIUM) DURATION_SIMPLE else DURATION_EXIT
        val easing = FastOutSlowInEasing

        return slideOutHorizontally(
            animationSpec = tween(durationMillis = duration, easing = easing),
            targetOffsetX = { (it * SLIDE_FRACTION).toInt() }
        ) + fadeOut(
            animationSpec = tween(durationMillis = duration, easing = easing)
        )
    }
}
