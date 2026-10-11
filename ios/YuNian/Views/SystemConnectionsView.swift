import SwiftUI
import UIKit
import AVFoundation
import Photos
import Speech
// Face ID 能力探测（LAContext.canEvaluatePolicy）
import LocalAuthentication

/// 系统连接 —— 每类系统能力的**真实**授权状态。
///
/// ## 这一版与上一版的区别
/// 上一版只列「本 App 声明了哪些能力」（读 Info.plist 用途说明键），
/// 那只是**声明**，不是**状态** —— 用户看不出"到底能不能用"。
/// 这一版去问系统要真实状态。
///
/// ## 只列声明过的能力，不凑数
/// 实测本 App 的 Info.plist 只声明了 6 项：
/// 相机 / Face ID / 麦克风 / 相册读 / 相册写 / 语音识别。
/// **没有**日历、通讯录、健康、定位、提醒。
/// 所以这里不列那五项 —— 列出来只能显示一个假的"未授权"，
/// 而真相是"本 App 根本没有这项能力"。
///
/// ## 三态
/// 未申请 / 已授权 / 已拒绝。这就是 Aru 那套「未授权 / 已授权但模型不可见 / 全开」
/// 里**在 iOS 单侧能真实成立的那一维** —— 另外两维（模型可见性、写入确认）
/// 要引擎侧的工具门控支持，现在没有，所以不做假开关。
struct SystemConnectionsView: View {

    @Environment(\.colorScheme) private var scheme
    @Environment(\.openURL) private var openURL

    @State private var capabilities: [Capability] = []

    /// 一类系统能力。
    struct Capability: Identifiable {
        let id: String
        let name: String
        let detail: String
        let symbol: String
        var state: State

        enum State: Equatable {
            case notDetermined
            case granted
            case denied
            /// 本机不支持（例如模拟器没有相机）
            case unavailable
        }
    }

    var body: some View {
        let c = YNTheme.palette(scheme)

        List {
            Section {
                ForEach(capabilities) { item in
                    HStack(spacing: YNTheme.Space.md) {
                        Image(systemName: item.symbol)
                            .foregroundStyle(c.accent)
                            .frame(width: 26)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name)
                            Text(item.detail)
                                .font(.caption)
                                .foregroundStyle(c.textSecondary)
                        }
                        Spacer(minLength: YNTheme.Space.sm)
                        stateBadge(item.state, colors: c)
                    }
                    .padding(.vertical, 2)
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("\(item.name)，\(stateText(item.state))")
                }
            } header: {
                Text("已声明的系统能力")
            } footer: {
                Text("授权状态由系统管理。本页只报告真实状态，不代为开关；改动请去系统设置。")
            }

            Section {
                Button {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        openURL(url)
                    }
                } label: {
                    Label("打开本 App 的系统设置", systemImage: "gear")
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("系统连接")
        .navigationBarTitleDisplayMode(.inline)
        // 每次进入刷新：用户可能刚从系统设置里改完回来。
        .onAppear { capabilities = Self.read() }
    }

    // MARK: - 状态徽标

    @ViewBuilder
    private func stateBadge(_ state: Capability.State, colors c: YNTheme.Palette) -> some View {
        // T6：图标 + 文字，不只靠颜色
        let (symbol, tint): (String, Color) = {
            switch state {
            case .granted: ("checkmark.circle.fill", c.success)
            case .denied: ("xmark.circle.fill", c.danger)
            case .notDetermined: ("questionmark.circle", c.textSecondary)
            case .unavailable: ("minus.circle", c.textTertiary)
            }
        }()
        return Label(stateText(state), systemImage: symbol)
            .font(.caption)
            .foregroundStyle(tint)
            .labelStyle(.titleAndIcon)
    }

    private func stateText(_ state: Capability.State) -> String {
        switch state {
        case .granted: "已授权"
        case .denied: "已拒绝"
        case .notDetermined: "未申请"
        case .unavailable: "不可用"
        }
    }

    // MARK: - 读取真实状态

    /// 逐项问系统要状态。
    ///
    /// ⚠️ 这些都是**同步且极轻**的查询，不会弹窗、不会阻塞 ——
    /// 与 `ApiProbeService` 那种阻塞网络调用是两回事，
    /// 所以可以直接在主线程调用，不需要 off-main 队列。
    private static func read() -> [Capability] {
        [
            Capability(
                id: "camera", name: "相机", detail: "拍照与扫码", symbol: "camera",
                state: map(AVCaptureDevice.authorizationStatus(for: .video))
            ),
            Capability(
                id: "microphone", name: "麦克风", detail: "语音输入与通话", symbol: "mic",
                state: map(AVAudioSession.sharedInstance().recordPermission)
            ),
            Capability(
                id: "photosRead", name: "相册读取", detail: "选取图片作为表情或头像", symbol: "photo.on.rectangle",
                state: map(PHPhotoLibrary.authorizationStatus(for: .readWrite))
            ),
            Capability(
                id: "photosAdd", name: "相册写入", detail: "保存生成的图片", symbol: "square.and.arrow.down",
                state: map(PHPhotoLibrary.authorizationStatus(for: .addOnly))
            ),
            Capability(
                id: "speech", name: "语音识别", detail: "把语音转成文字", symbol: "waveform",
                state: map(SFSpeechRecognizer.authorizationStatus())
            ),
            // Face ID 没有"授权状态"概念（它是每次评估时由系统决定是否弹窗），
            // 所以只报告"本机有没有"，不编一个假的三态。
            Capability(
                id: "faceID", name: "Face ID", detail: "用于本机身份校验", symbol: "faceid",
                state: Self.faceIDAvailable() ? .granted : .unavailable
            ),
        ]
    }

    private static func faceIDAvailable() -> Bool {
        // 只问"有没有这个硬件能力"，不触发任何评估（那会弹窗）。
        var error: NSError?
        let context = LAContext()
        let ok = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
        return ok
    }

    // MARK: - 映射

    private static func map(_ status: AVAuthorizationStatus) -> Capability.State {
        switch status {
        case .authorized: .granted
        case .denied, .restricted: .denied
        case .notDetermined: .notDetermined
        @unknown default: .unavailable
        }
    }

    private static func map(_ status: AVAudioSession.RecordPermission) -> Capability.State {
        switch status {
        case .granted: .granted
        case .denied: .denied
        case .undetermined: .notDetermined
        @unknown default: .unavailable
        }
    }

    private static func map(_ status: PHAuthorizationStatus) -> Capability.State {
        switch status {
        case .authorized, .limited: .granted
        case .denied, .restricted: .denied
        case .notDetermined: .notDetermined
        @unknown default: .unavailable
        }
    }

    private static func map(_ status: SFSpeechRecognizerAuthorizationStatus) -> Capability.State {
        switch status {
        case .authorized: .granted
        case .denied, .restricted: .denied
        case .notDetermined: .notDetermined
        @unknown default: .unavailable
        }
    }
}
