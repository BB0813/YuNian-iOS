import Foundation
import GRDB
import os

/// 备份导出 —— 对应 Android `BackupExportService`。
///
/// ## 职责
/// 把本地库序列化成 `.lybk` **明文 JSON**（9 分区），再交给
/// `BackupCrypto.encrypt` 加密封装。
///
/// ## ⚠️ 格式规格**不在这里**，在 `BackupImporter.swift` 头部
/// 那份清单是从导入器 `parse()` 里逐处读取的键反推出来的 ——
/// 也就是"实际被消费的契约"。导出必须产出**同一份**规格，
/// 否则会出现"导出看着成功、Android 却读不出"的数据陷阱。
/// **改格式时先改那边，再回来改这里。**
///
/// ## 两个容易写错的地方（与规格里的标注一致）
/// 1. `chatGroups.companionIds` 是 **CSV 字符串**，不是数组。
/// 2. `companions` 的 `lorebookIdsJson` / `apiConfigId` **导入侧不读** ——
///    这里仍然照实现导出（保真），但不要以为它们会被还原。
///
/// ## 表名与列名（本文件已用 `verify_swift_sql` 在真实 SQLite 上校验）
/// ⚠️ 三张表是**单数**：`temp_memory` / `token_usage`，
/// 日记表叫 `diary_entries`（不是 `diaries`）——
/// 而 JSON 分区名是复数的 `tempMemories` / `tokenUsages` / `diaries`。
/// **表名与分区名不一致**，这是最容易搞混的一处。
enum BackupExporter {

    static let log = Logger(subsystem: "com.yunian.ai", category: "backup.export")

    enum ExportError: Error, CustomStringConvertible {
        case serializationFailed(String)

        var description: String {
            switch self {
            case let .serializationFailed(msg): return "备份序列化失败：\(msg)"
            }
        }
    }

    struct Outcome: Sendable, Equatable {
        /// 各分区写出的**记录数**（不是字节数）。
        var sectionCounts: [String: Int]
        /// 明文 JSON 字节数（加密前的体积）。
        var plaintextBytes: Int
        /// 最终 `.lybk` 容器字节数。
        var containerBytes: Int

        var totalRecords: Int { sectionCounts.values.reduce(0, +) }

        var summary: String {
            "\(totalRecords) 条 / \(sectionCounts.count) 个分区 / "
                + "明文 \(plaintextBytes) B → 容器 \(containerBytes) B"
        }
    }

    /// `.lybk` 解密后的明文 JSON 是 UTF-8 的 `[String: Any]`，
    /// 顶层键就是 9 个分区名。顺序仅影响可读性（JSON 对象无序）。
    static let sectionKeys = [
        "companions", "chatGroups", "chatMessages", "groupMessages",
        "memoryEntries", "tempMemories", "tokenUsages", "unifiedMemories", "diaries",
    ]

    // MARK: - 对外入口

    /// 生成明文 JSON（未加密）。测试与调试用。
    static func makePlaintextJSON(database: YuNianDatabase) throws -> Data {
        let root = try collectSections(database: database)
        do {
            return try JSONSerialization.data(
                withJSONObject: root,
                options: [.sortedKeys]   // 稳定输出，便于比对与测试
            )
        } catch {
            throw ExportError.serializationFailed(String(describing: error))
        }
    }

    /// 生成可写盘的 `.lybk` 容器字节。
    static func exportFile(
        database: YuNianDatabase,
        password: String
    ) throws -> (data: Data, outcome: Outcome) {
        let plain = try makePlaintextJSON(database: database)
        let container = try BackupCrypto.encrypt(plain, password: password)

        var counts: [String: Int] = [:]
        let root = try? JSONSerialization.jsonObject(with: plain) as? [String: Any]
        for key in sectionKeys {
            counts[key] = (root?[key] as? [Any])?.count ?? 0
        }
        log.info("备份导出完成：\(plain.count) B 明文 → \(container.count) B 容器")

        return (
            container,
            Outcome(sectionCounts: counts,
                    plaintextBytes: plain.count,
                    containerBytes: container.count)
        )
    }

    // MARK: - 采集

    private static func collectSections(database: YuNianDatabase) throws -> [String: Any] {
        try database.pool.read { db in
            [
                "companions": try companions(db),
                "chatGroups": try chatGroups(db),
                "chatMessages": try messages(db, type: "chat"),
                "groupMessages": try messages(db, type: "group"),
                "memoryEntries": try memoryEntries(db),
                "tempMemories": try tempMemories(db),
                "tokenUsages": try tokenUsages(db),
                "unifiedMemories": try unifiedMemories(db),
                "diaries": try diaries(db),
            ]
        }
    }

