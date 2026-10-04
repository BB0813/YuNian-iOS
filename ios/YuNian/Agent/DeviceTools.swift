import Foundation
import UIKit              // UIDevice（电量）/ UIPasteboard（剪贴板）
import UserNotifications  // device_notify 的本地通知

/// 设备类工具（M4 批次2）。
///
/// ## 先核对过 Rust 侧
/// Rust 的内置工具只有 `emit_segmented` / `send_sticker` / `emit_bubble`
/// （`agent.rs:281-283`），**没有设备类** —— 所以这里是真正的新能力，
/// 不是重复实现（第 58 课：动工前先查 Rust 是否已有等价物）。
///
/// ## 为什么从 `device_get_time` 开始
/// 批次2 的 8 个工具里，它风险最低：
/// 无权限、无外部服务、无用户数据、纯本地计算。
/// 先用它验证「新增一个领域工具」的完整接线，再逐步加其余的。
///
/// ## 与 Android 的对应
/// 逐字对应 `feature/skills/.../DeviceTools.kt` 的 `DeviceGetTimeTool`。
enum DeviceTools {

    /// 工具名（与 Android 一致，模型侧契约）。
    static let getTimeName = "device_get_time"

    /// 工具定义（description 逐字对应 Android）。
    static func getTimeDefinition() -> ToolDefinition {
        ToolDefinition(
            name: getTimeName,
            description: "获取当前日期、时间和星期（无参数）。",
            parametersJson: #"{"type":"object","properties":{}}"#,
            category: ToolCategory.general,
            toolsets: ["device"],
            available: true
        )
    }

    /// 执行 `device_get_time`。
    ///
    /// 返回形如 `{"ok":true,"datetime":"2026-08-07 12:34:56","weekday":"星期五"}` ——
    /// 与 Android `okResult("datetime" to ..., "weekday" to ...)` 的键一致。
    /// `Calendar.weekday` → 中文星期名。
    ///
    /// ⚠️ 抽成纯函数是为了可测：`Calendar.weekday` 是 **1 = 周日**，
    /// 而 Kotlin `DayOfWeek` 是 **1 = 周一**，两者差 1。
    /// 这个映射是 `device_get_time` 里唯一有真实风险的部分
    /// （错了不报错，只是星期全错一位），因此单独拆出来逐值测试。
    static func weekdayName(calendarWeekday: Int?) -> String {
        switch calendarWeekday {
        case 2: return "星期一"
        case 3: return "星期二"
        case 4: return "星期三"
        case 5: return "星期四"
        case 6: return "星期五"
        case 7: return "星期六"
        default: return "星期日"   // 1（周日）与未知/nil
        }
    }

    /// - Parameters:
    ///   - argumentsJson: 入参（本工具不接受参数，保留形参以对齐 Rust trait 签名）
    ///   - now: 当前时刻（测试可注入固定值）
    ///   - calendar: 用于取本地分量的日历。**默认为 `.current`（设备时区）**；
    ///     测试可传入固定时区的日历以避免依赖运行机器的 TZ。
    static func executeGetTime(
        argumentsJson _: String,
        now: Date = Date(),
        calendar: Calendar = .current
    ) -> String {
        let comps = calendar.dateComponents(
            [.year, .month, .day, .hour, .minute, .second, .weekday], from: now)

        // Android 用 LocalDateTime + "yyyy-MM-dd HH:mm:ss"（**设备本地时区**）。
        // iOS 用 Calendar 取本地分量后格式化，等价且不依赖特定 locale。
        // 注意：不能用 ISO8601DateFormatter —— 那是 UTC，会把时间弄错。
        let datetime = String(
            format: "%04d-%02d-%02d %02d:%02d:%02d",
            comps.year ?? 0, comps.month ?? 0, comps.day ?? 0,
            comps.hour ?? 0, comps.minute ?? 0, comps.second ?? 0
        )

        return encodeJSON([
            "ok": true,
            "datetime": datetime,
            "weekday": weekdayName(calendarWeekday: comps.weekday),
        ])
    }

    // MARK: - 电量与充电状态

    static let batteryStatusName = "device_battery_status"

    static func batteryStatusDefinition() -> ToolDefinition {
        ToolDefinition(
            name: batteryStatusName,
            description: "查询手机电量与充电状态（无参数）。",
            parametersJson: #"{"type":"object","properties":{}}"#,
            category: ToolCategory.general,
            toolsets: ["device"],
            available: true
        )
    }

