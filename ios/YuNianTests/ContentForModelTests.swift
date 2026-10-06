import XCTest

/// 图片消息回喂模型的正文构造（`ChatSession.contentForModel`）。
///
/// 权威来源：`feature/chat/.../ui/viewmodel/ChatTypeConverters.kt:37-43`
/// 与其测试 `ImageGenTriggerTest.kt:438-459`。
@testable import YuNian
final class ContentForModelTests: XCTestCase {

    private func imageMessage(_ prompt: String?) -> ChatSession.Message {
        ChatSession.Message(role: .assistant, text: "[图片]", isSticker: false,
                            imageData: Data([0x1, 0x2]), imagePrompt: prompt)
    }

    // MARK: - 图片消息

    /// Kotlin Test:438-459 —— 有 searchContent 时附加系统注记。
    ///
    /// ⚠️ 这里**逐字**校验 Kotlin 的措辞。不是写个差不多的字符串 ——
    /// Kotlin 注释（ChatTypeConverters.kt:33-35）记着：措辞只要松一点，
    /// 模型就可能把画面描述抄进回复，泄漏成独立文本气泡（BUG-1）。
    func testImageMessageAttachesSystemNote() {
        let out = ChatSession.contentForModel(imageMessage("赛博朋克城市夜景"))
        XCTAssertEqual(
            out,
            "[图片]（系统注记：该图画面描述为 赛博朋克城市夜景；"
                + "此注记仅用于你理解图片内容，禁止在回复中输出任何「画面：」或括号包裹的画面描述）"
        )
    }

    /// Kotlin Test:461-468 —— 无 searchContent 的图片消息 → 原样 "[图片]"
    func testImageMessageWithoutPromptStaysRaw() {
        XCTAssertEqual(ChatSession.contentForModel(imageMessage(nil)), "[图片]")
        XCTAssertEqual(ChatSession.contentForModel(imageMessage("")), "[图片]")
        // 纯空白也算空（Kotlin 的 isNotBlank()）
        XCTAssertEqual(ChatSession.contentForModel(imageMessage("   ")), "[图片]")
    }

    /// 注记以 content 开头且含关键措辞（防将来被人"简化"掉）
    func testNoteContainsForbiddenImitationWording() {
        let out = ChatSession.contentForModel(imageMessage("猫"))
        XCTAssertTrue(out.hasPrefix("[图片]"), "content 本身必须在前")
        XCTAssertTrue(out.contains("系统注记"), "必须有系统注记标识")
        XCTAssertTrue(out.contains("禁止在回复中输出"), "必须有显式禁令 —— BUG-1 的教训")
    }

    // MARK: - 非图片消息

    /// Kotlin Test:461-468 —— 非图片消息 → 原 content
    func testTextMessageUnchanged() {
        let m = ChatSession.Message(role: .user, text: "今天天气不错")
        XCTAssertEqual(ChatSession.contentForModel(m), "今天天气不错")
    }

    /// 表情消息也不受影响
    func testStickerMessageUnchanged() {
        let m = ChatSession.Message(role: .assistant, text: "[123]", isSticker: true)
        XCTAssertEqual(ChatSession.contentForModel(m), "[123]")
    }

    /// 纯空白文本保持原样（不视为图片消息）
    func testBlankTextMessageUnchanged() {
        let m = ChatSession.Message(role: .user, text: "   ")
        XCTAssertEqual(ChatSession.contentForModel(m), "   ")
    }
}
