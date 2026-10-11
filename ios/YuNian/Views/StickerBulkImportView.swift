import SwiftUI
import Foundation
import UIKit

/// 批量导入表情（从「名称 + 图片直链」清单）。
///
/// ## 为什么是粘贴清单，而不是选文件
/// 用户手上流传的表情包资料基本是 docx（Word 里一行行写着
/// `名称：https://…/xxx.gif`）。而 **`.docx` 本质是 ZIP**，
/// 本项目没有引入任何 ZIP 库（`Package.swift` 零第三方依赖），
/// Swift 侧解不开它。引入一个压缩库只为读正文，代价远大于收益。
///
/// 所以这里做**粘贴正文**：从 Word 里复制那段清单贴进来即可。
/// 容器格式不重要，**内容才重要**。
struct StickerBulkImportView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var rawText = ""
    @State private var items: [ParsedSticker] = []
    @State private var status: Status?
    @State private var busy = false
    @State private var progress = (done: 0, total: 0)

    private enum Status: Equatable {
        case ok(String)
        case failure(String)
    }

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
    /// 扩展名改由下载后**按魔数判定**（见 fileExtension）——
    /// 反正图床返回的 Content-Type 也不可信。
    private static let urlPattern = #"https?://[A-Za-z0-9\-._~:/?#@!$&*+,;=%]+"#

    var body: some View {
        let c = YNTheme.palette(scheme)

        List {
            inputSection
            previewSection
            if let status { statusSection(status, colors: c) }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("批量导入")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if busy {
                    ProgressView().controlSize(.small)
                } else {
                    Button("导入") { Task { await runImport() } }
                        .disabled(items.isEmpty)
                }
            }
        }
    }

    // MARK: - 输入

    private var inputSection: some View {
        Section {
            TextEditor(text: $rawText)
                .frame(minHeight: 150)
                .font(.footnote)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onChange(of: rawText) { _, _ in items = Self.parse(rawText) }

            HStack {
                Text("解析出 \(items.count) 条")
                    .font(.caption)
                    .foregroundStyle(YNTheme.palette(scheme).textSecondary)
                Spacer()
                if !rawText.isEmpty {
                    Button("清空") {
                        rawText = ""
                        items = []
                    }
                    .font(.caption)
                }
            }
        } header: {
            Text("把清单粘贴到这里")
        } footer: {
            Text("从 Word 里直接复制正文即可。支持「名称：链接」「名称链接」，以及链接后面括号里写描述这三种写法。链接要能直接打开图片。")
        }
    }

    // MARK: - 预览

    @ViewBuilder
    private var previewSection: some View {
        let c = YNTheme.palette(scheme)
        if !items.isEmpty {
            Section {
                // 先给预览再导：解析错了能当场看出来，
                // 而不是导完几十张才发现名字全不对。
                ForEach(items.prefix(12)) { item in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.name)
                            .font(.subheadline)
                            .foregroundStyle(c.textPrimary)
                            .lineLimit(1)
                        Text(item.urlString)
                            .font(.caption2)
                            .foregroundStyle(c.textTertiary)
                            .lineLimit(1)
                    }
                }
                if items.count > 12 {
                    Text("… 还有 \(items.count - 12) 条")
                        .font(.caption)
                        .foregroundStyle(c.textTertiary)
                }
            } header: {
                Text("导入预览")
            } footer: {
                if busy {
                    Text("正在导入 \(progress.done)/\(progress.total)…")
                }
            }
        }
    }

    @ViewBuilder
    private func statusSection(_ status: Status, colors c: YNTheme.Palette) -> some View {
        Section {
            // T6：图标 + 文字，不只靠颜色
            switch status {
            case let .ok(text):
                Label(text, systemImage: "checkmark.circle.fill")
                    .foregroundStyle(c.success)
                    .font(.subheadline)
            case let .failure(text):
                Label(text, systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(c.warning)
                    .font(.subheadline)
            }
        }
    }

    // MARK: - 解析

    /// 扫出全部图片直链，并取每个链接**之前**的那段文字当名称。
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

            // 名称 = 上一个链接结束 → 本链接开始之间的文字
            let between = ns.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
            cursor = match.range.location + match.range.length

            // URL 之后紧跟的括号内容（若有）往往比前面的标题更具体
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
    /// 取字符串**开头**的括号内容：`（小猫拿红包）后面的…` → `小猫拿红包`。
    private static func leadingParenthetical(_ text: String) -> String? {
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
        case "\u{3010}": closer = "\u{3011}"   // 【 】
        case "[": closer = "]"
        default: return nil
        }

        guard let end = body.firstIndex(of: closer), end > body.startIndex else { return nil }
        let inner = body[body.index(after: body.startIndex)..<end]
        let value = inner.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    /// 去掉分隔符与包裹的标点。
    private static func clean(_ text: String) -> String {
        text.trimmingCharacters(in: CharacterSet(charactersIn: " \n\t：:，,、;；-—–·"))
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func nameFromURL(_ url: URL) -> String {
        url.deletingPathExtension().lastPathComponent
    }

    // MARK: - 导入

    private func runImport() async {
        guard let database = environment.database else {
            status = .failure("数据库尚未就绪。")
            return
        }
        busy = true
        progress = (0, items.count)
        defer { busy = false }

        let repository = StickerImportRepository(database: database)
        var imported = 0
        var skipped = 0
        var failed = 0

        for item in items {
            progress.done += 1
            do {
                let (data, _) = try await URLSession.shared.data(from: item.url)
                let ext = Self.fileExtension(for: data, fallback: item.url.pathExtension)
                let fileName = "custom_\(Int(Date().timeIntervalSince1970 * 1000))_\(Int.random(in: 0..<1000)).\(ext)"
                _ = try repository.importSticker(
                    data: data,
                    fileName: fileName,
                    description: item.name,
                    userTags: []
                )
                imported += 1
            } catch let error as StickerImportRepository.ImportError {
                // 重复 / 达上限属"可预期"，单独计数，不当作失败刷屏
                if error == .duplicate || error == .limitReached { skipped += 1 } else { failed += 1 }
            } catch {
                // 下载失败（链接失效、网络不通）也算失败
                failed += 1
            }
        }

        var parts = ["导入 \(imported) 张"]
        if skipped > 0 { parts.append("跳过 \(skipped) 张（重复或已达上限）") }
        if failed > 0 { parts.append("失败 \(failed) 张（链接失效或网络不通）") }
        let summary = parts.joined(separator: "，")
        status = imported > 0 ? .ok(summary) : .failure(summary)
    }

    /// 按魔数判扩展名；判不出再用 URL 的扩展名。
    /// 不信任服务端声明的类型 —— 图床返回错 Content-Type 很常见。
    private static func fileExtension(for data: Data, fallback: String) -> String {
        if data.starts(with: [0x89, 0x50, 0x4E, 0x47]) { return "png" }
        if data.starts(with: [0x47, 0x49, 0x46]) { return "gif" }
        if data.starts(with: [0x52, 0x49, 0x46, 0x46]) { return "webp" }
        if data.starts(with: [0xFF, 0xD8, 0xFF]) { return "jpg" }
        return fallback.isEmpty ? "jpg" : fallback.lowercased()
    }
}
