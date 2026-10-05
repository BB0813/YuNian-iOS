import Foundation
import GRDB

/// `sticker_pick` 工具宿主回调 —— Rust `builtin_send_sticker` 的预选入口。
///
/// ## 契约（来自 agent.rs:500-520，**不是猜的**）
/// Rust 调 `tool_host.execute("sticker_pick", {"tags":[…]}, {"companion_id":…,"group_id":…})`。
/// 宿主必须返回 JSON，且：
///   - `ok` 必须为 `true`，否则 Rust 视为预选失败
///   - `entryId` 必须存在且能转 u64，否则同样视为失败
///   - `fileName` / `description` 可选，用于回灌模型"实际发了哪张"
///
/// ## 预选失败的后果（agent.rs:455-459，重要）
/// 表情库为空时 Rust 的 `allow_passthrough = false` —— 预选失败就会
/// **直接告诉模型"没有任何可用表情包，改用文字"**，而不是放行一张默认图。
/// 这是为了修真机上的"空库恒发同一张默认表情"问题。
/// 所以这里宁可返回 `{"ok":false}`，也不要编一个 entryId 上去。
///
/// ## 与 Android 的差异（记录在案）
/// Android 用 `StickerPreferenceEngine`（加权随机 + 使用计数/时间衰减）。
/// 这里用确定性挑选：命中标签数 desc → 越久没用越优先。
/// 理由：iOS 侧引擎参数虽已在 `StickerTagProvider.defaultParams()` 逐字对齐，
/// 但"加权随机"会让同一 tags 每次发不同的图，难以稳定复现问题；
/// 且本轮目标是打通链路，不是复刻推荐算法。差异已显式记录，不假装等价。
struct StickerToolBridge {

    private let database: YuNianDatabase

    init(database: YuNianDatabase) {
        self.database = database
    }

    /// 处理一次 `sticker_pick`。
    ///
    /// - Parameter argumentsJson: `{"tags":["开心","委屈"]}`
    /// - Returns: `{"ok":true,"entryId":…,"fileName":…,"description":…}` 或 `{"ok":false}`
    func pick(argumentsJson: String) -> String {
        guard let data = argumentsJson.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = obj["tags"] as? [String]
        else {
            return Self.failure()
        }

        let wanted = raw.map(Self.normalizeTag).filter { !$0.isEmpty }
        if wanted.isEmpty { return Self.failure() }

        do {
            // 取出全部表情，按命中标签数排序（同数则越久没用越优先）
            let rows = try database.pool.read { db in
                try Row.fetchAll(db, sql: """
                    SELECT id, fileName, description, tags, lastUsedAt
                    FROM sticker_entries
                    """)
            }
            var best: (score: Int, lastUsed: Int64, row: Row)?
            for row in rows {
                let entryTags = Set(
                    (row["tags"] as String? ?? "")
                        .split(separator: ",")
                        .map { Self.normalizeTag(String($0)) }
                        .filter { !$0.isEmpty }
                )
                let score = wanted.filter { entryTags.contains($0) }.count
                if score == 0 { continue }
                let lastUsed = (row["lastUsedAt"] as Int64?) ?? 0
                if let cur = best {
                    // 分数高的赢；同分时 lastUsed 小的（更久没用）赢
                    if score < cur.score { continue }
                    if score == cur.score && lastUsed >= cur.lastUsed { continue }
                }
                best = (score, lastUsed, row)
            }

            guard let chosen = best else { return Self.failure() }
            let entryId = chosen.row["id"] as Int64? ?? 0
            if entryId <= 0 { return Self.failure() }

            // 记录一次「模型使用」，与 Android recordUsage 的计数列语义一致
            try? database.pool.write { db in
                try db.execute(
                    sql: """
                        UPDATE sticker_entries
                        SET modelUsageCount = modelUsageCount + 1, lastUsedAt = ?
                        WHERE id = ?
                        """,
                    arguments: [Int64(Date().timeIntervalSince1970 * 1000), entryId])
            }

            var out: [String: Any] = [
                "ok": true,
                "entryId": entryId,
            ]
            if let fn = chosen.row["fileName"] as String?, !fn.isEmpty { out["fileName"] = fn }
            if let d = chosen.row["description"] as String?, !d.isEmpty { out["description"] = d }
            let json = try JSONSerialization.data(withJSONObject: out)
            return String(data: json, encoding: .utf8) ?? Self.failure()
        } catch {
            return Self.failure()
        }
    }

    // MARK: - 标签归一化

    /// 与 Rust `agent.rs:525 clean_sticker_description` 同口径:
    /// 去空白 + 去标点 + 截断 20 + 小写。
    ///
    /// ⚠️ 两侧必须一致，否则 Rust 送来的 tags 与库里 tags 对不上，
    /// 表现为"模型说发了、设备上没动静"且不报错。
    static func normalizeTag(_ raw: String) -> String {
        var out = ""
        for ch in raw {
            // ⚠️ 第 120 轮：Swift 的 Character 没有 `isspace`，
            // 是 `isWhitespace`（CI 报 "value of type 'Character' has no member
            // 'isspace'"）—— 又一次凭印象写属性名。
            if ch.isWhitespace { continue }
            if ch.isPunctuation || ch.isSymbol { continue }
            out.append(ch)
            if out.count >= 20 { break }
        }
        return out.lowercased()
    }

    private static func failure() -> String {
        #"{"ok":false}"#
    }
}
