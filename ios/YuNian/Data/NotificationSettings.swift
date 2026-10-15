import Foundation

/// 通知与提醒的设置（存在 `app_meta`，键前缀 `notify.`）。
///
/// ## 为什么要有这一层
/// 这三件事的决定权在用户手上，而且**都要真的生效**：
/// - **角标**：图标上那个数字 = 未读总数（不做就成了"看着像有消息、其实没有"）
/// - **回复完成提醒**：你切走了，回复跑完了 —— 得让你知道
/// - **每日提醒**：iOS 没有服务端推送，App 也不能后台常驻，
///   所以"角色主动找你"在 iOS 上**只能**是本地定时提醒 + 你点开
///
/// ⚠️ 与 `DeviceTools.device_notify` 的区别：那是**模型**能调的工具
/// （`device_notify`，让 AI 自己发通知）；这里是 **App 自己**的行为。
/// 两者共用系统通知中心，但语义完全不同，别混。
struct NotificationSettings: Equatable {

    /// 图标角标显示未读总数（默认开 —— 角标是系统级约定，关掉容易漏消息）
    var badgeEnabled = true
    /// 回复跑完时，如果 App 不在前台就发一条本地通知（默认开）
    var replyDoneEnabled = true
    /// 每日提醒（默认**关** —— 默认开等于"刚装上就每天弹一次"，很烦）
    var dailyEnabled = false
    var dailyHour = 20
    var dailyMinute = 0
    /// 每日提醒的文案。刻意不写角色名：这条提醒是 App 发的，不是角色发的，
    /// 措辞上不该假装是角色本人在说话（想改也留了输入框）。
    var dailyText = "想你了，点开看看"

    static let badgeKey = "notify.badge"
    static let replyDoneKey = "notify.reply_done"
    static let dailyEnabledKey = "notify.daily_enabled"
    static let dailyHourKey = "notify.daily_hour"
    static let dailyMinuteKey = "notify.daily_minute"
    static let dailyTextKey = "notify.daily_text"

    /// 每日提醒的固定标识符（改时间时先撤旧的再排新的，避免叠出两条）。
    static let dailyRequestIdentifier = "yunian.daily.reminder"

    static func load(from repo: ApiConfigRepository?) -> NotificationSettings {
        var settings = NotificationSettings()
        guard let repo else { return settings }
        // 读不到就是默认值 —— 与 Android/其它 iOS 设置同一原则：**不猜**
        if let raw = try? repo.metaValue(forKey: badgeKey), let value = Bool(raw) {
            settings.badgeEnabled = value
        }
        if let raw = try? repo.metaValue(forKey: replyDoneKey), let value = Bool(raw) {
            settings.replyDoneEnabled = value
        }
        if let raw = try? repo.metaValue(forKey: dailyEnabledKey), let value = Bool(raw) {
            settings.dailyEnabled = value
        }
        if let raw = try? repo.metaValue(forKey: dailyHourKey), let value = Int(raw) {
            settings.dailyHour = value
        }
        if let raw = try? repo.metaValue(forKey: dailyMinuteKey), let value = Int(raw) {
            settings.dailyMinute = value
        }
        if let raw = try? repo.metaValue(forKey: dailyTextKey), !raw.isEmpty {
            settings.dailyText = raw
        }
        let time = Self.normalizedTime(hour: settings.dailyHour, minute: settings.dailyMinute)
        settings.dailyHour = time.hour
        settings.dailyMinute = time.minute
        return settings
    }

    func save(to repo: ApiConfigRepository?) throws {
        guard let repo else { return }
        try repo.setMetaValue(badgeEnabled ? "1" : "0", forKey: Self.badgeKey)
        try repo.setMetaValue(replyDoneEnabled ? "1" : "0", forKey: Self.replyDoneKey)
        try repo.setMetaValue(dailyEnabled ? "1" : "0", forKey: Self.dailyEnabledKey)
        let time = Self.normalizedTime(hour: dailyHour, minute: dailyMinute)
        try repo.setMetaValue("\(time.hour)", forKey: Self.dailyHourKey)
        try repo.setMetaValue("\(time.minute)", forKey: Self.dailyMinuteKey)
        try repo.setMetaValue(dailyText, forKey: Self.dailyTextKey)
    }

    /// 把时分夹到合法范围。
    ///
    /// 抽成纯函数是因为它**必须可测**：`UNCalendarNotificationTrigger` 拿到
    /// 越界的时/分（25 点、-1 分）不会报错，而是**静默不触发** ——
    /// 那种 bug 在用户那里表现为"提醒设了但从来不响"，极难排查。
    static func normalizedTime(hour: Int, minute: Int) -> (hour: Int, minute: Int) {
        (min(max(hour, 0), 23), min(max(minute, 0), 59))
    }

    /// 每日提醒排期用的一句描述（界面与测试共用同一份措辞）。
    var dailyTimeText: String {
        String(format: "%02d:%02d", dailyHour, dailyMinute)
    }
}
