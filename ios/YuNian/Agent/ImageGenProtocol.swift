import Foundation

/// 生图协议 —— 对应 Android `core:domain` 的 `ImageGenProtocol`。
///
/// ## 第 111 轮：补齐提取与清洗
/// 上一版只移植了 `systemRules`(注入给模型的协议文本),尚缺从模型回复里
/// **提取**与**清洗**的那一半。缺的后果:模型按协议输出了
/// `[[生图: 画面描述]]`,而 iOS 会把这行原文当普通文字显示/落库 ——
/// 用户看到一串标记,画没出来。Android 侧(v57 行起)早就处理了。
///
/// ## 移植口径
/// 三条正则与 Kotlin **逐字一致**(仅把 Kotlin 的 `Regex` 换成 `NSRegularExpression`,
/// 语义不变),提取顺序、长度下限(6)、清洗后的空行压缩都照原样。
/// Kotlin 的 `RegexOption.IGNORE_CASE` 对应 `.caseInsensitive`。
///
/// ⚠️ 只做"识别与清洗",**不做出图**:iOS 侧没有图片生成服务,
/// 强行接一个假渲染只会造出新的死代码。出图留给后续接真实服务时再做。
enum ImageGenProtocol {

    /// 单独一行「画面：xxx」的最小长度。短于此的多半是正常句子
    /// (如「画面：很好看」),保留原样避免误删 —— 与 Kotlin 同名常量一致。
    private static let minStandalonePromptLength = 6

    // MARK: - 三条匹配规则（与 Kotlin 逐字对齐）

    /// 键名候选。Kotlin: `"生图|画图|配图|生成图|画一张|出图|image|draw|img|picture"`
    private static let tagKeys =
        "生图|画图|配图|生成图|画一张|出图|image|draw|img|picture"

    /// 描述键名候选。Kotlin:
    /// `"画面描述|画面|图片描述|生图描述|配图描述|出图描述|image\\s*prompt|image|prompt"`
    private static let promptLabels =
        "画面描述|画面|图片描述|生图描述|配图描述|出图描述|image\\s*prompt|image|prompt"

