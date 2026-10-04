import Foundation
import os

/// 默认伴侣播种 —— 对应 Android 侧 `DefaultCompanionSeeder`。
///
/// ## 为什么需要「不再重建」的标志
/// Android 用 SharedPreferences 的 `deleted_by_user` 保证：
/// **用户手动删掉默认伴侣之后，App 不再把它自动建回来**。
/// 少了这个标志，用户每次删除后重启都会看到一个凭空冒出来的角色 —— 是很明显的体验缺陷。
///
/// iOS 用 `UserDefaults` 存同一语义。该标志非机密，放 Keychain 反而不合适
/// （会占用 iCloud 钥匙串条目，而它没有同步价值）。
///
/// ## 人设内容的来源
/// `YuNianSeed.defaultCompanionGirlfriend` 等常量由
/// `ios/Tools/generate_seeds.py` 从 `RolePresets.kt` **生成**，不手抄。
enum DefaultCompanionSeeder {

    private static let log = Logger(subsystem: "com.yunian.ai", category: "seed")

    /// 用户是否已手动删除过默认伴侣。
    static var deletedByUser: Bool {
        get { UserDefaults.standard.bool(forKey: YuNianSeed.deletedByUserDefaultsKey) }
        set { UserDefaults.standard.set(newValue, forKey: YuNianSeed.deletedByUserDefaultsKey) }
    }

    /// 首启播种入口（幂等）。
    @discardableResult
    static func seedIfNeeded(_ repo: CompanionRepository) -> Int64? {
        guard !deletedByUser else {
            log.info("用户已删除过默认伴侣，跳过播种")
            return nil
        }
        do {
            return try ensureDefault(repo)
        } catch {
            log.error("播种默认伴侣失败：\(String(describing: error), privacy: .public)")
            return nil
        }
    }

    /// 确保默认伴侣存在。
    ///
    /// 与 Android `ensureDefaultTestCompanion` 的「ensure」语义对齐：
    /// 已有默认伴侣（按标签或名字识别）时不重复插入。
    /// Android 还会把「旧版/不完整」的默认伴侣升级到完整体；
    /// iOS 是新平台、不存在历史遗留数据，因此只做存在性判断 —— 这一点是**有意的简化**，
    /// 不是遗漏（若将来支持导入 Android 数据，需要补上升级分支）。
    @discardableResult
    static func ensureDefault(
        _ repo: CompanionRepository,
        role: Role = .girlfriend
    ) throws -> Int64? {
        let existing = try repo.fetchAll()
        if existing.contains(where: isDefaultExperienceCompanion) {
            log.info("默认伴侣已存在，跳过插入")
            return nil
        }

        let seed = role == .girlfriend
            ? YuNianSeed.defaultCompanionGirlfriend
            : YuNianSeed.defaultCompanionBoyfriend

        let id = try repo.create(
            name: seed.name,
            personality: seed.personality,
            age: seed.age,
            backstory: seed.backstory,
            speakingStyle: seed.speakingStyle,
            avatarUrl: seed.avatarUrl,
            tags: seed.tags,
            rawPrompt: seed.rawPrompt,
            systemPrompt: seed.systemPrompt
        )
        log.info("已创建默认伴侣 #\(id) \(seed.name, privacy: .public)")
        return id
    }

    /// 用户删除伴侣后调用：若删的正是默认伴侣，置标志位以免自动重建。
    static func handleDeletion(of companion: CompanionRepository.Companion) {
        if isDefaultExperienceCompanion(companion) {
            deletedByUser = true
            log.info("默认伴侣被用户删除，已置 deleted_by_user 标志")
        }
    }

    /// 测试 / 「恢复默认角色」用。
    static func resetDeletionFlag() {
        deletedByUser = false
    }

    enum Role {
        case girlfriend
        case boyfriend
    }

    // MARK: - 识别（对应 Android 的 isDefaultExperienceCompanion）

    static func isDefaultExperienceCompanion(_ companion: CompanionRepository.Companion) -> Bool {
        // 先按标签（最可靠），再按名字兜底
        let tags = (companion.tags ?? "")
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces) }
        if tags.contains(YuNianSeed.defaultExperienceCompanionTag)
            || tags.contains(YuNianSeed.legacyDefaultCompanionTag) {
            return true
        }
        return companion.name == YuNianSeed.defaultCompanionGirlfriend.name
            || companion.name == YuNianSeed.defaultCompanionBoyfriend.name
    }
}
