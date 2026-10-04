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
            Text(message.text)
                .textSelection(.enabled)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(message.role == .user
                              ? Color.accentColor.opacity(0.18)
                              : Color(uiColor: .secondarySystemBackground))
                )
            if message.role != .user { Spacer(minLength: 40) }
        }
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
