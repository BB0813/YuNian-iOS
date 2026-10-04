import Foundation
import GRDB
import os

/// 伴侣（人设）仓储。
///
/// ## 为什么在 M3 的关键路径上
/// Rust 侧 `native_gateway.rs::load_companion` 会按 id 读 `companions` 表：
/// ```sql
/// SELECT name, age, personality, backstory, speakingStyle, rawPrompt, systemPrompt
/// FROM companions WHERE id = ?1
/// ```
/// 因此**没有伴侣行就无法进行带人设的回合**（Rust 会返回 Err）。
/// 这 7 列是 Rust 直读契约的一部分，改名即破坏。
///
/// ## 其余列
/// `avatarUrl` / `tags` / `intimacy` / `lorebookIdsJson` / `apiConfigId` 由 Kotlin
/// 经 Room 消费（不进 Rust 直读路径），但 iOS 侧必须能读写，否则人设编辑不完整。
final class CompanionRepository {

    private let database: YuNianDatabase
    private let log = Logger(subsystem: "com.yunian.ai", category: "repo.companion")

    init(database: YuNianDatabase) {
        self.database = database
    }

    /// 完整伴侣行。
    struct Companion: Sendable, Equatable, Identifiable {
        var id: Int64
        var name: String
        var avatarUrl: String?
        var age: Int?
        var personality: String
        var backstory: String?
        var speakingStyle: String?
        var tags: String?
        var rawPrompt: String?
        var systemPrompt: String?
        var intimacy: Int
        var lorebookIdsJson: String
        var apiConfigId: Int64?
        var createdAt: Int64
        var updatedAt: Int64
    }

    /// Rust 直读的 7 列 —— 单独抽出来，方便与 Rust 的 SELECT 列表比对。
    static let rustReadColumns = [
        "name", "age", "personality", "backstory", "speakingStyle", "rawPrompt", "systemPrompt",
    ]

    private static let selectColumns = """
    id, name, avatarUrl, age, personality, backstory, speakingStyle, tags,
    rawPrompt, systemPrompt, intimacy, lorebookIdsJson, apiConfigId, createdAt, updatedAt
    """

    // MARK: - 读

    func fetch(id: Int64) throws -> Companion? {
        try database.pool.read { db in
            try Row.fetchOne(
                db,
                sql: "SELECT \(Self.selectColumns) FROM companions WHERE id = ?",
                arguments: [id]
            ).map(Self.companion(from:))
        }
    }

    /// 按最近更新倒序列出全部伴侣（首页列表用）。
    ///
    /// ⚠️ 排序与 Android 一致是 `ORDER BY updatedAt DESC`，**没有次级 `id DESC`**。
    /// 我原先自行加了次级排序，会改变 `updatedAt` 相同时的顺序
    /// （批量导入/新建时很容易撞上相同毫秒）。
    func fetchAll() throws -> [Companion] {
        try database.pool.read { db in
            try Row.fetchAll(
                db,
                sql: "SELECT \(Self.selectColumns) FROM companions ORDER BY updatedAt DESC"
            ).map(Self.companion(from:))
        }
    }

    /// 默认伴侣（最近更新的一条）。首启无伴侣时返回 nil。
    func fetchDefault() throws -> Companion? {
        try fetchAll().first
    }

    // MARK: - 写

