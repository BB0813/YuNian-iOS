# Apple HIG 设计层（第一手摘录）

> 采集方式：Apple DocC JSON 接口 `https://developer.apple.com/tutorials/data/<路径>.json`
> 采集日期：本次会话
> 正文抽取：`primaryContentSections[].content[]` 中按顺序拼接 `text` 字段；`references` 字典用于解析链接标题（下文方括号内为 DocC 引用标题，非原文文字）。
> 约定：**原文（英文原句）逐字照抄，未改写、未翻译。** 「要点」「对本工程的约束」为本文档作者的中文归纳与推导。

---

## 1. materials — Materials

- **来源**：`https://developer.apple.com/tutorials/data/design/human-interface-guidelines/materials.json`
- **原文**（英文原句，逐条引用，不要改写）：
  > "Materials help visually separate foreground elements, such as text and controls, from background elements, such as content and solid colors. By allowing color to pass through from background to foreground, a material establishes visual hierarchy to help people more easily retain a sense of place."

  > "Apple platforms feature two types of materials: Liquid Glass, and standard materials. Liquid Glass is a dynamic material that unifies the design language across Apple platforms, allowing you to present controls and navigation without obscuring underlying content. In contrast to Liquid Glass, the Standard materials help with visual differentiation within the content layer."

  > "Liquid Glass forms a distinct functional layer for controls and navigation elements — like tab bars and sidebars — that floats above the content layer, establishing a clear visual hierarchy between functional elements and content. Liquid Glass allows content to scroll and peek through from beneath these elements to give the interface a sense of dynamism and depth, all while maintaining legibility for controls and navigation."

  > "Don’t use Liquid Glass in the content layer. Liquid Glass works best when it provides a clear distinction between interactive elements and content, and including it in the content layer can result in unnecessary complexity and a confusing visual hierarchy. Instead, use Standard materials for elements in the content layer, such as app backgrounds. An exception to this is for controls in the content layer with a transient interactive element like Sliders and Toggles; in these cases, the element takes on a Liquid Glass appearance to emphasize its interactivity when a person activates it."

  > "Use Liquid Glass effects sparingly. Standard components from system frameworks pick up the appearance and behavior of this material automatically. If you apply Liquid Glass effects to a custom control, do so sparingly. Liquid Glass seeks to bring attention to the underlying content, and overusing this material in multiple custom controls can provide a subpar user experience by distracting from that content. Limit these effects to the most important functional elements in your app."

  > "Only use clear Liquid Glass for components that appear over visually rich backgrounds. Liquid Glass provides two variants — regular and clear — that you can choose when building custom components or styling some system components. The appearance of these variants can differ in response to certain system settings, like if people choose a preferred look for Liquid Glass in their device’s settings, or turn on accessibility settings that reduce transparency or increase contrast in the interface."

  > "The regular variant blurs and adjusts the luminosity of background content to maintain legibility of text and other foreground elements. Scroll edge effects further enhance legibility by blurring and reducing the opacity of background content. Most system components use this variant. Use the regular variant when background content might create legibility issues, or when components have a significant amount of text, such as alerts, sidebars, or popovers."

  > "The clear variant is highly translucent, which is ideal for prioritizing the visibility of the underlying content and ensuring visually rich background elements remain prominent. Use this variant for components that float above media backgrounds — such as photos and videos — to create a more immersive content experience."

  > "For optimal contrast and legibility, determine whether to add a dimming layer behind components with clear Liquid Glass:"

  > "If the underlying content is bright, consider adding a dark dimming layer of 35% opacity."

  > "If the underlying content is sufficiently dark, or if you use standard media playback controls from AVKit that provide their own dimming layer, you don’t need to apply a dimming layer."

  > "Use standard materials and effects — such as UIBlurEffect, UIVibrancyEffect, and NSVisualEffectView.BlendingMode — to convey a sense of structure in the content beneath Liquid Glass."

  > "Choose materials and effects based on semantic meaning and recommended usage. Avoid selecting a material or effect based on the apparent color it imparts to your interface, because system settings can change its appearance and behavior. Instead, match the material or vibrancy style to your specific use case."

  > "Help ensure legibility by using vibrant colors on top of materials. When you use system-defined vibrant colors, you don’t need to worry about colors seeming too dark, bright, saturated, or low contrast in different contexts. Regardless of the material you choose, use vibrant colors on top of it."

  > "Consider contrast and visual separation when choosing a material to combine with blur and vibrancy effects. For example, consider that:"

  > "Thicker materials, which are more opaque, can provide better contrast for text and other elements with fine features."

  > "Thinner materials, which are more translucent, can help people retain their context by providing a visible reminder of the content that’s in the background."

  > "In addition to Liquid Glass, iOS and iPadOS continue to provide four standard materials — ultra-thin, thin, regular (default), and thick — which you can use in the content layer to help create visual distinction."（iOS, iPadOS 节）

  > "Except for quaternary, you can use the following vibrancy values for labels on any material. In general, avoid using quaternary on top of the thin and ultraThin materials, because the contrast is too low."

  > "Avoid removing or replacing material backgrounds for modal sheets when they’re provided by default."（watchOS 节）

- **要点**：HIG 把材质明确切成两层——**Liquid Glass 是控制/导航层**（浮在内容层之上），**standard materials 是内容层**（用于区分内容层级，如 app 背景）。这一页是全篇最重要的定位页：Liquid Glass 不是"好看的卡片背景"，它是**功能性图层**，其存在意义是让底下内容透出来。`regular` 与 `clear` 两个变体是两套取舍：regular 保可读性，clear 保内容可见性。系统的透明度/对比度无障碍设置会直接改变这两个变体的外观。
- **对本工程的约束**：
  - **禁止**对内容层元素使用 `.glassEffect()`，包括：消息气泡、会话列表行、设置项行、卡片、图片容器、应用背景。内容层一律使用 `Material`（`.ultraThinMaterial` / `.thinMaterial` / `.regularMaterial` / `.thickMaterial`）或语义色。
  - **允许**使用 `.glassEffect()` 的范围仅限：底部 TabBar 之上的浮动控制、悬浮操作按钮、播放条、以及瞬时交互控件（Slider/Toggle 的交互态）。
  - 全屏同时存在的 `glassEffect` 元素**必须** ≤ 3 个（对应"Limit these effects to the most important functional elements"）。
  - 同一视图内**禁止**多处使用 `.tint(...)` 的玻璃；着色玻璃**只允许**用在唯一的主行动（primary CTA）上。
  - 使用 `.glassEffect(.clear)` 时**必须**在玻璃之下补调暗层，按 Apple 的示例：`.background(.black.opacity(0.3))`（亮内容场景）；内容足够暗时可省略。
  - 需要"内容层内的视觉区分"时必须选 `Material` 而非玻璃，且按语义选厚度：细特征文字用厚材质，需要保留背景上下文用薄材质。
  - **禁止**在 `thin` / `ultraThin` 材质上使用 quaternary 级前景色（对比度不足）。
  - **禁止**自定义/移除系统 sheet 的默认材质背景。

---

## 2. layout — Layout

- **来源**：`https://developer.apple.com/tutorials/data/design/human-interface-guidelines/layout.json`
- **原文**（英文原句，逐条引用，不要改写）：
  > "Differentiate controls from content. Take advantage of the Liquid Glass material on all platforms that support it to provide a distinct appearance for your controls. Instead of applying a solid or semi-opaque background color beneath controls, use a scroll edge effect to visually elevate controls above content. For guidance, see Scroll views. For full-screen background content, be sure to extend it underneath sidebars, toolbars, and tab bars to fit the entire screen or window."

  > "If scaling a background image to the full window edge results in components like sidebars or inspectors covering important parts of the image, you can use a background extension effect to flip and blur the image, mirroring it beneath adjacent components and providing the appearance that the background image extends beneath them. For developer guidance, see backgroundExtensionEffect() and UIBackgroundExtensionView."

  > "Design a layout that adapts gracefully and consistently. People expect your experience to remain familiar when they rotate their device, resize a window, add another display, or switch to a different device. You can help ensure an adaptable interface by respecting system-defined safe areas, margins, and guides (where available) and specifying layout modifiers to fine-tune the placement of views in your interface."

  > "Be prepared for text-size changes. People use Supporting Dynamic Type to increase text size to be more readable, which occurs at the system level. Apps that don’t respond to this setting can be difficult or impossible to use for people who rely on this feature. Support Dynamic Type by adjusting your layout to accommodate text at larger sizes."

  > "A safe area defines the area within a window that isn’t covered on the edge by a hardware feature or another view within the window, like a toolbar, tab bar, or status bar. Respecting the safe area is essential to make sure system UI and hardware features like the Dynamic Island don’t obstruct content and controls."

  > "Determine layout based on size classes, not device type or orientation."

  > "Keep functionality the same as size classes change, and keep layout changes recognizable and familiar to the platform. Don’t change your app’s functionality based on the space it occupies."

  > "Use progressive disclosure to make layouts cleaner and easier to interact with."

  > "Align elements to make them easier to scan, and use indentation to convey hierarchy."

