import SwiftUI
import UIKit
import Photos
import os

/// 自绘相册选择器（第 250 轮）。
///
/// ## 为什么不用 `PhotosPicker`
///
/// 已经失败三次：
/// 1. `.plain` 按钮在 `safeAreaBar` 里收不到点击
/// 2. `PhotosPicker` 内联在 `Menu` 里不弹相册
/// 3. `.photosPicker` 修饰符在输入栏里也不弹
///
/// `PhotosPicker` 是 SwiftUI 的**黑盒呈现**：它自己决定什么时候 present、
/// 用什么 controller、在哪个 window 上。一旦它决定不弹，我们从外面**看不到任何原因**
/// —— 没有错误、没有回调、没有日志。这类"静默不弹"是最难修的。
///
/// 所以改成自绘：用 `PHPhotoLibrary` 直接读相册，用 SwiftUI 画网格，
/// 选中后自己 `PHImageManager` 取数据。**每一步都在我们手里，能打日志、能显示错误。**
///
/// ## 代价
///
/// - 需要自己处理权限（`.readWrite`，因为要读照片列表）
/// - 需要自己分页/缩略图缓存（这里用 `PHCachingImageManager`）
/// - 没有系统相册的"回忆/相簿/搜索"
///
/// 对一个"选一张图发给角色"的场景，这些代价可接受 —— 用户要的是快速选一张，
/// 不是浏览整个照片库。
struct PhotoLibraryPicker: View {

    /// 选中结果：照片给字节，视频给**临时文件 URL**。
    ///
    /// ## 为什么视频不给 `Data`
    /// 视频几十 MB 起步，读进内存纯属浪费 —— `PHAssetResourceManager.writeData`
    /// 把相册里的原始文件**流式**写到磁盘，App 内存里始终只有文件名。
    enum PickedMedia {
        case image(Data)
        case video(URL)
    }

    /// 分段：照片 | 视频。打开时落在 `mediaKind` 指定的那一段，
    /// 用户可在选择器里随时切换。
    enum MediaKind: CustomStringConvertible {
        case photo
        case video
        var description: String { self == .photo ? "photo" : "video" }
    }

    /// 选好后回调原始媒体（图片字节 / 视频临时文件）。
    let onPicked: (PickedMedia) -> Void
    let onCancel: () -> Void

    init(mediaKind: MediaKind = .photo,
         onPicked: @escaping (PickedMedia) -> Void,
         onCancel: @escaping () -> Void) {
        self.onPicked = onPicked
        self.onCancel = onCancel
        _kind = State(initialValue: mediaKind)
    }

    /// 当前分段（内部状态：切换即重新 fetch）。
    @State private var kind: MediaKind
    @State private var assets: [PHAsset] = []
    @State private var status: PHAuthorizationStatus = .notDetermined
    @State private var isLoading = true
    @State private var loadError: String?
    @State private var selected: PHAsset?

    private let log = Logger(subsystem: "com.yunian.ai", category: "photo.picker")

    private static let columns = [
        GridItem(.adaptive(minimum: 110, maximum: 160), spacing: 3)
    ]

