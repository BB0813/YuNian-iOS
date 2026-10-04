import Foundation

/// 中文全文检索分词器 —— 与 Android 侧 `MessageSearchTokenizer.kt` **逐字等价**。
///
/// ⚠️ 这是跨端一致性要求最高的组件之一：token 格式一旦不同，Android 写入的
/// 检索索引在 iOS 上就查不出来，反之亦然（两端共用同一个 `message_search_index` 表）。
///
/// 必须严格保持的约定（对应 `core/database/.../MessageSearchTokenizer.kt`）：
///   - 先 `lowercased()`（等价 Kotlin 的 `lowercase(Locale.ROOT)`），再按 **Unicode 码点**切分
///   - unigram：`u<hex>z`，hex 为**小写、无前导零**
///   - bigram ：`b<hex>x<hex>z`
///   - 索引侧：全部 unigram + 全部相邻 bigram，空格分隔
///   - 查询侧：1 个码点 → 只有 unigram；≥2 个码点 → **只用 bigram，不掺 unigram**，
///             整体用双引号包裹（构成 FTS 短语查询，保证 bigram 相邻）
///
/// Android 侧的 FTS 表是 FTS4 且**未指定 tokenizer**（走 SQLite 默认 `simple`）。
/// 由于本分词器的输出是纯 ASCII（`[a-z0-9]`），`simple` 分词器对它的切分正好符合预期 ——
/// 中文原文从不交给 SQLite 的分词器。这也意味着 iOS 侧用 FTS4 还是 FTS5 语义无差别。
enum MessageSearchTokenizer {

    /// 索引一行文本 → 写入 `message_search_index.tokens` 的字符串。
    static func indexTokens(_ content: String) -> String {
        let cps = normalizedCodePoints(content)
        guard !cps.isEmpty else { return "" }

        var tokens: [String] = []
        tokens.reserveCapacity(cps.count + max(0, cps.count - 1))
        for cp in cps {
            tokens.append(unigram(cp))
        }
        if cps.count > 1 {
            for i in 0..<(cps.count - 1) {
                tokens.append(bigram(cps[i], cps[i + 1]))
            }
        }
        return tokens.joined(separator: " ")
    }

    /// 查询串 → FTS `MATCH` 表达式；空串返回 nil（调用方应跳过检索）。
    static func matchQuery(_ query: String) -> String? {
        let cps = normalizedCodePoints(query)
        guard !cps.isEmpty else { return nil }

        let tokens: [String]
        if cps.count == 1 {
            tokens = [unigram(cps[0])]
        } else {
            tokens = (0..<(cps.count - 1)).map { bigram(cps[$0], cps[$0 + 1]) }
        }
        return "\"" + tokens.joined(separator: " ") + "\""
    }

    // MARK: - 内部

    private static func normalizedCodePoints(_ value: String) -> [UInt32] {
        value.lowercased().unicodeScalars.map(\.value)
    }

    private static func unigram(_ codePoint: UInt32) -> String {
        "u\(String(codePoint, radix: 16))z"
    }

    private static func bigram(_ first: UInt32, _ second: UInt32) -> String {
        "b\(String(first, radix: 16))x\(String(second, radix: 16))z"
    }
}
