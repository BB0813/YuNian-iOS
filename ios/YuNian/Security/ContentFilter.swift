import Foundation
import os

/// 违规等级 —— 与 Android `ContentFilter.ViolationLevel` 的**声明顺序一致**。
///
/// ⚠️ 顺序即语义：`checkBlocking` 用 `maxByOrNull { it.level.ordinal }` 取最高等级，
/// 因此 `rawValue` 必须严格等于 Kotlin 的 ordinal（NONE=0 … EXTREME=6）。
enum ViolationLevel: Int, Comparable, CaseIterable, Sendable {
    case none = 0
    case low = 1
    case medium = 2
    case high = 3
    case severe = 4
    case critical = 5
    case extreme = 6

    static func < (lhs: Self, rhs: Self) -> Bool { lhs.rawValue < rhs.rawValue }

    /// 对应 Android 的 `level.name`（数据里的等级键就是这些字符串）。
    var key: String {
        switch self {
        case .none: return "NONE"
        case .low: return "LOW"
        case .medium: return "MEDIUM"
        case .high: return "HIGH"
        case .severe: return "SEVERE"
        case .critical: return "CRITICAL"
        case .extreme: return "EXTREME"
        }
    }
}

struct CheckResult: Sendable, Equatable {
    let isViolating: Bool
    let level: ViolationLevel
    let reason: String
    let matchedKeywords: [String]

    static let clean = CheckResult(isViolating: false, level: .none, reason: "", matchedKeywords: [])
}

/// 内容过滤 —— 与 Android `ContentFilter` 等价。
///
/// ## 数据来源
/// 正则来自 `SecuritySeedLoader.Seed.filterPatterns`（由
/// `Tools/generate_security_seed.py` 从加密资源提取）。
/// 注意：每个等级的数组在资源里是「前半 patterns + 后半 kwds」拼接，
/// 生成器已按 `loadFromAsset` 的方式从中间劈开 —— 本类只消费 patterns 那半。
/// （kwds 那半在 Android 侧交给 native AC 预筛，不在本类的匹配路径上。）
///
/// ## 与 Android 一致的两个关键点
/// 1. `checkKeywords` 是**按等级从高到低取第一个有命中的等级**并立即返回，
///    **不是**「在所有命中里取最高等级」—— 二者在实现上都叫"取最高"，
///    但前者会在第一个非空等级停下。这里保持一致。
/// 2. 正则编译失败**静默跳过**（对应 Kotlin 的 `runCatching { ... }.getOrNull()`），
///    不因为一条坏正则就让整个过滤器不可用。
final class ContentFilter {

    static let shared = ContentFilter()

    private let log = Logger(subsystem: "com.yunian.ai", category: "content-filter")

    /// level → 已编译的正则
    private var compiled: [ViolationLevel: [NSRegularExpression]] = [:]
    private var patternCount = 0
    private var failedPatternCount = 0
    private let lock = NSLock()

    /// `checkKeywords` 的遍历顺序（与 Android 的字面量数组一致，从高到低）。
    private static let scanOrder: [ViolationLevel] = [
        .extreme, .critical, .severe, .high, .medium, .low,
    ]

    private init() {}

    // MARK: - 装载

    /// 从种子装载并编译正则。可重复调用（幂等）。
    /// 对应 Android 的 `initialize(context)` → `loadFromAsset` → `getCompiledPatterns()`。
    @discardableResult
    func load(seed: SecuritySeedLoader.Seed? = nil) -> Bool {
        lock.lock()
        defer { lock.unlock() }

        let source: SecuritySeedLoader.Seed
        do {
            source = try seed ?? SecuritySeedLoader.load()
        } catch {
            log.error("过滤词表装载失败：\(String(describing: error), privacy: .public)")
            return false
        }

        var table: [ViolationLevel: [NSRegularExpression]] = [:]
        var ok = 0
        var failed = 0

        for (key, patterns) in source.filterPatterns {
            guard let level = Self.level(fromKey: key) else {
                log.warning("过滤词表出现未知等级键：\(key, privacy: .public)")
                continue
            }
            var list: [NSRegularExpression] = []
            for pattern in patterns {
                do {
                    list.append(try NSRegularExpression(pattern: pattern))
                    ok += 1
                } catch {
                    // 与 Android 一致：坏正则静默跳过
                    failed += 1
                }
            }
            table[level] = list
        }

        compiled = table
        patternCount = ok
        failedPatternCount = failed
        log.info("内容过滤已装载：正则 \(ok) 条（编译失败 \(failed) 条）")
        return ok > 0
    }

