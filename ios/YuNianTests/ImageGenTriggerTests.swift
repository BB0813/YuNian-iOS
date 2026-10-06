import XCTest

/// 生图触发判定的规格测试。
///
/// 权威来源：`feature/chat/.../ImageGenTriggerTest.kt`（40 条 @Test）。
/// 本文件复刻其中**纯判定**部分 —— 网络/落库的部分（Test:33-40）需要
/// 集成环境，未纳入。
@testable import YuNian
final class ImageGenTriggerTests: XCTestCase {

    private func config(_ mutate: (inout ImageGenTrigger.GlobalConfig) -> Void = { _ in })
        -> ImageGenTrigger.GlobalConfig
    {
        var c = ImageGenTrigger.GlobalConfig()
        c.baseUrl = "https://api.example.com"
        c.apiKey = "sk-test"
        c.model = "flux.1"
        mutate(&c)
        return c
    }

    private func effective(_ mutate: (inout ImageGenTrigger.Effective) -> Void)
        -> ImageGenTrigger.Effective
    {
        var e = ImageGenTrigger.Effective(
            ready: true, baseUrl: "https://api.example.com", apiKey: "sk-test",
            probability: 30, keywords: ImageGenTrigger.defaultKeywords,
            cooldownMs: 180_000)
        mutate(&e)
        return e
    }

    // MARK: - #6 总开关先于一切

    /// Kotlin Test:110-115 —— enabled=false + 用户文本含关键词 → DISABLED
    func testDisabledShortCircuitsBeforeKeyword() {
        let g = config { $0.enabled = false }
        let e = effective { $0.ready = true }
        let d = ImageGenTrigger.decide(
            global: g, effective: e,
            userText: "画一张猫", aiText: "",
            lastGenAtMs: 0, nowMs: 1_000_000, randomRoll: 0)
        XCTAssertFalse(d.triggered)
        XCTAssertEqual(d.reason, .disabled)
    }

    // MARK: - #2 ready 先于关键词

    /// Kotlin Test:117-122 —— model 空 → NOT_CONFIGURED（关键词本可命中）
    func testNotConfiguredPrecedesKeyword() {
        let g = config { $0.enabled = true; $0.model = "" }
        let e = effective { $0.ready = false }
        let d = ImageGenTrigger.decide(
            global: g, effective: e,
            userText: "画一张猫", aiText: "",
            lastGenAtMs: 0, nowMs: 1_000_000, randomRoll: 0)
        XCTAssertFalse(d.triggered)
        XCTAssertEqual(d.reason, .notConfigured)
    }

    // MARK: - #3 关键词

    /// Kotlin Test:124-133 —— probability=0 + 关键词 → KEYWORD
    /// （关键词无视概率；matchedKeyword 是**配置原文**）
    func testKeywordIgnoresProbability() {
        let g = config { $0.enabled = true }
        let e = effective { $0.probability = 0 }
        let d = ImageGenTrigger.decide(
            global: g, effective: e,
            userText: "帮我画一张穿裙子的猫", aiText: "",
            lastGenAtMs: 0, nowMs: 1_000_000, randomRoll: 0)
        XCTAssertTrue(d.triggered)
        XCTAssertEqual(d.reason, .keyword)
        XCTAssertEqual(d.matchedKeyword, "画一张")
    }

    /// Kotlin Test:135-143 —— 关键词命中 AI 回复时同样触发
    func testKeywordMatchesAiText() {
        let g = config { $0.enabled = true }
        let e = effective { $0.keywords = ["给你看看"]; $0.probability = 0 }
        let d = ImageGenTrigger.decide(
            global: g, effective: e,
            userText: "在吗", aiText: "这就给你看看~",
            lastGenAtMs: 0, nowMs: 1_000_000, randomRoll: 0)
        XCTAssertTrue(d.triggered)
        XCTAssertEqual(d.matchedKeyword, "给你看看")
    }

    /// Kotlin Test:339-347 —— 「再生成一张」命中列表里第一个 "生成一张"
    /// （firstOrNull：列表顺序决定归属）
    func testDefaultKeywordsOrderMatters() {
        XCTAssertEqual(ImageGenTrigger.matchKeyword("再生成一张",
                                                    ImageGenTrigger.defaultKeywords),
                       "生成一张")
        XCTAssertNotNil(ImageGenTrigger.matchKeyword("好，帮你生成",
                                                    ImageGenTrigger.defaultKeywords))
    }

