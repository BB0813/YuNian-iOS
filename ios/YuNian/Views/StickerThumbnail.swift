import SwiftUI
import UIKit

/// 表情缩略图 —— 从 stickers 目录按 fileName 读图。
///
/// ## 为什么单独一个组件（第 120 轮）
/// 表情气泡（ChatView.stickerBubble）和选择器网格（StickerPickerSheet）
/// 都要"按 fileName 读一张图，读不到给占位"。
/// 两份各写一遍就会分叉——这正是第 8 轮记录的「同一契约两处各自硬编码」。
struct StickerThumbnail: View {

    let fileName: String

    /// 读到的图片。惰性：只有视图真的出现时才解码。
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
            } else {
                ZStack {
                    Color(uiColor: .tertiarySystemFill)
                    Image(systemName: "photo")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .task(id: fileName) { image = loadImage() }
        .onChange(of: fileName) { _, newName in
            image = loadImage(for: newName)
        }
    }

    private func loadImage(for name: String = "") -> UIImage? {
        let target = name.isEmpty ? fileName : name
        guard !target.isEmpty,
              let url = try? AppPaths.stickersDirectory()
                  .appendingPathComponent(target),
              let data = try? Data(contentsOf: url)
        else { return nil }
        return UIImage(data: data)
    }
}