    var body: some View {
        NavigationStack {
            Group {
                switch status {
                case .authorized, .limited:
                    grid
                case .denied, .restricted:
                    deniedView
                case .notDetermined:
                    ProgressView("正在请求相册权限…")
                @unknown default:
                    deniedView
                }
            }
            .navigationTitle(kind == .photo ? "选择照片" : "选择视频")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { onCancel() }
                }
            }
            .task { await start() }
        }
    }

    // MARK: - 网格

    private var grid: some View {
        Group {
            if isLoading {
                ProgressView("正在读取\(kind == .photo ? "照片" : "视频")…")
            } else if let loadError {
                ContentUnavailableView(
                    "读不到照片",
                    systemImage: "exclamationmark.triangle",
                    description: Text(loadError)
                )
            } else if assets.isEmpty {
                ContentUnavailableView(
                    kind == .photo ? "相册里没有照片" : "相册里没有视频",
                    systemImage: "photo.on.rectangle",
                    description: Text(kind == .photo ? "拍一张再回来。" : "拍一段再回来。")
                )
            } else {
                VStack(spacing: 0) {
                    // 分段切换（第 256 轮补完）：切段重新 fetch
                    Picker("", selection: $kind) {
                        Text("照片").tag(MediaKind.photo)
                        Text("视频").tag(MediaKind.video)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, YNTheme.Space.md)
                    .padding(.vertical, YNTheme.Space.sm)

                    ScrollView {
                        LazyVGrid(columns: Self.columns, spacing: 3) {
                            ForEach(assets, id: \.localIdentifier) { asset in
                                PhotoThumbnail(asset: asset, selected: selected == asset)
                                    .onTapGesture { pick(asset) }
                            }
                        }
                        .padding(3)
                    }
                }
                .onChange(of: kind) { _, _ in
                    isLoading = true
                    loadAssets()
                }
            }
        }
    }

    private var deniedView: some View {
        ContentUnavailableView {
            Label("没有相册权限", systemImage: "lock")
        } description: {
            Text("发图需要读取你的照片。可以到「设置 → 予念 → 照片」里允许。")
        } actions: {
            Button("打开设置") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
        }
    }

    // MARK: - 逻辑

    private func start() async {
        let current = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        log.notice("start status=\(current.rawValue, privacy: .public)")
        if current == .notDetermined {
            let granted = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
            status = granted
            log.notice("after request status=\(granted.rawValue, privacy: .public)")
        } else {
            status = current
        }
        guard status == .authorized || status == .limited else {
            isLoading = false
            return
        }
        loadAssets()
    }

    private func loadAssets() {
        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        // 按当前分段取媒体类型（第 256 轮：视频段取 video）
        let mediaType = kind == .photo
            ? PHAssetMediaType.image.rawValue
            : PHAssetMediaType.video.rawValue
        options.predicate = NSPredicate(format: "mediaType == %d", mediaType)
        options.fetchLimit = 500

        let result = PHAsset.fetchAssets(with: options)
        var list: [PHAsset] = []
        list.reserveCapacity(result.count)
        result.enumerateObjects { asset, _, _ in list.append(asset) }
        assets = list
        isLoading = false
        log.notice("loaded \(list.count, privacy: .public) assets (kind=\(kind, privacy: .public))")
        if list.isEmpty {
            loadError = nil
        }
    }

    private func pick(_ asset: PHAsset) {
        selected = asset
        log.notice("picking \(asset.localIdentifier, privacy: .public)")
        Task { await export(asset) }
    }

    private func export(_ asset: PHAsset) async {
        // 视频走流式落盘（第 256 轮补完）—— 几十 MB 不该过内存
        if kind == .video {
            await exportVideo(asset)
            return
        }

        let options = PHImageRequestOptions()
        options.isNetworkAccessAllowed = true
        options.deliveryMode = .highQualityFormat
        options.version = .current

        let data: Data? = await withCheckedContinuation { continuation in
            PHImageManager.default().requestImageDataAndOrientation(
                for: asset, options: options
            ) { data, _, _, info in
                // ⚠️ 这个回调的第四个参数是 **info 字典**（不是 error）。
                // 失败原因在 `PHImageErrorKey` 里。
                if data == nil {
                    let reason = (info?[PHImageErrorKey] as? NSError)?.localizedDescription ?? "未知"
                    self.log.error("export failed: \(reason, privacy: .public)")
                }
                continuation.resume(returning: data)
            }
        }
        if let data {
            log.notice("exported \(data.count, privacy: .public) bytes")
            onPicked(.image(data))
        } else {
            loadError = "这张照片读不出来（可能只在 iCloud 上且没开同步）"
            selected = nil
        }
    }

    /// 视频导出：`PHAssetResourceManager.writeData` 流式写临时文件。
    ///
    /// ## 为什么不用 `requestAVAsset`
    /// 它给的是相册库内的 URL（iCloud 视频会给占位文件），拷出去可能失效；
    /// `PHAssetResourceManager` 把**实际字节**流式写到我们指定的临时文件，
    /// 后续 `ChatSession.saveAttachment` 再从那里拷进 attachments —— 链路干净。
    private func exportVideo(_ asset: PHAsset) async {
        let resources = PHAssetResource.assetResources(for: asset)
        guard let resource = resources.first(where: { $0.type == .video })
                ?? resources.first else {
            loadError = "读不到这个视频的资源"
            selected = nil
            return
        }

        // 扩展名从原始文件名带过来（拿不到回退 mp4）
        let ext = resource.originalFilename.split(separator: ".").last.map(String.init) ?? "mp4"
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("picked_\(UUID().uuidString).\(ext)")

        let outcome: Result<URL, Error>? = await withCheckedContinuation { continuation in
            PHAssetResourceManager.default().writeData(
                for: resource, toFile: url, options: nil
            ) { error in
                if let error {
                    continuation.resume(returning: .failure(error))
                } else {
                    continuation.resume(returning: .success(url))
                }
            }
        }

        switch outcome {
        case .success(let url):
            if let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                log.notice("video exported \(size, privacy: .public) bytes")
            }
            onPicked(.video(url))
        case .failure(let error):
            log.error("video export failed: \(error.localizedDescription, privacy: .public)")
            loadError = "这个视频读不出来（可能只在 iCloud 上且没开同步）"
            selected = nil
        case nil:
            selected = nil
        }
    }
}

