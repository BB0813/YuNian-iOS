import Foundation
import os
import Combine   // ObservableObject / @Published

/// 应用级依赖装配 —— 对应 Android 侧 `YuNianApplication` 的 `ServiceRegistry` 绑定部分。
///
/// 启动顺序（与 Rust 的期望一致）：
///   1. 解析路径（Application Support）
///   2. 打开数据库（建立/校验 v45 基线）
///   3. 构造回调宿主（stores / tool host / sink / 签名器）
///   4. 用 `AgentGlobalConfig` 构造 `AgentRuntime`
///   5. 注入签名器，并下发 settings / credentials
@MainActor
final class AppEnvironment: ObservableObject {

    static let shared = AppEnvironment()

    private let log = Logger(subsystem: "com.yunian.ai", category: "app")

    @Published private(set) var database: YuNianDatabase?
    @Published private(set) var stores: AgentStores?
    @Published private(set) var companions: CompanionRepository?
    @Published private(set) var messages: MessageRepository?
    /// API 配置（M3 最小读实现）—— 用于 PARTNER 门控与自检面板。
    @Published private(set) var apiConfigs: ApiConfigRepository?
    /// 表情可用标签（供 `builtin_send_sticker` 精确匹配校验）。
    @Published private(set) var stickerTags: StickerTagProvider?
    /// 记忆读侧仓储（M3 记忆设置界面用）。
    @Published private(set) var memoryRepo: MemoryRepository?
    /// 标签数量摘要（自检面板用）。
    @Published private(set) var stickerTagsSummary: String = "—"
    /// 实际标签列表（自检面板展开显示；下发给 Rust 的就是这批）。
    @Published private(set) var stickerTagList: [String] = []
    @Published private(set) var runtime: AgentRuntime?
    @Published private(set) var streamSink = AgentStreamSinkImpl()
    @Published private(set) var toolHost = AgentToolHostImpl()
    @Published private(set) var signatureProvider = AgentSignatureProviderImpl()

    @Published private(set) var startupError: String?
    @Published private(set) var resolvedFTSVersion: String = "unknown"

    /// 当前下发给 Rust 的 settings / credentials，供自检面板显示（不含密钥明文）。
    @Published private(set) var settingsSummary: String = "—"
    @Published private(set) var credentialsSummary: String = "—"
    /// 从服务端拉到的模型列表（`ApiProbe.fetchModels`）。
    @Published private(set) var serverModels: [String] = []
    /// 拉取结果的一句话说明（含失败原因）。
    @Published private(set) var modelsMessage: String?
    /// 是否正在拉取。
    @Published private(set) var modelsLoading = false
    /// 安全基线播种结果（keywords / quiz_questions 各写入多少行）。
    @Published private(set) var securitySeedSummary: String = "—"
    /// 内容过滤是否已装载（对应 Android 的 `ContentFilter.initialize`）。
    @Published private(set) var contentFilterReady: Bool = false
    /// 上次数据库维护的结果摘要（对应 Android 的 `DataCleanupManager`）。
    @Published private(set) var maintenanceSummary: String = "—"

    private var didBoot = false
    private var settings = AgentSettings.withSystemTimezone()

    private init() {}

