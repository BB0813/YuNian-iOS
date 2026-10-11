# Apple Liquid Glass 技术层（第一手摘录）

> 采集方式：Apple DocC JSON 接口 `https://developer.apple.com/tutorials/data/<路径>.json`，
> 逐字段提取 `abstract` / `primaryContentSections[].content[]` / `declarations` / `metadata.platforms`。
> 所有 **原文** 均为英文原句，逐字复制（含 Apple 的弯引号 `’`），未做翻译或改写。
> 采集时间：2026 年；文档版权标记为 `Copyright © 2026 Apple Inc.`
> 原始提取文本保存在同目录 `_raw/`，可逐条复核。

---

## 0. 全局可用性结论（先看这条）

本文档中抓取到的**每一个** Liquid Glass 相关 API，可用性完全一致：

```
iOS 26.0 / iPadOS 26.0 / Mac Catalyst 26.0 / macOS 26.0 / tvOS 26.0 / watchOS 26.0
```

例外（额外支持 visionOS 26.0）：`safeAreaBar(edge:alignment:spacing:content:)`、`ConcentricRectangle`、
`Shape.rect(corners:isUniform:)`、`containerShape(_:)`、`ScrollEdgeEffectStyle` 及其
`.automatic` / `.hard` / `.soft`、`Edge.Corner.Style`、`scrollEdgeEffectHidden(_:for:)`。

**没有** 任何一个是 iOS 26 以下可用。全部必须 `@available(iOS 26.0, *)` 或
`if #available(iOS 26.0, *)` 包裹，否则无法编译。

---

## 1. Adopting Liquid Glass（采用 Liquid Glass）

- **来源**：`https://developer.apple.com/tutorials/data/documentation/technologyoverviews/adopting-liquid-glass.json`
- **标题**：Adopting Liquid Glass ｜ **abstract**：`Find out how to bring the new material to your app.`

### 1.1 何时/如何自动获得新外观（工程约束核心）

- **原文**：

  > "If your app uses standard components from SwiftUI, UIKit, or AppKit, your interface picks up the latest look and feel on the latest platform releases for iOS, iPadOS, macOS, tvOS, and watchOS. In Xcode, build your app with the latest SDKs, and run it on the latest platform releases to see the changes in your interface."

  > "Start by building your app in the latest version of Xcode to see the changes."

  > "Leverage system frameworks to adopt Liquid Glass automatically. In system frameworks, standard components like bars, sheets, popovers, and controls automatically adopt this material. System frameworks also dynamically adapt these components in response to factors like element overlap and focus state. Take advantage of this material with minimal code by using standard components from SwiftUI, UIKit, and AppKit."

  > "To update and ship your app with the latest SDKs while keeping your app as it looks when built against previous versions of the SDKs, you can add the UIDesignRequiresCompatibility key to your project’s Info pane."

- **要点**：Apple 给出的"自动获得"条件是 **两个同时满足**：①用最新 SDK 构建；②跑在最新平台版本上。同时 Apple 提供
  `UIDesignRequiresCompatibility` 作为"用新 SDK 但保持旧外观"的逃生舱——这句话出现在本页**正文最后一段**，
  与 Info.plist 页的 warning 互相印证（见第 5 节）。

- **对本工程的约束**：
  - **必须**：在 Xcode 工程里确认 `IPHONEOS_DEPLOYMENT_TARGET` 与链接的 SDK 版本；要拿到 Liquid Glass 就必须用最新 SDK 构建。
  - **禁止**：把 `UIDesignRequiresCompatibility = YES` 当作长期方案提交——Apple 明确称其为临时手段，且在 iOS 27+ 被忽略。
  - **允许**：调试期临时开启该键以做"新旧外观 diff"。

### 1.2 custom background 会干扰系统材质（逐字摘全）

- **原文**：

  > "**Reduce your use of custom backgrounds in controls and navigation elements.** Any custom backgrounds and appearances you use in these elements might overlay or interfere with Liquid Glass or other effects that the system provides, such as the scroll edge effect. Make sure to check any custom backgrounds in elements like split views, tab bars, and toolbars. Prefer to remove custom effects and let the system determine the background appearance, especially for the following elements:"

  Apple 随后用 Tab 列出的清单（SwiftUI 侧）：
  `NavigationStack`、`NavigationSplitView`、`WindowStyle.titleBar`、`View.toolbar(content:)`；
  UIKit 侧：`UINavigationBar`、`UITabBar`、`UIToolbar`、`UISplitViewController`；
  AppKit 侧：`NSToolbar`、`NSSplitView`。

  > "Audit the backgrounds of sheets and popovers. Check whether you add a visual effect view to your popover’s content view, and remove those custom background views to provide a consistent experience with other sheets across the system."

  > "Check for crowding or overlapping of controls. Prefer to use standard spacing metrics instead of overriding them, and avoid overcrowding or layering Liquid Glass elements on top of each other."

- **要点**：这是"**减法优先**"条款。自定义背景（`.background(...)`、`UITabBarAppearance` 覆盖、
  popover 里塞 `UIVisualEffectView`）会**叠加或干扰**系统的 Liquid Glass 与 scroll edge effect。
  Apple 用的是 "might overlay or interfere"，即风险提示；行动指令是 "Prefer to remove"。

- **对本工程的约束**：
  - **禁止**：在 `NavigationStack` / `toolbar` / `TabView` / `.sheet` / `.popover` 上再叠加自定义半透明背景、
    渐变遮罩或 `UIVisualEffectView`。
  - **必须**：所有"磨砂/毛玻璃导航栏"自研组件在 iOS 26 分支上**直接不渲染自绘背景**，交给系统。
  - **允许**：内容层（content layer）使用 `Material` 标准材质（见第 7 节 HIG）。

### 1.3 避免滥用 / 限制在最重要元素（逐字）

- **原文**：

  > "**Avoid overusing Liquid Glass effects.** If you apply Liquid Glass effects to a custom control, do so sparingly. Liquid Glass seeks to bring attention to the underlying content, and overusing this material in multiple custom controls can provide a subpar user experience by distracting from that content. Limit these effects to the most important functional elements in your app."

- **要点**：Apple 把"少用"写成了明确的设计意图理由：Liquid Glass 的目的是**把注意力让给底层内容**，
  用多了反而 "distracting from that content"。

- **对本工程的约束**：
  - **必须**：为自绘 Liquid Glass 控件建立白名单（最多 1~3 个/屏），例如只允许：主 CTA、悬浮输入栏、底部导航。
  - **禁止**：把 `.glassEffect()` 当作通用"好看背景"贴到列表行、卡片、头像、气泡上。

### 1.4 无障碍设置会降级/移除材质（逐字摘全）

- **原文**：

  > "**Test your interface with a variety of display and accessibility settings.** Translucency and fluid morphing animations contribute to the look and feel of Liquid Glass, but can adapt to people’s needs. For example, people can choose a preferred look for Liquid Glass in their device’s settings, or turn on accessibility settings that reduce transparency or motion in the interface. These settings can remove or modify certain effects. If you use standard components from system frameworks, this experience adapts automatically. Ensure you test your app’s custom elements, colors, and animations with different configurations of these settings."

- **要点**：关键动词是 **remove**——"These settings can **remove** or modify certain effects"。
  即开启"降低透明度/减弱动态效果"后，材质可能被**移除**（变成不透明），morph 动画可能被**去掉**。
  用系统组件自动适配；自定义元素必须自己测。

- **对本工程的约束**：
  - **必须**：自绘 Liquid Glass 在 `@Environment(\.accessibilityReduceTransparency)` /
    `\.accessibilityReduceMotion` 为 true 时，降级为**不透明纯色背景**，而不是继续画模糊层。
  - **必须**：功能不得依赖"材质可见"——删掉材质后控件仍可辨识、可点击、层级仍清晰。
  - **禁止**：把重要信息只放在"透过玻璃能看到的底层内容"上。

### 1.5 自定义 bar 如何注册 scroll edge effect（逐字摘全）

