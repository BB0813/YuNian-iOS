import SwiftUI

/// 设置行：图标 + 标题 + **一句人话解释** + 右侧状态徽标。
///
/// ## 为什么要有副标题和徽标
/// 竞品（Aru）的设置页是同一结构：每一项都有一句
/// "这是什么"的说明，右侧徽标告诉你"现在什么状态"。
///
/// 只有标题的列表，用户必须**一项项点进去才知道是什么** ——
/// 而设置页恰恰是他最不想乱点的地方（怕改坏）。
struct YNSettingsRow: View {

    let icon: String
    let title: String
    let subtitle: String
    var badge: String?
    var badgeTint: Color?

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let c = YNTheme.palette(scheme)
        HStack(spacing: YNTheme.Space.md) {
            Image(systemName: icon)
                .font(.body)
                .foregroundStyle(c.accent)
                .frame(width: 34, height: 34)
                .background(c.accent.opacity(0.14),
                            in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .foregroundStyle(c.textPrimary)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(c.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: YNTheme.Space.sm)

            if let badge {
                Text(badge)
                    .font(.caption)
                    .foregroundStyle(badgeTint ?? c.textSecondary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background((badgeTint ?? c.textSecondary).opacity(0.12),
                                in: Capsule())
            }
        }
        .padding(.vertical, 2)
        // 合并成一个可点元素：否则 VoiceOver 会读成三个互不相关的片段
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title)。\(subtitle)\(badge.map { "，\($0)" } ?? "")")
    }
}

