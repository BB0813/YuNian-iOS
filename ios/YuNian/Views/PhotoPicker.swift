import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// 相册多选器 —— SwiftUI 包装 `PHPickerViewController`。
///
/// 为什么用它而不是 `UIImagePickerController`：PHPicker 不需要相册权限
/// （用户在系统面板里逐次授权），且支持多选与 `NSItemProvider` 直读数据。
/// Android 侧对应 `StickerManager.importStickerFile(uri, …)` 的 SAF/相册入口。
struct PhotoPicker: UIViewControllerRepresentable {

    let maxSelection: Int
    let onPicked: ([Data], [String]) -> Void      // (数据列表, 建议扩展名)
    let onCancel: () -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration(photoLibrary: .shared())
        config.selectionLimit = maxSelection
        config.filter = .images
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let parent: PhotoPicker

        init(_ parent: PhotoPicker) {
            self.parent = parent
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            if results.isEmpty {
                parent.onCancel()
                return
            }

            var datas: [Data] = []
            var exts: [String] = []
            let group = DispatchGroup()
            let lock = NSLock()

            for result in results {
                // 优先按 UTType 取，取不到退回通用 object
                let provider = result.itemProvider
                let typeId = provider.registeredTypeIdentifiers(for: .image).first
                    ?? UTType.image.identifier
                group.enter()
                provider.loadDataRepresentation(forTypeIdentifier: typeId) { data, _ in
                    defer { group.leave() }
                    guard let data, !data.isEmpty else { return }
                    lock.lock()
                    datas.append(data)
                    exts.append(extensionHint(for: typeId, data: data))
                    lock.unlock()
                }
            }

            group.notify(queue: .main) {
                self.parent.onPicked(datas, exts)
            }
        }

        /// 由 UTType / 魔数推断扩展名。
        ///
        /// Android 侧是三级判定（DISPLAY_NAME → MIME → 魔数嗅探），
        /// 因为 SAF/微信/QQ/华为等 provider 常不给后缀或返回 octet-stream。
        /// PHPicker 给的是内存数据，没有原文件名，故这里按魔数兜底 ——
        /// 同样是"不能想当然认为有后缀"。
        private func extensionHint(for typeId: String, data: Data) -> String {
            if let ut = UTType(typeId), ut.conforms(to: .png) { return "png" }
            if let ut = UTType(typeId), ut.conforms(to: .jpeg) { return "jpg" }
            if let ut = UTType(typeId), ut.conforms(to: .gif) { return "gif" }
            if let ut = UTType(typeId), ut.conforms(to: .webp) { return "webp" }
            return sniffExtension(data)
        }

        private func sniffExtension(_ data: Data) -> String {
            let b = [UInt8](data.prefix(16))
            if b.count >= 8, b[0] == 0x89, b[1] == 0x50, b[2] == 0x4E, b[3] == 0x47 { return "png" }
            if b.count >= 3, b[0] == 0xFF, b[1] == 0xD8, b[2] == 0xFF { return "jpg" }
            if b.count >= 6, b[0] == 0x47, b[1] == 0x49, b[2] == 0x46 { return "gif" }
            if b.count >= 12,
               b[0] == 0x52, b[1] == 0x49, b[2] == 0x46, b[3] == 0x46,
               b[8] == 0x57, b[9] == 0x45, b[10] == 0x42, b[11] == 0x50 { return "webp" }
            return "png"
        }
    }
}
