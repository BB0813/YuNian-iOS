# Apple 设计约束 · 予念 iOS

> **这份文档是代码的验收标准。** 与它冲突的实现一律视为缺陷，**包括"更好看的"实现**。
> 每一条规则都能追到 Apple 官方原文，不采信任何二手转述。

---

## 0. 这份文档的效力与复核方式

### 0.1 证据链

全部内容来自 Apple 官方文档正文，通过 DocC 的机器可读接口获取：

```
https://developer.apple.com/tutorials/data/<文档路径>.json
```

> 直接抓 `developer.apple.com/cn/documentation/...` 只能拿到 JS 空壳，**拿不到正文**。
> 这是本次能"读到原文"而不只是"记得大概"的前提。

原始凭证全部保留在仓库内，可逐条复核：

| 文件 | 内容 |
|---|---|
| [`_apple-docs/hig.md`](_apple-docs/hig.md) | HIG 设计层：10/10 必抓页 + 5 页补充，625 行 |
| [`_apple-docs/liquid-glass.md`](_apple-docs/liquid-glass.md) | Liquid Glass 技术层：29 篇 JSON，925 行 |
| `_apple-docs/raw*/`、`_apple-docs/text*/` | 抓取到的原始 JSON 与抽取文本 |
| `_apple-docs/_fetch*.py`、`extract*.py` | 抓取与抽取脚本（可重跑验证） |

两份摘录中每条规则的格式都是：**来源 URL / 逐字英文原文 / 中文要点 / 对本工程的约束**。

### 0.2 抓取失败项的如实记录

**不编造**是这份文档的底线。以下内容**没有抓到**，本文档因此**不对其做任何断言**：

- `documentation/swiftui/glass/tint.json`、`glass/interactive.json`、`glass/interactive().json` → 404。
  **原因不是网络**：`Glass.tint` / `Glass.interactive` **作为静态属性根本不存在**，它们是方法 `tint(_:)` / `interactive(_:)`（已抓到）。这是一个典型陷阱：凭印象写 API。
- `design/human-interface-guidelines/liquid-glass.json` → 404。Liquid Glass **不是独立 HIG 页**，它是 `materials` 页里的锚点。
- `design/human-interface-guidelines/liquid-glass-color.json` → 404。内容实际在 `hig/color` 内。
- `swiftui/glass/regular.json` → 200 但 Discussion 为空。

### 0.3 一处**证据缺口**（必须承认）

`UIDesignRequiresCompatibility` 的原文只写了：

> "Absence of the key, or NO, is the default value for apps linking against **the latest SDKs**."

**Apple 从未写明"用旧 SDK 链接"时的默认行为**，且该键在 iOS 27+ 被系统忽略。

### 0.4 该缺口的实际解答（第 202 轮，实测）

排查发现：**系统判定"是否用最新 SDK 构建"的依据是 Mach-O 的 `LC_BUILD_VERSION`，
而不是 Info.plist 里有没有 `UIDesignRequiresCompatibility`。**

本工程经 xtool 构建时，产物曾被标成 `sdk 17.0`（因为链接器从 target triple
`arm64-apple-ios17.0` 推导，而 `-sdk` 参数不写这个字段）。后果是：
**源码用了 `.glassEffect` 能出效果，但系统组件（`TabView`、`.toolbar`、
`Picker(.segmented)`、系统材质）全部呈现旧版外观。**

修复见 `YuNian-iOS-xtool-Linux构建手册.md` §2②b（注入 `-platform_version ios 17.0 27.0`）。
修复后 Mach-O 为 `sdk 27.0 / minos 17.0`。

**教训**：判断"系统会不会给新外观"，必须看链接产物的 `LC_BUILD_VERSION`，
不能只看源码用了什么 API、也不能只看构建是否成功。

---

## 1. 核心模型：Liquid Glass 是**功能图层**，不是美化材质

这是整套约束的根，也是本工程此前**整片走错方向**的地方。

Apple HIG `materials` 原文：

> **"Don't use Liquid Glass in the content layer."**

`Adopting Liquid Glass` 原文：

> "This material forms a **distinct functional layer for controls and navigation elements**."

### 三层模型（权威定义）

```
┌──────────────────────────────────────────────┐
│ 导航 / 控件层   Liquid Glass                  │  ← 唯一允许用玻璃的地方
│ 系统栏、工具栏、按钮、Slider/Toggle 交互态        │     且必须优先用系统组件
├──────────────────────────────────────────────┤
│ 内容层          标准 Material（不是 Liquid Glass）│  ← 列表、卡片、气泡、设置行
│                                              │     本工程此前把玻璃用满了这一层
├──────────────────────────────────────────────┤
│ 背景            颜色 / 材质                    │
└──────────────────────────────────────────────┘
```

