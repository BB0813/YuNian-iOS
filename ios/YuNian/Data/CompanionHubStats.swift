import Foundation
import os

/// 角色页（角色中枢）的数字 —— **全部照引擎/列表的既有口径算**。
///
/// ## 为什么单独一个模型
/// 借鉴 Aru 的协作者页：副标题里直接写"1 条记忆 · 0 份长期资料 · 2 段旧对话"，
/// 用户不点进去就知道配了什么。但那套写法的前提是**数字必须可信** ——
/// 一旦角色页的世界书本数与引擎合成规则不一致，界面会理直气壮地骗人：
/// 显示「生效 0 本」，而实际每个回合都注入了一本。
/// 所以这里的每个数字都从既有单一来源取，并各自可单测：
///
/// | 数字 | 来源 | 口径 |
/// |---|---|---|
/// | 记忆 | `MemoryRepository.counts(companionId:)` | `COMPANION` 作用域 + 未过期 + 未删 |
/// | 世界书 | `WorldbookRuntimeSync.effectiveWorldbooks(_:companionId:)` | **引擎绑定规则**（已抽成唯一来源） |
/// | 消息/未读 | `ConversationRepository.rows()` | 与首页列表同一口径（排除 REASONING / TOOL_ACTIVITY） |
/// | 语音 | `VoiceProfileStore.voiceOverride` | 有覆盖 = 已单独指定 |
struct CompanionHubStats: Equatable {

    // 记忆
    var memoryActive: Int = 0
    var memoryDeleted: Int = 0

    // 世界书
    var worldbooksOwned: Int = 0
    /// 引擎绑定规则下"这一轮会带上"的本数。
    var worldbooksEffective: Int = 0
    /// 规则上生效，但里面没有可用条目 → 实际什么都不注入。
    /// 必须与上一条分开说，否则用户以为"显示生效就一定注入了"。
    var worldbookInjectsAnything: Bool = false

    // 会话
    var messages: Int = 0
    var unread: Int = 0

    // 语音
    var voiceOverride: String?

    // 人设摘要（"编辑人设"那一行的副标题）
    var personaSummary: String = ""

    /// 世界书那一行的副标题。
    ///
    /// 三种状态分开说（**这是这个页面最容易写错的地方**）：
    /// - 没有任何专属书 → 「没有专属世界书」
    /// - 规则上生效但不注入 → 「生效 N 本 · 但条目为空，当前不注入」
    /// - 正常 → 「生效 N 本」+（有专属书时）「共 M 本」
    var worldbookSubtitle: String {
        if worldbooksOwned == 0 && worldbooksEffective == 0 {
            return "没有专属世界书（全局书按绑定关系带入）"
        }
        if worldbooksEffective == 0 {
            return "共 \(worldbooksOwned) 本 · **当前不会带上**（都停用或不满足绑定）"
        }
        var text = "生效 \(worldbooksEffective) 本"
        if worldbooksOwned > worldbooksEffective {
            text += " · 共 \(worldbooksOwned) 本专属"
        }
        if !worldbookInjectsAnything {
            text += " · 但条目为空，当前不注入"
        }
        return text
    }

    /// 记忆那一行的副标题（软删除的也要说清楚 —— 它还能恢复）。
    var memorySubtitle: String {
        if memoryActive == 0 && memoryDeleted == 0 { return "还没有记住任何事" }
        var text = "\(memoryActive) 条参与召回"
        if memoryDeleted > 0 { text += " · \(memoryDeleted) 条已删除（可恢复）" }
        return text
    }

    /// 会话那一行的副标题。
    var conversationSubtitle: String {
        guard messages > 0 else { return "还没有聊过" }
        var text = "共 \(messages) 条"
        if unread > 0 { text += " · \(unread) 条未读" }
        return text
    }

    var voiceSubtitle: String {
        guard let voice = voiceOverride, !voice.isEmpty else { return "跟随全局音色" }
        return "已单独指定「\(voice)」"
    }

    static let log = Logger(subsystem: "com.yunian.ai", category: "companion.hub")

    /// 读一遍这个角色的全部数字。
    ///
    /// 每个来源各自容错：某一项读失败只让那一项为 0，**不让整页打不开** ——
    /// 角色页是入口页，它打开失败比少一个数字严重得多。
    static func load(database: YuNianDatabase?,
                     companion: CompanionRepository.Companion,
                     apiConfigs: ApiConfigRepository?) -> CompanionHubStats {
        var stats = CompanionHubStats()
        stats.personaSummary = personaSummary(for: companion)
        stats.voiceOverride = VoiceProfileStore.voiceOverride(companionId: companion.id,
                                                              repository: apiConfigs)

        guard let database else { return stats }

        let memoryCounts = MemoryRepository(database: database).counts(companionId: companion.id)
        stats.memoryActive = memoryCounts.active
        stats.memoryDeleted = memoryCounts.deleted

        let worldbooks = WorldbookRuntimeSync.effectiveWorldbooks(database, companionId: companion.id)
        stats.worldbooksEffective = worldbooks.count
        stats.worldbookInjectsAnything = worldbooks.injectsAnything
        stats.worldbooksOwned = (try? WorldbookRepository(database: database).list())?
            .filter { $0.companionId == companion.id }.count ?? 0

        // 与首页会话列表同一口径：整表取一次再挑（列表本身就带 messageCount）
        if let row = (try? ConversationRepository(database: database).rows())?
            .first(where: { $0.companionId == companion.id }) {
            stats.messages = row.messageCount
            stats.unread = (try? ConversationRepository(database: database)
                .unreadCount(companionId: companion.id)) ?? 0
        }
        return stats
    }

    /// 人设摘要：只写**用户填过的东西**，不编。
    static func personaSummary(for companion: CompanionRepository.Companion) -> String {
        var parts: [String] = []
        if let age = companion.age { parts.append("\(age) 岁") }
        let personality = companion.personality.trimmingCharacters(in: .whitespacesAndNewlines)
        if !personality.isEmpty { parts.append(personality) }
        if let style = companion.speakingStyle, !style.isEmpty { parts.append("风格：\(style)") }
        if parts.isEmpty { return "还没写人设" }
        let joined = parts.joined(separator: " · ")
        return joined.count > 40 ? String(joined.prefix(40)) + "…" : joined
    }
}
