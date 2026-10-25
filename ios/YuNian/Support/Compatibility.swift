//
//  Compatibility.swift
//  YuNian
//
//  把 deployment target 从 iOS 17.0 降到 16.0 所需的三个垫片。
//
//  为什么是 16.0 而不是更低：
//    · `NavigationStack`（13 处）与 `LabeledContent`（102 处）都是 iOS 16 起才有，
//      要下探到 15 就得重写这 115 处语义，收益是覆盖更老的机型 —— 不值。
//    · iOS 17 专属 API 实际只用了三样：两参数 `onChange`、`ContentUnavailableView`、
//      `defaultScrollAnchor`。这三个都能在下面用垫片吃掉，不改调用点。
//
//  设计原则：**垫片只在系统版本不够时生效**，能用系统实现就用系统的 ——
//  这样 iOS 17+ 上的行为与降级前完全一致，不存在「为了兼容反而劣化新系统」。
//

import SwiftUI

// MARK: - 1. 两参数 onChange（iOS 17 签名 → iOS 16 实现）

/// iOS 17 的 `onChange(of:) { oldValue, newValue in }` 在 iOS 16 上不存在。
///
/// 这里补一个同名同签名的扩展。SDK 里那个带 `@available(iOS 17.0, *)`，
/// 所以在 target 16.0 下编译器只会选中本实现；调用点一个字都不用改。
///
/// 注意：内部必须调用**单参数**版本（iOS 14 起就有），否则会递归到自己。
extension View {
    func onChange<V: Equatable>(
        of value: V,
        _ action: @escaping (V, V) -> Void
    ) -> some View {
        modifier(YNPairOnChangeModifier(value: value, action: action))
    }
}

private struct YNPairOnChangeModifier<V: Equatable>: ViewModifier {
    let value: V
    let action: (V, V) -> Void

    /// 首次改动时要能报出「真正的旧值」，所以 onAppear 时把初值记下来。
    @State private var previous: V?
    @State private var primed = false

    func body(content: Content) -> some View {
        content
            .onAppear {
                guard !primed else { return }
                primed = true
                previous = value
            }
            .onChange(of: value) { newValue in
                let old = previous ?? newValue
                previous = newValue
                action(old, newValue)
            }
    }
}

// MARK: - 2. 搜索无结果的占位视图（ContentUnavailableView 是 iOS 17+）

/// 对齐 `ContentUnavailableView.search(text:)` 的观感：放大镜 + 一句说明。
struct YNSearchEmptyView: View {
    let text: String

    var body: some View {
        VStack(spacing: YNTheme.Space.sm) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 40, weight: .regular))
                .foregroundStyle(.secondary)
            Text("没有找到“\(text)”")
                .font(.headline)
                .foregroundStyle(.primary)
            Text("换个关键词试试")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, YNTheme.Space.xl)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - 3. 聊天初始锚点（defaultScrollAnchor 是 iOS 17+）

extension View {
    /// iOS 17+：首次布局即停在底部；iOS 16：降级为空操作。
    ///
    /// iOS 16 下落空是有意的 —— `ChatView` 里 `.onChange(of: displayMessages.count)`
    /// 在 `isNearBottom == true`（初始值就是 true）时已经会 `scrollToBottom`，
    /// 历史一次性灌入的场景由它兜住，不需要再叠一层手写滚动。
    @ViewBuilder
    func ynDefaultScrollAnchorBottom() -> some View {
        if #available(iOS 17.0, *) {
            self.defaultScrollAnchor(.bottom)
        } else {
            self
        }
    }
}