- **原文**：

  > "**Optimize for legibility when content scrolls beneath controls.** Scroll views offer a scroll edge effect that helps maintain sufficient legibility and contrast for controls by obscuring content that scrolls beneath them. System bars like toolbars adopt this behavior by default. If you use a custom bar with elements like controls, text, or icons that have content scrolling beneath them, you can register those views to use a scroll edge effect with these APIs:"

  Apple 给出的 API（SwiftUI / UIKit 两个 Tab）：

  - SwiftUI：`View.safeAreaBar(edge:alignment:spacing:content:)`
  - UIKit：`UIScrollEdgeElementContainerInteraction`

- **要点**：**注册 scroll edge effect 的官方入口就是 `safeAreaBar`**。
  注意关键词是 "custom bar"——只有你自建 bar（而不是用 `ToolbarItem`）时才需要它。

- **对本工程的约束**：
  - **必须**：自绘顶部/底部 bar（例如微信式输入栏、自研导航栏）使用
    `.safeAreaBar(edge: .bottom) { ... }` / `.safeAreaBar(edge: .top) { ... }` 注册，
    **不要**再用 `.safeAreaInset(edge:)` + 自绘模糊背景。
  - **禁止**：在自绘 bar 上再叠 `.background(.ultraThinMaterial)` 冒充边缘效果——系统已提供 scroll edge effect。

### 1.6 同心圆角（concentric corners）

- **原文**：

  > "**Consider aligning the shape of controls with other rounded elements throughout the interface.** Across Apple platforms, the shape of the hardware informs the curvature, size, and shape of nested interface elements, including controls, sheets, popovers, windows, and more. Help maintain a sense of visual continuity in your interface by using rounded shapes that are concentric to their containers using these APIs:"

  给出 API：SwiftUI `Shape.rect(corners:isUniform:)`、`ConcentricRectangle`；
  UIKit `UIView.cornerConfiguration`、`UICornerConfiguration`。

- **对本工程的约束**：
  - **必须**：嵌套容器的圆角使用 `ConcentricRectangle` 或 `.rect(corners:isUniform:)` 计算，
    **禁止**硬编码 `cornerRadius: 16` 之类数值去"凑"外层圆角。

### 1.7 用系统按钮样式代替自绘（逐字）

- **原文**：

  > "**Leverage new button styles.** Instead of creating buttons with custom Liquid Glass effects, you can adopt the look and feel of the material with minimal code by using one of the following button style APIs:"

  给出：`PrimitiveButtonStyle.glass`、`.glassProminent`、`.glass(_:)`；
  UIKit `UIButton.Configuration.glass()` / `prominentGlass()` / `clearGlass()` / `prominentClearGlass()`；
  AppKit `NSButton.BezelStyle.glass`。

- **对本工程的约束**：
  - **禁止**：手写 `Button { } label: { }.background(...).glassEffect()`。
  - **必须**：改用 `.buttonStyle(.glass)` / `.buttonStyle(.glassProminent)` / `.buttonStyle(.glass(_:))`。

### 1.8 其他仍与本工程相关的原文（略去平台无关段落）

- **原文**（控件颜色、间距）：

  > "**Review your use of color in controls.** Be judicious with your use of color in controls and navigation so they stay legible. If you do apply color to these elements, leverage system colors, or define a custom color with light and dark variants, and an increased contrast option for each variant."

  > "**Review updates to control appearance and dimensions.** If you use standard controls from system frameworks and don’t hard-code their layout metrics, your app adopts changes to shapes and sizes automatically when you rebuild your app with the latest version of Xcode."

- **原文**（性能，出现在 Platform considerations 段）：

  > "**Combine custom Liquid Glass effects to improve rendering performance.** If you apply these effects to custom elements, make sure to combine them using a GlassEffectContainer, which helps optimize performance while fluidly morphing Liquid Glass shapes into each other."

- **原文**（watchOS / tvOS 的差异，说明"不重建也拿到"的特例）：

  > "In watchOS, adopt standard button styles and toolbar APIs. Liquid Glass changes are minimal in watchOS, so they appear automatically when you open your app on the latest release even if you don’t build against the latest SDK. However, to make sure your app picks up this appearance, adopt standard toolbar APIs and button styles from watchOS 10."

  > "In tvOS, adopt standard focus APIs. Across apps and system experiences in tvOS, standard buttons and controls take on a Liquid Glass appearance when focus moves to them. … Apple TV 4K (2nd generation) and newer models support Liquid Glass effects. On older devices, your app maintains its current appearance."

- **原文**（列表/表单布局变化）：

  > "Sections have an increased corner radius to match the curvature of controls across the system."

  > "Check capitalization in section headers. Lists, tables, and forms optimize for legibility by adopting title-style capitalization for section headers. This means section headers no longer render entirely in capital letters regardless of the capitalization you provide."

- **对本工程的约束**：
  - **禁止**：硬编码控件尺寸/行高（`frame(height: 44)`、固定 row height）去匹配旧版 iOS 的外观。
  - **必须**：section header 用 Title Case，不再写全大写字符串。

---

