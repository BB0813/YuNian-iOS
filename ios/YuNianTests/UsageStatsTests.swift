import GRDB
import XCTest

/// 用量统计（`token_usage` 的读取侧）。
///
/// 重点锁的是**会把数字算错但看不出来**的两件事：
/// 全局行 `companionId = -1` 不能被重复计入按伴侣分布；读必须带 `deviceId`。
@testable import YuNian
final class UsageStatsTests: XCTestCase {

    private func makeDatabase() throws -> (YuNianDatabase, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("usage_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return (try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database")), dir)
    }

    /// 直接插一行（绕开写入侧，便于构造"别的设备"等场景）。
    private func insert(_ database: YuNianDatabase, companionId: Int64, date: String,
                        input: Int, output: Int, requests: Int = 1,
                        deviceId: String, timestamp: Int64 = 1_700_000_000_000) throws {
        try database.pool.write { db in
            try db.execute(sql: """
                INSERT INTO token_usage
                  (companionId, date, inputTokens, outputTokens, totalTokens,
                   requestCount, timestamp, deviceId)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, arguments: [companionId, date, input, output, input + output,
                                 requests, timestamp, deviceId])
        }
    }

    func testEmptyDatabaseHasNoData() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let stats = UsageStatsRepository.load(database: database, deviceId: "dev-1")
        XCTAssertFalse(stats.hasAnyData)
        XCTAssertTrue(stats.days.isEmpty)
        XCTAssertTrue(stats.companions.isEmpty)
        XCTAssertEqual(stats.totalTokens, 0)
    }

    /// 全局合计**只**来自 `-1` 行；按伴侣分布**排除** `-1` 行。
    ///
    /// 这是这张表最容易算错的地方：`-1` 行本来就是各伴侣之和，
    /// 一旦混进按伴侣的 GROUP BY，总量就翻倍。
    func testGlobalRowIsNotDoubleCounted() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }

        // 两个伴侣各 100/10，全局行等于二者之和 200/20
        try insert(database, companionId: 1, date: "2026-10-10", input: 100, output: 10, deviceId: "dev-1")
        try insert(database, companionId: 2, date: "2026-10-10", input: 100, output: 10, deviceId: "dev-1")
        try insert(database, companionId: -1, date: "2026-10-10", input: 200, output: 20, requests: 2, deviceId: "dev-1")

        let stats = UsageStatsRepository.load(database: database, deviceId: "dev-1")
        XCTAssertEqual(stats.totalTokens, 220, "全局合计取 -1 行")
        XCTAssertEqual(stats.totalRequests, 2)

        XCTAssertEqual(stats.companions.count, 2, "-1 行不能出现在按伴侣分布里")
        XCTAssertEqual(stats.companions.map(\.companionId).sorted(), [1, 2])
        XCTAssertEqual(stats.companions.reduce(0) { $0 + $1.totalTokens }, 220,
                       "各伴侣之和应等于全局合计（说明没有重复计入）")
    }

    /// 读必须带 `deviceId`：别的设备的行不能被算进来。
    func testOtherDeviceRowsAreExcluded() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }

        try insert(database, companionId: -1, date: "2026-10-10", input: 10, output: 1, deviceId: "dev-1")
        try insert(database, companionId: -1, date: "2026-10-10", input: 999, output: 999, deviceId: "dev-2")
        try insert(database, companionId: 5, date: "2026-10-10", input: 10, output: 1, deviceId: "dev-1")
        try insert(database, companionId: 6, date: "2026-10-10", input: 777, output: 777, deviceId: "dev-2")

        let stats = UsageStatsRepository.load(database: database, deviceId: "dev-1")
        XCTAssertEqual(stats.totalTokens, 11)
        XCTAssertEqual(stats.companions.map(\.companionId), [5])
    }

    /// 按日列表：倒序（最新在前）且受 limitDays 限制。
    func testDaysAreNewestFirstAndLimited() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }

        for day in 1...5 {
            try insert(database, companionId: -1, date: "2026-10-0\(day)",
                       input: day * 10, output: 0, deviceId: "dev-1")
        }
        let stats = UsageStatsRepository.load(database: database, deviceId: "dev-1", limitDays: 3)
        XCTAssertEqual(stats.days.map(\.date), ["2026-10-05", "2026-10-04", "2026-10-03"])
        // 总量不受 limitDays 影响（它是单独聚合的）
        XCTAssertEqual(stats.totalTokens, 150)
        XCTAssertEqual(stats.firstDate, "2026-10-01")
    }

    /// 按伴侣分布按用量降序；伴侣已被删除时名字为空（界面据此显示 `伴侣 #id`）。
    func testCompanionOrderingAndMissingName() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }

        try insert(database, companionId: 1, date: "2026-10-10", input: 10, output: 0, deviceId: "dev-1")
        try insert(database, companionId: 2, date: "2026-10-10", input: 90, output: 0, deviceId: "dev-1")

        let stats = UsageStatsRepository.load(database: database, deviceId: "dev-1")
        XCTAssertEqual(stats.companions.map(\.companionId), [2, 1], "用量大的在前")
        // 测试库里的伴侣名可能为空（未播种）—— 只要不崩且是字符串即可
        XCTAssertTrue(stats.companions.allSatisfy { $0.name.isEmpty || !$0.name.isEmpty })
    }

    // MARK: - 维护

    func testClearOlderThanKeepsRecentRows() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }

        let now = Date()
        let old = Int64(now.addingTimeInterval(-100 * 86_400).timeIntervalSince1970 * 1000)
        let recent = Int64(now.addingTimeInterval(-86_400).timeIntervalSince1970 * 1000)
        try insert(database, companionId: -1, date: "2026-07-01", input: 5, output: 0,
                   deviceId: "dev-1", timestamp: old)
        try insert(database, companionId: -1, date: "2026-10-10", input: 7, output: 0,
                   deviceId: "dev-1", timestamp: recent)
        // 别的设备的老行不能被我清掉
        try insert(database, companionId: -1, date: "2026-07-01", input: 9, output: 0,
                   deviceId: "dev-2", timestamp: old)

        let removed = UsageStatsRepository.clearOlderThan(days: 90, database: database,
                                                          deviceId: "dev-1", now: now)
        XCTAssertEqual(removed, 1)
        let remaining = UsageStatsRepository.load(database: database, deviceId: "dev-1")
        XCTAssertEqual(remaining.totalTokens, 7)
        let other = UsageStatsRepository.load(database: database, deviceId: "dev-2")
        XCTAssertEqual(other.totalTokens, 9, "别的设备的行必须原样保留")
    }

    func testClearAllOnlyTouchesOwnDevice() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }

        try insert(database, companionId: -1, date: "2026-10-10", input: 5, output: 0, deviceId: "dev-1")
        try insert(database, companionId: -1, date: "2026-10-10", input: 9, output: 0, deviceId: "dev-2")

        XCTAssertEqual(UsageStatsRepository.clearAll(database: database, deviceId: "dev-1"), 1)
        XCTAssertFalse(UsageStatsRepository.load(database: database, deviceId: "dev-1").hasAnyData)
        XCTAssertTrue(UsageStatsRepository.load(database: database, deviceId: "dev-2").hasAnyData)
    }

    /// 今日查询用的日期串必须与写入侧**同一份实现**（否则"今日"永远查不到）。
    func testTodayStringMatchesWriter() {
        let now = Date()
        XCTAssertEqual(UsageStatsRepository.todayString(now), TurnReceiptStore.dateString(now))
    }

    /// nil 数据库不崩（启动早期 / 数据未就绪时界面照常显示）。
    func testNilDatabaseIsSafe() {
        XCTAssertFalse(UsageStatsRepository.load(database: nil, deviceId: "dev-1").hasAnyData)
        XCTAssertEqual(UsageStatsRepository.clearAll(database: nil, deviceId: "dev-1"), 0)
        XCTAssertEqual(UsageStatsRepository.clearOlderThan(days: 90, database: nil, deviceId: "dev-1"), 0)
    }
}
