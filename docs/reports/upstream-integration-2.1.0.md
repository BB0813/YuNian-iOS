# 上游 v2.1.0 整合与 Cordis 重写验收记录

> 本批目标：把 `Sylvara-Lin/YuNian` 的 `master` 稳定性与 Agent 核心改进整合进当前工作区，
> 但**不直接 merge/cherry-pick**——上游部分实现绕开了本仓库既有的 Cordis 插件化架构，需按本仓库架构重写。

## 1. 来源与版本决策

| 项 | 值 |
|----|----|
| 基线 | 当前 `yunian/main` = `51772c4a`（消息通道插件化那批的提交） |
| 参考 | `yunian/master` = `7453969e`（领先 25 个提交） |
| 版本 | `versionCode 27` / `versionName "2.1.0"`（原 26 / "2.0.2"） |
| 提交策略 | 重写完成后**单一整合提交**，不搬运上游提交历史 |

版本号同步点：`app/build.gradle.kts`、`feature/settings/.../AppUpdateManager.kt`（两处兜底版本串）、`README.md` badge。

## 2. 架构重写：工具活动卡片

这是本批**唯一需要重写而非搬运**的部分，也是用户明确点名的要求。

### 上游做法（未采纳）

上游在 `core:agent` 里新增 `ToolCallProgress.kt`，由 `AgentToolHost` 通过回调**直连** UI 层。
问题：Agent 核心（core 层）反向依赖了 UI 关注点，绕开 Cordis 事件总线，也不进插件生命周期。

### 本仓库做法（本次实现）

```
AgentToolHost.execute()
  └─ withToolLifecycle(reporter, toolName) { ... }   ← 必然产出配对事件
       └─ ToolLifecycleReporter.started()/finish()
            └─ PluginEventPublisher.emit(key, payload)   ← core:domain 契约
                 └─ PluginHostImpl.emit() → PluginEventBus（宿主级共享总线）
                      └─ ChatToolLifecyclePlugin（feature:chat，随回合装卸）
                           └─ ChatToolLifecycleProjection → ToolActivity → UI + 落库
```

新增件：

- `core/domain/.../plugin/EventKey.kt`：`EventKey<T>` + `PluginEventPublisher` + `PluginContext.on/emit` 扩展；
- `core/domain/.../plugin/ToolLifecycleEvents.kt`：`tool.started` / `tool.finished` 两个事件与载荷；
- `core/agent/.../host/ToolLifecycleReporter.kt`：发布端，`finish` 幂等，发布包在 `runCatching` 内；
- `feature/chat/.../plugin/ChatToolLifecyclePlugin.kt`：订阅端插件，`kind = PIPELINE`，每回合独立实例 id。

`PluginHostImpl` 实现 `PluginEventPublisher` 而**不新增任何全局/静态总线**——
事件仍然只走宿主持有的那一条总线，装载/卸载沿用既有 effect 逆序退订语义。

### 隐私契约

事件载荷**刻意不携带**工具参数、结果、上下文、异常文本，只带 `streamId/callId/toolName/status/时间戳`。
单测用反射钉死字段集合，并端到端断言敏感文本不出现在事件里；后续若有人给载荷加字段会立刻红灯。

## 3. 逐项交付

| # | 项 | 落点 |
|---|----|------|
| 1 | 数据库防数据丢失 | `core/database/DatabaseRecoveryPolicy.kt`（新增）+ `AppDatabase.kt`；`version = 45`、`SCHEMA_FROZEN_VERSION = 41` 保持不变 |
| 2 | 群聊崩溃修复 | `feature/groupchat/GroupChatPager.kt` + `GroupChatViewModel.kt` |
| 3 | 停用 HMS 半成品 | `app/AndroidManifest.xml` 移除活跃 service 声明；`PushManager` / `PushMessageDispatcher` / `HuaweiPushInitializer` 收敛；shell manifest 关闭 HMS 自动初始化 |
| 4 | 崩溃日志与 Rust panic 诊断 | `core/common/crash/`（新增）、`app/src/shell/ShellCrashHandler.java`、`MainActivity`、`StaticApkShell`、`YuNianApplication` |
| 5 | 角色串线剥离 | `core/common/ScriptTurnStripper.kt`（新增），覆盖单聊、群聊、通知 Worker 全部落库路径 |
| 6 | UniFFI 回调防崩 | `core/agent/AgentRequestSigner.kt` |
| 7 | LLM 重试 / 取消 | `agent-native/src/retry.rs`（新增），接入 `native_gateway.rs` 两处真实请求点；Rust 侧新增 `cancel_current_turn` |
| 8 | 多轮叙述独立成泡 | `agent-native/src/agent.rs` |
| 9 | Rust 日志桥 | `agent-native/src/agentlog.rs`（新增）+ `core/agent/RustAgentLogBridge.kt`（新增） |
| 10 | 工具活动卡片 | 见第 2 节（Cordis 重写） |

