import XCTest
@testable import YuNian

/// `DeviceTools` 测试（M4 批次2）。
///
/// 重点锁 `weekdayName` —— 这是 `device_get_time` 里唯一有真实风险的部分：
/// `Calendar.weekday`（1=周日）与 Kotlin `DayOfWeek`（1=周一）**差 1**，
/// 映射错不报错，只是星期全错一位。
final class DeviceToolsTests: XCTestCase {

    // MARK: - 星期映射（差一陷阱）

    /// 期望值由 Python 从 Kotlin `DayOfWeek` 语义算出后交叉核对：
    /// DayOfWeek 1=Mon..7=Sun；Calendar.weekday 1=Sun..7=Sat。
    ///
    /// 覆盖全部 7 个值 + nil + 越界值（8 / 0）。
    func testWeekdayNameCoversAllValues() {
        XCTAssertEqual(DeviceTools.weekdayName(calendarWeekday: 2), "星期一")
        XCTAssertEqual(DeviceTools.weekdayName(calendarWeekday: 3), "星期二")
        XCTAssertEqual(DeviceTools.weekdayName(calendarWeekday: 4), "星期三")
        XCTAssertEqual(DeviceTools.weekdayName(calendarWeekday: 5), "星期四")
        XCTAssertEqual(DeviceTools.weekdayName(calendarWeekday: 6), "星期五")
        XCTAssertEqual(DeviceTools.weekdayName(calendarWeekday: 7), "星期六")
        XCTAssertEqual(DeviceTools.weekdayName(calendarWeekday: 1), "星期日",
                       "Calendar.weekday=1 是周日 —— 最易错的一个")

        // 未知值兜底为星期日（与 Android 的 `else -> "星期日"` 一致）
        XCTAssertEqual(DeviceTools.weekdayName(calendarWeekday: nil), "星期日")
        XCTAssertEqual(DeviceTools.weekdayName(calendarWeekday: 0), "星期日")
        XCTAssertEqual(DeviceTools.weekdayName(calendarWeekday: 8), "星期日")
    }

    /// 7 个值必须两两不同（防止多处落到同一分支）。
    func testWeekdayNameIsExhaustiveAndDistinct() {
        let mapped = (1...7).map { DeviceTools.weekdayName(calendarWeekday: $0) }
        XCTAssertEqual(Set(mapped).count, 7, "7 个 weekday 值应映射到 7 个不同星期名")
        XCTAssertTrue(mapped.allSatisfy { $0.hasSuffix("星期") && $0.count == 3 })
    }

    // MARK: - 工具契约