- **要点**：本页给出了"控制层怎么从内容里区分出来"的正解：**不是给控制加半透明背景色，而是用 scroll edge effect**。另外明确要求全屏背景内容要**延伸到**工具栏/标签栏底下，而不是在栏下面断开。以及：布局判断依据是 size class，不是设备型号或朝向。
  **注意**：本页通篇**没有**出现 "concentric"（同心圆角）相关表述——同心圆角的第一手出处是 toolbars、app-icons 与 `ConcentricRectangle` 开发者文档（见第 6 节与补充章节），不要声称 HIG layout 页讲了同心圆角。
- **对本工程的约束**：
  - **禁止**给自定义工具栏/TabBar 铺 `.background(Color...)` 或 `.background(.ultraThinMaterial)` 来"做出"分栏效果；**必须**改用 scroll edge effect（`scrollEdgeEffectStyle(_:for:)` / `safeAreaBar(edge:alignment:spacing:content:)`）。
  - 全屏背景（聊天壁纸、主题背景图）**必须** `.ignoresSafeArea()` 延伸到 TabBar / 工具栏下方，让玻璃层真的"取色于内容"；**禁止**让背景在栏上方截断。
  - 布局**必须**基于 `@Environment(\.horizontalSizeClass)` / `verticalSizeClass`，**禁止**基于 `UIDevice.modelName` 或 `UIDevice.orientation` 做分支。
  - **禁止**因 size class 变化而改变功能可用性（只能改变可见功能数量）。
  - 所有视图**必须**尊重 safe area；**禁止**用固定的 Magic Number 顶开 Dynamic Island。

---

## 3. color — Color

- **来源**：`https://developer.apple.com/tutorials/data/design/human-interface-guidelines/color.json`
- **原文**（英文原句，逐条引用，不要改写）：
  > "By default, Liquid Glass has no inherent color, and instead takes on colors from the content directly behind it. You can apply color to some Liquid Glass elements, giving them the appearance of colored or stained glass. This is useful for drawing emphasis to a specific control, like a primary call to action, and is the approach the system uses for prominent button styling. Symbols or text labels on Liquid Glass controls can also have color."（Liquid Glass color 节）

  > "For smaller elements like toolbars and tab bars, the system can adapt Liquid Glass between a light and dark appearance in response to the underlying content. By default, symbols and text on these elements follow a monochromatic color scheme, becoming darker when the underlying content is light, and lighter when it’s dark. Liquid Glass appears more opaque in larger elements like sidebars to preserve legibility over complex backgrounds and accommodate richer content on the material’s surface."

  > "Apply color sparingly to the Liquid Glass material, and to symbols or text on the material. If you apply color, reserve it for elements that truly benefit from emphasis, such as status indicators or primary actions. To emphasize primary actions, apply color to the background rather than to symbols or text. For example, the system applies the app accent color to the background in prominent buttons — such as the Done button — to draw attention and elevate their visual prominence. Refrain from adding color to the background of multiple controls."

  > "Avoid using similar colors in control labels if your app has a colorful background. While color can make apps more visually appealing, playful, or reflective of your brand, too much color can be overwhelming and make control labels more difficult to read. If your app features colorful backgrounds or visually rich content, prefer a monochromatic appearance for toolbars and tab bars, or choose an accent color with sufficient visual differentiation. By contrast, in apps with primarily monochromatic content or backgrounds, choosing your brand color as the app accent color can be an effective way to tailor your app experience and reflect your company’s identity."

  > "Be aware of the placement of color in the content layer. Make sure your interface maintains sufficient contrast by avoiding overlap of similar colors in the content layer and controls when possible. Although colorful content might intermittently scroll underneath controls, make sure its default or resting state — like the top of a screen of scrollable content — maintains clear legibility."

  > "Make sure all your app’s colors work well in light, dark, and increased contrast contexts. iOS, iPadOS, macOS, and tvOS offer both light and Dark Mode appearance settings. System colors vary subtly depending on the system appearance, adjusting to ensure proper color differentiation and contrast for text, symbols, and other elements. With the Increase Contrast setting turned on, the color differences become far more apparent. When possible, use system colors, which already define variants for all these contexts. If you define a custom color, make sure to supply light and dark variants, and an increased contrast option for each variant that provides a significantly higher amount of visual differentiation. Even if your app ships in a single appearance mode, provide both light and dark colors to support Liquid Glass adaptivity in these contexts."

  > "Avoid hard-coding system color values in your app. Documented color values are for your reference during the app design process. The actual color values may fluctuate from release to release, based on a variety of environmental variables. Use APIs like Color to apply system colors."

  > "Avoid redefining the semantic meanings of dynamic system colors. To ensure a consistent experience and ensure your interface looks great when the appearance of the platform changes, use dynamic system colors as intended. For example, don’t use the separator color as a text color, or secondary text label color as a background color."

  > "Avoid relying solely on color to differentiate between objects, indicate interactivity, or communicate essential information."

- **要点**：玻璃默认无色，颜色来自**它背后的内容**；系统会在浅/深内容上自动反相工具栏与标签栏的文字符号。带色玻璃只用于强调，且强调应加在**背景**上而不是文字/符号上。最重要的一条反直觉要求：**即使 app 只做单一外观模式，也必须同时提供浅色与深色两套颜色**，因为 Liquid Glass 的自适应依赖它们。
- **对本工程的约束**：
  - **必须**为所有自定义色在 Asset Catalog 中提供 Any / Dark / High Contrast 变体；**禁止**只定义一套深色（当前"暗色优先"的微信风格主题若只写死深色值，即违反此条）。
  - **必须**用 `Color("...")` 或语义色（`.primary` / `.secondary` / `Color(.secondaryLabel)`）取色；**禁止**在 Swift 代码里硬编码 `#RRGGBB` 或 `Color(red:green:blue:)` 常量作为品牌色之外的配色。
  - **禁止**把 `separator` 当文字色、把 secondary label 当背景色使用（语义不可挪用）。
  - 工具栏/标签栏在彩色内容层之上时**必须**用单色外观（`.monochrome`），或选一个对比足够的 accent color；**禁止**让控制标签色与内容层主色相近。
  - 主行动的强调色**必须**加在玻璃/按钮的**背景**上（对应 `.buttonStyle(.glassProminent)`），**禁止**给多个控制同时上背景色。
  - 滚动内容在**静止态**（顶部）时**必须**确保控制标签可读；不能只在"滚动中途"看起来还行。
  - 任何用颜色区分状态的地方（在线/离线、已读/未读）**必须**同时提供形状或文字标识，**禁止**只靠颜色。

---

## 4. typography — Typography