## 2. Applying Liquid Glass to custom views（应用到自定义视图）

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/applying-liquid-glass-to-custom-views.json`
- **abstract**：`Configure, combine, and morph views using Liquid Glass effects.`

### 2.1 基本用法

- **原文**：

  > "Use the glassEffect(_:in:) modifier to add Liquid Glass effects to a view. By default, the modifier uses the regular variant of Glass and applies the given effect within a Capsule shape behind the view’s content."

  > "Use different shapes to have a consistent look and feel across custom components in your app. For example, use a rounded rectangle if you’re applying the effect to larger components that would look odd as a `Capsule` or Circle."

  > "Assign a tint color to suggest prominence."

  > "Add interactive(_:) to custom components to make them react to touch and pointer interactions. This applies the same responsive and fluid reactions that glass provides to standard buttons."

  Apple 官方示例（逐字）：

  ```swift
  Text("Hello, World!")
      .font(.title)
      .padding()
      .glassEffect()

  Text("Hello, World!")
      .font(.title)
      .padding()
      .glassEffect(in: .rect(cornerRadius: 16.0))

  Text("Hello, World!")
      .font(.title)
      .padding()
      .glassEffect(.regular.tint(.orange).interactive())
  ```

- **要点**：官方推荐的调用形式是 `.glassEffect(.regular.tint(.orange).interactive())` ——
  `Glass` 是**值类型配置对象**，通过链式调用组合变体、tint、interactive 三个维度。
  默认形态 = `.regular` + `Capsule`。

- **对本工程的约束**：
  - **必须**：`.glassEffect()` 的默认 `Capsule` 只在圆形/胶囊控件上使用；矩形卡片**必须**显式传 shape。
  - **必须**：`.glassEffect(_:in:)` 要放在**影响外观的其它 modifier 之后**（见 2.3 原文）。
  - **允许**：`.glassEffect(.regular.tint(...).interactive())` 作为交互式自定义控件的标准写法。

### 2.2 GlassEffectContainer：spacing 与 morph（逐字摘全）

- **原文**：

  > "Use GlassEffectContainer when applying Liquid Glass effects on multiple views to achieve the best rendering performance. A container also allows views with Liquid Glass effects to blend their shapes together and to morph in and out of each other during transitions. Inside a container, each view with the glassEffect(_:in:) modifier renders with the effects behind it."

  > "Customize the spacing on the container to control how the Liquid Glass effects behind views interact with one another. The larger the spacing value on the container, the sooner the Liquid Glass effects behind views blend together and merge the shapes during a transition. A spacing value on the container that’s larger than the spacing of an interior HStack, VStack, or other layout container causes Liquid Glass effects to blend together at rest because the views are too close to each other. Animating views in or out causes the shapes to morph apart or together as the space in the container changes."

  > "The `glassEffect(_:in:)` modifier captures the content to send to the container to render. Apply the `glassEffect(_:in:)` modifier after other modifiers that affect the appearance of the view."

  > "In some cases, you want the geometries of multiple views to contribute to a single Liquid Glass effect capsule, even when your content is at rest. Use the glassEffectUnion(id:namespace:) modifier to specify that a view contributes to a unified effect with a particular ID. This combines all effects with a similar shape, Liquid Glass effect, and ID into a single shape with the applied Liquid Glass material. This is especially useful when creating views dynamically, or with views that live outside of a layout container, like an `HStack` or `VStack`."

- **原文**（官方容器示例，spacing 40 / HStack spacing 40）：

  ```swift
  GlassEffectContainer(spacing: 40.0) {
      HStack(spacing: 40.0) {
          Image(systemName: "scribble.variable")
              .frame(width: 80.0, height: 80.0)
              .font(.system(size: 36))
              .glassEffect()

          Image(systemName: "eraser.fill")
              .frame(width: 80.0, height: 80.0)
              .font(.system(size: 36))
              .glassEffect()
              .offset(x: -40.0, y: 0.0)
      }
  }
  ```

- **要点（反直觉）**：**container 的 spacing 越大，越"早"融合**。
  spacing 是"融合触发距离"的阈值，不是"视觉间距"。若 `container.spacing > HStack.spacing`，
  控件在**静止状态**就会糊成一团（"blend together at rest"）。

- **对本工程的约束**：
  - **必须**：`GlassEffectContainer(spacing:)` 的值 **小于等于** 内部布局容器的 spacing，
    否则静止态即发生非预期融合。若要"静止即融合"才反过来设大。
  - **必须**：`.glassEffect()` 写在 `.frame()` / `.font()` / `.padding()` **之后**（它捕获内容）。
  - **禁止**：多个独立的 `.glassEffect()` 不包在同一个 `GlassEffectContainer` 里（性能）。
  - **允许**：`.glassEffectUnion(id:namespace:)` 用于动态生成、且不在同一 `HStack`/`VStack` 里的视图。

### 2.3 Morph / 转场（逐字摘全）

- **原文**：

  > "Morphing effects occur during transitions or animations between views with Liquid Glass effects. Coordinate transitions between views with effects in a container by using the glassEffectID(_:in:) modifier. GlassEffectTransition allows you to specify the type of transition to use when you want to add or remove effects within a container. For effects you want to add or remove that are positioned within the container’s assigned spacing, the default transition type is matchedGeometry."

  > "If you prefer to have a simpler transition or to create a custom transition, use the materialize transition and withAnimation(_:_:). Use the `materialize` transition for effects you want to add or remove that are farther from each other than the container’s assigned spacing. To provide people with a consistent experience, use `matchedGeometry` and `materialize` transitions across your apps. The system applies more than opacity changes with the available transition types."

  > "Associate each Liquid Glass effect with a unique identifier within a namespace that the Namespace property wrapper provides. These IDs ensure SwiftUI animates the same shapes correctly when a shape appears or disappears due to view hierarchy changes. SwiftUI uses the spacing provided to the effect container along with the geometry of the shapes themselves to determine when and which appropriate shapes to morph into and out of."

  > "The `glassEffectID(_:in:)` and `glassEffectTransition(_:)` modifiers only affect their content during view hierarchy transitions or animations."

  > "In the example below, the eraser image transitions into and out of the pencil image when the `isExpanded` variable changes. The `GlassEffectContainer` has a spacing value of `40.0`, and the `HStack` within it has a spacing of `40.0`. This morphs the eraser image into the pencil image when the eraser’s nearest edge is less than or equal to the container’s spacing."

- **要点（重要边界）**：`.glassEffectID` / `.glassEffectTransition` **只在视图层级发生插入/移除（转场或动画）时起作用**，
  静止状态改属性不会触发。morph 的触发条件是：**两个 shape 的最近边缘距离 ≤ container spacing**。

- **对本工程的约束**：
  - **必须**：想让两个玻璃控件互相 morph，必须 ①同一个 `GlassEffectContainer` 内；②各自有唯一
    `.glassEffectID(_:in: namespace)`；③动画变化用 `withAnimation { }` 驱动层级增删。
  - **必须**：距离 ≤ container spacing 用 `.matchedGeometry`；距离 > container spacing 用 `.materialize`。
  - **禁止**：把 `.glassEffectID` 当成普通 `id()` 用于 `ForEach` 去重（语义不同，仅影响 glass 转场）。

### 2.4 性能（逐字）

- **原文**：

  > "Creating too many Liquid Glass effect containers and applying too many effects to views outside of containers can degrade performance. Limit the use of Liquid Glass effects onscreen at the same time."

- **对本工程的约束**：
  - **必须**：一屏内 `GlassEffectContainer` 数量设上限（建议 1~2），且容器外的 `.glassEffect()` 数量为 0 或极少。
  - **必须**：本项目已有的 `HardwareInfo.tier` 降级策略在 iOS 端保留——低端设备关闭 glass，改纯色。

---

## 3. API 参考（abstract + discussion 正文）

### 3.1 `View.glassEffect(_:in:)`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/view/glasseffect(_:in:).json`
- **签名**：

  ```swift
  nonisolated func glassEffect(_ glass: Glass = .regular, in shape: some Shape = DefaultGlassEffectShape()) -> some View
  ```

- **abstract**：`Applies the Liquid Glass effect to a view.`
- **原文（Discussion）**：

  > "When you use this effect, the system:
  >   Renders a shape anchored behind a view with the Liquid Glass material.
  >   Applies the foreground effects of Liquid Glass over a view."

  > "SwiftUI uses the regular variant by default along with a Capsule shape."

  > "SwiftUI anchors the Liquid Glass to a view’s bounds. For the example above, the material fills the entirety of the `Text` frame, which includes the padding."

  > "You typically use this modifier with a GlassEffectContainer to combine multiple Liquid Glass shapes into a single shape that can morph into one another."

- **要点**：材质锚定在**视图 bounds** 上，包含 padding。所以 padding 加在哪一层会改变玻璃的形状大小。
- **对本工程的约束**：
  - **必须**：`.padding()` 先于 `.glassEffect()` 时，玻璃会覆盖 padding 区域；若要玻璃紧贴内容，`.glassEffect()` 放在 padding 之前。
  - **必须**：默认 shape 是 `Capsule`（不是圆角矩形），矩形 UI 必须显式传 shape。
- **可用性**：iOS 26.0 / iPadOS 26.0 / Mac Catalyst 26.0 / macOS 26.0 / tvOS 26.0 / watchOS 26.0

### 3.2 `GlassEffectContainer`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/glasseffectcontainer.json`
- **签名**：`@MainActor @preconcurrency struct GlassEffectContainer<Content> where Content : View`
- **abstract**：`A view that combines multiple Liquid Glass shapes into a single shape that can morph individual shapes into one another.`
- **原文（Overview）**：

  > "Use a container with the glassEffect(_:in:) modifier. Each view with a Liquid Glass effect contributes a shape rendered with the effect to a set of shapes. SwiftUI renders the effects together, improving rendering performance and allowing the effects to interact with and morph into one another."

  > "Configure how shapes interact with one another by customizing the default spacing value of the container. As shapes near one another, their paths start to blend into one another. The higher the spacing, the sooner blending begins as the shapes approach each other."

- **对本工程的约束**：**必须**要求所有自定义 glass 控件成组出现时共用一个 `GlassEffectContainer`。
- **可用性**：iOS 26.0 ……（同上六平台）

### 3.3 `View.glassEffectID(_:in:)`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/view/glasseffectid(_:in:).json`
- **签名**：`nonisolated func glassEffectID(_ id: (some Hashable & Sendable)?, in namespace: Namespace.ID) -> some View`
- **abstract**：`Associates an identity value to Liquid Glass effects defined within this view.`
- **原文（Discussion）**：

  > "You use this modifier with the glassEffect(_:in:) view modifier and a GlassEffectContainer view. When used together, SwiftUI uses the identifier to animate shapes to and from each other during transitions."

- **可用性**：iOS 26.0 ……（同上六平台）

