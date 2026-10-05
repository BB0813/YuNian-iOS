import SwiftUI

/// 对话界面 —— M2 的「最小可用对话」。
///
/// 数据流：输入 → `ChatSession.send` → `AgentHostThreading.turnQueue`（阻塞）
///        → Rust 决策 + 直连 LLM 的 SSE → `StreamSink` 增量 → 打字机效果。
struct ChatView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var session = ChatSession()
    @State private var draft: String = ""
    @State private var companionName: String = "对话"
    /// 伴侣绑定失败时的可见提示。
    ///
    /// ⚠️ 不能静默降级：Rust 的 `load_companion(None)` 会让回合**没有人设**地跑下去，
    /// 模型仍能回答，用户看到的是一个「没有角色的机器人」，且不报错。
    /// 这种失败必须在界面上说出来，而不是靠用户自己察觉。
    @State private var companionWarning: String?

    var body: some View {
        VStack(spacing: 0) {
            if let companionWarning {
                Label(companionWarning, systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.orange.opacity(0.12))
            }
            transcript
            Divider()
            composer
        }
        .navigationTitle(companionName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // 头像（第 144 轮接上 AvatarResolver）：默认伴侣的 avatarUrl 是
            // Android 资源 URI，不翻译就永远加载不出头像 —— 此前整个枚举无调用方。
            ToolbarItem(placement: .topBarLeading) {
                if let url = environment.defaultCompanion?.avatarUrl,
                   let asset = AvatarResolver.assetName(for: url) {
                    Image(asset)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 32, height: 32)
                        .clipShape(Circle())
                } else {
                    Image(systemName: "person.circle.fill")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                }
            }
            if session.isRunning {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("停止") { session.cancel(in: environment) }
                }
            }
        }
        .task {
            // 绑定默认伴侣：Rust 的 load_companion 依赖它才能拿到人设。
            // 失败时给出可见提示，而不是静默继续（否则用户得到无设定的对话）。
            guard session.companionId == nil else { return }
            if let companion = environment.defaultCompanion {
                session.companionId = companion.id
                companionName = companion.name
                companionWarning = nil
            } else {
                companionWarning = "未能绑定默认伴侣：本轮对话没有人设（Rust 的 load_companion 取不到角色）。"
            }
        }
    }

    // MARK: - 消息区

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    ForEach(session.messages) { message in
                        bubble(message)
                            .id(message.id)
                    }

                    if !session.reasoningText.isEmpty {
                        disclosure("思考过程", text: session.reasoningText)
                    }

                    if !session.streamingText.isEmpty {
                        bubble(.init(role: .assistant, text: session.streamingText))
                            .id("streaming")
                    }

                    if let error = session.lastError {
                        Label(error, systemImage: "exclamationmark.triangle")
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }
                .padding()
            }
            .onChange(of: session.messages.count) {
                scrollToBottom(proxy)
            }
            .onChange(of: session.streamingText) {
                scrollToBottom(proxy)
            }
        }
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        withAnimation(.easeOut(duration: 0.15)) {
            if !session.streamingText.isEmpty {
                proxy.scrollTo("streaming", anchor: .bottom)
            } else if let last = session.messages.last {
                proxy.scrollTo(last.id, anchor: .bottom)
            }
        }
    }

    private func bubble(_ message: ChatSession.Message) -> some View {
        HStack {
            if message.role == .user { Spacer(minLength: 40) }
            // 第 119 轮：表情消息按 Android 约定识别。
            // AiResponseFinalizer.kt:448-449：贴纸消息 = 普通 TEXT 消息，
            // 内容为 [stickerId]。据此分支：是表情渲染图片，
            // 否则走原文本路径（含生图标记清洗）。
            if let sticker = sticker(for: message) {
                stickerBubble(sticker)
            } else {
                // 第 112 轮：模型回复先过 ImageGenProtocol.sanitizeForDisplay
                // 再显示，否则 [[生图: 画面描述]] 会被当普通文字原样展示。
                Text(sanitizedDisplay(message))
                    .textSelection(.enabled)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(message.role == .user
                                   ? Color.accentColor.opacity(0.18)
                                   : Color(uiColor: .secondarySystemBackground))
                    )
            }
            if message.role != .user { Spacer(minLength: 40) }
        }
    }

    // MARK: - 表情消息

    /// 从消息文本解析表情。
    ///
    /// 只认 [<纯数字>] 且来自 assistant —— 用户自己打 [123]
    /// 不应被当成表情（Android 渲染层同样只对模型侧生效）。
    private func sticker(for message: ChatSession.Message) -> StickerBubbleModel? {
        guard message.role != .user,
              message.text.hasPrefix("["), message.text.hasSuffix("]"),
              message.text.count > 2,
              let id = Int64(message.text.dropFirst().dropLast()),
              id > 0
        else { return nil }
        return StickerBubbleModel(entryId: id, database: environment.database)
    }

    /// 表情气泡。图片从 stickers 目录按 fileName 读。
    ///
    /// 读不到文件时显示占位（消息仍可见），
    /// 与 Android"资源缺失时显示描述"的处理一致。
    private func stickerBubble(_ sticker: StickerBubbleModel) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if let image = sticker.image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 160, maxHeight: 160)
                    .cornerRadius(10)
            } else {
                Image(systemName: "photo")
                    .font(.largeTitle)
                    .foregroundStyle(.secondary)
                    .frame(width: 120, height: 120)
            }
            if let label = sticker.label {
                Text(label)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color(uiColor: .secondarySystemBackground))
        )
    }

    /// 显示前的清洗。
    ///
    /// 用户消息原样返回（它不是模型输出，不会带标记）；
    /// 模型消息走 ImageGenProtocol/sanitizeForDisplay；
    /// 若整条只有画面描述则显示占位文案。
    private func sanitizedDisplay(_ message: ChatSession.Message) -> String {
        guard message.role != .user else { return message.text }
        if ImageGenProtocol.isPromptOnly(message.text) {
            return "\u{1F3A8} 已生成画面"
        }
        return ImageGenProtocol.sanitizeForDisplay(message.text)
    }
    private func disclosure(_ title: String, text: String) -> some View {
        DisclosureGroup(title) {
            Text(text)
                .font(.caption)
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
        }
        .font(.caption)
    }

    // MARK: - 输入区

    private var composer: some View {
        HStack(spacing: 8) {
            TextField("说点什么…", text: $draft, axis: .vertical)
                .lineLimit(1...5)
                .textFieldStyle(.plain)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(Color(uiColor: .secondarySystemBackground))
                )
                .disabled(session.isRunning || environment.runtime == nil)

            Button {
                let text = draft
                draft = ""
                Task { await session.send(text, in: environment) }
            } label: {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 28))
            }
            .disabled(
                draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                || session.isRunning
                || environment.runtime == nil
            )
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }
}
