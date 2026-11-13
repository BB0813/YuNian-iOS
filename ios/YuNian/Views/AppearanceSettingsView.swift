import SwiftUI

/// 外观与字号 —— 浅色 / 深色 / 跟随系统 + 四档聊天字号。
///
/// ## 存储契约（与对话页的接线方共用，键名与取值**勿改**）
/// 都进 `app_meta` KV 表（经 `ApiConfigRepository.metaValue` / `setMetaValue`，
/// 不碰 schema、不加表）：
/// - `appearance.theme`     ：`"system"` / `"light"` / `"dark"`；缺省（无键）= 跟随系统
/// - `appearance.fontScale` ：`"small"` / `"standard"` / `"large"` / `"xlarge"`；缺省 = 标准
/// 枚举的 rawValue 就是入库字符串，两边任何一边改动都必须同步。
///
/// ## 保存时机：选中即存（即时存），本页没有「完成」存盘按钮
/// 为什么：这两个设置**每个键独立成立**，不存在"A 配好了 B 才有意义"的组合一致性，
/// 没有值得攒的"整套配置"（生图设置页用「完成」按钮，是因为它一次要写四个键
/// 还要同步 feature_route 绑定 —— 那边攒着一起存语义才清楚）；而 `app_meta`
/// 的写入是单行 UPSERT/DELETE，毫秒级，逐次写没有性能或事务风险。
/// 于是"未保存的更改"这个概念在本页不存在，也就不需要「完成」按钮；
/// 离开本页走系统返回即可。
///
/// ## ⚠️ 生效时机（如实说明，勿改成"立即生效"）
/// 本页只负责把值写进存储；对话页何时读取、如何应用主题与字号是**接线方**的事
/// （不在本文件）。已打开的对话页要重新进入后才会完全采用新值 ——
/// 页顶说明写的是「重启对话页后完全生效」，这不是谦虚，是事实。
struct AppearanceSettingsView: View {

    /// 由「我」页传入（`ApiConfigRepository` 的 init 需要 database，
    /// 与生图设置页同一注入方式）。
    let environment: AppEnvironment

    @Environment(\.colorScheme) private var scheme

    // MARK: - 存储契约

    private static let themeKey = "appearance.theme"
    private static let fontScaleKey = "appearance.fontScale"

    /// 外观三选一。rawValue = 入库字符串。
    enum ThemeChoice: String, CaseIterable, Identifiable {
        case system, light, dark

        var id: String { rawValue }

        var title: String {
            switch self {
            case .system: return "跟随系统"
            case .light:  return "浅色"
            case .dark:   return "深色"
            }
        }

        /// 每项一句"这是什么"的说明：设置页的每一项都要能自解释，
        /// 用户不该点进去才知道选项含义。
        var detail: String {
            switch self {
            case .system: return "跟 iPhone 系统的深浅色设置自动切换"
            case .light:  return "白天看：亮底深字"
            case .dark:   return "晚上看：暗底亮字"
            }
        }

        var icon: String {
            switch self {
            case .system: return "circle.lefthalf.filled"
            case .light:  return "sun.max"
            case .dark:   return "moon"
            }
        }
    }

    /// 字号四档。rawValue = 入库字符串。
    ///
    /// `previewPointSize` **只用于本页的示例渲染**：给用户一个"档位差距肉眼可见"
    /// 的预览。对话页真正按档换算字号是接线方的事 —— 本页只承诺
    /// "档位越高字越大"，不承诺具体 pt（两边都写死数值的话，改档时容易各自漂移）。
    enum FontScaleChoice: String, CaseIterable, Identifiable {
        case small, standard, large, xlarge

        var id: String { rawValue }

        var title: String {
            switch self {
            case .small:    return "小"
            case .standard: return "标准"
            case .large:    return "大"
            case .xlarge:   return "特大"
            }
        }

        /// 预览字号（示意值）：以 iOS 正文 17pt 为「标准」基准，各档拉开明显档差，
        /// 不实际切到对话页也能看出区别。
        var previewPointSize: CGFloat {
            switch self {
            case .small:    return 14
            case .standard: return 17
            case .large:    return 20
            case .xlarge:   return 24
            }
        }
    }

    @State private var theme: ThemeChoice = .system
    @State private var fontScale: FontScaleChoice = .standard
    /// 一次性防抖：onAppear 会反复触发，读一次就够（与 MeView.loadOnce 同款）。
    @State private var didLoad = false
    /// 运行环境未就绪 / 保存失败这类一次性提示。
    @State private var notice: String?

