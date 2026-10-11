import XCTest

/// 工具可见性与写入确认（P0 ③ 的后两维）。
///
/// 两件事必须被测试锁住，否则它们会悄悄失效：
/// 1. **可见性**：关掉的工具集必须真的从下发列表里消失（不是 `available=false`）
/// 2. **写入确认**：需要确认的工具必须以 `ToolCategory.commerce` 下发 ——
///    Rust 的审批门只认这个类别（agent.rs:1352）。类别填错 = 确认卡永远不弹，
///    而界面上开关看起来"开着"，这是最难查的一类假生效。
@testable import YuNian
final class ToolGateTests: XCTestCase {

    private func tool(_ name: String, toolset: String,
                      category: ToolCategory = .memory) -> ToolDefinition {
        ToolDefinition(
            name: name,
            description: "测试用",
            parametersJson: #"{"type":"object"}"#,
            category: category,
            toolsets: [toolset],
            available: true
        )
    }

    // MARK: - 可见性

    func testHiddenToolsetDisappearsEntirely() {
        let tools = [
            tool("recall_memory", toolset: "memory"),
            tool("device_get_time", toolset: "device"),
        ]
        var settings = ToolSettings()
        settings.hiddenToolsets = ["device"]

        let visible = AgentToolCatalog.gated(tools, settings: settings)
        XCTAssertEqual(visible.map(\.name), ["recall_memory"],
                       "被隐藏工具集里的工具必须**整个消失**，而不是标记成不可用")
    }

    /// 默认（用户没配过）→ 全部可见，与改动前行为一致。
    func testDefaultSettingsShowEverything() {
        let tools = [
            tool("recall_memory", toolset: "memory"),
            tool("device_get_time", toolset: "device"),
        ]
        XCTAssertEqual(AgentToolCatalog.gated(tools, settings: ToolSettings()).count, 2)
    }

    /// 隐藏两个工具集时两个都要消失（不是只命中第一个）。
    func testMultipleHiddenToolsets() {
        let tools = [
            tool("recall_memory", toolset: "memory"),
            tool("device_get_time", toolset: "device"),
            tool("load_skill", toolset: "skill"),
        ]
        var settings = ToolSettings()
        settings.hiddenToolsets = ["memory", "device"]

        let visible = AgentToolCatalog.gated(tools, settings: settings)
        XCTAssertEqual(visible.map(\.name), ["load_skill"])
    }

    // MARK: - 写入确认

    func testConfirmToolGetsCommerceCategory() {
        let tools = [
            tool("save_memory", toolset: "memory"),
            tool("recall_memory", toolset: "memory"),
        ]
        var settings = ToolSettings()
        settings.confirmTools = ["save_memory"]

        let gated = AgentToolCatalog.gated(tools, settings: settings)
        let save = gated.first { $0.name == "save_memory" }
        let recall = gated.first { $0.name == "recall_memory" }

        XCTAssertEqual(save?.category, .commerce,
                       "需要确认的工具必须以 Commerce 下发 —— Rust 的审批门只认它")
        XCTAssertEqual(recall?.category, .memory, "只读工具不该被改类别")
    }

    /// 关掉确认（= 授予长期自主权）→ 类别回到原样，审批门不再拦。
    func testConfirmToolKeepsOriginalCategoryWhenGranted() {
        let tools = [tool("save_memory", toolset: "memory")]
        var settings = ToolSettings()
        settings.confirmTools = []

        XCTAssertEqual(AgentToolCatalog.gated(tools, settings: settings).first?.category, .memory)
    }

    /// 改类别时**其余字段必须原样保留** —— 参数 schema 丢了模型就调不对。
    func testGatingPreservesOtherFields() {
        let original = ToolDefinition(
            name: "device_open_url",
            description: "打开链接",
            parametersJson: #"{"type":"object","properties":{"url":{"type":"string"}}}"#,
            category: .chat,
            toolsets: ["device"],
            available: true
        )
        var settings = ToolSettings()
        settings.confirmTools = ["device_open_url"]

        let gated = AgentToolCatalog.gated([original], settings: settings).first
        XCTAssertEqual(gated?.description, original.description)
        XCTAssertEqual(gated?.parametersJson, original.parametersJson)
        XCTAssertEqual(gated?.toolsets, original.toolsets)
        XCTAssertEqual(gated?.available, original.available)
    }

