import Foundation
import AVFoundation
import os

/// 语音消息录音器（第 252 轮）。
///
/// ## 和「语音输入」的区别（别混）
///
/// | | 语音输入（已有） | 语音消息（本文件） |
/// |---|---|---|
/// | 产出 | 文字（转写后填进输入框） | 音频文件（发给对方听） |
/// | 技术 | `SFSpeechRecognizer` | `AVAudioRecorder` |
/// | 用户预期 | 我说完，字出来 | 我说完，对方听到我的声音 |
///
/// ## 为什么用 m4a（AAC）而不是 wav
///
/// AAC 在 64kbps 下语音质量已经很好，一分钟约 480KB；
/// 而 16-bit PCM wav 一分钟约 5.5MB —— **十倍差距，而听感几乎无差**。
/// 语音消息的核心成本是传输，所以选有损压缩。
///
/// ## 权限
///
/// 只要麦克风（`NSMicrophoneUsageDescription`，已在 Info.plist）。
/// **不要语音识别权限** —— 录音不需要它。
@MainActor
final class VoiceRecorder: NSObject, ObservableObject {

    enum State: Equatable {
        case idle
        case recording
        /// 录完了，有一个可播放的文件
        case recorded(URL, duration: TimeInterval)
    }

    @Published private(set) var state: State = .idle
    /// 实时音量 0~1（画波形用）
    @Published private(set) var level: CGFloat = 0
    /// 已录时长（秒）
    @Published private(set) var elapsed: TimeInterval = 0

    private var recorder: AVAudioRecorder?
    private var meterTimer: Timer?
    private var startedAt: Date?
    private let log = Logger(subsystem: "com.yunian.ai", category: "voice.recorder")

    /// 是否正在等权限/建 recorder（`start()` 是异步的）。
    ///
    /// ⚠️ **这个标志修的是一个真实竞态**：`start()` 要等麦克风权限回调，
    /// 而用户可能在权限框弹出的那一瞬间就松手了 —— 此时 `state` 还是 `.idle`，
    /// `stop()` 的 `guard case .recording` 不成立，于是**什么都没停**，
    /// 录音却随后才真正开始 → 用户看到"松手还在录"。
    private var isStarting = false
    /// 用户已经在 `start()` 完成前要求停止 —— 建好 recorder 后立刻停。
    private var stopRequestedWhileStarting = false

    /// 最长录音时长。
    ///
    /// 60 秒：微信是 60 秒，用户对这个上限有肌肉记忆。
    /// 而且超过一分钟后，接收方几乎一定会想"怎么不直接打字"。
    static let maxDuration: TimeInterval = 60

    /// 开始录音。已在录音时是幂等的。
    func start() async {
        guard state == .idle || isRecorded else { return }
        // 先清掉上一次的录音
        if case .recorded(let url, _) = state {
            try? FileManager.default.removeItem(at: url)
        }
        state = .idle
        isStarting = true
        stopRequestedWhileStarting = false
        defer { isStarting = false }

        // 权限
        let granted = await withCheckedContinuation { continuation in
            AVAudioApplication.requestRecordPermission { continuation.resume(returning: $0) }
        }
        guard granted else {
            log.error("microphone denied")
            return
        }

        // ⚠️ 竞态检查点 1：等权限期间用户已经松手了 → 直接不开始录。
        if stopRequestedWhileStarting {
            log.notice("stopped before recording began (user released during permission)")
            stopRequestedWhileStarting = false
            return
        }

        // 音频会话
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default,
                                    options: [.defaultToSpeaker, .allowBluetooth])
            try session.setActive(true)
        } catch {
            log.error("session failed: \(error.localizedDescription, privacy: .public)")
            return
        }

        // 文件落点：临时目录（发出去后由 ChatSession 拷到 attachments）
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("voice_\(UUID().uuidString).m4a")

        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 64_000,
            AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue,
        ]

        do {
            let recorder = try AVAudioRecorder(url: url, settings: settings)
            recorder.isMeteringEnabled = true
            recorder.record(forDuration: Self.maxDuration)
            self.recorder = recorder
            startedAt = Date()
            state = .recording
            startMetering()
            log.notice("recording started")

            // ⚠️ 竞态检查点 3：如果松手发生在建好 recorder 的**前一刻**，
            // 上面那个 `stopRequestedWhileStarting` 已经置位 —— 这里立刻停。
            if stopRequestedWhileStarting {
                stopRequestedWhileStarting = false
                _ = stop()
                log.notice("stopped immediately after start (late release)")
            }
        } catch {
            log.error("recorder failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// 停止并返回录音结果（nil = 没录成或太短）。
    @discardableResult
    func stop() -> (url: URL, duration: TimeInterval)? {
        // ⚠️ 竞态检查点 2：`start()` 还没建好 recorder 时用户就松手了。
        // 这里必须**记下这个意图**，否则 `guard` 会让 stop 静默失效，
        // 而 recorder 随后才建好 → 一直录下去（真机就是这个症状）。
        if isStarting, recorder == nil {
            stopRequestedWhileStarting = true
            log.notice("stop requested while starting")
            return nil
        }
        guard case .recording = state, let recorder else { return nil }
        let duration = Date().timeIntervalSince(startedAt ?? Date())
        recorder.stop()
        stopMetering()
        self.recorder = nil

        // 太短（< 0.6s）当误触处理：录一秒都不到的语音对双方都是噪音
        guard duration >= 0.6, FileManager.default.fileExists(atPath: recorder.url.path) else {
            try? FileManager.default.removeItem(at: recorder.url)
            state = .idle
            log.notice("discarded too-short recording \(duration, privacy: .public)s")
            return nil
        }

        state = .recorded(recorder.url, duration: duration)
        log.notice("recorded \(duration, privacy: .public)s")
        return (recorder.url, duration)
    }

    /// 取消（丢弃录音）。
    func cancel() {
        if case .recorded(let url, _) = state {
            try? FileManager.default.removeItem(at: url)
        }
        recorder?.stop()
        recorder = nil
        stopMetering()
        state = .idle
        elapsed = 0
        level = 0
        try? AVAudioSession.sharedInstance().setActive(false,
            options: .notifyOthersOnDeactivation)
    }

    private var isRecorded: Bool {
        if case .recorded = state { return true }
        return false
    }

    private func startMetering() {
        meterTimer = Timer.scheduledTimer(withTimeInterval: 0.06, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, let recorder = self.recorder, recorder.isRecording else { return }
                recorder.updateMeters()
                // -60dB ~ 0dB 映射到 0~1
                let db = recorder.averagePower(forChannel: 0)
                let normalized = max(0, min(1, (db + 60) / 60))
                self.level = self.level * 0.55 + CGFloat(normalized) * 0.45
                self.elapsed = Date().timeIntervalSince(self.startedAt ?? Date())
                // 到达上限自动停
                if self.elapsed >= Self.maxDuration {
                    _ = self.stop()
                }
            }
        }
    }

    private func stopMetering() {
        meterTimer?.invalidate()
        meterTimer = nil
        level = 0
    }
}