    /// Kotlin Test:349-355 —— 无关闲聊不得误触发
    func testDefaultKeywordsAvoidFalsePositive() {
        XCTAssertNil(ImageGenTrigger.matchKeyword("今天上班好累啊",
                                                  ImageGenTrigger.defaultKeywords))
        XCTAssertNil(ImageGenTrigger.matchKeyword("生成的内容我看过了",
                                                  ImageGenTrigger.defaultKeywords))
    }

    /// 空/纯空白文本 → nil（ImageGenTrigger.kt:157）
    func testMatchKeywordBlankTextReturnsNil() {
        XCTAssertNil(ImageGenTrigger.matchKeyword("", ["画一张"]))
        XCTAssertNil(ImageGenTrigger.matchKeyword("   ", ["画一张"]))
    }

    /// 关键词列表里的空白项被跳过（:158）
    func testMatchKeywordSkipsBlankEntries() {
        XCTAssertNil(ImageGenTrigger.matchKeyword("今天天气不错", ["", "  ", "画一张"]))
    }

    /// 大小写不敏感（ignoreCase = true）
    func testMatchKeywordCaseInsensitive() {
        XCTAssertEqual(ImageGenTrigger.matchKeyword("draw a cat", ["DRAW"]), "DRAW")
    }

    // MARK: - #4 概率

    /// Kotlin Test:145-150 —— p=0 + roll=0、无关键词 → NOT_MATCHED
    func testProbabilityZeroNeverTriggers() {
        let g = config { $0.enabled = true }
        let e = effective { $0.probability = 0; $0.keywords = [] }
        let d = ImageGenTrigger.decide(
            global: g, effective: e,
            userText: "hi", aiText: "yo",
            lastGenAtMs: 0, nowMs: 1_000_000, randomRoll: 0)
        XCTAssertFalse(d.triggered)
        XCTAssertEqual(d.reason, .notMatched)
    }

    /// Kotlin Test:152-157 —— p=100 + roll=99 → PROBABILITY（证明 roll 上界 99）
    func testProbability100HitsAtRoll99() {
        let g = config { $0.enabled = true }
        let e = effective { $0.probability = 100; $0.keywords = [] }
        let d = ImageGenTrigger.decide(
            global: g, effective: e,
            userText: "hi", aiText: "yo",
            lastGenAtMs: 0, nowMs: 1_000_000, randomRoll: 99)
        XCTAssertTrue(d.triggered)
        XCTAssertEqual(d.reason, .probability)
    }

    /// Kotlin Test:159-164 —— 严格 `<`：roll 29 中、roll 30 不中（p=30）
    func testProbabilityBoundaryIsStrictLessThan() {
        let g = config { $0.enabled = true }
        let e = effective { $0.probability = 30; $0.keywords = [] }
        let hit = ImageGenTrigger.decide(
            global: g, effective: e, userText: "a", aiText: "b",
            lastGenAtMs: 0, nowMs: 1_000_000, randomRoll: 29)
        let miss = ImageGenTrigger.decide(
            global: g, effective: e, userText: "a", aiText: "b",
            lastGenAtMs: 0, nowMs: 1_000_000, randomRoll: 30)
        XCTAssertTrue(hit.triggered)
        XCTAssertFalse(miss.triggered)
        XCTAssertEqual(miss.reason, .notMatched)
    }

    // MARK: - #5 冷却

    /// Kotlin Test:166-178 —— 间隔 60s < 冷却 3min → COOLDOWN（含 matchedKeyword）
    func testCooldownBlocksWithinWindow() {
        let g = config { $0.enabled = true }
        let e = effective { $0.cooldownMs = 180_000 }
        let now: Int64 = 1_000_000_000
        let d = ImageGenTrigger.decide(
            global: g, effective: e,
            userText: "画一张猫", aiText: "",
            lastGenAtMs: now - 60_000, nowMs: now, randomRoll: 0)
        XCTAssertFalse(d.triggered)
        XCTAssertEqual(d.reason, .cooldown)
        XCTAssertEqual(d.matchedKeyword, "画一张", "COOLDOWN 也带 matchedKeyword")
    }

    /// Kotlin Test:180-191 —— 间隔 4min > 冷却 3min → 恢复触发
    func testCooldownExpiredResumes() {
        let g = config { $0.enabled = true }
        let e = effective { $0.cooldownMs = 180_000 }
        let now: Int64 = 1_000_000_000
        let d = ImageGenTrigger.decide(
            global: g, effective: e,
            userText: "画一张猫", aiText: "",
            lastGenAtMs: now - 240_000, nowMs: now, randomRoll: 0)
        XCTAssertTrue(d.triggered)
    }

