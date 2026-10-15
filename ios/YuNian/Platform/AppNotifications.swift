import Foundation
import UserNotifications
import UIKit

/// App **自己**的通知与角标（与 `DeviceTools.device_notify` 是两件事：
/// 那个是**模型**能调的工具，这个是我们自己的行为）。
///
/// ## iOS 的现实约束（决定了这里能做什么）
/// - **没有服务端推送**：本项目不带 APNs 服务，所以不存在"角色在服务器上主动找你"
/// - **App 不能后台常驻**：切走之后回合会停（除非申请后台任务，
///   而 iOS 只给约 30 秒 —— 够跑完一句短回复，不够长回复）
/// - 于是"主动找你"在 iOS 上**只能**是：**本地定时提醒** + 你点开
///
/// 这些约束写在这里而不是藏起来：界面上的文案必须与它一致，
/// 否则用户会以为"它会在后台一直陪着我"。
enum AppNotifications {

    /// 当前是否已获得通知权限（不弹窗，只查状态）。
    static func authorizationStatus() async -> UNAuthorizationStatus {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    /// 请求权限（幂等：已决定过就不会再弹）。
    @discardableResult
    static func requestAuthorization() async -> Bool {
        do {
            return try await UNUserNotificationCenter.current()
                .requestAuthorization(options: [.alert, .sound, .badge])
        } catch {
            return false
        }
    }

    /// 设图标角标。`count <= 0` 时清掉角标。
    ///
    /// 用 `setBadgeCount`（iOS 16+）而不是已废弃的
    /// `UIApplication.shared.applicationIconBadgeNumber`。
    static func setBadge(_ count: Int) async {
        try? await UNUserNotificationCenter.current().setBadgeCount(max(count, 0))
    }

    /// 「回复跑完了」提醒。
    ///
    /// - Parameters:
    ///   - preview: 回复开头的几十个字（太长会被系统截断，这里先自己收干净）
    ///   - companionId: 放进 `userInfo`，将来做"点通知直达该角色"时要用
    static func notifyReplyDone(companionName: String, preview: String, companionId: Int64) async {
        let content = UNMutableNotificationContent()
        content.title = companionName
        content.body = preview.isEmpty ? "回复好了" : preview
        content.sound = .default
        content.userInfo = ["companionId": companionId, "kind": "reply_done"]
        let request = UNNotificationRequest(
            // 固定 ID：同一个角色的新回复覆盖旧的，不会堆一屏
            identifier: "yunian.reply.\(companionId)",
            content: content,
            trigger: nil
        )
        try? await UNUserNotificationCenter.current().add(request)
    }

    /// 排一条**每日重复**的提醒（本地定时，不需要 App 在跑）。
    ///
    /// 先撤旧的再排新的：不撤的话改时间会叠成两条，用户会收到两次。
    @discardableResult
    static func scheduleDailyReminder(hour: Int, minute: Int, text: String) async -> Bool {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [
            NotificationSettings.dailyRequestIdentifier,
        ])

        let time = NotificationSettings.normalizedTime(hour: hour, minute: minute)
        let content = UNMutableNotificationContent()
        content.title = "予念"
        content.body = text
        content.sound = .default

        var components = DateComponents()
        components.hour = time.hour
        components.minute = time.minute
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: true)
        let request = UNNotificationRequest(
            identifier: NotificationSettings.dailyRequestIdentifier,
            content: content,
            trigger: trigger
        )
        do {
            try await center.add(request)
            return true
        } catch {
            return false
        }
    }

    /// 撤掉每日提醒（用户关掉开关时必须真的撤掉，否则"关了还在响"）。
    static func cancelDailyReminder() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(
            withIdentifiers: [NotificationSettings.dailyRequestIdentifier])
    }

    /// App 现在是不是在前台。用来决定"回复跑完要不要打扰用户"。
    static var isActive: Bool {
        UIApplication.shared.applicationState == .active
    }
}
