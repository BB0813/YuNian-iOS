import SwiftUI

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
/// ## 这一版刻意很小
/// 视觉层已整体废弃重建。这里只立壳，各页随后按
/// `APPLE-DESIGN-CONSTRAINTS.md` 逐屏实现。
struct RootView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    enum Tab: Hashable {
        case conversations
        case contacts
        case me
    }

    @State private var selection: Tab = .conversations

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
