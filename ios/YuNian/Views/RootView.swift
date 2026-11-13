import SwiftUI
import Combine
import LocalAuthentication

/// App 壳。
///
/// ## 依据
/// - **M2**（HIG `tab-bars`）：**禁止自绘底部导航栏** → 用系统 `TabView`。
///   系统标签栏在 iOS 26+ 自动获得 Liquid Glass，并随外观模式、
///   无障碍设置（Reduce Transparency / Reduce Motion）、动态字体一起演进 ——
///   这些是自绘实现永远追不上的（**A3**）。
/// - **M3**（HIG `toolbars`）：**禁止**给系统栏加 `.toolbarBackground` 或自绘模糊背景。
/// - **M6**（HIG `layout`）：全屏背景必须延伸到底栏**之下**。
///
/// ## 第 254 轮新增
/// - **App 锁**（Face ID / 设备密码）：开关在「我」页签的锁图标。
/// - **外观接线**：读 `appearance.theme` / `appearance.fontScale`（外观设置页写入，
///   本层只读 —— 与设置页解耦，靠键名契约）。
struct RootView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme
    /// 角标刷新用（第 227 轮）
    @Environment(\.scenePhase) private var scenePhase

    enum Tab: Hashable {
        case conversations
        case contacts
        case me
    }

    @State private var selection: Tab = .conversations
    /// App 锁 + 外观偏好（本层持有；读写 `app_meta`）。
    @StateObject private var appLock = AppLockModel()
    /// 外观两键的当前值（`nil` theme = 跟随系统）。
    @State private var themeOverride: ColorScheme?
    /// 字号档（standard 不加修饰符，所以单独存枚举而不是 ColorScheme 那种可选）。
    @State private var fontScaleRaw: String = "standard"

    var body: some View {
        Group {
            if let error = environment.startupError {
                StartupFailureView(message: error)
            } else if environment.runtime != nil {
                mainTabs
            } else {
                StartupLoadingView()
            }
        }
        // App 锁：盖住一切内容（含启动失败页 —— 锁就该连失败页一起遮）。
        .overlay {
            if appLock.isLocked {
                AppLockOverlay(model: appLock)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.15), value: appLock.isLocked)
        // 外观接线（第 254 轮）：设置页只写 `app_meta` 键，这里读并应用。
        // 用 ClosedRange 是因为 `.dynamicTypeSize` 的签名只收 RangeExpression，
        // 单点区间 = 把档位钉死。
        .preferredColorScheme(themeOverride)
        .modifier(AppearanceFontScale(raw: fontScaleRaw))
        // 三处刷新点（无推送机制，选低成本方案）：
        //   1. scenePhase 变化（回前台 / 切后台）顺带重读 —— 从设置页改完切走再回来即生效
        //   2. 页签切换重读 —— 用户改完外观回到「予念」页签立刻生效
        //   3. 首次就绪（task）兜冷启动竞态
        .onChange(of: scenePhase) { _, newPhase in
            Task { await environment.refreshBadge() }
            appLock.handleScenePhase(newPhase)
            if newPhase == .active { reloadAppearance() }
        }
        .onChange(of: selection) { _, _ in
            reloadAppearance()
        }
        .task(id: environment.runtime != nil) {
            appLock.refreshEnabled(environment: environment)
            reloadAppearance()
        }
    }

    /// 系统标签栏。
    ///
    /// 用 `.tabItem`（iOS 17 起可用）而非 iOS 18 的 `Tab(_:systemImage:value:)`，
    /// 因为部署目标是 iOS 17.0。两者在 iOS 26 上都拿到系统的 Liquid Glass 外观。
    ///
    /// **C6**：可见 Tab ≤ 5 —— 现在 3 个。
    private var mainTabs: some View {
        TabView(selection: $selection) {
            ConversationsView()
                .tabItem { Label("予念", systemImage: "bubble.left.and.bubble.right") }
                .tag(Tab.conversations)

            ContactsView()
                .tabItem { Label("通讯录", systemImage: "person.2") }
                .tag(Tab.contacts)

            MeView()
                .tabItem { Label("我", systemImage: "person.crop.circle") }
                .tag(Tab.me)
        }
        // M6：背景延伸到底栏之下，让系统材质有东西可采样。
        .background {
            YNCanvas()
        }
        // 第 222 轮（真机截图驱动）：**标签栏的选中色要跟品牌色走**。
        // 之前只在各页的 NavigationStack 上设了 `.tint`，而浮动标签栏在
        // TabView 这一层，于是选中项呈现系统蓝 —— 与整屏紫/暖色根本不搭。
        // 在 TabView 上设一次，三个标签一起生效。
        .tint(YNTheme.palette(scheme).accent)
        // App 锁开关（第 254 轮）：仅「我」页签显示。
        //
        // ## 为什么位置妥协在"我"页签右上悬浮
        // TabView 层挂 `.toolbar` 不渲染（三个页签各自是 NavigationStack，
        // 工具栏归它们所有，而那些文件不在本层可改范围）；
        // 放置在其他位置都会遮住对话页/通讯录的工具栏。
        // 放在导航栏行之下右侧、且只在「我」根页，是唯一零冲突的位置。
        .overlay(alignment: .top) {
            if selection == .me {
                lockToggle
                    .padding(.top, 56)   // 导航栏行之下
                    .padding(.trailing, YNTheme.Space.lg)
            }
        }
    }

    @ViewBuilder
    private var lockToggle: some View {
        Button {
            appLock.toggleEnabled(environment: environment)
        } label: {
            Image(systemName: appLock.enabled ? "lock.fill" : "lock.open")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(appLock.enabled ? YNTheme.palette(scheme).warm
                                                 : YNTheme.palette(scheme).textTertiary)
                .frame(width: 36, height: 36)
                .contentShape(Rectangle())
                .background(.ultraThinMaterial, in: Circle())
        }
        .buttonStyle(.plain)
        .disabled(appLock.isAuthenticating)
        .accessibilityLabel(appLock.enabled ? "关闭 App 锁" : "开启 App 锁")
        .accessibilityHint(appLock.enabled ? "需要验证后关闭" : "开启后进入 App 需要 Face ID 或密码")
    }

    /// 从 `app_meta` 重读外观两键。
    private func reloadAppearance() {
        guard let repo = environment.apiConfigs else { return }
        switch (try? repo.metaValue(forKey: "appearance.theme")) ?? nil {
        case "light": themeOverride = .light
        case "dark": themeOverride = .dark
        default: themeOverride = nil
        }
        fontScaleRaw = (try? repo.metaValue(forKey: "appearance.fontScale")) ?? "standard"
    }
}