    /// 幂等启动；失败时把原因暴露给 UI（不 crash —— 首启失败要有可读提示）。
    func boot() {
        guard !didBoot else { return }
        didBoot = true

        do {
            let dbURL = try AppPaths.databaseURL()
            let database = try YuNianDatabase(databaseURL: dbURL)
            let stores = AgentStores(database: database)
            let companions = CompanionRepository(database: database)
            let messages = MessageRepository(database: database)
            let apiConfigs = ApiConfigRepository(database: database)
            let memoryRepo = MemoryRepository(database: database)

            AppPaths.excludeDiagnosticsFromBackup()

            // settings：必须带 timezone，否则 Rust 的时间感知退化为 UTC（文档 §4.2 的 R8）
            //
            // ⚠️ 键集合必须与 Android `buildSettingsJson` 一致：role / timezone /
            // working_memory_limit /（条件）image_gen_rules。
            // **不要**加 `session_id` —— Android 无此键，多发会让 Rust 往
            // system prompt 注入一行「会话ID：」，两端提示词就此分叉。（曾误加过，已删。）
            // ⚠️ 第 105 轮补两个漏发的键。上一版到这里就停了，
            // 而 Kotlin 的 buildSettingsJson 四键全发（role / timezone /
            // working_memory_limit / image_gen_rules）：
            //   · imageGenRules 缺失 → Rust 的 setting_str("image_gen_rules")
            //     返回 None，**生图协议的提示词文本整块消失**，模型不知道
            //     [[生图: 描述]] 这个语法，用户要图时它不会输出该标记。
            //   · workingMemoryLimit 缺失 → 退回 Rust 默认值（恰好也是 200，
            //     所以此前无症状，但那是巧合不是等价）。
            settings = AgentSettings.withSystemTimezone()
            settings.workingMemoryLimit = 200
            settings.imageGenRules = ImageGenProtocol.defaultPrompt
            //
            // owner_name 是**有意的跨端分歧**：Android 从不发它，iOS 因「用户昵称」
            // 功能而多注入一行「群主：{昵称}」。详见 AgentSettings.ownerName 注释。
            // 若要对齐 Android，删掉下一行即可（功能保留，仅不注入提示词）。
            settings.ownerName = KeychainStore.string(for: KeychainStore.Key.ownerName)

            // credentials：Rust 无法解密库里的 apiKey（Tink 密文），必须由宿主传明文
            let credentials = AgentCredentials.fromKeychain(isPartner: false)

            let config = AgentGlobalConfig(
                dbPath: dbURL.path,
                deviceId: DeviceIdentity.deviceId,
                settingsJson: settings.jsonString(),
                stickers: [],
                credentialsJson: credentials.jsonString(),
                // ⚠️ **不能传 nil** —— 这是本次修复的关键。
                //
                // `orchestrator` 为 nil 时，Rust `agent.rs` 里两处 `if let Some(orchestrator)`
                // 都不成立，后果是：
                //   1. 1091 行：用户上下文前缀不注入 → 丢 `[当前时间]` / `[对话轮数]` / `[近期记忆]`
                //   2. 1186 行：**system prompt 完全不组装** → `request.system_prompt` 保持空
                // 也就是回合在没有任何人设/环境上下文的情况下跑，模型只看到历史。
                //
                // Android 侧 `AgentFacade.runtime()` 传的是真实的
                // `promptOrchestrator(context)`，从来不是 nil。
                //
                // 构造链（三个构造函数都由 UniFFI 暴露，`ios/Generated/LianyuAgent.swift`）：
                //   MemorySelector(store:) ← AgentStores 实现 MemoryStore
                //   SkillSelector(store:)  ← AgentStores 实现 SkillStore
                //   PromptOrchestrator(memory:skill:) ← 上面两者
                // memory / skill 两个参数本身可为空（空选择器时对应层不产出），
                // 但**编排器本身必须存在**。
                orchestrator: PromptOrchestrator(
                    memory: MemorySelector(store: stores),
                    skill: SkillSelector(store: stores)
                )
            )
            let runtime = AgentRuntime(config: config)

            // PARTNER 通道签名（fail-closed：未注入时 Rust 会拒绝发送 PARTNER 请求）
            runtime.setSignatureProvider(provider: signatureProvider)

            self.database = database
            self.stores = stores
            self.companions = companions
            self.messages = messages
            self.apiConfigs = apiConfigs
            self.stickerTags = StickerTagProvider(database: database, stores: stores)
            self.memoryRepo = memoryRepo
            self.runtime = runtime

            // 装配工具目录（记忆工具 + load_skill）并注册执行器。
            // 必须在 runtime 就绪后、且 ChatSession 发送前完成。
            AgentToolCatalog.install(
                memory: MemorySelector(store: stores),
                skill: SkillSelector(store: stores),
                host: toolHost
            )

            // 首启播种：Rust 的 load_companion 会按 id 读 companions；
            // 没有伴侣行就无法进行带人设的回合（Rust 返回 Err）。
            seedDefaultCompanionIfNeeded(companions)

            // 保证有一条启用的 api_configs 行 —— Rust 的关键路径依赖它
            // （load_api_config 无行时报「无可用 API 配置」，第 40 轮发现的缺口）。
            // 已有可用配置时不动，避免覆盖用户在别处的设置。
            try? ensureActiveApiConfig()

            // 安全基线（keywords / quiz_questions）—— 对应 Android 的
            // SecurityDataSeeder.seedIfEmpty()：表非空则跳过，因此可重复调用。
            securitySeedSummary = (try? SecuritySeedLoader.seedIfEmpty(database)) ?? "播种失败"

            // 内置聊天工具协议技能（builtin_chat_tool_protocol）。
            //
            // ⚠️ 这不是可选项：Rust 的 L4 技能层要求 agent_skills 里存在这一行，
            // 否则「必须用 emit_bubble 输出气泡」的协议注不进 system prompt，
            // 模型工具调用积极度不足。Android 由 BuiltinChatSkillPlugin 播种；
            // 由 Tools/generate_builtin_skill_seed.py 从源码程序化提取。
            let skillSeeded = BuiltinChatToolSkill.seed(into: stores)
            log.info("内置聊天协议技能种子：\(skillSeeded ? "成功" : "失败", privacy: .public)")

            // 内容过滤：编译过滤正则（对应 Android ContentFilter.initialize）
            contentFilterReady = ContentFilter.shared.load()
            let filterStats = ContentFilter.shared.stats
            log.info("内容过滤：正则 \(filterStats.patterns) 条（编译失败 \(filterStats.failed)）")

            // 数据库维护：对应 Android DataCleanupManager.cleanupIfNeeded ——
            // 距上次不足 24 小时会自行跳过，因此可在每次启动时安全调用。
            // Android 侧由 WorkManager 每日触发；iOS 侧的 BGTaskScheduler 等价物属 M5。
            let maintenance = DatabaseMaintenance.runIfNeeded(database: database, messages: messages)
            maintenanceSummary = maintenance.summary
            self.resolvedFTSVersion = database.resolvedFTSVersion

            settingsSummary = settings.jsonString()
            refreshCredentialsSummary()
            AppPaths.excludeDiagnosticsFromBackup()

            log.info("启动完成：schema v\(YuNianSchema.version) / FTS \(database.resolvedFTSVersion, privacy: .public) / 硬件签名 \(SecureEnclaveSigner.isHardwareBacked)")
        } catch {
            let description = (error as? YuNianDatabase.BootstrapError)?.description
                ?? String(describing: error)
            startupError = description
            log.error("启动失败：\(description, privacy: .public)")
        }
    }

