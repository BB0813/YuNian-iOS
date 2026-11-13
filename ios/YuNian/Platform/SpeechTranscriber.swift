import Foundation
import Speech
import AVFoundation
import os

/// 语音转文字（第 245 轮）。
///
/// ## 为什么用 `SFSpeechRecognizer` 而不是自己接 ASR 服务
///
/// 系统识别在设备本地就能跑（`requiresOnDeviceRecognition = true`），
/// **不需要网络、不上传音频、不花 token**。对我们这种"说一句话填进输入框"
/// 的场景足够，而且隐私上最干净 —— 用户说的话不出设备。
///
/// ## 边界（写清楚，免得以后以为能做更多）
///
/// - iOS 的 `SFSpeechRecognizer` 在**长时连续识别**上有分钟级限制，
///   所以这里按"一次说一句"设计，不追求实时听写整段。
/// - 中文识别质量取决于设备是否装了对应语言包；没装时 `onDevice` 会失败，
///   这里会自动回落到联网识别（前提是用户允许）。
@MainActor
final class SpeechTranscriber: NSObject, ObservableObject {

    enum State: Equatable {
        case idle
        case requesting
        /// 正在听（`level` 是 0~1 的实时音量，用来画波形）
        case listening
        /// 听完了，正在出最终文本
        case finishing
        case unavailable(String)
    }

    @Published private(set) var state: State = .idle
    /// 已识别出的文本（边说边更新，用户能看到字在长出来）
    @Published private(set) var transcript = ""
    /// 实时音量 0~1（给波形用）
    @Published private(set) var level: CGFloat = 0

    private var recognizer: SFSpeechRecognizer?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private let engine = AVAudioEngine()
    private var hasTap = false
    private let log = Logger(subsystem: "com.yunian.ai", category: "speech")

    /// 开始听。已在进行中时是幂等的。
    func start(locale: Locale = .current) async {
        guard state == .idle || isUnavailable else { return }
        state = .requesting
        transcript = ""

        // 1. 权限（麦克风 + 语音识别）
        guard await requestPermissions() else {
            state = .unavailable("没有麦克风或语音识别权限，请到「设置」里允许")
            return
        }

        // 2. 识别器
        guard let recognizer = SFSpeechRecognizer(locale: locale), recognizer.isAvailable else {
            state = .unavailable("这台设备的语音识别暂不可用")
            return
        }
        self.recognizer = recognizer

        // 3. 音频会话：`.record` + `duckOthers`，让正在播放的语音消息让路
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: [.duckOthers])
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            state = .unavailable("无法启用麦克风：\(error.localizedDescription)")
            return
        }

        // 4. 识别请求
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        // 优先本地识别：不上传音频、不依赖网络
        if recognizer.supportsOnDeviceRecognition {
            request.requiresOnDeviceRecognition = true
        }
        self.request = request

        // 5. 音频输入
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        // ⚠️ 采样率为 0 时 installTap 会崩（模拟器/无麦克风设备会这样）
        guard format.sampleRate > 0 else {
            state = .unavailable("拿不到麦克风输入")
            return
        }
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            request.append(buffer)
            self?.updateLevel(from: buffer)
        }
        hasTap = true

        engine.prepare()
        do {
            try engine.start()
        } catch {
            stopEngine()
            state = .unavailable("麦克风启动失败：\(error.localizedDescription)")
            return
        }

        state = .listening

        // 6. 结果回调
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            Task { @MainActor [weak self] in
                guard let self else { return }
                if let result {
                    self.transcript = result.bestTranscription.formattedString
                    if result.isFinal {
                        self.state = .finishing
                        self.stopEngine()
                        self.state = .idle
                    }
                }
                if let error {
                    // 「听不到声音」之类的瞬时错误不该把界面卡在 listening
                    self.log.notice("识别中断：\(error.localizedDescription, privacy: .public)")
                    if self.state == .listening {
                        self.stopEngine()
                        self.state = .idle
                    }
                }
            }
        }
    }

    /// 停止并返回已识别的文本。
    @discardableResult
    func stop() -> String {
        request?.endAudio()
        stopEngine()
        if state == .listening || state == .finishing {
            state = .idle
        }
        return transcript.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 取消（丢弃已识别内容）。
    func cancel() {
        task?.cancel()
        task = nil
        request = nil
        stopEngine()
        transcript = ""
        state = .idle
    }

    private var isUnavailable: Bool {
        if case .unavailable = state { return true }
        return false
    }

    private func stopEngine() {
        if engine.isRunning { engine.stop() }
        if hasTap {
            engine.inputNode.removeTap(onBus: 0)
            hasTap = false
        }
        request = nil
        task = nil
        level = 0
        // 不 deactivate session 会让后续播放语音消息音量变小
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// 从 PCM buffer 估算音量（给波形用）。
    ///
    /// 用 RMS 而不是峰值：峰值抖动太大，波形会跳得看不清。
    private func updateLevel(from buffer: AVAudioPCMBuffer) {
        guard let channel = buffer.floatChannelData?[0] else { return }
        let count = Int(buffer.frameLength)
        guard count > 0 else { return }
        var sum: Float = 0
        for i in 0..<count { sum += channel[i] * channel[i] }
        let rms = sqrt(sum / Float(count))
        // 归一到 0~1：-50dB ~ 0dB 是正常说话范围
        let db = 20 * log10(max(rms, 1e-7))
        let normalized = max(0, min(1, (db + 50) / 50))
        Task { @MainActor [weak self] in
            // 轻微平滑，避免波形抽搐
            self?.level = (self?.level ?? 0) * 0.6 + CGFloat(normalized) * 0.4
        }
    }

    private func requestPermissions() async -> Bool {
        // 麦克风
        let micGranted = await withCheckedContinuation { continuation in
            AVAudioApplication.requestRecordPermission { continuation.resume(returning: $0) }
        }
        guard micGranted else { return false }

        // 语音识别
        return await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: status == .authorized)
            }
        }
    }
}
