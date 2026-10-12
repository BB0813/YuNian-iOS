import SwiftUI

/// 角色页（角色中枢）—— 借鉴 Aru 协作者详情页的**信息层级**（不是抄皮肤）。
///
/// ## 为什么要有这一页
/// 通讯录点进去是聊天、左滑是编辑人设 —— 但"这个角色"其实还有记忆、世界书、
/// 音色、会话这些状态，它们**此前没有 per-companion 的家**：只能去「我」页的
/// 全局列表里翻，而且世界书页把归属显示成 `角色 #7`。
///
/// Aru 那套做法给了两个可直接借鉴的点：
/// 1. **副标题写状态与数量**（`1 条记忆 · 0 份长期资料 · 2 段旧对话`）——
///    不点进去就知道配了什么，包括 0 和"没带上"；
/// 2. **顶部一张卡**：大头像 + 名字 + 一行三格的状态条。
/// 数字全部来自 `CompanionHubStats`（照引擎既有口径算，可单测）。
///
/// ## 两个刻意的取舍
/// - **不做 Aru 的"每个角色一套主题"**：我们 `PRODUCT.md` 禁止满屏渐变/发光，
///   皮肤级定制先不做（画布仍是全局一套）。
/// - **删除角色仍只在通讯录列表里**（左滑）：同一个破坏性操作不留两个入口。
struct CompanionHubView: View {

    let companionId: Int64
    /// 从聊天页以 sheet 方式打开时为 true（需要自己的导航栈）。
    var embeddedInSheet = false

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    @State private var companion: CompanionRepository.Companion?
    @State private var stats = CompanionHubStats()
    @State private var loadError: String?
    @State private var showChat = false

    var body: some View {
        let c = YNTheme.palette(scheme)
        return Group {
            if embeddedInSheet {
                NavigationStack { content(c) }
            } else {
                content(c)
            }
        }
        .task { reload() }
        .onAppear { reload() }
    }

    @ViewBuilder
    private func content(_ c: YNTheme.Palette) -> some View {
        List {
            if let companion {
                headerCard(companion, c)
                rows(companion, c)
                if let loadError {
                    Section { Text(loadError).font(.caption).foregroundStyle(c.textSecondary) }
                }
            } else if loadError == nil {
                Section { ProgressView().frame(maxWidth: .infinity) }
            } else {
                Section {
                    Label(loadError ?? "", systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(c.warning)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle(companion?.name ?? "角色")
        .navigationBarTitleDisplayMode(.inline)
        .tint(c.accent)
        .toolbar {
            if embeddedInSheet {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
            }
        }
        // 会话行：直接进这个角色的聊天（主操作仍然是聊天）
        .navigationDestination(isPresented: $showChat) {
            ChatView(companionId: companionId)
        }
    }

    // MARK: - 顶部卡（Aru 的「头像 + 名字 + 三格状态条」）

    @ViewBuilder
    private func headerCard(_ companion: CompanionRepository.Companion,
                            _ c: YNTheme.Palette) -> some View {
        Section {
            VStack(spacing: YNTheme.Space.md) {
                // 与首页/通讯录同一套头像语言（这里是"身份"语义 → 品牌色光晕）
                YNAvatarHalo(avatarURL: companion.avatarUrl, name: companion.name, size: 76)

                Text(companion.name)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(c.textPrimary)

                // 三格状态条：细竖线分隔，数字大、标签小（对齐全靠 frame）
                HStack(spacing: 0) {
                    statCell("记忆", "\(stats.memoryActive)", c)
                    divider(c)
                    statCell("世界书", "\(stats.worldbooksEffective)", c)
                    divider(c)
                    statCell("对话", "\(stats.messages)", c)
                }
                .padding(.top, 2)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, YNTheme.Space.md)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
            .accessibilityElement(children: .combine)
            .accessibilityLabel("""
                \(companion.name)。记忆 \(stats.memoryActive) 条，\
                世界书生效 \(stats.worldbooksEffective) 本，对话 \(stats.messages) 条
                """)
        }
    }

    private func statCell(_ title: String, _ value: String, _ c: YNTheme.Palette) -> some View {
        VStack(spacing: 3) {
            Text(value)
                .font(.title3.weight(.medium).monospacedDigit())
                .foregroundStyle(c.textPrimary)
            Text(title)
                .font(.caption2)
                .foregroundStyle(c.textTertiary)
        }
        .frame(maxWidth: .infinity)
    }

    private func divider(_ c: YNTheme.Palette) -> some View {
        Rectangle()
            .fill(c.separator)
            .frame(width: 0.5, height: 26)
    }

    // MARK: - 分组行（副标题写状态与数量）

    @ViewBuilder
    private func rows(_ companion: CompanionRepository.Companion,
                      _ c: YNTheme.Palette) -> some View {
        Section {
            NavigationLink {
                CompanionEditView(companion: companion, onSaved: reload)
            } label: {
                YNSettingsRow(icon: "person.text.rectangle",
                              title: "人设",
                              subtitle: stats.personaSummary)
            }

            NavigationLink {
                MemoryView(companionId: companion.id, companionName: companion.name)
            } label: {
                YNSettingsRow(icon: "brain",
                              title: "记忆",
                              subtitle: stats.memorySubtitle)
            }

            NavigationLink {
                WorldbookLibraryView()
            } label: {
                YNSettingsRow(icon: "book.closed",
                              title: "世界书",
                              subtitle: stats.worldbookSubtitle)
            }

            NavigationLink {
                FeatureRouteView()
            } label: {
                YNSettingsRow(icon: "waveform",
                              title: "朗读音色",
                              subtitle: stats.voiceSubtitle)
            }
        } header: {
            Text("这个角色")
        } footer: {
            Text("""
            「世界书」那一行的数字按引擎的绑定规则算：**规则上没有带上**与
            「规则上带上了、但书里没有可用条目」是两件事，这里分开说。
            导出角色卡在「人设」页底部。
            """)
        }

        Section {
            Button {
                showChat = true
            } label: {
                YNSettingsRow(icon: "bubble.left.and.bubble.right",
                              title: "打开对话",
                              subtitle: stats.conversationSubtitle)
            }
        }
    }

    // MARK: - 数据

    private func reload() {
        guard let database = environment.database else {
            loadError = "数据库尚未就绪"
            return
        }
        do {
            guard let loaded = try environment.companions?.fetch(id: companionId) else {
                loadError = "这个角色不存在（可能已被删除）"
                companion = nil
                return
            }
            companion = loaded
            stats = CompanionHubStats.load(database: database, companion: loaded,
                                           apiConfigs: environment.apiConfigs)
            loadError = nil
        } catch {
            loadError = "角色信息暂时读不出来，请稍后重试"
        }
    }
}
