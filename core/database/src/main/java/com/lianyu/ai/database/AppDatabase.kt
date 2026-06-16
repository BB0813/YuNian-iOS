package com.lianyu.ai.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.dao.ApiConfigDao
import com.lianyu.ai.database.dao.ChatGroupDao
import com.lianyu.ai.database.dao.ChatMessageDao
import com.lianyu.ai.database.dao.CompanionDao
import com.lianyu.ai.database.dao.GroupMessageDao
import com.lianyu.ai.database.dao.KeywordDao
import com.lianyu.ai.database.dao.MemoryDao
import com.lianyu.ai.database.dao.QuizQuestionDao
import com.lianyu.ai.database.dao.TokenUsageDao
import com.lianyu.ai.database.model.ApiConfig
import com.lianyu.ai.database.model.ApiProvider
import com.lianyu.ai.database.model.ChatGroup
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.database.model.FileFormat
import com.lianyu.ai.database.model.GroupMessage
import com.lianyu.ai.database.model.KeywordEntity
import com.lianyu.ai.database.model.MemoryCategory
import com.lianyu.ai.database.model.MemoryEntry
import com.lianyu.ai.database.model.MessageType
import com.lianyu.ai.database.model.QuizQuestionEntity
import com.lianyu.ai.database.model.TempMemory
import com.lianyu.ai.database.model.TokenUsage
import java.io.File

