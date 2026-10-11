import SwiftUI
import UniformTypeIdentifiers

/// 导入角色卡（Tavern V1/V2/V3 JSON，或 PNG 内嵌 `chara`）。
///
/// ## 为什么值得做
/// 这是这个品类里**拿到角色最常见的路径**：别人的角色卡是 PNG 或 JSON，
/// 而引擎早就能解析它们（`card_import.rs`）—— 只是**两端都没有界面**。
///
/// ## 这一页的取舍
/// - **先预览再创建**：卡片字段与落位逐条摊开，用户看清了再决定。
/// - **开场白如实处理**：`companions` 表没有开场白列 → 不假装能存，
///   显示出来 + 一键复制（用户可自己贴成第一条消息）。
/// - **内嵌世界书是可选开关**：导入后**绑定到这个角色**
///   （按伴侣合成才会算上它），并说明绑定语义。
struct CharacterCardImportView: View {

    /// 创建成功后的回调（通讯录刷新）。
    var onImported: () -> Void

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    enum Source: String, CaseIterable, Identifiable {
        case paste, file
        var id: String { rawValue }
        var title: String { self == .paste ? "粘贴文本" : "选择文件" }
    }

    @State private var source: Source = .paste
    @State private var draft = ""
    @State private var preview: CharacterCardImporter.Preview?
    @State private var importWorld = true
    @State private var showFilePicker = false
    @State private var notice: String?
    @State private var copied = false

    var body: some View {
        let c = YNTheme.palette(scheme)
        return List {
            inputSection(c)
            if let preview {
                previewSection(preview, colors: c)
                optionsSection(preview, colors: c)
            }
            if let notice {
                Section { Text(notice).font(.caption).foregroundStyle(c.textSecondary) }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("导入角色卡")
        .navigationBarTitleDisplayMode(.inline)
        .tint(c.accent)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("创建角色") { save() }
                    .disabled(preview == nil)
            }
        }
        .fileImporter(isPresented: $showFilePicker,
                      allowedContentTypes: [.json, .png, .image],
                      allowsMultipleSelection: false) { result in
            handleFilePick(result)
        }
    }

    // MARK: - 输入

    private func inputSection(_ c: YNTheme.Palette) -> some View {
        Section {
            Picker("来源", selection: $source) {
                ForEach(Source.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)

            if source == .paste {
                TextEditor(text: $draft)
                    .font(.caption.monospaced())
                    .frame(minHeight: 160)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button {
                    parsePasted()
                } label: {
                    Label("解析", systemImage: "wand.and.stars")
                }
                .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            } else {
                Button {
                    showFilePicker = true
                } label: {
                    Label("选择 PNG / JSON 角色卡", systemImage: "doc.badge.plus")
                }
            }
        } header: {
            Text("角色卡")
        } footer: {
            Text("""
            支持 Tavern V1/V2/V3 的 JSON，以及 PNG 里内嵌 `tEXt/chara` 的角色卡（事实标准）。
            PNG 走的是引擎里已有的最小 chunk 解析，不是本机另写一套。
            """)
        }
    }

    // MARK: - 预览

    private func previewSection(_ preview: CharacterCardImporter.Preview,
                                colors c: YNTheme.Palette) -> some View {
        Section {
            LabeledContent("名字", value: preview.resolvedName)
            fieldRow("性格", preview.personality, c)
            fieldRow("描述", preview.description, c)
            fieldRow("系统提示词", preview.systemPrompt, c)
            if !preview.firstMes.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("开场白").font(.subheadline)
                    Text(preview.firstMes)
                        .font(.caption)
                        .foregroundStyle(c.textSecondary)
                        .lineLimit(4)
                    Button {
                        UIPasteboard.general.string = preview.firstMes
                        copied = true
                    } label: {
                        Label(copied ? "已复制" : "复制开场白", systemImage: "doc.on.doc")
                            .font(.caption)
                    }
                }
                .padding(.vertical, 2)
            }
            if preview.hasWorld {
                LabeledContent("内嵌世界书", value: "\(preview.worldEntryCount) 条")
            } else if !preview.worldJSON.isEmpty {
                LabeledContent("内嵌世界书", value: "有数据但解析不出条目")
            }
        } header: {
            Text("预览（字段落位已写在各节页脚）")
        } footer: {
            Text("""
            落位：名字 → 名字；性格 → 人设；**描述 → 背景**（ST 的 description 是设定正文）；
            系统提示词 → 自定义角色指令。
            ⚠️ **开场白不会落库** —— `companions` 表没有这一列（两端都没有），
            所以这里只显示 + 复制，你要自己贴成第一条消息。
            """)
        }
    }

    private func fieldRow(_ title: String, _ value: String,
                          _ c: YNTheme.Palette) -> some View {
        Group {
            if !value.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.subheadline)
                    Text(value)
                        .font(.caption)
                        .foregroundStyle(c.textSecondary)
                        .lineLimit(4)
                        .textSelection(.enabled)
                }
                .padding(.vertical, 2)
            }
        }
    }

    // MARK: - 选项

    private func optionsSection(_ preview: CharacterCardImporter.Preview,
                                colors c: YNTheme.Palette) -> some View {
        Section {
            Toggle("同时导入卡片自带的世界书", isOn: $importWorld)
                .tint(c.accent)
                .disabled(!preview.hasWorld)
        } header: {
            Text("选项")
        } footer: {
            Text(preview.hasWorld
                 ? "导入后它**归这个角色所有**（`worldbooks.companionId` = 该角色），只有 ta 的回合会带上（按伴侣合成）。想停用就去「世界书」页关掉那一本。"
                 : "这张卡没有可用的内嵌世界书，所以这一项不可用。")
        }
    }

    // MARK: - 解析 / 保存

    private func parsePasted() {
        copied = false
        preview = CharacterCardImporter.preview(json: draft)
        notice = preview == nil ? "解析失败：这不是一份可识别的角色卡 JSON（错误原因在绑定层丢失，见导入器注释）" : nil
        if preview != nil { importWorld = true }
    }

    private func handleFilePick(_ result: Result<[URL], Error>) {
        copied = false
        switch result {
        case .failure:
            notice = "选择文件失败"
        case .success(let urls):
            guard let url = urls.first else { return }
            // 安全作用域：不加会读不到 iCloud/其它 App 的文件
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else {
                notice = "读不到这个文件（可能是 iCloud 未下载或权限不足）"
                return
            }
            preview = CharacterCardImporter.preview(bytes: data)
            notice = preview == nil
                ? "解析失败：不是可识别的角色卡（PNG 需内嵌 `tEXt/chara`，JSON 需 V1/V2/V3 结构）"
                : "已解析：\(url.lastPathComponent)"
            if preview != nil {
                importWorld = true
                draft = ""
            }
        }
    }

    private func save() {
        guard let preview else { return }
        do {
            try CharacterCardImporter.save(
                preview,
                importWorld: importWorld,
                companions: environment.companions,
                database: environment.database
            )
            onImported()
            dismiss()
        } catch let error as CharacterCardImporter.ImportError {
            notice = error.message
        } catch {
            notice = "创建失败：写库出错（见 App 日志 card.import）"
        }
    }
}
