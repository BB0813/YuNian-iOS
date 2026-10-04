# YuNian iOS

予念的 iOS 端 —— **路线 B：SwiftUI 原生壳 + 复用 Rust Agent**。

原 Android 仓库见 <https://github.com/Sylvara-Lin/YuNian>（公开）。

## 目录结构

```
ios/                  Swift 工程（SwiftUI + GRDB + UniFFI 绑定）
  YuNian/             业务代码（Data / Agent / Platform / Security / Views）
  YuNianTests/        单元测试
  YuNian/Resources/   Info.plist / Assets.xcassets / SecuritySeed.json（加密）
  Tools/              33 道本地验证关卡（Python）
  Generated/          uniffi-bindgen 产出的 Swift 绑定（CI 生成，不入库）
agent-native/         Rust Agent crate（UniFFI 0.29.5，crate-type 含 staticlib/cdylib）
scripts/              build_agent_ios.sh 一键构建脚本
.github/workflows/    CI：Rust→iOS 交叉编译 + Swift 编译 + 33 道关卡 + 单测

core/ feature/        ⚠️ 仅保留 iOS 验证关卡所引用的**契约源文件**（约 16 个）。
                      它们不是 Android 工程的一部分，而是「权威源」——
                      schema JSON、过滤器规则、签名 payload 格式、工具定义等。
                      删掉它们，14 道关卡就失去对照基准。
                      原仓公开，故保留这些文件不构成额外暴露。
```

## 构建

macOS + Xcode 16+：

```bash
./scripts/build_agent_ios.sh
cd ios && xcodegen generate && xcodebuild build -scheme YuNian -sdk iphonesimulator CODE_SIGNING_ALLOWED=NO
```

详细步骤、失败判读、该回报什么：见 `ios/M0-RUNBOOK.md`。
