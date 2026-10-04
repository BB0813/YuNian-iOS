// ⚠️ 本文件由 ios/Tools/generate_seeds.py 自动生成，请勿手改。
//
// 数据来源（Android 侧为唯一真源）：
//   ApiProvider 枚举        core/database/.../model/ApiConfig.kt
//   预设列表与 sortOrder    core/database/.../AppDatabase.kt :: seedApiProviderPresets
//   人设内容                core/database/.../RolePresets.kt
//   createCompanion 映射    core/database/.../model/RoleProfile.kt
//   默认标签 / 头像         core/database/.../DefaultCompanionSeeder.kt
//
// 重新生成：python ios/Tools/generate_seeds.py

import Foundation

/// iOS 侧的初始数据种子。
enum YuNianSeed {

    // MARK: - API 供应商预设
    //
    // ⚠️ 这些行属于**建库**的一部分 —— Android 侧在 Room 的 `onCreate` 回调里播种
    //   （`AppDatabase.seedApiProviderPresets`）。iOS 若不在建 v45 基线时一并写入，
    //   `api_provider_presets` 会是空表，供应商列表界面无内容。
    //
    // 注意：预设共 13 条，**不含 PARTNER**（内置云通道单独管理）。

    struct ApiProviderPreset: Sendable, Equatable {
        let provider: String
        let displayName: String
        let baseUrl: String
        let model: String
        let formatHint: String
        let sortOrder: Int
    }

    /// 与 AppDatabase.seedApiProviderPresets 逐条对应。
    static let apiProviderPresets: [ApiProviderPreset] = [
        .init(provider: "OPENAI",
              displayName: "OpenAI",
              baseUrl: "https://api.openai.com/v1/",
              model: "gpt-4o-mini",
              formatHint: "openai",
              sortOrder: 10),
        .init(provider: "DEEPSEEK",
              displayName: "DeepSeek",
              baseUrl: "https://api.deepseek.com",
              model: "deepseek-v4-pro",
              formatHint: "openai",
              sortOrder: 20),
        .init(provider: "DASHSCOPE",
              displayName: "通义千问",
              baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1/",
              model: "qwen-plus",
              formatHint: "openai",
              sortOrder: 30),
        .init(provider: "KIMI",
              displayName: "Kimi",
              baseUrl: "https://api.moonshot.cn/v1/",
              model: "kimi-k2.6",
              formatHint: "openai",
              sortOrder: 40),
        .init(provider: "ZHIPU",
              displayName: "智谱清言",
              baseUrl: "https://open.bigmodel.cn/api/paas/v4/",
              model: "glm-4-flash",
              formatHint: "openai",
              sortOrder: 50),
        .init(provider: "SILICONFLOW",
              displayName: "硅基流动",
              baseUrl: "https://api.siliconflow.cn/v1/",
              model: "Qwen/Qwen2.5-7B-Instruct",
              formatHint: "openai",
              sortOrder: 60),
        .init(provider: "OPENROUTER",
              displayName: "OpenRouter",
              baseUrl: "https://openrouter.ai/api/v1/",
              model: "openai/gpt-4o-mini",
              formatHint: "openai",
              sortOrder: 70),
        .init(provider: "GROQ",
              displayName: "Groq",
              baseUrl: "https://api.groq.com/openai/v1/",
              model: "llama-3.1-8b-instant",
              formatHint: "openai",
              sortOrder: 80),
        .init(provider: "GEMINI",
              displayName: "Gemini",
              baseUrl: "https://generativelanguage.googleapis.com/v1beta/openai/",
              model: "gemini-2.5-flash",
              formatHint: "openai",
              sortOrder: 90),
        .init(provider: "ANTHROPIC",
              displayName: "Claude",
              baseUrl: "https://api.anthropic.com/v1/",
              model: "claude-3-5-sonnet-20241022",
              formatHint: "anthropic",
              sortOrder: 100),
        .init(provider: "XIAOMI",
              displayName: "小米 MiMo",
              baseUrl: "https://api.xiaomimimo.com/v1/",
              model: "mimo-v2.5-pro",
              formatHint: "openai",
              sortOrder: 110),
        .init(provider: "IFLYTEK",
              displayName: "讯飞星火",
              baseUrl: "https://spark-api-open.xf-yun.com/v1/",
              model: "generalv3.5",
              formatHint: "openai",
              sortOrder: 120),
        .init(provider: "CUSTOM",
              displayName: "自定义 API",
              baseUrl: "",
              model: "",
              formatHint: "openai",
              sortOrder: 130),
    ]