### 3.4 `View.glassEffectUnion(id:namespace:)`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/view/glasseffectunion(id:namespace:).json`
- **签名**：`@MainActor @preconcurrency func glassEffectUnion(id: (some Hashable & Sendable)?, namespace: Namespace.ID) -> some View`
- **abstract**：`Associates any Liquid Glass effects defined within this view to a union with the provided identifier.`
- **原文（Discussion）**：

  > "You may want the geometries of multiple views to contribute to a single Liquid Glass effect shape. In these cases, you can use a glassEffectUnion(id:namespace:) to specify that a view should contribute to a union of Liquid Glass effects with a particular identifier. All Liquid Glass effects with the same shape and Liquid Glass variant will be combined into a single shape."

- **要点（限制条件）**：合并的前提是 **same shape + same Liquid Glass variant**。shape 或 variant 不同就不会合并。
- **可用性**：iOS 26.0 ……（同上六平台）

### 3.5 `View.glassEffectTransition(_:)`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/view/glasseffecttransition(_:).json`
- **签名**：`@MainActor @preconcurrency func glassEffectTransition(_ transition: GlassEffectTransition) -> some View`
- **abstract**：`Associates a glass effect transition with any glass effects defined within this view.`
- **原文（Discussion）**：

  > "You use this modifier with the glassEffect(_:in:) view modifier and GlassEffectContainer view. When used together, SwiftUI will use the provided transition to apply changes to the glass effect when you add or remove views with these effects from the view hierarchy."

- **可用性**：iOS 26.0 ……（同上六平台）

#### 3.5.1 `GlassEffectTransition` 类型

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/glasseffecttransition.json`
- **签名**：`struct GlassEffectTransition`
- **abstract**：`A structure that describes changes to apply when a glass effect is added or removed from the view hierarchy.`

#### 3.5.2 `GlassEffectTransition.matchedGeometry`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/glasseffecttransition/matchedgeometry.json`
- **签名**：`static var matchedGeometry: GlassEffectTransition { get }`
- **abstract**：`Returns the matched geometry glass effect transition.`
- **原文（Discussion，注意最后一句的坑）**：

  > "The matched geometry transition allows the geometries of glass shapes during an appearance or disappearance phase of a transition to be derived from the geometry of a nearby shape within the glass container."

  > "For example, if a newly appearing shape is within the spacing of any existing shape, it will use that shapes geometry to transition out of."

  > "When using the default, this transition applies additional scale and offset effects to content when the identity of the shape does not change but its content does. Opt out of these additional animations by providing a specific animation like spring."

- **对本工程的约束**：**必须**知道 `.matchedGeometry` 在"ID 不变但内容变了"时会**额外附加 scale + offset 动画**；
  不想要就显式指定动画（如 `spring`）。

#### 3.5.3 `GlassEffectTransition.materialize`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/glasseffecttransition/materialize.json`
- **签名**：`static var materialize: GlassEffectTransition { get }`
- **abstract（逐字，注意它本身就是完整说明）**：

  > "The materialize glass effect transition which will fade in content and animate in or out the glass material but will not attempt to match the geometry of any other glass effects."

- **要点**：`materialize` = 淡入内容 + 玻璃材料进出动画，**不**做几何匹配。

### 3.6 `Glass` 类型与变体/修饰

#### 3.6.1 `Glass`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/glass.json`
- **签名**：`struct Glass`
- **abstract**：`A structure that defines the configuration of the Liquid Glass material.`
- **原文（Overview）**：

  > "You provide instances of a variant of Liquid Glass to the glassEffect(_:in:) view modifier"

  > "You can combine Liquid Glass effects using a GlassEffectContainer, which supports morphing views with this effect into each other based on the geometry of their associated views."

- **可用性**：iOS 26.0 / iPadOS 26.0 / Mac Catalyst 26.0 / macOS 26.0 / tvOS 26.0 / watchOS 26.0

#### 3.6.2 `Glass.regular`（静态属性）

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/glass/regular.json`
- **签名**：`static var regular: Glass { get }`
- **abstract**：`The regular variant of the Liquid Glass material.`
- **可用性**：iOS 26.0 ……（同上六平台）

#### 3.6.3 `Glass.clear`（静态属性，任务清单外但抓到了，**很重要**）

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/glass/clear.json`
- **签名**：`static var clear: Glass { get }`
- **abstract**：`The clear variant of glass.`
- **原文（Discussion）**：

  > "When using clear glass, ensure content remains legible by adding a dimming layer or other treatment beneath the glass."

  > "For example, you could add a transparent black color beneath your glass to ensure content remains legible above the glass."

  ```swift
  Label("Flag", systemImage: "flag.fill")
      .padding()
      .glassEffect(.clear)
      .background(.black.opacity(0.3))
  ```

- **要点（反直觉）**：`.clear` 是**危险变体**——官方要求你必须自己加一层 dimming（示例为 `.black.opacity(0.3)`）。
  注意示例顺序：`.glassEffect(.clear)` **在前**，`.background(.black.opacity(0.3))` **在后**。
- **对本工程的约束**：
  - **禁止**：在文本密集的 UI（聊天列表、设置页）使用 `.glassEffect(.clear)`。
  - **允许**：`.clear` 仅用于覆盖在照片/视频等视觉丰富背景上的悬浮控件，且必须自加变暗层。
- **可用性**：iOS 26.0 ……（同上六平台）

#### 3.6.4 `Glass.tint(_:)` —— **注意：不是静态属性**

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/glass/tint(_:).json`
- **签名**：`func tint(_ color: Color?) -> Glass`
- **abstract**：`Returns a copy of the structure with a configured tint color.`
- **可用性**：iOS 26.0 / iPadOS 26.0 / Mac Catalyst 26.0 / macOS 26.0 / tvOS 26.0 / watchOS 26.0
- **未抓到**：`Glass.tint`（无参数静态属性）→ **HTTP 404**，见第 8 节。
- **要点**：`.tint` 是**实例方法**，参数是 `Color?`（可选，传 `nil` 可清除 tint）。
  正确写法是 `.glassEffect(.regular.tint(.orange))`，**不是** `.glassEffect(.regular.tint)`。

### 3.7 `.interactive()` 的使用条件（逐字，须诚实标注边界）

#### 3.7.1 `Glass.interactive(_:)`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/glass/interactive(_:).json`
- **签名**：`func interactive(_ isEnabled: Bool = true) -> Glass`
- **abstract**：`Returns a copy of the structure configured to be interactive.`
- **可用性**：iOS 26.0 / iPadOS 26.0 / Mac Catalyst 26.0 / macOS 26.0 / tvOS 26.0 / watchOS 26.0

#### 3.7.2 关于 `.interactive()` 的**全部**官方表述（已穷尽本文档集）

- Applying Liquid Glass to custom views：

  > "Add interactive(_:) to custom components to make them react to touch and pointer interactions. This applies the same responsive and fluid reactions that glass provides to standard buttons."

- HIG – Materials（关于"内容层"里的例外）：

  > "An exception to this is for controls in the content layer with a transient interactive element like sliders and toggles; in these cases, the element takes on a Liquid Glass appearance to emphasize its interactivity when a person activates it."

- **诚实标注**：Apple **没有**在这几篇文档中写出 `.interactive()` 的硬性前置条件（例如"必须可点击"之类）。
  能确证的只有：它让自定义组件获得"与系统按钮相同的响应式/流体反馈"，
  以及材质在交互**激活瞬间**才出现是被允许的例外。
  因此下面的约束是从"少用/内容层禁用"推导出的工程规则，**不是** Apple 原话。

- **对本工程的约束（推导，非原文）**：
  - **必须**：`.interactive()` 只加在**真的能响应触摸/指针**的自定义控件上。
  - **禁止**：给静态容器、卡片、分隔条加 `.interactive()`。
  - **允许**：滑块/开关这类"瞬时交互元素"在拖动时呈现玻璃外观。

### 3.8 按钮样式

#### 3.8.1 `PrimitiveButtonStyle.glass`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/primitivebuttonstyle/glass.json`
- **签名**：`@export(implementation) nonisolated static var glass: GlassButtonStyle { get }`
- **abstract**：`A button style that applies a Liquid Glass effect based on the button’s context.`
- **原文（Discussion）**：

  > "In tvOS, this button style applies a Liquid Glass effect regardless of whether the button has focus."

  > "To apply this style to a button, or to a view that contains buttons, use the buttonStyle(_:) modifier."

