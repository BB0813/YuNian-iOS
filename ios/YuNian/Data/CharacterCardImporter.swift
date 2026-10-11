import Foundation
import os

/// 角色卡导入（Tavern V1/V2/V3 JSON，以及 PNG 内嵌 `tEXt/chara`）。
///
/// ## 现状：引擎早就会解析，但**两端都没有消费者**
/// Rust `card_import.rs` 导出 `parseCharacterCardJson` / `parseCharacterCardPng`
/// （PNG 走最小 chunk 遍历，只认事实标准 `tEXt` + `keyword == "chara"` + base64 JSON），
/// Android 侧只在 `AgentFacade` 里包了一层 `runCatching`，**没有任何界面调用它**；
/// iOS 侧此前也没有。也就是说：**这是既有能力第一次被真正用起来**。
///
/// ## ⚠️ 字段落位是 iOS 侧**新定的契约**（Android 还没有消费者，不是移植）
/// 导入的 `CharacterCardInfo` 有 6 个字段，而 `companions` 表只有 12 列 ——
/// 其中**没有开场白列**（两端都没有）。所以落位如下，界面里逐条写明：
///
/// | 卡片字段 | 落到哪 | 引擎会怎么用 |
/// |---|---|---|
/// | `name` | `companions.name` | 「你是{名字}…」 |
/// | `personality` | `personality` | 「人设：…」 |
/// | `description` | **`backstory`** | 「背景：…」（ST 的 description 是设定正文，放这里最贴语义） |
/// | `system_prompt` | `systemPrompt` | 单独一段【自定义角色指令】 |
/// | `first_mes` | **不落库** | 表里没有这一列 → 界面显示 + 一键复制（不假装能存下来） |
/// | `world_json` | `worldbooks` + 绑定 `lorebookIdsJson` | 按伴侣合成时参与（`synthForCompanion`） |
///
/// `avatarUrl` 不在卡片里 → 不填（头像回退到名字首字）。
struct CharacterCardImporter {

    private static let log = Logger(subsystem: "com.yunian.ai", category: "card.import")

    /// 解析结果的预览（**全部来自引擎**，不是本机另写一套解析）。
    struct Preview: Equatable {
        var name: String
        var description: String
        var personality: String
        var firstMes: String
        var systemPrompt: String
        var worldJSON: String
        /// 内嵌世界书的条目数（用 `WorldbookRuntimeSync.entrySummaries` 数，
        /// 与引擎合成同一个解码器）。
        var worldEntryCount: Int

        var hasWorld: Bool { !worldJSON.isEmpty && worldEntryCount > 0 }

        /// 落库时用的名字（卡片没写名字时给可读的兜底 —— 不写空串进 NOT NULL 列）。
        var resolvedName: String {
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? "未命名角色" : trimmed
        }
    }

    /// 从 JSON 解析。
    ///
    /// ⚠️ 失败时**只有 nil，没有原因**：Rust 侧是 `Result<CharacterCardInfo, String>`，
    /// 而 UniFFI 无法把 `String` 当 Swift 的 `Error`，于是绑定把它映射成**可选值**
    /// （错误文本在这一层就丢了）。界面因此只能说"解析失败"，别编具体原因。
    static func preview(json: String) -> Preview? {
        guard let info = parseCharacterCardJson(json: json) else { return nil }
        return makePreview(info)
    }

    /// 从 PNG 字节解析（Tavern V2 事实标准）。
    static func preview(png: Data) -> Preview? {
        guard let info = parseCharacterCardPng(bytes: png) else { return nil }
        return makePreview(info)
    }

    /// 自动识别：PNG 看签名，其余按文本 JSON 处理。
    static func preview(bytes: Data) -> Preview? {
        if isPNG(bytes) { return preview(png: bytes) }
        guard let text = String(data: bytes, encoding: .utf8) else { return nil }
        return preview(json: text)
    }

    static func isPNG(_ data: Data) -> Bool {
        data.count >= 8 && data.prefix(8) == Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
    }

    private static func makePreview(_ info: CharacterCardInfo) -> Preview {
        let entries = WorldbookRuntimeSync.entrySummaries(info.worldJson)
        return Preview(
            name: info.name,
            description: info.description,
            personality: info.personality,
            firstMes: info.firstMes,
            systemPrompt: info.systemPrompt,
            worldJSON: info.worldJson,
            worldEntryCount: entries.count
        )
    }

    // MARK: - 落库

    struct ImportResult: Equatable {
        var companionId: Int64
        /// 内嵌世界书若被导入，这里给出它在新表里的行 id（并已绑定到该角色）。
        var worldbookId: Int64?
    }

    /// 创建角色（可选：把卡片内嵌世界书一并导入）。
    ///
    /// ## 内嵌世界书落成**专属书**（`companionId = 新角色 id`），不写 `lorebookIdsJson`
    /// 我第一版写的是「存成全局书 + 把 id 绑进 `companions.lorebookIdsJson`」，
    /// 看起来更"灵活"，但对着 `synthForCompanion` 的绑定规则逐条推演后发现**两条路都不对**：
    /// - `boundIds` 为空时规则会放进**所有全局书** → 一本卡片世界书会漏给别的角色；
    /// - 想让绑定真正起作用，得指望别的角色**恰好也有绑定** —— 靠这种巧合不能算语义。
    ///
    /// 而 `worldbooks.companionId = 该角色` 的语义是明确的：
    /// 按伴侣合成时 `row.companionId == companionId` 命中，只有 ta 用得到。
    /// 所以这里**只用归属**，不叠加那层冗余且会漏的绑定。
    @discardableResult
    static func save(_ preview: Preview,
                     importWorld: Bool,
                     companions: CompanionRepository?,
                     database: YuNianDatabase?) throws -> ImportResult {
        guard let companions else { throw ImportError.environmentNotReady }

        let id = try companions.create(
            name: preview.resolvedName,
            personality: preview.personality.trimmingCharacters(in: .whitespacesAndNewlines),
            age: nil,
            // ST 的 description 是设定正文 → 落到「背景」（引擎会拼成「背景：…」）
            backstory: trimmedOrNil(preview.description),
            speakingStyle: nil,
            avatarUrl: nil,
            tags: nil,
            rawPrompt: nil,
            // ST 的 system_prompt 直接对应「自定义角色指令」
            systemPrompt: trimmedOrNil(preview.systemPrompt)
        )

        var worldbookId: Int64?
        if importWorld, preview.hasWorld, let database {
            let books = WorldbookRepository(database: database)
            // 归属到该角色：专属书，只有 ta 的回合会带上（见上面关于绑定规则的说明）
            let bookId = books.save(name: preview.resolvedName + " · 角色卡世界书",
                                    json: preview.worldJSON,
                                    companionId: id)
            if bookId > 0 {
                worldbookId = bookId
            } else {
                log.error("角色卡世界书落库失败，已跳过")
            }
        }

        return ImportResult(companionId: id, worldbookId: worldbookId)
    }

    enum ImportError: Error, Equatable {
        case environmentNotReady

        var message: String {
            switch self {
            case .environmentNotReady: return "运行环境未就绪"
            }
        }
    }

    private static func trimmedOrNil(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
