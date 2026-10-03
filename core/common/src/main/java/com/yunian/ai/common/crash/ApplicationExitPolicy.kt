package com.yunian.ai.common.crash

import android.app.ApplicationExitInfo

/**
 * 「哪些进程退出原因值得向用户提示」的**纯逻辑**策略。
 *
 * **常量来源（根因修复）**：本文件**不再手抄任何数值**，全部直接引用框架常量
 * `ApplicationExitInfo.REASON_*`。它们是 `public static final int`，Kotlin 编译期即
 * **内联为字面量**，运行时不加载 Android 类（故 JVM 单测/API 30 以下均安全）。
 *
 * 为什么必须这么做：上一版把整张常量表**手抄错位** —— 将
 * `REASON_INITIALIZATION_FAILURE` 抄成了 `8`，而权威值 `8` 实际是
 * `REASON_PERMISSION_CHANGE`。由此产生两个真实后果：
 *  - **误报**：运行中 `pm revoke <权限>` 导致进程被杀，重启后被提示为「上次运行异常退出」
 *    （报告里写着 `reason: REASON_8 description: permissions revoked`）—— 权限变更不是崩溃；
 *  - **漏报**：真正的初始化失败（权威值 7）不在白名单，用户看不到。
 * 改为引用框架常量后，「抄错数字」这类错误从根上不可能再发生。
 *
 * 权威常量表（`javap -constants -classpath platforms/android-35/android.jar` 核对，仅作文档）：
 * ```
 * REASON_UNKNOWN                  = 0   → 不提示
 * REASON_EXIT_SELF                = 1   → 不提示（正常退出）
 * REASON_SIGNALED                 = 2   → ✅ 提示（被信号终止，疑似底层崩溃）
 * REASON_LOW_MEMORY               = 3   → 不提示（系统回收内存，**不是异常**，见下）
 * REASON_CRASH                    = 4   → ✅ 提示（Java 未捕获异常）
 * REASON_CRASH_NATIVE             = 5   → ✅ 提示（native 崩溃）
 * REASON_ANR                      = 6   → ✅ 提示（应用无响应）
 * REASON_INITIALIZATION_FAILURE   = 7   → ✅ 提示（初始化失败）
 * REASON_PERMISSION_CHANGE        = 8   → 不提示（权限变更，非崩溃）
 * REASON_EXCESSIVE_RESOURCE_USAGE = 9   → 不提示
 * REASON_USER_REQUESTED           = 10  → 不提示（用户从最近任务划掉）
 * REASON_USER_STOPPED             = 11  → 不提示
 * REASON_DEPENDENCY_DIED          = 12  → 不提示
 * REASON_OTHER                    = 13  → 不提示（语义不明）
 * REASON_FREEZER                  = 14  → 不提示
 * REASON_PACKAGE_STATE_CHANGE     = 15  → 不提示
 * REASON_PACKAGE_UPDATED          = 16  → 不提示
 * ```
 */
internal object ApplicationExitPolicy {

    /**
     * 值得向用户提示的退出原因（白名单）—— **只收「应用真的出错了」的原因**。
     *
     * `REASON_LOW_MEMORY`（3，系统回收内存）**刻意排除**，理由：
     *  - **语义上它不是异常**：LMK 是内核在内存紧张时的正常行为，与应用出错无关。
     *    把它归入「异常退出」向用户展示，本身就是误报。
     *  - **实践中它必然噪声化**：本 App 常驻保活前台服务，`importance` 恒为
     *    `FOREGROUND_SERVICE(125)`。于是任何「只对前台/前台服务提示」的收窄条件
     *    **恒为真**，等于「每次被系统回收都弹窗」。实测（vivo V2324A / OriginOS）：
     *    `reason=3 importance=125 description=single-cleaner` 每隔约 1 分钟出现一次，
     *    用户每次启动都会看到「上次运行异常退出」。
     *  - **无法与用户主动退出区分**：OEM 清理器给出的 reason 与真被 LMK 完全一致
     *    （见 [isUserInitiatedExit]），靠 reason 永远分不开。
     *
     * 若日后需要让用户知道「应用又被系统回收了」，应走**独立于崩溃弹窗**的呈现
     * （保活健康度之类的入口），而不是把它重新塞回这张白名单。
     */
    private val NOTABLE: Set<Int> = setOf(
        ApplicationExitInfo.REASON_SIGNALED,               // 2  被信号终止（疑似底层崩溃）
        ApplicationExitInfo.REASON_CRASH,                  // 4  Java 未捕获异常
        ApplicationExitInfo.REASON_CRASH_NATIVE,           // 5  native 崩溃（华为那类）
        ApplicationExitInfo.REASON_ANR,                    // 6  应用无响应
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, // 7  初始化失败
    )

