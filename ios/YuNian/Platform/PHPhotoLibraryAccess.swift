import Foundation
import Photos
import UIKit

/// 保存图片到系统相册（第 243 轮，配合 `ImageViewer`）。
///
/// ## 为什么单独包一层
///
/// 1. **`.addOnly` 而不是 `.readWrite`** —— 我们只需要"往里加"，不需要读用户的相册。
///    要读权限会让用户看到一个更吓人的授权框（"允许访问所有照片"），
///    而实际只需要"添加"。**按最小权限要，是产品诚实的一部分。**
/// 2. `PHPhotoLibrary.performChanges` 是异步 + 可失败的，调用方（一个 SwiftUI 视图）
///    不该关心这些细节。
/// 3. 权限被拒和保存失败要**分开返回** —— 两者对用户的含义完全不同：
///    前者要去设置里改，后者可以直接重试。
enum PHPhotoLibraryAccess {

    enum Outcome {
        case success
        /// 用户拒绝过（或系统限制）—— 只能引导去设置
        case denied
        /// 有权限但写入失败（磁盘、iCloud 同步等）
        case failed
    }

    /// 保存原始数据到相册。
    ///
    /// - Parameter data: **原始图片数据**（不要传从 `UIImage` 重编码的结果 ——
    ///   重编码会掉质量，且 PNG 的透明通道会丢）。
    static func save(_ data: Data, completion: @escaping (Outcome) -> Void) {
        // `.addOnly`：只要"添加"权限。iOS 14+ 才有，项目最低 iOS 17，安全。
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                DispatchQueue.main.async { completion(.denied) }
                return
            }
            PHPhotoLibrary.shared().performChanges {
                // ⚠️ 用 `addResource(with:fileURL:)` 需要先落临时文件；
                // 用 `PHAssetCreationRequest.addResource(with:data:options:)` 可以直接喂数据，
                // 少一次磁盘往返，也不会在临时目录留垃圾。
                let request = PHAssetCreationRequest.forAsset()
                request.addResource(with: .photo, data: data, options: nil)
            } completionHandler: { ok, _ in
                DispatchQueue.main.async { completion(ok ? .success : .failed) }
            }
        }
    }
}
