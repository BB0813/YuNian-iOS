import XCTest

@testable import YuNian

/// 上下文账本的解析层（第 229 轮）。
///
/// ## 这套测试锁什么
/// 账本是"取证面"：它的数字如果错了，用户对整件事的信任就没了。
/// 所以这里锁三类东西：
/// 1. **双版本兼容** —— 旧账本（v1 裸数组）必须能打开，且**不伪造**当时没记的通道；
/// 2. **两部分区的边界** —— 已送入 / 降级 / 本实现没有，三者不能混；
/// 3. **差异公式的符号与基数** —— 写反了就与任何外部对照都不可比。
final class ContextLedgerTests: XCTestCase {

    // MARK: - v2

    private let v2JSON = """
    {
      "v": 2,
      "channels": [
        {"id":"runtime_clock","cn":"请求时间","status":"admitted","sent":true,
         "tokens":57,"chars":40,"bytes":200,"items":1,"depth":"focused",
         "owner":"PromptOrchestrator","statusLine":"","fragmentCount":1},
        {"id":"system_prompt","cn":"系统指令","status":"admitted","sent":true,
         "tokens":482,"chars":300,"bytes":1687,"items":1,"depth":"full",
         "owner":"PromptOrchestrator","statusLine":"含引擎追加的指令","fragmentCount":2},
        {"id":"tool_schemas","cn":"工具 Schema","status":"admitted","sent":true,
         "tokens":1180,"chars":0,"bytes":4130,"items":10,"depth":"summary",
         "owner":"AgentRuntime","statusLine":"","fragmentCount":0},
        {"id":"worldbook","cn":"世界书","status":"recorded","sent":false,
         "tokens":0,"chars":0,"bytes":0,"items":3,"depth":"index",
         "owner":"LorebookRuntime","statusLine":"本次未发送",
         "reason":"skipped_no_trigger","severity":"normal",
         "reasonHint":"本轮没有命中，无需处理",
         "evidence":{"scope":"companion","entry_set":"a1b2","scan_depths":"2"},
         "fragmentCount":0},
        {"id":"world_node_activation","cn":"世界节点","status":"not_applicable","sent":false,
         "tokens":0,"chars":0,"bytes":0,"items":0,"depth":"summary","owner":"—",
         "statusLine":"本实现没有这条通道","reason":"not_applicable",
         "severity":"not_applicable","fragmentCount":0}
      ],
      "toolLedger": {
        "visible":["emit_bubble","send_sticker"],
        "hidden":[{"name":"generateImage","reason":"image_generation_unavailable",
                   "severity":"config"}],
        "perTool":[{"name":"send_sticker","group":"chat","tokens":420,"bytes":1470},
                   {"name":"emit_bubble","group":"chat","tokens":218,"bytes":763}],
        "schemaTokens":1180,"schemaBytes":4130
      },
      "localEstimate":{"tokens":1719,"chars":340,"bytes":6017,
                       "method":"bytes_per_token:3.5","isRoughEstimate":true,
                       "calibration":{"providerInputTokens":1620,"overestimatePercent":6.1}},
      "diff":{"percent":-5.76,"providerInputTokens":1620,"formula":"(provider−local)/local"}
    }
    """

    func testDecodesV2() throws {
        let ledger = try XCTUnwrap(ContextLedger.decode(fragmentsJson: v2JSON))
        XCTAssertEqual(ledger.version, 2)
        XCTAssertFalse(ledger.isLegacy)
        XCTAssertEqual(ledger.channels.count, 5)
        XCTAssertEqual(ledger.estimate.method, "bytes_per_token:3.5")
        XCTAssertTrue(ledger.estimate.isRoughEstimate, "估算永远不是分词器")
    }

    /// 三个分区必须互不重叠，且各自只装该装的。
    func testPartitionsAreDisjointAndCorrect() throws {
        let ledger = try XCTUnwrap(ContextLedger.decode(fragmentsJson: v2JSON))
        XCTAssertEqual(ledger.admitted.map(\.id).sorted(),
                       ["runtime_clock", "system_prompt", "tool_schemas"])
        // 降级区只装"没送 + 不是 not_applicable"
        XCTAssertEqual(ledger.degraded.map(\.id), ["worldbook"],
                       "not_applicable 不能混进降级区，否则用户以为丢了内容")
        XCTAssertEqual(ledger.notApplicable.map(\.id), ["world_node_activation"])
    }