- **来源**：`https://developer.apple.com/tutorials/data/design/human-interface-guidelines/typography.json`
- **原文**（英文原句，逐条引用，不要改写）：
  > "Consider using the built-in text styles. The system-defined text styles give you a convenient and consistent way to convey your information hierarchy through font size and weight. Using text styles with the system fonts also ensures support for Dynamic Type and larger accessibility type sizes (where available), which let people choose the text size that works for them."

  > "You can use the constants defined in Font.Design to access all system fonts — don’t embed system fonts in your app or game. For example, use Font.Design.default to get the system font on all platforms; use Font.Design.serif to get the New York font."

  > "In general, avoid light font weights. For example, if you’re using system-provided fonts, prefer Regular, Medium, Semibold, or Bold font weights, and avoid Ultralight, Thin, and Light font weights, which can be difficult to see, especially when text is small."

  > "Use font sizes that most people can read easily. People need to be able to read your content at various viewing distances and under a variety of conditions. Follow the recommended default and minimum text sizes for each platform — for both custom and system fonts — to ensure your text is legible on all devices. Keep in mind that font weight can also impact how easy text is to read. If you use a custom font with a thin weight, aim for larger than the recommended sizes to increase legibility."

  （iOS, iPadOS 尺寸表）"iOS, iPadOS | 17 pt | 11 pt"（列头为 "Platform | Default size | Minimum size"）

  > "Minimize the number of typefaces you use, even in a highly customized interface. Mixing too many different typefaces can obscure your information hierarchy and hinder readability, in addition to making an interface feel internally inconsistent or poorly designed."

  > "Implement accessibility features for custom fonts. System fonts automatically support Dynamic Type (where available) and respond when people turn on accessibility features, such as Bold Text. If you use a custom font, make sure it implements the same behaviors."

  > "Make sure your app’s layout adapts to all font sizes. Verify that your design scales, and that text and glyphs are legible at all font sizes."

  > "Increase the size of meaningful interface icons as font size increases. If you use interface icons to communicate important information, make sure they’re easy to view at larger font sizes too. When you use SF Symbols, you get icons that scale automatically with Dynamic Type size changes."

  > "Keep text truncation to a minimum as font size increases. In general, aim to display as much useful text at the largest accessibility font size as you do at the largest standard font size. Avoid truncating text in scrollable regions unless people can open a separate view to read the rest of the content."

  > "Consider adjusting your layout at large font sizes. When font size increases in a horizontally constrained context, inline items (like glyphs and timestamps) and container boundaries can crowd text and cause truncation or overlapping. To improve readability, consider using a stacked layout where text appears above secondary items."

  > "If you need to display three or more lines of text, avoid tight leading even in areas where height is limited."

  > "Modify the built-in text styles if necessary. System APIs define font adjustments — called symbolic traits — that let you modify some aspects of a text style."

- **要点**：正解是**用系统文本样式**（`Font.TextStyle`）而不是自定义字号，这样 Dynamic Type 与 Bold Text 自动生效。自定义字体必须自己补齐 Dynamic Type 与 Bold Text 行为。iOS 默认 17pt / 最小 11pt；避免 Light 及更细字重（中文界面尤其容易因为细字重在小字号下糊掉）。三条以上文本避免 tight leading。
- **对本工程的约束**：
  - **必须**用 `.font(.body)` / `.headline` / `.caption` 等系统文本样式；**禁止** `.font(.system(size: 15))` 这类固定字号（除图标尺寸与极少数装饰性数字）。
  - **禁止** `.fontWeight(.ultraLight)` / `.thin` / `.light)`；允许的字重范围：`.regular` / `.medium` / `.semibold` / `.bold`。
  - **必须**用 `Font.Design.default`（或 `.rounded` / `.serif`）取字体；**禁止**把 SF 字体文件打包进 bundle（`UIAppFonts` 里不得有 SF Pro）。
  - 若引入自定义字体（如中文品牌字体），**必须**用 `.font(.custom("X", size: 17, relativeTo: .body))` 形式以保留 Dynamic Type 缩放；**禁止** `Font.custom(_:fixedSize:)`。
  - 正文字号**禁止**小于 11pt（按 iOS 最小尺寸）。
  - 聊天气泡等可增长容器**禁止** `.lineLimit(1)` 截断；**必须**让行数自由增长。
  - 时间戳等行内次要元素在超大字号下**必须**切换到堆叠布局（`ViewThatFits` 或对 `isAccessibilityCategory` 分支）。
  - 通知/会话列表里的图标**必须**随 Dynamic Type 放大（用 `Image(systemName:)` + `.font(...)` 或 `.imageScale`），**禁止**固定 `.frame(width:height:)` 而不缩放。

---

## 5. tab-bars — Tab bars

- **来源**：`https://developer.apple.com/tutorials/data/design/human-interface-guidelines/tab-bars.json`
- **原文**（英文原句，逐条引用，不要改写）：
  > "Use a tab bar to support navigation, not to provide actions. A tab bar lets people navigate among different sections of an app, like the Alarm, Stopwatch, and Timer tabs in the Clock app. If you need to provide controls that act on elements in the current view, use a Toolbars instead."

  > "A tab bar floats above content at the bottom of the screen. Its items rest on a Liquid Glass background that allows content beneath to peek through."（iOS 节）

  > "Make sure the tab bar is visible when people navigate to different sections of your app. If you hide the tab bar, people can forget which area of the app they’re in. The exception is when a modal view covers the tab bar, because a modal is temporary and self-contained."

  > "Don’t disable or hide tab bar buttons, even when their content is unavailable. Having tab bar buttons available in some cases but not others makes your app’s interface appear unstable and unpredictable. If a section is empty, explain why its content is unavailable."

  > "Include tab labels to help with navigation. A tab label appears beneath or beside a tab bar icon, and can aid navigation by clearly describing the type of content or functionality the tab contains. Use single words whenever possible."

  > "Consider using SF Symbols to provide familiar, scalable tab bar icons. When you use SF Symbols, tab bar icons automatically adapt to different contexts. For example, the tab bar can be regular or compact, depending on the device and orientation. Tab bar icons appear above tab labels in compact views, whereas in regular views, the icons and labels appear side by side. Prefer filled symbols or icons for consistency with the platform."

  > "Avoid overflow tabs. Depending on device size and orientation, the number of visible tabs can be smaller than the total number of tabs. If horizontal space limits the number of visible tabs, the trailing tab becomes a More tab in iOS and iPadOS, revealing the remaining items in a separate list. The More tab makes it harder for people to reach and notice content on tabs that are hidden, so limit scenarios in your app where this can happen."

  > "Avoid applying a similar color to tab labels and content layer backgrounds. If your app already has bright, colorful content in the content layer, prefer a monochromatic appearance for tab bars, or choose an accent color with sufficient visual differentiation."

  > "Use a badge to indicate that critical information is available. You can display a badge — a red oval containing white text and either a number or an exclamation point — on a tab to indicate that there’s new or updated information in the section that warrants a person’s attention. Reserve badges for critical information so you don’t dilute their impact and meaning."

  > "For tab bars with an attached accessory, like the MiniPlayer in Music, you can choose to minimize the tab bar and move the accessory inline with it when a person scrolls down. A person can exit the minimized state by tapping a tab or scrolling to the top of the view. For developer guidance, see TabBarMinimizeBehavior and UITabBarController.MinimizeBehavior."（iOS 节）

- **要点**：TabBar 本身就是系统提供的 Liquid Glass 元素，**不要自己重画**。它只做导航、不做动作；标签栏按钮永远不能被禁用或隐藏；图标优先用 filled SF Symbols；彩色内容层之上标签栏应保持单色。附件（如 MiniPlayer）可以通过 `TabBarMinimizeBehavior` 随滚动收起。
- **对本工程的约束**：
  - **必须**用系统 `TabView`（iOS 26 新签名 `Tab(...)` API）；**禁止**自绘底部导航栏，**禁止**给 `TabView` 加 `.background(...)` / `.toolbarBackground(...)`。
  - TabBar **禁止**承载动作按钮（例如"发送""新建"）；动作**必须**放到页面内或工具栏。
  - **禁止** `disabled()` 或按条件隐藏 Tab 项；空态**必须**在页面内解释原因。
  - Tab 项**必须**同时有 icon 与**单词**标签；图标**必须**用 filled 变体（如 `bubble.left.and.bubble.right.fill`）；**禁止**只用自定义位图图标。
  - 可见 Tab 数**必须** ≤ 5（避免 More 标签页）；若功能分区超过 5 个，**必须**改为 sidebar（`TabViewStyle.sidebarAdaptable`）或把次要分区收进页面内。
  - 未读徽标**只允许**用于真正需要即时注意的会话；**禁止**对普通更新滥用 badge。
  - Tab 标签色**禁止**与聊天区背景/气泡主色相近；彩色主题下 TabBar **必须**保持单色。

---

## 6. toolbars — Toolbars

