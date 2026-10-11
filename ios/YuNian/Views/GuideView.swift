import SwiftUI

/// 使用指南 —— 「这个 App 怎么用起来」。
///
/// ## 为什么做在应用内（而不是只写 README）
/// README 面向开发者；用户装完 App 面对的是**空聊天页**和**一长串设置项**。
/// 参照里 `guide`（267 条 / 文章 226 条）也是一个正式模块，不是附带品。
///
/// ## 三条写作原则
/// 1. 每条只回答一个问题：**在哪、做什么、做完会看到什么**。
/// 2. **只写真的存在的功能** —— 指南里写一个点不动的入口，比没有指南更糟
///    （这条与"不放假按钮"是同一条原则）。
/// 3. 每条尽量带一个「去这里」的深链：指南的价值一半在"教我怎么做"，
///    另一半在"直接带我过去"。
struct GuideView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    /// 自检结果。
    ///
    /// ⚠️ **不能直接在 `body` 里算**：它要读系统权限（AVFoundation / Photos…）
    /// 与几张表的计数，放在 body 里会随每次重绘都跑一遍。
    /// 这里改成一次性算好放进 `@State`，下拉可重算。
    @State private var selfChecks: [Check] = []

    var body: some View {
        let c = YNTheme.palette(scheme)
        return List {
            selfCheckSection(c)
            quickStartSection(c)
            contextSection(c)
            memorySection(c)
            skillSection(c)
            worldbookSection(c)
            voiceSection(c)
            privacySection(c)
            troubleshootingSection(c)
            diagnosticsSection(c)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("使用指南")
        .navigationBarTitleDisplayMode(.inline)
        .tint(c.accent)
        .task { if selfChecks.isEmpty { selfChecks = computeChecks() } }
        .refreshable { selfChecks = computeChecks() }
    }

    // MARK: - 开机自检

    /// 一条自检项（**全部来自真实状态**，不猜、不写死）。
    struct Check: Identifiable {
        var id: String
        var title: String
        var detail: String
        var ok: Bool
    }

    /// 「现在的状态」——把最容易卡住新用户的几处一次算出来。
    ///
    /// ## 为什么值得放在指南最上面
    /// 出问题的位置几乎总是这几处：没配渠道、生图线路没绑（于是拿对话模型去生图）、
    /// 技能没导入（于是模型说"未找到技能"）、世界书没启用（于是设定不生效）、
    /// 权限没给（于是那项能力永远不可用）。
    /// 让用户**一眼看到缺哪一项**，比让他读完整篇指南再回头查快得多。
    ///
    /// ## 只用既有读数
    /// 权限那一项复用 `SystemConnectionsView.read()` —— 同一份读数，
    /// 免得两页对同一个权限给出不同结论。
    private func selfCheckSection(_ c: YNTheme.Palette) -> some View {
        let items = selfChecks
        let failing = items.filter { !$0.ok }.count
        return Section {
            ForEach(items) { item in
                HStack(alignment: .top, spacing: YNTheme.Space.sm) {
                    Image(systemName: item.ok ? "checkmark.circle" : "exclamationmark.circle")
                        .foregroundStyle(item.ok ? c.accent : c.warning)
                        .frame(width: 18)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.title).font(.subheadline)
                        Text(item.detail)
                            .font(.caption)
                            .foregroundStyle(c.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.vertical, 2)
            }
        } header: {
            Text(failing == 0 ? "现在的状态：都就绪了" : "现在的状态：有 \(failing) 项可以补一下")
        } footer: {
            Text("这些读数来自本机真实状态，与你刚才的操作同步。缺项不影响聊天，只影响对应能力。")
        }
    }

    private func computeChecks() -> [Check] {
        var items: [Check] = []

        // ① 渠道
        let configs = (try? environment.apiConfigs?.allConfigs()) ?? []
        let enabledChannels = configs.filter(\.isEnabled).count
        // 这个形状与 FeatureRouteView 里既有的写法一致（`(try? …) ?? nil`），
        // 别在这里另造 `try?` 的组合 —— 见 VoiceProfileStore 的编译器崩溃注释。
        let activeChannel = (try? environment.apiConfigs?.activeConfig()) ?? nil
        items.append(Check(
            id: "channel",
            title: "渠道",
            detail: configs.isEmpty
                ? "还没配渠道 —— 聊天会提示「请先配置API」"
                : "已配 \(configs.count) 条，启用 \(enabledChannels) 条" + (activeChannel == nil ? "（当前没有启用中的渠道）" : ""),
            ok: activeChannel != nil
        ))

        // ② 三条功能线路（没绑定就是跟随当前渠道 —— 提示而不是报错）
        for purpose in [FeaturePurpose.image, .tts, .embedding] {
            let resolution = (try? environment.apiConfigs?.resolve(purpose: purpose)) ?? nil
            let bound = resolution?.isBound == true
            items.append(Check(
                id: "route-\(purpose.rawValue)",
                title: "\(purpose.title)线路",
                detail: bound ? "已单独绑定渠道" : "跟随当前渠道（若该渠道不擅长\(purpose.title)，建议单独绑一条）",
                ok: bound
            ))
        }

        // ③ 技能 / 世界书 / 记忆（引擎侧是否真的有东西可用）
        if let database = environment.database {
            let skills = SkillRepository(database: database).counts()
            items.append(Check(
                id: "skills",
                title: "技能",
                detail: skills.all == 0
                    ? "还没导入 —— 模型调用 load_skill 会得到「未找到技能」"
                    : "\(skills.all) 个，启用 \(skills.enabled) 个",
                ok: skills.enabled > 0
            ))

            let activeBook = WorldbookRepository(database: database).activeGlobalBook()
            items.append(Check(
                id: "worldbook",
                title: "世界书",
                detail: activeBook.map { "生效：\($0.name)（\($0.entryCount) 条）" }
                    ?? "没有启用的世界书 —— 设定不会注入",
                ok: activeBook != nil
            ))
        }

        if let counts = environment.memoryRepo?.counts() {
            items.append(Check(
                id: "memory",
                title: "记忆",
                detail: counts.active == 0
                    ? "还没有会进提示词的记忆（聊几轮后会有）"
                    : "会进提示词 \(counts.active) 条 / 共 \(counts.total) 条",
                ok: counts.active > 0
            ))
        }

        // ④ 权限（复用系统连接页的读数）
        let capabilities = SystemConnectionsView.read()
        let available = capabilities.filter { $0.state != .unavailable }
        let granted = available.filter { $0.state == .granted }.count
        let pending = available.filter { $0.state == .notDetermined }.count
        if !available.isEmpty {
            items.append(Check(
                id: "permissions",
                title: "系统权限",
                detail: "已授权 \(granted)/\(available.count)"
                    + (pending > 0 ? "（\(pending) 项还没申请过）" : ""),
                ok: granted == available.count
            ))
        }

        return items
    }

    // MARK: - 第一次用

    private func quickStartSection(_ c: YNTheme.Palette) -> some View {
        Section {
            step(1, "配一条渠道", "填服务商、API Key 与模型。Key 只进系统钥匙串，不写数据库、不进备份。", c)
            NavigationLink { ChannelListView() } label: { linkRow("去「渠道配置」", c) }

            step(2, "决定四条用途线路", "对话 / 生图 / 朗读 / 向量嵌入可以各走一条渠道与模型；不确定就都跟随当前渠道。", c)
            NavigationLink { FeatureRouteView() } label: { linkRow("去「用途线路」", c) }

            step(3, "选一位角色开聊", "到「通讯录」选角色；聊天页左上角可以看到当前用的是哪个模型。", c)
        } header: {
            Text("第一次用：三步")
        } footer: {
            Text("没有配好 Key 时，发消息会直接提示「请先配置API」，不会静默失败。")
        }
    }

    // MARK: - 上下文

    private func contextSection(_ c: YNTheme.Palette) -> some View {
        Section {
            bullet("长按任意一条回复 → 「本轮收据」", "看这一轮模型到底收到了什么：材料片段与字数、下发了哪些工具、哪些被跳过及原因、供应商回执的 token。", c)
            bullet("「跳过与说明」是最有用的一节", "「它怎么忘了」通常不是记性问题，而是某一段根本没送进去 —— 收据会直接写出来。", c)
            bullet("两种口径不要相加", "本机估算按字数、供应商回执是真实 token，含缓存命中等差异，只能分别看。", c)
            NavigationLink { TurnReceiptView(receipt: nil) } label: { linkRow("去「上下文与收据」（含历史条数、记忆注入上限两个选项）", c) }
        } header: {
            Text("模型每轮看了什么")
        }
    }

    // MARK: - 记忆

    private func memorySection(_ c: YNTheme.Palette) -> some View {
        Section {
            bullet("短期 vs 长期", "短期（WORKING）每回合都注入；长期按相关度召回，不是每回合都带。", c)
            bullet("「不再注入」是删过或过期的", "它们既不注入也不召回 —— 这是「它忘了」最常见的另一半答案。", c)
            bullet("误删可以恢复", "已删除条目**左滑**恢复；但恢复不改变有效期，过期的仍不会进提示词。", c)
            NavigationLink { MemoryView() } label: { linkRow("去「记忆」", c) }
        } header: {
            Text("记忆")
        }
    }

    // MARK: - 技能

    private func skillSection(_ c: YNTheme.Palette) -> some View {
        Section {
            bullet("技能 = 一段操作说明", "平时不占上下文；模型需要时用 `load_skill` 按需加载。", c)
            bullet("导入方式：粘贴 SKILL.md", "frontmatter 写 name / description，正文写怎么做。也可用页面里的示例模板。", c)
            bullet("禁用 = 模型看不到", "既不进技能目录，也不回应 `load_skill`；正文仍留在本机。", c)
            NavigationLink { SkillLibraryView() } label: { linkRow("去「技能」", c) }
        } header: {
            Text("技能")
        }
    }

    // MARK: - 世界书

    private func worldbookSection(_ c: YNTheme.Palette) -> some View {
        Section {
            bullet("世界书 = 按关键词注入的设定", "命中关键词的条目进入提示词；标「常驻」的不看关键词，总是注入。", c)
            bullet("引擎只取第一本启用的", "同时启用多本时只有一本生效，页面会显示是哪一本，并可一键只留它。", c)
            NavigationLink { WorldbookLibraryView() } label: { linkRow("去「世界书」", c) }
        } header: {
            Text("世界书")
        }
    }

    // MARK: - 朗读

    private func voiceSection(_ c: YNTheme.Palette) -> some View {
        Section {
            bullet("朗读走独立的语音线路", "在「用途线路 → 朗读」里绑定语音渠道与音色；页面里可以直接**试听**。", c)
            bullet("每个角色可以有自己的音色", "留空 = 用全局音色；填了则该角色优先。", c)
            bullet("自动朗读默认关", "打开后只念**助手的新文本回复**（表情、图片、历史都不会念）。", c)
        } header: {
            Text("朗读")
        }
    }

    // MARK: - 权限与隐私

    private func privacySection(_ c: YNTheme.Palette) -> some View {
        Section {
            bullet("系统连接页有三个维度", "① 系统授权（相机/麦克风/相册…）② 模型可见性（这条能力对应的工具要不要给模型看）③ 写入确认（写操作要不要先问你）。", c)
            bullet("写入确认默认开", "模型要写剪贴板 / 打开链接 / 发通知 / 存记忆时，聊天页会弹确认卡，你点允许它才做。", c)
            bullet("Key 与备份", "API Key 只进钥匙串；备份是自设口令加密的，口令不存本机。", c)
            NavigationLink { SystemConnectionsView() } label: { linkRow("去「系统连接」", c) }
        } header: {
            Text("权限与隐私")
        } footer: {
            Text("关掉某个工具集后，模型**完全看不到**它（不是「看得到但用不了」）。")
        }
    }

    // MARK: - 遇到问题

    private func troubleshootingSection(_ c: YNTheme.Palette) -> some View {
        Section {
            bullet("有的模型不出思考过程", "只有会返回 `reasoning_content` 的模型才有；换模型就会时有时无，这是模型差异而不是故障。", c)
            bullet("它说「未找到技能 X」", "多半是正文被改过、哈希与索引不一致 —— 技能详情页的「完整性校验」会直接标出来。", c)
            bullet("时间说得不对", "注入的时段是**粗分的**（凌晨/清晨/上午…），模型常照着它自己补一个具体钟点。诊断页「模型看到的时间」可以核对注入的原文。", c)
            bullet("回复很长但断了", "已在第 205 轮修掉：一回合只提交一次，多轮叙述的正文不会再丢前面几轮。", c)
        } header: {
            Text("遇到这些先看哪儿")
        }
    }

    // MARK: - 诊断

    private func diagnosticsSection(_ c: YNTheme.Palette) -> some View {
        Section {
            bullet("诊断页是取证面", "数据库路径、SDK 构建标记、渠道、人设、模型看到的时间、发给模型的工具名都在那里。", c)
            bullet("用量页", "按日与按角色的 token 统计（供应商回执口径）；从这一版开始才有记录。", c)
            NavigationLink { DiagnosticsView() } label: { linkRow("去「诊断」", c) }
            NavigationLink { UsageStatsView() } label: { linkRow("去「用量」", c) }
        } header: {
            Text("想确认状态")
        }
    }

    // MARK: - 组件

    private func step(_ index: Int, _ title: String, _ detail: String,
                      _ c: YNTheme.Palette) -> some View {
        HStack(alignment: .top, spacing: YNTheme.Space.sm) {
            Text("\(index)")
                .font(.caption.weight(.semibold).monospacedDigit())
                .foregroundStyle(c.accent)
                .frame(width: 18, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(c.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 2)
    }

    private func bullet(_ title: String, _ detail: String, _ c: YNTheme.Palette) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(.subheadline)
            Text(detail)
                .font(.caption)
                .foregroundStyle(c.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 2)
    }

    private func linkRow(_ title: String, _ c: YNTheme.Palette) -> some View {
        Label(title, systemImage: "arrow.turn.down.right")
            .font(.subheadline)
            .foregroundStyle(c.accent)
    }
}