### 明确排除（未纳入）

图片生成 UI 打磨、Shizuku/手机控制浮层、主动多轮气泡、上游的版本号提交。

## 4. 证据

### 4.1 原生层（重建，因为 Rust 接口有新增）

Rust 接口新增了 `cancel_current_turn`，而 UniFFI 的 `.so` 与生成的 `.kt` 必须同源，否则初始化即崩。
因此走完整重建流程（`scripts/build_agent.ps1 -GenBindings`）：

| 步骤 | 结果 |
|------|------|
| `cargo test` | **188 passed / 0 failed** |
| `cargo ndk --release` | 四 ABI 全部产出（arm64-v8a / armeabi-v7a / x86_64 / x86） |
| `uniffi-bindgen generate` | 绑定重生成，含 `cancelCurrentTurn`；行尾 LF 保持与仓库一致 |
| 同源性抽查 | arm64 `.so` 内含导出符号 `uniffi_lianyu_agent_fn_method_agentruntime_cancel_current_turn` |

### 4.2 Android 单元测试

| 模块 | Tests | Failures | Errors |
|------|-------|----------|--------|
| `core:agent` | 301 | 0 | 0 |
| `core:common` | 152 | 0 | 0 |
| `core:database` | 55 | 0 | 0 |
| `feature:chat` | 153 | 0 | 0 |
| `feature:groupchat` | 28 | 0 | 0 |
| **合计** | **689** | **0** | **0** |

### 4.3 打包与产物

| 项 | 结果 |
|----|------|
| `./gradlew assembleDebug` | **BUILD SUCCESSFUL**（732 tasks） |
| APK | `app/build/outputs/apk/debug/app-debug.apk`，106.54 MB |
| badging | `versionCode=27 versionName=2.1.0`，`native-code: arm64-v8a` |
| APK 内 `liblianyu_agent.so` | 含 `cancel_current_turn` 导出符号 |
| 新类进 DEX | `ToolLifecycleReporter` / `ChatToolLifecyclePlugin` / `ScriptTurnStripper` / `DatabaseRecoveryPolicy` / `CrashReporter` / `PluginEventBus` 均在 |

### 4.4 本轮补齐的测试

四个后台子代理在收尾前全部失败，未留下收口消息，因此整合层由调度者直接完成并补齐了两处此前缺失的测试：

- `core/agent/.../host/ToolLifecycleReporterTest.kt`（11 例）：用**真实** `PluginHostImpl` + 真实 `PluginEventBus`，
  断言 started/finished 配对且同 `callId`、四种终态映射、finish 幂等、卸载后不再投递、
  订阅者抛异常不影响工具返回值、无发布端时照常执行、载荷结构与隐私断言、时钟可注入。
- `feature/chat/.../plugin/ChatToolLifecycleProjectionTest.kt`（9 例）：断言插件订阅的是标准事件名、
  只认本回合 `streamId`、RUNNING→终态覆写保留工具名、乱序到达不丢卡片、保序、载荷结构钉死。

## 5. 红线核对

| 约束 | 结果 |
|------|------|
| `gradle.properties` / `gradle-wrapper.properties` / `libs.versions.toml` / `settings.gradle.kts` | 未改动（`git status` 为空） |
| `core/security/src/main/cpp` | 未改动 |
| `ContentFilter` | 未改动 |
| FGS / Worker / 心跳 / WebSocket 重连 / `connectMutex` / typing 时序 | 未改动 |
| QQ / 微信消息通道（`feature:qqbot` / `feature:wechat`） | 未改动 |
| Room schema | 版本与冻结版本未变 |
| 未授权的 merge / rebase / reset / stash / checkout / clean | 均未执行 |

