package com.lianyu.ai.domain.timeline

/**
 * 时间线事件种类。
 *
 * 扩展方式：新增枚举值 + 独立 [TimelinePayload] 实现 + Codec/CommitRule/Projector 注册。
 * 禁止在既有 kind 的实现里塞入无关分支。
 */
enum class TimelineEventKind {
    /** 思考过程（特殊聊天行；默认不进模型上下文） */
    REASONING,

    /** 助手正文（可分段多条） */
    ASSISTANT_TEXT,

    /** 工具调用请求（预留，Agent） */
    TOOL_CALL,

    /** 工具执行结果（预留，Agent） */
    TOOL_RESULT,

    /** 系统/游戏等互动事件（预留） */
    SYSTEM_EVENT,
}

/**
 * 事件生命周期状态。
 *
 * 性能约束：STREAMING 仅存在于内存 PendingTurn，默认不落库。
 */
enum class TimelineEventStatus {
    STREAMING,
    COMPLETE,
    FAILED,
    CANCELLED,
}

/**
 * 对用户/调试的可见性（与是否进模型上下文解耦）。
 */
enum class TimelineVisibility {
    /** 用户可见（仍受展示开关约束） */
    USER,

    /** 仅调试 */
    DEBUG,

    /** 永不展示 */
    INTERNAL,
}