    /// `batteryLevel`（0.0–1.0）→ 整数百分比。
    ///
    /// 抽成纯函数以便测试：Android 是 `level * 100 / scale`（整数除法，向下取整），
    /// iOS 的 `batteryLevel` 是 0.0–1.0 的浮点。用 `rounded()` 而非截断 ——
    /// 0.999 截断得 99、四舍五入得 100，后者更符合用户对「满电」的预期。
    static func percent(fromBatteryLevel level: Double) -> Int {
        Int((level * 100).rounded())
    }

    /// 充电状态判定。对应 Android 的
    /// `status == BATTERY_STATUS_CHARGING || status == BATTERY_STATUS_FULL`。
    static func isCharging(batteryState: UIDevice.BatteryState) -> Bool {
        switch batteryState {
        case .charging, .full: return true
        default: return false            // .unplugged / .unknown
        }
    }

    /// 执行 `device_battery_status`。
    ///
    /// Android 用 `ACTION_BATTERY_CHANGED` sticky broadcast；
    /// iOS 用 `UIDevice` 的 battery API。**必须先开启 monitoring**，
    /// 否则 `batteryLevel` 恒为 -1（与 Android 的 `level < 0` 异常路径对应）。
    ///
    /// 返回 `{"ok":true,"percent":N,"charging":Bool}` 或
    /// `{"ok":false,"error":"..."}` —— 与 Android `okResult` / `errorResult` 一致。
    static func executeBatteryStatus(argumentsJson _: String, device: UIDevice = .current) -> String {
        // 开启 monitoring（幂等；重复设 true 无副作用）。
        // ⚠️ 不能省：不开则 batteryLevel 返回 -1，本工具将永远走异常路径。
        device.isBatteryMonitoringEnabled = true

        let level = device.batteryLevel      // -1 表示不可用，否则 0.0–1.0
        guard level >= 0 else {
            return errorResult("电池数据异常")
        }

        return encodeJSON([
            "ok": true,
            "percent": percent(fromBatteryLevel: level),
            "charging": isCharging(batteryState: device.batteryState),
        ])
    }

    /// 对应 Android `errorResult(message)`：`{"ok":false,"error":msg}`
    private static func errorResult(_ message: String) -> String {
        encodeJSON(["ok": false, "error": message])
    }

    // MARK: - 剪贴板

    static let getClipboardName = "device_get_clipboard"
    static let setClipboardName = "device_set_clipboard"

    /// 读取剪贴板文本的**长度上限**（字符）。
    /// 对应 Android 的 `text.take(2000)` —— 防止把超大剪贴板内容灌进上下文。
    static let clipboardReadLimit = 2000

    static func getClipboardDefinition() -> ToolDefinition {
        ToolDefinition(
            name: getClipboardName,
            description: "读取剪贴板文本内容（无参数）。应用在前台时才能读取。",
            parametersJson: #"{"type":"object","properties":{}}"#,
            category: ToolCategory.general,
            toolsets: ["device"],
            available: true
        )
    }

    static func setClipboardDefinition() -> ToolDefinition {
        ToolDefinition(
            name: setClipboardName,
            description: "把文本写入剪贴板。参数 {text: string}。",
            parametersJson: ##"{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"##,
            category: ToolCategory.general,
            toolsets: ["device"],
            available: true
        )
    }

    /// 执行 `device_get_clipboard`。
    ///
    /// Android 读不到时区分两种情况（服务不可用 / 内容为空），
    /// iOS 的 `UIPasteboard.general.string` 合并为 nil —— 用同一句错误文案覆盖，
    /// 与 Android 的「剪贴板为空或不可读（应用需在前台）」语义一致。
    static func executeGetClipboard(
        argumentsJson _: String,
        pasteboard: UIPasteboard = .general
    ) -> String {
        guard let text = pasteboard.string, !text.isEmpty else {
            return errorResult("剪贴板为空或不可读（应用需在前台）")
        }
        return encodeJSON([
            "ok": true,
            "text": String(text.prefix(clipboardReadLimit)),
        ])
    }

