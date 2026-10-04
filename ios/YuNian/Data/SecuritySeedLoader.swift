import Foundation
import GRDB
import os

/// 安全基线种子 —— 加载 `SecuritySeed.json` 并写入 `keywords` / `quiz_questions`。
///
/// 对应 Android 侧 `SecurityDataSeeder`（`seedIfEmpty()` 语义：表非空则不重复播种）。
///
/// ## ⚠️ 关于数据里的「双重编码乱码」—— 这是有意保留的
/// `SecuritySeed.json` 里的中日文条目，在 Android 侧就是乱码形态：
/// UTF-8 字节被按 Latin-1 解读了一次（例如「児童」→ `U+00E5 U+0085 U+0090 ...`）。
///
/// 实测数据（由 `Tools/generate_security_seed.py` 统计并写入 `_diagnostics`）：
///   - `keywords`       94 / 284 条为双重编码
///   - `filterPatterns` 317 / 583 条为双重编码
///
/// **本文件如实保留该形态**，原因是：如果 iOS 单方面把它「修好」，
/// iOS 的实际过滤强度就会高于 Android —— 这是一个跨端行为分歧，
/// 属于产品/安全决策，不该由移植方单方面决定。
///
/// 修复是确定性的（`restoreDoubleEncoded` 已实现并有单测），
/// 但**必须两端同时启用**。开关见 `restoreDoubleEncodedEntries`。
enum SecuritySeedLoader {

    private static let log = Logger(subsystem: "com.yunian.ai", category: "security.seed")

    /// 是否在加载时把双重编码条目还原成正确文本。
    ///
    /// **默认 false**：与 Android 当前行为保持一致。
    /// 若团队决定修复（需同时改 Android 的 `d()` 等价实现），把这里改成 true 即可，
    /// 但请先把 Android 侧一并改掉，否则两端过滤强度会不同。
    static let restoreDoubleEncodedEntries = false

    // MARK: - 数据模型

    struct Keyword: Sendable, Equatable {
        let keyword: String
        let pattern: String?
        let level: String
        let type: String
        let banDays: Int
    }

    struct QuizQuestion: Sendable, Equatable {
        let question: String
        let options: String
        let correctIndex: Int
        let category: String
        let difficulty: String
    }

    struct Seed: Sendable {
        let keywords: [Keyword]
        let quizQuestions: [QuizQuestion]
        /// level → 过滤正则（对应 ContentFilter 使用的加密资源）
        let filterPatterns: [String: [String]]
        let diagnostics: Diagnostics

        struct Diagnostics: Sendable {
            let keywordsDoubleEncoded: Int
            let keywordsTotal: Int
            let filterPatternsDoubleEncoded: Int
            let filterPatternsTotal: Int
        }
    }

    enum LoadError: Error, CustomStringConvertible {
        case resourceMissing
        case malformed(String)

        var description: String {
            switch self {
            case .resourceMissing:
                return "找不到 SecuritySeed.json —— 请确认它已被加入 App Bundle（XcodeGen 会把 Resources/ 下的非编译文件作为资源）"
            case let .malformed(detail):
                return "SecuritySeed.json 结构异常：\(detail)"
            }
        }
    }

    // MARK: - 加载

    static func load(bundle: Bundle = .main) throws -> Seed {
        guard let url = bundle.url(forResource: "SecuritySeed", withExtension: "json") else {
            throw LoadError.resourceMissing
        }
        let data = try Data(contentsOf: url)
        return try parse(data)
    }

    static func parse(_ data: Data) throws -> Seed {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw LoadError.malformed("顶层不是对象")
        }

        let restore = restoreDoubleEncodedEntries

        guard let rawKeywords = root["keywords"] as? [[String: Any]] else {
            throw LoadError.malformed("缺少 keywords 数组")
        }
        let keywords: [Keyword] = rawKeywords.map { item in
            Keyword(
                keyword: transform(item["keyword"] as? String ?? "", restore: restore),
                pattern: (item["pattern"] as? String).map { transform($0, restore: restore) },
                level: item["level"] as? String ?? "LOW",
                type: item["type"] as? String ?? "KEYWORD",
                banDays: item["banDays"] as? Int ?? 0
            )
        }

        guard let rawQuiz = root["quizQuestions"] as? [[String: Any]] else {
            throw LoadError.malformed("缺少 quizQuestions 数组")
        }
        let quiz: [QuizQuestion] = rawQuiz.map { item in
            QuizQuestion(
                question: item["question"] as? String ?? "",
                options: item["options"] as? String ?? "",
                correctIndex: item["correctIndex"] as? Int ?? 0,
                category: item["category"] as? String ?? "SAFETY",
                difficulty: item["difficulty"] as? String ?? "MEDIUM"
            )
        }

