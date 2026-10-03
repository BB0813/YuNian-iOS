package com.yunian.ai.feature.skills.accessibility

import com.ven.assists.service.AssistsService
import com.yunian.ai.common.SecureLog

/**
 * 予念无障碍服务：AI 控制手机的执行通道（基类已由平台 [android.accessibilityservice.AccessibilityService]
 * 换成 assists 的 [AssistsService]）。
 *
 * ## 为什么可以换基类（只有这一条路）
 *
 * assists 全库取「当前无障碍服务」**只有一条路径**：
 * `AssistsService.Companion.getOrNull()` 读的是 `AssistsService` 里
 * `@Volatile private var serviceRef` —— 没有公开 setter，也没有任何
 * `setService / attachService / initService` 之类的注入入口（全 jar 检索 0 命中）。
 * 所以想让 assists 的能力作用在**本应用既有的这一个**无障碍服务实例上，
 * 唯一做法就是让该实例 IS-A `AssistsService`：`onCreate` / `onServiceConnected` /
 * `onAccessibilityEvent` 里 `serviceRef = this` 由基类完成，实例自然成为 assists 的服务。
 *
 * 附带的本项目收益：
 * - **manifest 一行不改**。`feature/skills/src/main/AndroidManifest.xml` 里声明的仍是
 *   `.accessibility.YuNianAccessibilityService` 一个 `<service>`；
 *   assists-base 的 AAR manifest **没有任何 `<service>`**（已核验原文），
 *   因此合并后系统无障碍列表里**仍然只有 1 个服务**，用户已经给出的授权**不会失效**。
 * - `accessibility_service_config.xml` 也一行不改：`AssistsService` 从不调用
 *   `setServiceInfo()`（全字节码无此调用），服务能力完全由现有 meta-data 决定，
 *   不会引入 assists 自带 xml 里的触摸探索 / 全按键捕获 / 截屏等激进能力。
 *
 * ## 覆盖点的取舍（每一处都有理由）
 *
 * 1. [onServiceConnected]：**必须先 `super`**。基类实现在这里有严格顺序的副作用 ——
 *    `serviceRef = this` → `AssistsWindowManager.init(this)` → 通知所有
 *    `AssistsServiceListener` → 打一条 utilcodex 日志。把 `instance = this` 放在
 *    `super` **之后**，[isReady] 为 true 才严格蕴含「assists 侧已就绪」，两者不会互相说谎；
 *    反过来写就会造出「[isReady] 已 true 但 `AssistsService.getOrNull()` 还是 null」的窗口。
 * 2. `onAccessibilityEvent`：**不覆盖**（原先那个 `override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit`
 *    已被删除）。除签名问题外的实质理由——基类实现里有一条 `serviceRef = this`，
 *    空实现会让它永不执行，同时 assists 的 listener 分发也永不触发。
 *    顺带说明：原先那行的参数是**可空**的 `AccessibilityEvent?`，而 `AssistsService` 声明为非空；
 *    换基类后这一行**直接编译失败**（`'onAccessibilityEvent' overrides nothing`，kotlinc 已实证），
 *    所以「删掉」不只是风格选择，也是必须的动作。
 * 3. [onInterrupt]：改为调 `super`。基类在这里向 `AssistsServiceListener` 分发中断事件；
 *    原来的 `= Unit` 会把这条分发掐断。保留覆盖（而不是删除）是为了把「这里有意调用基类」写在明面上。
 * 4. [onUnbind]：先 `instance = null` 再 `return super.onUnbind(intent)` —— 与迁移前语义逐字一致
 *    （基类在 `super` 之前会清自己的 `serviceRef` 并通知 listener）。
 *
 * ## 能力方法去哪了
 *
 * 迁移前本类上的 `readScreenText / findAndClick / climbToClickable / tap / swipe /
 * dispatchPathGesture / globalAction` 已**全部迁出**到
 * [AssistsAccessibilityBridge]（`res/values` 意义上的「真机实现」），由它对齐旧语义。
 * 删除前已用全仓 grep 证明这些方法除本文件内部外**没有任何其它调用方**。
 *
 * ## `AssistsCore.init(Application)` 有意不调用
 *
 * 它不是「服务初始化」：源码只做两件事 —— 保存 `initApplication` + 设置 utilcodex 的
 * `LogUtils.Config.globalTag`。唯一的用处是让 **无参重载** `AssistsCore.isA11yEnabled()`
 * 有个默认 `Context` 可用（否则抛 `IllegalStateException`）。本模块走的是
 * [isReady]（= 自己的服务实例）而不是 assists 的 `isA11yEnabled()`，手势 / 节点 / 全局动作
 * 也都不依赖它，所以**不需要**在 `Application` 里初始化，也就不该为此往启动路径里加一行。
 */
class YuNianAccessibilityService : AssistsService() {

    override fun onServiceConnected() {
        // super 先执行：serviceRef = this → AssistsWindowManager.init(this) → 通知 listeners。
        // 顺序不可调换，理由见类 KDoc 第 1 条。
        super.onServiceConnected()
        instance = this
        SecureLog.i(TAG, "Accessibility service connected")
    }

    override fun onInterrupt() {
        // 有意调用 super：基类在此向 AssistsServiceListener 分发中断事件。
        super.onInterrupt()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "YuNianA11y"

        /**
         * 当前服务实例；**语义与可见性均与迁移前逐字一致**（对外只读，写入口只在
         * [onServiceConnected] / [onUnbind]）。
         * `SkillsCenterScreen` 直接读 [isReady] 渲染「AI 控制手机」的状态，改语义会直接影响 UI。
         */
        @Volatile
        var instance: YuNianAccessibilityService? = null
            private set

        /** 无障碍服务是否已连接（对外契约，勿改）。 */
        val isReady: Boolean get() = instance != null
    }
}
