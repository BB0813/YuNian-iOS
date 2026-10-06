import Foundation

/// 生图的全局配置与上次生图时间 —— 持久化。
///
/// ## 权威来源（第 160 轮）
/// Kotlin 用 DataStore 存这套（`AppSettingsStore.kt:83-125`）：
///
/// | key | 默认值 | 出处 |
/// |---|---|---|
/// | `image_gen_enabled` | `false` | :83-84、438 |
/// | `image_gen_model` | `""` | :96 |
/// | `image_gen_size` | `"1024x1024"` | :101-102、497-501 |
/// | `image_gen_count` | `1`（1–4） | :104-105、507-511 |
/// | `image_gen_probability` | `0`（0–100） | :107-108、518-523 |
/// | `image_gen_keywords` | 18 个默认词，`\n` 连接 | :110-113、180-187 |
/// | `image_gen_last_at_<companionId>` | `0` | :124-125、578-584 |
///
/// ## iOS 侧落点
/// Kotlin 的 DataStore 无对应物，故用 `UserDefaults`。
///
/// ⚠️ **这里不存 apiKey** —— apiKey 走 Keychain
/// （`KeychainStore.Key.apiKey`），与第 121 轮的设计一致：
/// 不明文落 SQLite/UserDefaults。
///
/// ⚠️ **未做 UI**：本 store 目前由「AI 生图」页将来保存时写入；
/// 在那之前 `load()` 恒返回 nil → 自动触发恒不生效，
/// 与 Kotlin 的 `DEFAULT_IMAGE_GEN_ENABLED = false` 行为一致。
/// 不放一个假开关在设置里 —— 那是装饰性功能。
struct ImageGenStore {

    /// 落库的配置快照。
    struct Prefs {
        var global = ImageGenTrigger.GlobalConfig()
        /// 该伴侣上次生图的 epoch 毫秒。0 = 从未生过。
        ///
        /// ⚠️ 单伴侣单值。Kotlin 的 key 是
        /// `image_gen_last_at_<companionId>`（按伴侣分立），
        /// 这里先按「当前伴侣」存一个值 —— 多伴侣分立属下轮。
        var lastGenAtMs: Int64 = 0
    }

    private enum K {
        static let enabled = "image_gen_enabled"
        static let model = "image_gen_model"
        static let size = "image_gen_size"
        static let count = "image_gen_count"
        static let probability = "image_gen_probability"
        static let keywords = "image_gen_keywords"
        static let template = "image_gen_prompt_template"
        static let cooldownMinutes = "image_gen_cooldown_minutes"
        static let lastGenAt = "image_gen_last_at"      // 单伴侣简化
        static let connectionMode = "image_gen_connection_mode"
    }

    private static let defaults = UserDefaults.standard

    /// 读取；**未启用时返回 nil**（调用方据此不触发）。
    static func load() -> Prefs? {
        guard defaults.bool(forKey: K.enabled) else { return nil }

        var g = ImageGenTrigger.GlobalConfig()
        g.enabled = true
        g.connectionMode = defaults.string(forKey: K.connectionMode) ?? "auto"
        g.model = defaults.string(forKey: K.model) ?? ""
        g.size = defaults.string(forKey: K.size) ?? "1024x1024"
        g.count = defaults.integer(forKey: K.count)      // 0 时回落 1
        if g.count < 1 { g.count = 1 }
        if g.count > ImageGenClient.maxImagesPerRequest { g.count = ImageGenClient.maxImagesPerRequest }
        g.probability = defaults.integer(forKey: K.probability)
        if g.probability < 0 { g.probability = 0 }
        if g.probability > 100 { g.probability = 100 }
        g.promptTemplate = defaults.string(forKey: K.template) ?? "{content}"
        g.cooldownMinutes = defaults.integer(forKey: K.cooldownMinutes)
        if g.cooldownMinutes < 0 { g.cooldownMinutes = 0 }

        let rawKeywords = defaults.string(forKey: K.keywords)
            ?? ImageGenTrigger.defaultKeywords.joined(separator: "\n")
        g.keywords = ImageGenTrigger.parseKeywords(rawKeywords)

        var p = Prefs()
        p.global = g
        p.lastGenAtMs = Int64(defaults.double(forKey: K.lastGenAt))
        return p
    }

    /// 写入。
    static func save(_ prefs: Prefs) {
        let d = defaults
        let g = prefs.global
        d.set(g.enabled, forKey: K.enabled)
        d.set(g.connectionMode, forKey: K.connectionMode)
        d.set(g.model, forKey: K.model)
        d.set(g.size, forKey: K.size)
        d.set(g.count, forKey: K.count)
        d.set(g.probability, forKey: K.probability)
        d.set(g.promptTemplate, forKey: K.promptTemplate)
        d.set(g.cooldownMinutes, forKey: K.cooldownMinutes)
        d.set(g.keywords.joined(separator: "\n"), forKey: K.keywords)
        d.set(Double(prefs.lastGenAtMs), forKey: K.lastGenAt)
    }

    /// 从「AI 生图」页的填写值构造并落盘。
    ///
    /// ⚠️ 第 160 轮：目前无调用方 —— ImageGenView 尚未接保存。
    /// 接上后自动触发才真正可用。
    static func enable(
        model: String,
        size: String,
        count: Int,
        probability: Int,
        cooldownMinutes: Int,
        template: String,
        keywordText: String
    ) {
        var g = ImageGenTrigger.GlobalConfig()
        g.enabled = true
        g.model = model.trimmingCharacters(in: .whitespacesAndNewlines)
        g.size = size
        g.count = min(max(count, 1), ImageGenClient.maxImagesPerRequest)
        g.probability = min(max(probability, 0), 100)
        g.cooldownMinutes = max(cooldownMinutes, 0)
        g.promptTemplate = template.isEmpty ? "{content}" : template
        g.keywords = ImageGenTrigger.parseKeywords(keywordText)
        var p = Prefs()
        p.global = g
        p.lastGenAtMs = load()?.lastGenAtMs ?? 0
        save(p)
    }

    /// 关闭自动触发（清掉 enabled，其余配置保留）。
    static func disable() {
        defaults.set(false, forKey: K.enabled)
    }
}
