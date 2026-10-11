import GRDB
import XCTest

/// 技能页（第 209 轮）：导入路径 + 启用开关 + 正文读取。
///
/// 锁的是这条链上**最容易断**的三处：
/// 写入必须走 `AgentStores`（索引与文件一起动）、`enabled` 必须真的落库
/// （引擎按它过滤）、正文哈希不一致时**界面要能看出来**
/// （引擎会因此拒绝加载，表现是模型说「未找到技能」）。
@testable import YuNian
final class SkillLibraryTests: XCTestCase {

    private func makeStores() throws -> (AgentStores, YuNianDatabase, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("skill_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let database = try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database"))
        return (AgentStores(database: database), database, dir)
    }

    private static let sample = """
    ---
    name: 每日复盘
    description: 用户说「复盘今天」时按这个结构来。
    ---

    # 每日复盘

    1. 先问今天最想记住的一件事。
    """

    // MARK: - 导入（走 AgentStores，索引与文件一起落）

    func testImportWritesRowAndFile() throws {
        let (stores, database, dir) = try makeStores()
        defer { try? FileManager.default.removeItem(at: dir) }

        let version = stores.saveSkill(metaJson: #"{"skill_id":"daily_review","category":"PRODUCTIVITY","tags":"复盘,日记"}"#,
                                       content: Self.sample)
        XCTAssertEqual(version, 1, "新建技能 version 从 1 开始")

        let skills = SkillRepository(database: database).listAll()
        XCTAssertEqual(skills.count, 1)
        let skill = try XCTUnwrap(skills.first)
        XCTAssertEqual(skill.skillId, "daily_review")
        XCTAssertEqual(skill.name, "每日复盘", "名称优先取 frontmatter")
        XCTAssertEqual(skill.description, "用户说「复盘今天」时按这个结构来。")
        XCTAssertEqual(skill.category, "PRODUCTIVITY")
        XCTAssertEqual(skill.tagList, ["复盘", "日记"])
        XCTAssertTrue(skill.enabled, "默认启用")
        XCTAssertNil(skill.companionId, "没有 companion_id = 全局技能")

        // 正文文件真的写出来了，并且哈希与索引一致（引擎就是按它校验的）
        let content = try XCTUnwrap(SkillRepository(database: database).content(for: skill))
        XCTAssertTrue(content.hashMatches)
        XCTAssertTrue(content.text.contains("# 每日复盘"))
    }

    /// 同名 id 再导入 → **version+1**，不是插两行。
    func testReimportBumpsVersion() throws {
        let (stores, database, dir) = try makeStores()
        defer { try? FileManager.default.removeItem(at: dir) }

        _ = stores.saveSkill(metaJson: #"{"skill_id":"x"}"#, content: Self.sample)
        let second = stores.saveSkill(metaJson: #"{"skill_id":"x"}"#, content: Self.sample + "\n5. 收尾。")
        XCTAssertEqual(second, 2)
        XCTAssertEqual(SkillRepository(database: database).listAll().count, 1)
    }

    /// 不给 skill_id → 生成 UUID（不是失败）。
    func testMissingSkillIdGeneratesOne() throws {
        let (stores, database, dir) = try makeStores()
        defer { try? FileManager.default.removeItem(at: dir) }

        let version = stores.saveSkill(metaJson: "{}", content: Self.sample)
        XCTAssertEqual(version, 1)
        let skill = try XCTUnwrap(SkillRepository(database: database).listAll().first)
        XCTAssertFalse(skill.skillId.isEmpty)
        XCTAssertEqual(skill.name, "每日复盘")
    }

    /// 分类不在白名单 → 回落 `CUSTOM`（不是空串）。
    func testUnknownCategoryFallsBackToCustom() throws {
        let (stores, database, dir) = try makeStores()
        defer { try? FileManager.default.removeItem(at: dir) }
        _ = stores.saveSkill(metaJson: #"{"skill_id":"y","category":"随便写的"}"#, content: Self.sample)
        XCTAssertEqual(SkillRepository(database: database).listAll().first?.category, "CUSTOM")
    }

    // MARK: - 启用开关

    func testEnabledTogglePersistsAndFlipsCounts() throws {
        let (stores, database, dir) = try makeStores()
        defer { try? FileManager.default.removeItem(at: dir) }
        _ = stores.saveSkill(metaJson: #"{"skill_id":"a"}"#, content: Self.sample)

        let repo = SkillRepository(database: database)
        XCTAssertEqual(repo.counts().enabled, 1)

        XCTAssertTrue(repo.setEnabled(false, skillId: "a"))
        XCTAssertEqual(repo.counts().enabled, 0)
        XCTAssertEqual(repo.counts().disabled, 1)
        XCTAssertFalse(try XCTUnwrap(repo.listAll().first).enabled)

        XCTAssertTrue(repo.setEnabled(true, skillId: "a"))
        XCTAssertTrue(try XCTUnwrap(repo.listAll().first).enabled)
    }

    /// 关掉开关**不影响** version（它只是治理位，不是内容变更）。
    func testToggleDoesNotBumpVersion() throws {
        let (stores, database, dir) = try makeStores()
        defer { try? FileManager.default.removeItem(at: dir) }
        _ = stores.saveSkill(metaJson: #"{"skill_id":"a"}"#, content: Self.sample)

        let repo = SkillRepository(database: database)
        _ = repo.setEnabled(false, skillId: "a")
        XCTAssertEqual(repo.listAll().first?.version, 1)
    }

    func testSetEnabledOnMissingSkillReturnsFalse() throws {
        let (_, database, dir) = try makeStores()
        defer { try? FileManager.default.removeItem(at: dir) }
        XCTAssertFalse(SkillRepository(database: database).setEnabled(true, skillId: "不存在"))
    }

    // MARK: - 正文与校验

    /// 正文被外部改过（哈希不一致）→ 界面必须能看出来。
    /// 引擎此时会拒绝加载，用户看到的是模型说「未找到技能」。
    func testHashMismatchIsDetected() throws {
        let (stores, database, dir) = try makeStores()
        defer { try? FileManager.default.removeItem(at: dir) }
        _ = stores.saveSkill(metaJson: #"{"skill_id":"a"}"#, content: Self.sample)

        let repo = SkillRepository(database: database)
        let skill = try XCTUnwrap(repo.listAll().first)
        let url = try XCTUnwrap(skill.contentURL())
        try "被改过的正文".write(to: url, atomically: true, encoding: .utf8)

        let content = try XCTUnwrap(repo.content(for: skill))
        XCTAssertFalse(content.hashMatches, "哈希不一致必须报出来")
        XCTAssertEqual(content.text, "被改过的正文")
    }

    /// 读正文**不看 enabled**：已禁用的技能也要能预览。
    func testContentReadableWhenDisabled() throws {
        let (stores, database, dir) = try makeStores()
        defer { try? FileManager.default.removeItem(at: dir) }
        _ = stores.saveSkill(metaJson: #"{"skill_id":"a"}"#, content: Self.sample)

        let repo = SkillRepository(database: database)
        _ = repo.setEnabled(false, skillId: "a")
        let skill = try XCTUnwrap(repo.listAll().first)
        XCTAssertNotNil(repo.content(for: skill), "禁用只是治理位，正文还在")
    }

    /// 正文文件缺失 → nil（界面显示"读不到"，而不是显示空白正文假装正常）。
    func testMissingContentFileReturnsNil() throws {
        let (stores, database, dir) = try makeStores()
        defer { try? FileManager.default.removeItem(at: dir) }
        _ = stores.saveSkill(metaJson: #"{"skill_id":"a"}"#, content: Self.sample)

        let repo = SkillRepository(database: database)
        let skill = try XCTUnwrap(repo.listAll().first)
        try FileManager.default.removeItem(at: try XCTUnwrap(skill.contentURL()))
        XCTAssertNil(repo.content(for: skill))
    }

    // MARK: - 可见性与删除

    /// 显式传 companionId 才不会再出现"越权可见"：
    /// 全局技能 + 该角色私有技能可见，别的角色的私有技能不可见。
    func testVisibleToCompanionExcludesOtherCompanionsPrivateSkills() throws {
        let (stores, database, dir) = try makeStores()
        defer { try? FileManager.default.removeItem(at: dir) }

        _ = stores.saveSkill(metaJson: #"{"skill_id":"global_skill"}"#, content: Self.sample)
        _ = stores.saveSkill(metaJson: #"{"skill_id":"mine","companion_id":7}"#, content: Self.sample)
        _ = stores.saveSkill(metaJson: #"{"skill_id":"theirs","companion_id":9}"#, content: Self.sample)

        let repo = SkillRepository(database: database)
        XCTAssertEqual(repo.listAll().count, 3, "listAll 不过滤（界面要标归属）")
        let visible = repo.listVisible(to: 7).map(\.skillId).sorted()
        XCTAssertEqual(visible, ["global_skill", "mine"])
    }

    /// 删除走 `AgentStores`：索引与正文文件**一起**消失。
    func testDeleteRemovesRowAndFile() throws {
        let (stores, database, dir) = try makeStores()
        defer { try? FileManager.default.removeItem(at: dir) }
        _ = stores.saveSkill(metaJson: #"{"skill_id":"a"}"#, content: Self.sample)
        let repo = SkillRepository(database: database)
        let skill = try XCTUnwrap(repo.listAll().first)
        let url = try XCTUnwrap(skill.contentURL())
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))

        XCTAssertTrue(stores.deleteSkill(skillId: "a"))
        XCTAssertTrue(repo.listAll().isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path),
                       "正文文件也要删掉 —— 留孤儿文件会让下次同 id 导入哈希对不上")
    }

    // MARK: - 列表排序

    func testListOrderPutsEnabledFirst() throws {
        let (stores, database, dir) = try makeStores()
        defer { try? FileManager.default.removeItem(at: dir) }
        _ = stores.saveSkill(metaJson: #"{"skill_id":"on"}"#, content: Self.sample)
        _ = stores.saveSkill(metaJson: #"{"skill_id":"off"}"#, content: Self.sample)
        let repo = SkillRepository(database: database)
        _ = repo.setEnabled(false, skillId: "off")

        // 只断言"启用的排前面"：两个技能可能落在同一毫秒，updatedAt 排序不保证稳定
        XCTAssertEqual(repo.listAll().first?.skillId, "on")
    }
}