    var body: some View {
        let c = YNTheme.palette(scheme)
        NavigationStack {
            Form {
                effectNoteSection(c)
                themeSection(c)
                fontScaleSection(c)
                if let notice {
                    Section {
                        Text(notice).font(.caption).foregroundStyle(c.textSecondary)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(YNCanvas())
            .navigationTitle("外观与字号")
            .navigationBarTitleDisplayMode(.inline)
            .tint(c.accent)
            .onAppear(perform: load)
        }
    }

    // MARK: - 生效时机说明（页顶）

    /// 按需求放「重启对话页后完全生效」这句 —— 不写"立即生效"：
    /// 主题/字号的读取方在对话页接线，何时应用不在本页控制范围内。
    private func effectNoteSection(_ c: YNTheme.Palette) -> some View {
        Section {
            Label {
                Text("设置会保存进 App 数据；重启对话页后完全生效。")
                    .font(.footnote)
                    .foregroundStyle(c.textSecondary)
            } icon: {
                Image(systemName: "info.circle")
                    .foregroundStyle(c.accent)
            }
        }
    }

    // MARK: - 外观

    private func themeSection(_ c: YNTheme.Palette) -> some View {
        Section {
            ForEach(ThemeChoice.allCases) { choice in
                Button {
                    selectTheme(choice)
                } label: {
                    HStack(spacing: YNTheme.Space.md) {
                        Image(systemName: choice.icon)
                            .foregroundStyle(c.accent)
                            .frame(width: 30)
                            .accessibilityHidden(true)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(choice.title)
                                .foregroundStyle(c.textPrimary)
                            Text(choice.detail)
                                .font(.caption)
                                .foregroundStyle(c.textTertiary)
                        }

                        Spacer(minLength: YNTheme.Space.sm)

                        if theme == choice {
                            Image(systemName: "checkmark")
                                .foregroundStyle(c.accent)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        } header: {
            Text("外观")
        } footer: {
            Text("选「跟随系统」时白天浅色、夜间深色，随 iPhone 系统设置自动切换。")
        }
    }

    // MARK: - 字号

    private func fontScaleSection(_ c: YNTheme.Palette) -> some View {
        Section {
            ForEach(FontScaleChoice.allCases) { choice in
                Button {
                    selectFontScale(choice)
                } label: {
                    HStack(spacing: YNTheme.Space.md) {
                        VStack(alignment: .leading, spacing: 3) {
                            // 档名本身不随档缩放：缩放的是下面的示例文字 ——
                            // 要是档名也跟着变，四行行高互相打架，反而看不出档差。
                            Text(choice.title)
                                .font(.footnote.weight(.medium))
                                .foregroundStyle(c.textPrimary)
                            // 该档的**实际预览字号**（见 FontScaleChoice 注释）：
                            // "这段文字长什么样"就是选它之后聊天正文的样子。
                            Text("今天过得怎么样？")
                                .font(.system(size: choice.previewPointSize))
                                .foregroundStyle(c.textSecondary)
                                .lineLimit(1)
                        }

                        Spacer(minLength: YNTheme.Space.sm)

                        if fontScale == choice {
                            Image(systemName: "checkmark")
                                .foregroundStyle(c.accent)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        } header: {
            Text("字号")
        } footer: {
            Text("每行示例文字就是该档的实际大小。")
        }
    }

    // MARK: - 读写

    /// 读当前值。读不到键（首次使用 / 用户改回过默认）就用缺省档 ——
    /// 与对话页接线方约定一致：**缺省（无键）= system / standard**。
    private func load() {
        guard !didLoad else { return }
        didLoad = true
        guard let repo = environment.apiConfigs else {
            notice = "数据库尚未就绪，暂时不能更改设置"
            return
        }
        if let raw = try? repo.metaValue(forKey: Self.themeKey) {
            theme = ThemeChoice(rawValue: raw) ?? .system
        }
        if let raw = try? repo.metaValue(forKey: Self.fontScaleKey) {
            fontScale = FontScaleChoice(rawValue: raw) ?? .standard
        }
    }

    private func selectTheme(_ choice: ThemeChoice) {
        guard let repo = environment.apiConfigs else {
            notice = "运行环境未就绪，暂时不能更改"
            return
        }
        do {
            // 选回默认档（跟随系统）→ **删键**而不是写 "system"：
            // 契约里"缺省/无键 = system"，删键让"默认"只有一种表示，
            // app_meta 不积一行永远等于缺省的冗余值。
            // ⚠️ `setMetaValue(nil)` 是删键，与写空串语义不同 ——
            // 这里用 nil 表达"回到默认"，绝不写空串占位。
            try repo.setMetaValue(choice == .system ? nil : choice.rawValue, forKey: Self.themeKey)
            theme = choice
            notice = nil
        } catch {
            notice = "保存失败，请稍后重试"
        }
    }

    private func selectFontScale(_ choice: FontScaleChoice) {
        guard let repo = environment.apiConfigs else {
            notice = "运行环境未就绪，暂时不能更改"
            return
        }
        do {
            // 同主题：standard 是默认档 → 删键回默认（理由见 selectTheme）。
            try repo.setMetaValue(choice == .standard ? nil : choice.rawValue, forKey: Self.fontScaleKey)
            fontScale = choice
            notice = nil
        } catch {
            notice = "保存失败，请稍后重试"
        }
    }
}
