import Foundation
import GRDB
import os

/// 技能仓储（读侧 + 启用开关）。
///
/// ## 为什么 iOS 之前"有技能表、没有技能页"
/// 表 `agent_skills` 一直在 schema 里，`AgentStores` 也实现了
/// `listSkills` / `saveSkill` / `deleteSkill`（Rust 的 `SkillStore` 协议）——
/// 但**没有任何界面调用过它们**，也没有任何播种/导入路径。
/// 结果是：iOS 用户的技能表恒为空，`load_skill` 永远返回「未找到技能」，
/// 而 `prompt_orchestrator` 的技能目录菜单也就永远是空的。
///
/// 这一页补的就是这条断链：**看得到、能导入、能开关**。
///
/// ## ⚠️ 两个已核实的坑
/// 1. `AgentStores.listSkills(companionId: nil)` 的 SQL 是
///    `WHERE companionId IS NULL OR companionId = ?` —— 传 nil 会把
///    **各伴侣的私有技能一并返回**（代码里标为"越权可见"）。
///    所以这一页要么显式传角色 id，要么在界面里把归属标出来；这里选后者
///    （列表显示归属，不假装它们都属于当前角色）。
/// 2. **写（保存/删除）必须走 `AgentStores`**，不要自己写 SQL：
///    它负责"先写文件再写索引"的次序与 `contentPath` 相对路径约定。
///    只有 `enabled` 开关自己 UPDATE —— 协议里**没有** setEnabled，
///    而用 `saveSkill` 改开关会把 version 无谓地 +1。
struct SkillRepository {

    private let database: YuNianDatabase
    private let log = Logger(subsystem: "com.yunian.ai", category: "repo.skill")

    init(database: YuNianDatabase) {
        self.database = database
    }

    struct Skill: Sendable, Equatable, Identifiable {
        var id: Int64
        var skillId: String
        var name: String
        var description: String
        var category: String
        /// 逗号分隔（`agent_skills.tags` 的存储格式）。
        var tags: String
        /// 逗号分隔（`agent_skills.tools` 的存储格式）。
        var tools: String
        var contentPath: String
        var contentHash: String
        var contentLength: Int64
        var enabled: Bool
        /// nil = 全局技能；非 nil = 该角色私有。
        var companionId: Int64?
        var version: Int32
        var updatedAt: Int64

        var tagList: [String] {
            tags.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
        }

        var toolList: [String] {
            tools.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
        }

        /// 正文的绝对路径（`contentPath` 存的是相对路径，见 `AgentStores.saveSkill`）。
        func contentURL() -> URL? {
            guard !contentPath.isEmpty else { return nil }
            guard let base = try? AppPaths.applicationSupport() else { return nil }
            return base.appendingPathComponent(contentPath)
        }
    }

    /// 正文 + 完整性校验结果。
    struct Content: Equatable {
        var text: String
        /// SHA-256 是否与索引里记的一致。
        ///
        /// ⚠️ 不一致时**引擎会拒绝加载**（`getSkillContent` 无条件比对哈希后返回 nil），
        /// 表现是模型说「未找到技能」。所以界面必须把这一项显出来 ——
        /// 否则用户只看到"技能明明在列表里，模型却说没有"。
        var hashMatches: Bool
    }

    // MARK: - 读

    /// 全部技能（含禁用）。
    ///
    /// 不做伴侣过滤 —— 调用方在界面上标出归属（见类型注释的坑 1）。
    func listAll() -> [Skill] {
        (try? database.pool.read { db in
            try Row.fetchAll(db, sql: """
                SELECT id, skillId, name, description, category, tags, tools,
                       contentPath, contentHash, contentLength, enabled,
                       companionId, version, updatedAt
                FROM agent_skills
                ORDER BY enabled DESC, updatedAt DESC
                """).map(row(from:))
        }) ?? []
    }

    /// 只取某个角色可见的技能（全局 + 该角色私有）。
    ///
    /// 显式传 id 才能避开 `WHERE companionId IS NULL OR companionId = ?` 的越权读法。
    func listVisible(to companionId: Int64) -> [Skill] {
        (try? database.pool.read { db in
            try Row.fetchAll(db, sql: """
                SELECT id, skillId, name, description, category, tags, tools,
                       contentPath, contentHash, contentLength, enabled,
                       companionId, version, updatedAt
                FROM agent_skills
                WHERE companionId IS NULL OR companionId = ?
                ORDER BY enabled DESC, updatedAt DESC
                """, arguments: [companionId]).map(row(from:))
        }) ?? []
    }

    func counts() -> (all: Int, enabled: Int, disabled: Int) {
        let all = listAll()
        let enabled = all.filter(\.enabled).count
        return (all.count, enabled, all.count - enabled)
    }

    /// 读正文（**不看 `enabled`** —— 已禁用的技能也要能预览）。
    func content(for skill: Skill) -> Content? {
        guard let url = skill.contentURL(),
              let text = try? String(contentsOf: url, encoding: .utf8)
        else { return nil }
        let hash = DeviceIdentity.sha256Hex(Data(text.utf8))
        return Content(text: text, hashMatches: hash == skill.contentHash)
    }

    // MARK: - 启用开关

    /// 开 / 关一个技能。
    ///
    /// 引擎侧确实读这一列：`skill_selector.rs:101/162/196` 三处 `filter(|m| m.enabled)`，
    /// iOS 的 `searchSkills` SQL 也带 `AND enabled = 1` —— 所以这是**真开关**。
    @discardableResult
    func setEnabled(_ enabled: Bool, skillId: String) -> Bool {
        do {
            let changed = try database.pool.write { db -> Int in
                try db.execute(
                    sql: "UPDATE agent_skills SET enabled = ?, updatedAt = ? WHERE skillId = ?",
                    arguments: [enabled ? 1 : 0,
                                Int64(Date().timeIntervalSince1970 * 1000),
                                skillId]
                )
                return db.changesCount
            }
            return changed > 0
        } catch {
            log.error("setEnabled 失败：\(error.localizedDescription, privacy: .public)")
            return false
        }
    }

    // MARK: - 映射

    private func row(from row: Row) -> Skill {
        Skill(
            id: row["id"] as Int64? ?? 0,
            skillId: row["skillId"] as String? ?? "",
            name: row["name"] as String? ?? "",
            description: row["description"] as String? ?? "",
            category: row["category"] as String? ?? "",
            tags: row["tags"] as String? ?? "",
            tools: row["tools"] as String? ?? "",
            contentPath: row["contentPath"] as String? ?? "",
            contentHash: row["contentHash"] as String? ?? "",
            contentLength: row["contentLength"] as Int64? ?? 0,
            enabled: (row["enabled"] as Int? ?? 0) != 0,
            companionId: row["companionId"] as Int64?,
            version: Int32(row["version"] as Int? ?? 1),
            updatedAt: row["updatedAt"] as Int64? ?? 0
        )
    }
}
