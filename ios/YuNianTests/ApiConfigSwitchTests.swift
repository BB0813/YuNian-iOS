import XCTest

/// 多渠道的列出 / 切换 / 删除。
///
/// 关键语义：**同一时刻只有一条启用**（单活）。
/// Rust 侧读 `WHERE isEnabled = 1 ORDER BY id DESC LIMIT 1`，
/// 多条启用会让它取 id 最大的那条 —— 与 UI 显示的不一定一致。
@testable import YuNian
final class ApiConfigSwitchTests: XCTestCase {

    private var db: YuNianDatabase!
    private var repo: ApiConfigRepository!
    private var tmpDir: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        tmpDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("apicfg_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        db = try YuNianDatabase(databaseURL: tmpDir.appendingPathComponent("yunian_database"))
        repo = ApiConfigRepository(database: db)
    }

    override func tearDownWithError() throws {
        if let d = tmpDir { try? FileManager.default.removeItem(at: d) }
        db = nil; repo = nil
        try super.tearDownWithError()
    }

    // MARK: - 列出

    /// 配过两家就能列出两家。
    func testAllConfigsListsEveryProvider() throws {
        _ = try repo.upsertActiveConfig(provider: "OPENAI", model: "gpt-4o",
                                        baseUrl: "https://api.openai.com/v1", name: "OpenAI")
        _ = try repo.upsertActiveConfig(provider: "DASHSCOPE", model: "qwen-plus",
                                        baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1",
                                        name: "通义千问")
        let all = try repo.allConfigs()
        XCTAssertEqual(all.count, 2)
        XCTAssertEqual(all.map(\.provider), ["OPENAI", "DASHSCOPE"],
                       "按 id 升序 = 创建顺序")
    }

    /// 空库时返回空数组，不崩。
    func testAllConfigsEmptyWhenNothingSaved() throws {
        XCTAssertTrue(try repo.allConfigs().isEmpty)
    }

    // MARK: - 单活

    /// 保存第二家后，第一家应该被停用（upsertActiveConfig 的既有语义）。
    func testSavingSecondProviderDisablesFirst() throws {
        _ = try repo.upsertActiveConfig(provider: "OPENAI", model: "gpt-4o",
                                        baseUrl: "https://api.openai.com/v1")
        _ = try repo.upsertActiveConfig(provider: "DASHSCOPE", model: "qwen-plus",
                                        baseUrl: "https://dashscope.aliyuncs.com/v1")
        let all = try repo.allConfigs()
        XCTAssertEqual(all.filter { $0.isEnabled }.count, 1, "必须只有一条启用")
        XCTAssertEqual(try repo.activeConfig()?.provider, "DASHSCOPE")
    }

    /// ⚠️ **核心**：activate 之后必须仍然只有一条启用。
    /// 若这里返回 2，Rust 会取 id 最大的那条，与 UI 显示的不一致。
    func testActivateKeepsExactlyOneEnabled() throws {
        let a = try repo.upsertActiveConfig(provider: "OPENAI", model: "gpt-4o",
                                            baseUrl: "https://api.openai.com/v1")
        let b = try repo.upsertActiveConfig(provider: "DASHSCOPE", model: "qwen-plus",
                                            baseUrl: "https://dashscope.aliyuncs.com/v1")
        _ = a; _ = b

        // 切回第一家
        let first = try repo.allConfigs().first { $0.provider == "OPENAI" }!
        XCTAssertTrue(try repo.activate(id: first.id))

        let all = try repo.allConfigs()
        XCTAssertEqual(all.filter { $0.isEnabled }.count, 1,
                       "activate 后仍须只有一条启用")
        XCTAssertEqual(try repo.activeConfig()?.provider, "OPENAI",
                       "切回后 activeConfig 应该跟着变")
        XCTAssertEqual(try repo.activeConfig()?.model, "gpt-4o",
                       "切回后模型名也该是原来那家的")
    }

    /// activate 一个不存在的 id → false，且**不会**把已启用的也停掉。
    func testActivateUnknownIdLeavesCurrentIntact() throws {
        _ = try repo.upsertActiveConfig(provider: "OPENAI", model: "gpt-4o",
                                        baseUrl: "https://api.openai.com/v1")
        XCTAssertFalse(try repo.activate(id: 9999))
        XCTAssertEqual(try repo.activeConfig()?.provider, "OPENAI",
                       "激活失败的 id 不该影响当前启用态")
    }

    // MARK: - 删除

    /// 删掉**非启用**那条，启用态不受影响。
    ///
    /// ⚠️ 第 184 轮修正：我第一版这个测试名字说"删非启用"，
    /// 实际删的是 `second`（DASHSCOPE，最后保存所以是启用的）——
    /// 于是 `activeConfig()` 返回 nil，断言失败。
    /// **测试自己写错了**，与实现无关（那一版的实现确实有 bug，
    /// 但由另一个测试 `testActivateUnknownIdLeavesCurrentIntact` 抓到）。
    ///
    /// 现在明确删 OPENAI（保存 DASHSCOPE 后被停用的那条）。
    func testDeleteNonActiveKeepsActive() throws {
        let openai = try repo.upsertActiveConfig(provider: "OPENAI", model: "gpt-4o",
                                                 baseUrl: "https://api.openai.com/v1")
        _ = try repo.upsertActiveConfig(provider: "DASHSCOPE", model: "qwen-plus",
                                        baseUrl: "https://dashscope.aliyuncs.com/v1")
        // openai 这时已被停用（DASHSCOPE 是后保存的）
        XCTAssertFalse(try repo.allConfigs().first { $0.id == openai }!.isEnabled)

        XCTAssertTrue(try repo.delete(id: openai))
        XCTAssertEqual(try repo.allConfigs().count, 1)
        XCTAssertEqual(try repo.activeConfig()?.provider, "DASHSCOPE",
                       "删非启用那条，启用态不该被动")
    }

    /// ⚠️ 删掉**启用中**那条：剩下那条**不自动顶上**。
    /// 顶上来的话，用户没点过它就换了渠道，请求会悄悄打到别家去。
    func testDeleteActiveDoesNotAutoPromote() throws {
        _ = try repo.upsertActiveConfig(provider: "OPENAI", model: "gpt-4o",
                                        baseUrl: "https://api.openai.com/v1")
        let second = try repo.upsertActiveConfig(provider: "DASHSCOPE", model: "qwen-plus",
                                                 baseUrl: "https://dashscope.aliyuncs.com/v1")
        // second 是启用的；删它
        XCTAssertTrue(try repo.delete(id: second))
        XCTAssertEqual(try repo.allConfigs().count, 1, "行还在")
        XCTAssertNil(try repo.activeConfig(),
                     "启用中的被删后，剩下的不该自动顶上")
    }

    /// 删不存在的 id → false。
    func testDeleteUnknownIdReturnsFalse() throws {
        XCTAssertFalse(try repo.delete(id: 4242))
    }
}
