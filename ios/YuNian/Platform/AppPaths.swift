import Foundation

/// 路径解析 —— 替代 Android 侧的 `Context.getDatabasePath()` / `filesDir` / `cacheDir`。
///
/// ## 为什么数据库要放 Application Support 而不是 Documents
/// Rust 侧（`agent-native/src/native_gateway.rs`）按宿传入的 `db_path` 打开数据库，
/// 并在**同目录**写 `rust_agent_log.txt` 与 `rust_panic_last.txt`。Android 侧传的是
/// `context.getDatabasePath("yunian_database")`（无 `.db` 后缀，勿改）。
///
/// iOS 侧选择 Application Support：
///   - Documents 会被 iCloud 备份并可能对用户可见，日志与数据库都不该放那里；
///   - Caches 会被系统在存储紧张时回收，数据库放这里会丢数据。
/// Application Support 默认参与备份，但日志文件可用 `isExcludedFromBackup` 排除。
enum AppPaths {

    /// 与 Android 侧 `AppDatabase.DB_NAME` 保持一致（**无 `.db` 后缀**）。
    /// Rust 侧不关心文件名，但保持一致便于跨端排查与「导入 Android 库」。
    static let databaseName = "yunian_database"

    static let rustLogName = "rust_agent_log.txt"
    static let rustPanicName = "rust_panic_last.txt"

    enum PathError: Error {
        case unavailable(String)
    }

    /// Application Support 根目录（首次访问时创建）。
    static func applicationSupport() throws -> URL {
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let dir = base.appendingPathComponent("YuNian", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// 数据库文件完整路径 —— 传给 Rust 的 `AgentGlobalConfig.db_path`。
    static func databaseURL() throws -> URL {
        try applicationSupport().appendingPathComponent(databaseName, isDirectory: false)
    }

    /// Rust 侧日志落点（与 db 同目录，跟随 Rust 的约定）。
    static func rustLogURL() throws -> URL {
        try applicationSupport().appendingPathComponent(rustLogName, isDirectory: false)
    }

    /// Agent 技能正文目录，对应 Android 的 `filesDir/agent_skills/<skillId>/content.md`。
    static func agentSkillsDirectory() throws -> URL {
        let dir = try applicationSupport().appendingPathComponent("agent_skills", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// 表情包目录，对应 Android 的 `filesDir/stickers/`。
    static func stickersDirectory() throws -> URL {
        let dir = try applicationSupport().appendingPathComponent("stickers", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// 生图产物目录（第 167 轮）。
    ///
    /// 对应 Kotlin `ImageGenerationService.kt:475` 的
    /// `GENERATED_IMAGE_DIR = "generated_images"`，落在
    /// `getExternalFilesDir`（ImageGenerationProvider.kt:51-55 要求持久目录，
    /// **不可用 cache** —— 否则重启丢图）。
    ///
    /// iOS 侧对应物是 Application Support（与数据库/表情同根），
    /// 已由 `applicationSupport()` 保证创建。
    static func generatedImagesDirectory() throws -> URL {
        let dir = try applicationSupport().appendingPathComponent("generated_images", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// 生图文件的完整路径。
    ///
    /// 命名与 Kotlin 侧对齐：「时间戳_序号.png」，
    /// 便于跨端排查时不至于把两端的产物搞混。
    static func generatedImageURL(messageId: Int64) throws -> URL {
        try generatedImagesDirectory()
            .appendingPathComponent("gen_\(messageId).png", isDirectory: false)
    }

    /// 审计日志目录，对应 Android 的 `filesDir/lianyu_audit`。
    static func auditDirectory() throws -> URL {
        let dir = try applicationSupport().appendingPathComponent("audit", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// 临时音频等缓存，对应 Android 的 `context.cacheDir/tts_audio`。
    static func ttsCacheDirectory() throws -> URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("tts_audio", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// 首次启动时把日志与 panic 文件排除出 iCloud 备份（诊断产物无需备份）。
    static func excludeDiagnosticsFromBackup() {
        for url in [try? rustLogURL(), try? rustPanicURL()].compactMap({ $0 }) {
            var resourceValues = URLResourceValues()
            resourceValues.isExcludedFromBackup = true
            var mutable = url
            try? mutable.setResourceValues(resourceValues)
        }
    }

    static func rustPanicURL() throws -> URL {
        try applicationSupport().appendingPathComponent(rustPanicName, isDirectory: false)
    }
}