    // MARK: - 便捷访问

    /// 默认伴侣。
    ///
    /// 写成计算属性而不是在视图里直接 `try? repo?.fetchDefault()` ——
    /// 后者会得到 `Companion??`（可选链 + 返回可选 + try? 叠加），
    /// `if let` 只解开一层，编译期看不出、运行期表现为「拿不到伴侣」。
    var defaultCompanion: CompanionRepository.Companion? {
        guard let companions else { return nil }
        return try? companions.fetchDefault()
    }

    // MARK: - 播种

    /// 首启播种默认伴侣。
    ///
    /// 逻辑在 `DefaultCompanionSeeder`（对应 Android 的同名类），
    /// 人设内容来自 `YuNianSeed`（由 `Tools/generate_seeds.py` 从 `RolePresets.kt` 生成）。
    /// 注意 `deleted_by_user` 语义：用户删过默认伴侣后不再自动重建。
    private func seedDefaultCompanionIfNeeded(_ repo: CompanionRepository) {
        DefaultCompanionSeeder.seedIfNeeded(repo)
    }

    // MARK: - 运行时更新

    /// 设置 API Key 并立即下发给 Rust。
    /// 这是 M2 阶段的最小凭证入口；M3 会接上完整的 `api_configs` 管理界面。
    ///
    /// ⚠️ 除了写 Keychain，还必须写一条启用的 `api_configs` 行 ——
    /// Rust 的 `load_api_config` 需要它提供 provider / baseUrl / model，
    /// 否则每个回合都报「无可用 API 配置」。详见 `ApiConfigRepository.upsertActiveConfig`。
    func setAPIKey(_ key: String, provider: String = "OPENAI", model: String? = nil) throws {
        if key.isEmpty {
            KeychainStore.remove(KeychainStore.Key.apiKey)
        } else {
            try KeychainStore.set(key, for: KeychainStore.Key.apiKey)
        }
        try ensureActiveApiConfig(provider: provider, model: model)
        pushCredentials()
    }

