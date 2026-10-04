// ⚠️ 本文件由 ios/Tools/generate_schema.py 自动生成，请勿手改。
// 来源：core/database/schemas/com.yunian.ai.database.AppDatabase/45.json
// Room schema version = 45，identityHash = 4159c505d69c14f7c7d6dbd0fc17af36
//
// 重新生成：python ios/Tools/generate_schema.py

import Foundation

/// 予念 iOS 侧的数据库基线（与 Android 侧 Room v45 同构）。
///
/// 为什么不做迁移链：见 docs/ios-port-feasibility.md §4.4。
/// iOS 首发以 v45 空库建库；导入 Android 历史数据走逻辑导出（BackupData），
/// 不走 DB 文件拷贝 —— 消息正文与记忆是 AndroidKeyStore 加密的，iOS 无法解密。
enum YuNianSchema {

    /// Room 的 schema 版本，写入 PRAGMA user_version。
    static let version = 45

    /// Room 的 identity_hash。用于校验「导入的 Android 库」是否为本版本。
    static let identityHash = "4159c505d69c14f7c7d6dbd0fc17af36"

    /// schema 冻结基线（docs/database-schema-freeze.md）。
    static let frozenBaselineVersion = 41

    /// Rust 侧（agent-native/src/native_gateway.rs）接受的 schema 版本区间。
    static let rustSupportedVersionRange = 41...45

    static let tableCount = 31
    static let indexCount = 61
    static let foreignKeyCount = 2

    static let ftsTableName = "message_search_index"