    /// 降级项必须带齐三样：原因、三分类、证据；正常状态**不给修复路径**。
    func testDegradedItemCarriesEvidenceAndSeverity() throws {
        let ledger = try XCTUnwrap(ContextLedger.decode(fragmentsJson: v2JSON))
        let wb = try XCTUnwrap(ledger.degraded.first)
        XCTAssertEqual(wb.reason, .skippedNoTrigger)
        XCTAssertEqual(wb.severity, .normal, "本轮没命中是正常状态，不是故障")
        XCTAssertNil(wb.fixPath, "正常状态不该引导用户去改设置")
        XCTAssertEqual(wb.evidence["entry_set"], "a1b2", "要能复现：条目集指纹")
        XCTAssertEqual(wb.evidence["scan_depths"], "2")
        XCTAssertEqual(wb.statusLine, "本次未发送")
    }

    func testToolLedgerDecodes() throws {
        let ledger = try XCTUnwrap(ContextLedger.decode(fragmentsJson: v2JSON))
        XCTAssertEqual(ledger.toolLedger.visible.count, 2)
        XCTAssertEqual(ledger.toolLedger.perTool.first?.name, "send_sticker",
                       "逐工具成本按 token 降序，最贵的在前")
        XCTAssertEqual(ledger.toolLedger.hidden.first?.reason, .imageGenerationUnavailable)
        XCTAssertEqual(ledger.toolLedger.hidden.first?.severity, .config)
        XCTAssertEqual(ledger.toolLedger.schemaTokens, 1180)
    }

    /// ★ 这条是账本存在的第一个实际用途：算出工具占多少。
    /// Aru 是 91.8%，我们约 10 个工具 —— 这个比例决定"工具裁剪该不该做"。
    func testToolSchemaShare() throws {
        let ledger = try XCTUnwrap(ContextLedger.decode(fragmentsJson: v2JSON))
        // admitted: 57 + 482 + 1180 = 1719；工具 1180
        XCTAssertEqual(ledger.admittedTokens, 1719)
        XCTAssertEqual(ledger.toolSchemaShare, 1180.0 / 1719.0, accuracy: 0.0001)
    }

    // MARK: - v1 兼容

    /// 旧格式（裸数组）必须能打开，且按 `admitted` 处理。
    /// 依据：现有片段**确实全部真送进请求**，不是猜测。
    private let v1JSON = """
    [{"id":"env.static_meta","source":"env","layer":0,"lifetime":"stable","chars":40},
     {"id":"persona.identity","source":"persona","layer":1,"lifetime":"stable","chars":100},
     {"id":"safety.injection_note","source":"safety","layer":2,"lifetime":"stable","chars":60},
     {"id":"skill.chat_protocol","source":"skill","layer":4,"lifetime":"stable","chars":300},
     {"id":"skill.injected","source":"skill","layer":4,"lifetime":"task","chars":200},
     {"id":"memory.recall","source":"memory","layer":5,"lifetime":"task","chars":150},
     {"id":"tools.overview","source":"tools","layer":4,"lifetime":"stable","chars":90},
     {"id":"persona.reply_language","source":"persona","layer":1,"lifetime":"stable","chars":12}]
    """

    func testDecodesLegacyAsAllAdmitted() throws {
        let ledger = try XCTUnwrap(ContextLedger.decode(fragmentsJson: v1JSON))
        XCTAssertEqual(ledger.version, 1)
        XCTAssertTrue(ledger.isLegacy, "界面据此显示「旧记录」标记")
        XCTAssertEqual(ledger.degraded.count, 0, "旧记录没有降级信息，不能凭空造")
        XCTAssertTrue(ledger.channels.allSatisfy { $0.sent })
    }