    /// 执行 `device_set_clipboard`。
    ///
    /// ⚠️ 返回的 `length` 用 `utf16.count` 而不是 `count`：
    /// Kotlin 的 `text.length` 是 **UTF-16 代码单元数**，而 Swift 的
    /// `String.count` 是**字符（grapheme cluster）数** —— 对含 emoji / 组合字符的
    /// 文本两者不同。用 `count` 会让 iOS 报出与 Android 不一致的长度。
    static func executeSetClipboard(
        argumentsJson: String,
        pasteboard: UIPasteboard = .general
    ) -> String {
        guard let data = argumentsJson.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let text = obj["text"] as? String
        else {
            return errorResult("text 不能为空")
        }

        pasteboard.string = text
        return encodeJSON([
            "ok": true,
            "copied": true,
            "length": text.utf16.count,
        ])
    }

    // MARK: - 打开网页

    static let openUrlName = "device_open_url"

    static func openUrlDefinition() -> ToolDefinition {
        ToolDefinition(
            name: openUrlName,
            description: "用系统浏览器打开一个网页链接。参数 {url: string}。",
            parametersJson: ##"{"type":"object","properties":{"url":{"type":"string","description":"完整 URL，以 http/https 开头"}},"required":["url"]}"##,
            category: ToolCategory.general,
            toolsets: ["device"],
            available: true
        )
    }

    /// 校验 URL 是否合法（**纯函数，可测**）。
    ///
    /// 对应 Android 的 `!url.startsWith("http://") && !url.startsWith("https://")` 分支。
    /// 刻意与 Android 一致地**只认 http/https**：拒绝 `file://`、`javascript:` 等 scheme
    /// 是一道安全边界（避免模型诱导打开本地文件或危险协议）。
    static func isValidWebUrl(_ raw: String) -> Bool {
        let url = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return url.hasPrefix("http://") || url.hasPrefix("https://")
    }

    /// 从入参 JSON 取出 `url`（trim 后）。缺失或空白返回 nil。
    static func extractUrl(argumentsJson: String) -> String? {
        guard let data = argumentsJson.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = obj["url"] as? String
        else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    /// 执行 `device_open_url`。
    ///
    /// ## ⚠️ 异步桥接（iOS 与 Android 的实质差异）
    /// Android 的 `startActivity` 是同步的；iOS 的 `UIApplication.open` 是**异步**
    /// 且**必须在主线程**调用，而本回调跑在 Rust 的回调线程上。
    /// 因此这里用 `DispatchSemaphore` 把调用派发到主队列并等待结果。
    ///
    /// ## ⚠️ 死锁风险（无法本地验证，必须实测）
    /// 这个桥接依赖一个前提：**主线程没有被 Rust 回合阻塞**。
    /// 按当前实现，`ChatSession.send()` 把阻塞调用放到 `turnQueue`，
    /// 主 actor 只是 `await` 挂起（不是阻塞），所以主队列应当空闲。
    /// 但**这是一个必须实测确认的假设** —— 若主 actor 在等待本工具的结果，
    /// 信号量会等到 10 秒超时，表现为"打开网页很慢"而非明确死锁。
    /// 已在 M0-RUNBOOK 的第 5 步之外补充：实测中若调用涉及打开 URL 的回合，
    /// 重点观察是否有 10 秒卡顿。
    static func executeOpenUrl(argumentsJson: String) -> String {
        guard let urlString = extractUrl(argumentsJson: argumentsJson) else {
            return errorResult("url 不能为空")
        }
        guard isValidWebUrl(urlString) else {
            return errorResult("url 必须以 http:// 或 https:// 开头")
        }
        guard let url = URL(string: urlString) else {
            return errorResult("url 无法解析")
        }

        // 桥接异步 open 到同步工具回调
        let semaphore = DispatchSemaphore(value: 0)
        var opened = false
        DispatchQueue.main.async {
            opener0.open(url) { ok in
                opened = ok
                semaphore.signal()
            }
        }
        // 等待主线程完成。工具调用本就发生在回合内，阻塞一轮可接受。
        if semaphore.wait(timeout: .now() + .seconds(10)) == .timedOut {
            return errorResult("打开超时")
        }
        guard opened else {
            return errorResult("打开失败：系统拒绝打开该链接")
        }
        return encodeJSON(["ok": true, "opened": urlString])
    }

    // MARK: - 系统通知

    static let notifyName = "device_notify"

    /// 通知 ID 基数，对应 Android 的 `NOTIFY_ID_BASE = 20_000`。
    static let notifyIdBase = 20_000

    static func notifyDefinition() -> ToolDefinition {
        ToolDefinition(
            name: notifyName,
            description: "发送一条系统通知。参数 {title: string, body?: string}。",
            parametersJson: ##"{"type":"object","properties":{"title":{"type":"string"},"body":{"type":"string"}},"required":["title"]}"##,
            category: ToolCategory.general,
            toolsets: ["device"],
            available: true
        )
    }

    /// 从入参提取 title / body（**纯函数，可测**）。
    ///
    /// 对应 Android：title 必须非空白（`takeIf { it.isNotBlank() }`），
    /// body 可选、缺省空串。返回的 title 已 trim。
    static func extractNotifyParams(argumentsJson: String) -> (title: String, body: String)? {
        guard let data = argumentsJson.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }

        guard let rawTitle = obj["title"] as? String else { return nil }
        let title = rawTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty else { return nil }

        let body = (obj["body"] as? String) ?? ""
        return (title, body)
    }