// MARK: - 缩略图

/// 单张缩略图。
///
/// 用 `PHCachingImageManager` 而不是 `PHImageManager.default()`：
/// 前者会预取并缓存临近的图，滚动时不闪白。对一个满屏网格这是必须的。
private struct PhotoThumbnail: View {

    let asset: PHAsset
    let selected: Bool

    @State private var image: UIImage?

    private static let manager = PHCachingImageManager()

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Rectangle().fill(Color.secondary.opacity(0.18))
            }

            // 视频段：左下角播放图标 + 时长（照片段没有这两个）
            if asset.mediaType == .video {
                Image(systemName: "play.fill")
                    .font(.caption)
                    .foregroundStyle(.white)
                    .shadow(radius: 1)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)

                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        Text(Self.durationText(asset.duration))
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.white)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 2)
                            .background(.black.opacity(0.55), in: Capsule())
                            .padding(4)
                    }
                }
            }

            if selected {
                Rectangle().fill(Color.accentColor.opacity(0.35))
                Image(systemName: "checkmark.circle.fill")
                    .font(.title2)
                    .foregroundStyle(.white)
                    .shadow(radius: 2)
            }
        }
        .frame(height: 118)
        .clipped()
        .contentShape(Rectangle())
        .task(id: asset.localIdentifier) {
            await load()
        }
    }

    private static func durationText(_ seconds: TimeInterval) -> String {
        let s = Int(seconds.rounded())
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    private func load() async {
        let options = PHImageRequestOptions()
        options.deliveryMode = .opportunistic
        options.resizeMode = .fast
        options.isNetworkAccessAllowed = true

        let target = CGSize(width: 240, height: 240)
        let result: UIImage? = await withCheckedContinuation { continuation in
            Self.manager.requestImage(
                for: asset,
                targetSize: target,
                contentMode: .aspectFill,
                options: options
            ) { img, info in
                // `opportunistic` 会回调两次（先低清再高清）。
                // 只在拿到**非 degraded** 的那次才 resume，
                // 否则 `CheckedContinuation` 会被重复 resume 而崩溃。
                let degraded = (info?[PHImageResultIsDegradedKey] as? Bool) ?? false
                if !degraded {
                    continuation.resume(returning: img)
                }
            }
        }
        image = result
    }
}
