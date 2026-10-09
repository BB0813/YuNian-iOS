import XCTest

/// 聊天回复交付与流式生命周期回归（第 202 轮）。
///
/// 覆盖三类此前确认的缺陷：
/// 1. **bubble 事件被忽略** —— `CHAT` 只会落 sticker，模型走
///    `emit_bubble` / `emit_segmented` 协议时没有任何可见回复；
/// 2. **流式订阅提前终止** —— `onDone` 只代表一次 HTTP 响应完成，
///    一个回合可有多轮，提前 `closeAll()` 会丢掉后续轮次；
/// 3. **重复交付** —— 流式收尾文本与事件里的规范收尾气泡同文，
///    修复前会被提交两次。
///
/// 这里全部使用合成事件与受控 fake 回调，**不依赖真实 API Key / LLM**。
@testable import YuNian
final class ChatResponseDeliveryTests: XCTestCase {

    // MARK: - StreamSink 生命周期

    /// 每个 HTTP 轮的 `onDone` 之后订阅必须仍然存活：
    /// 多轮回合的第二轮增量不能再被丢弃（修复前 `onDone` 里 closeAll）。
    func testOnDoneDoesNotTerminateSubscription() async {
        let sink = AgentStreamSinkImpl()
        let stream = sink.makeStream()

        sink.onTextDelta(text: "第一轮")
        sink.onDone(fullText: "第一轮", finishReason: "stop")
        sink.onTextDelta(text: "第二轮")
        sink.onDone(fullText: "第二轮", finishReason: "stop")
        sink.closeAll()

        let events = await collect(stream)
        XCTAssertEqual(events, [
            "delta:第一轮", "done:第一轮:stop",
            "delta:第二轮", "done:第二轮:stop",
        ], "中间 onDone 不得终止订阅，否则后续轮次丢失")
    }

    /// `onError` 可能是传输层瞬时错误（Rust 随后仍可能重试并继续），
    /// 因此不得关闭订阅，且错误事件本身要能被消费者看到。
    func testOnErrorDoesNotTerminateSubscriptionAndStaysObservable() async {
        let sink = AgentStreamSinkImpl()
        let stream = sink.makeStream()

        sink.onError(error: "瞬时传输错误")
        sink.onTextDelta(text: "重试成功")
        sink.closeAll()

        let events = await collect(stream)
        XCTAssertEqual(events, ["error:瞬时传输错误", "delta:重试成功"],
                       "错误必须可见，但不得终止整个回合的观察")
    }

    /// 顺序两个回合（`closeAll` 收束第一个后再订阅第二个）不得互相串事件。
    func testSequentialSubscriptionsDoNotMix() async {
        let sink = AgentStreamSinkImpl()

        let first = sink.makeStream()
        sink.onTextDelta(text: "第一回合")
        sink.closeAll()
        let firstEvents = await collect(first)

        let second = sink.makeStream()
        sink.onTextDelta(text: "第二回合")
        sink.closeAll()
        let secondEvents = await collect(second)

        XCTAssertEqual(firstEvents, ["delta:第一回合"])
        XCTAssertEqual(secondEvents, ["delta:第二回合"])
    }

    /// 取消/终止后到达的迟到回调必须安全（不得崩溃、不得进入已关闭订阅）。
    func testCallbacksAfterCloseAreDroppedSafely() async {
        let sink = AgentStreamSinkImpl()
        let stream = sink.makeStream()

        sink.onTextDelta(text: "已交付")
        sink.closeAll()
        sink.onTextDelta(text: "关闭后")
        sink.onDone(fullText: "关闭后", finishReason: "stop")
        sink.onError(error: "关闭后")

        let events = await collect(stream)
        XCTAssertEqual(events, ["delta:已交付"], "关闭后的回调不得再进入消费者")
    }

    // MARK: - 纯文本 SSE

    /// 普通 SSE 回复：流式提交 + 事件里的同文收尾气泡
    /// → **恰好一条**可见 assistant 回复，不重复。
    func testPlainStreamAndFinalResultDoNotDuplicate() {
        let texts = deliveredTexts(
            roundTexts: ["你好呀"],
            events: [event("bubble", "你好呀")],
            finalText: "你好呀"
        )
        XCTAssertEqual(texts, ["你好呀"])
    }

