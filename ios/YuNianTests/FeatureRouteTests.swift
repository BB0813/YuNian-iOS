import XCTest

/// 用途线路（多线路 P0 ②）的语义。
///
/// 最要紧的一条是**不变式**：
/// > 存在启用行时，`app_meta.feature_route.chat` 必须指向它。
///
/// 因为引擎读前者、旧版引擎读后者 —— 两者不一致时，同一台机器上就有
/// 两个"当前渠道"：界面按线路显示 A，请求却打到 B。这类分歧不报错，只出错。
///
/// 第二条：**绑定非对话线路不得动到「当前启用渠道」** ——
/// 否则为了朗读配一条语音渠道，会把对话悄悄换到那家去。
@testable import YuNian
final class FeatureRouteTests: XCTestCase {

    private var db: YuNianDatabase!
    private var repo: ApiConfigRepository!
    private var tmpDir: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        tmpDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("featureroute_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        db = try YuNianDatabase(databaseURL: tmpDir.appendingPathComponent("yunian_database"))
        repo = ApiConfigRepository(database: db)
    }

    override func tearDownWithError() throws {
        if let tmpDir { try? FileManager.default.removeItem(at: tmpDir) }
        db = nil
        repo = nil
        try super.tearDownWithError()
    }

    // MARK: - 辅助

    /// 建一条渠道（`upsertActiveConfig` 会把它设为当前启用，并写下对话线路）。
    @discardableResult
    private func addConfig(_ provider: String, _ model: String) throws -> Int64 {
        try repo.upsertActiveConfig(
            provider: provider,
            model: model,
            baseUrl: "https://\(provider.lowercased()).example.com/v1"
        )
    }

    // MARK: - 不变式：对话线路 == 当前启用渠道

    /// 建第一条渠道时就要写下 `feature_route.chat`。
    func testUpsertWritesChatRoute() throws {
        let id = try addConfig("OPENAI", "gpt-4o")
        XCTAssertEqual(try repo.routeConfigId(purpose: .chat), id)
    }

    /// 切渠道时跟随更新。
    func testActivateKeepsChatRouteInSync() throws {
        let first = try addConfig("OPENAI", "gpt-4o")
        _ = try addConfig("DASHSCOPE", "qwen-plus")

        XCTAssertTrue(try repo.activate(id: first))
        XCTAssertEqual(try repo.activeConfig()?.id, first)
        XCTAssertEqual(try repo.routeConfigId(purpose: .chat), first,
                       "引擎读 feature_route.chat，旧引擎读 isEnabled —— 两者必须同一行")
    }

    /// 激活不存在的 id → false，且**不动**对话线路。
    func testActivateUnknownIdDoesNotTouchRoute() throws {
        let id = try addConfig("OPENAI", "gpt-4o")
        XCTAssertFalse(try repo.activate(id: 9999))
        XCTAssertEqual(try repo.routeConfigId(purpose: .chat), id)
    }

    /// 绑定对话线路 = 同时启用它。
    func testBindChatActivatesTheRow() throws {
        let first = try addConfig("OPENAI", "gpt-4o")
        _ = try addConfig("DASHSCOPE", "qwen-plus")

        try repo.bind(purpose: .chat, configId: first)
        XCTAssertEqual(try repo.routeConfigId(purpose: .chat), first)
        XCTAssertEqual(try repo.activeConfig()?.id, first)
    }

    /// 同一时刻只有一条启用 —— 多线路不该破坏单活语义。
    func testExactlyOneEnabledAfterBind() throws {
        let first = try addConfig("OPENAI", "gpt-4o")
        _ = try addConfig("DASHSCOPE", "qwen-plus")
        _ = try addConfig("XIAOMI", "mimo-v2.5-tts")
        try repo.bind(purpose: .chat, configId: first)

        let all = try repo.allConfigs()
        XCTAssertEqual(all.filter { $0.isEnabled }.count, 1)
    }

    // MARK: - 非对话线路

    /// 给朗读绑一条渠道，**不能**把对话换过去。
    func testBindTTSEndDoesNotChangeActiveChannel() throws {
        let chat = try addConfig("OPENAI", "gpt-4o")
        let tts = try addConfig("XIAOMI", "mimo-v2.5-tts")
        XCTAssertTrue(try repo.activate(id: chat))

        try repo.bind(purpose: .tts, configId: tts)

        XCTAssertEqual(try repo.activeConfig()?.id, chat, "朗读绑定不该动到对话渠道")
        XCTAssertEqual(try repo.routeConfigId(purpose: .tts), tts)
    }

