import Foundation

/// 表情清单解析 —— 从「名称 + 图片直链」的粘贴清单里扫出条目。
///
/// ## 为什么是粘贴清单，而不是选文件
/// 用户手上流传的表情包资料基本是 docx（Word 里一行行写着
/// `名称：https://…/xxx.gif`）。而 **`.docx` 本质是 ZIP**，
/// 本项目没有引入任何 ZIP 库（`Package.swift` 零第三方依赖），Swift 侧解不开它。
/// 引入一个压缩库只为读正文，代价远大于收益。所以做**粘贴正文**：
/// 容器格式不重要，**内容才重要**。
///
/// ## 命名规则（第 226 轮明确）
/// 一条表情的**名称**按以下优先级取：
/// 1. 链接**后面紧跟的括号内容**（`https://…/a.jpg（小猫拿红包）`）—— 最具体，优先；
/// 2. 否则取**链接之前**的那段文字（`小猫拿红包：https://…/a.jpg`）；
/// 3. 都没有 → 回落 URL 的文件名。
///
/// ⚠️ 名称不是装饰：`StickerImportRepository.deriveTags` 由它派生 **tags**，
/// 而 tags 是 Rust `builtin_send_sticker` 精确匹配的唯一依据 ——
/// **名称填得越具体，模型越容易在合适语境里挑中它**。
///
/// ## 这个类型为什么从 View 里搬出来
/// 原先它是 `StickerBulkImportView` 的 `static func`。解析是要**单测**的逻辑
/// （下面那些正则教训就是证据），放在 View 里只能靠 UI 测，成本高且容易漏。
struct StickerListParser {

    /// 解析出的一条。
    struct ParsedSticker: Identifiable, Equatable {
        let id = UUID()
        var name: String
        var url: URL
        var urlString: String
    }

    /// ## 正则为什么是「只允许 ASCII URL 安全字符、且不要求扩展名」
    ///
    /// 这是拿四份真实资料（137 张）实测出来的，前两版都是错的：
    ///
    /// **版本一** 用 `[^\s（）]+` 当字符类 → 允许中文 →
    /// 正则从第一个链接**一路吞到后面某个 `.gif`**，
    /// `Cin6DzJ.gif神气：https://…Cin6yfp.gif` 被当成**一个** URL。
    /// 实测：`呆猫八条UR①` 从应有的 16 条掉到 **1 条**。
    ///
    /// **版本二** 要求以 `\.(jpg|png|gif|webp)` 结尾 →
    /// 而 `https://u2.fukit.cn/4uhF9bynr` 这类图床链接**没有扩展名**。
    /// 实测：`表情包gif` 整份 **0 条**。
    ///
    /// 现在：字符集限定 ASCII URL 安全字符，**不要求扩展名**。
    /// 扩展名改由下载后**按魔数判定**（见 `StickerBulkImportView.fileExtension`）——
    /// 反正图床返回的 Content-Type 也不可信。
    static let urlPattern = #"https?://[A-Za-z0-9\-._~:/?#@!$&*+,;=%]+"#

    static func parse(_ text: String) -> [ParsedSticker] {
        guard !text.isEmpty,
              let regex = try? NSRegularExpression(pattern: urlPattern, options: [.caseInsensitive])
        else { return [] }

        let ns = text as NSString
        let matches = regex.matches(in: text, range: NSRange(location: 0, length: ns.length))

        var result: [ParsedSticker] = []
        var cursor = 0

        for match in matches {
            guard let url = URL(string: ns.substring(with: match.range)) else { continue }

            // 名称候选 A：上一个链接结束 → 本链接开始之间的文字
            let between = ns.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
            cursor = match.range.location + match.range.length

            // 名称候选 B：URL 之后紧跟的括号内容（更具体，优先）
            let after = ns.substring(from: min(cursor, ns.length))
            let trailing = leadingParenthetical(after)

            let name = clean(trailing ?? between)
            result.append(ParsedSticker(
                name: name.isEmpty ? nameFromURL(url) : name,
                url: url,
                urlString: url.absoluteString
            ))
        }
        return result
    }

    /// 取字符串**开头**的括号内容：`（小猫拿红包）后面的…` → `小猫拿红包`。
    static func leadingParenthetical(_ text: String) -> String? {
        let body = text.drop { $0 == " " || $0.isNewline || $0 == "\t" }
        guard let opener = body.first else { return nil }

        // 只认全角/半角圆括号与方括号这四种。
        // 用 switch 而不是字典字面量：字典键里出现单独的方括号字符时，
        // 解析器在字面量上容易出"expected declaration"这类级联报错，
        // 而这里本来也不需要一个字典。
        let closer: Character
        switch opener {
        case "\u{FF08}": closer = "\u{FF09}"   // （ ）
        case "(": closer = ")"
        case "\u{3010}": closer = "】"         // 【 】
        case "[": closer = "]"
        default: return nil
        }

        guard let end = body.firstIndex(of: closer), end > body.startIndex else { return nil }
        let inner = body[body.index(after: body.startIndex)..<end]
        let value = inner.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    /// 去掉分隔符、包裹的标点与**成对/落单的引号**。
    ///
    /// 引号要单独处理：用户清单里常见 `配文"九九成稀罕物`（只有左引号，
    /// 因为右引号被 Word 的自动配对吃掉或写在括号外），
    /// 不清理的话名字里会挂着一个孤零零的引号，标签也跟着脏。
    static func clean(_ text: String) -> String {
        let punctuation = CharacterSet(charactersIn: " \n\t：:，,、;；-—–·\"'“”‘’")
        return text.trimmingCharacters(in: punctuation)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func nameFromURL(_ url: URL) -> String {
        url.deletingPathExtension().lastPathComponent
    }
}
