import SwiftUI
import UIKit
import UserNotifications

/// 通知与提醒（第 227 轮）。
///
/// ## 这一页要回答的问题
/// 「它会不会在我没看的时候找我？怎么找我？」—— iOS 上这个问题有个**硬边界**：
/// 没有服务端推送、App 也不能后台常驻，所以"角色主动找你"只能是
/// **本地定时提醒** + 你点开。这一页把边界写在明面上，
/// 而不是放几个开关让用户以为它会一直在后台陪着。
///
/// ## 只放真的会生效的开关
/// 每个开关的保存路径都会**立刻执行副作用**（排程 / 撤销 / 刷角标），
/// 见 `AppEnvironment.updateNotificationSettings`。
struct NotificationSettingsView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme
    @Environment(\.openURL) private var openURL

    @State private var settings = NotificationSettings()
    @State private var status: UNAuthorizationStatus = .notDetermined
    @State private var loaded = false

    var body: some View {
        let c = YNTheme.palette(scheme)
        List {
            permissionSection(c)
            badgeSection
            replySection
            dailySection(c)
            limitSection(c)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("通知与提醒")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if !loaded {
                settings = environment.notificationSettings
                loaded = true
            }
            status = await AppNotifications.authorizationStatus()
        }
        // 每一处改动都立刻保存 + 生效（不设"保存"按钮：设置类页面多一步就多一次漏保存）
        .onChange(of: settings) { _, next in
            environment.updateNotificationSettings(next)
        }
    }

    // MARK: - 各段

    private func permissionSection(_ c: YNTheme.Palette) -> some View {
        Section {
            switch status {
            case .authorized, .provisional, .ephemeral:
                Label("系统已允许通知", systemImage: "checkmark.seal.fill")
                    .foregroundStyle(c.success)
                    .font(.subheadline)
            case .denied:
                VStack(alignment: .leading, spacing: YNTheme.Space.xs) {
                    Label("系统里被关掉了", systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(c.warning)
                        .font(.subheadline)
                    Text("下面的开关是 App 内的意愿；系统总开关在「设置 → 通知 → 予念」里。")
                        .font(.caption)
                        .foregroundStyle(c.textSecondary)
                    Button("打开系统设置") {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            openURL(url)
                        }
                    }
                    .font(.footnote)
                    .buttonStyle(.bordered)
                    .tint(c.accent)
                }
            default:
                Button("允许通知") {
                    Task {
                        await AppNotifications.requestAuthorization()
                        status = await AppNotifications.authorizationStatus()
                    }
                }
                .font(.subheadline)
                .tint(c.accent)
            }
        } header: {
            Text("系统权限")
        }
    }

    private var badgeSection: some View {
        Section {
            Toggle("图标角标显示未读数", isOn: $settings.badgeEnabled)
            LabeledContent("当前未读", value: "\(badgePreview) 条")
        } footer: {
            Text("角标数字 = 所有会话的未读总数，口径与首页列表完全一致"
                 + "（思考过程与工具卡片不算未读）。")
        }
    }

    private var replySection: some View {
        Section {
            Toggle("回复跑完时提醒我", isOn: $settings.replyDoneEnabled)
        } footer: {
            Text("只在你**不在看这一页**时发。切到别的 App 后 iOS 通常会挂起本 App，"
                 + "所以它覆盖的是「切走但还没被挂起」这类窗口期 —— "
                 + "长回复不保证跑完（这是系统的限制，不是开关的问题）。")
        }
    }

    private func dailySection(_ c: YNTheme.Palette) -> some View {
        Section {
            Toggle("每天固定时间提醒", isOn: $settings.dailyEnabled)
            if settings.dailyEnabled {
                DatePicker("提醒时间",
                           selection: timeBinding,
                           displayedComponents: .hourAndMinute)
                TextField("提醒文案", text: $settings.dailyText)
                    .textInputAutocapitalization(.never)
            }
        } header: {
            Text("每天提醒")
        } footer: {
            Text("这条是**本地定时通知**：到点由系统弹出来，不需要 App 在运行。"
                 + "文案由你定 —— 刻意不写角色名，因为这条是 App 发的，"
                 + "假装成角色本人在说话会让人出戏。")
        }
    }

    private func limitSection(_ c: YNTheme.Palette) -> some View {
        Section {
            Label("iOS 上没有服务端推送，App 也不能后台常驻 —— "
                  + "所以「角色主动找你」只能靠上面的每日提醒 + 你点开。",
                  systemImage: "info.circle")
                .font(.footnote)
                .foregroundStyle(c.textSecondary)
        } header: {
            Text("这一页的能力边界")
        }
    }

    // MARK: - 帮手

    /// 当前未读（只读展示，让"角标会显示几"在设置里就能看见）。
    private var badgePreview: Int {
        guard let database = environment.database else { return 0 }
        return (try? ConversationRepository(database: database).unreadTotal()) ?? 0
    }

    /// `DatePicker` 要 `Date`，设置里存的是时/分 —— 这里做双向桥接。
    private var timeBinding: Binding<Date> {
        Binding(
            get: {
                var components = DateComponents()
                components.hour = settings.dailyHour
                components.minute = settings.dailyMinute
                return Calendar.current.date(from: components) ?? Date()
            },
            set: { newValue in
                let parts = Calendar.current.dateComponents([.hour, .minute], from: newValue)
                settings.dailyHour = parts.hour ?? settings.dailyHour
                settings.dailyMinute = parts.minute ?? settings.dailyMinute
            }
        )
    }
}
