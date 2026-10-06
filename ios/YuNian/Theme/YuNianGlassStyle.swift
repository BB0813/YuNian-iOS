import SwiftUI

/// 毛玻璃风格分叉 —— iOS 17 与 26 的分水岭。
///
/// ## 用户的原话（第 176 轮目标）
/// > 优先把 iOS 原生 UI 做出来吧。根据 iOS17 和 iOS26 作为分水岭，
/// > iOS17 默认走旧原生 UI，iOS26 及以上默认走新的毛玻璃原生 UI。
///
/// ## 两条路各自是什么
/// | 档位 | 适用 | 实现 | 为什么这是"原生" |
/// |---|---|---|---|
/// `.legacy` | iOS 17–25 | `.ultraThinMaterial` | iOS 17-25 代的原生材质体系 | 
/// `.liquidGlass` | iOS 26+ | `.glassEffect(.regular, in:)` | Apple 26 代 Liquid Glass 原生 API |
///
/// ## 与之前做法的区别（重要）
/// 第 123 轮起我们用的是**手搓三层叠加**（半透明底 + 竖直高光 + 描边）
/// 去逼近 Android 的 `com.kyant.backdrop` 真折射。当时注明了
/// "SwiftUI 没有跨层采样 API"。
///
/// **iOS 26 之后这句话不再成立** —— Apple 给了原生 `.glassEffect`。
/// 所以本轮起：
/// - `.liquidGlass` 直接用原生 API，不再手搓
/// - `.legacy` 改用 iOS 17-25 代的**原生材质**，也不手搓
///
/// 手搓版保留为 deprecated 参考（`legacyHandRolled`），不再默认走。
///
/// ## 默认值怎么定
/// 用户**没有显式设置**时按 OS 版本：`#available(iOS 26.0, *)` → liquidGlass，
/// 否则 legacy。设置过一次就听用户的（"默认"二字指这个）。
enum YuNianGlassStyle: String, CaseIterable, Identifiable {

    /// iOS 17–25 的原生材质。
    case legacy = "legacy"

    /// iOS 26+ 的原生 Liquid Glass。
    case liquidGlass = "liquidGlass"

    var id: String { rawValue }

    /// 设置页里显示的名字（用户看得懂的分档说明）。
    var displayName: String {
        switch self {
        case .legacy: return "经典毛玻璃（iOS 17–25 原生）"
        case .liquidGlass: return "液态玻璃（iOS 26+ 原生）"
        }
    }

    var detail: String {
        switch self {
        case .legacy:
            return "用系统材质模糊背景，观感与 iOS 17–25 的系统控件一致。"
        case .liquidGlass:
            return "用 Apple 26 代 Liquid Glass，玻璃之间可相互融合变形。仅 iOS 26 及以上可用。"
        }
    }

    // MARK: - 解析

    /// UserDefaults key —— 存用户显式选择；nil = 跟随系统。
    static let overrideKey = "glass_style_override"

    /// 按用户设置解析；**没有设置时按 OS 版本自动判**。
    ///
    /// - Parameter raw: UserDefaults 里存的 rawValue；nil 或无法解析都走自动。
    static func resolved(raw: String?) -> YuNianGlassStyle {
        if let raw, let explicit = YuNianGlassStyle(rawValue: raw) {
            return explicit
        }
        return autoDetected()
    }

    /// OS 默认档位。这就是"分水岭"的落点。
    static func autoDetected() -> YuNianGlassStyle {
        if #available(iOS 26.0, *) {
            return .liquidGlass
        }
        return .legacy
    }

    /// 当前应生效的档位（读 UserDefaults + OS 版本）。
    static func current() -> YuNianGlassStyle {
        resolved(raw: UserDefaults.standard.string(forKey: overrideKey))
    }
}

// MARK: - 环境注入

/// 让整棵树共享同一个档位，避免每个视图各读一次 UserDefaults。
private struct GlassStyleEnvironmentKey: EnvironmentKey {
    static let defaultValue: YuNianGlassStyle = YuNianGlassStyle.current()
}

