import SwiftUI
import os

/// 输入栏左侧的附件按钮（第 244 轮，第 250 轮改用自绘相册）。
///
/// ## 三次失败的教训（都记在这里，别再走回去）
///
/// | 版本 | 做法 | 结果 |
/// |---|---|---|
/// | 244 | `.plain` + `Image`，28×28，在 `safeAreaBar` 里 | 点不开（命中区 + 容器） |
/// | 248 | 44×44 + `contentShape`，仍用 `PhotosPicker` | 图标能看到了，但选照片不弹 |
/// | 249 | `Menu` 里内联 `PhotosPicker` / `.photosPicker` 修饰符 | 「照片选项点不开」 |
///
/// 结论：**`PhotosPicker` 的呈现是黑盒** —— 它不弹时没有任何错误、回调或日志。
/// 所以改成自绘 `PhotoLibraryPicker`（`PHPhotoLibrary` + 自己的网格），
/// 每一步都能打日志、能显示错误。
struct ComposerAttachButton: View {

    let colors: YNTheme.Palette
    /// 选好之后回调（已经在后台压好尺寸）。
    let onPicked: (MessageAttachment) -> Void
    let onError: (String) -> Void

    @State private var showSourceDialog = false
    @State private var showPhotoPicker = false
    @State private var showVideoPicker = false
    @State private var showCamera = false

    private let log = Logger(subsystem: "com.yunian.ai", category: "composer.attach")

    var body: some View {
        Button {
            log.notice("attach button tapped")
            showSourceDialog = true
        } label: {
            Image(systemName: "plus")
                .font(.body.weight(.medium))
                .frame(width: 28, height: 28)
                // ⚠️ 命中区：HIG 下限 44×44。`contentShape` 必须有 ——
                // SwiftUI 只把**绘制内容**当命中区，图标周围的空白不接收点击。
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel("添加附件")
        .confirmationDialog("添加", isPresented: $showSourceDialog, titleVisibility: .visible) {
            Button("从相册选择") {
                log.notice("source = photo library")
                // 稍等一拍：action sheet 正在收起时立刻 present 会被系统丢弃。
                Task {
                    try? await Task.sleep(for: .milliseconds(200))
                    showPhotoPicker = true
                }
            }
            Button("视频") {
                log.notice("source = video library")
                Task {
                    try? await Task.sleep(for: .milliseconds(200))
                    showVideoPicker = true
                }
            }
            Button("拍照") {
                log.notice("source = camera")
                Task {
                    try? await Task.sleep(for: .milliseconds(200))
                    showCamera = true
                }
            }
            Button("取消", role: .cancel) {}
        }
        // 自绘相册（第 250 轮）—— 不依赖 `PhotosPicker` 的黑盒呈现。
        // 第 256 轮：回调升级为 `PickedMedia`（图片字节 / 视频临时文件 URL）。
        .sheet(isPresented: $showPhotoPicker) {
            PhotoLibraryPicker(mediaKind: .photo) { media in
                showPhotoPicker = false
                switch media {
                case .image(let data):
                    if let attachment = MessageAttachment.fromImageData(data) {
                        onPicked(attachment)
                    } else {
                        onError("这张图格式不支持")
                    }
                case .video(let url):
                    if let attachment = MessageAttachment.fromVideoURL(url) {
                        onPicked(attachment)
                    } else {
                        onError("这个视频读不出来")
                    }
                }
            } onCancel: {
                showPhotoPicker = false
            }
        }
        // 视频段用同一个自绘选择器，只是初始分段不同（第 256 轮）。
        .sheet(isPresented: $showVideoPicker) {
            PhotoLibraryPicker(mediaKind: .video) { media in
                showVideoPicker = false
                switch media {
                case .image(let data):
                    if let attachment = MessageAttachment.fromImageData(data) {
                        onPicked(attachment)
                    } else {
                        onError("这张图格式不支持")
                    }
                case .video(let url):
                    if let attachment = MessageAttachment.fromVideoURL(url) {
                        onPicked(attachment)
                    } else {
                        onError("这个视频读不出来")
                    }
                }
            } onCancel: {
                showVideoPicker = false
            }
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker { image in
                showCamera = false
                // 拍照拿到的 `UIImage` 先转 Data 再统一走压缩路径，
                // 与相册来的图**同一条处理链** —— 两条链迟早会漂。
                if let data = image.jpegData(compressionQuality: 0.9),
                   let attachment = MessageAttachment.fromImageData(data) {
                    onPicked(attachment)
                } else {
                    onError("这张照片处理失败")
                }
            } onCancel: {
                showCamera = false
            }
            .ignoresSafeArea()
        }
    }
}

// MARK: - 相机

/// 系统相机（`UIImagePickerController` 包一层）。
///
/// 为什么不用 `AVCaptureSession` 自己搭：系统相机自带对焦、闪光灯、HDR、
/// 实时照片、构图网格，自己搭一遍只会更差。**这类系统能力不该重写。**
struct CameraPicker: UIViewControllerRepresentable {
    let onPicked: (UIImage) -> Void
    let onCancel: () -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        // 模拟器没有相机 —— 不判断的话会直接崩（`sourceType` 不可用）。
        picker.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera)
            ? .camera : .photoLibrary
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onPicked: onPicked, onCancel: onCancel)
    }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onPicked: (UIImage) -> Void
        let onCancel: () -> Void

        init(onPicked: @escaping (UIImage) -> Void, onCancel: @escaping () -> Void) {
            self.onPicked = onPicked
            self.onCancel = onCancel
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            if let image = info[.originalImage] as? UIImage {
                onPicked(image)
            } else {
                onCancel()
            }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onCancel()
        }
    }
}
