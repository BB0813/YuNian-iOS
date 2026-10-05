import Foundation
import GRDB
import os

/// 备份导入 —— 对应 Android `BackupImportService`。
///
/// ## ⚠️ 与 Android 的实质差异：iOS 侧消息不加密
/// Android 的 `message_bodies.content` 是 `ChatMessageCrypto` 密文
/// （AndroidKeyStore 硬件密钥、不可导出），因此它的导入是「解密 → 重加密」两段。
/// iOS 侧 `message_bodies` 存**明文**（iOS 设计如此，见数据库层），
/// 所以这里是「明文直写」。这是**刻意差异**，但它让 iOS 的备份文件
/// 在设备未解锁时可读性不同于 Android —— 记录在案。
///
/// ## 三条从 Android 读出来、极易做错的语义
/// 1. **不保留 ID，按名字重映射**：伴侣/群组按 name 匹配已有记录并复用其 ID，
///    否则新建并记入映射表；所有子记录（消息/记忆）经映射表改写 ID。
/// 2. **`deviceId` 改写成导入设备的 ID**：`deviceId = deviceId`（本机）。
///    若沿用快照里的源设备 ID，导入的记忆将无法通过 `listMemories` 的
///    deviceId 过滤 —— 表现是「导入了但召不回」。
/// 3. **`anchorMessageId` 必须二阶段**：先插完所有消息、映射表齐全后，
///    才能回填锚点；边插边填会大面积丢失。
final class BackupImporter {

    private let database: YuNianDatabase
    private let log = Logger(subsystem: "com.yunian.ai", category: "backup.import")

    init(database: YuNianDatabase) {
        self.database = database
    }

    struct Result: Sendable, Equatable {
        var companionsCreated: Int = 0
        var companionsMatched: Int = 0
        var groupsCreated: Int = 0
        var chatMessagesInserted: Int = 0
        var groupMessagesInserted: Int = 0
        var memoriesInserted: Int = 0
        var tempMemoriesInserted: Int = 0
        var tokenUsagesInserted: Int = 0
        var unifiedMemoriesInserted: Int = 0
        var diariesInserted: Int = 0

        var summary: String {
            "伴侣+\(companionsCreated)/~\(companionsMatched) 群+\(groupsCreated) "
            + "消息+\(chatMessagesInserted)/+\(groupMessagesInserted) "
            + "记忆+\(memoriesInserted)/+\(unifiedMemoriesInserted) "
            + "其余+\(tempMemoriesInserted + tokenUsagesInserted + diariesInserted)"
        }
    }

    /// 群消息里 `companionId == -1` 是「系统发送者」哨兵值，**必须原样保留**，
    /// 不能进 ID 映射（对应 Android `if (s.companionId == -1L) -1L else ...`）。
    private static let systemSenderId: Int64 = -1

