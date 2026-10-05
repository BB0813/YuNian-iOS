import Foundation
import GRDB
import CryptoKit

/// 表情导入 —— 对应 Android `StickerPreferenceFacade.syncMetadataFromFilesystem`
/// (core/agent/.../sticker/StickerPreferenceFacade.kt:177-285)。
///
/// ## 为什么必须逐字对齐
/// `sticker_entries.tags` 是 Rust `builtin_send_sticker` 做精确匹配的唯一依据
/// （经 `settings.stickers` → `ToolContext.available_sticker_tags` 下发）。
/// 标签规则若与 Android 不一致，后果是"模型说发了表情、设备上却没动静"，
/// 且不报错。
///
/// 对齐点：
/// 1. `fileName` = `custom_<ms>_<0..999>.ext`（Android L366 同格式）
/// 2. `hash` = 文件内容 SHA-256，**按 hash 去重**（唯一索引 idx_sticker_entries_hash）
/// 3. 有用户标签 → `normalizeTags`（trim / 去空 / 去重 / ≤3 / 逗号拼接）
///    无用户标签 → `deriveTags`（description 按 `,，、/;； ` 拆分 …；空则用去扩展名的 fileName）
/// 4. `source` = `"imported"`
/// 5. 导入后重建 `sticker_tags` 聚合表（Android 同结论，见 rebuildTagStats）
struct StickerImportRepository {

    /// Android `StickerManager.MAX_IMPORTED_COUNT`
    private static let maxImportedCount = 500

    private let database: YuNianDatabase

    init(database: YuNianDatabase) {
        self.database = database
    }

    enum ImportError: Error, Equatable, CustomStringConvertible {
        case notImage
        case duplicate
        case limitReached
        case writeFailed(String)

        var description: String {
            switch self {
            case .notImage:
                return "无法识别的图片格式（仅支持 png/jpg/gif/webp）"
            case .duplicate:
                return "已有相同内容的表情"
            case .limitReached:
                return "自定义表情已达上限 \(StickerImportRepository.maxImportedCount) 个，请先清理"
            case let .writeFailed(msg):
                return "保存失败：\(msg)"
            }
        }
    }

    /// 把已拷贝到 stickers 目录之外的一份图片数据落库。
    ///
    /// - Parameters:
    ///   - data: 图片原始字节（由调用方从 PHPickers 结果读出）
    ///   - fileName: 目标文件名（调用方按 `custom_<ms>_<rand>.<ext>` 生成，
    ///     以便在保存前就能展示给用户）
    ///   - description: 表情名（strip 后为空则回落 fileName 去扩展名）
    ///   - userTags: 用户手填的别名/标签；空则走 deriveTags
    func importSticker(
        data: Data,
        fileName: String,
        description: String,
        userTags: [String]
    ) throws -> StickerLibraryRepository.Entry {
        let dir = try AppPaths.stickersDirectory()
        let destURL = dir.appendingPathComponent(fileName)

        // 先写文件：hash 要按文件内容算（Android 也是 sha256(file)）
        do {
            try data.write(to: destURL, options: .atomic)
        } catch {
            throw ImportError.writeFailed(String(describing: error))
        }

        let hash = Self.sha256Hex(fileURL: destURL)

        let trimmedDescription = description.trimmingCharacters(in: .whitespacesAndNewlines)
        let tags: String
        if userTags.isEmpty {
            tags = Self.deriveTags(description: trimmedDescription, fileName: fileName)
        } else {
            tags = Self.normalizeTags(userTags)
        }

        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let fileSize = Int64(data.count)

        do {
            let entryId: Int64 = try database.pool.write { db in
                // 上限检查（Android L333：entries.size >= MAX → 拒绝）
                let count = try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM sticker_entries") ?? 0
                if count >= Self.maxImportedCount {
                    throw ImportError.limitReached
                }
                // 按 hash 去重（Android L197 的 dao.getByHash）
                let existing = try Int.fetchOne(
                    db, sql: "SELECT id FROM sticker_entries WHERE hash = ? LIMIT 1",
                    arguments: [hash])
                if existing != nil {
                    throw ImportError.duplicate
                }
                // ⚠️ 第 116 轮：GRDB 6 的 `db.execute` 返回 **Void**，
                // 拿不到插入 id —— CI 报 "cannot convert value of type '()' to
                // expected argument type 'Int64'"。正确写法是本仓既有惯例：
                // execute 之后读 `db.lastInsertedRowID`（BackupImporter.swift:112
                // 等处四例一致）。
                try db.execute(
                    sql: """
                        INSERT INTO sticker_entries
                          (description, hash, tags, fileName, source, fileSize,
                           userUsageCount, modelUsageCount, createdAt, lastUsedAt)
                        VALUES (?,?,?,?,?,?,0,0,?,?)
                        """,
                    arguments: [
                        trimmedDescription, hash, tags, fileName, "imported", fileSize, now, now,
                    ])
                return db.lastInsertedRowID
            }
            // 导入后重建聚合表，与 Android rebuildTagStats 同结论
            try rebuildTagStats()
            return StickerLibraryRepository.Entry(
                id: entryId,
                fileName: fileName,
                tags: Self.splitTags(tags),
                embeddedText: nil,
                source: "imported",
                userUsageCount: 0,
                modelUsageCount: 0,
                lastUsedAt: now
            )
        } catch let error as ImportError {
            // 落库失败要回滚文件，避免"文件在、库没有"的孤儿
            try? FileManager.default.removeItem(at: destURL)
            throw error
        } catch {
            try? FileManager.default.removeItem(at: destURL)
            throw ImportError.writeFailed(String(describing: error))
        }
    }

