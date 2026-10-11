import Foundation
import os

/// 角色卡导出（Tavern V2 JSON）——与 `CharacterCardImporter` 对称。
///
/// ## 为什么是对称的一半
/// 上一轮把"导入角色卡"接通了（引擎早就能解析 PNG/JSON，两端都没有入口）。
/// 只进不出等于把用户的东西锁在 App 里：换设备、分享给朋友、丢进别人的
/// SillyTavern，都需要**导出**。这也是"数据是你的"那条原则在角色层面的落点。
///
/// ## ⚠️ 导出的三处**已知损失**（界面必须如实说明，不能装作无损）
/// 1. **开场白为空**：`companions` 表没有这一列（两端都没有），
///    所以导入时就没存下来 → 导出只能是空串。
/// 2. **只带一本世界书**：角色可能有多本专属书；卡片格式的 `character_book`
///    只有一个位置，所以只嵌入**第一本启用的专属书**（并回报本数）。
/// 3. **只出 JSON，不出 PNG**：Tavern 社区多用 PNG 内嵌 `tEXt/chara`，
///    但要生成**合法 PNG** 得自己做 CRC32 + IDAT + zlib 包装
///    （`Compression` 只给 raw deflate，不是 zlib 流）。
///    在没把握产出"别人一定能导入"的文件之前，宁可只给 JSON，
///    也不放一个点了生成坏文件的按钮。
///
/// ## 为什么 JSON 仍然够用
/// V2 JSON 是角色卡的标准形态之一（SillyTavern 直接吃 `.json`），
/// 而且**能被我们自己的导入器原样吃回来** —— 往返测试就是按这个写的。
struct CharacterCardExporter {

    private static let log = Logger(subsystem: "com.yunian.ai", category: "card.export")

    struct ExportResult: Equatable {
        var json: String
        /// 建议文件名（已去掉路径分隔符等非法字符）。
        var fileName: String
        /// 嵌入的世界书名（nil = 没有专属世界书）。
        var embeddedBookName: String?
        var embeddedEntryCount: Int
        /// 这个角色一共有几本专属世界书（> 1 时界面要提示只带了第一本）。
        var ownedBookCount: Int
    }

    /// 从角色（+ 它自己的世界书）生成 V2 卡片 JSON。
    static func export(companion: CompanionRepository.Companion,
                       database: YuNianDatabase?) -> ExportResult {
        var ownedBooks: [WorldbookRepository.Book] = []
        if let database {
            ownedBooks = WorldbookRepository(database: database)
                .list()
                .filter { $0.companionId == companion.id && $0.enabled }
        }
        // 只取第一本（按列表顺序 = enabled 优先、updatedAt 倒序）
        let embedded = ownedBooks.first

        var data: [String: Any] = [
            "name": companion.name,
            // ST 的 description ← 我们的「背景」（导入时是反向映射，这里原路返回）
            "description": companion.backstory ?? "",
            "personality": companion.personality,
            "scenario": "",
            // ⚠️ 开场白不在库里：导出只能是空串（界面要说明）
            "first_mes": "",
            "mes_example": "",
            "system_prompt": companion.systemPrompt ?? "",
        ]
        if let speaking = companion.speakingStyle, !speaking.isEmpty {
            // ST 没有"说话风格"这个字段 → 附在 description 末尾，别丢
            let base = (data["description"] as? String) ?? ""
            data["description"] = base.isEmpty ? "说话风格：\(speaking)"
                                               : base + "\n\n说话风格：\(speaking)"
        }
        if let embedded,
           let bookJSON = worldbookObject(from: embedded.json) {
            data["character_book"] = bookJSON
        }

        let root: [String: Any] = [
            "spec": "chara_card_v2",
            "spec_version": "2.0",
            "data": data,
        ]
        let json = (try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys]))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"

        return ExportResult(
            json: json,
            fileName: sanitizedFileName(companion.name),
            embeddedBookName: embedded?.name,
            embeddedEntryCount: embedded?.entryCount ?? 0,
            ownedBookCount: ownedBooks.count
        )
    }

    /// 世界书那列的原文 → 对象（卡片里 `character_book` 要放对象，不是字符串）。
    ///
    /// 解析不了就返回 nil → 导出时不带世界书（而不是塞一段坏 JSON 进去 ——
    /// 那会让别人的导入器整张卡都解析失败）。
    static func worldbookObject(from raw: String) -> [String: Any]? {
        guard let data = raw.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              object["entries"] != nil
        else { return nil }
        return object
    }

    /// 文件名清洗：去掉路径分隔符与各平台的非法字符，并兜底成可读名。
    static func sanitizedFileName(_ name: String) -> String {
        let illegal = CharacterSet(charactersIn: "/\\:*?\"<>|\n\r\t")
        let cleaned = name.components(separatedBy: illegal).joined(separator: "_")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let capped = cleaned.count > 40 ? String(cleaned.prefix(40)) : cleaned
        return capped.isEmpty ? "character-card" : capped
    }
}
