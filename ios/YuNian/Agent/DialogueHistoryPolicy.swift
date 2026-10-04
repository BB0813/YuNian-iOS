import Foundation

/// 对话历史清洗策略 —— 与 Android `AiDialogueHistoryPolicy.sanitizeForModel` 等价。
///
/// ## 为什么需要它
/// Rust 的注释两处明确说明：`history_json` 是「Kotlin 侧 `AiDialogueHistoryPolicy` 产物」
/// （`agent.rs:1059`、`native_gateway.rs:12`）。也就是说**模型看到的历史是清洗过的**，
/// 不是原始消息列表。iOS 侧原先直接全量映射，跳过了整层清洗。
///
/// ## 三层处理（与 Kotlin 逐条对应）
/// 1. **角色归一化**（`normalizeRole`）
/// 2. **过滤**：
///    - 去掉零宽空格 `\u200B` 后为空的 → 丢
///    - 操作性消息（toast / 错误提示 / 系统噪声，见 `AiOperationalMessages`）→ 丢
///    - 内容是 `[工具调用结果]` 但角色不是 TOOL 的 → 只保留 USER 的
/// 3. **合并**相邻的同一角色（TOOL 除外）→ `trimEnd + "\n" + trimStart`
///
/// ## ⚠️ 一个必须照抄的细节
/// 过滤判断用的是**清洗后的** content（去零宽空格并 trim），
/// 但**保留下来的消息仍带原始 content**。
/// 例如 `"\u200Bhello"`：判空用的是 `"hello"`（非空 → 保留），
/// 但列表里那条消息内容依然是 `"\u200Bhello"`。
/// 把消息内容也替换成清洗后的版本会改变发给模型的内容。
enum DialogueHistoryPolicy {

    // MARK: - 操作性消息（对应 AiOperationalMessages）

    /// `[TOAST]` 前缀。用于把「toast 化」的错误提示也纳入识别。
    private static let toastPrefix = "[TOAST]"

    /// 完全匹配才算操作性的固定文案。
    private static let exactOperational: Set<String> = [
        "请先配置API：我 → API设置 → 添加密钥",
        "请先配置并启用可用的API。在「我」->「API设置」中添加密钥并测试连接。",
        "请先配置并启用可用的API。",
        "模型名未配置，请在「API设置」中重新测试连接以自动选择模型。",
        "模型名未配置。",
        "系统正在加载伴侣信息，请稍后再试",
        "抱歉，找不到角色信息。",
        "抱歉，我无法继续这个话题。",
    ]

    /// 前缀匹配即算操作性的文案。
    private static let prefixOperational: [String] = [
        "请先配置API",
        "请先配置并启用可用的API",
        "模型名未配置",
        "系统正在加载",
        "API返回空内容",
        "网络连接超时",
        "API认证失败",
        "请求过于频繁",
        "图片识别",
        "视觉识别功能已关闭",
        "API密钥为空",
        "API地址为空",
        "当前模型不支持视觉",
        "账号已被封禁",
        "内容违规",
        "内容已拦截",
        "安全检查异常",
        "消息队列已满",
        "消息发送失败",
        "回复被打断",
        "发送失败",
    ]

    private static let operationalPrefixesExtra = ["工具执行失败", "工具执行超时"]

    static func stripToastPrefix(_ raw: String) -> String {
        raw.hasPrefix(toastPrefix)
            ? String(raw.dropFirst(toastPrefix.count)).trimmingCharacters(in: .whitespacesAndNewlines)
            : raw
    }

    static func isToastPrefixed(_ raw: String) -> Bool { raw.hasPrefix(toastPrefix) }

    /// 对应 `AiOperationalMessages.isOperationalContent`。
    ///
    /// 空内容返回 **false**（不是操作性内容，只是空）——
    /// 这一点与直觉相反，但 Kotlin 明确 `if (text.isEmpty()) return false`。
    static func isOperationalContent(_ content: String) -> Bool {
        let text = stripToastPrefix(content).trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return false }
        if exactOperational.contains(text) { return true }
        if prefixOperational.contains(where: { text.hasPrefix($0) }) { return true }
        if operationalPrefixesExtra.contains(where: { text.hasPrefix($0) }) { return true }
        return false
    }

    // MARK: - 主入口

    static func sanitizeForModel(_ history: [AgentHistoryMessage]) -> [AgentHistoryMessage] {
        if history.isEmpty { return [] }

        let filtered = history
            .map(normalizeRole)
            .filter { message in
                // 判断用**清洗后**的 content
                let content = message.content
                    .replacingOccurrences(of: "\u{200B}", with: "")
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                if content.isEmpty { return false }
                if isOperationalContent(content) { return false }
                // `[工具调用结果]` 但角色不是 TOOL → 只保留 USER
                if content.hasPrefix("[工具调用结果]") && message.role != .tool {
                    return message.role == .user
                }
                return true
            }

        if filtered.isEmpty { return [] }

        var merged: [AgentHistoryMessage] = []
        for message in filtered {
            if let last = merged.last,
               last.role == message.role,
               last.role != .tool {
                // 相邻同角色合并：trimEnd + "\n" + trimStart
                let combined = trimEnd(last.content) + "\n" + trimStart(message.content)
                merged[merged.count - 1].content = combined
            } else {
                merged.append(message)
            }
        }
        return merged
    }

    // MARK: - 内部

    /// 角色归一化。
    ///
    /// Kotlin 版还要从 `isFromUser` 推断角色（`msg.role != null -> msg.role`）；
    /// iOS 的 `AgentHistoryMessage.role` **非可选**，`historyForRequest()` 总是显式设置，
    /// 因此这里恒为 `msg.role`。保留此函数是为了让两处结构对齐、
    /// 以及将来支持从 DB 行还原历史时不用改调用点。
    static func normalizeRole(_ message: AgentHistoryMessage) -> AgentHistoryMessage {
        message
    }

    private static func trimEnd(_ s: String) -> String {
        var end = s.endIndex
        while end > s.startIndex {
            let prev = s.index(before: end)
            guard s[prev].isWhitespace else { break }
            end = prev
        }
        return String(s[s.startIndex..<end])
    }

    private static func trimStart(_ s: String) -> String {
        var start = s.startIndex
        while start < s.endIndex, s[start].isWhitespace { start = s.index(after: start) }
        return String(s[start..<s.endIndex])
    }
}