    // MARK: - 解析：界面与消费端共用的唯一入口

    /// 没绑定 → 回退「当前启用渠道」，且 `isBound == false`（界面据此显示"跟随当前"）。
    func testResolveFallsBackToActiveWhenUnbound() throws {
        let id = try addConfig("OPENAI", "gpt-4o")
        let image = try repo.resolve(purpose: .image)
        XCTAssertEqual(image?.configId, id)
        XCTAssertEqual(image?.isBound, false)
        XCTAssertEqual(image?.model, "gpt-4o")
    }

    /// 绑定行**不要求** `isEnabled` —— 这是"多线路"成立的前提。
    func testResolveUsesBoundRowEvenIfNotEnabled() throws {
        let openai = try addConfig("OPENAI", "gpt-4o")
        _ = try addConfig("DASHSCOPE", "qwen-plus")   // 把 OPENAI 挤成停用

        XCTAssertFalse(try repo.allConfigs().first { $0.id == openai }!.isEnabled)
        try repo.bind(purpose: .embedding, configId: openai)

        let resolved = try repo.resolve(purpose: .embedding)
        XCTAssertEqual(resolved?.configId, openai)
        XCTAssertEqual(resolved?.isBound, true)
        XCTAssertEqual(resolved?.provider, "OPENAI")
    }

    /// 空库 → nil（不是崩溃，也不是一条假配置）。
    func testResolveEmptyDatabaseReturnsNil() throws {
        XCTAssertNil(try repo.resolve(purpose: .chat))
        XCTAssertNil(try repo.resolve(purpose: .tts))
    }

    /// 模型覆盖优先于渠道自身的模型（宿主侧三条线路靠它共用一条渠道）。
    func testModelOverrideWinsForHostSideLine() throws {
        let id = try addConfig("OPENAI", "gpt-4o")
        try repo.setMetaValue("text-embedding-3-large",
                              forKey: FeaturePurpose.embedding.modelKey)

        let resolved = try repo.resolve(purpose: .embedding)
        XCTAssertEqual(resolved?.configId, id)
        XCTAssertEqual(resolved?.model, "text-embedding-3-large")
        XCTAssertEqual(resolved?.isModelOverridden, true)
    }

    /// 解绑要**连模型覆盖一起清**，否则下次绑到别的渠道会"莫名用了旧模型"。
    func testUnbindClearsModelOverrideToo() throws {
        let id = try addConfig("OPENAI", "gpt-4o")
        try repo.bind(purpose: .tts, configId: id)
        try repo.setMetaValue("custom-tts-model", forKey: FeaturePurpose.tts.modelKey)

        try repo.unbind(purpose: .tts)

        XCTAssertNil(try repo.routeConfigId(purpose: .tts))
        let resolved = try repo.resolve(purpose: .tts)
        XCTAssertEqual(resolved?.isBound, false)
        XCTAssertEqual(resolved?.isModelOverridden, false)
        XCTAssertEqual(resolved?.model, "gpt-4o", "覆盖清掉后应回到渠道自身的模型")
    }

    /// 值不是数字 → 当成"没绑定"（与 Rust `route_config_id` 同一套）。
    func testRouteConfigIdIgnoresNonNumericValue() throws {
        _ = try addConfig("OPENAI", "gpt-4o")
        try repo.setMetaValue("not-a-number", forKey: FeaturePurpose.image.metaKey)

        XCTAssertNil(try repo.routeConfigId(purpose: .image))
        XCTAssertEqual(try repo.resolve(purpose: .image)?.isBound, false)
    }

    // MARK: - 删除

    /// 删掉被绑定的行 → 绑定一并清掉（不留悬空引用）。
    func testDeleteClearsBindingsPointingAtThatRow() throws {
        let chat = try addConfig("OPENAI", "gpt-4o")
        let tts = try addConfig("XIAOMI", "mimo-v2.5-tts")
        XCTAssertTrue(try repo.activate(id: chat))
        try repo.bind(purpose: .tts, configId: tts)

        XCTAssertTrue(try repo.delete(id: tts))

        XCTAssertNil(try repo.routeConfigId(purpose: .tts),
                     "绑定行被删后不该留下悬空引用（界面会显示指向不存在的渠道）")
        XCTAssertEqual(try repo.routeConfigId(purpose: .chat), chat, "对话那条不受影响")
    }