- **可用性**：iOS 26.0 ……（同上六平台）

#### 3.8.2 `PrimitiveButtonStyle.glassProminent`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/primitivebuttonstyle/glassprominent.json`
- **签名**：`@MainActor @export(implementation) @preconcurrency static var glassProminent: GlassProminentButtonStyle { get }`
- **abstract**：`A button style that applies a prominent Liquid Glass effect based on the button’s context.`
- **原文（Discussion）**：

  > "In tvOS, this button style applies a Liquid Glass effect regardless of whether the button has focus. This style is similar to the borderedProminent style."

- **要点**：`glassProminent` ≈ 旧的 `borderedProminent`；结合 HIG 的"主行动把颜色放在**背景**上"，
  这就是"带 accent 色的主 CTA"的官方实现。
- **可用性**：iOS 26.0 ……（同上六平台）

#### 3.8.3 `PrimitiveButtonStyle.glass(_:)`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/primitivebuttonstyle/glass(_:).json`
- **签名**：`nonisolated static func glass(_ glass: Glass) -> Self`
- **abstract**：`A button style that applies a configurable Liquid Glass effect based on the button’s context.`
- **原文（Discussion）**：

  > "This button style applies a Liquid Glass effect that you can customize by specifying a tint or variant. In the following example, the button renders using the clear variant of Liquid Glass:"

  ```swift
  Button("Button") {}
      .buttonStyle(.glass(.clear))
  ```

  > "In tvOS, this button style applies a Liquid Glass effect regardless of whether the button has focus. This style is similar to the bordered style."

- **要点**：`.glass(_:)` 接收 `Glass` 值，所以 `.buttonStyle(.glass(.regular.tint(.orange)))` 是合法的官方用法。
- **可用性**：iOS 26.0 / iPadOS 26.0 / Mac Catalyst 26.0 / macOS 26.0 / tvOS 26.0 / watchOS 26.0

### 3.9 `View.safeAreaBar(edge:alignment:spacing:content:)`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/view/safeareabar(edge:alignment:spacing:content:).json`
- **签名**：

  ```swift
  nonisolated func safeAreaBar(edge: HorizontalEdge, alignment: VerticalAlignment = .center, spacing: CGFloat? = nil, @ContentBuilder content: () -> some View) -> some View
  ```

- **abstract**：`Shows the specified content as a custom bar beside the modified view.`
- **参数（逐字）**：
  - `edge` — "The horizontal edge of the view on which `content` is placed."
  - `alignment` — "The alignment guide used to position `content` vertically."
  - `spacing` — "Extra distance placed between the two views, or nil to use the default amount of spacing."
  - `content` — "A content builder function providing the view to display as a custom bar."
- **原文（Return Value）**：

  > "A new view that displays `content` beside the modified view, making space for the `content` view by horizontally insetting the modified view, adjusting the safe area and scroll edge effects to match."

- **原文（Discussion）——**这条最关键**：

  > "Similar to the safeAreaInset(edge:alignment:spacing:content:) modifier, the `content` view is anchored to the specified horizontal edge of the parent view and its width insets the safe area. Additionally, it extends the edge effect of any scroll views affected by the inset safe area."

- **要点（反直觉）**：`safeAreaBar` 相对 `safeAreaInset` 的**唯一增量**就是那句 "Additionally, it extends the edge effect of any scroll views affected by the inset safe area."
  也就是说：自绘 bar 必须用 `safeAreaBar`，用 `safeAreaInset` 会**没有** scroll edge effect。
- **对本工程的约束**：
  - **必须**：所有自绘 bar（自定义导航栏、悬浮输入栏、底部 tab）用 `.safeAreaBar(edge:)`。
  - **禁止**：用 `.safeAreaInset(edge:)` 实现 bar 后再手动补模糊背景。
  - **注意**：`edge` 类型是 `HorizontalEdge`（`.top` / `.bottom`），不是 `VerticalEdge`。
- **可用性**：iOS 26.0 / iPadOS 26.0 / Mac Catalyst 26.0 / macOS 26.0 / tvOS 26.0 / **visionOS 26.0** / watchOS 26.0

### 3.10 同心圆角 API

#### 3.10.1 `ConcentricRectangle`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/concentricrectangle.json`
- **签名**：`struct ConcentricRectangle`
- **abstract**：`A shape whose corners you configure, individually or uniformly, to be squared, rounded, or concentric relative to a container shape’s corners.`
- **原文（Overview，摘关键句）**：

  > "Use `ConcentricRectangle` to create a rectangular shape that fits inside a container’s shape, similar to the way that a sheet’s corners in iOS match the curvature of the screen. System-provided elements like sheets and popovers do this automatically."

  > "A rounded corner of a rectangle is concentric relative to the container shape’s adjacent corner when the corner’s radius shares a common center with the containing shape’s rounded corner radius. A containing shape could be a view that extends to the device’s rounded corners, or any view that sets containerShape(_:). `ConcentricRectangle` automatically calculates each corner’s radius relative to the container shape, so your view adapts correctly across devices and sizes without hard-coded values."

  > "When your `ConcentricRectangle`‘s corners are far away from the containing shape’s corners, such as the top corners in this example, the corner radius the system calculates may be zero. When that happens, the corner is square. It’s also possible that your app is running on a device whose corners are square. To ensure that your view always has rounded corners that are concentric relative to the container shape when they can be, use concentric(minimum:) to specify a rounded corner with a minimum radius."

  > "SwiftUI provides container shapes by default in system-provided views. To allow `ConcentricRectangle` to resolve corner radii based on concentricity in your custom view, use containerShape(_:) to specify a container shape that implements RoundedRectangularShape, such as Circle, Rectangle, RoundedRectangle, or Capsule. When the container shape does not conform to RoundedRectangularShape, `ConcentricRectangle` provides an inset version of the container shape like ContainerRelativeShape."

  > "The functions with uniform corner styles calculate each uniform corner’s radius first, then use the largest radius for each uniform corner"

- **要点（两个坑）**：①远离容器角的角，半径**会被算成 0**（即变直角）——需要 `.concentric(minimum:)` 兜底；
  ②自定义视图里必须显式 `.containerShape(...)` 才能解析同心，否则退回 `ContainerRelativeShape` 式的 inset 形状。
- **对本工程的约束**：
  - **必须**：外层容器调用 `.containerShape(RoundedRectangle(cornerRadius: n))`，内层才用 `ConcentricRectangle`。
  - **必须**：可能远离容器角的角使用 `concentric(minimum:)`，避免出现直角。
  - **禁止**：用 `ContainerRelativeShape` 冒充同心圆角（Apple 指明那是"容器形状不满足 `RoundedRectangularShape` 时的退路"）。
- **可用性**：iOS 26.0 / iPadOS 26.0 / Mac Catalyst 26.0 / macOS 26.0 / tvOS 26.0 / **visionOS 26.0** / watchOS 26.0

#### 3.10.2 `Shape.rect(corners:isUniform:)`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/shape/rect(corners:isuniform:).json`
- **签名**：`@export(implementation) static func rect(corners: Edge.Corner.Style, isUniform: Bool = false) -> Self`
- **abstract**：`Creates a rectangle with the same corner style set on four corners.`
- **参数**：
  - `corners` — "The corner style for all four corners."
  - `isUniform` — "A Boolean value that indicates whether to apply the corner style on each corner individually or uniformly."
- **原文（Discussion）**：

  > "When you provide `false` for `isUniform`, the system may calculate a different radius for each corner. This can happen when the rectangle is not centered within the container shape, or the container shape’s corners have different radii. When you provide `true` for `isUniform`, the system calculates the radius for each corner first. Then, it selects the largest radius and applies it to each corner to achieve the symmetric look."

- **要点（反直觉）**：默认 `isUniform: false` 意味着**四个角半径可能不同**（这是设计如此，不是 bug）。
  要对称必须显式传 `true`。