    func importBackup(_ json: String) throws -> Result {
        guard let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { throw ImportError.malformedJSON }

        let companions = root["companions"] as? [[String: Any]] ?? []
        let groups = root["chatGroups"] as? [[String: Any]] ?? []
        let chatMessages = root["chatMessages"] as? [[String: Any]] ?? []
        let groupMessages = root["groupMessages"] as? [[String: Any]] ?? []
        let memories = root["memoryEntries"] as? [[String: Any]] ?? []
        let tempMemories = root["tempMemories"] as? [[String: Any]] ?? []
        let tokenUsages = root["tokenUsages"] as? [[String: Any]] ?? []
        let unifiedMemories = root["unifiedMemories"] as? [[String: Any]] ?? []
        let diaries = root["diaries"] as? [[String: Any]] ?? []

        let localDeviceId = DeviceIdentity.deviceId
        var result = Result()

        try database.pool.write { db in
            // ① 伴侣：按 name 匹配 → 复用；否则新建（id=0 → 自增）
            var companionIdMap: [Int64: Int64] = [:]
            // ⚠️ 第 80 轮：`let` → `var`。
            // 下面第 109 行 `existingCompanionIds[name] = newId` 要写入它，
            // 而这里原先是 `let` 常量 —— CI 报
            // "Cannot assign through subscript: 'existingCompanionIds' is a 'let' constant"。
            // （App target 编译过了但测试 target 暴露：@testable import 会再编译一遍生产源码）
            var existingCompanionIds = try Self.existingCompanionIds(db)
            for s in companions {
                let oldId = s["id"] as? Int64 ?? 0
                let name = s["name"] as? String ?? ""
                if let existing = existingCompanionIds[name] {
                    companionIdMap[oldId] = existing
                    result.companionsMatched += 1
                } else {
                    try db.execute(sql: """
                        INSERT INTO companions
                          (name, avatarUrl, age, personality, backstory, speakingStyle, tags,
                           rawPrompt, systemPrompt, intimacy, lorebookIdsJson, apiConfigId,
                           createdAt, updatedAt)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, arguments: [
                            name, s["avatarUrl"] as? String, s["age"] as? Int,
                            s["personality"] as? String ?? "", s["backstory"] as? String,
                            s["speakingStyle"] as? String, s["tags"] as? String,
                            s["rawPrompt"] as? String, s["systemPrompt"] as? String,
                            s["intimacy"] as? Int ?? 0,
                            "[]",
                            // ⚠️ 第 79 轮：这里原来误写成了 `# 与 Android 一致...`
                            // （一个 `#` 开头的中文注释），Swift 把 `#` 认成**宏调用**：
                            //   extraneous whitespace between '#' and macro name
                            //   no macro named '与'
                            //   cannot find 'Android' in scope
                            // 一条裸 `#` 引出一串看似无关的报错。
                            // 与 Android 一致：api_configs 不随备份迁移，跨设备 ID 语义不可信 → nil
                            nil,
                            s["createdAt"] as? Int64 ?? 0, s["updatedAt"] as? Int64 ?? 0,
                        ])
                    let newId = db.lastInsertedRowID
                    companionIdMap[oldId] = newId
                    existingCompanionIds[name] = newId
                    result.companionsCreated += 1
                }
            }

            // ② 群组：按 name 匹配；成员取「已有 ∪ 备份」并集
            var groupIdMap: [Int64: Int64] = [:]
            let existingGroups = try Self.existingGroupRows(db)
            for s in groups {
                let oldId = s["id"] as? Int64 ?? 0
                let name = s["name"] as? String ?? ""
                // ⚠️ 第 84 轮：`parseIdCsv` 形参是非可选 String，
                // 而 `s["companionIds"] as? String` 是可选。
                // CI 报 BackupImporter.swift:125 "value of optional type
                // 'String?' must be unwrapped to a value of type 'String'"。
                // 用 `?? ""` 拍平（缺失等价于空 CSV，与原语义一致）。
                let backupMembers = Self.parseIdCsv(s["companionIds"] as? String ?? "")
                    .map { companionIdMap[$0] ?? $0 }
                if let existing = existingGroups[name] {
                    let merged = Array(Set(existing.members).union(backupMembers)).sorted()
                    if merged != existing.members {
                        try db.execute(
                            sql: "UPDATE chat_groups SET companionIds = ? WHERE id = ?",
                            arguments: [Self.idCsv(merged), existing.id]
                        )
                    }
                    groupIdMap[oldId] = existing.id
                } else {
                    try db.execute(sql: """
                        INSERT INTO chat_groups
                          (name, avatarUrl, companionIds, createdAt, updatedAt)
                        VALUES (?,?,?,?,?)
                        """, arguments: [
                            name, s["avatarUrl"] as? String, Self.idCsv(backupMembers),
                            s["createdAt"] as? Int64 ?? 0, s["updatedAt"] as? Int64 ?? 0,
                        ])
                    groupIdMap[oldId] = db.lastInsertedRowID
                    result.groupsCreated += 1
                }
            }

            // ③ 聊天消息：按 (timestamp, isFromUser, content) 去重
            var messageIdMap: [Int64: Int64] = [:]
            let bySnapshot = Dictionary(grouping: chatMessages) { $0["companionId"] as? Int64 ?? 0 }
            for (oldCompanionId, list) in bySnapshot {
                guard let localCompanionId = companionIdMap[oldCompanionId] else { continue }
                // ⚠️ 第 85 轮：`existing` 是 `let`，而下面第 193 行
                // `existing.insert(key)` 要改它 —— CI 报
                // "Cannot use mutating member on immutable value: 'existing' is a 'let' constant"。
                // 与第 84 轮 existingCompanionIds 是同族问题（Set/字典写回却声明成常量）。
                var existing = try Self.existingChatKeys(db, localCompanionId: localCompanionId)
                for s in list.sorted(by: { ($0["timestamp"] as? Int64 ?? 0) < ($1["timestamp"] as? Int64 ?? 0) }) {
                    let key = ChatKey(
                        timestamp: s["timestamp"] as? Int64 ?? 0,
                        isFromUser: s["isFromUser"] as? Bool ?? false,
                        content: s["content"] as? String ?? ""
                    )
                    if existing.contains(key) { continue }
                    let searchContent = (s["searchContent"] as? String) ?? ""
                    let contentKey = key.content
                    try db.execute(sql: """
                        INSERT INTO messages
                          (conversationId, conversationType, isFromUser, senderId, timestamp,
                           type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId)
                        VALUES (?, 'chat', ?, 1, ?, ?, ?, ?, ?, ?, NULL)
                        """, arguments: [
                            localCompanionId, key.isFromUser ? 1 : 0, key.timestamp,
                            s["type"] as? String ?? "TEXT", s["fileFormat"] as? String ?? "TEXT",
                            s["turnId"] as? String, s["eventIndex"] as? Int,
                            s["durationMs"] as? Int64,
                        ])
                    let newId = db.lastInsertedRowID
                    try db.execute(sql: """
                        INSERT OR REPLACE INTO message_bodies
                          (messageId, content, searchContent, linkString)
                        VALUES (?, ?, ?, ?)
                        """, arguments: [
                            newId, contentKey,
                            searchContent.isEmpty ? contentKey : searchContent,
                            s["linkString"] as? String ?? "",
                        ])
                    messageIdMap[s["id"] as? Int64 ?? 0] = newId
                    result.chatMessagesInserted += 1
                    existing.insert(key)
                }
            }

            // ④ anchorMessageId 二阶段回填（映射表此时才齐全）
            for s in chatMessages {
                guard let newId = messageIdMap[s["id"] as? Int64 ?? 0] else { continue }
                guard let oldAnchor = s["anchorMessageId"] as? Int64,
                      let newAnchor = messageIdMap[oldAnchor] else { continue }
                try db.execute(
                    sql: "UPDATE messages SET anchorMessageId = ? WHERE id = ?",
                    arguments: [newAnchor, newId]
                )
            }

            // ⑤ 群消息：系统哨兵 -1 不进映射；按 (timestamp, senderId, content) 去重
            let byGroup = Dictionary(grouping: groupMessages) { $0["groupId"] as? Int64 ?? 0 }
            for (oldGroupId, list) in byGroup {
                guard let localGroupId = groupIdMap[oldGroupId] else { continue }
                // ⚠️ 第 85 轮：同族 —— 下面第 242 行 `existing.insert(key)` 要改它。
                // 编译器一次只报一个，所以两处一起修。
                var existing = try Self.existingGroupKeys(db, localGroupId: localGroupId)
                for s in list.sorted(by: { ($0["timestamp"] as? Int64 ?? 0) < ($1["timestamp"] as? Int64 ?? 0) }) {
                    let oldSender = s["companionId"] as? Int64 ?? 0
                    let sender = oldSender == Self.systemSenderId
                        ? Self.systemSenderId
                        : (companionIdMap[oldSender] ?? oldSender)
                    let content = s["content"] as? String ?? ""
                    let key = (timestamp: s["timestamp"] as? Int64 ?? 0, sender: sender, content: content)
                    if existing.contains(key) { continue }
                    try db.execute(sql: """
                        INSERT INTO messages
                          (conversationId, conversationType, isFromUser, senderId, timestamp,
                           type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId)
                        VALUES (?, 'group', 0, ?, ?, 'TEXT', ?, NULL, NULL, NULL, NULL)
                        """, arguments: [
                            localGroupId, sender, key.timestamp,
                            s["fileFormat"] as? String ?? "TEXT",
                        ])
                    let newId = db.lastInsertedRowID
                    let searchContent = (s["searchContent"] as? String) ?? ""
                    try db.execute(sql: """
                        INSERT OR REPLACE INTO message_bodies
                          (messageId, content, searchContent, linkString)
                        VALUES (?, ?, ?, ?)
                        """, arguments: [
                            newId, content,
                            searchContent.isEmpty ? content : searchContent,
                            s["linkString"] as? String ?? "",
                        ])
                    result.groupMessagesInserted += 1
                    existing.insert(key)
                }
            }

            // ⑥ 其余分区：deviceId 一律改写成本机 ID
            for s in memories {
                try db.execute(sql: """
                    INSERT INTO memory_entries
                      (companionId, content, category, importance, context, accessCount,
                       timestamp, lastAccessed, deviceId)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, arguments: [
                        companionIdMap[s["companionId"] as? Int64 ?? 0] ?? (s["companionId"] as? Int64 ?? 0),
                        s["content"] as? String ?? "",
                        s["category"] as? String ?? "FACT",
                        s["importance"] as? Double ?? 0.5,
                        s["context"] as? String ?? "",
                        s["accessCount"] as? Int ?? 1,
                        s["timestamp"] as? Int64 ?? 0,
                        s["lastAccessed"] as? Int64 ?? 0,
                        localDeviceId,
                    ])
                result.memoriesInserted += 1
            }

            let now = Int64(Date().timeIntervalSince1970 * 1000)
            for s in tempMemories {
                try db.execute(sql: """
                    INSERT INTO temp_memory
                      (companionId, userInput, botResponse, timestamp, deviceId)
                    VALUES (?,?,?,?,?)
                    """, arguments: [
                        companionIdMap[s["companionId"] as? Int64 ?? 0] ?? (s["companionId"] as? Int64 ?? 0),
                        s["userInput"] as? String ?? "",
                        s["botResponse"] as? String ?? "",
                        s["timestamp"] as? Int64 ?? 0,
                        localDeviceId,
                    ])
                result.tempMemoriesInserted += 1
            }

            for s in tokenUsages {
                try db.execute(sql: """
                    INSERT INTO token_usage
                      (companionId, date, inputTokens, outputTokens, totalTokens,
                       requestCount, timestamp, deviceId)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, arguments: [
                        companionIdMap[s["companionId"] as? Int64 ?? 0] ?? (s["companionId"] as? Int64 ?? 0),
                        s["date"] as? String ?? "",
                        s["inputTokens"] as? Int64 ?? 0,
                        s["outputTokens"] as? Int64 ?? 0,
                        s["totalTokens"] as? Int64 ?? 0,
                        s["requestCount"] as? Int ?? 0,
                        s["timestamp"] as? Int64 ?? 0,
                        localDeviceId,
                    ])
                result.tokenUsagesInserted += 1
            }

            // ⑦ unified_memories：sourceId 按 scope 选择映射表；三个时间戳 0 → now
            for s in unifiedMemories {
                let scope = s["scope"] as? String ?? "COMPANION"
                let oldSourceId = s["sourceId"] as? Int64 ?? 0
                let sourceId: Int64
                switch scope {
                case "COMPANION": sourceId = companionIdMap[oldSourceId] ?? oldSourceId
                case "GROUP": sourceId = groupIdMap[oldSourceId] ?? oldSourceId
                default: sourceId = oldSourceId
                }
                let ifZero: (Int64?) -> Int64 = { t in
                    guard let t, t != 0 else { return now }
                    return t
                }
                try db.execute(sql: """
                    INSERT INTO unified_memories
                      (memoryType, scope, source, content, summary, confidence, importance,
                       sourceId, createdAt, updatedAt, lastAccessedAt, observedAt, expiresAt,
                       temporalAnchor, accessCount, tags, isDeleted, version, deviceId)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'',?, ?, 0, 1, ?)
                    """, arguments: [
                        s["memoryType"] as? String ?? "SEMANTIC",
                        scope,
                        s["source"] as? String ?? "CHAT",
                        s["content"] as? String ?? "",
                        s["summary"] as? String ?? "",
                        s["confidence"] as? Double ?? 1.0,
                        s["importance"] as? Double ?? 0.5,
                        sourceId,
                        ifZero(s["createdAt"] as? Int64),
                        ifZero(s["updatedAt"] as? Int64),
                        ifZero(s["observedAt"] as? Int64),
                        ifZero(s["observedAt"] as? Int64),
                        s["expiresAt"] as? Int64,
                        s["accessCount"] as? Int ?? 1,
                        s["tags"] as? String ?? "",
                        localDeviceId,
                    ])
                result.unifiedMemoriesInserted += 1
            }

            for s in diaries {
                try db.execute(sql: """
                    INSERT INTO diary_entries
                      (companionId, title, content, mood, date, weather, tags, deviceId)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, arguments: [
                        companionIdMap[s["companionId"] as? Int64 ?? 0] ?? (s["companionId"] as? Int64 ?? 0),
                        s["title"] as? String ?? "",
                        s["content"] as? String ?? "",
                        s["mood"] as? Int ?? 2,
                        s["date"] as? Int64 ?? 0,
                        s["weather"] as? String ?? "",
                        s["tags"] as? String ?? "",
                        localDeviceId,
                    ])
                result.diariesInserted += 1
            }
        }

        log.info("备份导入完成：\(result.summary, privacy: .public)")
        return result
    }