**已验证不影响熄屏保活 / typing 时序 / 重连循环**（本批未触碰上述链路）。

## 6. 未关闭的缺口（如实说明）

1. **没有真机运行时验证**。全部证据止步于「Rust 单测 + Android 单测 + 编译出包 + 产物结构检查」。
   用户正在使用该设备，故未用 adb 驱动真机。以下均**未验证**：
   - 工具活动卡片在真实回合里是否按顺序渲染、落库后重进会话是否回显；
   - 崩溃日志与 Rust panic 诊断在真实崩溃下是否写入、`MainActivity` 是否正确消费；
   - 数据库恢复逻辑在真实 corruption 场景下的行为（仅 JVM 单测与 androidTest 源码，未在设备上跑 instrumented 测试）；
   - 角色串线剥离在真实模型输出上的观感；
   - HMS 停用后推送链路的实际表现。
2. **`cancelCurrentTurn` 的 Kotlin 调用点未接线**——这是上游**有意推迟**的（重试已限定 3 次/30 秒预算），
   且接线会触碰消息管线时序，属红线范围。本批只保证 Rust 能力存在且经单测覆盖，**不等于**「停止生成」按钮已生效。
3. `git diff --check` 对生成文件 `lianyu_agent.kt` 报行尾空白：这是生成器（`--no-format`）既有习性，
   HEAD 版本本就有 619 行同类空白，当前 625 行，非本次引入，故按仓库既有流程保持原样。
4. Rust 编译告警 `with_transport` / `with_retry` 从未使用：二者只被测试构造器使用，
   生产路径走 `RetryPolicy::default()`，属测试专用构造器，非功能缺口。

## 7. 安装与验收建议

安装并实际打开这个 debug APK，是关闭第 6 节缺口的唯一途径。建议按以下顺序主动验收：

1. 正常聊一次并触发工具调用 → 看是否出现工具卡片且顺序为「正文 → 工具」；
2. 杀掉进程重进会话 → 看工具卡片是否回显；
3. 人为触发一次崩溃（或等自然崩溃）→ 看崩溃日志入口是否有内容；
4. 群聊连续翻页 → 看是否还复现此前的崩溃。

## 8. 真机运行时验证（实施于 v2.1.0 装机后）

设备：vivo V2324A / Android 16 (SDK 36) / arm64-v8a，adb 就地覆盖安装（install -r -d → Success，无权限弹窗）。

### 8.1 已通过（含证据）

| 项 | 证据 |
|----|------|
| 版本与 ABI | badging `versionCode=27 versionName=2.1.0`、`native-code: arm64-v8a` |
| 冷启动洁净 | 该 PID 无任何 E/W 行、无 FATAL、无 UnsatisfiedLinkError |
| Cordis 插件装配 | `Blueprint default applied: loaded=[coffee.luckin, skill.builtin_chat_protocol, sticker.preference, automation.core, channel.qqbot, channel.wechat, message.send] skipped=[]` |
| Rust Cordis 核心存活 | `Core plugins: entries=[turn-observer Active, core-tools Active, core-prompt-fragments Active, turn-consolidation Active, ...]` |
| 通道插件注册 | `ChannelRegistryImpl` 注册 qqbot 与 wechat 适配器 |
| 升级不丢数据 | `user_version=45`、`integrity_check=ok`、升级前后 messages 305→305、message_bodies 305→305、`room_master_table.identity_hash` 未变 |
| 应用自建升级前备份 | `files/db_backup/backup_20261003_005432/yunian_database`（1,355,776 B） |
| FGS | `SService` 与 `QQBotForegroundService` 均 `isForeground=true`、`types=0x40000000`（SPECIAL_USE） |
| QQ 重连（覆盖安装后） | `[QQBotWS] WebSocket connected` → `Resume sent with sessionId=96413507-…, seq=40`（崩后重启继续 seq=41/42/43） |
| **工具卡片 Cordis 链路** | 3 个真实回合中 2 个各产出**恰好一条** TOOL_ACTIVITY（`id=313 → turn e1c1d311`、`id=320 → turn fe3615a7`），且都排在该轮最后一条助手消息之后；每个回合都能看到 `PluginHostImpl: loaded: chat.tool-lifecycle-cards.<streamId>` 与 `unloaded:` 精确包住全部工具执行窗口 |
| 工具名与状态配对 | 回合内 `AgentToolHost: tool call:` / `tool done: … ok=true` 与卡片一一对应（如 search_web 470ms ok=true） |
| **崩溃上报（真实崩溃）** | 见 8.2：`files/crash/crash_business.txt`（12,078 B）含 source/时间/线程/PID/设备/版本/异常类/异常消息/**完整堆栈**/面包屑 |
| 异常退出检测 | `files/crash/.business_alive`、`.last_exit_ts` 均被更新 |
| Rust 日志桥 | `databases/rust_agent_log.txt`（1,748 B，持续增长）+ `shared_prefs/rust_agent_log_bridge.xml` 记录 `offset=644`，证明按偏移增量转储可重入 |
| 崩溃后自愈 | 进程重启后蓝图全量重放 `skipped=[]`、QQ 以 seq=41→43 连续 RESUME、双 FGS 恢复前台 |

