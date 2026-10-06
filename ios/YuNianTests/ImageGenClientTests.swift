import XCTest

/// `ImageGenClient` 的纯逻辑规格测试。
///
/// 权威来源：`core/network/.../ImageGenerationService.kt`。
///
/// 只测**无网络**的部分：
/// · 参数回退序列（4 级降级 + distinct）
/// · 参数类错误判定
/// · 生图模型启发式
/// · base 候选规范化
///
/// 网络部分（fetchModels / generate）需要真接口，未纳入单测。
@testable import YuNian
final class ImageGenClientTests: XCTestCase {

    // MARK: - 参数回退序列

    /// Kotlin `buildRequestAttempts`（:219-234）：
    /// primary(b64_json) → 去 responseFormat → count=1 → size=nil，distinct。
    func testAttemptSequenceDegradesInOrder() {
        let a = ImageGenClient.buildAttempts(prompt: "一只猫", size: "1024x1024", count: 2)

        XCTAssertEqual(a.count, 4, "应有 4 级")
        XCTAssertEqual(a[0].responseFormat, "b64_json", "第一级带 responseFormat")
        XCTAssertEqual(a[0].count, 2)
        XCTAssertEqual(a[0].size, "1024x1024")

        XCTAssertNil(a[1].responseFormat, "第二级去掉 responseFormat")
        XCTAssertEqual(a[1].count, 2, "第二级 count 不变")

        XCTAssertEqual(a[2].count, 1, "第三级 count 降到 1")
        XCTAssertNotNil(a[2].size, "第三级 size 仍在")

        XCTAssertEqual(a[3].count, 1)
        XCTAssertNil(a[3].size, "第四级 size 也去掉")
        XCTAssertNil(a[3].responseFormat)
    }

    /// `MAX_IMAGES_PER_REQUEST = 4` 上限（Kotlin :476）
    func testCountClampedToMax() {
        let a = ImageGenClient.buildAttempts(prompt: "x", size: nil, count: 99)
        XCTAssertEqual(a[0].count, 4)
    }

    /// count 下限 1（Kotlin coerceIn(1, MAX)）
    func testCountClampedToMin() {
        let a = ImageGenClient.buildAttempts(prompt: "x", size: nil, count: 0)
        XCTAssertEqual(a[0].count, 1)
    }

    /// 空 size 回落到默认（Kotlin :226 ifBlank DEFAULT_IMAGE_SIZE）
    func testBlankSizeFallsBackToDefault() {
        let a = ImageGenClient.buildAttempts(prompt: "x", size: "   ", count: 1)
        XCTAssertFalse((a[0].size ?? "").isEmpty, "空 size 必须有回落值")
    }

    /// distinct：第四级与第三级在 count=1 & size=nil & responseFormat=nil 时可能重复
    func testAttemptsAreDistinct() {
        let a = ImageGenClient.buildAttempts(prompt: "x", size: nil, count: 1)
        var seen = Set<String>()
        for at in a {
            let key = "\(at.count)|\(at.size ?? "-")|\(at.responseFormat ?? "-")"
            XCTAssertFalse(seen.contains(key), "attempt 序列里有重复：\(key)")
            seen.insert(key)
        }
    }

    // MARK: - 参数类错误判定

    /// Kotlin `isRecoverableParamError`（:236-244）
    func testRecoverableParamErrors() {
        let cases = [
            "response_format is not supported",
            "Unsupported parameter",
            "Invalid size",
            "n must be 1",
            "size must be square",
            "参数不正确",
        ]
        for m in cases {
            XCTAssertTrue(ImageGenClient.isRecoverableParamError(
                ImageGenClient.ImageGenError.http(m)),
                "应判为可回退：\(m)")
        }
    }

    func testNonRecoverableErrors() {
        let cases = ["unauthorized", "rate limit", "insufficient balance", "HTTP 500"]
        for m in cases {
            XCTAssertFalse(ImageGenClient.isRecoverableParamError(
                ImageGenClient.ImageGenError.http(m)),
                "不应判为可回退：\(m)")
        }
    }

    // MARK: - 模型启发式

    /// Kotlin `IMAGE_MODEL_HINTS`（:484-488）
    func testLikelyImageModelsHit() {
        let ids = ["flux.1-dev", "dall-e-3", "sdxl-base", "qwen-image",
                   "cogview-3", "wanx-v1", "seedream-3", "kolors-2"]
        for id in ids {
            XCTAssertTrue(ImageGenClient.isLikelyImageModel(id),
                          "应识别为生图模型：\(id)")
        }
    }

    /// Kotlin `CHAT_MODEL_EXCLUDES`（:491-494）——
    /// 含 "image" 的视觉模型被排除，避免误纳。
    func testChatModelsExcluded() {
        let ids = ["qwen-vl-chat", "gpt-4-vision-preview", "text-embedding-3",
                   "bge-rerank", "tts-1", "whisper-1", "gpt-4o-chat",
                   "deepseek-coder", "o1-reasoner"]
        for id in ids {
            XCTAssertFalse(ImageGenClient.isLikelyImageModel(id),
                           "不应识别为生图模型：\(id)")
        }
    }

    /// 优先级：命中 hint 但同时命中 exclude → 不识别。
    /// 例："image-chat" 既含 image 又含 chat。
    func testExcludeWinsOverHint() {
        XCTAssertFalse(ImageGenClient.isLikelyImageModel("image-chat"))
        XCTAssertTrue(ImageGenClient.isLikelyImageModel("image-generation-xl"))
    }

    // MARK: - base 候选

    /// 尾斜杠要去掉；带 /v1 的要额外给一份不带 /v1 的。
    func testBaseCandidatesTrimSlashAndSplitV1() {
        let a = ImageGenClient.baseCandidates("https://api.example.com/v1/")
        XCTAssertTrue(a.contains("https://api.example.com/v1"))
        XCTAssertTrue(a.contains("https://api.example.com"))
    }

    /// 不带 /v1 的要额外给一份带 /v1 的（Kotlin 逐个试到不 404）。
    func testBaseCandidatesAppendV1() {
        let a = ImageGenClient.baseCandidates("https://api.example.com")
        XCTAssertTrue(a.contains("https://api.example.com"))
        XCTAssertTrue(a.contains("https://api.example.com/v1"))
    }

    func testBaseCandidatesEmpty() {
        XCTAssertTrue(ImageGenClient.baseCandidates("   ").isEmpty)
    }

    // MARK: - 错误信息

    func testErrorMessagesMatchKotlin() {
        XCTAssertEqual(ImageGenClient.ImageGenError.emptyPrompt.message, "生图描述为空")
        XCTAssertEqual(ImageGenClient.ImageGenError.noModel.message, "未配置生图模型")
        XCTAssertEqual(ImageGenClient.ImageGenError.noImageData.message, "接口未返回图片数据")
    }
}
