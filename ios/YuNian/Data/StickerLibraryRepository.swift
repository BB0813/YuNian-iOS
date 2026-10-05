import Foundation
import GRDB

/// 表情库读侧 —— 补齐 `sticker_entries` / `sticker_tags` 的消费方。
///
/// ## 为什么需要（第 113 轮）
/// v45 schema 里这两张表一直都在（`YuNianSchema.swift:61-62`），
/// `StickerTagProvider` 也读 `sticker_tags` 做 Top-N 兜底。
/// 但**用户侧没有任何界面能看到表情**——全新安装下表是空的，
/// 用户既不知道"表情从哪来"，也无从判断到底有没有。
///
/// 本仓库只做**读**：列出已导入的表情与标签统计。
/// 导入入口需要相册/文件选择器，属另一件事，未在此范围内。
struct StickerLibraryRepository {

    struct Entry: Identifiable, Equatable {
        let id: Int64
        var fileName: String
        var tags: [String]
        var embeddedText: String?
        var source: String
        var userUsageCount: Int
        var modelUsageCount: Int
        var lastUsedAt: Int64?

        /// 展示用主标题：优先 description/文件名。
        var displayName: String { fileName }
    }

    struct TagStat: Identifiable, Equatable {
        var id: String { tag }
        let tag: String
        let stickerCount: Int
    }

    private let database: YuNianDatabase

    init(database: YuNianDatabase) {
        self.database = database
    }

    /// 全部表情，按最近使用 → 创建时间倒序。
    func entries() throws -> [Entry] {
        try database.pool.read { db in
            let rows = try Row.fetchAll(db, sql: """
                SELECT id, fileName, tags, embeddedText, source,
                       userUsageCount, modelUsageCount, lastUsedAt
                FROM sticker_entries
                ORDER BY lastUsedAt IS NULL, lastUsedAt DESC, id DESC
                """)
            return rows.map { row in
                Entry(
                    id: row["id"],
                    fileName: row["fileName"],
                    tags: splitTags(row["tags"]),
                    embeddedText: row["embeddedText"],
                    source: row["source"],
                    userUsageCount: row["userUsageCount"],
                    modelUsageCount: row["modelUsageCount"],
                    lastUsedAt: row["lastUsedAt"]
                )
            }
        }
    }

    /// 标签聚合，按图片数降序（与 Android `StickerTagDao` 的 ORDER BY 一致）。
    func tagStats() throws -> [TagStat] {
        try database.pool.read { db in
            let rows = try Row.fetchAll(db, sql: """
                SELECT tag, stickerCount FROM sticker_tags
                ORDER BY stickerCount DESC, tag ASC
                """)
            return rows.map { TagStat(tag: $0["tag"], stickerCount: $0["stickerCount"]) }
        }
    }

    /// 一并取两项，供界面一次渲染。
    func snapshot() throws -> (entries: [Entry], tags: [TagStat]) {
        (try entries(), try tagStats())
    }

    /// `tags` 列是逗号分隔文本（Android 同格式）。
    private func splitTags(_ raw: String?) -> [String] {
        guard let raw, !raw.isEmpty else { return [] }
        return raw.split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }
}