- **对本工程的约束**：
  - **必须**：需要视觉对称的卡片/面板使用 `.rect(corners: ..., isUniform: true)`。
  - **允许**：贴合屏幕边缘、故意追求"非居中同心"的场景保持 `false`。
- **可用性**：iOS 26.0 ……（含 visionOS 26.0，共七平台）

#### 3.10.3 `Edge.Corner.Style`（配套类型）

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/edge/corner/style.json`
- **签名**：`struct Style`
- **abstract**：`A style that describes the corner of a rectangular shape.`
- **原文（Overview）**：

  > "A corner can be square, rounded with a fixed-radius curve, or rounded with a curve that’s concentric to the container shape."

- **可用性**：iOS 26.0 ……（含 visionOS 26.0，共七平台）

#### 3.10.4 `View.containerShape(_:)`（配套）

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/view/containershape(_:).json`
- **签名**：`nonisolated func containerShape(_ shape: some RoundedRectangularShape) -> some View`
- **abstract**：`Sets the container shape to use for any container relative shape or concentric rectangle within this view.`
- **原文（Discussion）**：

  > "Any ContainerRelativeShape within the `content` matches the rounded rectangle shape from this container inset as appropriate. Any ConcentricRectangle within the `content` will match the corners to be concentric to the container corners."

- **可用性**：iOS 26.0 ……（含 visionOS 26.0，共七平台）

### 3.11 `View.scrollEdgeEffectStyle(_:for:)` 系列

#### 3.11.1 `scrollEdgeEffectStyle(_:for:)`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/view/scrolledgeeffectstyle(_:for:).json`
- **签名**：`nonisolated func scrollEdgeEffectStyle(_ style: ScrollEdgeEffectStyle?, for edges: Edge.Set) -> some View`
- **abstract**：`Configures the scroll edge effect style for scroll views within this hierarchy.`
- **原文（Discussion）**：

  > "By default, a scroll view renders an automatic edge effect. Use this modifier to change the scroll edge effect style."

  ```swift
  ScrollView {
      LazyVStack {
          ForEach(data) { item in
              RowView(item)
          }
      }
  }
  .scrollEdgeEffectStyle(.hard, for: .all)
  ```

- **可用性**：iOS 26.0 / iPadOS 26.0 / Mac Catalyst 26.0 / macOS 26.0 / tvOS 26.0 / watchOS 26.0

#### 3.11.2 `ScrollEdgeEffectStyle`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/scrolledgeeffectstyle.json`
- **签名**：`struct ScrollEdgeEffectStyle`
- **abstract**：`A structure that specifies blur transitions between scrolling content and an area with controls, such as toolbars.`
- **原文（Overview）**：

  > "By default, the system sets an automatic scroll edge effect style to provide a visual transition between scrolling content and stationary controls at both edges of the scroll view in the scrolling direction. The system determines which style to apply based on the platform and context. The hard style provides a more opaque, clearly defined linear boundary, and the soft style provides a subtle blurred transition"

  > "Specify a `ScrollEdgeEffectStyle` for a scroll view using scrollEdgeEffectStyle(_:for:) when the automatic style the system applies isn’t appropriate for your content and controls. Apply scrollEdgeEffectHidden(_:for:) to a scroll view to remove the scroll edge effect entirely for an edge you specify."

- **要点（反直觉）**：`.hard` 是"更不透明、边界更硬"，`.soft` 是"微弱模糊"——
  名字直觉容易反（有人以为 hard = 强模糊）。且默认是 `.automatic`（系统按平台/上下文自选），
  **不应该**无条件覆盖。
- **对本工程的约束**：
  - **必须**：保持默认 `.automatic`；只有确认系统选择不合适时才覆盖。
  - **允许**：`.scrollEdgeEffectHidden(_:for:)` 彻底移除某条边的效果。
- **可用性**：iOS 26.0 ……（含 visionOS 26.0，共七平台）

#### 3.11.3 `ScrollEdgeEffectStyle.hard` / `.soft` / `.automatic`

- **来源**：
  - `https://developer.apple.com/tutorials/data/documentation/swiftui/scrolledgeeffectstyle/hard.json`
  - `https://developer.apple.com/tutorials/data/documentation/swiftui/scrolledgeeffectstyle/soft.json`
  - `https://developer.apple.com/tutorials/data/documentation/swiftui/scrolledgeeffectstyle/automatic.json`
- **签名**：`static var hard: ScrollEdgeEffectStyle { get }` / `static var soft: ...` / `static var automatic: ...`
- **abstract（逐字）**：
  - `.hard` — "A scroll edge effect that provides a linear, nearly opaque boundary between pinned controls and scrolling content."
  - `.soft` — "A scroll edge effect that provides a subtle, blurred boundary between pinned controls and scrolling content."
  - `.automatic` — "A scroll edge effect the system applies automatically when pinned content overlaps scrolling content."
- **可用性**：三者均为 iOS 26.0 ……（含 visionOS 26.0，共七平台）

#### 3.11.4 `View.scrollEdgeEffectHidden(_:for:)`

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/view/scrolledgeeffecthidden(_:for:).json`
- **签名**：`nonisolated func scrollEdgeEffectHidden(_ hidden: Bool = true, for edges: Edge.Set = .all) -> some View`
- **abstract**：`Hides any scroll edge effects for scroll views within this hierarchy.`
- **原文（Discussion）**：`"By default, a scroll view renders an automatic edge effect style. Use this modifier to hide any edge effects for scroll views within this hierarchy."`
- **可用性**：iOS 26.0 / iPadOS 26.0 / Mac Catalyst 26.0 / macOS 26.0 / tvOS 26.0 / watchOS 26.0

---

## 4. `Glass` 系列的完整可用成员（本次实测结论）

| 写法 | 是否存在 | 形态 | 实测来源 |
|---|---|---|---|
| `Glass.regular` | ✅ | `static var regular: Glass { get }` | `swiftui/glass/regular.json` |
| `Glass.clear` | ✅ | `static var clear: Glass { get }` | `swiftui/glass/clear.json` |
| `Glass.tint` | ❌ **404** | 无此静态属性 | `swiftui/glass/tint.json` → HTTP 404 |
| `Glass.tint(_:)` | ✅ | `func tint(_ color: Color?) -> Glass` | `swiftui/glass/tint(_:).json` |
| `Glass.interactive()` | ✅ | `func interactive(_ isEnabled: Bool = true) -> Glass` | `swiftui/glass/interactive(_:).json` |
| `Glass.interactive` | ❌ **404** | 无此静态属性 | `swiftui/glass/interactive.json` → HTTP 404 |

**合法的官方调用链（由上述签名拼合，且与应用指南示例一致）**：

```swift
.glassEffect(.regular.tint(.orange).interactive())
```

---

## 5. 工程约束：`UIDesignRequiresCompatibility`（**本工程最可能卡住的地方**）

- **来源**：`https://developer.apple.com/tutorials/data/documentation/bundleresources/information-property-list/uidesignrequirescompatibility.json`
- **abstract**：`A Boolean value that indicates whether the system runs the app using a compatibility mode for UI.`
- **类型**：`boolean`（Info.plist key 名：`UIDesignRequiresCompatibility`）
- **原文（Discussion）——全文逐字**：

  > **Warning**： "Temporarily use this key while reviewing and refining your app’s UI for the design in the latest SDKs."

  > "If `YES`, the system runs the app using a compatibility mode for UI elements. The compatibility mode displays the app as it looks when built against previous versions of the SDKs."

  > "If `NO`, the system uses the UI design of the running OS, with no compatibility mode. Absence of the key, or `NO`, is the default value for apps linking against the latest SDKs."

  > "The system ignores this key when you build for iOS 27 or later, iPadOS 27 or later, Mac Catalyst 27 or later, macOS 27 or later, or tvOS 27 or later."

- **默认值（逐字）**：`"Absence of the key, or `NO`, is the default value for apps linking against the latest SDKs."`
  → 即：**用最新 SDK 链接的 App，不写这个键 = 默认拿到新外观（无兼容模式）**。
