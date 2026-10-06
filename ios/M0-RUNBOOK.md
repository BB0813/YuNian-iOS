# M0 实跑 Runbook —— 首次在 macOS 上构建与测试

> **这份文档的目的**：让有 Mac 的人用一次运行，带回**最大信息量**。
> 每一步都写了「预期结果」「失败说明什么」「该回报什么」。
> 不要跳过步骤 —— 前面的失败会让后面的报错变得难以解读。

## 0. 前置条件

| 项 | 要求 | 检查 |
|---|---|---|
| 操作系统 | macOS 14 或更高 | `sw_vers` |
| Xcode | 16+（含 iOS 17+ SDK） | `xcodebuild -version` |
| XcodeGen | 已安装 | `xcodegen --version` |
| Rust | stable（含 `aarch64-apple-ios` target） | `rustc --version` 与 `rustup target list --installed` |
| 网络 | 需访问 crates.io 与 github.com（GRDB SPM） | — |

```bash
xcodebuild -version        # 记下版本
xcodegen --version
rustc --version
```

**如果 Rust 没装 iOS target**：

```bash
rustup target add aarch64-apple-ios aarch64-apple-ios-sim
```

## 1. 构建 Rust Agent（最可能失败的一步）

```bash
cd <repo-root>
./scripts/build_agent_ios.sh
```

**预期**：产出 `ios/Generated/LianyuAgent.swift` 与
`ios/Frameworks/lianyu_agent.xcframework`，脚本退出码 0。

**失败说明什么**：
- `error[E0432]: unresolved import` → Rust 侧平台适配问题（**我没在本机验过 Rust 编译**）
- `crate-type` / 链接错误 → `uniffi.toml` 的 cdylib 配置
- `rustup target not installed` → 上面那条命令没跑

**回报什么**：完整输出（前 50 行 + 最后 30 行）。这是**整个流程里信息量最高的一段**。

## 2. 生成 Xcode 工程

```bash
cd ios && xcodegen generate
```

**预期**：生成 `YuNian.xcodeproj`，无警告缺失文件。

**失败说明什么**：
- `Unable to find file ...` → `project.yml` 引用了不存在的路径
- package 解析失败 → GRDB SPM 需要网络；离线会卡住

## 3. 编译（关键：先不签名）

```bash
xcodebuild build \
  -project YuNian.xcodeproj \
  -scheme YuNian \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO
```

**预期**：`** BUILD SUCCEEDED **`

**这是最可能暴露问题的一步**，且**我完全无法预判** —— 7,000 行 Swift 从未编译过。

**失败说明什么**（按我看到过的风险排序）：

| 报错形态 | 最可能的原因 |
|---|---|
| `Cannot find type 'AgentHost'` / 类似 | 我引用了不存在的自有类型（第 50 轮抓到过 1 个，修了一个，可能还有） |
| `Cannot find 'X' in scope` | 同名类型/函数在别处定义或缺失 import |
| `Row` 解码 / `DatabaseValueConvertible` 相关 | GRDB 的 `as Bool?` 等转换不在内建集（我已核对过 77 处，用的是内建类型） |
| `@MainActor` / `Sendable` / 并发隔离 | `SWIFT_STRICT_CONCURRENCY: minimal` 下应为**警告**，不挡构建；若为错误请回报完整文本 |
| GRDB API 形态不符 | 我对 GRDB 6.29 的用法基于资料，未实跑 |
| SwiftUI 视图 body 相关 | 同上 |

**回报什么**：**前 20 个不同的错误**（不是全部 —— 第一个错误往往引发后续连锁）。
每条要带文件名与行号。

## 4. 跑单元测试

```bash
xcodebuild test \
  -project YuNian.xcodeproj \
  -scheme YuNian \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO
```

**预期**：10 个测试文件全部通过。已知覆盖：

| 测试文件 | 覆盖 |
|---|---|
| `MessageSearchTokenizerTests` | 中文分词金标向量 |
| `AgentDTOsTests` | settings / credentials / history 的键契约 |
| `ContentFilterTests` | 过滤词表金标向量（依赖 test bundle 里的 `SecuritySeed.json`） |
| `DialogueHistoryPolicyTests` | 历史清洗 13 组边界 |
| `SkillContentParserTests` | frontmatter 解析 19 组边界 |
| `AgentStoresTests` | Rust 数据来源的 JSON 映射 + 过滤 |
| `DeviceToolsTests` | 设备工具：星期映射（差一陷阱）/ 电量取整 / scheme 白名单 / 参数提取 |
| `SkillChainTests` | 种子 → discover → loadContent 全链路 |
| `BackupImporterTests` | 备份导入：按 name 重映射 ID / deviceId 改写 / anchorMessageId 二阶段 / 重复导入幂等 / 群消息系统哨兵 |
| `BackupCryptoTests` | `.lybk` 容器：magic 校验 / 截断拒绝 / PBKDF2 密钥与夹具一致 / 端到端解密 / 错误密码 |
| `WorldbookRuntimeSyncTests` | 世界书合成：priority 回落 / 去重并列取后者 / 排序与重编号 / 禁用与空白过滤 / map+array 双形态 / depth 条件 / 绑定规则 |
| `SecuritySeedLoaderTests` | 种子装载与诊断 |
| `RequestSignerTests` | path 编码 / clientId / nonce |
| `LiteralContractTests` | 字面量取值钉死 |
| `YuNianLiquidTabsTests` | 底部 Tab 的 Kotlin 几何规格：64dp 容器 / 4dp 内边距 / 2dp 图文字距 / 24-10-14 lens 半径 / tab 宽度与选中块高公式 |
| `ImageGenClientTests` | 生图纯逻辑：4 级参数回退序列与 distinct / 参数类错误判定 / 生图模型启发式（hint 命中且 exclude 不命中）/ base 候选规范化 |
| `ImageGenTriggerTests` | 生图触发判定：判定顺序（总开关→配置→关键词→概率→冷却）/ 概率严格 `<` 边界（roll 29 中 30 不中）/ 冷却三条边界（含"正好等于"不冷却）/ override 成对覆盖且无权动冷却 / parseKeywords 三分隔符保序去重 / buildPrompt 四级优先级与 800 截断 |
| `ContentForModelTests` | 图片消息回喂模型的系统注记：**逐字**对齐 Kotlin 措辞（BUG-1 教训）/ 无 prompt 或纯空白时原样 / 非图片消息不变 |

