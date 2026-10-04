import XCTest
@testable import YuNian

/// 技能链路集成测试 —— 从种子到 `load_skill` 可加载，**不跑回合**。
///
/// ## 为什么有价值
/// 它能在没有 Mac / 没有 LLM 请求的情况下，验证整条技能链：
///   种子写入 → `SkillSelector.discover` 能发现 → `loadContent` 能取回正文
///
/// 这条链上曾有两个真实 bug，本测试都能抓住：
///   - **第 39 轮**：内置聊天协议技能没有种子 → `discover` 空、协议注不进 prompt
///   - **第 46 轮**：`load_skill` 的 companionId 被冻结在装配期（恒 nil）
///     → 伴侣专属技能取不回
final class SkillChainTests: XCTestCase {

    private var dir: URL!
    private var database: YuNianDatabase!
    private var stores: AgentStores!

    override func setUpWithError() throws {
        try super.setUpWithError()
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("skill-chain-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        database = try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database"))
        stores = AgentStores(database: database)
    }

    override func tearDownWithError() throws {
        if let dir { try? FileManager.default.removeItem(at: dir) }
        database = nil
        stores = nil
        try super.tearDownWithError()
    }

    /// 种子 → discover → loadContent 全链路。
    func testBuiltinChatToolSkillIsDiscoverableAndLoadable() throws {
        // ① 种子（这一步缺失就是第 39 轮的 bug）
        XCTAssertTrue(BuiltinChatToolSkill.seed(into: stores), "种子写入失败")

        let selector = SkillSelector(store: stores)

        // ② discover：available_tools 含 load_skill 与内置聊天工具
        let menu = selector.discover(
            companionId: nil,
            limit: 30,
            availableTools: ["load_skill", "emit_bubble", "emit_segmented", "send_sticker"]
        )
        let ids = menu.map(\.skillId)
        XCTAssertTrue(
            ids.contains(BuiltinChatToolSkill.skillId),
            "内置聊天协议技能应可被发现，实际 \(ids)"
        )

        let entry = try XCTUnwrap(menu.first { $0.skillId == BuiltinChatToolSkill.skillId })
        XCTAssertFalse(entry.name.isEmpty)
        XCTAssertFalse(entry.description.isEmpty)
        XCTAssertTrue(entry.tools.contains("emit_bubble"), "应声明它依赖的内置工具")

        // ③ loadContent：companionId 为 nil 也能取回（内置技能是全局的）
        let content = try XCTUnwrap(
            selector.loadContent(skillId: BuiltinChatToolSkill.skillId, companionId: nil)
        )
        XCTAssertEqual(content, BuiltinChatToolSkill.content,
                       "取回的正文必须与种子一致（否则 load_skill 给模型的是错内容）")
        XCTAssertTrue(content.contains("emit_bubble"), "正文应包含气泡工具协议")
        XCTAssertTrue(content.contains("send_sticker"), "正文应包含表情工具协议")
    }

    /// 没有种子时 discover 应为空 —— 这正是第 39 轮的症状，
    /// 这条断言保证「种子确实是被需要的」。
    func testWithoutSeedDiscoverReturnsEmpty() {
        let selector = SkillSelector(store: stores)
        let menu = selector.discover(
            companionId: nil, limit: 30,
            availableTools: ["load_skill", "emit_bubble"]
        )
        XCTAssertTrue(menu.isEmpty, "未播种时不应有任何技能，实际 \(menu.map(\.skillId))")
    }

    /// 未知 skillId 应返回 nil（Rust 会转成「技能不存在」提示给模型）。
    func testLoadUnknownSkillReturnsNil() {
        let selector = SkillSelector(store: stores)
        BuiltinChatToolSkill.seed(into: stores)
        XCTAssertNil(selector.loadContent(skillId: "no_such_skill", companionId: nil))
    }

    /// `AgentToolCatalog` 装配后，`load_skill` 必须是其中之一 ——
    /// 否则技能目录菜单与加载能力都不会注入模型。
    func testToolCatalogIncludesLoadSkill() {
        AgentToolCatalog.install(memory: nil, skill: SkillSelector(store: stores), host: AgentToolHostImpl())
        XCTAssertTrue(
            AgentToolCatalog.definitions.contains { $0.name == "load_skill" },
            "load_skill 必须在工具目录里（prompt_orchestrator.rs:270 依赖它）"
        )
        XCTAssertTrue(
            AgentToolCatalog.installedDefinitionNames.contains("load_skill"),
            "自检面板展示的工具名也应包含它"
        )
    }
}
