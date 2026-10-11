import XCTest

/// `TtsClient` 的纯逻辑规格（协议规整 / 音频嗅探 / 文本清理）。
///
/// 权威来源：`OpenAiCompatibleTtsProvider.kt` 与 `MiMoTtsProvider.kt`。
/// 这些函数**不发请求**，所以能在模拟器上直接验；
/// 真正的网络路径只能靠真机 + 一条真实语音渠道验收。
@testable import YuNian
final class TtsClientTests: XCTestCase {

    // MARK: - OpenAI 兼容：地址规整

    func testSpeechUrlFromBaseWithV1() {
        XCTAssertEqual(
            TtsClient.normalizeSpeechUrl("https://api.openai.com/v1")?.absoluteString,
            "https://api.openai.com/v1/audio/speech"
        )
    }

    func testSpeechUrlFromBareHost() {
        XCTAssertEqual(
            TtsClient.normalizeSpeechUrl("https://api.openai.com")?.absoluteString,
            "https://api.openai.com/v1/audio/speech"
        )
    }

    /// 尾斜杠要吃掉（预设里的 baseUrl 大多带 `/`）。
    func testSpeechUrlTrimsTrailingSlash() {
        XCTAssertEqual(
            TtsClient.normalizeSpeechUrl("https://api.openai.com/v1/")?.absoluteString,
            "https://api.openai.com/v1/audio/speech"
        )
    }

    func testSpeechUrlAlreadySpeech() {
        XCTAssertEqual(
            TtsClient.normalizeSpeechUrl("https://api.openai.com/v1/audio/speech")?.absoluteString,
            "https://api.openai.com/v1/audio/speech"
        )
    }

    func testSpeechUrlFromV1Audio() {
        XCTAssertEqual(
            TtsClient.normalizeSpeechUrl("https://api.openai.com/v1/audio")?.absoluteString,
            "https://api.openai.com/v1/audio/speech"
        )
    }

    /// 未知路径一律拒绝 —— 猜错的后果是把密钥发到没预期的路径上。
    func testSpeechUrlRejectsUnknownPath() {
        XCTAssertNil(TtsClient.normalizeSpeechUrl("https://api.openai.com/v1/other"))
        XCTAssertNil(TtsClient.normalizeSpeechUrl("https://api.openai.com/v2"))
    }

    /// 公网 http 不允许（明文 key 不该出网），私网 http 允许（本机 TTS 服务）。
    func testSpeechUrlAllowsHttpOnlyForPrivateHosts() {
        XCTAssertNil(TtsClient.normalizeSpeechUrl("http://api.openai.com/v1"))
        XCTAssertEqual(
            TtsClient.normalizeSpeechUrl("http://192.168.1.9:8080/v1")?.absoluteString,
            "http://192.168.1.9:8080/v1/audio/speech"
        )
        XCTAssertEqual(
            TtsClient.normalizeSpeechUrl("http://localhost:8000")?.absoluteString,
            "http://localhost:8000/v1/audio/speech"
        )
    }

    /// 带 query / fragment / userInfo 的一律拒绝。
    func testSpeechUrlRejectsQueryAndFriends() {
        XCTAssertNil(TtsClient.normalizeSpeechUrl("https://api.openai.com/v1?x=1"))
        XCTAssertNil(TtsClient.normalizeSpeechUrl("https://api.openai.com/v1#f"))
        XCTAssertNil(TtsClient.normalizeSpeechUrl("https://u:p@api.openai.com/v1"))
    }

    func testSpeechUrlRejectsEmptyAndRelative() {
        XCTAssertNil(TtsClient.normalizeSpeechUrl(""))
        XCTAssertNil(TtsClient.normalizeSpeechUrl("   "))
        XCTAssertNil(TtsClient.normalizeSpeechUrl("api.openai.com/v1"))
    }

    // MARK: - 私网判定

    func testIsPrivateHost() {
        for host in ["localhost", "::1", "127.0.0.1", "10.0.0.5", "172.16.3.4",
                     "172.31.255.254", "192.168.1.1", "169.254.1.1"] {
            XCTAssertTrue(TtsClient.isPrivateHost(host), "\(host) 应判为私网")
        }
        for host in ["api.openai.com", "8.8.8.8", "172.32.0.1", "172.15.0.1",
                     "192.169.0.1", "11.0.0.1", "127.0.0", "192.168.1"] {
            XCTAssertFalse(TtsClient.isPrivateHost(host), "\(host) 不该判为私网")
        }
    }

