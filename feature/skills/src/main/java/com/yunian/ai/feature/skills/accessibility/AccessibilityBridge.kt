package com.yunian.ai.feature.skills.accessibility

/**
 * 无障碍能力接缝（纯 Kotlin，零 Android 依赖）：工具层只依赖本接口，
 * 真机实现见 AssistsAccessibilityBridge，JVM 单测注入假实现。
 * 语义逐条对齐迁移前 YuNianAccessibilityService 的公开方法。
 */
interface AccessibilityBridge {
    /** 无障碍服务是否已连接（对齐 YuNianAccessibilityService.isReady）。 */
    fun isReady(): Boolean
    /** 当前活动窗口的扁平可见文本，最多 maxLength 字符（对齐 readScreenText）。 */
    fun readScreenText(maxLength: Int = 3000): String
    /** 当前活动窗口的结构化节点树 JSON；未就绪或无内容时返回空串。 */
    fun dumpNodeTreeJson(): String
    /** 手势点击（对齐既有 tap 的 60ms 短按语义）。 */
    suspend fun tap(x: Float, y: Float): Boolean
    /** 手势滑动。 */
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean
    /** 按可见文本查找并点击（优先可点击节点，否则上溯可点击祖先）。 */
    fun clickText(text: String): Boolean
    /**
     * 向**唯一确定的**可编辑节点输入文本：优先当前聚焦的 editable；
     * 否则要求活动窗口内可编辑节点唯一；缺失或多义一律返回 false（绝不猜输入目标）。
     */
    fun inputText(text: String): Boolean
    /** 全局动作：返回。 */
    fun back(): Boolean
    /** 全局动作：回主屏。 */
    fun home(): Boolean
}
