import Foundation
import GRDB
import os

/// 可用表情标签 —— 对应 Android `StickerPreferenceFacade.availableTagsWithFallback`。
///
/// ## 为什么需要（**活需求，此前曾被误判为死路径**）
/// 这些标签经 `AgentGlobalConfig.stickers` 下发给 Rust，最终进
/// `ToolContext.available_sticker_tags`，由 `builtin_send_sticker`
/// 做**精确匹配校验**与「无匹配报告」展示。
///
/// 而内置工具（`send_sticker` / `emit_bubble` / `emit_segmented`）**不受**
/// `request.tools` 门控、永远注入模型（`agent.rs::available_tool_definitions`
/// 的拼接以 `builtin_tool_definitions()` 开头）。所以 iOS 上模型**能**调用
/// `send_sticker`，这个标签表就不是可选项。
final class StickerTagProvider {

    private let database: YuNianDatabase
    private let stores: AgentStores
    private let log = Logger(subsystem: "com.yunian.ai", category: "sticker.tags")

    /// Rust 侧偏好引擎（惰性创建；参数与 Android `defaultParams()` 逐字一致）。
    private var selector: StickerPreferenceEngine?

    init(database: YuNianDatabase, stores: AgentStores) {
        self.database = database
        self.stores = stores
    }

    /// 文档 §8 参数表默认值 —— 逐字对应 Android `StickerPreferenceFacade.defaultParams()`。
    private static func defaultParams() -> StickerPreferenceParams {
        StickerPreferenceParams(
            decayHalfLifeMs: 30 * 24 * 60 * 60 * 1000,
            n0: 20,
            n1: 200,
            exploreEpsilon: 0.05,
            alpha: 1.0,
            beta: 1.0,
            gamma: 0.5,
            driftWindow: 100,
            driftThreshold: 0.15,
            maxClassSize: 5,
            sampleSeed: 20260810
        )
    }

    /// 取（惰性创建）偏好引擎。对应 Android 的 `engine(context)`。
    private func engine() -> StickerPreferenceEngine {
        if let selector { return selector }
        let created = StickerPreferenceEngine(store: stores, params: Self.defaultParams())
        selector = created
        return created
    }

    /// 偏好先验 Top-N。对应 Android `availableTags(context, topN)`：
    /// `tagPrior.entries.sortedByDescending { value }.map { key }.take(topN.coerceAtLeast(0))`
    func availableTags(topN: Int) -> [String] {
        let prior = engine().snapshot().tagPrior
        // ⚠️ 排序稳定性：Kotlin 的 sortedByDescending 对相同 value 保持原顺序，
        // 而 HashMap 的顺序不定。两端若对同值标签给出不同顺序，模型看到的清单顺序不同。
        // 这里按 (-value, key) 排序，使结果**确定性**可复现 —— 比照搬不确定性更稳。
        return prior
            .sorted { lhs, rhs in
                lhs.value == rhs.value ? lhs.key < rhs.key : lhs.value > rhs.value
            }
            .prefix(max(topN, 0))
            .map(\.key)
    }

    /// 完整入口。对应 Android `availableTagsWithFallback`：
    /// 先试偏好先验，为空时兜底查 `sticker_tags`（按图片数降序 → tag 升序）。
    func tagsWithFallback(topN: Int = 30) -> [String] {
        let fromPrior = availableTags(topN: topN)
        if !fromPrior.isEmpty { return fromPrior }

        do {
            let tags = try database.pool.read { db -> [String] in
                try String.fetchAll(db, sql: """
                SELECT tag FROM sticker_tags
                ORDER BY stickerCount DESC, tag ASC
                """)
            }
            return Array(tags.prefix(max(topN, 0)))
        } catch {
            log.error("sticker_tags 兜底查询失败：\(String(describing: error), privacy: .public)")
            return []
        }
    }
}