extension EnvironmentValues {
    var yuNianGlassStyle: YuNianGlassStyle {
        get { self[GlassStyleEnvironmentKey.self] }
        set { self[GlassStyleEnvironmentKey.self] = newValue }
    }
}

// MARK: - 分叉的玻璃修饰符

extension View {

    /// 毛玻璃背景 —— 按 `YuNianGlassStyle` 分叉。
    ///
    /// - Parameters:
    ///   - colors: 语义色。legacy 档用于取描边色与"无材质时的兜底纯色"，
    ///     liquidGlass 档只用于 glass tint。
    ///   - radius: 圆角。两档都用；liquidGlass 用 `.rect(cornerRadius:)`。
    ///   - tint: 玻璃着色。liquidGlass 档映射到 `.glassEffect(.tint(...))`。
    ///
    /// ## 两条分支
    /// ```
    /// iOS 26+  → .glassEffect(.regular.tint(c), in: .rect(cornerRadius: r))
    /// iOS 17-25 → .background(.ultraThinMaterial) + 暗色描边
    /// ```
    ///
    /// ⚠️ 与手搓版的视觉差异是**预期的**：手搓版是对 Android 玻璃的近似，
    /// 这两档都是 Apple 原生。用户的新目标就是"要原生，不要近似"。
    @ViewBuilder
    func yuNianGlass(
        _ colors: YuNianTheme.Colors,
        radius: CGFloat = YuNianTheme.Radius.glassDefault,
        surfaceColor: Color? = nil,
        tint: Color? = nil,
        isDark: Bool
    ) -> some View {
        let style = YuNianGlassStyle.current()
        switch style {
        case .liquidGlass:
            liquidGlass(radius: radius, tint: tint ?? surfaceColor)
        case .legacy:
            legacyGlass(colors: colors, radius: radius,
                        surfaceColor: surfaceColor, isDark: isDark)
        }
    }

    /// iOS 26+ 原生 Liquid Glass。
    @available(iOS 26.0, *)
    private func liquidGlass(radius: CGFloat, tint: Color?) -> some View {
        let glass: Glass = {
            guard let tint else { return .regular }
            return .regular.tint(tint)          // 只有 tint 非空才包一层
        }()
        return self
            .glassEffect(glass, in: .rect(cornerRadius: radius))
    }

    /// iOS 17–25 原生材质。
    ///
    /// 暗色下加 0.5dp 白@0.07 描边 —— 沿用 `GlassCard.kt:49` 的取值，
    /// 因为 ultraThinMaterial 在暗色下边缘太糊，需要一道边才立得住。
    private func legacyGlass(
        colors: YuNianTheme.Colors,
        radius: CGFloat,
        surfaceColor: Color?,
        isDark: Bool
    ) -> some View {
        var out = self
            .background(.ultraThinMaterial)
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
        if isDark {
            out = out.overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.07), lineWidth: 0.5)
            )
        }
        return out
    }

    /// 第 123–174 轮的手搓三层实现 —— **已废弃，仅作参考保留**。
    ///
    /// 保留原因：它是逐条对齐 Kotlin `GlassSurface.kt:25-64` 的复刻，
    /// 将来若要在某个平台复现 Android 原样观感，这份代码是唯一的依据。
    /// 默认路径已不走这里（见 `yuNianGlass` 的分叉）。
    @available(*, deprecated, message: "改用 yuNianGlass（按 OS 分叉到原生材质/原生 Liquid Glass）")
    func yuNianGlassHandRolled(
        _ colors: YuNianTheme.Colors,
        radius: CGFloat = YuNianTheme.Radius.glassDefault,
        surfaceColor: Color? = nil,
        isDark: Bool
    ) -> some View {
        let base = surfaceColor ?? colors.glassSurface
        return self
            .background(
                ZStack {
                    base.opacity(0.50)
                    LinearGradient(
                        stops: [
                            .init(color: .white.opacity(0.14), location: 0),
                            .init(color: .white.opacity(0.04), location: 0.35),
                            .init(color: .white.opacity(0), location: 1),
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                }
            )
            .cornerRadius(radius)
            .overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .strokeBorder(isDark ? Color.white.opacity(0.07) : Color.clear,
                                  lineWidth: 0.5)
            )
    }
}
