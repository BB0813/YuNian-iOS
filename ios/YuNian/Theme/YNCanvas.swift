import SwiftUI

/// 全屏背景。
///
/// ## 依据
/// - **M6**（HIG `layout`）：全屏背景必须延伸到底栏/工具栏**之下**。
/// - **M3**：**不是**用来"喂给系统材质采样"的 —— 系统材质自己会处理滚动
///   边缘的易读性（**M5**）。这里只是一个克制的产品背景，**没有**光斑、
///   没有自绘模糊、没有为了让玻璃"有东西可采样"而堆的层。
/// - **M1**：它属于背景层，**不得**使用 Liquid Glass。
struct YNCanvas: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let c = YNTheme.palette(scheme)

        LinearGradient(
            colors: [c.backgroundElevated, c.background],
            startPoint: .top,
            endPoint: .bottom
        )
        .ignoresSafeArea()
    }
}
