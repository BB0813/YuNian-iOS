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
    @State private var items: [StickerListParser.ParsedSticker] = []
    @State private var status: Status?
    @State private var busy = false
    @State private var progress = (done: 0, total: 0)

    private enum Status: Equatable {
        case ok(String)
        case failure(String)
    }

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
                .onChange(of: rawText) { _, _ in items = StickerListParser.parse(rawText) }

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
            // 「括号里的描述会变成名字」这一点必须写出来 ——
            // 名字决定标签、标签决定模型怎么挑表情（第 226 轮）。
            // ⚠️ 不用 Markdown 加粗：这里用了字符串拼接，而 SwiftUI 的
            // Markdown 只对**字面量**生效，拼接后 `**…**` 会原样显示成星号。
            Text("从 Word 里直接复制正文即可。三种写法都认：「名称：链接」「名称链接」，"
                 + "以及链接后面括号里的描述。括号内容会成为这张表情的名字，"
                 + "并据此生成标签 —— 模型是按标签挑表情的，写得越具体越容易在合适语境被挑中。"
                 + "重复导入不会多出图片，但会用新名字覆盖旧名字。链接要能直接打开图片。")
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
        var updated = 0
        var skipped = 0
        var failed = 0

        for item in items {
            progress.done += 1
            do {
                let (data, _) = try await URLSession.shared.data(from: item.url)
                let ext = Self.fileExtension(for: data, fallback: item.url.pathExtension)
                let fileName = "custom_\(Int(Date().timeIntervalSince1970 * 1000))_\(Int.random(in: 0..<1000)).\(ext)"
                let outcome = try repository.importSticker(
                    data: data,
                    fileName: fileName,
                    description: item.name,
                    userTags: []
                )
                // 「更新」= 这张图库里已有，本次只是把名字（进而标签）补齐
                switch outcome {
                case .inserted: imported += 1
                case .updated: updated += 1
                }
            } catch let error as StickerImportRepository.ImportError {
                // 重复 / 达上限属"可预期"，单独计数，不当作失败刷屏
                if error == .duplicate || error == .limitReached { skipped += 1 } else { failed += 1 }
            } catch {
                // 下载失败（链接失效、网络不通）也算失败
                failed += 1
            }
        }

        var parts = ["导入 \(imported) 张"]
        if updated > 0 { parts.append("更新 \(updated) 张的名字") }
        if skipped > 0 { parts.append("跳过 \(skipped) 张（重复或已达上限）") }
        if failed > 0 { parts.append("失败 \(failed) 张（链接失效或网络不通）") }
        let summary = parts.joined(separator: "，")
        status = (imported + updated) > 0 ? .ok(summary) : .failure(summary)
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