    private static func companions(_ db: Database) throws -> [[String: Any]] {
        let rows = try Row.fetchAll(db, sql: """
            SELECT id, name, avatarUrl, age, personality, backstory, speakingStyle,
                   tags, rawPrompt, systemPrompt, intimacy, lorebookIdsJson,
                   apiConfigId, createdAt, updatedAt
            FROM companions ORDER BY id ASC
            """)
        return rows.map { r in
            var o: [String: Any] = [
                "id": r["id"] as Int64? ?? 0,
                "name": r["name"] as String? ?? "",
                "personality": r["personality"] as String? ?? "",
                "intimacy": r["intimacy"] as Int? ?? 0,
                "createdAt": r["createdAt"] as Int64? ?? 0,
                "updatedAt": r["updatedAt"] as Int64? ?? 0,
            ]
            put(&o, "avatarUrl", r["avatarUrl"] as String?)
            put(&o, "age", r["age"] as Int?)
            put(&o, "backstory", r["backstory"] as String?)
            put(&o, "speakingStyle", r["speakingStyle"] as String?)
            put(&o, "tags", r["tags"] as String?)
            put(&o, "rawPrompt", r["rawPrompt"] as String?)
            put(&o, "systemPrompt", r["systemPrompt"] as String?)
            put(&o, "lorebookIdsJson", r["lorebookIdsJson"] as String?)
            put(&o, "apiConfigId", r["apiConfigId"] as Int64?)
            return o
        }
    }

    private static func chatGroups(_ db: Database) throws -> [[String: Any]] {
        let rows = try Row.fetchAll(db, sql: """
            SELECT id, name, avatarUrl, companionIds, createdAt, updatedAt
            FROM chat_groups ORDER BY id ASC
            """)
        return rows.map { r in
            var o: [String: Any] = [
                "id": r["id"] as Int64? ?? 0,
                "name": r["name"] as String? ?? "",
                // ⚠️ CSV 字符串，原样带出（导入侧按 parseIdCsv 拆）
                "companionIds": r["companionIds"] as String? ?? "",
                "createdAt": r["createdAt"] as Int64? ?? 0,
                "updatedAt": r["updatedAt"] as Int64? ?? 0,
            ]
            put(&o, "avatarUrl", r["avatarUrl"] as String?)
            return o
        }
    }

    /// 单聊与群聊共用一张 `messages` 表，靠 `conversationType` 区分；
    /// 两类在 JSON 里是**不同分区、不同字段名**（见规格）：
    /// - 单聊：`companionId` = `conversationId`
    /// - 群聊：`groupId` = `conversationId`，`companionId` = `senderId`
    private static func messages(_ db: Database, type: String) throws -> [[String: Any]] {
        let rows = try Row.fetchAll(db, sql: """
            SELECT m.id, m.conversationId, m.senderId, m.timestamp, m.isFromUser,
                   m.type, m.fileFormat, m.turnId, m.eventIndex, m.durationMs,
                   m.anchorMessageId, b.content, b.searchContent, b.linkString
            FROM messages m
            LEFT JOIN message_bodies b ON b.messageId = m.id
            WHERE m.conversationType = ?
            ORDER BY m.id ASC
            """, arguments: [type])

        let isChat = (type == "chat")
        return rows.map { r in
            let conversationId = r["conversationId"] as Int64? ?? 0
            let senderId = r["senderId"] as Int64? ?? 0
            var o: [String: Any] = [
                "id": r["id"] as Int64? ?? 0,
                "timestamp": r["timestamp"] as Int64? ?? 0,
                "isFromUser": (r["isFromUser"] as Int? ?? 0) != 0,
                "content": r["content"] as String? ?? "",
                "type": r["type"] as String? ?? "TEXT",
                "fileFormat": r["fileFormat"] as String? ?? "TEXT",
                "searchContent": r["searchContent"] as String? ?? "",
                "linkString": r["linkString"] as String? ?? "",
            ]
            if isChat {
                o["companionId"] = conversationId
            } else {
                o["groupId"] = conversationId
                o["companionId"] = senderId
            }
            // 单聊侧导入读 anchorMessageId；群聊侧不读它，故只给单聊写
            if isChat { put(&o, "anchorMessageId", r["anchorMessageId"] as Int64?) }
            put(&o, "turnId", r["turnId"] as String?)
            put(&o, "eventIndex", r["eventIndex"] as Int?)
            put(&o, "durationMs", r["durationMs"] as Int64?)
            return o
        }
    }

