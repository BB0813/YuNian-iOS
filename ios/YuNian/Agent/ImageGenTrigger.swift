import Foundation

/// 生图触发判定 —— 逐条复刻 Kotlin `ImageGenTriggerLogic` / `ImageGenCoordinator`。
///
/// ## 权威来源（第 156 轮，子代理只读勘察 + 本轮逐行核对）
/// `feature/chat/.../ui/viewmodel/ImageGenTrigger.kt`：
///
/// | 项 | Kotlin 值 | 行号 |
/// |---|---|---|
/// | 入口 | `suspend fun maybeTriggerImage(userText, aiText)` | :301 |
/// | 纯判定 | `fun decide(global, effective, userText, aiText, lastGenAtMs, nowMs, randomRoll)` | :168-176 |
/// | 随机源 | `Random.nextInt(100)` 单次/次，整数 0–99 | :283 |
/// | 判定顺序 | 总开关 → 配置完整 → 关键词 → 概率 → 冷却 | :177-199 |
/// | 冷却 | `cooldownMs > 0 && lastGenAtMs > 0 && now - last < cooldownMs` | :194-196 |
/// | prompt 上限 | `PROMPT_MAX_LENGTH = 800` | :123、:243 |
/// | 默认关键词 | 18 个，`AppSettingsStore.kt:180-187` | — |
/// | 默认开关/概率/冷却 | false / 0 / 3 分钟 | :59-69 |
///
/// ## 判定顺序不可调换（F3）
/// Kotlin 测试 `总开关关闭时不触发`（Test:110-115）证明 #1 先于关键词；
/// `模型未配置时不触发`（Test:117-122）证明 `ready` 先于关键词。
///
/// ## 高危差异（F 节，逐条落地）
/// 1. 随机是 `Int` 0–99，**不是 Float** —— 否则 p=100 会漏、p=1 语义变
/// 2. `?:` 短路：用户文本命中时**不解析** AI 文本（:184-185）
/// 3. 冷却是「先扣后画」：`saveLastGenAt(now)` 在生图前（:346-347），失败也计入
enum ImageGenTrigger {

    // MARK: - 配置与结果

    /// Kotlin `ImageGenGlobalConfig`（ImageGenTrigger.kt:57-71）
    struct GlobalConfig {
        var enabled = false                     // :59 默认 false
        var connectionMode = "auto"             // :60、:72
        var baseUrl = ""
        var apiKey = ""
        var model = ""
        var size = "1024x1024"                  // :64 → ImageGenerationProvider.kt:41
        var count = 1                           // :65
        var probability = 0                     // :66
        var promptTemplate = "{content}"        // :69
        var cooldownMinutes = 3                 // :68
    }

    /// Kotlin `ImageGenCompanionOverride`（ImageGenTrigger.kt:77-81）
    struct CompanionOverride {
        var enabled = false
        var probability = 0
        var keywords: [String] = []
    }

    /// Kotlin `ImageGenTriggerReason`（ImageGenTrigger.kt:91-109）
    enum Reason: String {
        case keyword = "KEYWORD"
        case probability = "PROBABILITY"
        case disabled = "DISABLED"
        case notConfigured = "NOT_CONFIGURED"
        case notMatched = "NOT_MATCHED"
        case cooldown = "COOLDOWN"
    }

    /// Kotlin `ImageGenDecision`（ImageGenTrigger.kt:111-117）
    struct Decision {
        var triggered: Bool
        var reason: Reason
        var matchedKeyword: String?
        var prompt: String?
    }

    /// Kotlin `resolveEffective` 的输出（ImageGenTrigger.kt:130-153）
    struct Effective {
        var ready: Bool
        var baseUrl: String
        var apiKey: String
        var probability: Int
        var keywords: [String]
        var cooldownMs: Int64
    }

    // MARK: - 常量

    /// `PROMPT_MAX_LENGTH = 800`（ImageGenTrigger.kt:123）
    static let promptMaxLength = 800

    /// 冷却换算：分钟 → 毫秒（ImageGenTrigger.kt:151）
    static func cooldownMs(minutes: Int) -> Int64 {
        Int64(max(minutes, 0)) * 60_000        // coerceAtLeast(0)
    }

    /// 默认关键词（AppSettingsStore.kt:180-187）
    ///
    /// 注释（:183）：「生成一张」必须单独收，否则漏掉「再生成一张」。
    static let defaultKeywords = [
        "画一张", "画个", "画一下", "给我画", "画出来", "画一幅", "画副",
        "生成一张", "生成图片", "来张图", "来一张", "生图",
        "帮你生成", "帮你画", "给你画", "给你生成", "帮你生成一张",
    ]

    /// Kotlin `AppSettingsStore.parseKeywords`（:198-202）：
    /// 分隔符 **只有** 换行 / 半角逗号 / 全角逗号（不含空格、分号、顿号），
    /// trim + 去空 + **保序**去重。
    static func parseKeywords(_ raw: String) -> [String] {
        var out: [String] = []
        for part in raw.components(separatedBy: CharacterSet(charactersIn: "\n,，")) {
            let t = part.trimmingCharacters(in: .whitespacesAndNewlines)
            if t.isEmpty { continue }
            if !out.contains(t) { out.append(t) }      // distinct 保首现顺序
        }
        return out
    }

    // MARK: - 判定