    /// 保证有一条启用的 `api_configs` 行（Rust 的关键路径依赖它）。
    ///
    /// 已存在启用行时**保持原样**（用户可能在别处配置过）；
    /// 完全没有时才用预设默认值建一条。
    func ensureActiveApiConfig(provider: String = "OPENAI", model: String? = nil) throws {
        guard let repo = apiConfigs else { return }
        if let existing = try repo.activeConfig(), !existing.model.isEmpty {
            return   // 已有可用配置，不动
        }
        // 从预设备里取该 provider 的默认 baseUrl / model
        let preset = YuNianSeed.apiProviderPresets.first { $0.provider == provider }
            ?? YuNianSeed.apiProviderPresets.first { $0.provider == "OPENAI" }
        guard let preset else { return }
        _ = try repo.upsertActiveConfig(
            provider: preset.provider,
            model: (model?.isEmpty == false) ? model! : preset.model,
            baseUrl: preset.baseUrl,
            formatHint: preset.formatHint
        )
        log.info("已创建启用配置：\(preset.provider, privacy: .public) / \(preset.model, privacy: .public)")
    }

    /// 设置 PARTNER 会话（suflow.cloud）。
    /// `session` → `X-LianYu-Session` 头；`clientId` → `X-LianYu-Client-Id` 头 + 签名回调入参。
    func setPartnerSession(session: String, clientId: String) throws {
        if session.isEmpty {
            KeychainStore.remove(KeychainStore.Key.partnerToken)
        } else {
            try KeychainStore.set(session, for: KeychainStore.Key.partnerToken)
        }
        if clientId.isEmpty {
            KeychainStore.remove(KeychainStore.Key.partnerClientId)
        } else {
            try KeychainStore.set(clientId, for: KeychainStore.Key.partnerClientId)
        }
        pushCredentials()
    }

    func setOwnerName(_ name: String) {
        if name.isEmpty {
            KeychainStore.remove(KeychainStore.Key.ownerName)
        } else {
            try? KeychainStore.set(name, for: KeychainStore.Key.ownerName)
        }
        settings.ownerName = name.isEmpty ? nil : name
        runtime?.updateSettings(settingsJson: settings.jsonString())
        settingsSummary = settings.jsonString()
    }

    /// 从 Keychain 重新装配 credentials 并下发。
    private func pushCredentials() {
        guard let runtime else { return }
        // PARTNER 门控：只有当前启用配置是 PARTNER 时才下发 session / client_id。
        // 对应 Android `syncRuntimeConfig` 的 `if (isPartner) ... else null`。
        let isPartner = (try? apiConfigs?.isActiveProviderPARTNER()) ?? false
        let credentials = AgentCredentials.fromKeychain(isPartner: isPartner)
        runtime.updateCredentials(credentialsJson: credentials.jsonString())
        refreshCredentialsSummary(credentials: credentials)
    }