    /// Kotlin Test:193-203 —— cooldownMinutes=0 → 永不冷却
    func testZeroCooldownNeverBlocks() {
        let g = config { $0.enabled = true }
        let e = effective { $0.cooldownMs = 0 }
        let now: Int64 = 1_000_000_000
        let d = ImageGenTrigger.decide(
            global: g, effective: e,
            userText: "画一张猫", aiText: "",
            lastGenAtMs: now - 1, nowMs: now, randomRoll: 0)
        XCTAssertTrue(d.triggered)
    }

    /// lastGenAtMs=0（从未生过图/读取失败）→ 永不冷却（:196）
    func testZeroLastGenAtNeverBlocks() {
        let g = config { $0.enabled = true }
        let e = effective { $0.cooldownMs = 180_000 }
        let d = ImageGenTrigger.decide(
            global: g, effective: e,
            userText: "画一张猫", aiText: "",
            lastGenAtMs: 0, nowMs: 1_000_000, randomRoll: 0)
        XCTAssertTrue(d.triggered)
    }

    /// 间隔**正好等于**冷却时长 → `<` false → 照常触发（:196）
    func testCooldownBoundaryEqualIsNotBlocked() {
        let g = config { $0.enabled = true }
        let e = effective { $0.cooldownMs = 180_000 }
        let now: Int64 = 1_000_000_000
        let d = ImageGenTrigger.decide(
            global: g, effective: e,
            userText: "画一张猫", aiText: "",
            lastGenAtMs: now - 180_000, nowMs: now, randomRoll: 0)
        XCTAssertTrue(d.triggered)
    }

    /// 冷却换算：分钟 → 毫秒；负数夹到 0（:151）
    func testCooldownMsConversion() {
        XCTAssertEqual(ImageGenTrigger.cooldownMs(minutes: 3), 180_000)
        XCTAssertEqual(ImageGenTrigger.cooldownMs(minutes: 0), 0)
        XCTAssertEqual(ImageGenTrigger.cooldownMs(minutes: -5), 0)
    }

    // MARK: - resolveEffective

    /// Kotlin Test:36-44 —— auto 模式取主 API 连接
    func testEffectiveAutoUsesMainConnection() {
        var g = config()
        g.connectionMode = "auto"
        g.baseUrl = ""
        g.apiKey = ""
        let e = ImageGenTrigger.resolveEffective(
            global: g, override: .init(),
            mainConnection: ("https://main.example.com", "sk-main"))
        XCTAssertTrue(e.ready)
        XCTAssertEqual(e.baseUrl, "https://main.example.com")
        XCTAssertEqual(e.apiKey, "sk-main")
    }

    /// Kotlin Test:46-54 —— auto 且 mainConnection=nil → 未就绪
    func testEffectiveAutoWithoutMainNotReady() {
        var g = config()
        g.connectionMode = "auto"
        let e = ImageGenTrigger.resolveEffective(
            global: g, override: .init(), mainConnection: nil)
        XCTAssertFalse(e.ready)
    }

    /// Kotlin Test:56-64 —— CUSTOM 但 apiKey 空 → 未就绪
    func testEffectiveCustomMissingApiKeyNotReady() {
        var g = config()
        g.connectionMode = "CUSTOM"
        g.apiKey = ""
        let e = ImageGenTrigger.resolveEffective(
            global: g, override: .init(),
            mainConnection: ("https://main.example.com", "sk-main"))
        XCTAssertFalse(e.ready, "独立模式不因主连接存在而补救")
    }

    /// Kotlin Test:66-79 —— override 开启 → 用 override 的 probability + keywords
    func testOverrideEnabledReplacesBoth() {
        let g = config { $0.enabled = true; $0.probability = 30 }
        let e = ImageGenTrigger.resolveEffective(
            global: g,
            override: .init(enabled: true, probability: 55, keywords: ["出图"]),
            mainConnection: nil)
        XCTAssertEqual(e.probability, 55)
        XCTAssertEqual(e.keywords, ["出图"])
    }

    /// Kotlin Test:81-90 —— override 关闭但填了值 → 仍用全局
    func testOverrideDisabledFallsBackToGlobal() {
        let g = config { $0.enabled = true; $0.probability = 30 }
        let e = ImageGenTrigger.resolveEffective(
            global: g,
            override: .init(enabled: false, probability: 99, keywords: ["出图"]),
            mainConnection: nil)
        XCTAssertEqual(e.probability, 30)
        XCTAssertEqual(e.keywords, ImageGenTrigger.defaultKeywords)
    }

