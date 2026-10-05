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
                let provider = result.itemProvider
                // ⚠️ 第 117 轮：不再用 `registeredTypeIdentifiers(for:)`。
                // 该 API 在这个 SDK 上被解析成 **[String] 属性**而非方法，
                // CI 报 "cannot call value of non-function type '[String]'"。
                // 它的用途只是"要一个能读出数据的 UTI"；而 `public.image`
                // 就是图像数据的通用 UTI，足够。
                //
                // 具体是 png/jpg/gif/webp 由下面 extensionHint 的**魔数嗅探**定 ——
                // 这更可靠：PHPicker 给的是内存数据，没有原文件名可依赖。
                let typeId = UTType.image.identifier
                group.enter()
                provider.loadDataRepresentation(forTypeIdentifier: typeId) { data, _ in
                    defer { group.leave() }
                    guard let data, !data.isEmpty else { return }
                    lock.lock()
                    datas.append(data)
                    // ⚠️ 第 118 轮：闭包里调 Coordinator 自己的方法必须显式 self.
                    // CI 报 "requires explicit use of 'self' to make capture
                    // semantics explicit" —— 最基本的 Swift 规则，我漏了。
                    exts.append(self.extensionHint(for: typeId, data: data))
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
        /// PHPicker 给的是内存数据、没有原文件名，故这里按魔数兜底 ——
        /// 同样是"不能想当然认为有后缀"。
        ///
        /// ⚠️ 第 116 轮：`UTType.webp` **在这个 SDK 上不存在**（CI 报
        /// "type 'UTType' has no member 'webp'"）。webp 只能靠魔数
        /// `RIFF....WEBP` 识别，而这正是下面 sniffExtension 已覆盖的。
        private func extensionHint(for typeId: String, data: Data) -> String {
            if let ut = UTType(typeId) {
                if ut.conforms(to: .png) { return "png" }
                if ut.conforms(to: .jpeg) { return "jpg" }
                if ut.conforms(to: .gif) { return "gif" }
            }
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
