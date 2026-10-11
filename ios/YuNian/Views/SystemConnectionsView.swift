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
                    // 「未申请」的项目给一个**申请**入口（第 204 轮）。
                    // 其余状态只报告，不做开关。
                    if item.state == .notDetermined, Self.isRequestable(item.id) {
                        Button {
                            request(item)
                        } label: {
                            row(item, colors: c) {
                                Label("申请", systemImage: "hand.raised")
                                    .font(.caption)
                                    .foregroundStyle(c.accent)
                            }
                        }
                        .buttonStyle(.plain)
                    } else {
                        row(item, colors: c) {
                            stateBadge(item.state, colors: c)
                        }
                    }
                }
            } header: {
                Text("已声明的系统能力")
            } footer: {
                Text("""
                点「申请」会弹出**系统自己的**询问框，由你决定；已拒绝的项目不再给申请入口（iOS 不允许二次询问），请去系统设置改。
                ⚠️ 系统的「设置 → 予念」里**只有在本 App 请求过之后**才会出现对应的开关 —— 这不是权限没配上，是它还没被问过一次。
                """)
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

    /// 一行：图标 + 名称/说明 + 右侧内容。
    ///
    /// 抽出来的理由：可申请的那几行右侧是「申请」按钮，其余是状态徽标 ——
    /// 不抽的话这段布局要写两遍，而两遍必然慢慢走样。
    @ViewBuilder
    private func row<T: View>(_ item: Capability, colors c: YNTheme.Palette,
                              @ViewBuilder trailing: () -> T) -> some View {
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
            trailing()
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(item.name)，\(stateText(item.state))")
    }

    /// 该项能不能**主动申请**。
    ///
    /// Face ID 不在其中：它没有"授权状态"这一说，系统只在每次评估时决定要不要弹窗
    /// （所以上面也只报告"本机有没有"）。
    private static func isRequestable(_ id: String) -> Bool {
        ["camera", "microphone", "photosRead", "photosAdd", "speech"].contains(id)
    }

    /// 发起授权请求。
    ///
    /// ## 为什么这一页必须能"申请"（第 204 轮，用户实测撞上的死局）
    /// 这一版之前是**只读**的（页脚原话："不代为开关"）。只读 + 一个 iOS 行为
    /// 就构成了死局：
    ///   · iOS 的「设置 → 本 App」里**只有在本 App 请求过之后**才出现对应开关；
    ///   · 而这一页是唯一的入口，却只显示"未申请"、不给操作。
    /// 于是用户在两处都找不到办法授权，只能永远看着「未申请」
    /// （用户截图：设置页里只有 Siri / 搜索 / 无线数据）。
    ///
    /// **申请 ≠ 偷偷改开关**：前者是请系统弹出它自己的询问框，决定权仍在用户；
    /// 已拒绝的项目**不给**申请入口（iOS 也不允许二次询问），只指向系统设置。
    private func request(_ item: Capability) {
        switch item.id {
        case "camera":
            AVCaptureDevice.requestAccess(for: .video) { _ in refresh() }
        case "microphone":
            AVAudioSession.sharedInstance().requestRecordPermission { _ in refresh() }
        case "photosRead":
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { _ in refresh() }
        case "photosAdd":
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { _ in refresh() }
        case "speech":
            SFSpeechRecognizer.requestAuthorization { _ in refresh() }
        default:
            break
        }
    }

    /// 回调在任意线程 → 回主线程刷新状态。
    private func refresh() {
        Task { @MainActor in capabilities = Self.read() }
    }

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
