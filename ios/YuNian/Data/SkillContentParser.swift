import Foundation

/// SKILL.md frontmatter 解析 —— 与 Android `SkillContentParser` 逐字等价。
///
/// 格式：正文首部用 `---` 行包裹的 YAML 子集（`key: value` 单行），支持 `name` / `description`。
/// 无 frontmatter 或格式非法时**原样返回正文**。
///
/// ## 为什么必须等价
/// 两处都依赖它：
///   1. `getSkillContent` 返回给模型的是 `body`（**已剥离 frontmatter**）
///   2. `saveSkill` 用解析出的 name / description **覆盖**索引字段
/// 如果 iOS 不做剥离，模型会看到 `---\nname: ...\n---` 这段元数据；
/// 如果不做覆盖，技能名会与文件里的声明不一致。
///
/// 纯函数、无平台依赖，可直接用 `YuNianTests` 验证。
enum SkillContentParser {

    struct ParsedSkill: Equatable {
        let name: String?
        let description: String?
        let body: String
    }

    static func parse(_ content: String) -> ParsedSkill {
        // Kotlin 的 `String.lines()` 会在 \n、\r\n、\r 三种换行上切分；
        // Swift 的 `components(separatedBy: "\n")` 只认 \n，会把 \r 留在行尾。
        // 先归一化，保证与 Android 的切分结果一致（否则 CRLF 文件的正文会带 \r）。
        let normalized = content
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        let lines = normalized.components(separatedBy: "\n")

        // 少于 3 行不可能构成 frontmatter（开标记 + 至少一项 + 闭标记）
        guard lines.count >= 3 else { return ParsedSkill(name: nil, description: nil, body: content) }
        guard lines[0].trimmingCharacters(in: .whitespaces) == "---" else {
            return ParsedSkill(name: nil, description: nil, body: content)
        }

        // 从第二行起找闭合 ---
        var closeIndex = -1
        for i in 1..<lines.count where lines[i].trimmingCharacters(in: .whitespaces) == "---" {
            closeIndex = i
            break
        }
        guard closeIndex >= 0 else { return ParsedSkill(name: nil, description: nil, body: content) }

        var name: String?
        var description: String?

        for i in 1..<closeIndex {
            let line = lines[i].trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix("#") { continue }

            // 必须用**第一个**冒号切分（与 Kotlin 的 indexOf(':') 一致）
            guard let sepRange = line.range(of: ":") else { continue }
            let key = String(line[line.startIndex..<sepRange.lowerBound])
                .trimmingCharacters(in: .whitespaces)
            var value = String(line[sepRange.upperBound...])
                .trimmingCharacters(in: .whitespaces)

            // Kotlin: `.trim('"').trim('\'')` —— 依次剥离双引号与单引号
            value = trimQuotes(value, "\"")
            value = trimQuotes(value, "'")

            switch key {
            case "name": name = value
            case "description": description = value
            default: break
            }
        }

        let body = lines[(closeIndex + 1)...]
            .joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        return ParsedSkill(name: name, description: description, body: body)
    }

    /// 对应 Kotlin 的 `String.trim(char)`：剥掉**两端**所有该字符。
    private static func trimQuotes(_ value: String, _ quote: Character) -> String {
        var chars = Array(value)
        while chars.first == quote { chars.removeFirst() }
        while chars.last == quote { chars.removeLast() }
        return String(chars)
    }
}