- **来源**：`https://developer.apple.com/tutorials/data/design/human-interface-guidelines/toolbars.json`
- **原文**（英文原句，逐条引用，不要改写）：
  > "Reduce the use of toolbar backgrounds and tinted controls. Any custom backgrounds and appearances you use might overlay or interfere with background effects that the system provides. Instead, use the content layer to inform the color and appearance of the toolbar, and use a ScrollEdgeEffectStyle when necessary to distinguish the toolbar area from the content area. This approach helps your app express its unique personality without distracting from content."

  > "Avoid applying a similar color to toolbar item labels and content layer backgrounds. If your app already has bright, colorful content in the content layer, prefer using the default monochromatic appearance of toolbars."

  > "Prefer using standard components in a toolbar. By default, standard buttons, text fields, headers, and footers have corner radii that are concentric with bar corners. If you need to create a custom component, ensure that its corner radius is also concentric with the bar’s corners."

  > "Choose items deliberately to avoid overcrowding. People need to be able to distinguish and activate each item, so you don’t want to put too many items in the toolbar. To accommodate variable view widths, define which items move to the overflow menu as the toolbar becomes narrower."

  > "The system automatically adds an overflow menu in macOS or iPadOS when items no longer fit. Don’t add an overflow menu manually, and avoid layouts that cause toolbar items to overflow by default."

  > "Use the standard Back and Close buttons. People know that the standard Back button lets them retrace their steps through a hierarchy of information, and the standard Close button closes a modal view. Prefer the standard symbols for each, and don’t use a text label that says Back or Close."

  > "Prefer system-provided symbols without borders. System-provided symbols are familiar, automatically receive appropriate coloring and vibrancy, and respond consistently to user interactions. Borders (like outlined circle symbols) aren’t necessary because the section provides a visible container, and the system defines hover and selection state appearances automatically."

  > "Use the `.prominent` style for key actions such as Done or Submit. This separates and tints the action so there’s a clear focal point. Only specify one primary action, and put it on the trailing side of the toolbar."

  > "Group toolbar items logically by function and frequency of use."

  > "Minimize the number of groups. Too many groups of controls can make a toolbar feel cluttered and confusing, even with the added space on iPad and Mac. In general, aim for a maximum of three."

  > "Keep actions with text labels separate. Placing an action with a text label next to an action with a symbol can create the illusion of a single action with a combined text and symbol, leading to confusion and misinterpretation."

  > "Don’t title windows with your app name."

  > "Write a concise title. Aim for a word or short phrase that distills the purpose of the window or view, and keep the title under 15 characters long so you leave enough room for other controls."

  > "Use a large title to help people stay oriented as they navigate and scroll."（iOS 节）

  > "Prioritize only the most important items for inclusion in the main toolbar area. Because space is so limited, carefully consider which actions are essential to your app and include those first. Create a More menu to include additional items."（iOS 节）

- **要点**：这是"不要覆盖系统材质"的**最直接第一手出处**：自定义工具栏背景会**遮挡或干扰系统提供的背景效果**。正解是——让内容层的颜色去影响工具栏，必要时用 `ScrollEdgeEffectStyle` 区分区域。另一条硬规则：自定义组件的圆角**必须与栏的圆角同心（concentric）**。工具栏分组最多 3 组，只有 1 个主行动且放在尾部。
- **对本工程的约束**：
  - **禁止**对 `.toolbar` / `NavigationStack` 的栏区域使用 `.toolbarBackground(...)`、`.background(Color...)`、`.background(.ultraThinMaterial)` 或自绘模糊层；**必须**保留系统默认材质。
  - 需要区分工具栏与内容时，**必须**用 `ScrollEdgeEffectStyle`（`.automatic` / `.hard` / `.soft`），**禁止**用叠加的半透明色块。
  - 工具栏内自定义控件（如自定义标题视图、自定义按钮容器）**必须**使用 `ConcentricRectangle` 或 `.rect(corners:isUniform:)` 让圆角与栏同心；**禁止**写死 `cornerRadius: 12` 之类的固定值。
  - 工具栏分组**必须** ≤ 3 组；主行动**必须**唯一且放在 trailing 侧，使用 `.prominent` 样式。
  - 工具栏按钮**必须**用无边框 SF Symbols；**禁止**用带圆圈/描边的自定义图标。
  - 返回/关闭**必须**用系统默认按钮；**禁止**自定义 "返回"/"关闭" 文字标签。
  - **禁止**自建 overflow/More 菜单（系统会加）。
  - 标题**必须** ≤ 15 个字符且**禁止**使用 App 名称当标题。

---

## 7. buttons — Buttons

- **来源**：`https://developer.apple.com/tutorials/data/design/human-interface-guidelines/buttons.json`
- **原文**（英文原句，逐条引用，不要改写）：
  > "Make buttons easy for people to use. It’s essential to include enough space around a button so that people can visually distinguish it from surrounding components and content. Giving a button enough space is also critical for helping people select or activate it, regardless of the method of input they use. As a general rule, a button needs a hit region of at least 44x44 pt — in visionOS, 60x60 pt — to ensure that people can select it easily, whether they use a fingertip, a pointer, their eyes, or a remote."

  > "Always include a press state for a custom button. Without a press state, a button can feel unresponsive, making people wonder if it’s accepting their input."

  > "In general, use a button that has a prominent visual style for the most likely action in a view. To draw people’s attention to a specific button, use a prominent button style so the system can apply an accent color to the button’s background. Buttons that use color tend to be the most visually distinctive, helping people quickly identify the actions they’re most likely to use. Keep the number of prominent buttons to one or two per view. Presenting too many prominent buttons increases cognitive load, requiring people to spend more time considering options before making a choice."

  > "Use style — not size — to visually distinguish the preferred choice among multiple options. When you use buttons of the same size to offer two or more options, you signal that the options form a coherent set of choices. By contrast, placing two buttons of different sizes near each other can make the interface look confusing and inconsistent. If you want to highlight the preferred or most likely option in a set, use a more prominent button style for that option and a less prominent style for the remaining ones."

  > "Avoid applying a similar color to button labels and content layer backgrounds. If your app already has bright, colorful content in the content layer, prefer using the default monochromatic appearance of button labels."

  > "Assign the primary role to the button people are most likely to choose."

  > "Don’t assign the primary role to a button that performs a destructive action, even if that action is the most likely choice. Because of its visual prominence, people sometimes choose a primary button without reading it first. Help people avoid losing content by assigning the primary role to nondestructive buttons."

  > "Configure a button to display an activity indicator when you need to provide feedback about an action that doesn’t instantly complete. Displaying an activity indicator within a button can save space in your user interface while clearly communicating the reason for the delay."（iOS, iPadOS 节）

  > "Consider using text when a short label communicates more clearly than an icon."

  > "Try to associate familiar actions with familiar icons."

- **要点**：44×44pt 是**最低**命中区；自定义按钮**必须**有按压态。一屏内突出的按钮最多 1–2 个，且"强调"用**样式**而不是**尺寸**（不许把主按钮做大）。破坏性操作**禁止**给 primary role。
- **对本工程的约束**：
  - 所有可点击控件的命中区**必须** ≥ 44×44pt；图标按钮若视觉尺寸更小，**必须**用 `.frame(minWidth: 44, minHeight: 44)` 或 `.contentShape(Rectangle())` 扩大命中区；**禁止**直接放一个 20pt 图标按钮。
  - 自定义按钮**必须**有按压反馈（`.buttonStyle(...)` 自定义 `configuration.isPressed` 分支，或 `ScaleButtonStyle`）；**禁止**裸 `.onTapGesture` 充当按钮。
  - 每屏 `.buttonStyle(.glassProminent)` / `.borderedProminent` 数量**必须** ≤ 2。
  - **禁止**用尺寸差异区分主次按钮；**必须**用样式差异。
  - "删除会话""清空记忆""清除数据"等破坏性按钮**禁止**使用 primary/prominent 样式；**必须**用 `.destructive` role。
  - 发送、加载、生成等耗时动作**必须**支持按钮内进度指示（替代或伴随标签文字）。
  - 按钮标签色**禁止**与聊天背景主色相近。

---

## 8. app-icons — App icons