    private static func memoryEntries(_ db: Database) throws -> [[String: Any]] {
        let rows = try Row.fetchAll(db, sql: """
            SELECT companionId, content, category, importance, context,
                   accessCount, timestamp, lastAccessed
            FROM memory_entries ORDER BY id ASC
            """)
        return rows.map { r in
            [
                "companionId": r["companionId"] as Int64? ?? 0,
                "content": r["content"] as String? ?? "",
                "category": r["category"] as String? ?? "FACT",
                "importance": r["importance"] as Double? ?? 0.5,
                "context": r["context"] as String? ?? "",
                "accessCount": r["accessCount"] as Int? ?? 1,
                "timestamp": r["timestamp"] as Int64? ?? 0,
                "lastAccessed": r["lastAccessed"] as Int64? ?? 0,
            ]
        }
    }

    private static func tempMemories(_ db: Database) throws -> [[String: Any]] {
        let rows = try Row.fetchAll(db, sql: """
            SELECT companionId, userInput, botResponse, timestamp
            FROM temp_memory ORDER BY id ASC
            """)
        return rows.map { r in
            [
                "companionId": r["companionId"] as Int64? ?? 0,
                "userInput": r["userInput"] as String? ?? "",
                "botResponse": r["botResponse"] as String? ?? "",
                "timestamp": r["timestamp"] as Int64? ?? 0,
            ]
        }
    }

    private static func tokenUsages(_ db: Database) throws -> [[String: Any]] {
        let rows = try Row.fetchAll(db, sql: """
            SELECT companionId, date, inputTokens, outputTokens, totalTokens,
                   requestCount, timestamp
            FROM token_usage ORDER BY id ASC
            """)
        return rows.map { r in
            [
                "companionId": r["companionId"] as Int64? ?? 0,
                "date": r["date"] as String? ?? "",
                "inputTokens": r["inputTokens"] as Int64? ?? 0,
                "outputTokens": r["outputTokens"] as Int64? ?? 0,
                "totalTokens": r["totalTokens"] as Int64? ?? 0,
                "requestCount": r["requestCount"] as Int? ?? 0,
                "timestamp": r["timestamp"] as Int64? ?? 0,
            ]
        }
    }

    private static func unifiedMemories(_ db: Database) throws -> [[String: Any]] {
        let rows = try Row.fetchAll(db, sql: """
            SELECT memoryType, scope, source, content, summary, confidence,
                   importance, sourceId, createdAt, updatedAt, observedAt,
                   expiresAt, accessCount, tags
            FROM unified_memories ORDER BY id ASC
            """)
        return rows.map { r in
            var o: [String: Any] = [
                "memoryType": r["memoryType"] as String? ?? "SEMANTIC",
                "scope": r["scope"] as String? ?? "COMPANION",
                "source": r["source"] as String? ?? "CHAT",
                "content": r["content"] as String? ?? "",
                "summary": r["summary"] as String? ?? "",
                "confidence": r["confidence"] as Double? ?? 1.0,
                "importance": r["importance"] as Double? ?? 0.5,
                "sourceId": r["sourceId"] as Int64? ?? 0,
                "createdAt": r["createdAt"] as Int64? ?? 0,
                "updatedAt": r["updatedAt"] as Int64? ?? 0,
                "observedAt": r["observedAt"] as Int64? ?? 0,
                "accessCount": r["accessCount"] as Int? ?? 1,
                "tags": r["tags"] as String? ?? "",
            ]
            put(&o, "expiresAt", r["expiresAt"] as Int64?)
            return o
        }
    }

    private static func diaries(_ db: Database) throws -> [[String: Any]] {
        let rows = try Row.fetchAll(db, sql: """
            SELECT companionId, title, content, mood, date, weather, tags
            FROM diary_entries ORDER BY id ASC
            """)
        return rows.map { r in
            [
                "companionId": r["companionId"] as Int64? ?? 0,
                "title": r["title"] as String? ?? "",
                "content": r["content"] as String? ?? "",
                "mood": r["mood"] as Int? ?? 2,
                "date": r["date"] as Int64? ?? 0,
                "weather": r["weather"] as String? ?? "",
                "tags": r["tags"] as String? ?? "",
            ]
        }
    }

    /// 只写非 nil 键。导入侧对可空字段都有默认值，
    /// 但**发 null 与不发键在语义上不同** —— 发 null 会让
    /// `as? T` 落到分支外，不发键才会走到默认值。保持一致：不发。
    private static func put(_ dict: inout [String: Any], _ key: String, _ value: Any?) {
        guard let value else { return }
        dict[key] = value
    }
}
