import Foundation
import os

/// 语义/绕过检测 —— 与 Android `SemanticDetector` 等价。
///
/// ## 三层规则
/// 1. **evasion**：字符替换（`p0rn`/`sh!t`）、空格插入（`s e x`）等绕过尝试
/// 2. **intent**：可疑意图（写色情、如何杀人、如何洗钱），**需同组命中 ≥2 条**才计
/// 3. **multilingual**：中英混写、拼音绕过
///
/// ## 等级判定依赖 match 字符串的内容
/// Android 把每条命中格式化成 `"[EVASION:类别] 命中文本"` 这类字符串，
/// 然后**用子串匹配**决定等级（找 `CHILD`/`PORNO`/`BOMB`/`INTENT`/`MULTILINGUAL` 等）。
/// 因此这些格式串必须逐字复刻 —— 改一个字就会改变等级判定。
///
/// ## 一处如实保留的怪癖
/// 判定里找的是 `"CHILD"`、`"PEDO"` 这类**英文子串**，而多数命中文本并不含它们：
///   - 字符替换类命中形如 `c.h.i.l.d`（带点）→ 不含 `CHILD`
///   - 中文类命中形如 `儿童色情` → 不含 `CHILD`
///   - 类别名（`CHAR_SUBSTITUTION` / `CHINESE_ENGLISH_MIX` …）也都不含
/// 因此 EXTREME 分支实际只有一种触发路径：multilingual 规则里
/// `(儿童|幼女|loli|child)` 命中了**字面量 `child`**。
/// 这与 Android 行为一致，**不做单方面修正** —— 过滤等级直接决定封禁天数，
/// 属于产品/安全决策。
enum SemanticDetector {

    private static let log = Logger(subsystem: "com.yunian.ai", category: "semantic")

    struct DetectionResult: Sendable, Equatable {
        let isViolating: Bool
        let level: ViolationLevel
        let reason: String
        let matchedTerms: [String]
    }

    // MARK: - 规则表（逐字转录自 SemanticDetector.kt）

    /// ⚠️ Kotlin 用的是原始字符串（`"""`），其中 `\!` 是**字面反斜杠 + 感叹号**，
    /// `\*` 是字面反斜杠 + 星号；`${'$'}` 是模板，结果为单个 `$`。
    /// Swift 这里用原始字符串 `#"..."#` 保持一致。
    private static let evasionPatterns: [String: [String]] = [
        "CHAR_SUBSTITUTION": [
            #"(?i)(s$x|f$ck|sh\!t|@ss|b\!tch|d\!ck)"#,
            #"(?i)(p0rn|pr0n|s3x|n00d|f4gg0t|r3t4rd)"#,
            #"(?i)(c\.h\.i\.l\.d|p\.e\.d\.o|j\.i\.h\.a\.d)"#,
            #"(?i)(\*sex\*|\*porn\*|\*fuck\*|\*rape\*)"#,
        ],
        "SPACE_INSERTION": [
            #"(?i)(s\s+e\s+x|p\s+o\s+r\s+n|f\s+u\s+c\s+k|k\s+i\s+l\s+l)"#,
            #"(?i)(j\s+a\s+i\s+l\s+b\s+r\s+e\s+a\s+k)"#,
        ],
    ]

    private static let intentPatterns: [String: [String]] = [
        "SEXUAL_INTENT": [
            #"(?i)(write\s+(a|me)\s+(erotic|porn|nude|sex\s*ual)\s*(story|scene))"#,
            #"(?i)(describe\s+(explicitly|graphically)\s+(sex|nude|porn))"#,
            #"(?i)(make\s+it\s+(more\s+)?(explicit|erotic|sexual))"#,
        ],
        "VIOLENT_INTENT": [
            #"(?i)(how\s+to\s+(kill|hurt|murder|torture|poison|bomb))"#,
            #"(?i)(help\s+me\s+(die|kill\s+myself|end\s+my\s+life|suicide))"#,
            #"(?i)(want\s+to\s+(die|kill\s+myself|hurt\s+someone))"#,
        ],
        "ILLEGAL_INTENT": [
            #"(?i)(how\s+can\s+I\s+(get\s+away\s+with|avoid\s+detection|hide\s+evidence))"#,
            #"(?i)(black\s+market|dark\s+web|deep\s+web)"#,
            #"(?i)(money\s+laundering|tax\s+evasion|fraud\s+scheme|identity\s+theft)"#,
        ],
    ]

    private static let multilingualPatterns: [String: [String]] = [
        "CHINESE_ENGLISH_MIX": [
            #"(?i)(写\s*(write|generate|create).{0,10}(色情|porn|hentai|性爱))"#,
            #"(?i)(忽略\s*(ignore|disregard).{0,10}(安全规则|限制))"#,
            #"(?i)((儿童|幼女|loli|child).{0,10}(色情|porn|性爱|sex))"#,
            #"(?i)((炸弹|bomb|炸药).{0,10}(制造|make|build).{0,10}(教程|guide))"#,
        ],
        "PINYIN_DETECTION": [
            #"(se\s+qing|huang\s+pian|lian\s+tong|xing\s+ai)"#,
            #"(yue\s+yu|po\s+xian|jie\s+jia)"#,
        ],
    ]

    /// 预先编译；编译失败的条目静默丢弃（对应 Kotlin 的 `mapNotNull { runCatching { ... } }`）。
    private static let compiledEvasion = compile(evasionPatterns)
    private static let compiledIntent = compile(intentPatterns)
    private static let compiledMultilingual = compile(multilingualPatterns)