    /// Kotlin `decide`（ImageGenTrigger.kt:168-205）—— **纯函数**，可单测。
    ///
    /// `randomRoll` 由调用方注入（生产 `Int.random(in: 0..<100)`），测试可钉死。
    static func decide(
        global: GlobalConfig,
        effective: Effective,
        userText: String,
        aiText: String,
        lastGenAtMs: Int64,
        nowMs: Int64,
        randomRoll: Int
    ) -> Decision {

        // #1 总开关（:177-179）
        guard global.enabled else {
            return Decision(triggered: false, reason: .disabled,
                            matchedKeyword: nil, prompt: nil)
        }

        // #2 配置完整性（:180-182）—— 先于关键词（Test:117-122）
        guard effective.ready else {
            return Decision(triggered: false, reason: .notConfigured,
                            matchedKeyword: nil, prompt: nil)
        }

        // #3 关键词：先用户文本，再 AI 回复（:184-185 的 `?:` 短路）
        let keyword = matchKeyword(userText, effective.keywords)
            ?? matchKeyword(aiText, effective.keywords)

        // #4 概率（:187-188）
        let triggered = keyword != nil
            || (effective.probability > 0 && randomRoll < effective.probability)
        guard triggered else {
            return Decision(triggered: false, reason: .notMatched,
                            matchedKeyword: nil, prompt: nil)
        }

        // #5 冷却闸门（:194-196）—— 仅在已判定触发后求值
        let inCooldown = effective.cooldownMs > 0
            && lastGenAtMs > 0
            && (nowMs - lastGenAtMs) < effective.cooldownMs
        if inCooldown {
            return Decision(triggered: false, reason: .cooldown,
                            matchedKeyword: keyword, prompt: nil)
        }

        // #6 成功（:201-205）
        return Decision(
            triggered: true,
            reason: keyword != nil ? .keyword : .probability,
            matchedKeyword: keyword,
            prompt: nil
        )
    }

    /// Kotlin `matchKeyword`（ImageGenTrigger.kt:156-159）。
    ///
    /// - 空串/纯空白 → nil（:157）
    /// - 关键词列表里的空白项被跳过（:158）
    /// - 大小写不敏感的**子串包含**，无词边界、无正则
    /// - `firstOrNull` → **列表顺序**决定谁算命中
    static func matchKeyword(_ text: String, _ keywords: [String]) -> String? {
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return nil }
        let lower = text.lowercased()
        for kw in keywords {
            let t = kw.trimmingCharacters(in: .whitespacesAndNewlines)
            if t.isEmpty { continue }                  // it.isNotBlank()
            if lower.contains(t.lowercased()) { return kw }
        }
        return nil
    }

    // MARK: - 生效配置

    /// Kotlin `resolveEffective`（ImageGenTrigger.kt:130-153）。
    ///
    /// override 只覆盖 probability + keywords（**成对**，无单项覆盖）；
    /// baseUrl/apiKey 在 auto 模式下取主连接；cooldown 永远来自全局。
    static func resolveEffective(
        global: GlobalConfig,
        override: CompanionOverride,
        mainConnection: (baseUrl: String, apiKey: String)?
    ) -> Effective {
        let auto = global.connectionMode == "auto"        // CONNECTION_MODE_AUTO
        let baseUrl = auto ? (mainConnection?.baseUrl ?? "") : global.baseUrl
        let apiKey = auto ? (mainConnection?.apiKey ?? "") : global.apiKey

        // ready = enabled && baseUrl !blank && apiKey !blank && model !blank（:139-142）
        let ready = global.enabled
            && !baseUrl.trimmingCharacters(in: .whitespaces).isEmpty
            && !apiKey.trimmingCharacters(in: .whitespaces).isEmpty
            && !global.model.trimmingCharacters(in: .whitespaces).isEmpty

        let probability = (override.enabled ? override.probability : global.probability)
            .clamped(to: 0...100)                        // coerceIn(0,100)（:149）
        let keywords = override.enabled ? override.keywords : global.keywords

        return Effective(
            ready: ready,
            baseUrl: baseUrl,
            apiKey: apiKey,
            probability: probability,
            keywords: keywords,
            cooldownMs: cooldownMs(minutes: global.cooldownMinutes)
        )
    }

    // MARK: - prompt 拼装

    /// Kotlin `buildPrompt`（ImageGenTrigger.kt:231-250）。
    ///
    /// 优先级：**AI 标签描述 > 去掉关键词后的用户文本 > 清洗后的 AI 正文**。
    ///
    /// ⚠️ `userText.replace(matchedKeyword, " ")` 是**字面量、替换全部出现处、
    /// 大小写敏感**，用的关键词是**配置原文**（:240）——不是匹配时的实际文本。
    static func buildPrompt(
        aiText: String,
        userText: String,
        matchedKeyword: String?,
        template: String
    ) -> String {
        var content: String
        let tagged = ImageGenProtocol.extractPrompt(aiText)

        if tagged != nil {
            content = tagged!                                  // ① 标签优先
        } else if let kw = matchedKeyword {
            let stripped = userText.replacingOccurrences(of: kw, with: " ")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            content = stried.isEmpty
                ? ImageGenProtocol.sanitizeForDisplay(aiText)  // ② 回落 AI 正文
                : stripped
        } else {
            content = ImageGenProtocol.sanitizeForDisplay(aiText)
        }

        // PROMPT_MAX_LENGTH 对所有分支统一生效（:243）
        if content.count > promptMaxLength {
            content = String(content.prefix(promptMaxLength))
        }
        if content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return ""
        }

        // 模板（:246-252）
        let safeTemplate = template.trimmingCharacters(in: .whitespacesAndNewlines)
        if safeTemplate.isEmpty { return content }
        if safeTemplate.contains("{content}") {
            return safeTemplate.replacingOccurrences(of: "{content}", with: content)
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return (safeTemplate + content).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private extension Int {
    func clamped(to range: ClosedRange<Int>) -> Int {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
