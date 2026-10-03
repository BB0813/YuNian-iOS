package com.yunian.ai.feature.skills.testing

import com.yunian.ai.feature.skills.accessibility.AccessibilityBridge

/**
 * [AccessibilityBridge] 的 JVM 假实现：记录每一次被调用的方法名，并按用例返回预设结果。
 *
 * 两个用途：
 * 1. 断言**成功路径**的返回值传递（例如 tap 的坐标是否原样传给接缝）；
 * 2. 断言**失败路径不触碰接缝**（参数缺失 / 文本为空时 [calls] 必须为空——
 *    「什么都没做」比「做错了一半」重要）。
 */
class FakeAccessibilityBridge : AccessibilityBridge {

    /** 按调用顺序记录的方法名（参数化记录写在方法名里，便于失败信息自解释）。 */
    val calls: MutableList<String> = mutableListOf()

    var ready: Boolean = true
    var screenText: String = ""
    var nodeTreeJson: String = ""
    var tapResult: Boolean = true
    var swipeResult: Boolean = true
    var clickTextResult: Boolean = true
    var inputTextResult: Boolean = true
    var backResult: Boolean = true
    var homeResult: Boolean = true

    override fun isReady(): Boolean {
        calls += "isReady"
        return ready
    }

    override fun readScreenText(maxLength: Int): String {
        calls += "readScreenText(maxLength=$maxLength)"
        return screenText
    }

    override fun dumpNodeTreeJson(): String {
        calls += "dumpNodeTreeJson"
        return nodeTreeJson
    }

    override suspend fun tap(x: Float, y: Float): Boolean {
        calls += "tap(x=$x,y=$y)"
        return tapResult
    }

    override suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        calls += "swipe(x1=$x1,y1=$y1,x2=$x2,y2=$y2,durationMs=$durationMs)"
        return swipeResult
    }

    override fun clickText(text: String): Boolean {
        calls += "clickText(text=$text)"
        return clickTextResult
    }

    override fun inputText(text: String): Boolean {
        calls += "inputText(text=$text)"
        return inputTextResult
    }

    override fun back(): Boolean {
        calls += "back"
        return backResult
    }

    override fun home(): Boolean {
        calls += "home"
        return homeResult
    }
}
