package com.yunian.ai.agent

import com.yunian.ai.agent.uniffi.AgentTurnResult
import com.yunian.ai.common.SecureLog
import kotlinx.coroutines.CancellationException

/**
 * 确认门守卫的日志出口（对应 [SecureLog] 的宿主日志面）。
 *
 * 存在理由：确认门守卫需要打日志，而 [SecureLog] 的底层 `android.util.Log` 在纯 JVM 单测里是
 * 抛 `RuntimeException("Stub!")` 的空壳（本仓库无 Robolectric，`:core:agent` 也未开启
 * `unitTests.isReturnDefaultValues`）。因此把日志收敛到一个可替换的接口（与
 * `com.yunian.ai.agent.plugin.PluginLog` 同一手法）：
 * - 生产：默认值 [SecureLogConfirmGuardLog]（tag 与文案与抽取前逐字一致）；
 * - 单测：注入替身（静默 / 记录），纯 JVM 即可驱动真实的 [AgentConfirmGuard]。
 *
 * 可见性说明：本接口是 :core:agent 的实现细节，但**必须 public**——[AgentConfirmGuard]
 * 的构造函数带本类型的默认参数，Kotlin 禁止 public 函数暴露 internal 参数类型。
 * 它不是对外契约的一部分，调用方不需要、也不应该实现它。
 *
 * 行为约定：实现**不得**抛出异常（日志失败不得影响确认门语义）。
 */
interface AgentConfirmGuardLog {
    /** 告警级日志（对齐 `SecureLog.w`）。 */
    fun w(tag: String, message: String)

    /** 错误级日志（对齐 `SecureLog.e`，可带 throwable）。 */
    fun e(tag: String, message: String, throwable: Throwable?)
}

/** 生产实现：转发到 [SecureLog]（tag / 文案与抽取前 [AgentDialogueCoordinator] 内联循环逐字一致）。 */
internal object SecureLogConfirmGuardLog : AgentConfirmGuardLog {
    override fun w(tag: String, message: String) = SecureLog.w(tag, message)

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable == null) SecureLog.e(tag, message) else SecureLog.e(tag, message, throwable)
    }
}

/**
 * Commerce 类工具确认门在**无确认界面**的回合上的兜底守卫：fail-closed 自动拒绝 + 有限次重跑。
 *
 * 背景：`AiTool.requiresConfirmation = true` 经 [AgentFacade.deriveToolCategory] 映射为
 * `ToolCategory.COMMERCE` 后，Rust 回合会以 `finished_reason = "confirm_pending"` 提前结束，
 * 并产出 `kind = "confirm_request"` 的事件（text = 工具名，extra = 参数 JSON）。群聊 / 消息通道
 * 这类没有确认卡片的路径若不处理，回合就此静默结束、用户被晾在「需要确认」上（死路）。
 *
 * 语义（与抽取前 `AgentDialogueCoordinator.runTurn` 的内联实现逐字一致，未新增分支）：
 * 1. 循环条件：`finishedReason == "confirm_pending"` 且重跑次数 < [MAX_AUTO_REJECT]（3）；
 * 2. 取不到 `kind == "confirm_request"` 的事件即 `break`（不空转）；
 * 3. 每次先调 [AgentConfirmGuard.drive] 的 `rejectTool`（一次性拒绝：工具名 + 参数 JSON），再重跑；
 * 4. 重跑抛出的普通异常被吞掉、记日志后 `break`（不向上传播，与抽取前一致）；
 *    但 `CancellationException` **单独重抛**（取消必须传播，见下）。
 *
 * 取消语义（U2）：抽取前/初版用 `runCatching` 捕获重跑，会把 `CancellationException` 一并吞掉，
 * 于是已取消的作用域仍会继续往下执行。本类既接在通道侧、也接在群聊 ViewModel 的生命周期里，
 * 一旦吞掉取消，用户退出群聊后陈旧回复仍可能被投递到 UI。现在取消单独重抛、且不记 error 日志
 * （取消不是失败，不得当故障刷噪音）；其余 `Throwable` 语义逐字不变。
 *
 * 为什么不弹确认卡片：确认卡片类型 `ToolConfirmationRequest` 位于 `feature:chat`，而 feature
 * 模块之间禁止互相依赖（AGENTS.md 硬规则），搬到 core 属架构变更；群聊还可能在同回合内并发多个
 * 伴侣，逐伴侣弹卡的交互尚未设计。预授权白名单（Q4 方向）落地后已授权组合根本不会触发确认门。
 *
 * 线程 / 挂起：本类无状态、可安全复用；[drive] 在调用者的协程上下文里执行。
 */
class AgentConfirmGuard(
    private val log: AgentConfirmGuardLog = SecureLogConfirmGuardLog,
) {

    /**
     * 驱动「自动拒绝 + 重跑」直到回合不再要求确认，或到达重跑上限。
     *
     * @param tag 日志 subtag，沿用调用方既有约定（通道侧 `AgentDialogueCoordinator`、
     *   群聊侧 `GroupChatViewModel`）。
     * @param initial 调用方**已经跑过**的首回合结果（首跑失败的处置属于调用方，本类不改）。
     * @param rerunFailureMessage 重跑失败时的日志文案（companion 上下文由调用方自带）。
     * @param runTurn 重跑一个回合；返回 null 视同失败（break）。
     * @param rejectTool 拒绝待确认的工具调用 (工具名, 参数 JSON)，一次性生效。
     * @return 最终回合结果：可能已不再要求确认，也可能是到达上限 / 重跑失败时的最后一次结果。
     * @throws CancellationException 重跑被取消时**原样向上抛出**（不记日志、不计为失败，见 U2）。
     */
    suspend fun drive(
        tag: String,
        initial: AgentTurnResult,
        rerunFailureMessage: String,
        runTurn: suspend () -> AgentTurnResult?,
        rejectTool: (name: String, args: String) -> Unit,
    ): AgentTurnResult {
        var result = initial
        var confirmGuard = 0
        while (result.finishedReason == FINISHED_CONFIRM_PENDING && confirmGuard < MAX_AUTO_REJECT) {
            val pending = result.events.firstOrNull { it.kind == EVENT_CONFIRM_REQUEST } ?: break
            confirmGuard += 1
            log.w(tag, "confirm gate on non-interactive channel, auto-reject: ${pending.text}")
            rejectTool(pending.text, pending.extra)
            // 取消必须向上传播：runCatching 会把 CancellationException 一并吞掉，让已取消的作用域
            // （用户退出群聊 / 页面销毁）继续往下执行，把陈旧回复投递到 UI。取消不是「重跑失败」，
            // 因此单独重抛，且**不记 error 日志**（否则正常取消会被当成故障刷噪音）。
            val rerun = try {
                runTurn()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                log.e(tag, rerunFailureMessage, failure)
                break
            }
            result = rerun ?: break
        }
        return result
    }

    companion object {
        /** 重跑上限：与抽取前 `confirmGuard < 3` 逐字一致。 */
        const val MAX_AUTO_REJECT: Int = 3

        /** Rust 回合提前结束、等待确认的 finished_reason。 */
        const val FINISHED_CONFIRM_PENDING: String = "confirm_pending"

        /** 待确认工具调用的事件 kind（text = 工具名，extra = 参数 JSON）。 */
        const val EVENT_CONFIRM_REQUEST: String = "confirm_request"
    }
}