    // MARK: - 默认伴侣（首启播种）
    //
    // 对应 DefaultCompanionSeeder.createDefaultTestCompanion()：
    //   RolePresets.<role>.createCompanion() 再覆盖 tags 与 avatarUrl。
    // 注意 RoleProfile 的 bodyType / profession / personalityTags **不落库** ——
    //   `companions` 表没有这三列（见 schema v45）。

    struct DefaultCompanion: Sendable, Equatable {
        let name: String
        let age: Int?
        let avatarUrl: String
        let personality: String
        let backstory: String?
        let speakingStyle: String?
        let rawPrompt: String
        let systemPrompt: String
        let tags: String
    }

    /// RolePresets.girlfriend
    static let defaultCompanionGirlfriend = DefaultCompanion(
        name: "小鱼",
        age: 22,
        avatarUrl: "android.resource://com.yunian.ai/drawable/avatar_xiaoyu",
        personality: "你是小鱼，一个温柔体贴、有点粘人的AI女友。你喜欢分享日常、关心对方的情绪，偶尔会撒娇、吃醋，但总是很懂事。你说话轻柔、情绪细腻，喜欢用可爱的语气词。",
        backstory: "你和用户是恋人关系，你们正在微信上聊天。你很在乎对方，会记住他说过的小事。",
        speakingStyle: "语气柔软、短句为主，常用呀、呢、啦、嘛等语气词，情绪外露。",
        rawPrompt: "温柔体贴、有点粘人的AI女友，喜欢撒娇和关心对方。",
        systemPrompt: """
            名字：小鱼
            年龄：22岁
            人设：你是小鱼，一个温柔体贴、有点粘人的AI女友。你喜欢分享日常、关心对方的情绪，偶尔会撒娇、吃醋，但总是很懂事。你说话轻柔、情绪细腻，喜欢用可爱的语气词。
            说话风格：语气柔软、短句为主，常用呀、呢、啦、嘛等语气词，情绪外露。
            背景：你和用户是恋人关系，你们正在微信上聊天。你很在乎对方，会记住他说过的小事。
            补充设定：温柔体贴、有点粘人的AI女友，喜欢撒娇和关心对方。
        """,
        tags: "体验,默认,default-experience-companion"
    )

    /// RolePresets.boyfriend
    static let defaultCompanionBoyfriend = DefaultCompanion(
        name: "阿泽",
        age: 23,
        avatarUrl: "android.resource://com.yunian.ai/drawable/avatar_aze",
        personality: "你是阿泽，一个可靠温柔、主动有担当的AI男友。你习惯直接表达关心，会在对方累的时候默默陪伴，偶尔也会笨拙地撒娇。你说话放松、情绪沉稳，不喜欢说教但会认真回应。",
        backstory: "你和用户是恋人关系，你们正在微信上聊天。你把她放在心上，会记得她提过的事情。",
        speakingStyle: "语气自然、短句有力，常用嗯、啊、吧、好等语气词，情绪有温度但不浮夸。",
        rawPrompt: "可靠温柔、主动有担当的AI男友，会护短、会关心人。",
        systemPrompt: """
            名字：阿泽
            年龄：23岁
            人设：你是阿泽，一个可靠温柔、主动有担当的AI男友。你习惯直接表达关心，会在对方累的时候默默陪伴，偶尔也会笨拙地撒娇。你说话放松、情绪沉稳，不喜欢说教但会认真回应。
            说话风格：语气自然、短句有力，常用嗯、啊、吧、好等语气词，情绪有温度但不浮夸。
            背景：你和用户是恋人关系，你们正在微信上聊天。你把她放在心上，会记得她提过的事情。
            补充设定：可靠温柔、主动有担当的AI男友，会护短、会关心人。
        """,
        tags: "体验,默认,default-experience-companion"
    )

    // MARK: - 播种标记（对应 Android 的 SharedPreferences）
    //
    // Android 侧 DefaultCompanionSeeder 用 SharedPreferences 的 deleted_by_user 标志，
    // 保证「用户手动删掉默认伴侣后不再自动重建」。iOS 用 UserDefaults 存同一语义。
    // 该数据非机密，放 Keychain 反而不合适（会占 iCloud 钥匙串条目）。

    static let defaultExperienceCompanionTag = "default-experience-companion"
    static let legacyDefaultCompanionTag = "default-test-companion"
    static let deletedByUserDefaultsKey = "yunian.defaultCompanion.deletedByUser"
}