    private static func compile(_ source: [String: [String]]) -> [String: [NSRegularExpression]] {
        var out: [String: [NSRegularExpression]] = [:]
        for (category, patterns) in source {
            out[category] = patterns.compactMap { try? NSRegularExpression(pattern: $0) }
        }
        return out
    }

    // MARK: - 检测

    /// 对应 `detectSemanticViolations`。
    static func detectSemanticViolations(_ text: String) -> DetectionResult {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return DetectionResult(isViolating: false, level: .none, reason: "", matchedTerms: [])
        }

        // Android 对**小写化后**的文本做匹配
        let lowered = text.lowercased()
        let range = NSRange(lowered.startIndex..., in: lowered)
        var allMatches: [String] = []

        // ① 绕过尝试：收集全部命中
        // 注意：Android 遍历的是 Map，顺序不确定；这里按类别名排序以保证确定性
        // （结果集相同，只有 allMatches 的顺序不同，而后续判定是 any/contains，与顺序无关）
        for category in compiledEvasion.keys.sorted() {
            for pattern in compiledEvasion[category] ?? [] {
                for match in pattern.matches(in: lowered, options: [], range: range) {
                    if let r = Range(match.range, in: lowered) {
                        allMatches.append("[EVASION:\(category)] \(String(lowered[r]))")
                    }
                }
            }
        }

        // ② 可疑意图：同类别需命中 ≥2 条才计一条摘要
        for category in compiledIntent.keys.sorted() {
            var matchCount = 0
            for pattern in compiledIntent[category] ?? [] where pattern.firstMatch(in: lowered, options: [], range: range) != nil {
                matchCount += 1
            }
            if matchCount >= 2 {
                allMatches.append("[INTENT:\(category)] 检测到\(matchCount)个意图匹配")
            }
        }

        // ③ 多语言混合 / 拼音：收集全部命中
        for category in compiledMultilingual.keys.sorted() {
            for pattern in compiledMultilingual[category] ?? [] {
                for match in pattern.matches(in: lowered, options: [], range: range) {
                    if let r = Range(match.range, in: lowered) {
                        allMatches.append("[MULTILINGUAL:\(category)] \(String(lowered[r]))")
                    }
                }
            }
        }

        return classify(allMatches)
    }

    /// 等级判定链 —— 逐字对应 Android 的 `when { ... }`，**顺序不可调换**。
    private static func classify(_ allMatches: [String]) -> DetectionResult {
        guard !allMatches.isEmpty else {
            return DetectionResult(isViolating: false, level: .none, reason: "", matchedTerms: [])
        }
        let distinct = Array(Set(allMatches)).sorted()

        func anyContains(_ needles: [String]) -> Bool {
            allMatches.contains { line in
                needles.contains { line.range(of: $0, options: .caseInsensitive) != nil }
            }
        }

        if anyContains(["CHILD", "PORNO", "PEDO"]) {
            return DetectionResult(isViolating: true, level: .extreme, reason: "检测到极端违规内容", matchedTerms: distinct)
        }
        if anyContains(["BOMB", "TERROR", "ENCODING"]) {
            return DetectionResult(isViolating: true, level: .critical, reason: "检测到极严重违规内容", matchedTerms: distinct)
        }
        // 注意这一条是「同一行同时含 INTENT 与 VIOLENT/ILLEGAL」的合取
        let intentViolentOrIllegal = allMatches.contains { line in
            line.range(of: "INTENT", options: .caseInsensitive) != nil
                && (line.range(of: "VIOLENT", options: .caseInsensitive) != nil
                    || line.range(of: "ILLEGAL", options: .caseInsensitive) != nil)
        }
        if intentViolentOrIllegal {
            return DetectionResult(isViolating: true, level: .severe, reason: "检测到严重违规意图", matchedTerms: distinct)
        }
        if anyContains(["MULTILINGUAL"]) {
            return DetectionResult(isViolating: true, level: .medium, reason: "检测到可疑多语言混合", matchedTerms: distinct)
        }
        if anyContains(["INTENT"]) {
            return DetectionResult(isViolating: true, level: .low, reason: "检测到不明确意图", matchedTerms: distinct)
        }
        return DetectionResult(isViolating: true, level: .low, reason: "检测到轻微绕过尝试", matchedTerms: distinct)
    }

    /// 对应 `preprocessAndDetect`：额外跑一遍「去掉所有空白」的文本，只返回违规结果。
    ///
    /// 去空白是为了抓 `s e x` 这类空格插入绕过（尽管 SPACE_INSERTION 规则本身也覆盖了它）。
    static func preprocessAndDetect(_ text: String) -> [DetectionResult] {
        var results: [DetectionResult] = [detectSemanticViolations(text)]
        let noSpaces = text.replacingOccurrences(
            of: #"\s+"#, with: "", options: .regularExpression
        )
        if noSpaces != text {
            results.append(detectSemanticViolations(noSpaces))
        }
        return results.filter(\.isViolating)
    }

    /// 对应 `generateDetectionReport`。
    static func generateDetectionReport(_ text: String) -> String {
        let violations = preprocessAndDetect(text)
        if violations.isEmpty { return "✅ 未检测到违规" }

        var lines = ["⚠️ 检测到 \(violations.count) 个潜在违规:"]
        for violation in violations {
            lines.append("  [\(violation.level.key)] \(violation.reason)")
            for term in violation.matchedTerms {
                lines.append("    - \(term)")
            }
        }
        return lines.joined(separator: "\n")
    }
}