    // MARK: - 音频嗅探

    private func riffWave() -> Data {
        Data([0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00,
              0x57, 0x41, 0x56, 0x45])
    }

    func testIsLikelyAudioBodyByMagic() {
        XCTAssertTrue(TtsClient.isLikelyAudioBody(riffWave(), contentType: ""))
        XCTAssertTrue(TtsClient.isLikelyAudioBody(Data([0x49, 0x44, 0x33, 0x04]),
                                                  contentType: ""))
        XCTAssertTrue(TtsClient.isLikelyAudioBody(Data([0xFF, 0xFB, 0x90, 0x00]),
                                                  contentType: ""))
        XCTAssertTrue(TtsClient.isLikelyAudioBody(Data([0xFF, 0xF3, 0x90, 0x00]),
                                                  contentType: ""))
        XCTAssertTrue(TtsClient.isLikelyAudioBody(Data([0x66, 0x4C, 0x61, 0x43]),
                                                  contentType: ""))
        XCTAssertTrue(TtsClient.isLikelyAudioBody(Data([0x4F, 0x67, 0x67, 0x53]),
                                                  contentType: ""))
        XCTAssertTrue(TtsClient.isLikelyAudioBody(Data([0xFF, 0xF1, 0x50, 0x80]),
                                                  contentType: ""))
    }

    /// Content-Type 说是 JSON/HTML → 一定是错误体，哪怕字节很长。
    func testIsLikelyAudioBodyRejectsJsonAndHtml() {
        let longJson = Data(repeating: 0x41, count: 500)
        XCTAssertFalse(TtsClient.isLikelyAudioBody(longJson, contentType: "application/json"))
        XCTAssertFalse(TtsClient.isLikelyAudioBody(longJson, contentType: "text/html; charset=utf-8"))
        XCTAssertFalse(TtsClient.isLikelyAudioBody(longJson, contentType: "text/plain"))
        XCTAssertFalse(TtsClient.isLikelyAudioBody(longJson, contentType: "application/xml"))
    }

    func testIsLikelyAudioBodyAcceptsAudioContentType() {
        XCTAssertTrue(TtsClient.isLikelyAudioBody(Data(repeating: 0x01, count: 4),
                                                  contentType: "audio/mpeg"))
    }

    /// 没有魔数也没有 Content-Type：短片判为错误体，长片放行。
    func testIsLikelyAudioBodyFallsBackToLength() {
        XCTAssertFalse(TtsClient.isLikelyAudioBody(Data(repeating: 0x01, count: 10),
                                                   contentType: ""))
        XCTAssertTrue(TtsClient.isLikelyAudioBody(Data(repeating: 0x01, count: 200),
                                                  contentType: ""))
    }

    // MARK: - MiMo

    func testMimoBaseUrlAcceptsWhitelistedHosts() {
        XCTAssertEqual(
            TtsClient.normalizeMimoBaseUrl("https://api.xiaomimimo.com/v1")?.absoluteString,
            "https://api.xiaomimimo.com/v1"
        )
        XCTAssertEqual(
            TtsClient.normalizeMimoBaseUrl("https://token-plan-cn.xiaomimimo.com/")?.absoluteString,
            "https://token-plan-cn.xiaomimimo.com/v1"
        )
    }

    func testMimoBaseUrlRejectsEverythingElse() {
        XCTAssertNil(TtsClient.normalizeMimoBaseUrl("http://api.xiaomimimo.com/v1"),
                     "非 https 一律拒绝")
        XCTAssertNil(TtsClient.normalizeMimoBaseUrl("https://api.openai.com/v1"),
                     "白名单外的主机一律拒绝")
        XCTAssertNil(TtsClient.normalizeMimoBaseUrl("https://api.xiaomimimo.com/v1/chat"),
                     "路径只允许空或 /v1")
        XCTAssertNil(TtsClient.normalizeMimoBaseUrl("https://api.xiaomimimo.com/v1?x=1"))
        XCTAssertNil(TtsClient.normalizeMimoBaseUrl("https://api.xiaomimimo.com:8443/v1"))
        XCTAssertNil(TtsClient.normalizeMimoBaseUrl(""))
    }