    /// 新建伴侣。`intimacy` 默认 0，与 Android 侧初始值一致。
    @discardableResult
    func create(
        name: String,
        personality: String,
        age: Int? = nil,
        backstory: String? = nil,
        speakingStyle: String? = nil,
        avatarUrl: String? = nil,
        tags: String? = nil,
        rawPrompt: String? = nil,
        systemPrompt: String? = nil,
        intimacy: Int = 0,
        lorebookIdsJson: String = "[]",
        apiConfigId: Int64? = nil
    ) throws -> Int64 {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return try database.pool.write { db in
            try db.execute(sql: """
            INSERT INTO companions
              (name, avatarUrl, age, personality, backstory, speakingStyle, tags,
               rawPrompt, systemPrompt, intimacy, lorebookIdsJson, apiConfigId, createdAt, updatedAt)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, arguments: [
                name, avatarUrl, age, personality, backstory, speakingStyle, tags,
                rawPrompt, systemPrompt, intimacy, lorebookIdsJson, apiConfigId, now, now,
            ])
            return db.lastInsertedRowID
        }
    }

    /// 整体更新（对应 Room 的 `@Update`）——**写入实体自身的 `updatedAt`，不自动刷新**。
    ///
    /// ⚠️ Android 的 `updateCompanion` 是 `@Update`，只写实体里的值。
    /// 调用方 `CreateCompanionViewModel.saveCompanion` 仅做 `companion.copy(avatarUrl = ...)`，
    /// **没有设置 `updatedAt`**；刷新时间戳是另一条独立路径 `updateTimestamp`
    /// （对话流程里 `updateTimestamp / increaseIntimacy(2)` 是两次分开的调用）。
    ///
    /// 我原先在这里无条件写 `updatedAt = now`，会让「编辑人设」意外改变列表排序位置，
    /// 且与 Android 不一致。现在如实写入 `companion.updatedAt`；
    /// 需要刷新时间戳的调用方请显式调用 `touch(id:)`。
    func update(_ companion: Companion) throws {
        try database.pool.write { db in
            try db.execute(sql: """
            UPDATE companions SET
              name = ?, avatarUrl = ?, age = ?, personality = ?, backstory = ?,
              speakingStyle = ?, tags = ?, rawPrompt = ?, systemPrompt = ?,
              intimacy = ?, lorebookIdsJson = ?, apiConfigId = ?, updatedAt = ?
            WHERE id = ?
            """, arguments: [
                companion.name, companion.avatarUrl, companion.age, companion.personality,
                companion.backstory, companion.speakingStyle, companion.tags,
                companion.rawPrompt, companion.systemPrompt, companion.intimacy,
                companion.lorebookIdsJson, companion.apiConfigId,
                companion.updatedAt, companion.id,
            ])
        }
    }

    /// 只刷新时间戳。对应 Android `CompanionDao.updateTimestamp(id, timestamp)`：
    /// ```sql
    /// UPDATE companions SET updatedAt = :timestamp WHERE id = :id
    /// ```
    @discardableResult
    func touch(id: Int64, timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws -> Bool {
        try database.pool.write { db in
            try db.execute(
                sql: "UPDATE companions SET updatedAt = ? WHERE id = ?",
                arguments: [timestamp, id]
            )
            return db.changesCount > 0
        }
    }

    /// 亲密度累加。对应 Android `CompanionDao.increaseIntimacy(id, amount)`：
    /// ```sql
    /// UPDATE companions SET intimacy = intimacy + :amount WHERE id = :id
    /// ```
    ///
    /// ⚠️ **两处曾经的臆造，都已去掉**：
    ///   1. 我加了 `MAX(0, intimacy + ?)` 钳制 —— Android **没有**钳制。
    ///      调用方只传正值（对话落库 +2），但语义必须一致：传负数时 Android 允许降到负。
    ///   2. 我顺手更新了 `updatedAt` —— Android 不更新，时间戳由 `touch(id:)` 单独负责。
    @discardableResult
    func increaseIntimacy(id: Int64, amount: Int = 1) throws -> Int {
        try database.pool.write { db in
            try db.execute(
                sql: "UPDATE companions SET intimacy = intimacy + ? WHERE id = ?",
                arguments: [amount, id]
            )
            return db.changesCount
        }
    }

    /// 读亲密度。对应 Android `CompanionDao.getIntimacy(id)`。
    func intimacy(id: Int64) throws -> Int? {
        try database.pool.read { db in
            try Int.fetchOne(db, sql: "SELECT intimacy FROM companions WHERE id = ?", arguments: [id])
        }
    }

    /// 删除伴侣。
    ///
    /// ⚠️ **不会连带删除会话消息**：`messages` 表对本表**没有外键**
    /// （schema v45 只有 2 个外键，都在消息正文表上）。
    /// 因此调用方若要「删人设并清聊天记录」，必须显式调用
    /// `MessageRepository.deleteForConversation(...)` —— 顺序是先清消息再删伴侣。
    ///
    /// 另外：若删的是默认伴侣，会置 `deleted_by_user` 标志位，
    /// 避免下次启动被 `DefaultCompanionSeeder` 自动重建（对应 Android 的同名机制）。
    func delete(id: Int64) throws {
        // 先取出整行，删完才能判断它是否为默认伴侣
        if let companion = try fetch(id: id) {
            let wasDefault = DefaultCompanionSeeder.isDefaultExperienceCompanion(companion)
            try database.pool.write { db in
                try db.execute(sql: "DELETE FROM companions WHERE id = ?", arguments: [id])
            }
            if wasDefault {
                DefaultCompanionSeeder.handleDeletion(of: companion)
            }
            return
        }
        try database.pool.write { db in
            try db.execute(sql: "DELETE FROM companions WHERE id = ?", arguments: [id])
        }
    }

    // MARK: - 映射

    private static func companion(from row: Row) -> Companion {
        Companion(
            id: row["id"] as Int64? ?? 0,
            name: row["name"] as String? ?? "",
            avatarUrl: row["avatarUrl"] as String?,
            age: row["age"] as Int?,
            personality: row["personality"] as String? ?? "",
            backstory: row["backstory"] as String?,
            speakingStyle: row["speakingStyle"] as String?,
            tags: row["tags"] as String?,
            rawPrompt: row["rawPrompt"] as String?,
            systemPrompt: row["systemPrompt"] as String?,
            intimacy: row["intimacy"] as Int? ?? 0,
            lorebookIdsJson: row["lorebookIdsJson"] as String? ?? "[]",
            apiConfigId: row["apiConfigId"] as Int64?,
            createdAt: row["createdAt"] as Int64? ?? 0,
            updatedAt: row["updatedAt"] as Int64? ?? 0
        )
    }
}