**唯一例外**：`Slider` / `Toggle` 的**交互态**（旋钮在拖动/切换瞬间）可以使用 Liquid Glass。

### 1.1 本工程的判决

| 现有做法 | 判定 |
|---|---|
| `YuNianGlassCard` 把 12 个屏幕的卡片做成玻璃 | ❌ 违反"内容层禁止 Liquid Glass"，**整片违规** |
| `YuNianLiquidTabs` 手搓底部导航栏 | ❌ 违反 HIG `tab-bars`：**禁止自绘底部导航栏** |
| `YuNianGlassPage` 自绘顶栏 + `.safeAreaInset` | ❌ 双重违规：自绘栏背景 + 用错注册 API |
| 聊天气泡 / 列表行使用玻璃 | ❌ 内容层 |

**结论**：不是"玻璃没调好"，是**玻璃用错了图层**。这一点无论怎么重写视觉层都必须先立住。

---

## 2. 硬规则合并表

`hig.md` 25 条 + `liquid-glass.md` 16 条，去重与冲突裁决后如下。

### 2.1 材质与图层（最高优先级）

| # | 规则 | 类型 | 依据 |
|---|---|---|---|
| M1 | **内容层（列表 / 卡片 / 气泡 / 设置行 / 背景）禁止 `.glassEffect()`** | 禁止 | HIG `materials` |
| M2 | **禁止自绘底部导航栏**；导航用系统 `TabView` | 禁止 | HIG `tab-bars` |
| M3 | 系统栏（`.toolbar` / `TabView`）**禁止**加 `.toolbarBackground` 或自绘模糊背景 | 禁止 | HIG `toolbars`、`Adopting` |
| M4 | 若确需自绘 bar，注册滚动边缘效果**必须用 `.safeAreaBar(edge:)`**，**不得用 `.safeAreaInset`** | 必须 | `View.safeAreaBar` |
| M5 | 控制与内容的区分必须靠 **scroll edge effect**，不得用半透明色块伪造 | 必须 | HIG `layout`、`scroll views` |
| M6 | 全屏背景必须延伸到 TabBar / 工具栏**之下** | 必须 | HIG `layout` |
| M7 | `.glassEffect()` **必须写在所有影响外观的 modifier 之后** | 必须 | `glassEffect(_:in:)` |
| M8 | 多个玻璃元素**必须**包在同一 `GlassEffectContainer` 内 | 必须 | `Applying Liquid Glass` |
| M9 | `GlassEffectContainer(spacing:)` **必须 ≤ 内部 HStack/VStack 间距**（否则静止态就融合成一团） | 必须 | `Applying Liquid Glass` |
| M10 | 一屏内 `GlassEffectContainer` ≤ 2；容器外 `.glassEffect()` 尽量为 0 | 必须 | 性能 |
| M11 | `.tint` / `.interactive` **必须带括号**（是方法，不是属性） | 必须 | `Glass` 类型 |
| M12 | tint 只加在**单个主 CTA 或状态指示**上；禁止批量给背景上色 | 限制 | HIG `color` |
| M13 | `.clear` 变体仅用于媒体背景之上，且**必须自加调暗层**（亮背景约 35% 暗色不透明度） | 限制 | `Glass.clear`、`materials` |

### 2.2 形状与布局

| # | 规则 | 类型 | 依据 |
|---|---|---|---|
| S1 | 圆角用 **`ConcentricRectangle`** / `.rect(corners:isUniform:)`；禁止硬编码 `cornerRadius` | 必须 | `ConcentricRectangle` |
| S2 | 栏内控件圆角必须与栏**同心** | 必须 | HIG `toolbars` |
| S3 | `ConcentricRectangle` 远离容器角的角半径会被算成 0，需要 `concentric(minimum:)` | 必须 | `ConcentricRectangle` |
| S4 | `.rect(corners:isUniform:)` 默认 `isUniform: false`，四角半径可能不同 | 注意 | `Shape.rect` |

### 2.3 控件

| # | 规则 | 类型 | 依据 |
|---|---|---|---|
| C1 | 按钮用 `.buttonStyle(.glass / .glassProminent / .glass(_:))`；**禁止自绘玻璃按钮** | 必须 | `PrimitiveButtonStyle` |
| C2 | 每屏 prominent 按钮 ≤ 2；工具栏主行动唯一且在 trailing | 必须 | HIG `buttons`、`toolbars` |
| C3 | **破坏性操作禁止**使用 prominent / primary 样式 | 禁止 | HIG `buttons` |
| C4 | 命中区 ≥ 44×44pt；绝对下限 28×28pt 且需 ≥12pt 间距 | 必须 | HIG `buttons`、`accessibility` |
| C5 | 工具栏分组 ≤ 3 组；标题 ≤ 15 字符且**不得用 App 名** | 必须 | HIG `toolbars` |
| C6 | 可见 Tab ≤ 5；**不得禁用或隐藏 Tab** | 必须 | HIG `tab-bars` |