- **Apple 明确写的"何时该用"（逐字）**：
  1. warning 句：`"Temporarily use this key while reviewing and refining your app’s UI for the design in the latest SDKs."`
  2. adopting-liquid-glass 末段：`"To update and ship your app with the latest SDKs while keeping your app as it looks when built against previous versions of the SDKs, you can add the UIDesignRequiresCompatibility key to your project’s Info pane."`
- **何时失效（逐字）**：iOS 27 / iPadOS 27 / Mac Catalyst 27 / macOS 27 / tvOS 27 或更新版本构建时，**系统忽略该键**。
- **可用性标注**：iOS 26.0 / iPadOS 26.0 / macOS 26.0 / tvOS 26.0（此页未列 Mac Catalyst / watchOS / visionOS）

### 5.1 ⚠️ 关于"何时**自动**获得新外观"——逐字证据与**证据缺口**

**Apple 逐字写出的部分：**

1. 两个条件（最新 SDK + 最新系统）：
   > "If your app uses standard components from SwiftUI, UIKit, or AppKit, your interface picks up the latest look and feel on the latest platform releases for iOS, iPadOS, macOS, tvOS, and watchOS. In Xcode, build your app with the latest SDKs, and run it on the latest platform releases to see the changes in your interface."
2. 默认值只对"最新 SDK"作了陈述：
   > "Absence of the key, or `NO`, is the default value for apps linking against the latest SDKs."
3. watchOS 是明确"不重建也给"的例外：
   > "Liquid Glass changes are minimal in watchOS, so they appear automatically when you open your app on the latest release even if you don’t build against the latest SDK."
4. 硬件门槛（tvOS）：
   > "Apple TV 4K (2nd generation) and newer models support Liquid Glass effects. On older devices, your app maintains its current appearance."

**证据缺口（必须诚实标注，不能替 Apple 说话）：**

- 本次抓取到的文档中，**没有任何一句**逐字说明"用**旧** SDK 链接的 App 在 iOS 26 上是否默认获得新外观"。
  `UIDesignRequiresCompatibility` 页对默认值的陈述**限定于** `for apps linking against the latest SDKs`。
- 常见的"只要用最新 SDK 构建就自动获得"这句话，Apple 的措辞是"build your app with the latest SDKs, **and run it on the latest platform releases**"——
  是**两个条件的合取**，不是单一条件。
- **因此**：不要在本工程规范里写"用旧 SDK 一定不会变色"或"用新 SDK 一定会变色"这类断言。
  可验收的写法只能是第 5.2 节的"实测/校验"规则。

### 5.2 对本工程的约束（可验收）

- **必须**：在 iOS 工程中显式检查 `Info.plist` **不含** `UIDesignRequiresCompatibility`（或显式 `NO`），
  以保证用最新 SDK 构建时处于新外观。
- **必须**：CI 或人工验收项加入一条二值检查：
  `plutil -extract UIDesignRequiresCompatibility raw Info.plist` 返回空 / `false`。
- **禁止**：把 `UIDesignRequiresCompatibility = YES` 提交进 release 分支。
  Apple 用 **Warning** 语气称其为临时手段，且在 **iOS 27+ 被系统忽略**——
  把它当长期开关会导致"iOS 26 上还是旧样子、iOS 27 上突然全变"的双重不一致。
- **必须**：既然本工程目标含 iOS 26/27，必须**同时**验证 iOS 26 与 iOS 27 下的实际外观，
  因为该键在 27 上不生效。
- **允许**：仅在本地调试/截图对比时临时置 `YES`，且不得进入版本控制。

---

## 6. 补充：HIG – Materials（材质选型，"不要用在内容层"）

- **来源**：`https://developer.apple.com/tutorials/data/design/human-interface-guidelines/materials.json`
- **abstract**：`A material is a visual effect that creates a sense of depth, layering, and hierarchy between foreground and background elements.`
- **原文（Liquid Glass 段，逐字）**：

  > "Liquid Glass forms a distinct functional layer for controls and navigation elements — like tab bars and sidebars — that floats above the content layer, establishing a clear visual hierarchy between functional elements and content."

  > "**Don’t use Liquid Glass in the content layer.** Liquid Glass works best when it provides a clear distinction between interactive elements and content, and including it in the content layer can result in unnecessary complexity and a confusing visual hierarchy. Instead, use standard materials for elements in the content layer, such as app backgrounds. An exception to this is for controls in the content layer with a transient interactive element like sliders and toggles; in these cases, the element takes on a Liquid Glass appearance to emphasize its interactivity when a person activates it."

  > "**Use Liquid Glass effects sparingly.** Standard components from system frameworks pick up the appearance and behavior of this material automatically. If you apply Liquid Glass effects to a custom control, do so sparingly. Liquid Glass seeks to bring attention to the underlying content, and overusing this material in multiple custom controls can provide a subpar user experience by distracting from that content. Limit these effects to the most important functional elements in your app."

  > "**Only use clear Liquid Glass for components that appear over visually rich backgrounds.** Liquid Glass provides two variants — regular and clear — that you can choose when building custom components or styling some system components. The appearance of these variants can differ in response to certain system settings, like if people choose a preferred look for Liquid Glass in their device’s settings, or turn on accessibility settings that reduce transparency or increase contrast in the interface."

  > "The regular variant blurs and adjusts the luminosity of background content to maintain legibility of text and other foreground elements. Scroll edge effects further enhance legibility by blurring and reducing the opacity of background content. Most system components use this variant. Use the regular variant when background content might create legibility issues, or when components have a significant amount of text, such as alerts, sidebars, or popovers."

  > "The clear variant is highly translucent, which is ideal for prioritizing the visibility of the underlying content and ensuring visually rich background elements remain prominent. Use this variant for components that float above media backgrounds — such as photos and videos — to create a more immersive content experience."

  > "For optimal contrast and legibility, determine whether to add a dimming layer behind components with clear Liquid Glass:
  >   - If the underlying content is bright, consider adding a dark dimming layer of 35% opacity.
  >   - If the underlying content is sufficiently dark, or if you use standard media playback controls from AVKit that provide their own dimming layer, you don’t need to apply a dimming layer."

  > "Apple platforms feature two types of materials: Liquid Glass, and standard materials. Liquid Glass is a dynamic material that unifies the design language across Apple platforms, allowing you to present controls and navigation without obscuring underlying content. In contrast to Liquid Glass, the standard materials help with visual differentiation within the content layer."

  > "**Choose materials and effects based on semantic meaning and recommended usage.** Avoid selecting a material or effect based on the apparent color it imparts to your interface, because system settings can change its appearance and behavior."

- **要点（极其反直觉，本工程高风险）**：
  - **禁止在 content layer 用 Liquid Glass** —— 而"聊天消息列表/卡片"正是 content layer。
    本工程大量"液体玻璃卡片"可能就是违规项。
  - `.clear` 的官方 dimming 数值是 **35% opacity 的暗色层**（bright 背景时）。
  - 无障碍设置（Reduced Transparency / Increase Contrast）会**改变 variant 的外观**，
    所以不能把 variant 的外观当成固定视觉规范。

- **对本工程的约束**：
  - **禁止**：在聊天列表、消息气泡、卡片、设置行等内容层元素上使用 Liquid Glass。
  - **必须**：内容层改用 `Material`（`.ultraThinMaterial` / `.thinMaterial` / `.regularMaterial` / `.thickMaterial`）。
  - **必须**：`.clear` 变体必须配 dimming 层；bright 背景取 ~35% 暗色不透明度。
  - **必须**：材质选择按**语义**（层级/用途）而非"看起来什么颜色"，否则系统设置变化会破坏视觉。

- **原文（watchOS）**：

  > "Use materials to provide context in a full-screen modal view. Because full-screen modal views are common in watchOS, the contrast provided by material layers can help orient people in your app and distinguish controls and system elements from other content. Avoid removing or replacing material backgrounds for modal sheets when they’re provided by default."

---

## 7. 补充：HIG – Color 的 "Liquid Glass color" 段（`.tint` 的正确用法与强度）