    /// 测试连接：发一个最小请求验证 key / baseUrl / 协议。
    func testConnection() async {
        modelsLoading = true
        modelsMessage = nil
        defer { modelsLoading = false }

        guard let config = try? apiConfigs?.activeConfig() else {
            modelsMessage = "尚未选择供应商（或未填密钥）"
            return
        }
        let extraHeaders = ApiProbeService.authHeaders(
            provider: config.provider,
            apiKey: KeychainStore.string(for: KeychainStore.Key.apiKey) ?? "",
            partnerSession: KeychainStore.string(for: KeychainStore.Key.partnerToken),
            partnerClientId: KeychainStore.string(for: KeychainStore.Key.partnerClientId)
        )

        switch ApiProbeService.testConnection(config: config, extraHeaders: extraHeaders) {
        case let .success(reply):
            let brief = reply.trimmingCharacters(in: .whitespacesAndNewlines)
            modelsMessage = "连接成功" + (brief.isEmpty ? "" : "：\(brief)")
        case let .failure(error):
            modelsMessage = "连接失败：\(error.description)"
        }
    }
    ///
    /// ## 为什么值得做成 UI 动作
    /// 没有它，iOS 用户填完 key 后**无法验证 key 是否可用**，也无法从服务端
    /// 拉模型列表，只能手填模型名。而填错的症状是"发消息没反应"——
    /// 第 40 轮把这类问题列为「上线即故障」。
    func loadServerModels() async {
        modelsLoading = true
        modelsMessage = nil
        defer { modelsLoading = false }

        guard let config = try? apiConfigs?.activeConfig() else {
            modelsMessage = "尚未选择供应商（或未填密钥）"
            serverModels = []
            return
        }

        // 认证头由 ApiProbeService 按 provider 规则构造（PARTNER/XIAOMI/Bearer）。
        // 与回合内的头是两条路径但同一份 Keychain 数据，结论一致。
        let extraHeaders = ApiProbeService.authHeaders(
            provider: config.provider,
            apiKey: KeychainStore.string(for: KeychainStore.Key.apiKey) ?? "",
            partnerSession: KeychainStore.string(for: KeychainStore.Key.partnerToken),
            partnerClientId: KeychainStore.string(for: KeychainStore.Key.partnerClientId)
        )

        let result = ApiProbeService.fetchModels(config: config, extraHeaders: extraHeaders)
        switch result {
        case let .success(models):
            serverModels = models
            modelsMessage = models.isEmpty ? "服务端返回空列表" : "拉到 \(models.count) 个模型"
        case let .failure(error):
            serverModels = []
            modelsMessage = error.description
        }
    }

    /// 每回合前重新同步运行时配置。
    ///
    /// ## 对应 Android 的 `AgentDialogueCoordinator.syncRuntimeConfig()`
    /// Android 在**构造每个回合请求之前**都会调用它（源码 284 行），
    /// 而不是只在启动时配一次。它刷新：
    ///   1. `stickers` —— 表情标签 Top-30（`availableTagsWithFallback`）
    ///   2. `session` / `client_id` —— PARTNER 会话（会随握手过期/刷新）
    ///   3. `apiKey` —— 从库里**现取现解密**的当前启用配置
    ///   4. `settings` —— 重新构造
    ///
    /// 只在启动时配一次的问题：任何**不经本类 setter** 的变更都不会生效 ——
    /// 例如 PARTNER 会话被 `RemoteKeyProvider` 刷新、或 API 配置在其它入口被修改。
    ///
    /// ## iOS 侧的已知简化（与 Android 的差异，M3 补齐）
    /// - `stickers` 仍为空：iOS 未实现 `StickerPreferenceFacade.availableTagsWithFallback`
    ///   （依赖表情偏好引擎与 `sticker_tags` 表的 Top-N 查询）。
    /// - `apiKey` 来自 Keychain 而非现取现解密 api_configs 行：M2 凭证入口只写
    ///   Keychain，因此这里等价于「读 Keychain 现值」。
    ///   **PARTNER 门控已按 Android 语义实现**（读取当前启用配置的 provider）。
    func syncRuntimeConfig() {
        guard let runtime else { return }
        settings = AgentSettings.withSystemTimezone()
        // owner_name 是有意的跨端分歧，见 AgentSettings.ownerName 注释
        settings.ownerName = KeychainStore.string(for: KeychainStore.Key.ownerName)
        runtime.updateSettings(settingsJson: settings.jsonString())
        settingsSummary = settings.jsonString()

        // 表情可用标签：经 stickers 下发给 Rust 的 `builtin_send_sticker`
        // 做精确匹配校验。⚠️ 这是**活需求** —— 内置工具不受 request.tools 门控，
        // iOS 上模型确实能调用 send_sticker（见 ChatSession.swift 的说明）。
        if let stickerTags {
            let tags = stickerTags.tagsWithFallback(topN: 30)
            runtime.updateStickers(stickers: tags)
            stickerTagsSummary = "\(tags.count) 个标签"
            stickerTagList = tags
        }

        pushCredentials()
    }

    /// 只显示「是否已配置」，不泄露明文。
    private func refreshCredentialsSummary(credentials: AgentCredentials = .init()) {
        credentialsSummary = "api_key: \(credentials.apiKey?.isEmpty == false ? "已配置" : "未配置")"
            + " / session: \(credentials.session?.isEmpty == false ? "已配置" : "未配置")"
    }
}
