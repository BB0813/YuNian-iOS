package com.lianyu.ai.domain

/**
 * 自动化到点检查回调。
 *
 * 由 feature:automation 实现、app 注册到 [ServiceRegistry]，
 * 常驻保活服务（feature:notification）周期性调用 [onTick]，
 * 作为 WorkManager 在 Doze 下延迟触发时的兜底路径。
 *
 * 保持 feature 隔离：notification 只依赖本接口，不依赖 automation 模块。
 */
interface AutomationTickProvider {
    /** 检查并执行所有已到点的自动化任务（幂等：已执行的跳过）。 */
    suspend fun onTick()
}