    /// 删掉当前渠道：对话线路同样要清（否则界面显示一条已不存在的渠道）。
    func testDeleteActiveRowClearsChatRouteToo() throws {
        let id = try addConfig("OPENAI", "gpt-4o")
        XCTAssertEqual(try repo.routeConfigId(purpose: .chat), id)

        XCTAssertTrue(try repo.delete(id: id))

        XCTAssertNil(try repo.routeConfigId(purpose: .chat))
        XCTAssertNil(try repo.activeConfig())
    }

    // MARK: - 绑定校验

    /// id 不存在 → 抛错且**什么都不写**。
    func testBindUnknownIdThrowsAndWritesNothing() throws {
        _ = try addConfig("OPENAI", "gpt-4o")

        XCTAssertThrowsError(try repo.bind(purpose: .image, configId: 4242)) { error in
            XCTAssertEqual(error as? RouteError, RouteError.configNotFound)
        }
        XCTAssertNil(try repo.routeConfigId(purpose: .image))
    }

    /// 对话线路绑到不存在的 id：当前启用态与对话线路都不许被改坏。
    func testBindChatUnknownIdKeepsEverythingIntact() throws {
        let id = try addConfig("OPENAI", "gpt-4o")

        XCTAssertThrowsError(try repo.bind(purpose: .chat, configId: 4242)) { error in
            XCTAssertEqual(error as? RouteError, RouteError.configNotFound)
        }
        XCTAssertEqual(try repo.activeConfig()?.id, id)
        XCTAssertEqual(try repo.routeConfigId(purpose: .chat), id)
    }

    // MARK: - app_meta 读写

    /// 空串 = 删键（而不是写一个空值进去）。
    func testMetaValueEmptyStringDeletesKey() throws {
        try repo.setMetaValue("v1", forKey: "probe.key")
        XCTAssertEqual(try repo.metaValue(forKey: "probe.key"), "v1")

        try repo.setMetaValue("", forKey: "probe.key")
        XCTAssertNil(try repo.metaValue(forKey: "probe.key"))

        try repo.setMetaValue(nil, forKey: "probe.key")
        XCTAssertNil(try repo.metaValue(forKey: "probe.key"))
    }

    /// `updateModel` 改的是**渠道行**（对话线路的模型就存在那里，引擎从行里读）。
    func testUpdateModelChangesRow() throws {
        let id = try addConfig("OPENAI", "gpt-4o")
        XCTAssertTrue(try repo.updateModel(id: id, model: "gpt-4o-mini"))
        XCTAssertEqual(try repo.config(id: id)?.model, "gpt-4o-mini")
        XCTAssertEqual(try repo.resolve(purpose: .chat)?.model, "gpt-4o-mini")
        XCTAssertFalse(try repo.updateModel(id: 9999, model: "x"), "不存在的 id 应返回 false")
    }

    /// 按 id 读一行：不要求 `isEnabled`（用途绑定是显式指定）。
    func testConfigByIdReadsDisabledRow() throws {
        let openai = try addConfig("OPENAI", "gpt-4o")
        _ = try addConfig("DASHSCOPE", "qwen-plus")

        let row = try repo.config(id: openai)
        XCTAssertEqual(row?.provider, "OPENAI")
        XCTAssertEqual(row?.isEnabled, false)
        XCTAssertNil(try repo.config(id: 4242))
    }

    /// 四条线路各自独立：绑一条不影响另一条。
    func testRoutesAreIndependent() throws {
        let a = try addConfig("OPENAI", "gpt-4o")
        let b = try addConfig("XIAOMI", "mimo-v2.5-tts")
        try repo.bind(purpose: .image, configId: a)
        try repo.bind(purpose: .tts, configId: b)

        XCTAssertEqual(try repo.routeConfigId(purpose: .image), a)
        XCTAssertEqual(try repo.routeConfigId(purpose: .tts), b)
        XCTAssertNil(try repo.routeConfigId(purpose: .embedding))
    }

    /// `FeaturePurpose` 的键是与 Rust 的**跨端契约**，逐字锁死。
    func testPurposeMetaKeysMatchRustContract() {
        XCTAssertEqual(FeaturePurpose.chat.metaKey, "feature_route.chat")
        XCTAssertEqual(FeaturePurpose.image.metaKey, "feature_route.image")
        XCTAssertEqual(FeaturePurpose.tts.metaKey, "feature_route.tts")
        XCTAssertEqual(FeaturePurpose.embedding.metaKey, "feature_route.embedding")
        XCTAssertEqual(FeaturePurpose.embedding.modelKey, "feature_route.embedding.model")
    }
}