- **来源**：`https://developer.apple.com/tutorials/data/design/human-interface-guidelines/app-icons.json`
- **原文**（英文原句，逐条引用，不要改写）：
  > "In iOS, iPadOS, and macOS, icons are square, and the system applies masking to produce rounded corners that precisely match the curvature of other rounded interface elements throughout the system and the bezel of the physical device itself. In tvOS, icons are rectangular, also with concentric edges. In visionOS and watchOS, icons are square and the system applies circular masking."（Icon shape 节）

  > "Produce appropriately shaped, unmasked layers. The system masks all layer edges to produce an icon’s final shape. For iOS, iPadOS, and macOS icons, provide square layers so the system can apply rounded corners. For visionOS and watchOS, provide square layers so the system can create the circular icon shape. For tvOS, provide rectangular layers so the system can apply rounded corners. Providing layers with pre-defined masking negatively impacts specular highlight effects and makes edges look jagged."

  > "Keep primary content centered to avoid truncation when the system adjusts corners or applies masking."

  > "Let the system handle blurring and other visual effects. The system dynamically applies visual effects to your app icon layers, so there’s no need to include specular highlights, drop shadows between layers, beveled edges, blurs, glows, and other effects. In addition to interfering with system-provided effects, custom effects are static, whereas the system supplies dynamic ones."（Visual effects 节）

  > "Prefer clearly defined edges in foreground layers. To ensure system-drawn highlights and shadows look best, avoid soft and feathered edges on foreground layer shapes."

  > "Vary opacity in foreground layers to increase the sense of depth and liveliness."

  > "Design a background that both stands out and emphasizes foreground content. If you choose a gradient for your background layer, ensure that it responds well to system lighting effects. Icon Composer supports solid colors and gradients for background layers, making it unnecessary to import custom background images in most cases. If you do import a background layer, make sure it’s full-bleed and opaque."

  > "Prefer vector graphics when bringing layers into Icon Composer. Unlike raster images, vector graphics (such as SVG or PDF) scale gracefully and appear crisp at any size."

  > "Keep your icon’s features consistent across appearances. To create a seamless experience, keep your icon’s core visual features the same in the default, dark, clear, and tinted appearances. Avoid creating custom icon variants that swap elements in and out with each variant, which may make it harder for people to find your app when they switch appearances."

  > "Use your light app icon as the basis for your dark icon. Choose complementary colors that reflect the default design, and avoid excessively bright images."

  > "Include text only when it’s essential to your experience or brand. Text in icons doesn’t support accessibility or localization, is often too small to read easily, and can make an icon appear cluttered."

  > "Prefer illustrations to photos and avoid replicating UI components."

  > "Don’t use replicas of Apple hardware products. Apple products are copyrighted and can’t be reproduced in your app icons."

  （Specifications 表）"iOS, iPadOS, macOS | Square | Rounded rectangle (square) | 1024x1024 px | Layered | Default, dark, clear light, clear dark, tinted light, tinted dark"

  > "App icons support the following color spaces:" / "sRGB (color)" / "Gray Gamma 2.2 (grayscale)" / "Display P3 (wide-gamut color in iOS, iPadOS, macOS, tvOS, and watchOS only)"

  > "iOS, iPadOS, macOS, and watchOS app icons include a background layer and one or more foreground layers that coalesce to create dimensionality. These icons take on Liquid Glass attributes like specular highlights, refraction, and translucency. These effects automatically adapt with the size of your icon, apply consistently across platforms, and can appear differently between system versions."

- **要点**：图标形状与圆角**由系统遮罩产生**，其曲率与系统其他圆角元素及硬件边框一致（这就是"同心"在图标上的体现）。**禁止**自己预先切圆角/加描边——会破坏系统高光并让边缘出现锯齿。也不要自己画高光、投影、模糊、发光。当前要求交付**分层**图标（Icon Composer），1024×1024，并覆盖 default / dark / clear / tinted 变体。
- **对本工程的约束**：
  - **必须**交付 1024×1024 的**未遮罩方形**图层（Icon Composer `.icon` 文件）；**禁止**在图标源文件里预先应用圆角、圆角遮罩或描边。
  - **禁止**在图标图层中内置高光、投影、斜面、模糊、发光等效果（一律交给系统）。
  - 前景图层边缘**必须**清晰（禁止羽化/柔边）；**禁止**极细线宽与尖锐直角（小尺寸会丢失细节）。
  - **必须**提供 default（light）/ dark / clear / tinted 四个外观的图层标注；各外观**必须**保持同一核心视觉特征，**禁止**在不同变体间替换元素。
  - dark 变体**必须**以 light 图标为基础派生，避免过亮图像。
  - 图标**禁止**包含文字（除非是品牌必需的单字母记忆符）；**禁止**使用产品截图或 Apple 硬件产品图形。
  - 导出色彩空间**只允许** sRGB / Gray Gamma 2.2 / Display P3。

---

## 9. designing-for-ios — Designing for iOS

- **来源**：`https://developer.apple.com/tutorials/data/design/human-interface-guidelines/designing-for-ios.json`
- **原文**（英文原句，逐条引用，不要改写）：
  > "Ergonomics. People generally hold their iPhone in one or both hands as they interact with it, switching between landscape and portrait orientations as needed. While people are interacting with the device, their viewing distance tends to be no more than a foot or two."

  > "Help people concentrate on primary tasks and content by limiting the number of onscreen controls while making secondary details and actions discoverable with minimal interaction."

  > "Adapt seamlessly to appearance changes — like device orientation, Dark Mode, and Dynamic Type — letting people choose the configurations that work best for them."

  > "Support interactions that accommodate the way people usually hold their device. For example, it tends to be easier and more comfortable for people to reach a control when it’s located in the middle or bottom area of the display, so it’s especially important let people swipe to navigate back or initiate actions in a list row."

  > "With people’s permission, integrate information available through platform capabilities in ways that enhance the experience without asking people to enter data."

  > "Sometimes, people spend just a minute or two checking on event or social media updates, tracking data, or sending messages. At other times, people can spend an hour or more browsing the web, playing games, or enjoying media. People typically have multiple apps open at the same time, and they appreciate switching frequently among them."

- **要点**：iPhone 的使用距离 30–60cm，单/双手持握，重要控制应放在**屏幕中部或底部**（拇指可达区），返回用滑动而不是只靠左上角按钮。次要信息渐进披露，控制数量克制。短时高频与长时间沉浸两种使用模式都要成立。
- **对本工程的约束**：
  - 高频主行动（发送、语音、切换会话）**必须**位于屏幕下半部可达区；**禁止**只把关键操作放在导航栏左上角。
  - 聊天详情等页面**必须**支持从屏幕左缘右滑返回（`NavigationStack` 默认行为）；**禁止**用 `interactiveDismissDisabled` 或自定义手势覆盖它。
  - 次要设置项**必须**渐进披露（收进二级页面 / `Menu` / `DisclosureGroup`）；**禁止**在首屏平铺全功能。
  - 聊天列表行**必须**支持滑动操作（swipe actions），而不是只能长按。

---

## 10. accessibility — Accessibility