    // ── 建表（Room v45 原样 DDL）────────────────────────────────
    static let createTables: [String] = [
        "CREATE TABLE IF NOT EXISTS `app_meta` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
        "CREATE TABLE IF NOT EXISTS `companions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `avatarUrl` TEXT, `age` INTEGER, `personality` TEXT NOT NULL, `backstory` TEXT, `speakingStyle` TEXT, `tags` TEXT, `rawPrompt` TEXT, `systemPrompt` TEXT, `intimacy` INTEGER NOT NULL, `lorebookIdsJson` TEXT NOT NULL DEFAULT '[]', `apiConfigId` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `api_configs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `provider` TEXT NOT NULL, `name` TEXT NOT NULL, `apiKey` TEXT NOT NULL, `extraApiKeys` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, `model` TEXT NOT NULL, `temperature` REAL NOT NULL, `maxTokens` INTEGER, `isEnabled` INTEGER NOT NULL, `connectionTested` INTEGER NOT NULL, `connectionTestedAt` INTEGER NOT NULL, `latencyMs` INTEGER NOT NULL, `formatHint` TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `api_provider_presets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `provider` TEXT NOT NULL, `displayName` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, `model` TEXT NOT NULL, `formatHint` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `isVisible` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `memory_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `companionId` INTEGER NOT NULL, `content` TEXT NOT NULL, `category` TEXT NOT NULL, `importance` REAL NOT NULL, `context` TEXT NOT NULL, `accessCount` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `lastAccessed` INTEGER NOT NULL, `deviceId` TEXT NOT NULL DEFAULT '')",
        "CREATE TABLE IF NOT EXISTS `temp_memory` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `companionId` INTEGER NOT NULL, `userInput` TEXT NOT NULL, `botResponse` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `deviceId` TEXT NOT NULL DEFAULT '')",
        "CREATE TABLE IF NOT EXISTS `unified_memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `memoryType` TEXT NOT NULL, `scope` TEXT NOT NULL, `source` TEXT NOT NULL, `content` TEXT NOT NULL, `summary` TEXT NOT NULL DEFAULT '', `confidence` REAL NOT NULL DEFAULT 1.0, `importance` REAL NOT NULL DEFAULT 0.5, `sourceId` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastAccessedAt` INTEGER NOT NULL, `observedAt` INTEGER NOT NULL, `expiresAt` INTEGER, `validFrom` INTEGER, `validTo` INTEGER, `temporalAnchor` TEXT NOT NULL DEFAULT '', `accessCount` INTEGER NOT NULL DEFAULT 1, `tags` TEXT NOT NULL, `fuzzyHints` TEXT NOT NULL DEFAULT '', `mergedFrom` TEXT NOT NULL DEFAULT '', `isDeleted` INTEGER NOT NULL DEFAULT 0, `version` INTEGER NOT NULL DEFAULT 1, `deviceId` TEXT NOT NULL DEFAULT '', `embedding` BLOB, `embeddingModel` TEXT NOT NULL DEFAULT '')",
        "CREATE TABLE IF NOT EXISTS `diary_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `companionId` INTEGER NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `mood` INTEGER NOT NULL, `date` INTEGER NOT NULL, `weather` TEXT NOT NULL, `tags` TEXT NOT NULL, `deviceId` TEXT NOT NULL DEFAULT '')",
        "CREATE TABLE IF NOT EXISTS `chat_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `avatarUrl` TEXT, `companionIds` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `keywords` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `keyword` TEXT NOT NULL, `pattern` TEXT, `level` TEXT NOT NULL, `type` TEXT NOT NULL, `banDays` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `checksum` TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `quiz_questions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `question` TEXT NOT NULL, `options` TEXT NOT NULL, `correctIndex` INTEGER NOT NULL, `category` TEXT NOT NULL, `difficulty` TEXT NOT NULL, `isEnabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `checksum` TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `token_usage` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `companionId` INTEGER NOT NULL, `date` TEXT NOT NULL, `inputTokens` INTEGER NOT NULL, `outputTokens` INTEGER NOT NULL, `totalTokens` INTEGER NOT NULL, `requestCount` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `deviceId` TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `conversation_summary` (`sessionId` INTEGER NOT NULL, `sessionType` TEXT NOT NULL, `lastMessageId` INTEGER, `lastMessagePreview` TEXT NOT NULL, `lastMessageTimestamp` INTEGER NOT NULL, `lastMessageIsFromUser` INTEGER NOT NULL, `readThroughMessageTimestamp` INTEGER, `readThroughMessageId` INTEGER, `unreadCount` INTEGER NOT NULL, `isPinned` INTEGER NOT NULL, `isMuted` INTEGER NOT NULL, PRIMARY KEY(`sessionId`, `sessionType`))",
        "CREATE TABLE IF NOT EXISTS `messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversationId` INTEGER NOT NULL, `conversationType` TEXT NOT NULL, `isFromUser` INTEGER NOT NULL, `senderId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `type` TEXT NOT NULL, `fileFormat` TEXT NOT NULL, `turnId` TEXT, `eventIndex` INTEGER, `durationMs` INTEGER, `anchorMessageId` INTEGER)",
        "CREATE TABLE IF NOT EXISTS `message_bodies` (`messageId` INTEGER NOT NULL, `content` TEXT NOT NULL, `searchContent` TEXT NOT NULL, `linkString` TEXT NOT NULL, PRIMARY KEY(`messageId`), FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `archived_messages` (`id` INTEGER NOT NULL, `conversationId` INTEGER NOT NULL, `conversationType` TEXT NOT NULL, `isFromUser` INTEGER NOT NULL, `senderId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `type` TEXT NOT NULL, `fileFormat` TEXT NOT NULL, `turnId` TEXT, `eventIndex` INTEGER, `durationMs` INTEGER, `anchorMessageId` INTEGER, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `archived_message_bodies` (`messageId` INTEGER NOT NULL, `content` TEXT NOT NULL, `searchContent` TEXT NOT NULL, `linkString` TEXT NOT NULL, PRIMARY KEY(`messageId`), FOREIGN KEY(`messageId`) REFERENCES `archived_messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `wechat_outbox` (`id` TEXT NOT NULL, `rootId` TEXT NOT NULL, `companionId` INTEGER NOT NULL, `wechatUserId` TEXT NOT NULL, `kind` INTEGER NOT NULL, `text` TEXT, `mediaLocalPath` TEXT, `mediaFileName` TEXT, `mediaDescription` TEXT, `segmentIndex` INTEGER NOT NULL, `segmentCount` INTEGER NOT NULL, `sourceMessageId` INTEGER, `status` TEXT NOT NULL, `retryCount` INTEGER NOT NULL, `nextAttemptAtMs` INTEGER NOT NULL, `lastError` TEXT, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `wechat_inbox_dedupe` (`dedupeKey` TEXT NOT NULL, `messageId` INTEGER, `fromUserId` TEXT NOT NULL, `processedAtMs` INTEGER NOT NULL, PRIMARY KEY(`dedupeKey`))",
        "CREATE TABLE IF NOT EXISTS `lorebooks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL DEFAULT '', `companionId` INTEGER, `enabled` INTEGER NOT NULL DEFAULT 1, `createdAt` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS `lorebook_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `lorebookId` INTEGER NOT NULL, `keywordsJson` TEXT NOT NULL, `content` TEXT NOT NULL, `injectionPosition` TEXT NOT NULL, `priority` INTEGER NOT NULL DEFAULT 0, `injectDepth` INTEGER, `role` TEXT NOT NULL, `caseSensitive` INTEGER NOT NULL DEFAULT 0, `useRegex` INTEGER NOT NULL DEFAULT 0, `sortOrder` INTEGER NOT NULL DEFAULT 0, `scanDepth` INTEGER NOT NULL DEFAULT 10, `constantActive` INTEGER NOT NULL DEFAULT 0, `enabled` INTEGER NOT NULL DEFAULT 1, `createdAt` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE IF NOT EXISTS `agent_skills` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `skillId` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `category` TEXT NOT NULL, `tags` TEXT NOT NULL, `tools` TEXT NOT NULL, `contentPath` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `contentLength` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `companionId` INTEGER, `version` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `prompt_audit` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `companionId` INTEGER, `groupId` INTEGER, `sessionId` TEXT, `promptVersion` INTEGER NOT NULL, `fragmentsJson` TEXT NOT NULL, `roundsUsed` INTEGER NOT NULL, `systemPromptHash` TEXT NOT NULL, `toolNames` TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `agent_dispatch_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `companionId` INTEGER, `groupId` INTEGER, `sessionId` TEXT, `dispatchId` TEXT NOT NULL, `provider` TEXT NOT NULL, `model` TEXT NOT NULL, `startedAtMs` INTEGER NOT NULL, `completedAtMs` INTEGER NOT NULL, `roundsUsed` INTEGER NOT NULL, `finishedReason` TEXT NOT NULL, `error` TEXT NOT NULL, `toolNames` TEXT NOT NULL, `toolCallsJson` TEXT NOT NULL, `eventsJson` TEXT NOT NULL, `querySummary` TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `sticker_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `description` TEXT, `hash` TEXT NOT NULL, `tags` TEXT NOT NULL, `embeddedText` TEXT, `detail` TEXT, `fileName` TEXT NOT NULL, `source` TEXT NOT NULL, `fileSize` INTEGER, `userUsageCount` INTEGER NOT NULL, `modelUsageCount` INTEGER NOT NULL, `createdAt` INTEGER, `lastUsedAt` INTEGER)",
        "CREATE TABLE IF NOT EXISTS `sticker_tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tag` TEXT NOT NULL, `stickerCount` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `sticker_usage_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `stickerId` INTEGER NOT NULL, `source` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `contextTags` TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `event_ledger` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `streamId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `type` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `payloadJson` TEXT NOT NULL, `metadataJson` TEXT NOT NULL, `idempotencyKey` TEXT, `prevHash` TEXT NOT NULL, `hash` TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `event_ledger_snapshot` (`streamId` TEXT NOT NULL, `version` INTEGER NOT NULL, `stateJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`streamId`))",
        "CREATE TABLE IF NOT EXISTS `delegation_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `role` TEXT NOT NULL, `prompt` TEXT NOT NULL, `status` TEXT NOT NULL, `result` TEXT NOT NULL, `error` TEXT NOT NULL, `companionId` INTEGER, `dispatchId` TEXT, `createdAtMs` INTEGER NOT NULL, `completedAtMs` INTEGER)",
        "CREATE TABLE IF NOT EXISTS `worldbooks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `json` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `companionId` INTEGER, `updatedAt` INTEGER NOT NULL)",
    ]

