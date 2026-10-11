import Combine
import Foundation
import os

/// 试听 —— 设置页里"按这个音色念一句"。
///
/// ## 为什么要单独一个对象
/// 设置页不是聊天页：它没有 `ChatViewModel`，也不该把播放状态混进聊天状态。
/// 但**必须**由某个被持有的对象拿住 `AVAudioPlayer` ——
/// 放成局部变量会让声音刚要出声就被释放（见 `TtsSpeaker` 的注释）。
///
/// ## 试听文本为什么是通用短句
/// 用角色台词会让"这条线路读得对不对"和"这句台词合不合适"两件事混在一起。
/// 试听只需要暴露音色与语速。
@MainActor
final class VoicePreviewer: ObservableObject {

    enum State: Equatable {
        case idle
        case synthesizing
        case playing
        case failed(String)

        var isBusy: Bool { self == .synthesizing || self == .playing }

        /// 失败态（界面据此用警示色显示原因）。
        var isFailed: Bool {
            if case .failed = self { return true }
            return false
        }

        /// 界面上的按钮标题（nil = 不显示状态行）。
        var title: String? {
            switch self {
            case .idle: return nil
            case .synthesizing: return "合成中…"
            case .playing: return "正在试听（再点一次停止）"
            case .failed(let message): return message
            }
        }
    }

    @Published private(set) var state: State = .idle

    private let log = Logger(subsystem: "com.yunian.ai", category: "tts.preview")
    private let speaker = TtsSpeaker()
    private var task: Task<Void, Never>?

    /// 试听用的示例文本。
    static let sampleText = "你好，这是这条语音线路的试听。"

    var isPlaying: Bool { state == .playing }

    /// 合成并播放一次。
    ///
    /// - Parameter voice: **实际要用的音色**（调用方已经过
    ///   `VoiceProfileStore.effectiveVoice` 解析；空串 = 用协议默认）。
    func preview(baseUrl: String,
                 apiKey: String,
                 model: String,
                 provider: String,
                 settings: TtsSettings,
                 voice: String) {
        stop()
        state = .synthesizing
        let plan = TtsClient.Plan(
            baseUrl: baseUrl,
            apiKey: apiKey,
            model: model,
            provider: provider,
            proto: settings.proto,
            voice: voice,
            format: settings.format,
            text: Self.sampleText
        )
        task = Task { [weak self] in
            guard let self else { return }
            do {
                let audio = try await TtsClient().synthesize(plan)
                guard !Task.isCancelled else { return }
                try self.speaker.play(audio, format: settings.format)
                self.state = .playing
                // 播完回到 idle（与聊天页同一套：轮询式结束回调）
                self.speaker.onFinish = { [weak self] _ in
                    guard let self, self.state == .playing else { return }
                    self.state = .idle
                }
            } catch {
                guard !Task.isCancelled else { return }
                // 与聊天页共用同一份错误文案映射
                let message = TtsClient.describe(error)
                self.state = .failed(message)
                self.log.error("试听失败：\(message, privacy: .public)")
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
        speaker.onFinish = nil
        speaker.stop()
        if state.isBusy { state = .idle }
    }

    /// 退出设置页 / 切换线路时调：停声并把失败提示也清掉
    /// （失败提示属于那一次试听，换一条线路就过期了）。
    func reset() {
        stop()
        state = .idle
    }
}