@Database(
    entities = [
        CompanionEntity::class,
        ChatMessage::class,
        ApiConfig::class,
        MemoryEntry::class,
        TempMemory::class,
        ChatGroup::class,
        GroupMessage::class,
        KeywordEntity::class,
        QuizQuestionEntity::class,
        TokenUsage::class
    ],
    version = 17,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun companionDao(): CompanionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun apiConfigDao(): ApiConfigDao
    abstract fun memoryDao(): MemoryDao
    abstract fun chatGroupDao(): ChatGroupDao
    abstract fun groupMessageDao(): GroupMessageDao
    abstract fun keywordDao(): KeywordDao
    abstract fun quizQuestionDao(): QuizQuestionDao
    abstract fun tokenUsageDao(): TokenUsageDao

    companion object {
        private const val DB_NAME = "lianyu_database"
        private val LOCK = Any()

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(LOCK) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun resetForTest() {
            synchronized(LOCK) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        fun shutdown() {
            synchronized(LOCK) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            autoBackupIfNeeded(context)
            return openVerifiedDatabase(context, allowRecovery = true)
        }

        private fun openVerifiedDatabase(context: Context, allowRecovery: Boolean): AppDatabase {
            var candidate: AppDatabase? = null
            return try {
                candidate = createDatabase(context)
                verifyDatabaseCanOpen(candidate)
                candidate
            } catch (e: Throwable) {
                runCatching { candidate?.close() }
                if (!allowRecovery) throw e

                val isSchemaMismatch = (e is IllegalStateException && 
                    (e.message?.contains("cannot verify the data integrity") == true ||
                        e.message?.contains("identity hash") == true)) ||
                    (e.cause is IllegalStateException && 
                    (e.cause?.message?.contains("cannot verify the data integrity") == true ||
                        e.cause?.message?.contains("identity hash") == true)) ||
                    (e.cause?.cause is IllegalStateException &&
                    (e.cause?.cause?.message?.contains("cannot verify the data integrity") == true ||
                        e.cause?.cause?.message?.contains("identity hash") == true))

                if (isSchemaMismatch) {
                    SecureLog.w("AppDatabase", "Schema mismatch detected, rebuilding database...")
                    backupBeforeRecovery(context)
                    deleteDatabaseFiles(context)
                    return createDatabase(context).also {
                        verifyDatabaseCanOpen(it)
                        SecureLog.i("AppDatabase", "Database rebuilt successfully")
                    }
                }

                SecureLog.w("AppDatabase", "Database corrupted, attempting recovery...")
                backupBeforeRecovery(context)
                recoverDatabase(context)
                createDatabase(context).also {
                    verifyDatabaseCanOpen(it)
                    SecureLog.i("AppDatabase", "Database recovered successfully from backup")
                }
            }
        }

        private fun deleteDatabaseFiles(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            dbFile.delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            File(dbFile.path + "-journal").delete()
        }

        private fun backupBeforeRecovery(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) return

            val recoveryDir = File(dbFile.parentFile, "recovery")
            recoveryDir.mkdirs()
            val timestamp = System.currentTimeMillis().toString()

            listOf(
                dbFile,
                File(dbFile.path + "-wal"),
                File(dbFile.path + "-shm"),
                File(dbFile.path + "-journal")
            ).filter { it.exists() }.forEach { file ->
                val target = File(recoveryDir, "${file.name}.corrupted_$timestamp")
                runCatching { file.copyTo(target, overwrite = true) }
            }
        }

        private fun recoverDatabase(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            val recoveryDir = File(dbFile.parentFile, "recovery")

            if (!recoveryDir.exists()) return

            val backups = recoveryDir.listFiles()
                ?.filter { it.name.endsWith(".db") || it.name.contains("corrupted") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            if (backups.isNotEmpty()) {
                val latestBackup = backups.first()
                if (latestBackup.length() > 1024) {
                    latestBackup.copyTo(dbFile, overwrite = true)
                    SecureLog.i("AppDatabase", "Restored database from ${latestBackup.name}")
                    return
                }
            }

            dbFile.delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            File(dbFile.path + "-journal").delete()
        }

        private fun createDatabase(context: Context): AppDatabase {
            // EncryptedFile-based DB encryption — disabled for troubleshooting
            // com.lianyu.ai.security.EncryptedDatabaseWrapper.prepareDatabase(context)
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )
                .addMigrations(*MIGRATIONS)
                .fallbackToDestructiveMigration()
                .build()
        }

        private fun verifyDatabaseCanOpen(database: AppDatabase) {
            database.openHelper.writableDatabase.query("PRAGMA user_version").close()
        }

        /**
         * Move unreadable legacy DB files out of the way before creating a new DB.
         * This handles old SQLCipher/native-incompatible databases without crashing
         * the first main-screen render. Files stay inside app-private storage.
         */
        private fun backupBrokenDatabase(context: Context) {
            val dbFile = context.applicationContext.getDatabasePath(DB_NAME)
            val candidates = listOf(
                dbFile,
                File(dbFile.path + "-wal"),
                File(dbFile.path + "-shm"),
                File(dbFile.path + "-journal")
            ).filter { it.exists() }

            if (candidates.isEmpty()) return

            val backupDir = File(dbFile.parentFile, "recovery")
            backupDir.mkdirs()
            val suffix = System.currentTimeMillis().toString()

            candidates.forEach { file ->
                val target = File(backupDir, "${file.name}.broken.$suffix")
                if (!file.renameTo(target)) {
                    runCatching { file.copyTo(target, overwrite = true) }
                    runCatching { file.delete() }
                }
            }
        }

        private fun addColumnIfMissing(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnName: String,
            columnDefinition: String
        ) {
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == columnName) return
                }
            }
            db.execSQL("ALTER TABLE `$tableName` ADD COLUMN $columnName $columnDefinition")
        }

        private fun migrateLegacyTo6(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `companions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `avatarUrl` TEXT,
                    `age` INTEGER,
                    `personality` TEXT NOT NULL DEFAULT '',
                    `backstory` TEXT,
                    `speakingStyle` TEXT,
                    `tags` TEXT,
                    `rawPrompt` TEXT,
                    `systemPrompt` TEXT,
                    `intimacy` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL DEFAULT 0,
                    `updatedAt` INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            addColumnIfMissing(db, "companions", "avatarUrl", "TEXT")
            addColumnIfMissing(db, "companions", "age", "INTEGER")
            addColumnIfMissing(db, "companions", "personality", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "companions", "backstory", "TEXT")
            addColumnIfMissing(db, "companions", "speakingStyle", "TEXT")
            addColumnIfMissing(db, "companions", "tags", "TEXT")
            addColumnIfMissing(db, "companions", "rawPrompt", "TEXT")
            addColumnIfMissing(db, "companions", "systemPrompt", "TEXT")
            addColumnIfMissing(db, "companions", "intimacy", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "companions", "createdAt", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "companions", "updatedAt", "INTEGER NOT NULL DEFAULT 0")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `chat_messages` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `companionId` INTEGER NOT NULL,
                    `content` TEXT NOT NULL,
                    `isFromUser` INTEGER NOT NULL,
                    `timestamp` INTEGER NOT NULL DEFAULT 0,
                    `type` TEXT NOT NULL DEFAULT 'TEXT',
                    `searchContent` TEXT NOT NULL DEFAULT '',
                    `fileFormat` TEXT NOT NULL DEFAULT 'TEXT',
                    `linkString` TEXT NOT NULL DEFAULT '',
                    FOREIGN KEY(`companionId`) REFERENCES `companions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            addColumnIfMissing(db, "chat_messages", "type", "TEXT NOT NULL DEFAULT 'TEXT'")
            addColumnIfMissing(db, "chat_messages", "searchContent", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "chat_messages", "fileFormat", "TEXT NOT NULL DEFAULT 'TEXT'")
            addColumnIfMissing(db, "chat_messages", "linkString", "TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_companionId` ON `chat_messages` (`companionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_companionId_timestamp` ON `chat_messages` (`companionId`, `timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_companionId_fileFormat` ON `chat_messages` (`companionId`, `fileFormat`)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `api_configs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `provider` TEXT NOT NULL,
                    `name` TEXT NOT NULL DEFAULT '',
                    `apiKey` TEXT NOT NULL,
                    `baseUrl` TEXT NOT NULL,
                    `model` TEXT NOT NULL,
                    `temperature` REAL NOT NULL DEFAULT 0.7,
                    `maxTokens` INTEGER,
                    `isEnabled` INTEGER NOT NULL DEFAULT 1,
                    `connectionTested` INTEGER NOT NULL DEFAULT 0,
                    `connectionTestedAt` INTEGER NOT NULL DEFAULT 0,
                    `latencyMs` INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            addColumnIfMissing(db, "api_configs", "id", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "api_configs", "name", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "api_configs", "temperature", "REAL NOT NULL DEFAULT 0.7")
            addColumnIfMissing(db, "api_configs", "maxTokens", "INTEGER")
            addColumnIfMissing(db, "api_configs", "isEnabled", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing(db, "api_configs", "connectionTested", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "api_configs", "connectionTestedAt", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "api_configs", "latencyMs", "INTEGER NOT NULL DEFAULT 0")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `memory_entries` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `companionId` INTEGER NOT NULL,
                    `content` TEXT NOT NULL,
                    `category` TEXT NOT NULL DEFAULT 'FACT',
                    `importance` REAL NOT NULL DEFAULT 0.5,
                    `context` TEXT NOT NULL DEFAULT '',
                    `accessCount` INTEGER NOT NULL DEFAULT 1,
                    `timestamp` INTEGER NOT NULL DEFAULT 0,
                    `lastAccessed` INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            addColumnIfMissing(db, "memory_entries", "category", "TEXT NOT NULL DEFAULT 'FACT'")
            addColumnIfMissing(db, "memory_entries", "importance", "REAL NOT NULL DEFAULT 0.5")
            addColumnIfMissing(db, "memory_entries", "context", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "memory_entries", "accessCount", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing(db, "memory_entries", "lastAccessed", "INTEGER NOT NULL DEFAULT 0")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `temp_memory` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `companionId` INTEGER NOT NULL,
                    `userInput` TEXT NOT NULL,
                    `botResponse` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `chat_groups` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `avatarUrl` TEXT,
                    `companionIds` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL DEFAULT 0,
                    `updatedAt` INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `group_messages` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `groupId` INTEGER NOT NULL,
                    `companionId` INTEGER NOT NULL,
                    `content` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL DEFAULT 0,
                    `searchContent` TEXT NOT NULL DEFAULT '',
                    `fileFormat` TEXT NOT NULL DEFAULT 'TEXT',
                    `linkString` TEXT NOT NULL DEFAULT '',
                    FOREIGN KEY(`groupId`) REFERENCES `chat_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_messages_groupId` ON `group_messages` (`groupId`)")
            addColumnIfMissing(db, "group_messages", "searchContent", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "group_messages", "fileFormat", "TEXT NOT NULL DEFAULT 'TEXT'")
            addColumnIfMissing(db, "group_messages", "linkString", "TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_messages_groupId_timestamp` ON `group_messages` (`groupId`, `timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_messages_groupId_fileFormat` ON `group_messages` (`groupId`, `fileFormat`)")
        }

        val MIGRATION_1_6 = object : Migration(1, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateLegacyTo6(db)
        }

        val MIGRATION_2_6 = object : Migration(2, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateLegacyTo6(db)
        }

        val MIGRATION_3_6 = object : Migration(3, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateLegacyTo6(db)
        }

        val MIGRATION_4_6 = object : Migration(4, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateLegacyTo6(db)
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateLegacyTo6(db)
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "api_configs", "connectionTested", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "api_configs", "connectionTestedAt", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        private fun migrateApiConfigsTo9(db: SupportSQLiteDatabase) {
            addColumnIfMissing(db, "api_configs", "name", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "api_configs", "temperature", "REAL NOT NULL DEFAULT 0.7")
            addColumnIfMissing(db, "api_configs", "maxTokens", "INTEGER")
            addColumnIfMissing(db, "api_configs", "isEnabled", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing(db, "api_configs", "connectionTested", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "api_configs", "connectionTestedAt", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "api_configs", "latencyMs", "INTEGER NOT NULL DEFAULT 0")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `api_configs_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `provider` TEXT NOT NULL,
                    `name` TEXT NOT NULL DEFAULT '',
                    `apiKey` TEXT NOT NULL,
                    `baseUrl` TEXT NOT NULL,
                    `model` TEXT NOT NULL,
                    `temperature` REAL NOT NULL DEFAULT 0.7,
                    `maxTokens` INTEGER,
                    `isEnabled` INTEGER NOT NULL DEFAULT 1,
                    `connectionTested` INTEGER NOT NULL DEFAULT 0,
                    `connectionTestedAt` INTEGER NOT NULL DEFAULT 0,
                    `latencyMs` INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("""
                INSERT INTO `api_configs_new` (`id`, `provider`, `name`, `apiKey`, `baseUrl`, `model`, `temperature`, `maxTokens`, `isEnabled`, `connectionTested`, `connectionTestedAt`, `latencyMs`)
                SELECT `id`, `provider`, `name`, `apiKey`, `baseUrl`, `model`, `temperature`, `maxTokens`, `isEnabled`, `connectionTested`, `connectionTestedAt`, `latencyMs`
                FROM `api_configs`
            """.trimIndent())
            db.execSQL("DROP TABLE `api_configs`")
            db.execSQL("ALTER TABLE `api_configs_new` RENAME TO `api_configs`")
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateApiConfigsTo9(db)
        }

        private fun migrateChatStorageTo10(db: SupportSQLiteDatabase) {
            addColumnIfMissing(db, "chat_messages", "searchContent", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "chat_messages", "fileFormat", "TEXT NOT NULL DEFAULT 'TEXT'")
            addColumnIfMissing(db, "chat_messages", "linkString", "TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE `chat_messages` SET `searchContent` = `content` WHERE `searchContent` = ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_companionId_timestamp` ON `chat_messages` (`companionId`, `timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_companionId_fileFormat` ON `chat_messages` (`companionId`, `fileFormat`)")

            addColumnIfMissing(db, "group_messages", "searchContent", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "group_messages", "fileFormat", "TEXT NOT NULL DEFAULT 'TEXT'")
            addColumnIfMissing(db, "group_messages", "linkString", "TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE `group_messages` SET `searchContent` = `content` WHERE `searchContent` = ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_messages_groupId_timestamp` ON `group_messages` (`groupId`, `timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_messages_groupId_fileFormat` ON `group_messages` (`groupId`, `fileFormat`)")
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateChatStorageTo10(db)
        }

        private fun migrateSecurityTablesTo11(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `keywords` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `keyword` TEXT NOT NULL,
                    `pattern` TEXT,
                    `level` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `banDays` INTEGER NOT NULL,
                    `isEnabled` INTEGER NOT NULL DEFAULT 1,
                    `createdAt` INTEGER NOT NULL DEFAULT 0,
                    `checksum` TEXT NOT NULL DEFAULT ''
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_keywords_level` ON `keywords` (`level`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_keywords_type` ON `keywords` (`type`)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `quiz_questions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `question` TEXT NOT NULL,
                    `options` TEXT NOT NULL,
                    `correctIndex` INTEGER NOT NULL,
                    `category` TEXT NOT NULL,
                    `difficulty` TEXT NOT NULL DEFAULT 'MEDIUM',
                    `isEnabled` INTEGER NOT NULL DEFAULT 1,
                    `createdAt` INTEGER NOT NULL DEFAULT 0,
                    `checksum` TEXT NOT NULL DEFAULT ''
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_quiz_category` ON `quiz_questions` (`category`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_quiz_difficulty` ON `quiz_questions` (`difficulty`)")
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateSecurityTablesTo11(db)
        }

        private fun migrateTokenUsageTo12(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `token_usage` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `companionId` INTEGER NOT NULL,
                    `date` TEXT NOT NULL,
                    `inputTokens` INTEGER NOT NULL DEFAULT 0,
                    `outputTokens` INTEGER NOT NULL DEFAULT 0,
                    `totalTokens` INTEGER NOT NULL DEFAULT 0,
                    `requestCount` INTEGER NOT NULL DEFAULT 0,
                    `timestamp` INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_token_usage_companionId_date` ON `token_usage` (`companionId`, `date`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_token_usage_date` ON `token_usage` (`date`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_token_usage_companionId` ON `token_usage` (`companionId`)")
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateTokenUsageTo12(db)
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "chat_messages", "deviceId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "memory_entries", "deviceId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "temp_memory", "deviceId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "token_usage", "deviceId", "TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateChatStorageTo10(db)
                addColumnIfMissing(db, "token_usage", "deviceId", "TEXT NOT NULL DEFAULT ''")
                db.execSQL("DROP INDEX IF EXISTS `index_token_usage_companionId_date`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_token_usage_companionId_date_deviceId` ON `token_usage` (`companionId`, `date`, `deviceId`)")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "api_configs", "extraApiKeys", "TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 确保 deviceId 列存在（处理跳过 12→13 的旧 DB）
                addColumnIfMissing(db, "chat_messages", "deviceId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "memory_entries", "deviceId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "temp_memory", "deviceId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "token_usage", "deviceId", "TEXT NOT NULL DEFAULT ''")
                // 清理可能残留的多余索引
                db.execSQL("DROP INDEX IF EXISTS index_chat_messages_companionId_deviceId")
                db.execSQL("DROP INDEX IF EXISTS index_memory_entries_companionId_deviceId")
                db.execSQL("DROP INDEX IF EXISTS index_temp_memory_companionId_deviceId")
            }
        }

        val MIGRATIONS = arrayOf(
            MIGRATION_1_6,
            MIGRATION_2_6,
            MIGRATION_3_6,
            MIGRATION_4_6,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17
        )

        private var lastBackupTime: Long = 0L
        private val BACKUP_INTERVAL_MS = 24 * 60 * 60 * 1000L

        fun backupDatabase(context: Context): Boolean {
            return try {
                val dbFile = context.getDatabasePath(DB_NAME)
                if (!dbFile.exists()) return false

                val backupDir = File(context.filesDir, "db_backup")
                backupDir.mkdirs()

                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    .format(java.util.Date())

                val filesToBackup = listOf(
                    dbFile,
                    File(dbFile.path + "-wal"),
                    File(dbFile.path + "-shm"),
                    File(dbFile.path + "-journal")
                ).filter { it.exists() && it.length() > 0 }

                if (filesToBackup.isEmpty()) return false

                val backupSubDir = File(backupDir, "backup_$timestamp")
                backupSubDir.mkdirs()

                filesToBackup.forEach { file ->
                    val target = File(backupSubDir, file.name)
                    file.copyTo(target, overwrite = true)
                }

                lastBackupTime = System.currentTimeMillis()
                true
            } catch (e: Exception) {
                false
            }
        }

        fun restoreFromBackup(context: Context): Boolean {
            return try {
                val backupDir = File(context.filesDir, "db_backup")
                if (!backupDir.exists()) return false

                val backups = backupDir.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("backup_") }
                    ?.sortedByDescending { it.name }
                    ?: emptyList()

                if (backups.isEmpty()) return false

                val latestBackup = backups.first()
                val dbFile = context.getDatabasePath(DB_NAME)

                INSTANCE?.close()
                INSTANCE = null

                latestBackup.listFiles()?.forEach { backupFile ->
                    val target = when {
                        backupFile.name == DB_NAME -> dbFile
                        else -> File(dbFile.parentFile, backupFile.name)
                    }
                    backupFile.copyTo(target, overwrite = true)
                }

                true
            } catch (e: Exception) {
                false
            }
        }

        fun getBackupInfo(context: Context): List<Map<String, Any>> {
            val backupDir = File(context.filesDir, "db_backup")
            if (!backupDir.exists()) return emptyList()

            return backupDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("backup_") }
                ?.sortedByDescending { it.name }
                ?.map { backup ->
                    val totalSize = backup.listFiles()?.sumOf { it.length() } ?: 0L
                    mapOf(
                        "name" to backup.name,
                        "timestamp" to backup.name.removePrefix("backup_"),
                        "size" to totalSize,
                        "fileCount" to (backup.listFiles()?.size ?: 0)
                    )
                } ?: emptyList()
        }

        fun autoBackupIfNeeded(context: Context) {
            val now = System.currentTimeMillis()
            if (now - lastBackupTime >= BACKUP_INTERVAL_MS) {
                backupDatabase(context)
            }
        }

        fun clearOldBackups(keepCount: Int = 5): Int {
            return try {
                val backupDir = File(android.os.Environment.getDataDirectory(),
                    "data/${java.lang.System.getProperty("package.name", "")}/files/db_backup")
                if (!backupDir.exists()) return 0

                val backups = backupDir.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("backup_") }
                    ?.sortedByDescending { it.name }
                    ?: emptyList()

                var deletedCount = 0
                backups.drop(keepCount).forEach { backup ->
                    if (backup.deleteRecursively()) deletedCount++
                }
                deletedCount
            } catch (e: Exception) {
                0
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun fromApiProvider(value: ApiProvider): String = value.name

    @TypeConverter
    fun toApiProvider(value: String?): ApiProvider {
        if (value.isNullOrBlank()) return ApiProvider.OPENAI
        return runCatching { ApiProvider.valueOf(value.trim().uppercase()) }.getOrDefault(ApiProvider.OPENAI)
    }

    @TypeConverter
    fun fromMessageType(value: MessageType): String = value.name

    @TypeConverter
    fun toMessageType(value: String?): MessageType {
        if (value.isNullOrBlank()) return MessageType.TEXT
        return runCatching { MessageType.valueOf(value.trim().uppercase()) }.getOrDefault(MessageType.TEXT)
    }

    @TypeConverter
    fun fromFileFormat(value: FileFormat): String = value.name

    @TypeConverter
    fun toFileFormat(value: String?): FileFormat {
        if (value.isNullOrBlank()) return FileFormat.UNKNOWN
        return runCatching { FileFormat.valueOf(value.trim().uppercase()) }.getOrDefault(FileFormat.UNKNOWN)
    }

    @TypeConverter
    fun fromMemoryCategory(value: MemoryCategory): String = value.name

    @TypeConverter
    fun toMemoryCategory(value: String?): MemoryCategory {
        if (value.isNullOrBlank()) return MemoryCategory.FACT
        return runCatching { MemoryCategory.valueOf(value.trim().uppercase()) }.getOrDefault(MemoryCategory.FACT)
    }
}