- **来源**：`https://developer.apple.com/tutorials/data/design/human-interface-guidelines/accessibility.json`
- **原文**（英文原句，逐条引用，不要改写）：
  > "Support larger text sizes. Make sure people can adjust the size of your text or icons to make them more legible, visible, and comfortable to read. Ideally, give people the option to enlarge text by at least 200 percent (or 140 percent in watchOS apps)."（Vision 节）

  （iOS 最小/默认可读尺寸表）"iOS, iPadOS | 17 pt | 11 pt"

  > "Strive to meet color contrast minimum standards. To ensure all information in your app is legible, it’s important that there’s enough contrast between foreground text and icons and background colors."

  （WCAG Level AA 对比度表）"Up to 17 pts | All | 4.5:1" / "18 pts | All | 3:1" / "All | Bold | 3:1"

  > "If your app doesn’t provide this minimum contrast by default, ensure it at least provides a higher contrast color scheme when the system setting Increase Contrast is turned on. If your app supports Dark Mode, make sure to check the minimum contrast in both light and dark appearances."

  > "Prefer system-defined colors. These colors have their own accessible variants that automatically adapt when people adjust their color preferences, such as enabling Increase Contrast or toggling between the light and dark appearances."

  > "Convey information with more than color alone. Some people have trouble differentiating between certain colors and shades. For example, people who are color blind may have particular difficulty with pairings such as red-green and blue-orange. Offer visual indicators, like distinct shapes or icons, in addition to color to help people perceive differences in function and changes in state."

  > "Offer sufficiently sized controls. Controls that are too small are hard for many people to interact with and select. Strive to meet the recommended minimum control size for each platform to ensure controls and menus are comfortable for all when tapping and clicking."（Mobility 节）

  （控件尺寸表）"iOS, iPadOS | 44x44 pt | 28x28 pt"

  > "Consider spacing between controls as important as size. Include enough padding between elements to reduce the chance that someone taps the wrong control. In general, it works well to add about 12 points of padding around elements that include a bezel. For elements without a bezel, about 24 points of padding works well around the element’s visible edges."

  > "Support simple gestures for common interactions. For many people, with or without disabilities, complex gestures can be challenging. For interactions people do frequently in your app or game, use the simplest gesture possible — avoid custom multifinger and multihand gestures — so repetitive actions are both comfortable and easy to remember."

  > "Offer alternatives to gestures. Make sure your UI’s core functionality is accessible through more than one type of physical interaction. For example, if you use a swipe gesture to dismiss a view, also make a button available so people can tap or use an assistive device."

  > "Be cautious with fast-moving and blinking animations. When you use these effects in excess, it can be distracting, cause dizziness, and in some cases even result in epileptic episodes. People who are prone to these effects can turn on the Reduce Motion accessibility setting. When this setting is active, ensure your app or game responds by reducing automatic and repetitive animations, including zooming, scaling, and peripheral motion. Other best practices for reducing motion include:"（Cognitive 节）

  > "Tightening animation springs to reduce bounce effects"

  > "Tracking animations directly with people’s gestures"

  > "Avoiding animating depth changes in z-axis layers"

  > "Replacing transitions in x-, y-, and z-axes with fades to avoid motion"

  > "Avoiding animating into and out of blurs"

  > "Minimize use of time-boxed interface elements. Views and controls that auto-dismiss on a timer can be problematic for people who need longer to process information, and for people who use assistive technologies that require more time to traverse the interface. Prefer dismissing views with an explicit action."

  > "Let people use the keyboard alone to navigate and interact with your app. People can turn on Full Keyboard Access to navigate apps using their physical keyboard."

  > "Support Switch Control."

  > "Keep actions simple and intuitive. Ensure that people can navigate your interface using easy-to-remember and consistent interactions. Prefer system gestures and behaviors people are already familiar with over creating custom gestures people must learn and retain."

  > "Let people control audio and video playback. Avoid autoplaying audio and video content without also providing controls to start and stop it."

- **要点**：文本至少放大到 200%；对比度按 WCAG AA（≤17pt 需 4.5:1，≥18pt 或粗体需 3:1）；控件最小 28×28pt（默认 44×44pt），无边框元素周围留约 24pt 间距。Reduce Motion 生效时**必须**减少自动与重复动画，且明确列出 5 条具体手法（收紧弹簧、动画跟随手势、避免 z 轴深度动画、xyz 过渡换淡入淡出、**避免动画进出模糊**）。
  **重要更正**：「减弱透明度（Reduce Transparency）对材质的影响」这句**不在** accessibility 页，而在 **materials 页**（"turn on accessibility settings that reduce transparency or increase contrast in the interface"）与 **Adopting Liquid Glass**（"turn on accessibility settings that reduce transparency or motion in the interface. These settings can remove or modify certain effects."）以及 SwiftUI `accessibilityReduceTransparency` 文档中。不要把它归到 accessibility 页。
- **对本工程的约束**：
  - **禁止**对任何视图写死最小缩放比例或禁用 Dynamic Type（`dynamicTypeSize(...DynamicTypeSize.accessibility5)` 上限是允许的，但**禁止** `.dynamicTypeSize(.large)` 之类锁死）。
  - 所有文字/图标在最大无障碍字号下**必须**不裁切（用 `ViewThatFits` 或堆叠布局兜底）。
  - 纯色背景上的正文对比度**必须** ≥ 4.5:1；≥18pt 或 bold 可放宽到 3:1；**必须**为 Increase Contrast 提供单独的高对比变体（`@Environment(\.colorSchemeContrast) == .increased`）。
  - 所有可交互元素**必须** ≥ 44×44pt；**允许**的下限是 28×28pt，且此时周围**必须**有至少 12pt 间距。
  - 关键操作（发送、删除、关闭）**禁止**只依赖手势；**必须**同时提供可点击控件。
  - **禁止**多指/多手自定义手势作为常用操作入口。
  - `@Environment(\.accessibilityReduceMotion)` 为 `true` 时**必须**：关闭所有弹簧回弹（`.spring` 改为 `.easeInOut` 或 `.linear`）、关闭液体玻璃的形变/morph 过渡、把位移/缩放过渡替换为 `.opacity` 淡入淡出，并**禁止**任何进出 blur 的动画（`Material` 的透明度渐变也在内）。
  - **禁止**自动定时消失的提示（toast/snackbar 只有 `onDisappear` 无显式关闭）；**必须**提供显式关闭动作或常驻可关闭。
  - **禁止**声音/语音作为唯一信息通道（连接失败、新消息等**必须**有视觉提示）。
  - 每个图标型按钮**必须**有 `accessibilityLabel`；**禁止**仅有装饰性 `Image` 参与无障碍树（应加 `.accessibilityHidden(true)`）。

---

# 补充第一手资料（超出必抓 10 页，但直接决定本工程实现细节）

以下页面同样通过 DocC JSON 抓取成功，因直接回答「自定义背景 / 不要覆盖系统材质 / 同心圆角 / 无障碍降级」四大痛点，单列。

## 11. Adopting Liquid Glass — 官方迁移指南

- **来源**：`https://developer.apple.com/tutorials/data/documentation/technologyoverviews/adopting-liquid-glass.json`
- **原文**：
  > "Reduce your use of custom backgrounds in controls and navigation elements. Any custom backgrounds and appearances you use in these elements might overlay or interfere with Liquid Glass or other effects that the system provides, such as the scroll edge effect. Make sure to check any custom backgrounds in elements like split views, tab bars, and toolbars. Prefer to remove custom effects and let the system determine the background appearance, especially for the following elements:"

  > "Test your interface with a variety of display and accessibility settings. Translucency and fluid morphing animations contribute to the look and feel of Liquid Glass, but can adapt to people’s needs. For example, people can choose a preferred look for Liquid Glass in their device’s settings, or turn on accessibility settings that reduce transparency or motion in the interface. These settings can remove or modify certain effects. If you use standard components from system frameworks, this experience adapts automatically. Ensure you test your app’s custom elements, colors, and animations with different configurations of these settings."

  > "Check for crowding or overlapping of controls. Prefer to use standard spacing metrics instead of overriding them, and avoid overcrowding or layering Liquid Glass elements on top of each other."

  > "Optimize for legibility when content scrolls beneath controls. Scroll views offer a scrollEdgeEffectStyle(_:for:) that helps maintain sufficient legibility and contrast for controls by obscuring content that scrolls beneath them. System bars like toolbars adopt this behavior by default. If you use a custom bar with elements like controls, text, or icons that have content scrolling beneath them, you can register those views to use a scroll edge effect with these APIs:"

  > "Consider aligning the shape of controls with other rounded elements throughout the interface. Across Apple platforms, the shape of the hardware informs the curvature, size, and shape of nested interface elements, including controls, sheets, popovers, windows, and more. Help maintain a sense of visual continuity in your interface by using rounded shapes that are concentric to their containers using these APIs:"

  > "Leverage new button styles. Instead of creating buttons with custom Liquid Glass effects, you can adopt the look and feel of the material with minimal code by using one of the following button style APIs:"

  > "Liquid Glass applies to the topmost layer of the interface, where you define your navigation. Key navigation elements like Tab bars and Sidebars float in this Liquid Glass layer to help people focus on the underlying content."

  > "Establish a clear navigation hierarchy. It’s more important than ever for your app to have a clear and consistent navigation structure that’s distinct from the content you provide. Ensure that you clearly separate your content from navigation elements, like tab bars and sidebars, to establish a distinct functional layer above the content layer."

  > "Audit the backgrounds of sheets and popovers. Check whether you add a visual effect view to your popover’s content view, and remove those custom background views to provide a consistent experience with other sheets across the system."

  > "Combine custom Liquid Glass effects to improve rendering performance. If you apply these effects to custom elements, make sure to combine them using a GlassEffectContainer, which helps optimize performance while fluidly morphing Liquid Glass shapes into each other."

  > "Review your use of color in controls. Be judicious with your use of Color in controls and navigation so they stay legible. If you do apply color to these elements, leverage system colors, or define a custom color with light and dark variants, and an increased contrast option for each variant."

  > "Check content safe areas for sidebars and inspectors."

  > "Extend content beneath sidebars and inspectors."

  本页通过 `tabNavigator` 明确列出的 **SwiftUI API 清单**（逐字）：
  - scroll edge effect 注册：`safeAreaBar(edge:alignment:spacing:content:)`
  - 同心圆角：`rect(corners:isUniform:)`、`ConcentricRectangle`
  - 玻璃按钮样式：`glass`、`glassProminent`、`glass(_:)`（`PrimitiveButtonStyle`）
  - 侧栏自适应：`sidebarAdaptable`（`TabViewStyle`）
  - 分割视图：`NavigationSplitView`、`inspector(isPresented:content:)`
  - 背景延伸效果：`backgroundExtensionEffect()`