    enum ImportError: Error, CustomStringConvertible {
        case malformedJSON

        var description: String { "备份文件不是合法 JSON 对象" }
    }

    // MARK: - 辅助

    private struct ChatKey: Hashable {
        let timestamp: Int64
        let isFromUser: Bool
        let content: String
    }

    private struct GroupKey: Hashable {
        let timestamp: Int64
        let sender: Int64
        let content: String
    }

    private static func existingCompanionIds(_ db: Database) throws -> [String: Int64] {
        let rows = try Row.fetchAll(db, sql: "SELECT id, name FROM companions")
        var out: [String: Int64] = [:]
        for r in rows { out[r["name"] as String? ?? ""] = r["id"] as Int64? ?? 0 }
        return out
    }

    private struct GroupRow {
        let id: Int64
        let members: [Int64]
    }

    private static func existingGroupRows(_ db: Database) throws -> [String: GroupRow] {
        let rows = try Row.fetchAll(db, sql: "SELECT id, name, companionIds FROM chat_groups")
        var out: [String: GroupRow] = [:]
        for r in rows {
            out[r["name"] as String? ?? ""] = GroupRow(
                id: r["id"] as Int64? ?? 0,
                members: parseIdCsv(r["companionIds"] as String? ?? "")
            )
        }
        return out
    }

