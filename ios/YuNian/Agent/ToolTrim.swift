import Foundation

/// 按**这一轮的消息内容**裁掉用不到的工具组（第 242 轮）。
///
/// ## 为什么需要它（有真机数据支撑）
///
/// 账本量出来：一轮输入 2,951 tok，其中 **工具 Schema 占 62%（1,823 tok）** ——
/// 比系统提示 + 消息窗口 + 技能加起来还多。拆开看：
///
/// | 组 | 工具 | 成本 |
/// |---|---|---|
/// | chat | emit_bubble / emit_segmented / send_sticker | 626 tok（每轮必需）|
/// | memory | recall / save / consolidate | 574 tok |
/// | device | 6 个 device_* | 379 tok |
/// | 其它 | load_skill / core_status | 250 tok |
///
/// 而一句「今天好累啊」根本用不到 `device_set_clipboard` 或 `consolidate_memory`。
/// 这些工具每轮都在付费，却绝大多数轮次不会被调用。
///
/// ## 设计原则（**只裁"明显不需要"的，绝不猜**）
///
/// 1. **chat 组永远保留** —— 回复/分段/表情是产品的地基，裁掉就不是聊天了。
/// 2. **memory 组永远保留** —— 「记住我喜欢猫」和「我今天很累」在字面上无法区分，
///    猜错一次就等于永久丢失一条记忆。这类**不可逆**的漏判必须偏保守。
/// 3. **device 组按关键词裁** —— 设备动作有很强的字面特征（"剪贴板""电量""打开链接"），
///    误判代价也只是"这一轮用不了"，下一轮就回来了，**可逆**。
/// 4. **宁可多留** —— 命中任何一个关键词就保留整组。省 379 tok 不值得让用户
///    在真要用时发现工具没了。
///
/// ## 为什么不在 Rust 里做
///
/// Rust 的 `toolsets` 是**静态归属**，它不知道"这一轮用户说了什么"。
/// 而 `request.tools` 本来就是一个**由宿主决定的列表**（iOS 已经用它做门控），
/// 在这里裁剪不改变任何协议、不需要 ABI 变更、也不影响 Android。
enum ToolTrim {

    /// 判断结果（带上原因，供收据/账本解释"这轮为什么少了几个工具"）。
    struct Decision: Equatable {
        /// 要裁掉的工具集名（与 `ToolDefinition.toolsets` 同一套字符串）。
        let droppedToolsets: Set<String>
        /// 命中的关键词（空 = 没有任何设备类意图）。
        let matchedCues: [String]

        var isTrimming: Bool { !droppedToolsets.isEmpty }

        /// 给用户看的一句话（放在账本"没带上"那一栏）。
        var reason: String {
            if droppedToolsets.isEmpty {
                return "这一轮没有可裁的工具组"
            }
            if matchedCues.isEmpty {
                return "消息里没有设备类意图，这一轮不下发设备工具"
            }
            return "消息提到「\(matchedCues.prefix(3).joined(separator: "、"))」，保留设备工具"
        }
    }

    /// 会被裁剪的工具集 —— **只含 device**。
    ///
    /// 刻意不包含 `chat`（地基）和 `memory`（不可逆）。要加新组时，
    /// 先问自己：**猜错的代价可逆吗？** 不可逆就别加。
    static let trimmableToolsets: Set<String> = ["device"]

    /// 设备类意图的关键词。
    ///
    /// 覆盖"要看/要改设备状态"和"要跳出 App"两类。宁可宽一点：
    /// 多留一组只花 379 tok，漏掉一组会让用户觉得"它突然变笨了"。
    static let deviceCues: [String] = [
        // 时间 / 电量 / 状态
        "几点", "时间", "日期", "今天几号", "星期", "电量", "电池", "充电", "剩余",
        // 剪贴板
        "剪贴板", "复制", "粘贴", "复制下来", "贴过来",
        // 通知
        "提醒我", "通知我", "定个闹钟", "闹钟", "待办", "别忘了",
        // 打开外部
        "打开链接", "打开这个", "浏览器", "搜一下", "搜索", "网址", "http",
        // 泛化的"帮我操作设备"
        "手机上", "我的手机", "设备",
    ]

    /// 按这一轮的用户输入决定裁哪些工具组。
    ///
    /// - Parameter userText: 用户这一轮说的话（不含历史 —— 只按"现在要什么"判断）
    static func decide(userText: String) -> Decision {
        let text = userText.lowercased()
        let matched = deviceCues.filter { text.contains($0.lowercased()) }

        // 命中任何设备类线索 → 整组保留（可逆的省，不值得赌）
        if !matched.isEmpty {
            return Decision(droppedToolsets: [], matchedCues: matched)
        }
        return Decision(droppedToolsets: trimmableToolsets, matchedCues: [])
    }

    /// 把裁剪结果叠加到用户的手动设置上。
    ///
    /// ⚠️ **只做并集，不做覆盖**：用户手动关掉的组（`settings.hiddenToolsets`）
    /// 必须继续生效 —— 自动裁剪只是"额外少给一点"，绝不能把用户关掉的东西放回来。
    static func merge(_ decision: Decision, into settings: ToolSettings) -> ToolSettings {
        guard decision.isTrimming else { return settings }
        var merged = settings
        merged.hiddenToolsets.formUnion(decision.droppedToolsets)
        return merged
    }
}