- **要点**：官方迁移指南把「去掉自定义背景」写成**行动项**，并点名要检查 split views / tab bars / toolbars；也点名要**移除 popover content view 上的 visual effect view**。同时明确"自定义玻璃必须放进 `GlassEffectContainer`"以优化渲染，且"不要给玻璃元素互相叠加"。
- **对本工程的约束**：
  - **必须**审计并删除 `core:ui-common` 等价 iOS 模块里所有自绘的磨砂/玻璃背景（`UIVisualEffectView`、自定义 `blur` 视图、`Rectangle().fill(.ultraThinMaterial)` 充当栏背景）在**控制/导航层**的使用。
  - **必须**把多个相邻玻璃元素包在 `GlassEffectContainer` 内，并让容器 `spacing` **小于**内部 `HStack`/`VStack` 的 spacing（否则静止时就会糊成一块）。
  - **禁止**玻璃元素互相叠加（z 轴堆叠）。
  - **禁止**在 sheet/popover 的 content view 上额外加 visual effect view。
  - 使用玻璃按钮时**必须**优先 `.buttonStyle(.glass)` / `.glassProminent`，**禁止**自己用 `.glassEffect()` 拼一个按钮。

## 12. Scroll views — 滚动边缘效果

- **来源**：`https://developer.apple.com/tutorials/data/design/human-interface-guidelines/scroll-views.json`
- **原文**：
  > "In iOS, iPadOS, and macOS, a scroll edge effect provides a visual separation between certain interface elements, such as Toolbars, and the scrolling content area behind them. If you use custom bars, you might want to add this effect manually if the top layer of your interface needs extra clarity, or adjust its style from automatic to the hard or soft style."

  > "Prefer the automatic scroll edge effect style. Where possible, use the default automatic style of the scroll edge effect. This style provides a more opaque visual separation for top toolbars that contain a large number of controls, text that appears outside of Liquid Glass controls, and pinned table headers. If you use the soft scroll edge effect style instead, thoroughly test your interface to ensure your controls maintain legibility in a variety of contexts."

  > "Only use a scroll edge effect when a scroll view is behind floating interface elements. Scroll edge effects aren’t decorative. They don’t block or darken like overlays; they exist to ensure controls stay visually distinct."

  > "Apply one scroll edge effect per view. In split view layouts on iPad and Mac, each pane can have its own scroll edge effect; in this case, keep them consistent in height to maintain alignment."

  > "Avoid putting a scroll view inside another scroll view with the same orientation. Nesting scroll views that have the same orientation can create an unpredictable interface that’s difficult to control. It’s alright to place a horizontal scroll view inside a vertical scroll view (or vice versa), however."

- **要点**：scroll edge effect **不是装饰**，也不该当作遮罩用；它只在"滚动内容位于浮动元素之后"时使用。优先 `automatic` 样式。一个视图只能有一个 scroll edge effect。
- **对本工程的约束**：
  - 聊天列表滚动到 TabBar / 输入栏之下时，**必须**依赖系统默认 scroll edge effect；**禁止**额外叠加 `.overlay(LinearGradient)` 之类的渐隐遮罩。
  - 自定义顶部栏**必须**用 `safeAreaBar(edge:alignment:spacing:content:)` 注册，而**不是**手写背景。
  - **禁止**使用 `.soft` 样式而不做多场景可读性验证；默认**必须**用 `.automatic`。
  - 同一视图**禁止**出现两个 scroll edge effect。
  - **禁止**同方向 ScrollView 嵌套（消息里的横向卡片列表允许嵌在纵向列表内）。

## 13. Dark Mode — 材质在无障碍设置下的降级

- **来源**：`https://developer.apple.com/tutorials/data/design/human-interface-guidelines/dark-mode.json`
- **原文**：
  > "Test your content to make sure that it remains comfortably legible in both appearance modes. For example, in Dark Mode with Increase Contrast and Reduce Transparency turned on (both separately and together), you may find places where dark text is less legible when it’s on a dark background. You might also find that turning on Increase Contrast in Dark Mode can result in reduced visual contrast between dark text and a dark background. Although people with strong vision might still be able to read lower contrast text, such text could be illegible for many."

  > "Aim for sufficient color contrast in all appearances. Using system-defined colors can help you achieve a good contrast ratio between your foreground and background content. At a minimum, make sure the contrast ratio between colors is no lower than 4.5:1. For custom foreground and background colors, strive for a contrast ratio of 7:1, especially in small text."

  > "The system uses vibrancy and increased contrast to maintain the legibility of text on darker backgrounds."

  > "In Dark Mode, the system uses a dark color palette for all screens, views, menus, and controls, and may also use greater perceptual contrast to make foreground content stand out against the darker backgrounds."

- **要点**：**反直觉**——打开 Increase Contrast 在深色模式下**可能反而降低**深色文字与深色背景的对比。自定义颜色应追求 7:1（系统色最低 4.5:1）。
- **对本工程的约束**：
  - 深色主题**必须**在 `Increase Contrast + Reduce Transparency` 同时开启下逐一验证聊天气泡、会话列表、设置页四类页面。
  - 深色模式下的自定义前景/背景色对比度**必须** ≥ 7:1（小字号尤其）。
  - `accessibilityReduceTransparency == true` 时，半透明背景**必须**降级为不透明（见第 15 节 API 原文）。

## 14. ConcentricRectangle — 同心圆角的官方 API 与定义

- **来源**：`https://developer.apple.com/tutorials/data/documentation/swiftui/concentricrectangle.json`
- **原文**：
  > "Use `ConcentricRectangle` to create a rectangular shape that fits inside a container’s shape, similar to the way that a sheet’s corners in iOS match the curvature of the screen. System-provided elements like sheets and popovers do this automatically. You can use this effect for your custom views to match a device’s curved edges, or for your custom views near the edges inside another view with concentric corners."

  > "A rounded corner of a rectangle is concentric relative to the container shape’s adjacent corner when the corner’s radius shares a common center with the containing shape’s rounded corner radius. A containing shape could be a view that extends to the device’s rounded corners, or any view that sets containerShape(_:). `ConcentricRectangle` automatically calculates each corner’s radius relative to the container shape, so your view adapts correctly across devices and sizes without hard-coded values."

  > "When your `ConcentricRectangle`‘s corners are far away from the containing shape’s corners, such as the top corners in this example, the corner radius the system calculates may be zero. When that happens, the corner is square. It’s also possible that your app is running on a device whose corners are square. To ensure that your view always has rounded corners that are concentric relative to the container shape when they can be, use concentric(minimum:) to specify a rounded corner with a minimum radius."

  > "SwiftUI provides container shapes by default in system-provided views. To allow `ConcentricRectangle` to resolve corner radii based on concentricity in your custom view, use containerShape(_:) to specify a container shape that implements RoundedRectangularShape, such as Circle, Rectangle, RoundedRectangle, or Capsule."

  > "The functions with uniform corner styles calculate each uniform corner’s radius first, then use the largest radius for each uniform corner"

- **要点**：同心圆角的定义是"圆角半径与容器圆角半径**共享同一个圆心**"。`ConcentricRectangle` **自动计算**，因此**不应写死数值**。远离容器角的位置算出来可能是 0（变直角），需要圆角时用 `concentric(minimum:)`。
- **对本工程的约束**：
  - 所有嵌套在容器内的自定义卡片/按钮/标题视图**必须**用 `ConcentricRectangle` 或 `.rect(corners:isUniform:)`；**禁止** `RoundedRectangle(cornerRadius: 16)` 这类与容器无关的固定圆角。
  - 自定义容器**必须**通过 `.containerShape(...)`（`RoundedRectangle` / `Capsule` / `Circle`）声明容器形状，否则同心半径无法解析。
  - 期望始终有圆角时**必须**用 `.concentric(minimum: x)` 设定最小半径，**禁止**依赖默认计算出的 0。

