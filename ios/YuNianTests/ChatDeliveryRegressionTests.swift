import XCTest
@testable import YuNian

/// Beta 1：流式回合与独立 bubble 事件交付回归。
final class ChatDeliveryRegressionTests: XCTestCase {
    func testBubbleWithNoFinalTextRemainsVisible() {
        let events: [(kind: String, text: String)] = [
            ("reasoning", "准备打招呼"), ("bubble", "你好呀"), ("sticker", "")
        ]
        XCTAssertEqual(ChatSession.visibleBubbleEventIndices(events: events, finalText: ""), [1])
    }

    func testOnlyTrailingFinalTextMirrorIsSkipped() {
        let events: [(kind: String, text: String)] = [
            ("bubble", "重复"), ("bubble", "重复"), ("usage", "{}"), ("bubble", "正文")
        ]
        XCTAssertEqual(ChatSession.visibleBubbleEventIndices(events: events, finalText: "正文"), [0, 1])
    }

    func testSinkSurvivesDoneAndErrorBeforeTurnEnd() async {
        let sink = AgentStreamSinkImpl()
        let stream = sink.makeStream()
        sink.onTextDelta(text: "第一轮")
        sink.onDone(fullText: "第一轮", finishReason: "tool_calls")
        sink.onError(error: "recoverable")
        sink.onTextDelta(text: "第二轮")
        sink.onDone(fullText: "第二轮", finishReason: "stop")
        sink.closeAll()

        var joinedText = ""
        var rounds = 0
        var errors = 0
        for await event in stream {
            switch event {
            case .textDelta(let chunk): joinedText += chunk
            case .done(_, _): rounds += 1
            case .error(_): errors += 1
            default: break
            }
        }
        XCTAssertEqual(joinedText, "第一轮第二轮")
        XCTAssertEqual(rounds, 2)
        XCTAssertEqual(errors, 1)
    }
}