- **来源**：`https://developer.apple.com/tutorials/data/design/human-interface-guidelines/color.json`
- **原文（逐字，整段）**：

  > "By default, Liquid Glass has no inherent color, and instead takes on colors from the content directly behind it. You can apply color to some Liquid Glass elements, giving them the appearance of colored or stained glass. This is useful for drawing emphasis to a specific control, like a primary call to action, and is the approach the system uses for prominent button styling. Symbols or text labels on Liquid Glass controls can also have color."

  > "For smaller elements like toolbars and tab bars, the system can adapt Liquid Glass between a light and dark appearance in response to the underlying content. By default, symbols and text on these elements follow a monochromatic color scheme, becoming darker when the underlying content is light, and lighter when it’s dark. Liquid Glass appears more opaque in larger elements like sidebars to preserve legibility over complex backgrounds and accommodate richer content on the material’s surface."

  > "**Apply color sparingly to the Liquid Glass material, and to symbols or text on the material.** If you apply color, reserve it for elements that truly benefit from emphasis, such as status indicators or primary actions. To emphasize primary actions, apply color to the background rather than to symbols or text. For example, the system applies the app accent color to the background in prominent buttons — such as the Done button — to draw attention and elevate their visual prominence. Refrain from adding color to the background of multiple controls."

  > "**Avoid using similar colors in control labels if your app has a colorful background.** While color can make apps more visually appealing, playful, or reflective of your brand, too much color can be overwhelming and make control labels more difficult to read. If your app features colorful backgrounds or visually rich content, prefer a monochromatic appearance for toolbars and tab bars, or choose an accent color with sufficient visual differentiation."

  > "**Be aware of the placement of color in the content layer.** Make sure your interface maintains sufficient contrast by avoiding overlap of similar colors in the content layer and controls when possible. Although colorful content might intermittently scroll underneath controls, make sure its default or resting state — like the top of a screen of scrollable content — maintains clear legibility."

  （另有一句关于可变色/明暗变体，与控件颜色相关）：

  > "Even if your app ships in a single appearance mode, provide both light and dark colors to support Liquid Glass adaptivity in these contexts."

- **要点（"强度"结论）**：Apple **没有给出 tint 的数值/透明度规范**。
  它给的规则是**位置与数量**规则，而非数值规则：
  - 默认无固有颜色（"has no inherent color"）；
  - 颜色只用于"真正需要强调"的元素（状态指示、主行动）；
  - **强调主行动时把颜色放在 background，而不是 symbol/text 上**；
  - **不要给多个控件的背景加颜色**（"Refrain from adding color to the background of multiple controls."）。
- **对本工程的约束**：
  - **必须**：`.tint(...)` 用于 `.buttonStyle(.glassProminent)` 之类的单个主 CTA，
    或用于"状态指示"（如在线/离线点）。
  - **禁止**：给一组并列控件（如底部 tab 的每一项、工具栏每个按钮）的玻璃背景加 tint。
  - **禁止**：在内容层与其上的控件中使用相近色导致对比不足。
  - **必须**：自定义颜色同时提供 light / dark 两个变体 + 每个变体的 Increased Contrast 版本。

---

## 8. 未能抓取

以下 URL 实测返回 **HTTP 404**（尝试时间：本次会话，已重试不同大小写与参数形式）：

| 期望文档 | 尝试过的 JSON URL | 结果 |
|---|---|---|
| `Glass.tint`（无参静态属性） | `https://developer.apple.com/tutorials/data/documentation/swiftui/glass/tint.json` | HTTP 404 ×2（另在批次 1、批次 3 各试一次） |
| `Glass.interactive`（无参静态属性） | `https://developer.apple.com/tutorials/data/documentation/swiftui/glass/interactive.json` | HTTP 404 |
| `Glass.interactive()`（带空括号写法） | `https://developer.apple.com/tutorials/data/documentation/swiftui/glass/interactive().json` | HTTP 404 |
| HIG "Liquid Glass color"（独立页面） | `https://developer.apple.com/tutorials/data/design/human-interface-guidelines/liquid-glass-color.json` | HTTP 404 |
| HIG "Liquid Glass"（独立页面） | `https://developer.apple.com/tutorials/data/design/human-interface-guidelines/liquid-glass.json` | HTTP 404 |
| TechnologyOverviews "liquid-glass-color" | `https://developer.apple.com/tutorials/data/documentation/technologyoverviews/liquid-glass-color.json` | HTTP 404 |

**替代与结论**：
- `Glass.tint` / `Glass.interactive` 的 404 **不是抓取失败，而是这两个成员本身不存在**——
  它们是**方法** `tint(_:)` / `interactive(_:)`，已成功抓到（见第 3.6.4、3.7.1 节）。
  这本身就是一条需要写进规范的结论：**不许写 `.regular.tint` 或 `.regular.interactive`，必须带括号**。
- "Liquid Glass color" / "Liquid Glass" 独立 HIG 页不存在，相关内容实际位于
  `design/human-interface-guidelines/color`（"Liquid Glass color" 章节）与
  `design/human-interface-guidelines/materials`（"Liquid Glass" 章节），已抓取并摘录（第 6、7 节）。

**另有 2 项未纳入摘录范围**（超出任务范围，未抓取，故不做任何断言）：
- `documentation/swiftui/glasseffecttransition/identity`、`documentation/swiftui/glass/identity`
  已抓到但内容极简（仅 abstract + 签名），本文件未展开。
- UIKit 侧 API（`UIGlassEffect`、`UIScrollEdgeElementContainerInteraction`、`UICornerConfiguration`、
  `UIButton.Configuration.glass()` 等）本次**未抓取**，仅在 adopting 指南的引用表里出现；
  本文件对其不做任何原文引用。

**本次实际成功抓取：29 个 JSON 文档**（含 2 篇指南、19 个 SwiftUI 符号、1 个 Info.plist 键、
3 个 HIG 页面、1 个技术总览页、3 个配套类型）。

---

## 9. 一页速查：本工程必须遵守的硬规则

| # | 规则 | 类型 | 依据 |
|---|---|---|---|
| 1 | 全部 Liquid Glass API 需 `iOS 26.0+` 门禁 | 必须 | 第 0 节（全部 availability） |
| 2 | `Info.plist` 不得含 `UIDesignRequiresCompatibility = YES` | 禁止 | §5 |
| 3 | 内容层（列表/卡片/气泡/设置行）禁止 Liquid Glass | 禁止 | §6 HIG Materials |
| 4 | 导航/控件层自定义背景需移除，交还系统 | 必须 | §1.2 |
| 5 | 自绘 bar 必须用 `.safeAreaBar(edge:)`，不用 `.safeAreaInset` | 必须 | §1.5 / §3.9 |
| 6 | `.glassEffect()` 放在影响外观的 modifier 之后 | 必须 | §2.2 |
| 7 | `GlassEffectContainer(spacing:) ≤ 内部 HStack/VStack 间距` | 必须 | §2.2 |
| 8 | 多个 glass 必须包在同一 `GlassEffectContainer` 内 | 必须 | §2.4 / §1.8 |
| 9 | `.tint` / `.interactive` **必须带括号**（是方法不是属性） | 必须 | §4 / §8 |
| 10 | tint 只加在单个主 CTA 或状态指示，禁止批量给背景上色 | 禁止/必须 | §7 |
| 11 | `.clear` 仅用于媒体背景上方，且必须加 dimming 层 | 限制 | §3.6.3 / §6 |
| 12 | 按钮用 `.buttonStyle(.glass/.glassProminent/.glass(_:))`，禁止自绘 | 必须 | §1.7 / §3.8 |
| 13 | 圆角用 `ConcentricRectangle` / `.rect(corners:isUniform:)`，禁止硬编码 radius | 必须 | §1.6 / §3.10 |
| 14 | `Reduce Transparency` / `Reduce Motion` 下材质降级为不透明纯色 | 必须 | §1.4 |
| 15 | 拒绝硬编码控件尺寸与全大写 section header | 禁止 | §1.8 |
| 16 | 一屏内 `GlassEffectContainer` 建议 ≤2，容器外 `.glassEffect()` 尽量为 0 | 必须 | §2.4 |