## 15. SwiftUI 无障碍与玻璃 API 第一手说明

- **来源**：
  - `https://developer.apple.com/tutorials/data/documentation/swiftui/environmentvalues/accessibilityreducetransparency.json`
  - `https://developer.apple.com/tutorials/data/documentation/swiftui/environmentvalues/accessibilityreducemotion.json`
  - `https://developer.apple.com/tutorials/data/documentation/swiftui/glass/clear.json`
  - `https://developer.apple.com/tutorials/data/documentation/swiftui/view/glasseffect(_:in:).json`
  - `https://developer.apple.com/tutorials/data/documentation/swiftui/scrolledgeeffectstyle.json`
  - `https://developer.apple.com/tutorials/data/documentation/swiftui/applying-liquid-glass-to-custom-views.json`
- **原文**：
  > "If this property’s value is true, UI (mainly window) backgrounds should not be semi-transparent; they should be opaque."（`accessibilityReduceTransparency`）

  > "If this property’s value is true, UI should avoid large animations, especially those that simulate the third dimension."（`accessibilityReduceMotion`）

  > "When using clear glass, ensure content remains legible by adding a dimming layer or other treatment beneath the glass."（`Glass.clear`）

  > "For example, you could add a transparent black color beneath your glass to ensure content remains legible above the glass."（`Glass.clear`）

  > "Use the glassEffect(_:in:) modifier to add Liquid Glass effects to a view. By default, the modifier uses the regular variant of Glass and applies the given effect within a Capsule shape behind the view’s content."（Applying Liquid Glass to custom views）

  > "The `glassEffect(_:in:)` modifier captures the content to send to the container to render. Apply the `glassEffect(_:in:)` modifier after other modifiers that affect the appearance of the view."（Applying Liquid Glass to custom views）

  > "Use GlassEffectContainer when applying Liquid Glass effects on multiple views to achieve the best rendering performance."（Applying Liquid Glass to custom views）

  > "Creating too many Liquid Glass effect containers and applying too many effects to views outside of containers can degrade performance. Limit the use of Liquid Glass effects onscreen at the same time."（Applying Liquid Glass to custom views）

  > "SwiftUI anchors the Liquid Glass to a view’s bounds. For the example above, the material fills the entirety of the `Text` frame, which includes the padding."（`glassEffect(_:in:)`）

  > "By default, the system sets an automatic scroll edge effect style to provide a visual transition between scrolling content and stationary controls at both edges of the scroll view in the scrolling direction. The system determines which style to apply based on the platform and context. The hard style provides a more opaque, clearly defined linear boundary, and the soft style provides a subtle blurred transition"（`ScrollEdgeEffectStyle`）

  > "Apply scrollEdgeEffectHidden(_:for:) to a scroll view to remove the scroll edge effect entirely for an edge you specify."（`ScrollEdgeEffectStyle`）
- **要点**：`accessibilityReduceTransparency` 直接要求"不应半透明、应为不透明"——这是材质降级的**唯一权威表述**。`glassEffect(_:in:)` **必须放在其它影响外观的 modifier 之后**（一个非常容易写错、且可验收的规则）。玻璃锚定在视图 bounds（含 padding）。
- **对本工程的约束**：
  - **必须**在根视图读取 `@Environment(\.accessibilityReduceTransparency)`；为 `true` 时所有自定义半透明背景**必须**替换为不透明背景色，玻璃元素**必须**退回 `Material` 或纯色。
  - `.glassEffect(...)` **必须**写在该视图所有影响外观的 modifier（`.padding`、`.frame`、`.background`、`.clipShape`）**之后**；**禁止**在其后再叠 `.background` / `.clipShape`。
  - **禁止**把 `glassEffect` 用在 `List` / `LazyVStack` 的每一个 cell 上（性能 + 违反"内容层不用玻璃"）。
  - `accessibilityReduceMotion == true` 时**必须**关闭 `glassEffectID` / `GlassEffectTransition` 的 morph 与 `.matchedGeometry` 过渡，改为 `.opacity` 淡入淡出。

---

# 未能抓取 / 抓取失败记录

| 尝试的 URL | 结果 | 说明 |
|---|---|---|
| `https://developer.apple.com/tutorials/data/design/human-interface-guidelines/liquid-glass.json` | **HTTP 404** | Liquid Glass **不是**独立的 HIG 页面，它是 `materials` 页的锚点 `#Liquid-Glass`。本页正文已包含在本文档第 1 节，无需另抓。 |
| `https://developer.apple.com/tutorials/data/documentation/swiftui/glass/regular.json` | 抓取成功（HTTP 200）但**无正文** | 该页 `primaryContentSections` 只有 `mentions`，Discussion 为空。`regular` 变体的第一手描述只能来自 `materials` 页（"The regular variant blurs and adjusts the luminosity of background content..."），本文档已逐字引用。 |
| `https://developer.apple.com/tutorials/data/documentation/swiftui/glassEffect(_:in:).json`（未 URL 编码） | **HTTP 404** | 需使用编码后的 `glasseffect(_:in:)` 小写路径，改用后抓取成功（见第 15 节）。 |

**必抓 10 页全部成功（10/10）**，无内容编造。所有英文引文均直接来自上述 JSON 的 `text` 字段。

---

# 规则速查表（可直接作为 code review checklist）

| # | 规则 | 类型 | 依据 |
|---|---|---|---|
| 1 | 内容层（气泡/列表行/卡片/背景）不得使用 `.glassEffect()` | 禁止 | materials |
| 2 | 不得对 `.toolbar` / `TabView` 使用 `.toolbarBackground` 或自绘模糊背景 | 禁止 | toolbars, Adopting Liquid Glass |
| 3 | 控制与内容的区分必须用 scroll edge effect，不得用半透明色块 | 必须 | layout, scroll views |
| 4 | 全屏背景必须延伸到 TabBar/工具栏之下 | 必须 | layout |
| 5 | 自定义栏内控件圆角必须与栏同心（`ConcentricRectangle`） | 必须 | toolbars, ConcentricRectangle |
| 6 | 不得写死 `cornerRadius` 用于嵌套容器内元素 | 禁止 | ConcentricRectangle |
| 7 | `.glassEffect()` 必须写在其它外观 modifier 之后 | 必须 | glassEffect(_:in:) |
| 8 | 多玻璃元素必须包在 `GlassEffectContainer`，容器 spacing < 内部栈 spacing | 必须 | Applying Liquid Glass to custom views |
| 9 | 使用 `.glassEffect(.clear)` 时必须加调暗层 | 必须 | Glass.clear, materials |
| 10 | Reduce Transparency 为真时半透明背景必须变不透明 | 必须 | accessibilityReduceTransparency |
| 11 | Reduce Motion 为真时必须去掉弹簧/morph/进出 blur 的动画 | 必须 | accessibility |
| 12 | 每屏 prominent 按钮 ≤ 2；工具栏主行动唯一且在 trailing | 必须 | buttons, toolbars |
| 13 | 破坏性操作不得使用 prominent/primary 样式 | 禁止 | buttons |
| 14 | 命中区 ≥ 44×44pt；绝对下限 28×28pt 且需 12pt 间距 | 必须 | buttons, accessibility |
| 15 | 固定字号 `Font.system(size:)` 用于正文 | 禁止 | typography |
| 16 | Light/Thin/UltraLight 字重 | 禁止 | typography |
| 17 | 打包 SF 系统字体 | 禁止 | typography |
| 18 | 即使单一外观模式，也必须提供浅色+深色+高对比三套颜色 | 必须 | color |
| 19 | 深色模式自定义色对比度 ≥ 7:1 | 必须 | dark mode |
| 20 | 只靠颜色传达状态 | 禁止 | color, accessibility |
| 21 | 可见 Tab ≤ 5；不得禁用或隐藏 Tab | 必须 | tab bars |
| 22 | 自绘底部导航栏 | 禁止 | tab bars |
| 23 | 图标须为未遮罩 1024×1024 分层源文件，不得预切圆角或加高光/投影 | 必须 | app icons |
| 24 | 图标内含文字或 Apple 硬件图形 | 禁止 | app icons |
| 25 | 工具栏分组 ≤ 3 组；标题 ≤ 15 字符且不得用 App 名 | 必须 | toolbars |
