import SwiftUI
import UIKit

/// 全屏图片查看器（第 243 轮，BACKLOG D2）。
///
/// ## 为什么需要
///
/// 聊天里的图片（生成图、用户发的图）原先**点了没有任何反应** ——
/// 既不能放大看细节，也不能保存。用户看到一张喜欢的生成图，唯一能做的是截图。
/// 这是 iOS 上最基础的期待之一：**点图放大**。
///
/// ## 交互
///
/// | 手势 | 行为 |
/// |---|---|
/// | 双指捏合 | 缩放 1×~4×，松手回弹到合法范围 |
/// | 双击 | 在 1× 和 2.5× 之间切换 |
/// | 单击 | 收起控制栏（沉浸看） |
/// | 向下拖 | 关闭（放大状态下不生效 —— 那时拖动是"看细节"） |
///
/// ## 为什么是纯 SwiftUI（而不是包一层 `UIScrollView`）
///
/// 第一版用了 `UIViewRepresentable` + `UIScrollView`，被项目的「静态 UIKit 调用」
/// 关卡连拦三次（`UIView.animate` / `NSLayoutConstraint.activate` /
/// `UITapGestureRecognizer(`）。
///
/// 那条关卡不是形式主义 —— 它守的是**这类代码无法在 SwiftUI 预览和单测里验证**，
/// 而图片查看器恰恰是"必须能在模拟器里点一遍"的东西。
/// 所以这里改成纯 SwiftUI：`MagnifyGesture` + `SpatialTapGesture` + `DragGesture`，
/// 全部状态是 `@State`，逻辑可以直接单测。
///
/// 代价是没有 `UIScrollView` 的惯性滚动。对"看一张图"这个场景可以接受 ——
/// 用户要么看全图，要么放大看局部，不会像读长文那样需要惯性。
struct ImageViewer: View {

    let image: UIImage
    /// 保存用的原始数据。从 `UIImage` 重编码会掉质量，所以能拿到原数据就用原数据。
    let originalData: Data?
    let suggestedName: String?

    @Environment(\.dismiss) private var dismiss
    @State private var showControls = true
    @State private var savedToast: String?

    // 缩放/平移状态
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    /// 拖动关闭的进度（0~1），用来让背景随拖动变淡
    @State private var dismissProgress: CGFloat = 0

    private let minScale: CGFloat = 1
    private let maxScale: CGFloat = 4
    private let closeThreshold: CGFloat = 120

    var body: some View {
        ZStack {
            Color.black
                .opacity(1 - dismissProgress * 0.6)
                .ignoresSafeArea()
                .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { showControls.toggle() } }

            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .scaleEffect(scale)
                .offset(offset)
                .gesture(dragGesture)
                .gesture(magnifyGesture)
                .onTapGesture(count: 2) { toggleZoom() }
                .accessibilityLabel("图片")
                .accessibilityAddTraits(.isImage)

            if showControls {
                controls.transition(.opacity)
            }
        }
        .statusBarHidden(!showControls)
        .animation(.easeInOut(duration: 0.2), value: showControls)
        .overlay(alignment: .bottom) {
            if let savedToast {
                Text(savedToast)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 10)
                    .background(.black.opacity(0.72), in: Capsule())
                    .padding(.bottom, 90)
                    .transition(.opacity)
            }
        }
    }

    private var controls: some View {
        VStack {
            HStack {
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(.black.opacity(0.45), in: Circle())
                }
                .accessibilityLabel("关闭")

                Spacer()

                Button {
                    saveToPhotoLibrary()
                } label: {
                    Image(systemName: "square.and.arrow.down")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(.black.opacity(0.45), in: Circle())
                }
                .accessibilityLabel("保存到相册")
            }
            .padding(.horizontal, YNTheme.Space.lg)
            .padding(.top, YNTheme.Space.sm)

            Spacer()
        }
    }

    // MARK: - 手势

    private var magnifyGesture: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                // 夹取到 [minScale, maxScale]：不夹的话能缩到 0（图消失）或无穷大（白屏）
                let next = lastScale * value.magnification
                scale = min(max(next, minScale * 0.8), maxScale * 1.2)
            }
            .onEnded { _ in
                // 松手回弹到合法范围 —— 允许拖出一点再弹回来，手感比硬边界好
                withAnimation(.spring(duration: 0.25)) {
                    scale = min(max(scale, minScale), maxScale)
                    if scale == minScale { offset = .zero }
                }
                lastScale = scale
                if scale == minScale { lastOffset = .zero }
            }
    }

    private var dragGesture: some Gesture {
        DragGesture()
            .onChanged { value in
                if scale > 1 {
                    // 放大状态：拖动是平移看细节，不关闭
                    offset = CGSize(
                        width: lastOffset.width + value.translation.width,
                        height: lastOffset.height + value.translation.height
                    )
                } else if value.translation.height > 0 {
                    // 原始尺寸：只允许向下拖（关闭手势）
                    dismissProgress = min(value.translation.height / closeThreshold, 1)
                    offset = CGSize(width: 0, height: value.translation.height)
                }
            }
            .onEnded { value in
                if scale > 1 {
                    lastOffset = offset
                    return
                }
                if value.translation.height > closeThreshold {
                    dismiss()
                } else {
                    withAnimation(.spring(duration: 0.25)) {
                        offset = .zero
                        dismissProgress = 0
                    }
                    lastOffset = .zero
                }
            }
    }

    private func toggleZoom() {
        withAnimation(.spring(duration: 0.25)) {
            if scale > 1 {
                scale = 1
                offset = .zero
                lastScale = 1
                lastOffset = .zero
            } else {
                scale = 2.5
                lastScale = 2.5
            }
        }
    }

    // MARK: - 保存

    /// 保存到系统相册。
    ///
    /// ⚠️ **优先写原始数据**：从 `UIImage` 重编码成 JPEG 会掉质量，
    /// 而且会丢掉 PNG 的透明通道（表情包会变成黑底）。
    private func saveToPhotoLibrary() {
        let payload = originalData ?? image.pngData()
        guard let payload else {
            flash("这张图无法保存")
            return
        }
        PHPhotoLibraryAccess.save(payload) { result in
            switch result {
            case .success:
                flash("已保存到相册")
            case .denied:
                flash("没有相册权限，请到「设置」里允许")
            case .failed:
                flash("保存失败")
            }
        }
    }

    private func flash(_ text: String) {
        withAnimation { savedToast = text }
        Task {
            try? await Task.sleep(for: .seconds(2))
            withAnimation { savedToast = nil }
        }
    }
}
