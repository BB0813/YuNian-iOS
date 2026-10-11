import Foundation

/// 语音方案 —— 全局线路设置 + **按角色音色覆盖** + 试听。
///
/// ## 为什么音色要能按角色分开
/// 参照（Aru `settings.voice`）：「从已配置的功能模型线路里选择回答朗读线路、
/// 模型、音色和格式」+ `voice.profiles`「语音方案」。
/// 同一条语音渠道在不同角色上换音色是常见诉求，而**渠道行只有一个 `model`
/// 字段，没有音色的位置** —— 覆盖值只能挂在角色上。
///
/// 现在的问题正是"全局一个音色"：给一个角色配了某个音色，想给另一个换
/// 就得来回改，改完两个角色一起变。
///
/// ## 存哪
/// `app_meta`：`voice.voice.<companionId>`。空值/缺键 = 该角色没有覆盖（用全局）。
/// 与 `feature_route.tts.*` 同一张 KV 表，**不新增表、不改 schema**。
enum VoiceProfileStore {

    static func overrideKey(companionId: Int64) -> String {
        "voice.voice.\(companionId)"
    }

    /// 该角色的音色覆盖（nil = 没有覆盖，用全局）。
    static func override(companionId: Int64, repository: ApiConfigRepository?) -> String? {
        guard let repository, companionId > 0 else { return nil }
        // ⚠️ 第 208 轮：这里**必须拆成两句**。
        //
        // 原来写成 `guard let raw = try? repository.metaValue(forKey:
        // overrideKey(companionId: companionId))` —— 一行里套了自动闭包
        // （`try?`）与嵌套调用，macOS 的 Swift 报
        // `failed to produce diagnostic for expression`（**编译器崩溃**，
        // 不是普通类型错误）：本地 WSL 的 Swift 能过、CI 的 Xcode 工具链不能。
        // 拆开之后两边都过，且更清楚。
        let key = overrideKey(companionId: companionId)
        // ⚠️ 写成与 `TtsSettings.load` **逐字同形**的 `if let raw = try? f(key)`：
        // 那是 CI 一直在编的写法。别在这里造新形状。
        if let raw = try? repository.metaValue(forKey: key) {
            let trimmed = raw.trimmingCharacters(in: .whitespaces)
            return trimmed.isEmpty ? nil : trimmed
        }
        return nil
    }

    /// 写该角色的音色覆盖。空串 = 清除覆盖（`setMetaValue` 对空值是**删键**）。
    static func setOverride(_ voice: String, companionId: Int64,
                            repository: ApiConfigRepository?) throws {
        guard let repository, companionId > 0 else { return }
        try repository.setMetaValue(voice.trimmingCharacters(in: .whitespaces),
                                    forKey: overrideKey(companionId: companionId))
    }

    /// 实际使用的音色：**角色覆盖 > 全局**。
    ///
    /// 写成纯函数是为了可单测（存储读写在调用方）；
    /// 这条优先级一旦写反，表现是"给角色单独配的音色没生效"。
    static func effectiveVoice(override: String?, global: String) -> String {
        guard let override else { return global }
        let trimmed = override.trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty ? global : trimmed
    }

    /// 组合入口：从库里取该角色的有效音色。
    static func effectiveVoice(companionId: Int64?, global: String,
                               repository: ApiConfigRepository?) -> String {
        let override = companionId.flatMap { override(companionId: $0, repository: repository) }
        return effectiveVoice(override: override, global: global)
    }

    /// 哪些角色**配了**独立音色（界面用来显示角标 / 概览）。
    static func overriddenCompanionIds(_ ids: [Int64],
                                       repository: ApiConfigRepository?) -> Set<Int64> {
        guard let repository else { return [] }
        var result: Set<Int64> = []
        for id in ids where override(companionId: id, repository: repository) != nil {
            result.insert(id)
        }
        return result
    }
}