    /**
     * 「崩溃类」退出原因：**只有这类退出才可能与落盘的崩溃报告对应**。
     *
     * 用来修一个真实缺陷：业务崩溃报告（`crash_business.txt` / `crash_shell.txt`）会一直留在
     * 磁盘上，而启动时的展示路径只看「文件在不在」。于是**一份陈旧报告被无限重放** ——
     * 用户每次主动划掉后台，下次启动照样看到「上次运行发生了闪退」，
     * 而系统记录的其实是 `REASON_LOW_MEMORY`（正常回收）。
     *
     * 判定依据：本项目**没有任何 native 信号处理器**，所有报告都出自
     * `Thread.setDefaultUncaughtExceptionHandler`，故实际只会对应 `REASON_CRASH`；
     * 这里仍把 `CRASH_NATIVE` / `SIGNALED` 一并纳入，是「宁可多提示、绝不漏报」的保守取向。
     */
    private val CRASH_LIKE: Set<Int> = setOf(
        ApplicationExitInfo.REASON_SIGNALED,     // 2  被信号终止
        ApplicationExitInfo.REASON_CRASH,        // 4  Java 未捕获异常（本项目报告的实际来源）
        ApplicationExitInfo.REASON_CRASH_NATIVE, // 5  native 崩溃
    )

    /**
     * 「用户移除任务」→「进程结束」的最大时间窗（毫秒）。
     *
     * 实测（vivo V2324A / OriginOS，Android 16）：从最近任务划掉后约 2s 进程即被系统
     * `single-cleaner` 结束。取 15s 留足余量，同时远小于「划掉后进程仍长期存活、
     * 之后才因别的原因死亡」的情形 —— 后者不该被算作用户主动退出。
     */
    private const val USER_TASK_REMOVAL_WINDOW_MS = 15_000L

    /**
     * 该退出原因是否值得向用户提示为「异常退出」。
     *
     * 历史上这里还带一个 `importance` 收窄参数，用于「只在前台/前台服务时提示 LMK」。
     * 该参数已随 `REASON_LOW_MEMORY` 一并移除：本 App 常驻前台服务，`importance` 恒为
     * 125，收窄条件恒为真，参数没有任何实际区分力（见 [NOTABLE] 的说明）。
     *
     * @param reason     退出原因，取自 [ApplicationExitInfo] 的 `REASON_*`。
     * @param userInitiated 该退出是否由用户主动移除任务引起（见 [isUserInitiatedExit]）。
     *                   为 true 时**一律不提示**：用户自己划掉的应用不该被告知「异常退出」。
     */
    fun isNotable(reason: Int, userInitiated: Boolean = false): Boolean {
        if (userInitiated) return false
        return reason in NOTABLE
    }

    /**
     * 该退出原因是否属于「崩溃类」（见 [CRASH_LIKE]）。
     *
     * 用于判定落盘的崩溃报告是否**属于本次退出**：不属于即为陈旧报告，不应再向用户展示。
     */
    fun isCrashLike(reason: Int): Boolean = reason in CRASH_LIKE

    /**
     * 该退出是否由**用户主动把任务从最近任务移除**引起。
     *
     * **为什么不能用 reason 判断**：在 vivo/OriginOS 上，用户划掉后台触发的是系统
     * `single-cleaner`，它给出的正是 `REASON_LOW_MEMORY` + `importance=125
     * (FOREGROUND_SERVICE)` —— 与「真的被 LMK 杀掉」的特征**完全重合**（实测
     * `dumpsys activity exit-info`：`reason=3 subreason=0 importance=125
     * description=single-cleaner`），仅凭 reason/importance 无法区分。
     * 所以改用「用户移除任务」这一**不依赖 OEM 语义**的自有信号：
     * `Service.onTaskRemoved()` 落盘的时间戳，与系统记录的死亡时间戳比对。
     *
     * @param exitTimestamp      系统记录的进程死亡时间戳（epoch ms）。
     * @param removedAtTimestamp 用户移除任务的时间戳（epoch ms）；<=0 表示无记录。
     * @return 死亡发生在前者之后的 [USER_TASK_REMOVAL_WINDOW_MS] 窗口内时为 true。
     */
    fun isUserInitiatedExit(exitTimestamp: Long, removedAtTimestamp: Long): Boolean {
        if (exitTimestamp <= 0L || removedAtTimestamp <= 0L) return false
        // 死亡时间早于移除时间 → 这次死亡与移除无关。
        if (exitTimestamp < removedAtTimestamp) return false
        return exitTimestamp - removedAtTimestamp <= USER_TASK_REMOVAL_WINDOW_MS
    }
}
