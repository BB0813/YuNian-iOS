import Foundation
import GRDB
import os

/// iOS 侧数据库引导 —— 与 Android 侧 Room v45 同构。
///
/// ## 关键设计（每一条都有依据，改动前请先读文档）
///
/// 1. **用 `DatabasePool`（WAL），不用 `DatabaseQueue`。**
///    Android 侧 Room 开了 `WRITE_AHEAD_LOGGING`，而 Rust 侧
///    （`native_gateway.rs`）以 `SQLITE_OPEN_READ_ONLY | SQLITE_OPEN_NO_MUTEX`
///    并发读同一个库文件，其正确性建立在 WAL 之上。因此 iOS 侧**必须**保持 WAL，
///    否则 Rust 的只读连接会读到不完整状态或直接失败。
///
/// 2. **不使用 GRDB 的 `DatabaseMigrator` 做版本管理。**
///    GRDB 用 `PRAGMA user_version` 记录「已应用迁移数」，而我们的
///    `user_version` 要与 Room 的 schema 版本（45）对齐 —— 这是 Rust 侧
///    `MIN_SUPPORTED_SCHEMA=41 / MAX_SUPPORTED_SCHEMA=45` 契约的一部分，
///    也是「导入 Android 库文件」时的校验依据。两套语义会互相打架，
///    因此这里显式自管版本，走 `bootstrap()`。
///
/// 3. **只建 v45 基线，不移植 Room 的 44 个迁移链。**
///    见 `docs/ios-port-feasibility.md` §4.4 的待决策项 D6。
///    Room 的 v1–v16 连 schema JSON 都没有，无法做严格迁移验证。
///
/// 4. **FTS 表运行时探测 FTS4 / FTS5。**
///    见 §9 的 V4。分词在应用层完成（`MessageSearchTokenizer` 输出纯 ASCII），
///    两种 FTS 版本的匹配语义无差别，因此可安全回退。
final class YuNianDatabase {

    enum BootstrapError: Error, CustomStringConvertible {
        case unsupportedSchemaVersion(found: Int, supported: ClosedRange<Int>)
        case belowFrozenBaseline(found: Int, baseline: Int)
        case schemaMismatch(missingTables: [String])
        case ftsUnavailable(String)

        var description: String {
            switch self {
            case let .unsupportedSchemaVersion(found, supported):
                return """
                数据库 schema 版本 \(found) 超出支持区间 \(supported)。
                本 App 构建识别 Room v\(YuNianSchema.version) 基线；
                库里若是更高版本，说明它由更新的 Android 客户端写入，请先升级 iOS 版。
                """
            case let .belowFrozenBaseline(found, baseline):
                return """
                数据库 schema 版本 \(found) 低于冻结基线 v\(baseline)。
                请在 Android 侧先升级到 v\(baseline) 或更高版本再导出，
                iOS 侧不提供 v1–\(baseline - 1) 的迁移链（见文档 §4.4）。
                """
            case let .schemaMismatch(missingTables):
                return "数据库缺少表：\(missingTables.joined(separator: ", "))"
            case let .ftsUnavailable(detail):
                return "FTS 表创建失败（FTS4 与 FTS5 均不可用）：\(detail)"
            }
        }
    }

    /// 实际落库时用到的 FTS 版本（"FTS4" 或 "FTS5"），供诊断面板显示。
    private(set) var resolvedFTSVersion: String = "unknown"

    let pool: DatabasePool
    let databaseURL: URL

    private let log = Logger(subsystem: "com.yunian.ai", category: "database")

