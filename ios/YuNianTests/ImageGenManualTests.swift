import XCTest
import GRDB

@testable import YuNian

/// 主动生图（第 228 轮，BACKLOG B2）。
///
/// ## 为什么只测到这里
/// 真正出图要走网络（`ImageGenClient.generate`），单测不适合打真接口。
/// 但这条链路上有几处**纯逻辑**必须锁住 —— 它们恰好是"点了没反应"的常见成因：
/// 1. 空描述要**在发请求之前**就被挡住（否则会发一个空的生图请求，用户等到超时）；
/// 2. 手动输入的清洗（去空白、封顶长度）—— 超长 prompt 会换来一个语焉不详的 400；
/// 3. 未配生图模型时要给出**可执行的原因**，而不是静默失败。
final class ImageGenManualTests: XCTestCase {

    // MARK: - 输入清洗

    func testManualPromptTrimsAndRejectsBlank() {
        XCTAssertEqual(ImageGenCoordinator.manualPrompt("  窗边的小猫  "), "窗边的小猫")
        XCTAssertNil(ImageGenCoordinator.manualPrompt(""))
        XCTAssertNil(ImageGenCoordinator.manualPrompt("   \n\t "))
    }

    /// 超长描述会被截断到上限 —— 与其让服务端回一个语焉不详的 400，
    /// 不如在这里截断（用户至少能出图）。
    func testManualPromptCapsLength() {
        let long = String(repeating: "猫", count: 2000)
        let cleaned = ImageGenCoordinator.manualPrompt(long, limit: 100)
        XCTAssertEqual(cleaned?.count, 100)
    }

    func testManualPromptKeepsShortTextIntact() {
        XCTAssertEqual(ImageGenCoordinator.manualPrompt("一只猫", limit: 800), "一只猫")
    }

    // MARK: - 空描述不发请求（"点了没反应"的反面：点了要有明确答复）

    @MainActor
    func testManualWithBlankPromptReturnsReadableErrorWithoutCallingNetwork() async {
        var messages: [(String, Bool)] = []
        var imagesHandedOff = 0
        var coordinator = ImageGenCoordinator(deps: .init(
            global: ImageGenTrigger.GlobalConfig(),
            override: ImageGenTrigger.CompanionOverride(),
            mainConnection: nil,
            configBaseUrl: "https://example.invalid/v1",
            configApiKey: "test-key",
            imageModel: "flux-schnell",
            lastGenAtMs: 0,
            saveLastGenAt: { _ in }
        ))
        coordinator.onMessage = { text, isError in messages.append((text, isError)) }
        coordinator.onImages = { datas, _ in imagesHandedOff += datas.count }

        // 只给空白 —— 这一步必须**在触网之前**返回
        let outcome = await coordinator.runManual(prompt: "   ", companionId: 1)

        XCTAssertFalse(outcome.triggered)
        XCTAssertTrue(outcome.isError, "空描述要如实报错，而不是静默什么都不做")
        XCTAssertEqual(outcome.message, ImageGenClient.ImageGenError.emptyPrompt.message)
        XCTAssertEqual(outcome.imagesWritten, 0)
        XCTAssertEqual(imagesHandedOff, 0)
        XCTAssertEqual(messages.first?.0, ImageGenClient.ImageGenError.emptyPrompt.message)
        XCTAssertEqual(messages.first?.1, true)
    }

    /// 空模型名：也要给可执行的原因（去哪填），而不是发一个 `"model": ""` 的请求。
    /// 这里用一个**必然连不上**的地址：如果实现顺序写错（先发请求再校验模型），
    /// 这条会因为超时/网络错误而失败，从而暴露顺序问题。
    @MainActor
    func testManualWithoutImageModelFailsFast() async {
        var coordinator = ImageGenCoordinator(deps: .init(
            global: ImageGenTrigger.GlobalConfig(),
            override: ImageGenTrigger.CompanionOverride(),
            mainConnection: nil,
            configBaseUrl: "https://example.invalid/v1",
            configApiKey: "test-key",
            imageModel: "   ",
            lastGenAtMs: 0,
            saveLastGenAt: { _ in }
        ))
        coordinator.onMessage = { _, _ in }
        coordinator.onImages = { _, _ in XCTFail("不该出图") }

        let outcome = await coordinator.runManual(prompt: "一只猫", companionId: 1)
        XCTAssertFalse(outcome.triggered)
        XCTAssertTrue(outcome.isError)
        XCTAssertEqual(outcome.message, ImageGenClient.ImageGenError.noModel.message)
    }
}