    var isLoaded: Bool { lock.withLock { !compiled.isEmpty } }

    var stats: (patterns: Int, failed: Int) {
        lock.withLock { (patternCount, failedPatternCount) }
    }

    // MARK: - 检查入口（与 Android 的四个入口一一对应）

    /// 对应 `check(text, skipLanguageCheck = false)`。
    func check(_ text: String) -> CheckResult { checkBlocking(text) }

    /// 对应 `checkFull`：与 `check` 行为相同（Android 侧两者实现一致）。
    func checkFull(_ text: String) -> CheckResult { checkBlocking(text) }

    /// 对应 `checkInput`：**仅 HIGH 及以上才算违规**，低于 HIGH 一律放行。
    /// 这是输入侧的门槛，比输出侧宽松。
    func checkInput(_ text: String) -> CheckResult {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return .clean }
        let result = checkBlocking(text)
        guard result.isViolating, result.level >= .high else { return .clean }
        return result
    }

    /// 对应 Android 的 `runCatching { ContentFilter.checkInput(msg) }.getOrNull()`：
    /// **过滤器未装载时返回 nil**（Android 侧即 `inputCheck == null` → 「安全检查异常」）。
    /// 非可空的 `checkInput` 无法表达这个状态，故单独提供本方法。
    func checkInputOrNil(_ text: String) -> CheckResult? {
        guard isLoaded else { return nil }
        return checkInput(text)
    }

    func isViolating(_ text: String) -> Bool { check(text).isViolating }

    struct OutputSafetyResult: Sendable, Equatable {
        let isSafe: Bool
        let level: ViolationLevel
        let reason: String
    }

    /// 对应 `checkOutputSafety`：违规且等级 ≥ HIGH 视为不安全。
    func checkOutputSafety(_ text: String) -> OutputSafetyResult {
        let result = check(text)
        let unsafe = result.isViolating && result.level >= .high
        return OutputSafetyResult(
            isSafe: !unsafe,
            level: unsafe ? result.level : .none,
            reason: unsafe ? result.reason : ""
        )
    }

    /// 对应 `getBanDays`。
    static func banDays(for level: ViolationLevel) -> Int {
        switch level {
        case .none: return 0
        case .low: return 1
        case .medium: return 3
        case .high: return 7
        case .severe: return 10
        case .critical: return 31
        case .extreme: return 365
        }
    }

    /// 对应 `getLevelName`。
    static func levelName(for level: ViolationLevel) -> String {
        switch level {
        case .none: return "正常"
        case .low: return "轻度违规"
        case .medium: return "中度违规"
        case .high: return "高度违规"
        case .severe: return "严重违规"
        case .critical: return "极严重违规"
        case .extreme: return "极端违规"
        }
    }

    // MARK: - 核心匹配

    /// 对应 `checkKeywords`：按等级从高到低，**第一个有命中的等级即返回**。
    func checkKeywords(_ text: String) -> CheckResult {
        guard let table = lock.withLock({ compiled }), !table.isEmpty else { return .clean }
        let range = NSRange(text.startIndex..., in: text)

        for level in Self.scanOrder {
            guard let patterns = table[level], !patterns.isEmpty else { continue }
            var found: [String] = []
            for pattern in patterns {
                for match in pattern.matches(in: text, options: [], range: range) {
                    if let r = Range(match.range, in: text) {
                        found.append(String(text[r]))
                    }
                }
            }
            if !found.isEmpty {
                return CheckResult(
                    isViolating: true,
                    level: level,
                    reason: "检测到\(Self.levelName(for: level))内容",
                    matchedKeywords: Array(Set(found)).sorted()
                )
            }
        }
        return .clean
    }

    /// 对应 `checkBlocking`：关键词匹配 + 语义预处理检测，取**等级最高**者。
    func checkBlocking(_ text: String) -> CheckResult {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return .clean }

        var results: [CheckResult] = [checkKeywords(text)]

        let semantic = SemanticDetector.preprocessAndDetect(text)
        if let highest = semantic.max(by: { $0.level < $1.level }) {
            log.warning("预处理检测触发：\(highest.reason, privacy: .public)")
            results.append(CheckResult(
                isViolating: true,
                level: highest.level,
                reason: "\(highest.reason) (预处理检测)",
                matchedKeywords: highest.matchedTerms
            ))
        }

        let violating = results.filter(\.isViolating)
        guard let top = violating.max(by: { $0.level < $1.level }) else { return .clean }
        return top
    }

    // MARK: - 辅助

    private static func level(fromKey key: String) -> ViolationLevel? {
        ViolationLevel.allCases.first { $0.key == key }
    }
}