    /// 工具名 / 描述 / JSON Schema 必须与 Android 逐字一致（模型侧契约）。
    func testGetTimeDefinitionMatchesAndroid() {
        let def = DeviceTools.getTimeDefinition()
        XCTAssertEqual(def.name, "device_get_time")
        XCTAssertEqual(def.description, "获取当前日期、时间和星期（无参数）。")
        XCTAssertEqual(def.parametersJson, #"{"type":"object","properties":{}}"#)
        XCTAssertEqual(def.toolsets, ["device"])
        XCTAssertTrue(def.available)
    }

    /// 返回 JSON 的键必须与 Android `okResult("datetime" to ..., "weekday" to ...)` 一致。
    ///
    /// ⚠️ 用**固定时区的 Calendar** 注入 —— 否则断言会依赖运行机器的 TZ
    /// （我第一版测试就栽在这：日期按 Asia/Shanghai 构造，实现却用 .current）。
    func testExecuteGetTimeProducesAndroidShape() throws {
        // ⚠️ 第 91 轮：`Calendar` 是 struct，`let cal` 之后不能再改 timeZone。
        // CI 报 DeviceToolsTests.swift:58 "cannot assign to property: 'cal' is a 'let' constant"。
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Shanghai")!

        // 2026-08-07 12:34:56 +08:00 → Python 核算：2026-08-07 是星期五
        var comps = DateComponents()
        comps.year = 2026; comps.month = 8; comps.day = 7
        comps.hour = 12; comps.minute = 34; comps.second = 56
        comps.timeZone = cal.timeZone
        let fixed = try XCTUnwrap(Calendar(identifier: .gregorian).date(from: comps))

        let json = try object(
            DeviceTools.executeGetTime(argumentsJson: "{}", now: fixed, calendar: cal))

        XCTAssertEqual(json["ok"] as? Bool, true)
        XCTAssertEqual(json["datetime"] as? String, "2026-08-07 12:34:56")
        XCTAssertEqual(json["weekday"] as? String, "星期五")
    }

    // MARK: - 电量

    /// Android 是整数除法 `level * 100 / scale`（向下取整）；iOS 的 batteryLevel 是
    /// 0.0–1.0 浮点。我在实现里选了 `rounded()` 而非截断 —— 这是**有意的偏离**，
    /// 因此必须用测试把它钉住，并说明理由（0.999 → 100 而非 99）。
    func testBatteryPercentUsesRounding() {
        XCTAssertEqual(DeviceTools.percent(fromBatteryLevel: 0.0), 0)
        XCTAssertEqual(DeviceTools.percent(fromBatteryLevel: 0.005), 1)     // 0.5 → 1
        XCTAssertEqual(DeviceTools.percent(fromBatteryLevel: 0.999), 100)   // 截断会得 99
        XCTAssertEqual(DeviceTools.percent(fromBatteryLevel: 0.5), 50)
        XCTAssertEqual(DeviceTools.percent(fromBatteryLevel: 1.0), 100)
        // 越界值由调用方的 level >= 0 守卫处理；这里只验证纯数学
        XCTAssertEqual(DeviceTools.percent(fromBatteryLevel: 1.0), 100)
    }

    /// 充电判定：`.charging` 与 `.full` 都算充电中（对应 Android 的两个状态）。
    func testIsChargingCoversAllBatteryStates() {
        XCTAssertTrue(DeviceTools.isCharging(batteryState: .charging))
        XCTAssertTrue(DeviceTools.isCharging(batteryState: .full))
        XCTAssertFalse(DeviceTools.isCharging(batteryState: .unplugged))
        XCTAssertFalse(DeviceTools.isCharging(batteryState: .unknown))
    }

    /// 电量工具契约与 Android 逐字一致。
    func testBatteryStatusDefinitionMatchesAndroid() {
        let def = DeviceTools.batteryStatusDefinition()
        XCTAssertEqual(def.name, "device_battery_status")
        XCTAssertEqual(def.description, "查询手机电量与充电状态（无参数）。")
        XCTAssertEqual(def.parametersJson, #"{"type":"object","properties":{}}"#)
        XCTAssertEqual(def.toolsets, ["device"])
    }

    // MARK: - 打开网页

    /// scheme 白名单：只认 http/https（对应 Android 的 startsWith 双重判断）。
    /// 拒绝 `file://` / `javascript:` 是安全边界。
    func testIsValidWebUrlOnlyAllowsHttpAndHttps() {
        XCTAssertTrue(DeviceTools.isValidWebUrl("http://example.com"))
        XCTAssertTrue(DeviceTools.isValidWebUrl("https://example.com/a?b=1"))
        XCTAssertTrue(DeviceTools.isValidWebUrl("  https://x.com  "), "两端空白应被容忍")

        XCTAssertFalse(DeviceTools.isValidWebUrl("ftp://example.com"))
        XCTAssertFalse(DeviceTools.isValidWebUrl("file:///etc/passwd"))
        XCTAssertFalse(DeviceTools.isValidWebUrl("javascript:alert(1)"))
        XCTAssertFalse(DeviceTools.isValidWebUrl("example.com"), "缺 scheme 应拒绝")
        XCTAssertFalse(DeviceTools.isValidWebUrl("HTTPS://x.com"), "大小写敏感（与 Kotlin startsWith 一致）")
        XCTAssertFalse(DeviceTools.isValidWebUrl(""))
    }

    /// 参数提取：缺失 / 空 / 非字符串都返回 nil。
    func testExtractUrlHandlesMissingAndBlank() {
        XCTAssertEqual(DeviceTools.extractUrl(argumentsJson: ##"{"url":"https://a.com"}"##), "https://a.com")
        XCTAssertEqual(DeviceTools.extractUrl(argumentsJson: ##"{"url":"  https://a.com  "}"##), "https://a.com")
        XCTAssertNil(DeviceTools.extractUrl(argumentsJson: ##"{}"##))
        XCTAssertNil(DeviceTools.extractUrl(argumentsJson: ##"{"url":""}"##))
        XCTAssertNil(DeviceTools.extractUrl(argumentsJson: ##"{"url":123}"##))
        XCTAssertNil(DeviceTools.extractUrl(argumentsJson: "not json"))
    }

    /// 打开网页工具契约与 Android 逐字一致。
    func testOpenUrlDefinitionMatchesAndroid() {
        let def = DeviceTools.openUrlDefinition()
        XCTAssertEqual(def.name, "device_open_url")
        XCTAssertEqual(def.description, "用系统浏览器打开一个网页链接。参数 {url: string}。")
        XCTAssertEqual(def.toolsets, ["device"])
        XCTAssertTrue(def.parametersJson.contains(#""required":["url"]"#))
        XCTAssertTrue(def.parametersJson.contains("http/https"))
    }

    // MARK: - 系统通知

    /// title 必须非空白；body 可选、缺省空串。对应 Android 的
    /// `takeIf { it.isNotBlank() }` 与 `orEmpty()`。
    func testExtractNotifyParams() {
        XCTAssertEqual(
            DeviceTools.extractNotifyParams(argumentsJson: ##"{"title":"到点了","body":"该开会了"}"##)?.title,
            "到点了")
        XCTAssertEqual(
            DeviceTools.extractNotifyParams(argumentsJson: ##"{"title":"到点了","body":"该开会了"}"##)?.body,
            "该开会了")
        // 无 body → 空串（不是 nil）
        XCTAssertEqual(
            DeviceTools.extractNotifyParams(argumentsJson: ##"{"title":"只有标题"}"##)?.body, "")
        // title 两端空白应被 trim 后接受
        XCTAssertEqual(
            DeviceTools.extractNotifyParams(argumentsJson: ##"{"title":"  提醒  "}"##)?.title, "提醒")
        // 各类非法入参
        XCTAssertNil(DeviceTools.extractNotifyParams(argumentsJson: ##"{}"##))
        XCTAssertNil(DeviceTools.extractNotifyParams(argumentsJson: ##"{"title":""}"##))
        XCTAssertNil(DeviceTools.extractNotifyParams(argumentsJson: ##"{"title":"   "}"##))
        XCTAssertNil(DeviceTools.extractNotifyParams(argumentsJson: ##"{"title":123}"##))
        XCTAssertNil(DeviceTools.extractNotifyParams(argumentsJson: "not json"))
    }

    /// 同一 title 必须得到同一 ID（用于替换旧通知），不同 title 应不同。
    func testNotifyIdentifierIsStablePerTitle() {
        XCTAssertEqual(DeviceTools.notifyIdentifier(title: "到点了"),
                       DeviceTools.notifyIdentifier(title: "到点了"))
        XCTAssertNotEqual(DeviceTools.notifyIdentifier(title: "到点了"),
                          DeviceTools.notifyIdentifier(title: "别的"))
        // 必须是纯数字字符串（Android 侧是 Int）
        XCTAssertTrue(DeviceTools.notifyIdentifier(title: "x").allSatisfy(\.isNumber))
    }

    /// 通知工具契约与 Android 逐字一致。
    func testNotifyDefinitionMatchesAndroid() {
        let def = DeviceTools.notifyDefinition()
        XCTAssertEqual(def.name, "device_notify")
        XCTAssertEqual(def.description, "发送一条系统通知。参数 {title: string, body?: string}。")
        XCTAssertEqual(def.parametersJson, ##"{"type":"object","properties":{"title":{"type":"string"},"body":{"type":"string"}},"required":["title"]}"##)
    }

    // MARK: - 汇总

    /// 已装配的工具必须覆盖批次2 的全部可移植项。
    func testAllDefinitionsCoverBatchTwo() {
        let names = DeviceTools.allDefinitions().map(\.name)
        XCTAssertEqual(names, [
            "device_get_time", "device_battery_status",
            "device_get_clipboard", "device_set_clipboard",
            "device_open_url", "device_notify",
        ])
        XCTAssertEqual(Set(names).count, names.count, "工具名重复")
    }

    // MARK: - 辅助

    /// 在**固定时区**下构造 Date，避免测试因运行机器的 TZ 不同而失败。
    private func fixedDate(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int) throws -> Date {
        var comps = DateComponents()
        comps.year = year; comps.month = month; comps.day = day
        comps.hour = hour; comps.minute = minute; comps.second = second
        comps.timeZone = TimeZone(identifier: "Asia/Shanghai")   // +08:00，固定
        guard let date = Calendar(identifier: .gregorian).date(from: comps) else {
            XCTFail("无法构造固定日期"); throw NSError(domain: "test", code: 1)
        }
        return date
    }

    private func object(_ json: String) throws -> [String: Any] {
        guard let data = json.data(using: .utf8),
              let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { XCTFail("不是 JSON 对象：\(json)"); return [:] }
        return obj
    }
}