    // MARK: - 分类完备性（新增工具时必须归类）

    /// ⚠️ 每一个**设备工具**都必须被归类：要么只读，要么需要确认。
    ///
    /// 这条锁的是"新增工具忘了归类"—— 那会让写操作**默认不确认**，
    /// 而界面上看不出任何异常（这一节只会少一项开关）。
    func testEveryDeviceToolIsClassified() {
        for definition in DeviceTools.allDefinitions() {
            let classified = ToolSettings.readOnlyTools.contains(definition.name)
                || ToolSettings.defaultConfirmTools.contains(definition.name)
            XCTAssertTrue(classified,
                          "设备工具 \(definition.name) 既不在只读表也不在确认表里 —— 新增工具时要归类")
        }
    }

    /// 两张表不能重叠（重叠时"只读"会被确认门拦住，用户会看到莫名其妙的卡）。
    func testReadOnlyAndConfirmTablesAreDisjoint() {
        let overlap = ToolSettings.readOnlyTools
            .intersection(ToolSettings.defaultConfirmTools)
        XCTAssertTrue(overlap.isEmpty, "重叠项：\(overlap.sorted())")
    }

    /// 默认要确认的写操作**至少包含**这几个真实存在的名字 ——
    /// 工具改名后这里会立刻红（硬编码名字必须有去处）。
    func testDefaultConfirmToolsCoverKnownWrites() {
        for name in ["save_memory", "consolidate_memory",
                     DeviceTools.setClipboardName,
                     DeviceTools.openUrlName,
                     DeviceTools.notifyName] {
            XCTAssertTrue(ToolSettings.defaultConfirmTools.contains(name), "缺少 \(name)")
        }
    }

    // MARK: - 持久化

    func testSettingsRoundTripThroughAppMeta() throws {
        let tmpDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("toolgate_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let database = try YuNianDatabase(databaseURL: tmpDir.appendingPathComponent("yunian_database"))
        let repo = ApiConfigRepository(database: database)

        // 没配过 → 默认值（全部可见 + 写操作都要确认）
        let initial = ToolSettings.load(from: repo)
        XCTAssertTrue(initial.hiddenToolsets.isEmpty)
        XCTAssertEqual(initial.confirmTools, ToolSettings.defaultConfirmTools)

        var saved = ToolSettings()
        saved.hiddenToolsets = ["device", "memory"]
        saved.confirmTools = ["device_open_url"]
        try saved.save(to: repo)

        XCTAssertEqual(ToolSettings.load(from: repo), saved)
    }

    /// **空集也要能持久化**（"我明确把确认全关了" ≠ "没配过"）。
    ///
    /// 这条是 `setMetaValue` 语义的直接风险点：它对空串是**删键**，
    /// 所以空集必须编码成 `[]` 而不是空串，否则用户关掉全部确认后一重启
    /// 就回到默认（全部要确认）—— 而且看不出为什么。
    func testEmptySetPersistsAsEmptyArray() throws {
        let tmpDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("toolgate2_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let database = try YuNianDatabase(databaseURL: tmpDir.appendingPathComponent("yunian_database"))
        let repo = ApiConfigRepository(database: database)

        var settings = ToolSettings()
        settings.confirmTools = []
        settings.hiddenToolsets = []
        try settings.save(to: repo)

        XCTAssertEqual(try repo.metaValue(forKey: ToolSettings.confirmToolsKey), "[]")
        let reloaded = ToolSettings.load(from: repo)
        XCTAssertTrue(reloaded.confirmTools.isEmpty,
                      "空集必须读回空集 —— 否则用户关掉全部确认后一重启就'自己开回来'")
    }
}