### 8.2 真实崩溃与崩溃上报

验证期间捕获到一次**未被捕获的真实崩溃**（非人工构造）：

```
FATAL EXCEPTION: DefaultDispatcher-worker-12   (PID 31760)
android.database.sqlite.SQLiteDiskIOException: disk I/O error
  (code 522 SQLITE_IOERR_SHORT_READ), while compiling: PRAGMA journal_mode
  at SQLiteConnection.setJournalMode / SQLiteConnectionPool.tryAcquireNonPrimaryConnectionLocked
  at androidx.room.RoomDatabase.useConnection
  at com.yunian.ai.database.dao.ApiConfigDao_Impl.getAllConfiguredConfigs
  Suppressed: DiagnosticCoroutineContextException: StandaloneCoroutine{Cancelling}, Dispatchers.IO
→ Sending signal. PID: 31760 SIG: 9
```

崩溃上报链路**工作正常**：报告含设备/版本/线程/异常类与消息/完整堆栈/面包屑，且崩溃后数据完好
（`integrity_check=ok`、messages 与 message_bodies 行数始终相等、伙伴与 API 配置均在，无丢失）。

**该崩溃的触发源是验证手段本身，须如实记录**：验证过程中对**活库**直接执行了 sqlite3 CLI 查询；
sqlite3 CLI 打开 WAL 库时会 checkpoint 并改动 -wal/-shm，App 已持有的连接池随后新建连接即
SQLITE_IOERR_SHORT_READ（同一错误类别在崩溃堆栈中被独立证实）。**已改用导出副本查询，不再触碰活库。**

### 8.3 未关闭的缺口（本轮新增，如实说明）

1. **偶发整轮零产出（未复现、未定位）**：某回合（turn `282d81f3`）在日志中记录
   `Agent turn completed in 8398ms, reason=completed, bubbles=1`，但界面**无回复气泡**（用户确认），
   库中也只有一条 REASONING——既无 TEXT、也无 TOOL_ACTIVITY；`archived_messages` 为 0 行，
   `Persist tool activities failed` / `AI response failed` 均未出现，即**没有任何错误路径被走到**。
   紧随其后的回合完全正常（产出 id=320 卡片）。工具卡片缺失是该轮「零产出」的**结果**而非独立缺陷：
   `persistToolActivities` 仅在活动列表为空时跳过，故该轮投影未被触发。
   **结论：症状确认，根因未定位，需专项排查。**
2. **运行期 DB I/O 错误会打死进程**：上述崩溃表明，Dispatchers.IO 协程中一次瞬时
   SQLiteDiskIOException 即可终止整个进程。本批的 `DatabaseRecoveryPolicy` 只覆盖**启动期**
   打开/迁移，运行期 I/O 错误不在其内，也没有为 DB 查询协程配置统一异常处理。
   这是**真实健壮性缺口**（触发源虽是外部 sqlite3 访问，但缺口本身成立）。
3. **仍未验证**：工具卡片的 **UI 渲染观感与重进会话回显**（仅验证了落库与消息流位置）；
   角色串线剥离在真实模型输出上的效果；HMS 停用后推送链路的实际表现；群聊连续翻页稳定性；
   真机上的 DB 真实 corruption 恢复（仍只有 JVM 单测）。