    // ── 建索引（含自定义 idx_* 名与 DESC 复合索引，必须逐字一致）──
    static let createIndices: [String] = [
        "CREATE INDEX IF NOT EXISTS `index_companions_created_at` ON `companions` (`createdAt`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_api_provider_presets_provider` ON `api_provider_presets` (`provider`)",
        "CREATE INDEX IF NOT EXISTS `index_unified_memories_deviceId_scope_sourceId` ON `unified_memories` (`deviceId`, `scope`, `sourceId`)",
        "CREATE INDEX IF NOT EXISTS `index_unified_memories_deviceId_memoryType` ON `unified_memories` (`deviceId`, `memoryType`)",
        "CREATE INDEX IF NOT EXISTS `index_unified_memories_deviceId_observedAt` ON `unified_memories` (`deviceId`, `observedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_unified_memories_deviceId_importance` ON `unified_memories` (`deviceId`, `importance`)",
        "CREATE INDEX IF NOT EXISTS `index_unified_memories_isDeleted` ON `unified_memories` (`isDeleted`)",
        "CREATE INDEX IF NOT EXISTS `index_unified_memories_expiresAt` ON `unified_memories` (`expiresAt`)",
        "CREATE INDEX IF NOT EXISTS `index_diary_entries_companionId_deviceId` ON `diary_entries` (`companionId`, `deviceId`)",
        "CREATE INDEX IF NOT EXISTS `index_diary_entries_date` ON `diary_entries` (`date`)",
        "CREATE INDEX IF NOT EXISTS `index_diary_entries_deviceId` ON `diary_entries` (`deviceId`)",
        "CREATE INDEX IF NOT EXISTS `index_keywords_level` ON `keywords` (`level`)",
        "CREATE INDEX IF NOT EXISTS `index_keywords_type` ON `keywords` (`type`)",
        "CREATE INDEX IF NOT EXISTS `index_quiz_category` ON `quiz_questions` (`category`)",
        "CREATE INDEX IF NOT EXISTS `index_quiz_difficulty` ON `quiz_questions` (`difficulty`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_token_usage_companionId_date_deviceId` ON `token_usage` (`companionId`, `date`, `deviceId`)",
        "CREATE INDEX IF NOT EXISTS `index_token_usage_date` ON `token_usage` (`date`)",
        "CREATE INDEX IF NOT EXISTS `index_token_usage_companionId` ON `token_usage` (`companionId`)",
        "CREATE INDEX IF NOT EXISTS `index_token_usage_companionId_deviceId` ON `token_usage` (`companionId`, `deviceId`)",
        "CREATE INDEX IF NOT EXISTS `idx_summary_type_time` ON `conversation_summary` (`sessionType`, `lastMessageTimestamp`)",
        "CREATE INDEX IF NOT EXISTS `idx_messages_conv` ON `messages` (`conversationType` ASC, `conversationId` ASC, `timestamp` DESC, `id` DESC)",
        "CREATE INDEX IF NOT EXISTS `idx_messages_turn` ON `messages` (`turnId` ASC, `eventIndex` ASC, `id` ASC)",
        "CREATE INDEX IF NOT EXISTS `idx_archived_messages_conv` ON `archived_messages` (`conversationType` ASC, `conversationId` ASC, `timestamp` DESC, `id` DESC)",
        "CREATE INDEX IF NOT EXISTS `idx_archived_messages_turn` ON `archived_messages` (`turnId` ASC, `eventIndex` ASC, `id` ASC)",
        "CREATE INDEX IF NOT EXISTS `index_wechat_outbox_status_nextAttemptAtMs` ON `wechat_outbox` (`status`, `nextAttemptAtMs`)",
        "CREATE INDEX IF NOT EXISTS `index_wechat_outbox_wechatUserId_status` ON `wechat_outbox` (`wechatUserId`, `status`)",
        "CREATE INDEX IF NOT EXISTS `index_wechat_outbox_rootId` ON `wechat_outbox` (`rootId`)",
        "CREATE INDEX IF NOT EXISTS `index_wechat_outbox_companionId` ON `wechat_outbox` (`companionId`)",
        "CREATE INDEX IF NOT EXISTS `index_wechat_inbox_dedupe_processedAtMs` ON `wechat_inbox_dedupe` (`processedAtMs`)",
        "CREATE INDEX IF NOT EXISTS `index_wechat_inbox_dedupe_fromUserId` ON `wechat_inbox_dedupe` (`fromUserId`)",
        "CREATE INDEX IF NOT EXISTS `index_lorebooks_companionId` ON `lorebooks` (`companionId`)",
        "CREATE INDEX IF NOT EXISTS `index_lorebooks_enabled` ON `lorebooks` (`enabled`)",
        "CREATE INDEX IF NOT EXISTS `index_lorebook_entries_lorebookId` ON `lorebook_entries` (`lorebookId`)",
        "CREATE INDEX IF NOT EXISTS `index_lorebook_entries_enabled` ON `lorebook_entries` (`enabled`)",
        "CREATE INDEX IF NOT EXISTS `index_lorebook_entries_priority` ON `lorebook_entries` (`priority`)",
        "CREATE INDEX IF NOT EXISTS `index_lorebook_entries_injectionPosition` ON `lorebook_entries` (`injectionPosition`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_skills_skillId` ON `agent_skills` (`skillId`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_skills_category` ON `agent_skills` (`category`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_skills_companionId` ON `agent_skills` (`companionId`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_skills_enabled` ON `agent_skills` (`enabled`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_skills_updatedAt` ON `agent_skills` (`updatedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_prompt_audit_timestamp` ON `prompt_audit` (`timestamp`)",
        "CREATE INDEX IF NOT EXISTS `index_prompt_audit_companionId` ON `prompt_audit` (`companionId`)",
        "CREATE INDEX IF NOT EXISTS `index_prompt_audit_groupId` ON `prompt_audit` (`groupId`)",
        "CREATE INDEX IF NOT EXISTS `index_prompt_audit_sessionId` ON `prompt_audit` (`sessionId`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_dispatch_log_timestamp` ON `agent_dispatch_log` (`timestamp`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_dispatch_log_companionId` ON `agent_dispatch_log` (`companionId`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_dispatch_log_groupId` ON `agent_dispatch_log` (`groupId`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_dispatch_log_sessionId` ON `agent_dispatch_log` (`sessionId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `idx_sticker_entries_hash` ON `sticker_entries` (`hash`)",
        "CREATE INDEX IF NOT EXISTS `idx_sticker_entries_tags` ON `sticker_entries` (`tags`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sticker_tags_tag` ON `sticker_tags` (`tag`)",
        "CREATE INDEX IF NOT EXISTS `idx_sticker_usage_sticker` ON `sticker_usage_log` (`stickerId`)",
        "CREATE INDEX IF NOT EXISTS `idx_sticker_usage_time` ON `sticker_usage_log` (`timestamp`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_event_ledger_streamId_sequence` ON `event_ledger` (`streamId`, `sequence`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_event_ledger_idempotencyKey` ON `event_ledger` (`idempotencyKey`)",
        "CREATE INDEX IF NOT EXISTS `index_event_ledger_timestamp` ON `event_ledger` (`timestamp`)",
        "CREATE INDEX IF NOT EXISTS `index_delegation_records_status` ON `delegation_records` (`status`)",
        "CREATE INDEX IF NOT EXISTS `index_delegation_records_companionId` ON `delegation_records` (`companionId`)",
        "CREATE INDEX IF NOT EXISTS `index_worldbooks_enabled` ON `worldbooks` (`enabled`)",
        "CREATE INDEX IF NOT EXISTS `index_worldbooks_companionId` ON `worldbooks` (`companionId`)",
    ]