    /// override 无法覆盖冷却（:151）
    func testOverrideCannotChangeCooldown() {
        let g = config { $0.enabled = true; $0.cooldownMinutes = 3 }
        let e = ImageGenTrigger.resolveEffective(
            global: g,
            override: .init(enabled: true, probability: 50, keywords: []),
            mainConnection: nil)
        XCTAssertEqual(e.cooldownMs, 180_000)
    }

    /// 概率夹取 0–100（:149）
    func testProbabilityClampedToRange() {
        let g = config { $0.enabled = true }
        let e = ImageGenTrigger.resolveEffective(
            global: g,
            override: .init(enabled: true, probability: 500, keywords: []),
            mainConnection: nil)
        XCTAssertEqual(e.probability, 100)
    }

    /// 纯空格 baseUrl 也算未配置（isNotBlank，:141）
    func testBlankBaseUrlCountsAsNotConfigured() {
        let g = config { $0.enabled = true; $0.baseUrl = "   " }
        let e = ImageGenTrigger.resolveEffective(
            global: g, override: .init(), mainConnection: nil)
        XCTAssertFalse(e.ready)
    }

    // MARK: - parseKeywords

    /// Kotlin AppSettingsStore:198-202 —— 三种分隔符、trim、去空、保序去重
    func testParseKeywordsSeparatorsAndDistinct() {
        let got = ImageGenTrigger.parseKeywords("画一张\n画个,画一下，画一张")
        XCTAssertEqual(got, ["画一张", "画个", "画一下"])
    }

    /// 分隔符**不含**空格、分号、顿号
    func testParseKeywordsExcludesOtherSeparators() {
        XCTAssertEqual(ImageGenTrigger.parseKeywords("画一张 画个;画一下、画图"),
                       ["画一张 画个;画一下、画图"])
    }

    // MARK: - buildPrompt

    /// Kotlin Test:267-276 —— AI 标签优先于关键词与正文
    func testBuildPromptTaggedWins() {
        let p = ImageGenTrigger.buildPrompt(
            aiText: "马上画\n[[生图: 赛博朋克城市夜景]]",
            userText: "画一张穿着汉服的猫",
            matchedKeyword: "画一张",
            template: "{content}")
        XCTAssertEqual(p, "赛博朋克城市夜景")
    }

    /// Kotlin Test:278-287 —— 用去掉关键词后的用户文本
    func testBuildPromptStripsKeywordFromUserText() {
        let p = ImageGenTrigger.buildPrompt(
            aiText: "好的",
            userText: "画一张穿着汉服的猫",
            matchedKeyword: "画一张",
            template: "{content}")
        XCTAssertEqual(p, "穿着汉服的猫")
    }

    /// Kotlin Test:289-298 —— 无标签无关键词 → 回落到 AI 正文
    func testBuildPromptFallsBackToAiBody() {
        let p = ImageGenTrigger.buildPrompt(
            aiText: "好啊，我马上给你画",
            userText: "在吗",
            matchedKeyword: nil,
            template: "{content}")
        XCTAssertEqual(p, "好啊，我马上给你画")
    }

    /// Kotlin Test:300-309 —— 占位符替换
    func testBuildPromptTemplatePlaceholder() {
        let p = ImageGenTrigger.buildPrompt(
            aiText: "猫",
            userText: "",
            matchedKeyword: nil,
            template: "画一张：{content}，写实风格")
        XCTAssertEqual(p, "画一张：猫，写实风格")
    }

    /// Kotlin Test:311-320 —— 无占位符时模板作为前缀
    func testBuildPromptTemplateAsPrefix() {
        let p = ImageGenTrigger.buildPrompt(
            aiText: "猫",
            userText: "",
            matchedKeyword: nil,
            template: "写实风格：")
        XCTAssertEqual(p, "写实风格：猫")
    }

    /// Kotlin Test:322-331 —— 超长截断到 800
    func testBuildPromptTruncatesTo800() {
        let long = String(repeating: "猫", count: 2000)
        let p = ImageGenTrigger.buildPrompt(
            aiText: long, userText: "", matchedKeyword: nil, template: "{content}")
        XCTAssertEqual(p.count, 800)
    }

    /// 最终 prompt 为空串 → 返回空（Kotlin :331-333 的「触发后又反悔」）
    func testBuildPromptEmptyReturnsEmpty() {
        let p = ImageGenTrigger.buildPrompt(
            aiText: "", userText: "", matchedKeyword: nil, template: "{content}")
        XCTAssertEqual(p, "")
    }
}
