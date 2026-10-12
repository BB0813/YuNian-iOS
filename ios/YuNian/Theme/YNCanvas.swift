import SwiftUI

/// 全屏画布 —— 第 220 轮「暖夜」重做。
///
/// ## 旧实现为什么让整套界面变丑了
/// 它只有 `LinearGradient(backgroundElevated → background)`，而这两个色相差很小
/// （`#1E1930 → #14111D`），**肉眼看基本是一块死黑**。
/// 而 `DESIGN.md` §3 写的是画布 =「**渐变 + 品牌辉光**」——
/// 也就是说**偏离文档的是这份朴素实现**，不是这次改动。
/// 少了辉光，画布就没有"底"，内容面又都在同一族深色里，整屏读起来像一整块灰板。
///
/// ## 现在它做三件事（都已写进 `DESIGN.md` §3.1）
/// 1. **可见的黄昏渐变**：上暖下暗，两端亮度差拉开到肉眼可辨；
/// 2. **双光源**：品牌紫在右上、暖色在左下 —— 「暖夜」的体积感来源，
///    同时满足 `DESIGN.md`「给玻璃可采样的东西」这个前提；
/// 3. **极轻的颗粒**：破掉数字平面的塑料感（程序生成，无资源依赖）。
///
/// ## 三条无障碍约束
/// - `Reduce Transparency`：辉光与颗粒全部去掉，只留纯色渐变；
/// - `Reduce Motion`：辉光静止（不做呼吸）；
/// - 画布**永不承载信息** —— 任何"只靠画布深浅传达状态"的实现都是 bug。
struct YNCanvas: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// 呼吸相位（Reduce Motion 时恒为 0，辉光静止）。
    @State private var phase: Double = 0

    var body: some View {
        let c = YNTheme.palette(scheme)
        ZStack {
            // ① 黄昏底：上暖下暗
            LinearGradient(
                colors: [c.backgroundElevated, c.background],
                startPoint: .top,
                endPoint: .bottom
            )

            if !reduceTransparency {
                // ② 双光源
                glow(c.canvasGlow, size: 1.0, alignment: .topTrailing)
                glow(c.canvasGlowWarm, size: 0.8, alignment: .bottomLeading)

                // ③ 颗粒（0.05 在深色上刚可感知，不影响任何对比度）
                YNGrain(opacity: scheme == .dark ? 0.05 : 0.035)
                    .blendMode(.overlay)
                    .allowsHitTesting(false)
            }
        }
        .ignoresSafeArea()
        .onAppear {
            guard !reduceMotion, !reduceTransparency else { return }
            withAnimation(.easeInOut(duration: 9).repeatForever(autoreverses: true)) {
                phase = 1
            }
        }
    }

    /// 一个径向柔和光源。`phase` 让两个光源缓慢反向移动 ——
    /// 静止的辉光容易被读成"贴上去的色块"，缓慢呼吸才像环境光。
    private func glow(_ color: Color, size: CGFloat, alignment: Alignment) -> some View {
        let shift = reduceMotion ? 0 : (phase * 12)
        let isTopTrailing = alignment == .topTrailing
        return RadialGradient(
            colors: [color.opacity(0.30), color.opacity(0.10), .clear],
            center: .center,
            startRadius: 0,
            endRadius: 260
        )
        .frame(width: 520, height: 520)
        .scaleEffect(size)
        .offset(x: isTopTrailing ? 90 - shift : -110 + shift,
                y: isTopTrailing ? -170 - shift : 290 + shift)
        .blur(radius: 34)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: alignment)
    }
}

/// 程序生成的颗粒层（不引入任何图片资源）。
///
/// ⚠️ 固定种子的伪随机：同一帧内完全确定 —— **不抖动的颗粒**。
/// 每帧重新随机的颗粒在 OLED 上像故障噪点，比没有颗粒还糟。
private struct YNGrain: View {
    var opacity: Double

    var body: some View {
        Canvas { context, size in
            var seed: UInt64 = 0x9E37_79B9_7F4A_7C15
            func next() -> Double {
                seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
                return Double((seed >> 33) & 0xFF_FFFF) / Double(0xFF_FFFF)
            }
            // 密度按面积算：大屏多一点、小屏少一点，观感一致
            let count = max(Int((size.width * size.height) / 5200), 1)
            for _ in 0..<count {
                let x = next() * size.width
                let y = next() * size.height
                let r = 0.4 + next() * 0.7
                context.fill(
                    Path(ellipseIn: CGRect(x: x, y: y, width: r, height: r)),
                    with: .color(.white.opacity(next() > 0.5 ? 0.55 : 0.28))
                )
            }
        }
        .opacity(opacity)
        // 光栅化一次，避免每帧重画几千个点
        .drawingGroup()
        .ignoresSafeArea()
    }
}