    // ── 中文全文检索表 ──────────────────────────────────────────
    //
    // Android 侧为 FTS4 + SQLite 默认 simple 分词器；中文分词由
    // MessageSearchTokenizer 在应用层完成（unigram u<hex>z + bigram b<hex>x<hex>z，
    // 输出纯 ASCII），原文从不交给 SQLite 分词器。
    //
    // 因此 FTS4 与 FTS5 的匹配语义无差别，iOS 侧运行时探测后择一。
    // 注意：rowid 必须显式等于 messageId；该表无外键，一致性靠应用层维护。
    static let createFTSFTS4 =
        "CREATE VIRTUAL TABLE IF NOT EXISTS `message_search_index` USING FTS4(`tokens` TEXT NOT NULL)"

    static let createFTSFTS5 =
        "CREATE VIRTUAL TABLE IF NOT EXISTS `message_search_index` USING fts5(`tokens`)"

    /// 探测本机 SQLite 是否启用 FTS4（依赖 SQLITE_ENABLE_FTS3）。
    /// 不可用时回退 FTS5 —— 见 docs/ios-port-feasibility.md §9 的 V4。
    static func createFTSTable(_ db: Database) throws -> String {
        do {
            try db.execute(sql: createFTSFTS4)
            return "FTS4"
        } catch {
            try db.execute(sql: createFTSFTS5)
            return "FTS5"
        }
    }
}