        guard let rawPatterns = root["filterPatterns"] as? [String: [String]] else {
            throw LoadError.malformed("缺少 filterPatterns 对象")
        }
        let patterns = rawPatterns.mapValues { list in
            list.map { transform($0, restore: restore) }
        }

        let diagRaw = root["_diagnostics"] as? [String: Any]
        let counts = diagRaw?["doubleEncodedCounts"] as? [String: Any] ?? [:]
        let diagnostics = Seed.Diagnostics(
            keywordsDoubleEncoded: counts["keywords"] as? Int ?? 0,
            keywordsTotal: counts["keywordsTotal"] as? Int ?? keywords.count,
            filterPatternsDoubleEncoded: counts["filterPatterns"] as? Int ?? 0,
            filterPatternsTotal: counts["filterPatternsTotal"] as? Int ?? 0
        )

        return Seed(
            keywords: keywords,
            quizQuestions: quiz,
            filterPatterns: patterns,
            diagnostics: diagnostics
        )
    }

    // MARK: - 双重编码还原

    /// 把「UTF-8 字节被按 Latin-1 解读」的乱码还原为正确文本。
    ///
    /// 不可还原时**原样返回**（ASCII 条目本就是正确的，走这里会直接返回原值）。
    /// 对应 Python 侧的 `s.encode('latin-1').decode('utf-8')`。
    static func restoreDoubleEncoded(_ text: String) -> String {
        guard !text.isEmpty else { return text }

        // 先确认所有标量都落在 Latin-1 可表示区间，否则一定不可还原
        var bytes: [UInt8] = []
        bytes.reserveCapacity(text.unicodeScalars.count)
        for scalar in text.unicodeScalars {
            guard scalar.value <= 0xFF else { return text }
            bytes.append(UInt8(scalar.value))
        }

        guard let restored = String(bytes: bytes, encoding: .utf8) else {
            return text
        }
        return restored
    }

    private static func transform(_ text: String, restore: Bool) -> String {
        restore ? restoreDoubleEncoded(text) : text
    }

    // MARK: - 播种

    /// 建表后按 `seedIfEmpty` 语义写入（表非空则跳过），对应 Android 的
    /// `SecurityDataSeeder.seedIfEmpty()`。
    @discardableResult
    static func seedIfEmpty(_ database: YuNianDatabase, bundle: Bundle = .main) throws -> String {
        let seed = try load(bundle: bundle)

        let inserted = try database.pool.write { db -> (Int, Int) in
            let existingKeywords = try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM keywords") ?? 0
            let existingQuiz = try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM quiz_questions") ?? 0

            let now = Int64(Date().timeIntervalSince1970 * 1000)
            var kw = 0
            if existingKeywords == 0 {
                for k in seed.keywords {
                    try db.execute(sql: """
                    INSERT INTO keywords
                      (keyword, pattern, level, type, banDays, isEnabled, createdAt, checksum)
                    VALUES (?,?,?,?,?,1,?,'')
                    """, arguments: [k.keyword, k.pattern, k.level, k.type, k.banDays, now])
                    kw += 1
                }
            }

            var qz = 0
            if existingQuiz == 0 {
                for q in seed.quizQuestions {
                    try db.execute(sql: """
                    INSERT INTO quiz_questions
                      (question, options, correctIndex, category, difficulty, isEnabled, createdAt, checksum)
                    VALUES (?,?,?,?,?,1,?,'')
                    """, arguments: [
                        q.question, q.options, q.correctIndex, q.category, q.difficulty, now,
                    ])
                    qz += 1
                }
            }
            return (kw, qz)
        }

        let summary = "keywords +\(inserted.0) / quiz +\(inserted.1)"
        log.info("安全基线播种完成：\(summary, privacy: .public)")

        if seed.diagnostics.keywordsDoubleEncoded > 0 {
            log.warning("""
            安全基线含双重编码条目（与 Android 一致，未修复）：\
            keywords \(seed.diagnostics.keywordsDoubleEncoded)/\(seed.diagnostics.keywordsTotal)，\
            filterPatterns \(seed.diagnostics.filterPatternsDoubleEncoded)/\(seed.diagnostics.filterPatternsTotal)。\
            这些中日文规则在当前形态下无法匹配真实输入。详见 ios/README.md。
            """)
        }
        return summary
    }
}
