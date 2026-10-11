import Foundation

/// 回复语言（第 218 轮）。
///
/// ## 它解决的是一条**用户反馈**，不是我想加功能
/// 反馈：「回复有时是英文，不是中文」。
/// 查下来的根因是：**引擎里根本没有语言这个概念** —— 全仓 Rust 只有一处
/// `role_label` 的中文映射，提示词里**没有任何语言指令**。
/// 模型用什么语言回答，全靠它自己从人设与历史里猜；
/// 人设/历史里英文成分偏多、或模型本身偏英文，就会突然换语言，
/// 而用户没有任何可纠正的地方（改人设是另一回事，且会改坏角色）。
///
/// ## 它落在哪
/// 经 `AgentSettings.replyLanguage` → `settings_json.reply_language`
/// → Rust `PromptOrchestratorOptions.reply_language`
/// → 在【角色设定】之后注入一段【回复语言】。
/// **加这个键不需要改 UniFFI 签名**（settings 本来就是自由 JSON 字符串），
/// 但**需要引擎重建后才会生效**（CI 每次都会重建）。
///
/// ## ⚠️ 三条边界（界面文案必须与之一致）
/// 1. **默认「跟随模型」= 不下发这个键**（不是下发空串）——
///    Android 不下发，行为必须与之前逐字一致。
/// 2. 它是**指令**不是硬约束：长上下文、强人设、模型自身偏好都可能压过它。
///    所以文案写"要求"，不写"保证"。
/// 3. 值是**语言名**（`简体中文`）而不是 `zh-Hans` 之类的码 ——
///    少一层映射就少一处"码对了但文案不对"的可能，也方便将来让用户自定义。
enum ReplyLanguage: String, CaseIterable, Identifiable {
    /// 不下发（默认）：保持与 Android 完全一致的行为。
    case followModel = "follow"
    case simplifiedChinese = "zh-Hans"
    case traditionalChinese = "zh-Hant"
    case english = "en"
    case japanese = "ja"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .followModel: return "跟随模型"
        case .simplifiedChinese: return "简体中文"
        case .traditionalChinese: return "繁體中文"
        case .english: return "English"
        case .japanese: return "日本語"
        }
    }

    /// 下发给引擎的**语言名**。`nil` = 不下发这个键。
    var languageName: String? {
        switch self {
        case .followModel: return nil
        case .simplifiedChinese: return "简体中文"
        // 注意：用「繁體中文」而不是「繁体中文」—— 简繁两种写法在提示词里
        // 会轻微影响模型自身的用字倾向，与选项标题保持一致最稳。
        case .traditionalChinese: return "繁體中文"
        case .english: return "English"
        case .japanese: return "日本語"
        }
    }

    // MARK: - 存取

    /// app_meta 键（与 `feature_route.*` / `context.*` 同级）。
    static let storageKey = "chat.reply_language"

    static func load(from repo: ApiConfigRepository) -> ReplyLanguage {
        // 读不到 / 值不认识（例如回退到旧版本）= 跟随模型，绝不猜。
        guard let raw = try? repo.metaValue(forKey: storageKey),
              let value = ReplyLanguage(rawValue: raw) else { return .followModel }
        return value
    }

    func save(to repo: ApiConfigRepository) throws {
        try repo.setMetaValue(rawValue, forKey: Self.storageKey)
    }
}