/// 我 —— 设置与个人信息。
///
/// ## 为什么它排在聊天页之后、却是第一个被填实的页面
/// 它本身观感不复杂，但它是**其余能力的宿主**：
/// 没有它，用户连 API Key 都配不了 —— 聊天页永远停在
/// 「没有可用的 API Key」，流式 / typing / 思考过程这些
/// **最该被验收的状态一个都看不到**。
///
/// ## 形态选择：系统 `List` + `Section`
/// 不自己搭卡片。iOS 设置类页面的**原生答案**就是 inset grouped List，
/// 它自带分组、分隔线、可点区域、动态字体与 VoiceOver 支持。
struct MeView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var ownerName = ""
    @State private var didLoad = false

    var body: some View {
        NavigationStack {
            List {
                profileCard
                aiSection
                systemSection
                dataSection
                diagnosticSection
                aboutSection
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(YNCanvas())
            // ⚠️ 第 222 轮第二次修正：页头交回**系统大标题**。
            // 自绘页头（`YNPageHeader`）在真机上出现"标题缺字 / 内容被裁"，
            // 所以三个页签统一用系统 `.largeTitle`（34pt 粗体，量级一致、几何由系统保证）。
            // 原来挂在页头上的版本号与渠道状态，移进下面的个人卡（那里本来就是身份位）。
            .navigationTitle("我")
            .navigationBarTitleDisplayMode(.large)
            .task {
                loadOnce()
                // 每次这一页出现都问一次张数：导入完返回、或别处改了表情库，
                // 徽标立刻跟上（不能只靠启动时那一次）。
                environment.refreshStickerCount()
            }
        }
        .tint(YNTheme.palette(scheme).accent)
    }

    /// 个人信息卡：大头像（首字）+ 内联改称呼 + **渠道状态 / 版本号**。
    ///
    /// 第 222 轮：原来这三样分别挂在"自绘页头"和一行带图标的输入框上；
    /// 页头改回系统大标题后，把它们都收进这张卡 —— 它本来就是这一页的身份位。
    private var profileCard: some View {
        let c = YNTheme.palette(scheme)
        return Section {
            HStack(spacing: YNTheme.Space.md) {
                YNAvatarHalo(avatarURL: nil,
                             name: ownerName.isEmpty ? "我" : ownerName,
                             size: 64)

                VStack(alignment: .leading, spacing: 4) {
                    // 内联编辑而不是跳页：只有一个字段，开一屏是多余的。
                    TextField("你的称呼", text: $ownerName)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(c.textPrimary)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onSubmit { saveOwnerName() }
                        .accessibilityLabel("你的称呼")

                    Text(ownerName.isEmpty ? "角色会用它称呼你（留空则不下发）" : "点一下可以改")
                        .font(.caption)
                        .foregroundStyle(c.textTertiary)

                    // 状态与版本（原来在自绘页头上）
                    Text(channelReady ? "渠道已就绪 · \(Self.versionText)"
                                      : "还没有可用渠道 —— 先在「模型渠道」里配一条")
                        .font(.caption2)
                        .foregroundStyle(channelReady ? c.textTertiary : c.warning)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.vertical, YNTheme.Space.sm)
            .listRowBackground(Color.clear)
        }
    }

    /// 版本号：`1.0.0 (1)`。与 Release 标签前缀一致（第 217 轮起两者对齐）。
    private static var versionText: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "—"
        let build = info?["CFBundleVersion"] as? String ?? "—"
        return "\(short) (\(build))"
    }

    // MARK: - 个人卡

    /// 个人信息卡：大头像（首字）+ 内联改称呼。
    ///
    /// 旧版这一处只是一行带图标的 `TextField` —— 一整页里没有任何"人的痕迹"。
    /// 头像用**暖色光晕**：这是这个 App 唯一属于"你"的形象位。
    private func profileCard(_ c: YNTheme.Palette) -> some View {
        Section {
            HStack(spacing: YNTheme.Space.md) {
                YNAvatarHalo(avatarURL: nil,
                             name: ownerName.isEmpty ? "我" : ownerName,
                             size: 64)

                VStack(alignment: .leading, spacing: 4) {
                    // 内联编辑而不是跳页：只有一个字段，开一屏是多余的。
                    TextField("你的称呼", text: $ownerName)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(c.textPrimary)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onSubmit { saveOwnerName() }
                        .accessibilityLabel("你的称呼")

                    Text(ownerName.isEmpty ? "角色会用它称呼你（留空则不下发）" : "点一下可以改")
                        .font(.caption)
                        .foregroundStyle(c.textTertiary)
                }
            }
            .padding(.vertical, YNTheme.Space.sm)
            .listRowBackground(Color.clear)
        }
    }

    private func saveOwnerName() {
        environment.setOwnerName(ownerName.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    // MARK: - AI 能力

    private var aiSection: some View {
        let c = YNTheme.palette(scheme)
        return Section {
            NavigationLink {
                ChannelListView()
            } label: {
                YNSettingsRow(
                    icon: "cpu",
                    title: "模型渠道",
                    subtitle: "对话用哪个服务商与密钥；可配多条并随时切换。",
                    badge: channelBadge,
                    badgeTint: channelReady ? nil : c.warning
                )
            }

            // 第 203 轮（P0 ②）：四条用途线路。
            // 单独一层入口而不是塞进渠道页 —— 渠道页回答"我配了哪些渠道"，
            // 这里回答"哪个用途用哪条"，是两件事。
            NavigationLink {
                FeatureRouteView()
            } label: {
                YNSettingsRow(
                    icon: "arrow.triangle.branch",
                    title: "用途线路",
                    subtitle: "对话 / 生图 / 朗读 / 向量嵌入各自用哪条渠道与模型。",
                    badge: routesBadge,
                    badgeTint: routesBoundCount == 0 ? nil : c.accent
                )
            }

            // 第 218 轮：回复语言。
            //
            // 起因是一条用户反馈「回复有时是英文」。查下来引擎里**根本没有语言指令**，
            // 用什么语言回答全靠模型自己从人设与历史里猜 —— 这一项把它变成显式下发。
            // 默认「跟随模型」= 不下发，行为与 Android 一致。
            NavigationLink {
                ReplyLanguageView()
            } label: {
                YNSettingsRow(
                    icon: "character.bubble",
                    title: "回复语言",
                    subtitle: "要求它固定用哪种语言回复（含思考过程与工具参数）。",
                    badge: replyLanguageBadge
                )
            }

            // 第 227 轮：通知与提醒（角标 / 回复完成提醒 / 每日提醒）。
            // 放在「AI 能力」组里是因为它决定"它会不会在你不看的时候出现"，
            // 属于陪伴体验的一部分；开关本身在系统连接那种"配置"语义之外。
            NavigationLink {
                NotificationSettingsView()
            } label: {
                YNSettingsRow(
                    icon: "bell.badge",
                    title: "通知与提醒",
                    subtitle: "角标显示未读、回复跑完提醒你、每天固定时间提醒。",
                    badge: notificationBadge
                )
            }

            NavigationLink {
                StickerLibraryView()
            } label: {
                YNSettingsRow(
                    icon: "face.smiling",
                    title: "表情库",
                    subtitle: "导入表情并命名 —— 名称决定标签，模型按标签挑表情。",
                    // ⚠️ 第 226 轮：这里原先显示 `stickerTagsSummary`（**标签数**），
                    // 而它只在 `syncRuntimeConfig()` 里被赋值（发消息前 / 改渠道后才跑）
                    // → 刚开机会显示 `—`，用户反馈"有时候正常有时候不正常"。
                    // 现在显示**张数**（这才是"表情总数"），并在下面 `.task` 里按需刷新。
                    badge: environment.stickerCount.map { "\($0) 张" } ?? "—"
                )
            }

            // 第 207 轮（P1）：上下文与收据。
            //
            // 入口在这里而不是只挂在消息菜单里：收据是"这一句为什么这么答"的取证面，
            // 而**上下文选项**（历史条数 / 短期记忆上限）需要有个固定的家 ——
            // 放在能看到它效果的那一页旁边，改完立刻能在材料清单里看到差别。
            NavigationLink {
                TurnReceiptView(receipt: nil)
            } label: {
                YNSettingsRow(
                    icon: "list.bullet.rectangle",
                    title: "上下文与收据",
                    subtitle: "看模型每一轮实际收到了什么，并调历史条数与记忆注入上限。"
                )
            }

            // 第 207 轮（P1）：记忆。
            //
            // 与「上下文与收据」是一对：那边说**这一轮带了什么**，
            // 这边说**库里还有什么、哪些不再注入**（"它怎么忘了"的另一半答案）。
            NavigationLink {
                MemoryView()
            } label: {
                YNSettingsRow(
                    icon: "brain",
                    title: "记忆",
                    subtitle: "短期（每回合注入）与长期（按相关度召回）分别看，可逐条软删除。",
                    badge: environment.memoryRepo.flatMap { $0.counts().active }
                        .map { "\($0) 条" }
                )
            }

            // 第 209 轮：技能。
            //
            // 这一页补的是一条**死链**：模型一直看得到 `load_skill` 工具，
            // 但 iOS 既没有界面也没有导入路径 → 技能表恒为空，
            // 模型永远只能得到「未找到技能」。现在能看、能导、能开关。
            NavigationLink {
                SkillLibraryView()
            } label: {
                YNSettingsRow(
                    icon: "wrench.and.screwdriver",
                    title: "技能",
                    subtitle: "按需加载的操作说明：导入、启用/禁用、看正文。",
                    badge: skillBadge
                )
            }

            // 第 209 轮：世界书。
            //
            // 与技能同一种病、但更隐蔽：表在、每回合也会推给引擎，
            // 却没有任何界面与写入路径 → 恒为空，而界面上看不出异常。
            NavigationLink {
                WorldbookLibraryView()
            } label: {
                YNSettingsRow(
                    icon: "book.closed",
                    title: "世界书",
                    subtitle: "按关键词注入设定：导入、启用/停用、看条目与生效状态。",
                    badge: worldbookBadge
                )
            }

            // 说明：表情库此前没有入口，是因为导入页没做。
            // 现在 `StickerImportRepository` 有了调用方。

        } header: {
            Text("AI 能力")
        } footer: {
            if !channelReady {
                // 明确告诉用户"不配就用不了"，而不是等他发消息才弹错。
                Text("未配置可用的 API Key，聊天无法获得回复。")
            }
        }
    }

    private var channelReady: Bool {
        (try? environment.apiConfigs?.activeConfig()) != nil
    }

    /// 用户**显式指定**过渠道的线路条数。
    ///
    /// ⚠️ 刻意**不数「对话」**：对话线路由 `upsertActiveConfig` / `activate`
    /// 自动与「当前启用渠道」保持同步（见 `ApiConfigRepository.activate` 的不变式），
    /// 所以它几乎恒为已绑定 —— 数进去的话，一个刚装好、从没进过线路页的用户
    /// 也会看到「已指定 1 条」，那是在描述实现细节，不是在描述用户做过什么。
    private var routesBoundCount: Int {
        guard let repo = environment.apiConfigs else { return 0 }
        return FeaturePurpose.allCases
            .filter { $0 != .chat }
            .filter { purpose in
                let bound = (try? repo.routeConfigId(purpose: purpose)) ?? nil
                return bound != nil
            }
            .count
    }

    /// 徽标回答两个问题：另外三条线路有没有单独指定；没有就是全部跟随当前。
    private var routesBadge: String {
        let count = routesBoundCount
        return count == 0 ? "跟随当前渠道" : "已单独指定 \(count) 条"
    }

    /// 回复语言徽标：显示**实际下发的内容**（跟随模型时明说"跟随模型"）。
    ///
    /// 不用"已设置/未设置"这种含糊说法 —— 这一项的意义全在"到底有没有下发指令"。
    private var replyLanguageBadge: String {
        guard let repo = environment.apiConfigs else { return "—" }
        return ReplyLanguage.load(from: repo).title
    }

    /// 通知徽标：如实显示"现在会不会响"（第 227 轮）。
    ///
    /// 不显示开关数量那种虚的 —— 用户看这一行只想知道一件事：
    /// **它到底会不会在我没看的时候提醒我**。
    private var notificationBadge: String {
        let settings = environment.notificationSettings
        switch (settings.replyDoneEnabled, settings.dailyEnabled) {
        case (true, true): return "回复 + 每日 \(settings.dailyTimeText)"
        case (true, false): return "回复提醒"
        case (false, true): return "仅每日 \(settings.dailyTimeText)"
        case (false, false): return "全部关闭"
        }
    }

    /// 技能徽标：已启用 / 全部。
    ///
    /// 用"已启用"而不是总数：技能页的主开关语义就是"模型能看到几个"。
    private var skillBadge: String? {
        guard let database = environment.database else { return nil }
        let counts = SkillRepository(database: database).counts()
        guard counts.all > 0 else { return "还没导入" }
        return "\(counts.enabled)/\(counts.all) 启用"
    }

    /// 世界书徽标：**回答"现在到底注不注入"**，而不是"有几本"。
    ///
    /// 引擎只取第一本启用的（`LIMIT 1`），所以"有 3 本"完全可能等于"没注入"。
    private var worldbookBadge: String? {
        guard let database = environment.database else { return nil }
        let repo = WorldbookRepository(database: database)
        guard let active = repo.activeGlobalBook() else { return "未注入" }
        return "生效：\(active.name)"
    }

    /// 徽标只回答一个问题：「现在能不能用」。
    private var channelBadge: String {
        guard let active = try? environment.apiConfigs?.activeConfig() else {
            return "未配置"
        }
        let name = environment.visibleApiPresets()
            .first { $0.provider == active.provider }?.displayName ?? active.provider
        let hasKey = !environment.resolvedAPIKey(configId: active.id).isEmpty
        return hasKey ? name : "\(name) · 缺密钥"
    }

    // MARK: - 系统连接

    /// 系统能力授权。
    ///
    /// 完整形态（每类三态 + 写入确认 + 访问日志）需要真正接入各系统框架，
    /// 见 `SystemConnectionsView` 的说明。这里先把**入口**立起来，
    /// 并把当前实际状态如实写进徽标（"只读"），不假装能开关。
    private var systemSection: some View {
        Section {
            NavigationLink {
                SystemConnectionsView()
            } label: {
                YNSettingsRow(
                    icon: "link",
                    title: "系统连接",
                    subtitle: "本 App 声明了哪些系统能力，以及去哪里管理授权。",
                    badge: "只读"
                )
            }
        } header: {
            Text("系统连接")
        }
    }

    // MARK: - 数据

    private var dataSection: some View {
        Section {
            NavigationLink {
                BackupView()
            } label: {
                YNSettingsRow(
                    icon: "externaldrive",
                    title: "数据与备份",
                    subtitle: "导出加密备份、从备份恢复；数据始终能带走。"
                )
            }

            // 第 207 轮：用量页终于有数据可看 ——
            // `token_usage` 在此之前 iOS 侧只有备份恢复会写，运行时无人写。
            NavigationLink {
                UsageStatsView()
            } label: {
                YNSettingsRow(
                    icon: "chart.bar",
                    title: "用量",
                    subtitle: "输入/输出 token 与请求次数（供应商回执口径）。"
                )
            }
        } header: {
            Text("数据")
        } footer: {
            Text("没有导出入口的数据等于不属于你。备份以自设口令加密，口令不存本机。")
        }
    }

    // MARK: - 诊断

    private var diagnosticSection: some View {
        let c = YNTheme.palette(scheme)
        return Section {
            NavigationLink {
                DiagnosticsView()
            } label: {
                YNSettingsRow(
                    icon: "stethoscope",
                    title: "诊断",
                    subtitle: "构建标记、数据状态、安全基线、渠道状态；只读自查。",
                    badge: environment.startupError == nil ? "正常" : "有错误",
                    badgeTint: environment.startupError == nil ? nil : c.danger
                )
            }
        } header: {
            Text("诊断")
        } footer: {
            Text("构建 SDK 与设计兼容模式决定系统是否渲染新外观 —— 排查外观问题时先看这两项。")
        }
    }

    /// 诊断摘要（已移入 `DiagnosticsView`，此处保留供「我」页快速一瞥）。
    private var diagnosticSummarySection: some View {
        let c = YNTheme.palette(scheme)
        return Section("诊断") {
            // 只读汇总。完整诊断页（含契约自检）是后续项 ——
            // 这里先把"当前能不能用"如实摆出来，避免用户靠猜。
            LabeledContent("数据库", value: environment.resolvedFTSVersion)
            LabeledContent("内容过滤", value: environment.contentFilterReady ? "已就绪" : "未就绪")
            LabeledContent("安全种子", value: environment.securitySeedSummary)
            LabeledContent("渠道数量", value: "\(channelCount) 条")
            // 表情状态放在这里：目前还没有导入页，所以只如实汇报"有没有"，
            // 不做一个点不动的入口。
            LabeledContent("表情标签", value: environment.stickerTagsSummary)

            if let error = environment.startupError {
                // T6：不只靠颜色 —— 图标 + 文字共同传达
                Label {
                    Text(error).font(.caption)
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(c.danger)
                }
            }
        }
    }

    private var channelCount: Int {
        ((try? environment.apiConfigs?.allConfigs()) ?? []).count
    }

    // MARK: - 关于

    private var aboutSection: some View {
        Section("关于") {
            // 第 211 轮：使用指南。
            //
            // 放在「关于」旁边而不是塞进 AI 能力：它回答的是"这个 App 怎么用起来"，
            // 覆盖第一次配置、记忆/技能/世界书、权限、以及"遇到问题先看哪儿"。
            NavigationLink {
                GuideView()
            } label: {
                Label("使用指南", systemImage: "questionmark.circle")
            }
            LabeledContent("版本", value: Self.appVersion)
            LabeledContent("引擎", value: "Rust Agent + SwiftUI")
        }
    }

    private static var appVersion: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "—"
        return "\(v) (\(b))"
    }

    // MARK: - 载入

    private func loadOnce() {
        guard !didLoad else { return }
        didLoad = true
        ownerName = KeychainStore.string(for: KeychainStore.Key.ownerName) ?? ""
    }
}