    /// 由 title 派生一个稳定的数字 ID（对应 Android 的
    /// `NOTIFY_ID_BASE + (title.hashCode() and 0xFFFF)`）。
    ///
    /// ⚠️ 无法与 Android 逐字节一致：Kotlin 的 `String.hashCode` 是 JVM 规范
    /// （`s[0]*31^(n-1) + ...`），与 Swift 的 `Hasher` 不同。
    /// 通知 ID 是**本地**概念，不跨端共享，因此只保证「同一 title 得到同一 ID」
    /// （用于替换同一标题的旧通知），不保证数值与 Android 相同。
    static func notifyIdentifier(title: String) -> String {
        "\(notifyIdBase + (abs(title.hashValue) % 0x10000))"
    }

    /// 执行 `device_notify`。
    ///
    /// ## ⚠️ 异步桥接与权限
    /// iOS 发通知前需 `requestAuthorization`，且 `add` 也是异步 ——
    /// 做法同 `executeOpenUrl`：信号量 + 主队列，**同样的死锁假设需实测**。
    ///
    /// 未授权时 Android 的文案是「若未授予通知权限，请先在系统设置中允许」——
    /// 照搬，因为模型会把这句话转述给用户。
    static func executeNotify(argumentsJson: String) -> String {
        guard let params = extractNotifyParams(argumentsJson: argumentsJson) else {
            return errorResult("title 不能为空")
        }

        let center = UNUserNotificationCenter.current()
        let semaphore = DispatchSemaphore(value: 0)
        var failure: String?

        DispatchQueue.main.async {
            center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
                guard granted else {
                    failure = "通知发送失败：若未授予通知权限，请先在系统设置中允许"
                    semaphore.signal()
                    return
                }
                let content = UNMutableNotificationContent()
                content.title = params.title
                content.body = params.body
                content.sound = .default

                let request = UNNotificationRequest(
                    identifier: notifyIdentifier(title: params.title),
                    content: content,
                    trigger: nil
                )
                center.add(request) { error in
                    if error != nil {
                        failure = "通知发送失败：若未授予通知权限，请先在系统设置中允许"
                    }
                    semaphore.signal()
                }
            }
        }

        if semaphore.wait(timeout: .now() + .seconds(10)) == .timedOut {
            return errorResult("通知发送超时")
        }
        if let failure {
            return errorResult(failure)
        }
        return encodeJSON(["ok": true, "notified": true])
    }

    // MARK: - 装配

    static func install(host: AgentToolHostImpl) {
        host.register(getTimeName) { argsJson, _ in
            executeGetTime(argumentsJson: argsJson)
        }
        host.register(batteryStatusName) { argsJson, _ in
            executeBatteryStatus(argumentsJson: argsJson)
        }
        host.register(getClipboardName) { argsJson, _ in
            executeGetClipboard(argumentsJson: argsJson)
        }
        host.register(setClipboardName) { argsJson, _ in
            executeSetClipboard(argumentsJson: argsJson)
        }
        host.register(openUrlName) { argsJson, _ in
            executeOpenUrl(argumentsJson: argsJson)
        }
        host.register(notifyName) { argsJson, _ in
            executeNotify(argumentsJson: argsJson)
        }
    }

    static func allDefinitions() -> [ToolDefinition] {
        [getTimeDefinition(), batteryStatusDefinition(),
         getClipboardDefinition(), setClipboardDefinition(),
         openUrlDefinition(), notifyDefinition()]
    }
}