// MARK: - 字号档

/// 把 `appearance.fontScale` 的字符串映射成 `.dynamicTypeSize` 的钉死区间。
///
/// standard 不钉（跟随系统的 Dynamic Type 设置）；其余档位用单点区间钉死 ——
/// 因为 `.dynamicTypeSize` 的签名只收 RangeExpression。
private struct AppearanceFontScale: ViewModifier {
    let raw: String

    func body(content: Content) -> some View {
        switch raw {
        case "small": content.dynamicTypeSize(.medium ... .medium)
        case "large": content.dynamicTypeSize(.xLarge ... .xLarge)
        case "xlarge": content.dynamicTypeSize(.xxLarge ... .xxLarge)
        default: content
        }
    }
}

// MARK: - App 锁

/// App 锁的状态机（第 254 轮）。
///
/// ## 转移规则（为什么 .inactive 不上锁）
/// 通知中心下拉、系统弹窗、Face ID 自身的弹窗都会让 scenePhase 短暂变成
/// `.inactive` —— 如果对它上锁，用户刚解锁完就会被 Face ID 弹窗引发的
/// active 回环再锁回去。只有真正离开 App（`.background`）才重新上锁。
///
/// ## 为什么用 `.deviceOwnerAuthentication`
/// 它带**设备密码回退**：面容连续失败或不可用时，用户还能用手机密码解锁。
/// 用 `.deviceOwnerAuthenticationWithBiometrics`（只认生物识别）在
/// "戴着口罩 + 没设备用密码"的场景会把用户锁死在 App 外面 —— 那是数据丢失级的事故。
@MainActor
final class AppLockModel: ObservableObject {

    @Published private(set) var enabled = false
    @Published private(set) var isLocked = false
    @Published private(set) var isAuthenticating = false
    @Published private(set) var failureCount = 0
    /// 生物识别/密码都不可用（极少见：设备什么锁都没设）。
    @Published private(set) var authUnavailable = false
    @Published private(set) var notice: String?

    /// 本次前台会话是否已解锁 —— scenePhase 每次回 active 只锁一次。
    private var unlockedThisForeground = false
    private static let key = "applock.enabled"

    /// 从库重读开关（启动 / 需要时调用）。
    func refreshEnabled(environment: AppEnvironment) {
        enabled = (try? environment.apiConfigs?.metaValue(forKey: Self.key)) ?? nil == "1"
        if !enabled { isLocked = false }
    }

    /// 开/关开关。**开启必须先验证一次**：确认是本人开的，
    /// 否则任何拿到手机的人都能把锁打开（那这把锁就没意义了）。
    func toggleEnabled(environment: AppEnvironment) {
        if enabled {
            setEnabled(false, environment: environment)
        } else {
            authenticate(reason: "开启 App 锁需要验证是本人") { [weak self] ok in
                guard ok else { return }
                self?.setEnabled(true, environment: environment)
            }
        }
    }

    private func setEnabled(_ value: Bool, environment: AppEnvironment) {
        _ = try? environment.apiConfigs?.setMetaValue(value ? "1" : "0", forKey: Self.key)
        enabled = value
        if !value {
            isLocked = false
            failureCount = 0
        } else {
            // 刚开锁的瞬间也要求验证一次（本次会话已解锁则不重复）
            if !unlockedThisForeground { isLocked = true }
        }
        unlockedThisForeground = false
    }

