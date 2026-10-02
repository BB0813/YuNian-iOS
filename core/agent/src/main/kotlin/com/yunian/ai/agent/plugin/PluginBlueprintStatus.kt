package com.yunian.ai.agent.plugin

import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 默认蓝图装载结果的**进程级可查询状态**（债务 D4 / B2）。
 *
 * ## 它解决什么问题
 * 蓝图装载此前是「**失败即静默**」：
 * - `app/.../YuNianApplication.kt` 的调用点被 `runCatching` 包着，失败只剩一条日志；
 * - `PluginHostImpl.loadBlueprint` 将单插件失败记为 `Applied.skipped` 中的 `failed:` 项；
 * - `PluginHost.loadedIds()` 只能告诉你「现在装了什么」，**无法区分**
 *   「还没尝试装载」与「尝试了但整份蓝图没装载」。
 *
 * 例如资产语法错误时，本次不会执行蓝图装载；这不证明其他路径此前没有加载插件，
 * 也不能仅凭启用偏好推断运行时已装载。
 *
 * 本对象把「这一次蓝图装载到底成没成、装了什么、跳过了什么、为什么失败」
 * 变成**代码里可查询**的一件事：
 * ```kotlin
 * if (PluginBlueprintStatus.outcome() is PluginBlueprintStatus.Outcome.Failed) { /* 告警 / 降级 */ }
 * ```
 *
 * ## 边界（刻意不做的事）
 * - **不做 UI**：本对象只存状态，不画任何东西；
 * - **不持久化**：进程级内存状态，冷启动重新记录（蓝图装载本身就是每次冷启动执行一次）；
 * - **不改变装载语义**：调用方是否继续、是否降级，仍由调用方决定——这里只保证「看得见」；
 * - **不改蓝图资产**：`assets/blueprints/default.json` 仍是只读资产，本对象只读它的装载结果。
 *
 * ## 为什么放在 `core:agent`（而不是 `core:domain`）
 * 蓝图解析器 [PluginBlueprintParser] 与宿主 [PluginHostImpl] 都在 `core:agent`，
 * 「蓝图装载」这件事的**归属模块**就是它；`:app` 已经依赖 `:core:agent`（构造
 * [PluginHostImpl]），因此放在这里**不新增任何模块依赖**。若放进 `core:domain` 反而要在
 * 契约层引入一个只有单一实现者、且不被任何契约方法消费的状态类型。
 *
 * ## 线程安全
 * 写入是 `AtomicReference` 的整对象替换（调用点在启动协程里，天然只有一次），
 * 读取无锁。读取到的是不可变快照，不会看到半更新状态。
 *
 * ## 测试
 * 纯 JVM 可测：不碰 `android.util.Log` / `org.json` / `SystemClock`，
 * 见 `PluginBlueprintStatusTest`。
 */
object PluginBlueprintStatus {

    /** 默认蓝图在 `assets` 里的路径（与 `YuNianApplication.loadDefaultBlueprint` 一致）。 */
    const val DEFAULT_BLUEPRINT_ASSET: String = "blueprints/default.json"

    /**
     * 一次蓝图装载的**结局**。
     *
     * 注意 [Applied] 里的 `skipped` 非空**不等于**失败：`disabled:<id>`（蓝图里显式
     * `enabled=false`）与 `notfound:<id>`（蓝图引用了未注册的插件）都是蓝图正常工作时的
     * 遍历结果；`failed:<id>(<原因>)` 则代表单插件失败，宿主仍继续后续项。
     * [Applied] 表示遍历完成，不保证全部插件成功；[Failed] 表示整次尝试未正常完成。
     */
    sealed interface Outcome {
        /** 还没尝试装载（进程刚起 / 装载代码还没跑到）。 */
        data object NotAttempted : Outcome

        /** 蓝图遍历完成：[loaded] 是返回 Loaded 的 id（含幂等已装载项），[skipped] 含逐项失败。 */
        data class Applied(
            /** 本次返回 Loaded 的插件 id（顺序同蓝图，含幂等项；不是当前存活集合）。 */
            val loaded: List<String>,
            /** 跳过项，形如 `disabled:<id>` / `notfound:<id>` / `failed:<id>(<原因>)`。 */
            val skipped: List<String>,
        ) : Outcome

