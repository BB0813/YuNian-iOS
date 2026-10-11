import GRDB
import XCTest

/// 回复语言（第 218 轮）。
///
/// ## 这套测试要挡住的三类错
/// 1. **默认值把行为改了**：`跟随模型` 必须**不下发** `reply_language` 键 ——
///    下发空串会让"宿主没配"与"配了空"在引擎侧无法区分，也改变了 Android 既有行为。
/// 2. **存取漂移**：写进去的值读不回来（或读到不认识的旧值时乱猜）。
/// 3. **码与文案不一致**：界面显示"简体中文"，下发的却是 `zh-Hans` 之类 ——
///    所以这里断言下发的是**语言名**，且与选项标题一致。
@testable import YuNian
final class ReplyLanguageTests: XCTestCase {

    private func makeRepo() throws -> (ApiConfigRepository, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("replylang_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let database = try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database"))
        return (ApiConfigRepository(database: database), dir)
    }

    // MARK: - 默认值：不下发

    func testDefaultIsFollowModelAndSendsNothing() throws {
        let (repo, dir) = try makeRepo()
        defer { try? FileManager.default.removeItem(at: dir) }

        let value = ReplyLanguage.load(from: repo)
        XCTAssertEqual(value, .followModel)
        XCTAssertNil(value.languageName, "跟随模型 = 不下发语言名（不是下发空串）")
    }

    /// 缺键与"值不可识别"都要回落到跟随模型 —— 不能猜。
    func testUnknownStoredValueFallsBackToFollowModel() throws {
        let (repo, dir) = try makeRepo()
        defer { try? FileManager.default.removeItem(at: dir) }

        try repo.setMetaValue("klingon", forKey: ReplyLanguage.storageKey)
        XCTAssertEqual(ReplyLanguage.load(from: repo), .followModel)
    }

    // MARK: - 存取往返

    func testRoundTripForEveryOption() throws {
        let (repo, dir) = try makeRepo()
        defer { try? FileManager.default.removeItem(at: dir) }

        for option in ReplyLanguage.allCases {
            try option.save(to: repo)
            XCTAssertEqual(ReplyLanguage.load(from: repo), option, "\(option.title) 存取往返失败")
        }
    }

    // MARK: - 下发内容与文案一致

    func testLanguageNameMatchesTitleForEveryOption() {
        for option in ReplyLanguage.allCases {
            if let name = option.languageName {
                XCTAssertEqual(name, option.title,
                               "下发的是语言名，应与界面标题一致：\(option.rawValue)")
            }
        }
        XCTAssertEqual(ReplyLanguage.simplifiedChinese.languageName, "简体中文")
        XCTAssertEqual(ReplyLanguage.traditionalChinese.languageName, "繁體中文", "繁体用字要一致")
        XCTAssertEqual(ReplyLanguage.english.languageName, "English")
        XCTAssertEqual(ReplyLanguage.japanese.languageName, "日本語")
    }

    // MARK: - 进 settings.json 的那一步

    /// 引擎读的是 `settings_json.reply_language`：
    /// 键名必须**逐字**是 `reply_language`，值必须是语言名。
    func testSettingsJSONUsesSnakeCaseKeyAndLanguageName() throws {
        let settings = AgentSettings(replyLanguage: "简体中文")
        let data = try JSONEncoder().encode(settings)
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])

        XCTAssertEqual(json["reply_language"] as? String, "简体中文")
        XCTAssertNil(json["replyLanguage"], "不能把 Swift 属性名原样发出去")
    }

    /// 跟随模型时**这个键根本不出现**（而不是出现且为空）。
    func testFollowModelOmitsTheKeyEntirely() throws {
        let settings = AgentSettings(replyLanguage: nil)
        let data = try JSONEncoder().encode(settings)
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertNil(json["reply_language"])
    }

    /// 仓储不可用时（老库/未就绪）不能崩，也不能乱设。
    func testStorageKeyIsNamespaced() {
        XCTAssertEqual(ReplyLanguage.storageKey, "chat.reply_language")
        XCTAssertTrue(ReplyLanguage.storageKey.hasPrefix("chat."),
                      "与 feature_route.* / context.* 同级命名，避免和别的键撞")
    }

    // MARK: - 收据里的那一条（界面即取证面）

    private func segments() -> [TurnReceipt.Segment] {
        [TurnReceipt.Segment(id: "env.info", source: "env", layer: 0,
                              lifetime: "dynamic", chars: 10),
         TurnReceipt.Segment(id: "persona.identity", source: "persona", layer: 1,
                              lifetime: "stable", chars: 100),
         TurnReceipt.Segment(id: "safety.injection_note", source: "safety", layer: 2,
                              lifetime: "stable", chars: 20)]
    }

    /// 引擎把【回复语言】追加在【角色设定】之后 —— 收据里的顺序必须一致。
    func testLanguageSegmentIsInsertedRightAfterPersona() {
        let result = TurnReceipt.insertingLanguageSegment(segments(), language: "简体中文")
        XCTAssertEqual(result.map(\.id),
                       ["env.info", "persona.identity", "persona.reply_language",
                        "safety.injection_note"])
    }

    /// 没配就**不许**多出一条（与引擎 `reply_language_suffix` 同规则）。
    func testNoLanguageSegmentWhenUnsetOrBlank() {
        for value in [nil, "", "   ", "\n"] as [String?] {
            let result = TurnReceipt.insertingLanguageSegment(segments(), language: value)
            XCTAssertEqual(result.map(\.id), segments().map(\.id),
                           "value=\(value ?? "nil") 不该插入片段")
        }
    }

    /// 找不到 persona 片段时追加到末尾，而不是直接丢掉 ——
    /// 收据宁可多显示一条，也不能漏掉"确实下发了语言指令"。
    func testLanguageSegmentFallsBackToAppend() {
        let only = [TurnReceipt.Segment(id: "env.info", source: "env", layer: 0,
                                        lifetime: "dynamic", chars: 5)]
        let result = TurnReceipt.insertingLanguageSegment(only, language: "English")
        XCTAssertEqual(result.map(\.id), ["env.info", "persona.reply_language"])
    }
}