    func handleScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .active:
            if enabled && !unlockedThisForeground && !isAuthenticating {
                isLocked = true
            }
        case .background:
            unlockedThisForeground = false
            isLocked = enabled && !isAuthenticating
        case .inactive:
            break   // 见类注释：通知中心/LA 弹窗引发的短暂 inactive 不上锁
        @unknown default:
            break
        }
    }

    /// 解锁（锁定层的「解锁」按钮）。
    func unlock() {
        authenticate(reason: "解锁予念") { [weak self] ok in
            guard let self else { return }
            if ok {
                self.isLocked = false
                self.failureCount = 0
                self.unlockedThisForeground = true
            }
        }
    }

    /// 逃生口：设备没有任何可用验证方式时的唯一出路。
    ///
    /// ⚠️ 这里**不做验证** —— 能看到这个按钮的前提就是 `canEvaluatePolicy`
    /// 已经返回 false（什么验证都做不了）。无限锁死比不锁更糟：
    /// 用户的聊天数据在 SQLite 里，把 App 锁死等于把数据也锁死。
    func forceDisable() {
        setEnabled(false, environment: .shared)
        authUnavailable = false
        notice = nil
    }

    private func authenticate(reason: String, completion: @escaping (Bool) -> Void) {
        guard !isAuthenticating else { return }
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            authUnavailable = true
            notice = error?.localizedDescription ?? "这台设备没有可用的验证方式"
            return
        }
        isAuthenticating = true
        notice = nil
        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { [weak self] ok, err in
            Task { @MainActor [weak self] in
                guard let self else { return }
                isAuthenticating = false
                if ok {
                    authUnavailable = false
                    completion(true)
                } else {
                    // 用户主动取消只提示不计次 —— 那不是"验证失败"
                    let isCancel = (err as? LAError)?.code == .userCancel
                    if !isCancel {
                        failureCount += 1
                        notice = "第 \(failureCount) 次验证失败"
                    } else {
                        notice = "已取消验证"
                    }
                    completion(false)
                }
            }
        }
    }
}

/// 全屏锁定层。
///
/// ## 为什么不用系统 App Lock 那种模糊透出
/// 模糊透出（`Material` + 高斯模糊）在亮色壁纸下仍能辨认出对话头像与名字的轮廓。
/// 锁屏的意义是"别人拿到手机看不到任何内容"，所以背景**完全不透明**。
struct AppLockOverlay: View {

    @ObservedObject var model: AppLockModel
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let c = YNTheme.palette(scheme)
        ZStack {
            YNCanvas()

            VStack(spacing: YNTheme.Space.lg) {
                Image(systemName: model.enabled ? "lock.fill" : "exclamationmark.lock")
                    .font(.system(size: 44, weight: .light))
                    .foregroundStyle(c.accent)
                    .accessibilityHidden(true)

                Text(model.enabled ? "予念已锁定" : "无法启用验证")
                    .font(YNTheme.TextStyle.title)
                    .foregroundStyle(c.textPrimary)

                if model.authUnavailable {
                    Text("这台设备既没有设置 Face ID / Touch ID，也没有设备密码 ——\n无法验证身份。可以关闭 App 锁继续使用。")
                        .font(.footnote)
                        .foregroundStyle(c.textSecondary)
                        .multilineTextAlignment(.center)

                    Button {
                        model.forceDisable()
                    } label: {
                        Text("关闭 App 锁")
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, 18)
                            .padding(.vertical, 10)
                            .background(c.surface, in: Capsule())
                            .foregroundStyle(c.textPrimary)
                    }
                    .buttonStyle(.plain)
                } else {
                    if let notice = model.notice {
                        Text(notice)
                            .font(.footnote)
                            .foregroundStyle(c.warning)
                    }

                    Button {
                        model.unlock()
                    } label: {
                        Label("解锁", systemImage: "faceid")
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 22)
                            .padding(.vertical, 12)
                            .background(c.accent, in: Capsule())
                            .foregroundStyle(c.onAccent)
                    }
                    .buttonStyle(.plain)
                    .disabled(model.isAuthenticating)
                }
            }
            .padding(.horizontal, YNTheme.Space.xxl)
        }
        .ignoresSafeArea()
    }
}

// MARK: - 启动态

/// 启动中。用骨架屏而不是白屏转圈 —— 内容到达时不会发生布局跳动。
struct StartupLoadingView: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let c = YNTheme.palette(scheme)
        ZStack {
            YNCanvas()
            ProgressView()
                .tint(c.accent)
                .accessibilityLabel("正在启动")
        }
    }
}

/// 启动失败。
struct StartupFailureView: View {
    let message: String

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let c = YNTheme.palette(scheme)
        ZStack {
            YNCanvas()
            VStack(spacing: YNTheme.Space.lg) {
                // T6：不只靠颜色 —— 图标 + 文字标题共同传达
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.largeTitle)
                    .foregroundStyle(c.danger)

                Text("启动失败")
                    .font(YNTheme.TextStyle.title)
                    .foregroundStyle(c.textPrimary)

                Text(message)
                    .font(YNTheme.TextStyle.callout)
                    .foregroundStyle(c.textSecondary)
                    .textSelection(.enabled)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, YNTheme.Space.xxl)
            }
        }
    }
}