    /// 从 `sticker_entries` 全量重建 `sticker_tags`（对应 Android rebuildTagStats L249）。
    func rebuildTagStats() throws {
        try database.pool.write { db in
            try db.execute(sql: "DELETE FROM sticker_tags")
            // Android 按 fileName 维度聚合；这里按 tags 拆分后计数
            let rows = try Row.fetchAll(
                db, sql: "SELECT tags FROM sticker_entries")
            var counts: [String: Int] = [:]
            for row in rows {
                for tag in Self.splitTags(row["tags"]) {
                    counts[tag, default: 0] += 1
                }
            }
            for (tag, count) in counts {
                try db.execute(
                    sql: "INSERT OR REPLACE INTO sticker_tags (tag, stickerCount) VALUES (?,?)",
                    arguments: [tag, count])
            }
        }
    }

    // MARK: - 与 Kotlin 逐字对应的两个纯函数

    /// Kotlin L265：trim / 去空 / 去重 / ≤3 / 逗号拼接
    static func normalizeTags(_ tags: [String]) -> String {
        var seen = Set<String>()
        var out: [String] = []
        for raw in tags {
            let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if t.isEmpty || seen.contains(t) { continue }
            seen.insert(t)
            out.append(t)
            if out.count == 3 { break }      // Kotlin take(3)
        }
        return out.joined(separator: ",")
    }

    /// Kotlin L275：description 按 `[,，、/;； ]` 拆分；空则 fileName 去扩展名。
    static func deriveTags(description: String, fileName: String) -> String {
        let base = description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? (fileName as NSString).deletingPathExtension
            : description
        if base.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return "" }

        let separators = CharacterSet(charactersIn: ",，、/;； ")
        var seen = Set<String>()
        var out: [String] = []
        for raw in base.components(separatedBy: separators) {
            let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if t.isEmpty || seen.contains(t) { continue }
            seen.insert(t)
            out.append(t)
            if out.count == 3 { break }
        }
        return out.joined(separator: ",")
    }

    /// Kotlin L279 的分隔符集合（含全角逗号/分号/空格）—— 供测试核对。
    static let tagSeparators = CharacterSet(charactersIn: ",，、/;； ")

    // MARK: - 工具

    /// 流式 SHA-256（Android L288 同算法，8KB buffer）。
    static func sha256Hex(fileURL: URL) -> String {
        guard let handle = try? FileHandle(forReadingFrom: fileURL) else { return "" }
        defer { try? handle.close() }
        var hasher = SHA256()
        while autoreleasepool(invoking: {
            let chunk = handle.readData(ofLength: 8192)
            if chunk.isEmpty { return false }
            hasher.update(data: chunk)
            return true
        }) {}
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    static func splitTags(_ raw: String?) -> [String] {
        guard let raw, !raw.isEmpty else { return [] }
        return raw.split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }
}