    init(databaseURL: URL) throws {
        self.databaseURL = databaseURL

        var configuration = Configuration()
        // 显式开启外键：SQLite 默认 OFF，而 Room 会开。
        // 本 schema 有 2 个 ON DELETE CASCADE 外键（message_bodies / archived_message_bodies）。
        configuration.foreignKeysEnabled = true
        configuration.busyMode = .timeout(5)
        configuration.prepareDatabase { db in
            // 与 Room 的 WRITE_AHEAD_LOGGING 对齐；DatabasePool 已默认 WAL，此处再显式确认。
            try db.execute(sql: "PRAGMA journal_mode = WAL")
            try db.execute(sql: "PRAGMA synchronous = NORMAL")
            // 安全删除：删除行时清零内容，而不是只留可恢复的残留页。
            //
            // 对应 Android `AppDatabase.kt` 的 MIGRATION_36_37 里的
            // `PRAGMA secure_delete = ON`。
            //
            // ⚠️ Android 的实现**不可靠**：secure_delete 是**每连接** PRAGMA（不落库），
            // 它只对执行那次迁移的连接生效。iOS 侧没有迁移路径（直接建 v45 基线），
            // 因此必须显式设置才能获得同等效果。
            //
            // 这里选择对齐 Android 代码的**意图**（及其在新建库时的 de facto 行为），
            // 而非完全不复刻。代价是删除略慢；收益是已删除的聊天内容不会残留在可用空间里。
            try db.execute(sql: "PRAGMA secure_delete = ON")
        }

        self.pool = try DatabasePool(path: databaseURL.path, configuration: configuration)
        try bootstrap()
    }

    // MARK: - 引导

    /// 确认版本并（在空库时）建立 v45 基线。
    private func bootstrap() throws {
        try pool.writeWithoutTransaction { db in
            let version = try Int.fetchOne(db, sql: "PRAGMA user_version") ?? 0
            let supported = YuNianSchema.rustSupportedVersionRange

            if version == 0 {
                log.info("空库，建立 Room v\(YuNianSchema.version) 基线")
                try createBaseline(db)
                return
            }

            if version < YuNianSchema.frozenBaselineVersion {
                throw BootstrapError.belowFrozenBaseline(
                    found: version,
                    baseline: YuNianSchema.frozenBaselineVersion
                )
            }
            guard supported.contains(version) else {
                throw BootstrapError.unsupportedSchemaVersion(found: version, supported: supported)
            }

            // 版本在支持区间内：这可能是导入的 Android 库，或已初始化过的 iOS 库。
            log.info("检测到既有 schema 版本 \(version)，校验关键表")
            try verifyCriticalTables(db)
        }
    }

    /// 建立 v45 基线：32 张表 + 61 个索引 + FTS 表 + **供应商预设播种**，并写入 `user_version = 45`。
    ///
    /// ⚠️ 预设播种是**建库的一部分**，不是应用层的首启逻辑。
    /// Android 侧把它放在 Room 的 `onCreate` 回调里（`AppDatabase.seedApiProviderPresets`），
    /// 因此任何「新建的库」都必须带上这 13 行 —— 否则 `api_provider_presets` 是空表，
    /// 供应商列表界面无内容，而这不是一个能被轻易发现的错误。
    private func createBaseline(_ db: Database) throws {
        try db.inTransaction {
            for sql in YuNianSchema.createTables {
                try db.execute(sql: sql)
            }
            for sql in YuNianSchema.createIndices {
                try db.execute(sql: sql)
            }
            resolvedFTSVersion = try YuNianSchema.createFTSTable(db)
            try Self.seedApiProviderPresets(db)

            // Rust 侧契约：MIN_SUPPORTED_SCHEMA=41 / MAX_SUPPORTED_SCHEMA=45。
            // 字面量拼接是必要的 —— PRAGMA 不支持参数绑定，且此处是编译期常量。
            try db.execute(sql: "PRAGMA user_version = \(YuNianSchema.version)")
            return .commit
        }
        let fts = resolvedFTSVersion
        log.info("基线建立完成：表 \(YuNianSchema.tableCount) / 索引 \(YuNianSchema.indexCount) / 外键 \(YuNianSchema.foreignKeyCount) / FTS \(fts, privacy: .public) / 预设 \(YuNianSeed.apiProviderPresets.count)")
    }