### 2.4 字体与颜色

| # | 规则 | 类型 | 依据 |
|---|---|---|---|
| T1 | **禁止**用固定字号 `Font.system(size:)` 排正文（破坏动态字体） | 禁止 | HIG `typography` |
| T2 | **禁止** Light / Thin / UltraLight 字重 | 禁止 | HIG `typography` |
| T3 | **禁止**打包 SF 系统字体 | 禁止 | HIG `typography` |
| T4 | **即使只做单一外观模式，也必须同时提供浅色 + 深色（+ 高对比）三套颜色** | 必须 | HIG `color` |
| T5 | 深色模式自定义色：**正文与次级文字 ≥ 7:1**；三级元信息 ≥ **4.5:1**（WCAG AA 正文下限） | 必须 | HIG `dark mode` + WCAG AA |
| T6 | **禁止**只靠颜色传达状态 | 禁止 | HIG `color`、`accessibility` |

> T4 对本工程是硬伤：现有 `YuNianTheme` 是**暗色优先**，浅色方案长期未被检验。

### 2.5 无障碍

| # | 规则 | 类型 | 依据 |
|---|---|---|---|
| A1 | `accessibilityReduceTransparency == true` 时，半透明背景**必须变不透明** | 必须 | `accessibilityReduceTransparency` |
| A2 | `Reduce Motion` 为真时，**必须去掉**弹簧 / morph / 进出场模糊动画 | 必须 | HIG `accessibility` |
| A3 | 材质在无障碍设置下会降级 —— 用系统组件则自动适配，自绘则要自己实现 | 必须 | `Adopting`、`materials` |

### 2.6 图标

| # | 规则 | 类型 | 依据 |
|---|---|---|---|
| I1 | 图标必须是**未遮罩的 1024×1024 分层源文件**；不得预切圆角、不得预加高光/投影 | 必须 | HIG `app-icons` |
| I2 | **禁止**图标内含文字或 Apple 硬件图形 | 禁止 | HIG `app-icons` |
| I3 | 用 **Icon Composer** 组合与预览（系统负责遮罩、模糊、反射、折射、投影、高光） | 必须 | `Adopting` |

---

## 3. 历史违规清单（**代码已删除，此表仅作重写依据**）

> ⚠️ 下表描述的是**已被整体废弃的旧视觉层**（`Views/` + `Theme/` 共 27 文件 / 5,733 行，
> 第 202 轮删除）。**这些类型在仓库里已不存在**，保留此表是为了回答
> 「当初为什么要重写」以及「哪些坑不能再犯」。
> 归档位置：`D:\Project\YuNian-iOS-visual-archive-*`，另可从 git HEAD `cd54ede` 取回。

| 违规 | 规则 | 严重度 |
|---|---|---|
| 12 个屏幕的内容卡片用玻璃（`YuNianGlassCard` → `yuNianGlass`） | M1 | **架构级** |
| 手搓底部导航栏（`YuNianLiquidTabs`，252 行） | M2 | **架构级** |
| 自绘顶栏 + 自绘背景（`YuNianGlassPage`） | M3 / M4 | **架构级** |
| 自绘栏用 `.safeAreaInset` 而非 `.safeAreaBar` | M4 | 高 |
| 大量硬编码圆角（`Radius` 枚举 14 个数值） | S1 | 中 |
| 暗色优先，浅色方案未经检验 | T4 | 高 |
| 三级文字对比度曾为 1.41:1（已修，但仍需按 ≥7:1 复核） | T5 | 已部分修复 |
| 大量 `Font.system(size:)` 固定字号 | T1 | 中 |

---

## 4. 与 `DESIGN.md` 的关系

| 文档 | 回答的问题 |
|---|---|
| **本文档** | **Apple 允许什么、要求什么** —— 不可协商 |
| [`DESIGN.md`](DESIGN.md) | 本产品要什么 —— 在本文档边界内的自主决策 |

**冲突时以本文档为准。** 任何与 Apple 约束冲突的"产品偏好"，都必须改产品偏好，而不是绕过约束。

---

## 5. 使用方式

1. 写任何 UI 代码前，先过 §2 的对应小节。
2. 每屏完成后，用 `hig.md` 文末的 25 条 checklist + `liquid-glass.md` §9 的 16 条速查复核。
3. **发现本文档与 Apple 原文不符时，改本文档**，并同步更新 `_apple-docs/` 下的凭证 —— 不得在代码里悄悄偏离。