    private static func existingChatKeys(
        _ db: Database, localCompanionId: Int64
    ) throws -> Set<ChatKey> {
        let rows = try Row.fetchAll(db, sql: """
            SELECT m.timestamp, m.isFromUser, b.content
            FROM messages m LEFT JOIN message_bodies b ON b.messageId = m.id
            WHERE m.conversationId = ? AND m.conversationType = 'chat'
            """, arguments: [localCompanionId])
        return Set(rows.map { ChatKey(
            timestamp: $0["timestamp"] as Int64? ?? 0,
            isFromUser: ($0["isFromUser"] as Int? ?? 0) != 0,
            content: $0["content"] as String? ?? ""
        ) })
    }

    private static func existingGroupKeys(
        _ db: Database, localGroupId: Int64
    ) throws -> Set<GroupKey> {
        let rows = try Row.fetchAll(db, sql: """
            SELECT m.timestamp, m.senderId, b.content
            FROM messages m LEFT JOIN message_bodies b ON b.messageId = m.id
            WHERE m.conversationId = ? AND m.conversationType = 'group'
            """, arguments: [localGroupId])
        return Set(rows.map { GroupKey(
            timestamp: $0["timestamp"] as Int64? ?? 0,
            sender: $0["senderId"] as Int64? ?? 0,
            content: $0["content"] as String? ?? ""
        ) })
    }

    private static func parseIdCsv(_ csv: String) -> [Int64] {
        csv.split(separator: ",").compactMap { Int64($0.trimmingCharacters(in: .whitespaces)) }
    }

    private static func idCsv(_ ids: [Int64]) -> String {
        ids.map(String.init).joined(separator: ",")
    }
}
