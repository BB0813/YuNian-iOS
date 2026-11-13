import SwiftUI
import AVFoundation
import os

/// 语音消息录音条（第 252 轮）。
///
/// ## 交互
///
/// - **按住说话**：手指按下开始录，松开结束并发送
/// - 上滑取消：手指上移超过阈值就丢弃
/// - 录的时候显示时长与波形
///
/// ## 为什么语音消息用「按住」而语音输入用「点一下」
///
/// 这是两种不同的事：
/// - **语音输入**产文字，常常要说一整句，按住手会累 → 点一下开始、再点一下结束
/// - **语音消息**产音频，长度通常在几秒内，而且"按住"是微信培养出来的肌肉记忆
///   —— 用户按住时知道"松手就发"，这个反馈比"再点一下"更明确
///
/// 用错交互会让用户困惑，所以两者刻意不同。
struct VoiceRecordBar: View {

    let colors: YNTheme.Palette
    @ObservedObject var recorder: VoiceRecorder
    /// 录完回调（URL + 时长）。
    let onFinish: (URL, TimeInterval) -> Void
    let onCancel: () -> Void

    @State private var isPressing = false
    @State private var dragOffset: CGFloat = 0

    /// 上滑超过这个距离就取消
    private let cancelThreshold: CGFloat = 70

    var body: some View {
        HStack(spacing: YNTheme.Space.sm) {
            if case .recording = recorder.state {
                recordingIndicator
            } else {
                holdToTalkButton
            }
        }
        .padding(.horizontal, YNTheme.Space.lg)
        .padding(.vertical, YNTheme.Space.sm)
        .background(colors.background)
    }

    // MARK: - 按住说话

    private var holdToTalkButton: some View {
        Text("按住说话")
            .font(.subheadline.weight(.medium))
            .foregroundStyle(colors.textSecondary)
            .frame(maxWidth: .infinity)
            .frame(height: 40)
            .background(
                colors.surface,
                in: RoundedRectangle(cornerRadius: YNTheme.Radius.composer, style: .continuous)
            )
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if !isPressing {
                            isPressing = true
                            Task { await recorder.start() }
                        }
                        dragOffset = value.translation.height
                    }
                    .onEnded { _ in
                        isPressing = false
                        if dragOffset < -cancelThreshold {
                            recorder.cancel()
                            onCancel()
                        } else if let result = recorder.stop() {
                            onFinish(result.url, result.duration)
                        } else {
                            onCancel()
                        }
                        dragOffset = 0
                    }
            )
            .accessibilityLabel("按住录音")
    }

    // MARK: - 录音中

    private var recordingIndicator: some View {
        HStack(spacing: YNTheme.Space.sm) {
            // 波形
            HStack(spacing: 2) {
                ForEach(0..<12, id: \.self) { index in
                    Capsule()
                        .fill(colors.warm)
                        .frame(width: 3, height: barHeight(index: index))
                        .animation(.easeOut(duration: 0.1), value: recorder.level)
                }
            }
            .frame(width: 60)

            Text(timeText)
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(colors.textPrimary)

            Spacer()

            // 松手即发，所以这里给一个"取消"按钮兜底
            Button {
                recorder.cancel()
                onCancel()
            } label: {
                Text("取消")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(colors.warning)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12)
        .frame(height: 40)
        .background(
            colors.surface,
            in: RoundedRectangle(cornerRadius: YNTheme.Radius.composer, style: .continuous)
        )
        .overlay(alignment: .top) {
            if dragOffset < -20 {
                Text(dragOffset < -cancelThreshold ? "松手取消" : "上滑取消")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(dragOffset < -cancelThreshold ? colors.warning : colors.textTertiary)
                    .offset(y: -22)
            }
        }
    }

    private func barHeight(index: Int) -> CGFloat {
        // 中间高两边低，让波形看起来像声音而不是一根柱子
        let center = 5.5
        let distance = abs(Double(index) - center) / center
        let base: CGFloat = 3
        let peak: CGFloat = 22
        return max(base, base + (peak - base) * recorder.level * CGFloat(1 - distance * 0.5))
    }

    private var timeText: String {
        let seconds = Int(recorder.elapsed)
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}

// MARK: - 语音消息气泡播放

/// 语音消息的播放器（第 252 轮）。
///
/// 一次只播一条：两条同时响会听不清，而且用户按第二条就是想听第二条。
@MainActor
final class VoiceMessagePlayer: NSObject, ObservableObject {

    /// 正在播放的消息 id（nil = 没在播）
    @Published private(set) var playingId: UUID?
    @Published private(set) var progress: CGFloat = 0

    private var player: AVAudioPlayer?
    private var progressTimer: Timer?
    private let log = Logger(subsystem: "com.yunian.ai", category: "voice.player")

    func toggle(messageId: UUID, url: URL) {
        if playingId == messageId {
            stop()
            return
        }
        stop()
        do {
            // `.playback` + `duckOthers`：语音要在静音键打开时也能出声吗？
            // 不能 —— 聊天语音属于"媒体"，应该遵守静音键（`.ambient` 那套语义）。
            // 但 `.ambient` 在静音时不响，用户会以为坏了。
            // 折中：用 `.playback`（静音也响），这是微信/Telegram 的做法 ——
            // 用户点播放就是明确想听。
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
            try session.setActive(true)

            let player = try AVAudioPlayer(contentsOf: url)
            player.delegate = self
            player.prepareToPlay()
            player.play()
            self.player = player
            playingId = messageId
            startProgress()
        } catch {
            log.error("play failed: \(error.localizedDescription, privacy: .public)")
            stop()
        }
    }

    func stop() {
        player?.stop()
        player = nil
        progressTimer?.invalidate()
        progressTimer = nil
        playingId = nil
        progress = 0
        try? AVAudioSession.sharedInstance().setActive(false,
            options: .notifyOthersOnDeactivation)
    }

    private func startProgress() {
        progressTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, let player = self.player, player.duration > 0 else { return }
                self.progress = CGFloat(player.currentTime / player.duration)
            }
        }
    }
}

extension VoiceMessagePlayer: AVAudioPlayerDelegate {
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor [weak self] in
            self?.stop()
        }
    }
}