    /// 事件里的收尾气泡在流式未交付时仍要落地（防丢回复兜底）。
    func testTrailingFinalBubbleDeliveredWhenStreamMissing() {
        let texts = deliveredTexts(
            roundTexts: [],
            events: [event("bubble", "由事件交付")],
            finalText: "由事件交付"
        )
        XCTAssertEqual(texts, ["由事件交付"])
    }

    /// 完全没有任何交付来源时，最终文本兜底补一条。
    func testFinalTextFallbackFillsGenuinelyMissingOutput() {
        let texts = deliveredTexts(
            roundTexts: [],
            events: [],
            finalText: "兜底回复"
        )
        XCTAssertEqual(texts, ["兜底回复"])
    }

    // MARK: - bubble 事件

    /// `emit_bubble` 且 `finalText` 为空（iOS 最常见的协议路径）
    /// → 必须产生可见回复。
    func testEmitBubbleWithEmptyFinalTextProducesVisibleReply() {
        let texts = deliveredTexts(
            roundTexts: [],
            events: [event("bubble", "在你身边呢")],
            finalText: ""
        )
        XCTAssertEqual(texts, ["在你身边呢"])
    }

    /// `emit_segmented` → 多条气泡按事件顺序落地。
    func testEmitSegmentedProducesOrderedBubbles() {
        let texts = deliveredTexts(
            roundTexts: [],
            events: [
                event("bubble", "第一句"),
                event("bubble", "第二句"),
                event("bubble", "第三句"),
            ],
            finalText: ""
        )
        XCTAssertEqual(texts, ["第一句", "第二句", "第三句"])
    }

    /// 工具生成的气泡 + 随后的正常文本都要保留，且最终文本排在事件之后。
    func testToolBubblesAndSubsequentFinalTextBothPreserved() {
        let texts = deliveredTexts(
            roundTexts: ["好的，我看看"],
            events: [
                event("bubble", "先查一下"),
                event("bubble", "好的，我看看"),
            ],
            finalText: "好的，我看看"
        )
        XCTAssertEqual(texts, ["先查一下", "好的，我看看"],
                       "工具气泡在前，收尾文本在后且只出现一次")
    }

    /// 两条**文本相同**的有意气泡（emit_bubble 连发）必须保持两条，
    /// 不能用文本集合去重把独立消息吞掉；同时流式收尾的那条不重复。
    func testIdenticalIntentionalBubblesRemainSeparate() {
        let texts = deliveredTexts(
            roundTexts: ["嗯"],
            events: [
                event("bubble", "嗯"),
                event("bubble", "嗯"),
            ],
            finalText: "嗯"
        )
        XCTAssertEqual(texts, ["嗯", "嗯"],
                       "首条有意气泡 + 最终文本各一条，均不得被吞")
    }

    /// 空文本 bubble 事件不得产生空气泡。
    func testEmptyBubbleEventsDoNotCreateEmptyMessages() {
        let plan = ChatSession.planTurnDelivery(
            events: [event("bubble", "")],
            finalText: "",
            lastRoundCommittedText: nil,
            partialText: ""
        )
        XCTAssertTrue(plan.items.isEmpty)
        XCTAssertFalse(plan.hasVisibleOutput)
    }

    // MARK: - sticker 事件（既有行为）

    /// sticker 事件仍按 `entry_id=...` KV 解析并按事件顺序落地，行为不变。
    func testStickerEventParsingAndOrderUnchanged() {
        let plan = ChatSession.planTurnDelivery(
            events: [
                event("bubble", "抱抱"),
                event("sticker", "", extra: "entry_id=42;file_name=custom_1.png"),
                event("bubble", "晚安"),
            ],
            finalText: "晚安",
            lastRoundCommittedText: "晚安",
            partialText: ""
        )
        XCTAssertEqual(plan.items, [
            .bubble("抱抱"),
            .sticker(entryId: "42"),
        ])
        XCTAssertTrue(plan.moveFinalTextToEnd)
    }

    /// 缺 `entry_id` 的 sticker 事件跳过（与旧实现一致）。
    func testStickerWithoutEntryIdIsSkipped() {
        let plan = ChatSession.planTurnDelivery(
            events: [event("sticker", "", extra: "file_name=x.png")],
            finalText: "",
            lastRoundCommittedText: nil,
            partialText: ""
        )
        XCTAssertTrue(plan.items.isEmpty)
    }

    // MARK: - 错误 / 残文