        /** 蓝图本身没装载成功：JSON 非法 / 资产读不到 / 宿主未注册 / 装载抛异常。 */
        data class Failed(
            /** 蓝图 id；解析失败到拿不到 id 时为 null。 */
            val blueprintId: String?,
            /** 人类可读原因（进日志与告警，不参与分支判定）。 */
            val reason: String,
            /**
             * 调用方明确提供的已知装载项；默认空表示未提供，不证明宿主为空。
             * 宿主抛异常或返回 BlueprintLoadResult.Failed 时不携带部分进度，
             * 当前 app 路径因此不填此列表。当前存活集合应查询 PluginHost.loadedIds()。
             * 单插件失败通常属于 Applied.skipped，而不是此结局。
             */
            val loaded: List<String> = emptyList(),
        ) : Outcome
    }

    /** 一次装载的记录：[outcome] + 记录时刻（便于观测「上次装载是多久以前」）。 */
    data class Record(
        /** 本次装载的结局。 */
        val outcome: Outcome,
        /** 记录时刻（`System.currentTimeMillis()`）。 */
        val recordedAtMs: Long,
    )

    private val current = AtomicReference(Record(Outcome.NotAttempted, 0L))
    private val attempts = AtomicLong(0L)

    /** 当前记录（未尝试过时 [Outcome.NotAttempted]，`recordedAtMs = 0`）。 */
    fun current(): Record = current.get()

    /** 当前结局的快捷读取（等价于 `current().outcome`）。 */
    fun outcome(): Outcome = current.get().outcome

    /** 最近一次是否整次失败；单插件失败需检查 Applied.skipped 中的 failed: 项。 */
    fun hasFailed(): Boolean = current.get().outcome is Outcome.Failed

    /** 已记录的装载次数（用于区分「一次都没跑」与「跑过但结果没变」）。 */
    fun attempts(): Long = attempts.get()

    /**
     * 记录一次遍历完成（`BlueprintLoadResult.Applied`），可能包含单插件失败。
     *
     * @param blueprintId 蓝图 id（来自解析结果）。
     * @param loaded 本次返回 Loaded 的插件 id（含幂等项）。
     * @param skipped 跳过项（`disabled:` / `notfound:` / `failed:` 前缀，宿主原样给出）。
     */
    fun recordApplied(blueprintId: String, loaded: List<String>, skipped: List<String>) {
        attempts.incrementAndGet()
        current.set(
            Record(
                Outcome.Applied(
                    loaded = Collections.unmodifiableList(ArrayList(loaded)),
                    skipped = Collections.unmodifiableList(ArrayList(skipped)),
                ),
                System.currentTimeMillis(),
            )
        )
    }

    /**
     * 记录一次**失败**装载。
     *
     * @param reason 人类可读原因；[failure] 非空时自动附上 `<异常类名>: <message>`，
     *   避免「NullPointerException 无 message」这类情况丢掉定位信息。
     * @param blueprintId 蓝图 id（拿不到就传 null）。
     * @param loaded 调用方提供的已知装载项（默认空 = 未提供，不是宿主为空）。
     * @param failure 原始异常（可空）。
     */
    fun recordFailure(
        reason: String,
        blueprintId: String? = null,
        loaded: List<String> = emptyList(),
        failure: Throwable? = null,
    ) {
        attempts.incrementAndGet()
        val detail = if (failure == null) {
            reason
        } else {
            reason + " — " + failure.javaClass.simpleName + ": " + (failure.message ?: "(no message)")
        }
        current.set(
            Record(
                Outcome.Failed(
                    blueprintId = blueprintId,
                    reason = detail,
                    loaded = Collections.unmodifiableList(ArrayList(loaded)),
                ),
                System.currentTimeMillis(),
            )
        )
    }

    /**
     * 把状态复位成 [Outcome.NotAttempted] 并清零计数。
     *
     * **仅供本模块单测使用**——生产路径不需要复位（每次冷启动天然是新的进程）。
     * 之所以是 `internal` 而不是 public：`:app` 需要的是**读**（`outcome()` /
     * `hasFailed()`），没有任何生产或跨模块测试理由去**改**这个状态；
     * 把它挡在 `:app` 之外，避免「谁都能清空失败记录」把可见性重新变成可选。
     */
    internal fun resetForTest() {
        attempts.set(0L)
        current.set(Record(Outcome.NotAttempted, 0L))
    }
}