    /// 播种 API 供应商预设。
    ///
    /// 逐条对应 `AppDatabase.seedApiProviderPresets`（Kotlin），
    /// 用的是 `INSERT OR IGNORE` —— 因为 `provider` 列上有唯一索引
    /// （`index_api_provider_presets_provider`），重复播种不会产生副本。
    ///
    /// 数据本身由 `ios/Tools/generate_seeds.py` 从 Android 源码生成，不手抄。
    static func seedApiProviderPresets(_ db: Database) throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        for preset in YuNianSeed.apiProviderPresets {
            try db.execute(sql: """
            INSERT OR IGNORE INTO `api_provider_presets` (
                `provider`, `displayName`, `baseUrl`, `model`, `formatHint`,
                `sortOrder`, `isVisible`, `updatedAt`
            ) VALUES (?, ?, ?, ?, ?, ?, 1, ?)
            """, arguments: [
                preset.provider,
                preset.displayName,
                preset.baseUrl,
                preset.model,
                preset.formatHint,
                preset.sortOrder,
                now,
            ])
        }
    }

    /// 校验 Rust 直读依赖的 2 张表存在（`api_configs` + `companions`）。
    /// Rust 的 `native_gateway.rs` 只读这两张表 + `PRAGMA user_version`。
    private func verifyCriticalTables(_ db: Database) throws {
        let required = ["api_configs", "companions"]
        let present = try String.fetchSet(
            db,
            sql: "SELECT name FROM sqlite_master WHERE type = 'table'"
        )
        let missing = required.filter { !present.contains($0) }
        guard missing.isEmpty else {
            throw BootstrapError.schemaMismatch(missingTables: missing)
        }

        // 顺带确认 FTS 表的实际形态（导入的库可能用 FTS4，iOS 新建可能用 FTS5）
        let ftsSQL = try String.fetchOne(
            db,
            sql: "SELECT sql FROM sqlite_master WHERE name = ?",
            arguments: [YuNianSchema.ftsTableName]
        )
        if let ftsSQL {
            resolvedFTSVersion = ftsSQL.lowercased().contains("fts5") ? "FTS5" : "FTS4"
        } else {
            log.warning("缺少 FTS 表 \(YuNianSchema.ftsTableName, privacy: .public)，尝试补建")
            resolvedFTSVersion = try YuNianSchema.createFTSTable(db)
        }
    }

    // MARK: - 检索（中文全文检索，与 Android 侧同一张索引表）

    /// 按关键词检索消息 id，返回按 `rowid`（即 messageId）降序的结果。
    ///
    /// 索引维护不在本方法内 —— Android 侧由 14 个 DAO 写/删点手工维护
    /// （`MessageDao` 写热消息、归档、回填、删除等处），且该 FTS 表**没有外键**。
    /// iOS 侧需要复刻同批维护点，否则两端检索结果会分叉。
    func searchMessageIds(matching query: String, limit: Int = 50) throws -> [Int64] {
        guard let match = MessageSearchTokenizer.matchQuery(query) else { return [] }
        return try pool.read { db in
            try Int64.fetchAll(
                db,
                sql: """
                SELECT rowid FROM \(YuNianSchema.ftsTableName)
                WHERE \(YuNianSchema.ftsTableName) MATCH ?
                ORDER BY rowid DESC
                LIMIT ?
                """,
                arguments: [match, limit]
            )
        }
    }

    /// 写入/更新某条消息的检索索引。`messageId` 必须作为 FTS 的 `rowid` 显式写入
    /// （Android 侧靠 `MessageSearchIndex` 的 `@PrimaryKey rowid` 保证）。
    func upsertSearchIndex(messageId: Int64, searchContent: String) throws {
        let tokens = MessageSearchTokenizer.indexTokens(searchContent)
        try pool.write { db in
            try db.execute(
                sql: "INSERT OR REPLACE INTO \(YuNianSchema.ftsTableName)(rowid, tokens) VALUES (?, ?)",
                arguments: [messageId, tokens]
            )
        }
    }

    func removeSearchIndex(messageId: Int64) throws {
        try pool.write { db in
            try db.execute(
                sql: "DELETE FROM \(YuNianSchema.ftsTableName) WHERE rowid = ?",
                arguments: [messageId]
            )
        }
    }
}