    /// 部分输出后出错：已产出的气泡与流式残文都要保留（残文排最后）。
    func testPartialOutputOnErrorIsPreserved() {
        let texts = deliveredTexts(
            roundTexts: [],
            events: [event("bubble", "已经发出的半句")],
            finalText: "",
            partialText: "正在生成的残句"
        )
        XCTAssertEqual(texts, ["已经发出的半句", "正在生成的残句"])
    }

    /// 有 `finalText` 时流式残文不另补 —— 否则会制造重复前缀。
    func testPartialContainedInFinalTextIsNotDuplicated() {
        let plan = ChatSession.planTurnDelivery(
            events: [],
            finalText: "完整回复",
            lastRoundCommittedText: nil,
            partialText: "完整"
        )
        XCTAssertEqual(plan.fallbackFinalText, "完整回复")
        XCTAssertNil(plan.partialText)
    }

    /// 回合无任何可见输出（confirm_pending / state_stop 等）时，
    /// 必须给出可读说明，不能让等待指示无声消失。
    func testNoVisibleOutputProducesReadableExplanation() {
        for reason in ["confirm_pending", "state_stop", "completed"] {
            let message = ChatSession.emptyTurnMessage(finishedReason: reason)
            XCTAssertFalse(message.isEmpty, "\(reason) 必须有可读说明")
        }
        XCTAssertTrue(ChatSession.emptyTurnMessage(finishedReason: "confirm_pending")
            .contains("确认"))
    }

    /// 只有 sticker 的回合算"有可见输出"，不应误报无回复。
    func testStickerOnlyTurnCountsAsVisibleOutput() {
        let plan = ChatSession.planTurnDelivery(
            events: [event("sticker", "", extra: "entry_id=7;file_name=s.png")],
            finalText: "",
            lastRoundCommittedText: nil,
            partialText: ""
        )
        XCTAssertTrue(plan.hasVisibleOutput)
    }

    /// usage / reasoning 等非可见事件不产生消息，也不误判为可见输出。
    func testNonVisibleEventsProduceNoMessages() {
        let plan = ChatSession.planTurnDelivery(
            events: [
                event("usage", #"{"total_tokens":12}"#),
                event("reasoning", "思考内容"),
                event("status", "N/M"),
            ],
            finalText: "",
            lastRoundCommittedText: nil,
            partialText: ""
        )
        XCTAssertTrue(plan.items.isEmpty)
        XCTAssertFalse(plan.hasVisibleOutput)
        XCTAssertNil(plan.fallbackFinalText)
    }

    // MARK: - 辅助

    private func event(_ kind: String, _ text: String,
                       extra: String = "") -> (kind: String, text: String, extra: String) {
        (kind: kind, text: text, extra: extra)
    }

    /// 模拟 `ChatSession.applyEvents` 对交付计划的落地，返回最终文本序列。
    ///
    /// 只镜像"正常路径"的清单：事件项 → 兜底 → 残文 → 被重排的最终文本。
    /// 决策本身全部来自被测的 `planTurnDelivery`。
    private func deliveredTexts(
        roundTexts: [String],
        events: [(kind: String, text: String, extra: String)],
        finalText: String,
        partialText: String = ""
    ) -> [String] {
        let plan = ChatSession.planTurnDelivery(
            events: events,
            finalText: finalText,
            lastRoundCommittedText: roundTexts.last,
            partialText: partialText
        )
        var out = roundTexts
        var held: String?
        if plan.moveFinalTextToEnd {
            held = out.popLast()
        }
        for item in plan.items {
            if case .bubble(let text) = item { out.append(text) }
        }
        if let fallback = plan.fallbackFinalText { out.append(fallback) }
        if let partial = plan.partialText { out.append(partial) }
        if let held { out.append(held) }
        return out
    }

    /// 把一次订阅的全部事件转成可断言的字符串序列；流结束（closeAll）后返回。
    private func collect(_ stream: AsyncStream<AgentStreamSinkImpl.Event>) async -> [String] {
        var out: [String] = []
        for await event in stream {
            switch event {
            case .textDelta(let text):
                out.append("delta:\(text)")
            case .reasoningDelta(let text):
                out.append("reasoning:\(text)")
            case .done(let fullText, let finishReason):
                out.append("done:\(fullText):\(finishReason)")
            case .error(let message):
                out.append("error:\(message)")
            }
        }
        return out
    }
}