    func testMimoModelNormalize() {
        XCTAssertEqual(TtsClient.normalizeMimoModel("mimo-v2.5-tts"), "mimo-v2.5-tts")
        XCTAssertEqual(TtsClient.normalizeMimoModel("MiMo-V2.5-TTS"), "mimo-v2.5-tts")
        XCTAssertEqual(TtsClient.normalizeMimoModel("mimo-v2.5-tts-voicedesign"),
                       "mimo-v2.5-tts-voicedesign")
        XCTAssertEqual(TtsClient.normalizeMimoModel("mimo-v2.5-tts-voiceclone"),
                       "mimo-v2.5-tts-voiceclone")
        XCTAssertEqual(TtsClient.normalizeMimoModel("gpt-4o"), "mimo-v2.5-tts",
                       "认不出来的模型回落到默认 TTS 模型")
        XCTAssertEqual(TtsClient.normalizeMimoModel(""), "mimo-v2.5-tts")
    }

    func testExtractMimoAudioBase64() {
        let json = #"{"choices":[{"message":{"audio":{"data":"QUJD"}}}]}"#
        XCTAssertEqual(TtsClient.extractMimoAudioBase64(Data(json.utf8)), "QUJD")

        XCTAssertNil(TtsClient.extractMimoAudioBase64(Data(#"{"choices":[]}"#.utf8)))
        XCTAssertNil(TtsClient.extractMimoAudioBase64(Data(#"{"error":{"message":"x"}}"#.utf8)))
        XCTAssertNil(TtsClient.extractMimoAudioBase64(Data("not json".utf8)))
    }

    // MARK: - 协议判定

    func testResolveProtocol() {
        XCTAssertEqual(TtsClient.resolveProtocol(.auto, provider: "XIAOMI"), .mimo)
        XCTAssertEqual(TtsClient.resolveProtocol(.auto, provider: "OPENAI"), .openai)
        XCTAssertEqual(TtsClient.resolveProtocol(.auto, provider: "CUSTOM"), .openai)
        // 显式选择优先于 provider 推断
        XCTAssertEqual(TtsClient.resolveProtocol(.openai, provider: "XIAOMI"), .openai)
        XCTAssertEqual(TtsClient.resolveProtocol(.mimo, provider: "OPENAI"), .mimo)
    }

    // MARK: - 超时预算（逐字对应 TimeoutBudgets.ttsSynthTimeoutMs）

    func testSynthTimeout() {
        XCTAssertEqual(TtsClient.synthTimeout(textLength: 0), 30)
        XCTAssertEqual(TtsClient.synthTimeout(textLength: 10), 30)
        XCTAssertEqual(TtsClient.synthTimeout(textLength: 100), 100)
        XCTAssertEqual(TtsClient.synthTimeout(textLength: 1000), 180)
    }

    // MARK: - 文本清理

    func testCleanTextRemovesStickerIdsAndImageMarkers() {
        XCTAssertEqual(TtsClient.cleanText("你好[12]呀"), "你好呀")
        XCTAssertEqual(TtsClient.cleanText("[图片]"), "")
        XCTAssertEqual(TtsClient.cleanText("[[生图: 一只猫]] 好看"), "好看",
                       "双层方括号要整体吃掉，不能留下孤立的 ]")
        XCTAssertEqual(TtsClient.cleanText("今天**很好**"), "今天很好")
    }

    /// 括号内容默认**保留**（与 Android `skipParentheses` 默认 false 一致）。
    func testCleanTextKeepsParenthesesByDefault() {
        XCTAssertEqual(TtsClient.cleanText("（轻轻抱住你）我在"), "（轻轻抱住你）我在")
        XCTAssertEqual(TtsClient.cleanText("（轻轻抱住你）我在", skipParentheses: true), "我在")
        XCTAssertEqual(TtsClient.cleanText("(hug) hi", skipParentheses: true), "hi")
    }

    func testCleanTextRemovesAngleTags() {
        XCTAssertEqual(TtsClient.cleanText("<thinking>嗯</thinking>好"), "嗯好")
    }

    func testCleanTextTrims() {
        XCTAssertEqual(TtsClient.cleanText("   \n 你好 \n "), "你好")
    }

    // MARK: - 错误文案

    func testServerMessageExtraction() {
        let body = Data(#"{"error":{"message":"Invalid API key"}}"#.utf8)
        XCTAssertEqual(TtsClient.serverMessage(body), "Invalid API key")
        XCTAssertEqual(TtsClient.serverMessage(Data(#"{"message":"boom"}"#.utf8)), "boom")
        XCTAssertEqual(TtsClient.serverMessage(Data(#"{"error":{"type":"bad"}}"#.utf8)), "bad")
        XCTAssertNil(TtsClient.serverMessage(Data("not json".utf8)))
    }

    /// 不支持的两个模型要给出**能照着做**的提示，而不是抛一句"失败"。
    func testUnsupportedModelMessageMentionsFallback() {
        let message = TtsClient.TtsError
            .unsupportedModel(TtsClient.mimoModelVoiceClone).message
        XCTAssertTrue(message.contains("mimo-v2.5-tts"))
    }

    /// 没有可朗读的文字时提前退出（不发请求）。
    func testSynthesizeRejectsEmptyText() async {
        let plan = TtsClient.Plan(
            baseUrl: "https://api.openai.com/v1",
            apiKey: "sk-test",
            model: "tts-1",
            provider: "OPENAI",
            proto: .openai,
            voice: "alloy",
            format: .mp3,
            text: "   "
        )
        do {
            _ = try await TtsClient().synthesize(plan)
            XCTFail("空文本不该发起请求")
        } catch {
            XCTAssertEqual(error as? TtsClient.TtsError, TtsClient.TtsError.emptyText)
        }
    }

    /// 没密钥同样提前退出。
    func testSynthesizeRejectsMissingKey() async {
        let plan = TtsClient.Plan(
            baseUrl: "https://api.openai.com/v1",
            apiKey: "",
            model: "tts-1",
            provider: "OPENAI",
            proto: .openai,
            voice: "alloy",
            format: .mp3,
            text: "你好"
        )
        do {
            _ = try await TtsClient().synthesize(plan)
            XCTFail("没有密钥不该发起请求")
        } catch {
            XCTAssertEqual(error as? TtsClient.TtsError, TtsClient.TtsError.missingKey)
        }
    }

    /// 音色设计/复刻模型在 iOS 上明确不支持（要额外字段与本地样本）。
    func testSynthesizeRejectsVoiceCloneModel() async {
        let plan = TtsClient.Plan(
            baseUrl: "https://api.xiaomimimo.com/v1",
            apiKey: "k",
            model: TtsClient.mimoModelVoiceClone,
            provider: "XIAOMI",
            proto: .mimo,
            voice: "mimo_default",
            format: .wav,
            text: "你好"
        )
        do {
            _ = try await TtsClient().synthesize(plan)
            XCTFail("复刻模型应当明确报不支持")
        } catch {
            XCTAssertEqual(error as? TtsClient.TtsError,
                           TtsClient.TtsError.unsupportedModel(TtsClient.mimoModelVoiceClone))
        }
    }

    // MARK: - 设置项

    func testTtsSettingsRoundTrip() throws {
        let tmpDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("ttssettings_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let database = try YuNianDatabase(databaseURL: tmpDir.appendingPathComponent("yunian_database"))
        let repo = ApiConfigRepository(database: database)

        // 默认值：与 Android 的 ChatTtsConfig 默认一致
        let empty = TtsSettings.load(from: repo)
        XCTAssertEqual(empty.voice, "")
        XCTAssertEqual(empty.proto, .auto)
        XCTAssertEqual(empty.format, .mp3)
        XCTAssertFalse(empty.skipParentheses)

        var saved = TtsSettings()
        saved.voice = "冰糖"
        saved.proto = .mimo
        saved.format = .wav
        saved.skipParentheses = true
        try saved.save(to: repo)

        XCTAssertEqual(TtsSettings.load(from: repo), saved)
    }

    /// 关掉 skipParentheses 后，键要被**删掉**（而不是留一个 "0"）。
    func testTtsSettingsClearsSkipParenthesesKey() throws {
        let tmpDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("ttssettings2_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmpDir) }

        let database = try YuNianDatabase(databaseURL: tmpDir.appendingPathComponent("yunian_database"))
        let repo = ApiConfigRepository(database: database)

        var settings = TtsSettings()
        settings.skipParentheses = true
        try settings.save(to: repo)
        XCTAssertEqual(try repo.metaValue(forKey: TtsSettings.skipParenthesesKey), "1")

        settings.skipParentheses = false
        try settings.save(to: repo)
        XCTAssertNil(try repo.metaValue(forKey: TtsSettings.skipParenthesesKey))
    }
}