    /// v1 的多个片段要按通道正确聚合（skill.* 三条 → 技能一条）。
    func testLegacyGroupsFragmentsIntoChannels() throws {
        let ledger = try XCTUnwrap(ContextLedger.decode(fragmentsJson: v1JSON))
        let ids = ledger.channels.map(\.id)
        XCTAssertTrue(ids.contains("skills"))
        // skill.chat_protocol(300) + skill.injected(200) 合成「技能」
        let skills = try XCTUnwrap(ledger.channels.first { $0.id == "skills" })
        XCTAssertEqual(skills.items, 2)
        XCTAssertEqual(skills.fragmentCount, 2)
        // persona.identity + persona.reply_language 合成「系统指令」
        let sys = try XCTUnwrap(ledger.channels.first { $0.id == "system_prompt" })
        XCTAssertEqual(sys.items, 2)
    }

    /// 旧记录**没有**工具账 —— 必须留空，而不是拿 `tools.overview` 冒充 schema 成本。
    ///
    /// 这是"倒填就是篡改历史"的具体体现：当时没量，就不该让用户以为量过。
    func testLegacyHasNoToolLedger() throws {
        let ledger = try XCTUnwrap(ContextLedger.decode(fragmentsJson: v1JSON))
        XCTAssertEqual(ledger.toolLedger.schemaTokens, 0)
        XCTAssertTrue(ledger.toolLedger.perTool.isEmpty)
        XCTAssertTrue(ledger.toolLedger.visible.isEmpty)
    }

    /// 未知片段 id 不能被丢掉（进「未登记片段」）。
    func testLegacyUnknownFragmentIsNotDropped() throws {
        let json = """
        [{"id":"brand.new.fragment","source":"x","layer":1,"lifetime":"stable","chars":10}]
        """
        let ledger = try XCTUnwrap(ContextLedger.decode(fragmentsJson: json))
        XCTAssertEqual(ledger.channels.count, 1)
        XCTAssertEqual(ledger.channels[0].id, "unmapped")
    }

    // MARK: - 差异公式（符号与基数）

    /// ★ 按 Aru 实测三轮验算的公式：`(供应商 − 本机) / 本机`。
    ///
    /// 写成 `(本机 − 供应商) / 供应商` 会得到正数，符号反、基数也不同。
    func testDiffFormulaMatchesVerifiedRound() throws {
        let diff = ContextLedger.Diff.compute(provider: 29198, local: 30877)
        XCTAssertEqual(try XCTUnwrap(diff.percent), -5.44, accuracy: 0.01)
        XCTAssertEqual(diff.percentText, "-5.4%")
    }

    func testDiffFormulaSecondRound() throws {
        let diff = ContextLedger.Diff.compute(provider: 30395, local: 31916)
        XCTAssertEqual(try XCTUnwrap(diff.percent), -4.77, accuracy: 0.01)
        XCTAssertEqual(diff.percentText, "-4.8%")
    }

    /// **无回执 → `percent` 必须是 nil**（界面据此整行不渲染）。
    /// 不是 0%（撒谎），也不是 `—%`（易被读成"未知但接近 0"）。
    func testNoReceiptMeansNoDiffRow() {
        let diff = ContextLedger.Diff.compute(provider: nil, local: 28512)
        XCTAssertNil(diff.percent)
        XCTAssertNil(diff.percentText, "界面靠 nil 判断「整行不渲染」")
        XCTAssertEqual(diff.formula, "(provider−local)/local", "公式写进数据")
    }

    /// 本机估算是 0 时不能算出 inf/nan（除零）。
    func testDiffGuardsAgainstZeroLocal() {
        let diff = ContextLedger.Diff.compute(provider: 100, local: 0)
        XCTAssertNil(diff.percent)
    }

    // MARK: - 坏输入

    func testBadInputReturnsNilInsteadOfCrashing() throws {
        for bad in ["", "{}", "not json", "[1,2,3]", "null"] {
            XCTAssertNil(ContextLedger.decode(fragmentsJson: bad),
                         "无法解析时返回 nil，由界面回落到旧展示：`\(bad)`")
        }
    }

    /// `{"v":2}` 但没有 channels —— 也要能解出空账本而不崩。
    func testEmptyV2DecodesToEmptyLedger() throws {
        let ledger = try XCTUnwrap(ContextLedger.decode(fragmentsJson: #"{"v":2}"#))
        XCTAssertTrue(ledger.channels.isEmpty)
        XCTAssertEqual(ledger.toolSchemaShare, 0, "无数据时不能除零")
    }
}
