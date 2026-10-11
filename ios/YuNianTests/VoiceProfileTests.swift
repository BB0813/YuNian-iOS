import GRDB
import XCTest

/// 语音方案（第 208 轮）：按角色音色覆盖 + 自动朗读开关。
///
/// 锁的是两类会"静默失效"的东西：
/// 覆盖优先级（角色 > 全局）与"空值 = 清除覆盖"的存储语义
/// （`setMetaValue` 对空串是**删键**，写错了会变成"清不掉"）。
@testable import YuNian
final class VoiceProfileTests: XCTestCase {

    private func makeDatabase() throws -> (YuNianDatabase, ApiConfigRepository, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("voice_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let database = try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database"))
        return (database, ApiConfigRepository(database: database), dir)
    }

    // MARK: - 优先级（纯函数）

    func testOverrideWinsOverGlobal() {
        XCTAssertEqual(VoiceProfileStore.effectiveVoice(voiceOverride: "冰糖", global: "alloy"), "冰糖")
    }

    func testNilOverrideFallsBackToGlobal() {
        XCTAssertEqual(VoiceProfileStore.effectiveVoice(voiceOverride: nil, global: "alloy"), "alloy")
    }

    /// 空串/空白 = 没有覆盖（界面上"清空输入框"就是这个语义）。
    func testBlankOverrideFallsBackToGlobal() {
        XCTAssertEqual(VoiceProfileStore.effectiveVoice(voiceOverride: "", global: "alloy"), "alloy")
        XCTAssertEqual(VoiceProfileStore.effectiveVoice(voiceOverride: "   ", global: "alloy"), "alloy")
    }

    func testGlobalMayBeEmptyMeaningProtocolDefault() {
        XCTAssertEqual(VoiceProfileStore.effectiveVoice(voiceOverride: nil, global: ""), "")
    }

    // MARK: - 存储

    func testOverrideRoundTrip() throws {
        let (_, repo, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }

        XCTAssertNil(VoiceProfileStore.voiceOverride(companionId: 7, repository: repo))
        try VoiceProfileStore.setVoiceOverride("茉莉", companionId: 7, repository: repo)
        XCTAssertEqual(VoiceProfileStore.voiceOverride(companionId: 7, repository: repo), "茉莉")
        XCTAssertNil(VoiceProfileStore.voiceOverride(companionId: 8, repository: repo),
                     "不同角色互不影响")
    }

    /// ⚠️ 清除覆盖必须能真的清除（依赖 `setMetaValue("")` = 删键）。
    func testClearingOverrideRestoresGlobal() throws {
        let (_, repo, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }

        try VoiceProfileStore.setVoiceOverride("苏打", companionId: 3, repository: repo)
        try VoiceProfileStore.setVoiceOverride("", companionId: 3, repository: repo)
        XCTAssertNil(VoiceProfileStore.voiceOverride(companionId: 3, repository: repo),
                     "空串必须等于「没有覆盖」")
        XCTAssertEqual(VoiceProfileStore.effectiveVoice(companionId: 3, global: "alloy",
                                                        repository: repo), "alloy")
    }

    func testWhitespaceOverrideIsTreatedAsAbsent() throws {
        let (_, repo, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        try VoiceProfileStore.setVoiceOverride("   ", companionId: 3, repository: repo)
        XCTAssertNil(VoiceProfileStore.voiceOverride(companionId: 3, repository: repo))
    }

    func testEffectiveVoiceUsesCompanionOverride() throws {
        let (_, repo, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        try VoiceProfileStore.setVoiceOverride("白桦", companionId: 11, repository: repo)

        XCTAssertEqual(VoiceProfileStore.effectiveVoice(companionId: 11, global: "alloy",
                                                        repository: repo), "白桦")
        XCTAssertEqual(VoiceProfileStore.effectiveVoice(companionId: 12, global: "alloy",
                                                        repository: repo), "alloy")
        // nil 角色（未绑定伴侣）→ 全局
        XCTAssertEqual(VoiceProfileStore.effectiveVoice(companionId: nil, global: "alloy",
                                                        repository: repo), "alloy")
    }

    /// companionId <= 0 是无效角色：**不能**写出 `voice.voice.0` 这种键。
    func testInvalidCompanionIdIsIgnored() throws {
        let (_, repo, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        try VoiceProfileStore.setVoiceOverride("冰糖", companionId: 0, repository: repo)
        XCTAssertNil(try repo.metaValue(forKey: VoiceProfileStore.overrideKey(companionId: 0)))
        XCTAssertNil(VoiceProfileStore.voiceOverride(companionId: 0, repository: repo))
    }

    func testOverriddenCompanionIds() throws {
        let (_, repo, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        try VoiceProfileStore.setVoiceOverride("冰糖", companionId: 1, repository: repo)
        try VoiceProfileStore.setVoiceOverride("", companionId: 2, repository: repo)

        XCTAssertEqual(VoiceProfileStore.overriddenCompanionIds([1, 2, 3], repository: repo), [1])
    }

    // MARK: - 自动朗读开关（与 TtsSettings 同一份存储）

    func testAutoReadDefaultsOffAndPersists() throws {
        let (_, repo, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }

        // 默认**关**：默认开等于"刚装上就突然出声"
        XCTAssertFalse(TtsSettings.load(from: repo).autoRead)

        var settings = TtsSettings()
        settings.autoRead = true
        try settings.save(to: repo)
        XCTAssertTrue(TtsSettings.load(from: repo).autoRead)

        settings.autoRead = false
        try settings.save(to: repo)
        XCTAssertFalse(TtsSettings.load(from: repo).autoRead,
                       "关掉后必须能读回 false（不是「键还在就算真」）")
    }

    /// 音色覆盖与线路参数互不干扰（同一张 `app_meta`，键前缀不同）。
    func testVoiceOverrideDoesNotTouchRouteSettings() throws {
        let (_, repo, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }

        var settings = TtsSettings()
        settings.voice = "alloy"
        settings.proto = .mimo
        settings.skipParentheses = true
        try settings.save(to: repo)

        try VoiceProfileStore.setVoiceOverride("茉莉", companionId: 5, repository: repo)

        let reloaded = TtsSettings.load(from: repo)
        XCTAssertEqual(reloaded.voice, "alloy")
        XCTAssertEqual(reloaded.proto, .mimo)
        XCTAssertTrue(reloaded.skipParentheses)
    }

    // MARK: - 试听状态机

    @MainActor
    func testPreviewerIdleStateHasNoStatusLine() {
        let previewer = VoicePreviewer()
        XCTAssertEqual(previewer.state, .idle)
        XCTAssertNil(previewer.state.title, "idle 不该显示状态行")
        XCTAssertFalse(previewer.state.isBusy)
        XCTAssertFalse(previewer.state.isFailed)
    }

    @MainActor
    func testPreviewerResetClearsFailure() {
        let previewer = VoicePreviewer()
        previewer.reset()
        XCTAssertEqual(previewer.state, .idle)
    }

    /// 试听文本是通用短句（不写角色台词 —— 否则"线路对不对"和"台词合不合适"混在一起）。
    func testSampleTextIsGeneric() {
        XCTAssertFalse(VoicePreviewer.sampleText.isEmpty)
        XCTAssertFalse(VoicePreviewer.sampleText.contains("["), "不该带协议标记")
    }

    /// 错误文案与聊天页**共用同一份**映射（两处说法不一致会像两个毛病）。
    func testErrorDescriptionIsSharedWithChat() {
        let error = TtsClient.TtsError.http(401, "bad key")
        XCTAssertEqual(TtsClient.describe(error), error.message)
        XCTAssertTrue(TtsClient.describe(error).contains("401"))
    }
}