    /// 1. `[[生图: 描述]]`
    private static let doubleBracketTag = makeRegex(
        #"\[\s*(?:\#(tagKeys))\s*[:：]\s*([^\[\]]+?)\s*\]"#)
    /// 2. `（画面：描述）` 等括号包裹
    private static let bracketedPrompt = makeRegex(
        #"[（(\[【《「]\s*(?:\#(promptLabels))\s*[:：]\s*([^（）()\[\]【】《》「」]+?)\s*[）)\]】》」]"#)
    /// 3. 独占一行的 `画面：描述`（允许前置空白或列表符号）
    private static let standalonePromptLine = makeRegex(
        #"(?m)^[\s>\-*•]*(?:画面描述|画面|图片描述|生图描述|配图描述)\s*[:：]\s*(.\{6,\})$"#)

    private static func makeRegex(_ pattern: String) -> NSRegularExpression {
        // ⚠️ pattern 已由上面的字符串插值拼好；再用一次插值把 tagKeys/promptLabels 填进去。
        var resolved = pattern
        resolved = resolved.replacingOccurrences(of: "#(tagKeys)", with: tagKeys)
        resolved = resolved.replacingOccurrences(of: "#(promptLabels)", with: promptLabels)
        // 试一次；若写错就直接崩，别静默返回 nil 让上层以为"没有画面描述"
        return try! NSRegularExpression(
            pattern: resolved, options: [.caseInsensitive, .dotMatchesLineSeparators])
    }

    // MARK: - 提取

    /// 从模型回复里取出画面描述。
    ///
    /// 顺序与 Kotlin 一致：`[[…]]` → 括号包裹 → 独占一行。
    /// 第三条有长度下限(`minStandalonePromptLength`),短于它的视为正常句子。
    static func extractPrompt(_ text: String) -> String? {
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return nil }

        for regex in [doubleBracketTag, bracketedPrompt] {
            if let hit = firstMatch(regex, text)?.trimmingCharacters(in: .whitespacesAndNewlines),
               !hit.isEmpty {
                return hit
            }
        }
        if let hit = firstMatch(standalonePromptLine, text)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
           hit.count >= minStandalonePromptLength {
            return hit
        }
        return nil
    }

    /// 回复里是否夹带了任意写法的画面描述。
    static func hasPrompt(_ text: String) -> Bool { extractPrompt(text) != nil }

    // MARK: - 清洗

    /// 剥离所有画面描述标记，返回可安全展示/落库的文本。
    ///
    /// 连续空行会被压成单个空行；若整条回复只有画面描述则返回空串。
    static func sanitizeForDisplay(_ text: String) -> String {
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return text }

        var out = replaceAll(doubleBracketTag, text, with: "")
        out = replaceAll(bracketedPrompt, out, with: "")
        // 太短的"画面：xx"多半是正常句子，保留原样避免误删（与 Kotlin 的 lambda 等价）
        out = replace(standalonePromptLine, in: out) { match in
            let body = (firstGroup(of: match) ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return body.count >= minStandalonePromptLength ? "" : match
        }
        out = replacePattern("^[ \\t]+\\n", in: out, with: "\n", multiline: true)
        out = replacePattern("\\n{3,}", in: out, with: "\n\n", multiline: false)
        return out.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 整条回复是否"只有"画面描述（此时应显示占位文案而不是原文）。
    static func isPromptOnly(_ text: String) -> Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && sanitizeForDisplay(text).isEmpty
            && hasPrompt(text)
    }

    // MARK: - 注入给模型的协议文本

    /// 对应 `ImageGenProtocol.systemRules(enabled:hasKeywordTrigger:)`。
    ///
    /// `enabled = false` 返回空串 —— 对应 iOS 就是**不发这个键**
    /// (`buildSettingsJson` 用 `isNullOrBlank()` 判空)。
    static func systemRules(enabled: Bool = true, hasKeywordTrigger: Bool = false) -> String {
        if !enabled { return "" }
        let extra = hasKeywordTrigger
            ? "用户也可能直接用「画一张…」「再生成一张」这类说法来要图，此时同样必须输出该标签。"
            : ""
        // Kotlin 用 buildString { append(...) } 逐段无分隔拼接，故用 + 显式连接，
        // 不要用 Swift 多行字符串字面量（每行自带换行，会多出 8 个 \n）。
        return "当用户要求你画图/生图/配图，或你主动想发一张画面时，"
            + "你必须在回复正文的最后单独输出一行：[[生图: 画面描述]]。"
            + "不要只说「帮你生成」「这就画」这类话却不输出该标签 —— 那样不会真的出图。"
            + "画面描述只写画面本身（主体、场景、风格、构图、光线），不要写对话口吻的文字。"
            + extra
            + "不需要配图时，绝对不要输出该标签。"
            + "除 [[生图: 画面描述]] 这一种写法外，"
            + "严禁在回复中输出「画面：…」或以圆括号/方括号/书名号包裹的画面描述，"
            + "也不要复述系统注记里出现的画面内容。"
    }

    /// 当前 iOS 无开关 UI，取 Kotlin 默认分支。
    static var defaultPrompt: String { systemRules() }
}

// MARK: - NSRegularExpression 辅助

private extension ImageGenProtocol {

    static func firstMatch(_ regex: NSRegularExpression, _ text: String) -> String? {
        let range = NSRange(text.startIndex..., in: text)
        guard let m = regex.firstMatch(in: text, options: [], range: range),
              m.numberOfRanges > 1,
              let g = Range(m.range(at: 1), in: text) else { return nil }
        return String(text[g])
    }

    static func firstGroup(of match: String) -> String? {
        // 供 lambda 形式替换用：从整段匹配里再取第一组
        for regex in [doubleBracketTag, bracketedPrompt, standalonePromptLine] {
            let range = NSRange(match.startIndex..., in: match)
            if let m = regex.firstMatch(in: match, options: [], range: range),
               m.numberOfRanges > 1,
               let g = Range(m.range(at: 1), in: match) {
                return String(match[g])
            }
        }
        return nil
    }

    static func replaceAll(_ regex: NSRegularExpression, _ text: String, with template: String)
        -> String
    {
        let range = NSRange(text.startIndex..., in: text)
        return regex.stringByReplacingMatches(in: text, options: [], range: range, withTemplate: template)
    }

    /// 按整段回调决定替换内容（Kotlin 的 `replace(text) { match -> … }`）。
    static func replace(
        _ regex: NSRegularExpression, in text: String, using transform: (String) -> String
    ) -> String {
        let range = NSRange(text.startIndex..., in: text)
        let matches = regex.matches(in: text, options: [], range: range)
        var out = text
        for m in matches.reversed() {
            guard let g = Range(m.range(at: 0), in: out) else { continue }
            out.replaceSubrange(g, with: transform(String(out[g])))
        }
        return out
    }

    static func replacePattern(
        _ pattern: String, in text: String, with template: String, multiline: Bool
    ) -> String {
        let regex = try! NSRegularExpression(
            pattern: pattern,
            options: multiline ? [.anchorsMatchLines] : [])
        let range = NSRange(text.startIndex..., in: text)
        return regex.stringByReplacingMatches(
            in: text, options: [], range: range, withTemplate: template)
    }
}
