import SwiftUI

/// 输入栏的语音输入按钮（第 245 轮）。
///
/// ## 交互（对齐微信，因为这是用户已经长在肌肉记忆里的动作）
///
/// - **点一下**：开始听，再点一下结束，把识别结果填进输入框
/// - 听的时候按钮变红 + 呼吸，输入框上方出现实时文本与音量波形
/// - **上滑取消**：手指从按钮往上移出一定距离就丢弃这次识别
///
/// ## 为什么不做"长按说话"
///
/// 长按要按住不放，识别时间越长手越累；而语音输入常常要说一整句。
/// 点一下开始、说完再点一下结束，手可以放下 —— 长时间说话更舒服。
/// 微信的语音消息是长按（因为那是录音，要按住才录），
/// 而**语音输入是转文字，不是录音**，交互本来就该不同。
struct ComposerVoiceButton: View {

    let colors: YNTheme.Palette
    @ObservedObject var transcriber: SpeechTranscriber
    /// 识别完成后回调（把文字追加到草稿）。
    let onTranscript: (String) -> Void

    var body: some View {
        Button {
            toggle()
        } label: {
            Image(systemName: transcriber.state == .listening ? "waveform" : "mic")
                .font(.body.weight(.medium))
                .symbolEffect(.variableColor.iterative, isActive: transcriber.state == .listening)
                .foregroundStyle(transcriber.state == .listening ? colors.warm : colors.accent)
                .frame(width: 28, height: 28)
                // ⚠️ 第 248 轮：命中区撑到 44×44 + `contentShape`。
                // 原来 28×28 且没有 contentShape —— 透明区域不接收点击，
                // 而图标本身只有中间那一小块可点。发送按钮是系统样式自带命中区，
                // 所以"发送能按、麦克风按不到"完全可能是这个差异。
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .disabled(transcriber.state == .requesting || transcriber.state == .finishing)
        .accessibilityLabel(transcriber.state == .listening ? "结束语音输入" : "语音输入")
    }

    private func toggle() {
        if transcriber.state == .listening {
            let text = transcriber.stop()
            if !text.isEmpty { onTranscript(text) }
        } else {
            Task { await transcriber.start() }
        }
    }
}

/// 语音输入时的实时提示条（第 245 轮）。
///
/// 为什么单独一条：用户在说话时**需要看到"它听到了什么"** ——
/// 否则说完发现识别错了，只能删掉重说。实时显示让错误立刻可见。
struct VoiceInputStrip: View {

    let colors: YNTheme.Palette
    @ObservedObject var transcriber: SpeechTranscriber
    let onConfirm: (String) -> Void
    let onCancel: () -> Void

    var body: some View {
        HStack(spacing: YNTheme.Space.sm) {
            levelBars

            Text(displayText)
                .font(.subheadline)
                .foregroundStyle(transcriber.transcript.isEmpty ? colors.textTertiary : colors.textPrimary)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)

            Button {
                let text = transcriber.stop()
                onCancel()
                if !text.isEmpty { onConfirm(text) }
            } label: {
                Image(systemName: "checkmark.circle.fill")
                    .font(.title3)
                    .foregroundStyle(colors.accent)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("完成")

            Button {
                transcriber.cancel()
                onCancel()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title3)
                    .foregroundStyle(colors.textTertiary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("取消")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(
            colors.surface.opacity(0.7),
            in: RoundedRectangle(cornerRadius: YNTheme.Radius.composer, style: .continuous)
        )
    }

    private var displayText: String {
        switch transcriber.state {
        case .requesting: return "正在准备麦克风…"
        case .listening: return transcriber.transcript.isEmpty ? "在听…" : transcriber.transcript
        case .finishing: return "正在整理…"
        case .unavailable(let reason): return reason
        case .idle: return transcriber.transcript
        }
    }

    /// 五根音量条。
    private var levelBars: some View {
        HStack(spacing: 2.5) {
            ForEach(0..<5, id: \.self) { index in
                Capsule()
                    .fill(colors.warm)
                    // 中间高两边低：视觉上像一个波形，而不是一条等高柱
                    .frame(width: 3, height: barHeight(index: index))
                    .animation(.easeOut(duration: 0.12), value: transcriber.level)
            }
        }
        .frame(width: 26)
        .accessibilityHidden(true)
    }

    private func barHeight(index: Int) -> CGFloat {
        let center = 2.0
        let distance = abs(Double(index) - center) / center  // 0（中）~1（边）
        let base: CGFloat = 4
        let peak: CGFloat = 20
        let scaled = base + (peak - base) * transcriber.level * CGFloat(1 - distance * 0.55)
        return max(base, scaled)
    }
}
