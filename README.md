# YuNian iOS

予念的 iOS 端 —— **路线 B：SwiftUI 原生壳 + 复用 Rust Agent**。

原 Android 仓库见 <https://github.com/Sylvara-Lin/YuNian>（公开）。

> **只想装上用** → 直接看 [装到自己的 iPhone 上（自签教程）](#装到自己的-iphone-上自签教程)。
> **想下载** → [最新 Release](https://github.com/BB0813/YuNian-iOS/releases)（当前 **Beta 2**）。
> **想自己编译** → 看 [自己构建（不需要 Mac）](#自己构建不需要-mac) 与 [`ios/M0-RUNBOOK.md`](ios/M0-RUNBOOK.md)。
> **想知道还差什么** → [`ios/BACKLOG.md`](ios/BACKLOG.md)：全部待开发 / 做了一半 / **决定不做**的事，
> 每项带成本与验收方式（单测能覆盖还是必须真机），有数据风险的单独标出。
>
> ⚠️ **接手的同学注意**：这是 **iOS 仓库**（`YuNian-iOS`）。
> 另有一个 Android 仓库 `YuNian`，两边目录名相似，别在错的那边找 `ios/` 下的文件。

---

## 装到自己的 iPhone 上（自签教程）

### 为什么必须自签

Apple 只允许**已签名**的 App 安装到 iPhone 上，而签名必须绑到**你自己的 Apple ID**
（或开发者账号）—— 我们无法替你签，任何人都不能。

所以 CI 的产物是**未签名 IPA**：它包含完整的 App 本体，只差最后一步签名。
你用**自己的** Apple ID 签一次就能装，**免费 Apple ID 完全够用**（代价是 7 天有效期，可续签）。

- 工程最低系统版本：**iOS 17.0**（`minos 17.0`）
- **下载 IPA（首选）**：[Releases](https://github.com/BB0813/YuNian-iOS/releases) → 最新一版
  （当前 **Beta 2**）→ 下载 **`YuNian-unsigned.ipa`**。Release 里的产物**长期保留**。
- **下载 IPA（想拿最新构建）**：本仓库 → **Actions** → 最近一次成功的 `iOS Agent`
  → 页面底部 **Artifacts** → **`YuNian-unsigned-ipa`**（只保留 14 天）
- 装好后可在 App 内 `我 → 关于` 核对版本：**Beta 2 对应 `1.0.0 (2)`**
  （Release 标签 `v1.0.0-ios-beta.2` ↔ App 内 `1.0.0 (2)`，两者对齐）

### 准备（三条路线都需要）

| 需要 | 说明 |
|---|---|
| Windows 10/11 或 macOS | 也支持 Linux，但工具链最少的是 Windows |
| 数据线 | 尽量用原装线、直连电脑（不经 hub） |
| 一个 Apple ID | **免费即可**；开了双重认证也没问题 |
| ⚠️ Apple 官网版 iTunes / iCloud | Windows 上**必须**用官网版，**不要**用 Microsoft Store 版 —— 后者会让工具找不到设备 |

---

### 路线 A：Windows + Sideloadly（最省事，推荐）

1. **装 Apple 官网版 iTunes**：<https://www.apple.com/itunes/download/win64>
   装完**打开一次**（让 Apple 的 USB 驱动就位），然后关掉。
   iCloud 同样装官网版（Apple 官网搜索 “iCloud for Windows 下载”，**别装商店版**）。
2. **装 Sideloadly**：<https://sideloadly.io/> → Windows 64-bit
   直链：<https://sideloadly.io/SideloadlySetup64.exe>
3. **下载未签名 IPA**：按上面的路径从 Actions 取 `YuNian-unsigned-ipa`，解压得到 `.ipa`。
4. **连线并信任电脑**：iPhone 插上 → 手机上点「信任此电脑」。
5. **签并安装**：打开 Sideloadly → 把 `.ipa` 拖进窗口 → 顶部 Apple ID 填你自己的
   → 设备选到你的 iPhone → **Start**。中途会问一次 Apple ID 密码（只用于向 Apple 换取签名，
   不会上传到别处）。
6. **在手机上信任开发者**（**不做这步 App 一打开就闪退**）：
   `设置 → 通用 → VPN 与设备管理 → 开发者 App → 选你的 Apple ID → 信任`。
7. **打开 App**。免费账号签出来的有效期 **7 天**；到期后 App 图标还在但打不开 ——
   重新走第 5 步即可（Sideloadly 自带自动续签：保持电脑与手机在**同一 Wi-Fi** 下，它会在后台重签）。

> 💡 Sideloadly 里可以改 Bundle ID（**Change Bundle ID**）。如果你之前签过同名 App、
> 或者要同时装两个版本，改一个唯一的 Bundle ID 最省事。

---

### 路线 B：Windows / macOS + AltStore（带自动续签）

1. 装 Apple 官网版 iTunes + iCloud（Windows，同上）。
2. **AltServer**：<https://altstore.io/> → Downloads → **AltStore Classic**
   - Windows：`altinstaller.zip`（要求 Windows 10+）
   - macOS：`altserver.zip`（要求 macOS 11+）
3. 运行 AltServer → 托盘/菜单栏图标 → **Install AltStore** → 选你的 iPhone → 输入 Apple ID 密码。
4. 手机上 `设置 → 通用 → VPN 与设备管理` → 信任 **AltStore**。
5. 把 `.ipa` 传到手机（AirDrop / iCloud Drive / 「文件」App），在 AltStore 里
   **My Apps → +** 选那个 `.ipa`，用同一个 Apple ID 签并安装。
6. 之后 AltStore 会在**同一 Wi-Fi** 下自动续签，不用每 7 天手动重装。

---

### 路线 C：有 Mac 时用 Xcode

本仓库**没有** `.xcodeproj`（工程是 SwiftPM + xtool 适配形态），所以 Mac 上要先生成工程：

```bash
# 1) 先生成 Rust Agent 的 xcframework（否则链接会失败）
./scripts/build_agent_ios.sh

# 2) 生成 Xcode 工程
cd ios && xcodegen generate && open YuNian.xcodeproj
```

然后在 Xcode 里：选中 target `YuNian` → **Signing & Capabilities** →
勾 `Automatically manage signing` → **Team 选你自己的 Personal Team** →
把 **Bundle Identifier 改成唯一值**（例如 `com.你的名字.yunian`）→ 选你的 iPhone → **Run**。
首次运行后同样要去 `设置 → 通用 → VPN 与设备管理` 信任一次。

> Mac 上需要 [XcodeGen](https://github.com/yonaskolb/XcodeGen)（`brew install xcodegen`）。

---

### 路线 D：不想一直插线？

- **[SideStore](https://sidestore.io/)**：初次需要电脑生成配对文件，之后同 Wi-Fi 自签，不必常插线。
- **[TrollStore](https://github.com/opa334/TrollStore)**：**永久签名**，但**只适用于存在相应漏洞的旧系统**
  （特定 iOS 14–17.x 版本）。新系统用不了，不要照抄教程硬装。

### 免费账号 vs 付费账号（$99/年）

| | 免费 Apple ID | 付费开发者账号 |
|---|---|---|
| 签出来的 App 有效期 | **7 天**（需续签） | 1 年 |
| 同时可装的自签 App 数 | 3 个 | 基本不受限（受账号的设备数上限约束） |
| App ID 配额 | 每 7 天 10 个 | 基本不受限 |
| TestFlight 分发 | ✗ | ✓ |

官方对比：<https://developer.apple.com/support/compare-memberships/>

### 常见问题

| 现象 | 原因与解决 |
|---|---|
| 工具里**找不到设备** | 装的是 Microsoft Store 版 iTunes/iCloud → 卸载，装 Apple 官网版；换原装线、直连电脑 |
| 装完**一打开就闪退** | 没信任开发者：`设置 → 通用 → VPN 与设备管理 → 开发者 App → 信任` |
| 用了一周后**打不开** | 免费账号 7 天到期 → 重新签一次（路线 B 会自动续签） |
| 报 **provisioning profile / App ID 上限** | 免费账号每周 App ID 配额用完了 → 等一周，或删掉不用的自签 App |
| 装不上第二个 / Bundle ID 冲突 | 在签名工具里把 **Bundle ID 改成唯一值** |
| 提示系统版本过低 | 本 App 需要 **iOS 17.0+** |
| 用免费账号**会不会封号** | 用**自己的** Apple ID 给**自己的**设备签**自己的** App 是 Apple 允许的用法。风险来自拿别人的证书/账号签、或拿去分发 |

### 合规声明

本仓库**不包含**任何分发证书、私钥或描述文件（CI 也只产出**未签名** IPA）。
上面所有签名都由**你自己的** Apple ID 完成，产物只有你能安装。
请不要传播他人的签名证书，也不要用本项目的产物做商业分发。

---

## 自己构建（不需要 Mac）

本项目在 **Windows + WSL2(Arch) + xtool** 上完成构建、签名与真机安装，全程无 Mac。

```bash
# 在 WSL 里
bash scripts/build_agent_ios.sh          # 交叉编译 Rust Agent → lianyu_agent.xcframework
cd ios/omarchy-xtool && xtool dev build  # SwiftPM → YuNian.app / YuNian.ipa
```

用到的工具（点进去各有安装文档）：

| 工具 | 用途 | 链接 |
|---|---|---|
| **xtool** | 在 Linux/WSL 上替代 Xcode：构建、签名、安装 iOS App | <https://github.com/xtool-org/xtool> · 安装文档 <https://xtool.sh/documentation/xtooldocs/installation-linux> |
| **usbipd-win** | 把 iPhone 从 Windows 直通给 WSL2（`winget install --id dorssel.usbipd-win`） | <https://github.com/dorssel/usbipd-win> |
| **libimobiledevice / usbmuxd** | WSL 里与设备通信（`idevice_id` / `ideviceinfo`） | <https://libimobiledevice.org/> · <https://github.com/libimobiledevice/usbmuxd> |
| **ArchWSL** | WSL2 里的 Arch 发行版 | <https://github.com/yuk7/ArchWSL> |
| **omarchy-apple-dev** | 本项目 WSL2 + xtool 环境的来源（MIT） | <https://github.com/joshuaswarren/omarchy-apple-dev> |
| **XcodeGen** | 从 `project.yml` 生成 Xcode 工程（Mac 路线用） | <https://github.com/yonaskolb/XcodeGen> |

自签（见上一节）用到的工具：

| 工具 | 用途 | 链接 |
|---|---|---|
| **Sideloadly** | Windows/macOS 上用自己的 Apple ID 签并安装 IPA | <https://sideloadly.io/> |
| **AltStore / AltServer** | 带自动续签的自签方案 | <https://altstore.io/> |
| **SideStore** | 不必长期连电脑的自签 | <https://sidestore.io/> |
| **Apple 官网版 iTunes / iCloud** | Windows 上的 Apple 设备驱动与服务（**不要**用 Microsoft Store 版） | <https://www.apple.com/itunes/download/win64> |
| **TrollStore** | 仅旧系统可用的永久签名 | <https://github.com/opa334/TrollStore> |
| **开发者计划对比** | 免费账号与付费账号的差别 | <https://developer.apple.com/support/compare-memberships/> |

---

## 目录结构

```
ios/                  Swift 工程（SwiftUI + GRDB + UniFFI 绑定）
  YuNian/             业务代码（Data / Agent / Platform / Security / Views）
  YuNianTests/        单元测试
  YuNian/Resources/   Info.plist / Assets.xcassets / SecuritySeed.json（加密）
  Tools/              34 道本地验证关卡（Python）
  Generated/          uniffi-bindgen 产出的 Swift 绑定（CI 生成，不入库）
agent-native/         Rust Agent crate（UniFFI 0.29.5，crate-type 含 staticlib/cdylib）
scripts/              build_agent_ios.sh 一键构建脚本
.github/workflows/    CI：Rust→iOS 交叉编译 + Swift 编译 + 34 道关卡 + 单测 + 导出未签名 IPA

core/ feature/        ⚠️ 仅保留 iOS 验证关卡所引用的**契约源文件**（约 16 个）。
                      它们不是 Android 工程的一部分，而是「权威源」——
                      schema JSON、过滤器规则、签名 payload 格式、工具定义等。
                      删掉它们，14 道关卡就失去对照基准。
                      原仓公开，故保留这些文件不构成额外暴露。
```

## 构建（macOS + Xcode 16+ 的传统路线）

```bash
./scripts/build_agent_ios.sh
cd ios && xcodegen generate && xcodebuild build -scheme YuNian -sdk iphonesimulator CODE_SIGNING_ALLOWED=NO
```

详细步骤、失败判读、该回报什么：见 [`ios/M0-RUNBOOK.md`](ios/M0-RUNBOOK.md)。
