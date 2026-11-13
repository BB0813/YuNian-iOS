import Foundation
import UIKit

/// 要随消息发出的一张图（第 244 轮）。
///
/// ## 为什么需要这个类型
///
/// `AgentTurnRequest.image` 从移植以来一直是**写死的 `nil`** ——
/// 所以 iOS 聊天里的图片**只能是 AI 生成的**，用户没法发一张图给模型看。
/// 「我拍了个东西给你看」这条路完全断着，而这恰恰是聊天软件最低门槛的功能之一。
///
/// 底层能力其实一直是通的：Rust 的 `ImageInput` 支持 path / base64 / mime，
/// gateway 也有视觉链路。缺的只是**从相册到请求这一段**。
struct MessageAttachment: Identifiable, Equatable {

    enum Kind: String {
        case image
        case video
        /// 语音消息（第 252 轮）：发给对方**听**，不做语音识别。
        case audio
    }

    let id = UUID()
    let kind: Kind
    /// 原始字节。视频可能是几十 MB，所以**不常驻内存** —— 见 `url`。
    let data: Data?
    /// 落盘后的位置（视频用这个，避免把大文件读进内存）。
    let url: URL?
    /// 扩展名（决定 mime 与落盘文件名）。
    let fileExtension: String

    /// 给 Rust 的 `ImageInput.mime_type`。
    var mimeType: String {
        switch fileExtension.lowercased() {
        case "png": return "image/png"
        case "heic", "heif": return "image/heic"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "mp4", "mov": return "video/\(fileExtension.lowercased())"
        case "m4a": return "audio/m4a"
        case "mp3": return "audio/mpeg"
        case "caf", "wav": return "audio/\(fileExtension.lowercased())"
        default: return "image/jpeg"
        }
    }

    /// 估算体积（用于界面提示与"太大发不出去"的判断）。
    var byteCount: Int {
        if let data { return data.count }
        if let url, let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize {
            return size
        }
        return 0
    }

    static func == (lhs: MessageAttachment, rhs: MessageAttachment) -> Bool {
        lhs.id == rhs.id
    }
}

// MARK: - 从相册项构造

extension MessageAttachment {

    /// 视觉输入的上限。
    ///
    /// 为什么是 10MB：主流视觉模型的单图上限普遍在 5~20MB 之间，
    /// 而 base64 编码会让体积再涨 33%。10MB 原图 → 约 13MB 请求体，
    /// 在多数网关的 20MB 限制内。
    static let maxImageBytes = 10 * 1024 * 1024

    /// 从相册选中的图片构造。
    ///
    /// - 会做**一次等比压缩**：相册里的原图常有 4000px 以上、8~15MB，
    ///   直接发既慢又容易超模型上限。压到长边 1600px / JPEG 0.85 后
    ///   通常在 300KB 以内，而视觉模型普遍会再缩到 ~1024px，
    ///   所以**不会损失模型实际能用的细节**。
    static func fromImageData(_ raw: Data) -> MessageAttachment? {
        // 已经够小就不动它（避免二次编码掉质量）
        if raw.count <= 900 * 1024, let image = UIImage(data: raw),
           max(image.size.width, image.size.height) <= 1800 {
            return MessageAttachment(kind: .image, data: raw, url: nil, fileExtension: "jpg")
        }
        guard let image = UIImage(data: raw), let compressed = image.ynDownscaled() else {
            return nil
        }
        return MessageAttachment(kind: .image, data: compressed, url: nil, fileExtension: "jpg")
    }

    /// 从录音构造（第 252 轮）。音频文件通常几十 KB ~ 几百 KB，直接读进内存。
    static func fromAudioURL(_ url: URL) -> MessageAttachment? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return MessageAttachment(kind: .audio, data: data, url: url, fileExtension: "m4a")
    }

    /// 从相册选中的视频构造（视频**不读进内存**，只留 URL）。
    static func fromVideoURL(_ url: URL) -> MessageAttachment? {
        let ext = url.pathExtension.isEmpty ? "mp4" : url.pathExtension
        return MessageAttachment(kind: .video, data: nil, url: url, fileExtension: ext)
    }
}

extension UIImage {

    /// 等比缩到长边不超过 1600px 并转 JPEG。
    ///
    /// 用 `UIGraphicsImageRenderer` 而不是 `UIGraphicsBeginImageContext`：
    /// 后者在 iPhone 上是 1x 渲染（图会糊），前者按屏幕 scale 渲染。
    func ynDownscaled(maxDimension: CGFloat = 1600, quality: CGFloat = 0.85) -> Data? {
        let longest = max(size.width, size.height)
        guard longest > 0 else { return nil }
        let ratio = min(1, maxDimension / longest)
        let target = CGSize(width: size.width * ratio, height: size.height * ratio)

        let format = UIGraphicsImageRendererFormat.default()
        // ⚠️ 固定 1x：视觉模型不需要 3x 的像素量，
        // 按屏幕 scale 渲染会让 1600px 的图变成 4800px（体积翻 9 倍）。
        format.scale = 1
        format.opaque = true

        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        let resized = renderer.image { _ in
            draw(in: CGRect(origin: .zero, size: target))
        }
        return resized.jpegData(compressionQuality: quality)
    }
}