**失败说明什么**：
- 大量 `Cannot find 'X' in scope` → 第 3 步其实没成功，先修编译
- `SecuritySeedLoader` 相关失败 → test bundle 资源装载仍有问题（第 31 轮修过一次，可能不彻底）
- 金标向量失败 → **跨端不一致**，最严重，请把失败断言原文发回

## 5. 模拟器手动验证（功能冒烟）

```bash
# 先跑通 1–4，再手动开 app
open YuNian.xcodeproj   # 或第 3 步之后直接在模拟器里运行
```

按顺序验证，**每步的结果都回报**：

| # | 操作 | 预期 | 失败含义 |
|---|---|---|---|
| 1 | 启动 app | 自检面板显示 schema v45 / FTS 版本 / 默认伴侣 / 设备签名 | 启动崩溃 |
| 2 | 自检面板「数据层」 | `表/索引/外键` 计数非零，FTS 解析出 `fts4`/`fts5` | 引导逻辑问题 |
| 3 | 「伴侣」区 | 显示默认伴侣（小鱼）而非「缺失」 | `DefaultCompanionSeeder` 未生效 |
| 4 | 「凭证」区选 provider + 填 key，保存 | `credentials` 摘要变为「已配置」 | `setAPIKey` / `ensureActiveApiConfig` 失败 |
| 5 | 「进入对话」发一条消息 | 出现打字机效果，最终落地一条回复 | **核心链路**，见下 |
| 6 | 「记忆管理」 | 列表（可能为空）+ 计数 | — |
| 7 | 「技能库」 | 列出「内置聊天工具协议技能」，点进去能看到正文 | 第 39 轮的种子问题 |
| 8 | 「表情标签」区 | 显示标签数与具体标签（**全新安装下为空是正常的**，两端一致） | — |
| 9 | 「凭证」区换 provider 后保存 | `credentials` 摘要更新；对应 api_configs 行已写入 | 第 40 轮的缺口 |
| 10 | 「凭证」区点「测试连接」 | 显示「连接成功」或带原因的失败文案 | `ApiProbe.testOpenai/Anthropic` 未接（第 94–96 轮补的） |
| 11 | 「凭证」区点「拉取模型列表」 | 列出模型名（可选中复制）；服务端不支持 `/models` 时会说明 | `ApiProbe.fetchModels`（第 95 轮） |
| 12 | 「备份导入」 | 选 `.lybk` 文件 → 输密码 → 看到「导入成功 + 各分区条数」 | V8 整条链（第 121 轮补 UI）；密码错时会明确说「解密失败」而非空响应 |

**第 5 步是重点**。它一次验证：orchestrator 注入、settings/credentials 下发、
`load_companion`、历史清洗、LLM 请求、SSE 流式、`emit_bubble` 协议。
失败时请回报：自检面板当时的 `settings` / `credentials` 摘要截图，
以及界面上的错误文本（`ChatView` 会把 Rust 的错误显示出来）。

**第 5 步若涉及「打开网页 / 发通知」**（模型调用 `device_open_url` / `device_notify`）：
这两个工具用信号量把 iOS 的异步 API 桥接成同步返回值。
**若出现约 10 秒卡顿，就是这个桥接的死锁假设不成立** ——
详见 `DeviceTools.swift` 的注释，请把卡顿时长回报。

## 6. 回报的最小信息集

如果回合行为异常（该回复时没回复、回了一半、工具没执行），
**先看设备日志里的 `agent.stream` 与 `agent.tool` 分类** ——
`AgentStreamSinkImpl` / `AgentToolHostImpl` 已用 `os.Logger` 打了关键节点。

若仍无法定位，在 `ChatSession.send` 的 `runTurnStream` 返回处临时加一行：

```swift
log.error("turn outcome: \(String(describing: outcome))")
```

（`roundsUsed` / `finishedReason` / `error` 三个字段足够定位多数问题。）

⚠️ **这不是完整方案**：Android 侧每回合会写一条 `agent_dispatch_log`
（含 provider/model/起止时间/工具调用明细/事件流），iOS 侧尚未移植 ——
见 `docs/turn-path-parity.md` 第 91 轮的记录与理由。

如果只能回报一样东西，**回报第 3 步的前 20 个编译错误**。

如果全部通过，请回报：
1. `xcodebuild -version` 输出
2. 每个步骤的退出码
3. 第 5 步的结果（哪怕失败 —— 错误文本）
4. `xcodebuild test` 的 `Test Suite 'All tests' ... executed N tests, with 0 failures`
   那一行

---

## 附：已知的、**预期内**的告警（不要当成新问题）

- `DEAD_CODE_STRIPPING: NO` 与 `-lc++` 是为 Rust 静态库刻意设置的
- `SecureEnclave` 在模拟器上显示「软件回退」—— 模拟器没有 SE，正常
- 「表情标签」为空 —— 第 42 轮确认这是全新安装的正常状态
- 领域工具未注入（模型没有记忆/技能之外的领域工具）—— 已记录缺口，不影响上述步骤
- 编译期的 `Sendable` / 并发警告 —— `minimal` 严格度下预期出现
