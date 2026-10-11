import GRDB
import XCTest

/// 角色编辑（第 212 轮）。
///
/// ## 这一套测试真正要锁的是什么
/// 不是"表单能存库"，而是**存进去的字段正好是引擎会读的那几个**。
/// Rust 侧 `to_profile` 只读 7 列（name/age/personality/speakingStyle/
/// backstory/rawPrompt/systemPrompt）—— 界面若把用户输入写到别的列，
/// 表现是"改了人设但模型没变"，而且界面上看不出任何异常。
/// 所以这里用 `ContextProbe.makeProfile` 把库里的行**按引擎的映射**重建一遍再断言。
@testable import YuNian
final class CompanionEditingTests: XCTestCase {

    private func makeRepo() throws -> (CompanionRepository, YuNianDatabase, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("companion_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let database = try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database"))
        return (CompanionRepository(database: database), database, dir)
    }

    // MARK: - 新建 / 编辑

    func testCreateThenEditReachesEngineVisibleFields() throws {
        let (repo, _, dir) = try makeRepo()
        defer { try? FileManager.default.removeItem(at: dir) }

        let id = try repo.create(name: "阿念", personality: "温柔", age: 22,
                                 backstory: "在杭州", speakingStyle: "轻声",
                                 avatarUrl: nil, tags: nil,
                                 rawPrompt: "喜欢猫", systemPrompt: "不要提搬家")
        var companion = try XCTUnwrap(try repo.fetch(id: id))

        // 引擎侧映射（与 native_gateway.rs::to_profile 逐字段对应）
        let profile = try XCTUnwrap(ContextProbe.makeProfile(companion, role: "GIRLFRIEND"))
        XCTAssertEqual(profile.name, "阿念")
        XCTAssertEqual(profile.age, 22)
        XCTAssertEqual(profile.personality, "温柔")
        XCTAssertEqual(profile.speakingStyle, "轻声")
        XCTAssertEqual(profile.backstory, "在杭州")
        XCTAssertEqual(profile.rawPrompt, "喜欢猫")
        XCTAssertEqual(profile.customPrompt, "不要提搬家", "systemPrompt → customPrompt")

        // 编辑后仍然逐项到达
        companion.personality = "外冷内热"
        companion.speakingStyle = "话少"
        try repo.update(companion)

        let edited = try XCTUnwrap(ContextProbe.makeProfile(try repo.fetch(id: id),
                                                            role: "GIRLFRIEND"))
        XCTAssertEqual(edited.personality, "外冷内热")
        XCTAssertEqual(edited.speakingStyle, "话少")
        XCTAssertEqual(edited.name, "阿念", "没动的字段不该被清掉")
    }

    /// ⚠️ 编辑**不动** `updatedAt`（与 Android 的 `@Update` 一致）：
    /// 否则改一次人设，列表顺序就会跳到最前。
    func testEditKeepsUpdatedAtSoListOrderDoesNotJump() throws {
        let (repo, _, dir) = try makeRepo()
        defer { try? FileManager.default.removeItem(at: dir) }

        let older = try repo.create(name: "先建的", personality: "A")
        _ = try secondWithDelay(repo)                       // 后建的 updatedAt 更大
        XCTAssertEqual(try repo.fetchAll().first?.name, "后建的", "最近更新在前")

        var first = try XCTUnwrap(try repo.fetch(id: older))
        first.personality = "改过"
        try repo.update(first)                              // 保留原 updatedAt

        XCTAssertEqual(try repo.fetchAll().first?.name, "后建的",
                       "编辑不该让列表顺序跳变")
        XCTAssertEqual(try repo.fetch(id: older)?.personality, "改过")
    }

    func testTouchRefreshesTimestampExplicitly() throws {
        let (repo, _, dir) = try makeRepo()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try repo.create(name: "甲", personality: "A")
        _ = try secondWithDelay(repo)

        XCTAssertTrue(try repo.touch(id: id, timestamp: Int64(Date().timeIntervalSince1970 * 1000) + 1000))
        XCTAssertEqual(try repo.fetchAll().first?.name, "甲", "显式 touch 才会置顶")
    }

    func testEmptyOptionalFieldsStayNil() throws {
        let (repo, _, dir) = try makeRepo()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try repo.create(name: "只有名字", personality: "")
        let companion = try XCTUnwrap(try repo.fetch(id: id))

        XCTAssertNil(companion.speakingStyle)
        XCTAssertNil(companion.backstory)
        XCTAssertNil(companion.rawPrompt)
        XCTAssertNil(companion.systemPrompt)
        XCTAssertNil(companion.age)

        let profile = try XCTUnwrap(ContextProbe.makeProfile(companion, role: "GIRLFRIEND"))
        XCTAssertNil(profile.age)
        XCTAssertNil(profile.speakingStyle)
        XCTAssertTrue(profile.personality.isEmpty)
    }

    /// 引擎读的列清单是**契约**：这份列表变了说明 Rust 侧读的字段变了，
    /// 界面上的表单要跟着改。
    func testRustReadColumnsContract() {
        XCTAssertEqual(CompanionRepository.rustReadColumns,
                       ["name", "age", "personality", "backstory",
                        "speakingStyle", "rawPrompt", "systemPrompt"])
    }

    // MARK: - 删除

    /// 只删角色：聊天记录**保留**（messages 表对 companions 没有外键）。
    func testDeleteCompanionKeepsMessagesByDefault() throws {
        let (repo, database, dir) = try makeRepo()
        defer { try? FileManager.default.removeItem(at: dir) }

        let id = try repo.create(name: "要删的", personality: "A")
        let messages = MessageRepository(database: database)
        _ = try messages.insert(conversationId: id, conversationType: .chat,
                                isFromUser: true, senderId: 0, type: "text",
                                content: "你好", searchContent: "你好")

        try repo.delete(id: id)
        XCTAssertNil(try repo.fetch(id: id))
        XCTAssertEqual(try messages.history(conversationId: id, type: .chat).count, 1,
                       "删角色默认不动聊天记录")
    }

    /// 连聊天记录一起删：必须先清消息再删角色（顺序反了会留下孤儿会话）。
    func testDeleteWithMessagesClearsConversationFirst() throws {
        let (repo, database, dir) = try makeRepo()
        defer { try? FileManager.default.removeItem(at: dir) }

        let id = try repo.create(name: "要清空的", personality: "A")
        let messages = MessageRepository(database: database)
        _ = try messages.insert(conversationId: id, conversationType: .chat,
                                isFromUser: true, senderId: 0, type: "text",
                                content: "会被清掉", searchContent: "会被清掉")

        try messages.deleteForConversation(conversationId: id, type: .chat)
        try repo.delete(id: id)

        XCTAssertNil(try repo.fetch(id: id))
        XCTAssertTrue(try messages.history(conversationId: id, type: .chat).isEmpty)
    }

    /// 删除内置默认角色会置 `deleted_by_user`，之后不再自动重建
    /// （对应 Android 的同名机制）—— 界面据此给出额外警告。
    func testDeletingDefaultCompanionMarksDeletedByUser() throws {
        let (repo, _, dir) = try makeRepo()
        defer { try? FileManager.default.removeItem(at: dir) }

        // 用播种器建出的**真**默认角色（而不是自己拼一份 —— 那样测的是我编的标签）
        let id = try XCTUnwrap(try DefaultCompanionSeeder.ensureDefault(repo))
        var companion = try XCTUnwrap(try repo.fetch(id: id))
        XCTAssertTrue(DefaultCompanionSeeder.isDefaultExperienceCompanion(companion),
                      "内置角色的标签要被识别出来（界面据此加警告）")

        try repo.delete(id: id)
        XCTAssertNil(try repo.fetch(id: id))

        companion.tags = nil
        XCTAssertFalse(DefaultCompanionSeeder.isDefaultExperienceCompanion(companion),
                       "清掉标签后不再被识别为默认角色")
    }

    // MARK: - 辅助

    /// 建第二个角色并确保 `updatedAt` 严格更大（毫秒可能撞上）。
    private func secondWithDelay(_ repo: CompanionRepository) throws -> Int64 {
        let id = try repo.create(name: "后建的", personality: "B")
        let bumped = (try repo.fetch(id: id)?.updatedAt ?? 0) + 1
        try repo.touch(id: id, timestamp: bumped)
        return id
    }
}
