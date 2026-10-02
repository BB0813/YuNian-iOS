# 技术债收尾与 Debug APK 验收记录

## 本轮执行约束
- 延续用户“先处理遗留技术债，再构建 debug apk”的请求。
- 并行修改互不重叠模块；子代理不运行 Gradle。全部修改结算后由调度者串行验证。
- 不使用旧 build-lock 脚本：基于文件删除和 PID 的接管存在竞态；旧冒烟测试还覆盖过共享锁，不能据此证明历史构建互斥。
- 不 checkout/reset/stash 工作区。既有未提交改动必须保留。
- 不操作用户正在使用的手机。APK 构建不等于真机验收通过。

## 修正此前证据结论
- 命中 category 注释不能证明整个文件已从 HEAD 回退中完整恢复。
- val AtomicInteger 禁止重赋引用，但不禁止 set；是否有重置应检查调用，不能只看 val。
- 凭据文件的部分结构指纹不足以证明整份文件无泄露风险；此前“确认无泄露”撤回。未继续输出疑似秘密，临时文件为可恢复移动。
- 旧 XML 是子代理既往测试证据，不替代当前源码的最终集成验证。
- 不以文件修改时间、Java 进程是否存在或锁文件是否存在单独断言子代理完成/构建互斥。

## 本轮任务
- 微信失败提示：核实剩余失败，清理无断言探针。
- 插件开关：检查真实装载状态、失败反馈及持久化失败提示。
- 微信分句：保留连续标点及合法省略号，防止切出碎片；清理 profile 孤儿资源。
- 蓝图状态：复核取消传播及部分失败记录。
- 文档：“零依赖”明确为零项目模块依赖。
- 全部结算后执行模块测试、app 编译/测试并核对既存失败，再 assembleDebug。
## 真机验证与 QQ 群聊链路排查（2026-10-01）

### 设备
- vivo V2324A / Android 16 (SDK 36) / arm64-v8a / 1260x2800，设备号 10AE1S0TKL002FT。
- 已安装 APK 签名与本次构建一致，因此 adb install -r 为原地更新，用户数据保留（既有会话「小鱼」仍在）。
- 启动无 FATAL / 无 ANR；topResumedActivity=com.yunian.ai/.MainActivity。
- 安装包 Application 类确认为 com.yunian.ai.security.StaticApkShell（aapt xmltree），android:debuggable=0xffffffff。
- APK 28 个 dex 中 nativeDecryptDex（app/src/shell/java 的 Java 版特征）命中 0，
  证明 src/shell/java 未被注册为源集、实际编译的是 src/main 下的 Kotlin 版 StaticApkShell。

### 插件设置 UI（用户请求的两处入口）——已在真机确认
- 我页出现「插件设置」，副标题「管理插件与启停开关」，纵坐标位于 API设置(1411) 与 主题模式(1853) 之间，
  即用户要求的「API设置卡片下方、主题模式上方」。
- 进入后：标题「插件设置」、搜索框「搜索插件名称或 id」、上方导航栏 TabRow「消息通道 / 通用插件」。
- 「消息通道」下正确列出 channel.qqbot（QQ 机器人通道）与 channel.wechat（微信通道）。
- 全程无崩溃。

### 应用日志现状（重要，可复现）
- 该应用在本机 logcat 中输出为 0 行：adb logcat -b all -d 共 49416 行，
  YuNianReleasePerformance / StaticApkShell / QQBotBridge 全部 0 命中；对 app pid 实时订阅 5 秒同样 0 行。
- 而 StaticApkShell.onCreate 中 logSecurityPerformance() 的 android.util.Log.i 是无条件执行的，
  且 android:debuggable=true、logd main 缓冲健康（4MiB，19MiB readable，非轮转丢失）。
  ⇒ 应用 logcat 输出被系统/壳层抑制。liblianyu_shell.so 确实在包内（53304 字节），
    其 nativeAntiHookInit / MethodRecoveryEngine.install / OatDisabler.disable 均会执行，是首要怀疑对象。
- 应用自身另有一个文件日志：/data/data/com.yunian.ai/files/chatvm_debug.log，
  目前只记录 [Pipeline] / [ChatGeneration] 两类，不含 QQ 链路。
- 应用可 run-as（debuggable），QQ 持久化状态位于 files/datastore/qqbot_prefs.preferences_pb（435 字节）。

### QQ 群聊链路：代码侧逐段核对结论
读完整条路径，代码是完整且自洽的，未发现断链：
| 环节 | 位置 | 结论 |
|---|---|---|
| 网关 intents | QQBotWebSocketClient.kt:94,505 | 1 shl 25 (GROUP_AND_C2C) + 1 shl 26，已含群事件 |
| 事件白名单 | QQInboundEventMapper.kt:29-35 | 同时含 GROUP_AT_MESSAGE_CREATE 与 GROUP_MESSAGE_CREATE |
| 载荷派发 | QQBotMessageRepository.kt:347-378 | op==0 → supports → decode → map → tryEmit，正确 |
| JSON | 各 Json{} 配置 | 均 ignoreUnknownKeys=true，不会因未知字段解析失败 |
| 模型字段 | QQBotModels.kt:54-59 | QQMessageAuthor 同时有 user_openid 与 member_openid |
| 出站 | QQBotMessageRepository.kt:222-226 | GroupAtMessage → POST v2/groups/{group_openid}/messages |

### 由此定位到的两个真实缺陷（可观测性，非断链）
1. QQBotMessageRepository.kt:363-368：map() 返回 null 时，只有 GROUP_FULL_MESSAGE 分支打日志，
   GROUP_AT_MESSAGE 分支**一行日志都没有**。而该分支要求 author.member_openid 非空——
   一旦字段缺失或改名，群 @ 消息会被静默丢弃，且无任何痕迹。这是当前最可疑的静默失效点。
2. QQBotChatBridge.kt:139：if (text.isNotBlank()) 才回复。extractText 对群消息会剥离 @ 前缀，
   剥离后为空则整条事件被静默跳过。
补充：QQBotChatBridge.kt:218 的 if (tokenStore.getForwardEnabled()) 为假时，出站同样静默丢弃。

### 尚未完成（设备断开）
- 设备已从 adb 断开，以下验证未做：
  读取 qqbot_prefs.preferences_pb 判断是否出现过 群openid:成员openid 形式的映射键（可区分入站不通/出站不通）；
  插件启停开关重启后是否保持；FULL_PAGE 浮层的视觉与返回键层级；微信健康告警是否真的上屏。
- 未验证：7 个插件在真机上是否真的装载成功。

## 修复：QQ 通道的静默丢弃（本轮实际改动）

### 缺陷（两条，均为可观测性缺陷，非断链）
1. QQBotMessageRepository 在入站事件映射失败时，**只有全量群消息分支打日志**，
   群 @（GROUP_AT_MESSAGE_CREATE）分支一行都没有。而该分支要求 author.member_openid 非空
   （QQInboundEventMapper 既有 fail-closed 设计，已被单测锁定）——字段一旦缺失，群聊静默失效且零痕迹。
2. feature:wechat 把静默丢弃写进文件日志（WeChatDebugLog），feature:chat 有 ChatDebugLog，
   而 feature:qqbot **一个文件日志都没有**。叠加实测「本应用 logcat 在真机被系统抑制」，
   QQ 通道的丢弃点在真机上完全不可观测。

### 根因确认的边界（如实记录）
- **未能确认根因**：设备已从 adb 断开，无法读取 qqbot_prefs.preferences_pb，
  因此「群消息到底有没有进入应用」这一步没有观测到。
- 代码侧逐段核对**未发现断链**（intents 含 1 shl 25、事件白名单含群事件、JSON ignoreUnknownKeys、
  出站走 POST v2/groups/{group_openid}/messages）。
- 本次修复的作用是**让下一次复现能直接给出答案**，而不是继续猜测。

### 改动（6 个文件，单模块）
| 文件 | 改动 |
|---|---|
| feature/qqbot/build.gradle.kts | buildFeatures 增 buildConfig = true（对齐 feature:wechat） |
| feature/qqbot/.../QQBotDebugLog.kt | 新增，37 行，写 files/qqbot_debug.log，与 WeChatDebugLog 同形 |
| feature/qqbot/.../data/QQInboundEventMapper.kt | 新增 MapOutcome{Mapped,Dropped(reason)} 与 outcome()；map() 签名不变 |
| feature/qqbot/.../data/QQBotMessageRepository.kt | 丢弃分支改为记录 outcome.reason，同时写 logcat 与文件日志 |
| feature/qqbot/.../data/QQBotChatBridge.kt | 补静默分支留痕：事件到达 / autoReply 关闭 / 文本剥离后为空 / 转发关闭 |
| feature/qqbot/.../data/QQInboundEventMapperTest.kt | 新增 6 个用例 |

### 关键设计：原因由唯一来源产出
map() 现在就是 outcome() 的投影（(outcome(...) as? Mapped)?.event），
调用方**无法**自行复刻判定逻辑，因此日志里的原因不可能与真实判定漂移。
新增用例 outcome never drifts from map on any branch 覆盖 13 条分支锁定该不变量。

### 行为变更声明
**无行为变更。** 群 @ / 全量群消息的 fail-closed 丢弃语义逐字未动，
出站发送判定（含 getForwardEnabled 的逐句读取）逐字未动——只在其旁边增加了记录。
原 15 个 mapper 用例全部仍绿即为证据。

### 验证（调度者亲自串行执行）
| 命令 | 结果 |
|---|---|
| :feature:qqbot:testDebugUnitTest | **BUILD SUCCESSFUL**，6 个测试类 **100 用例 / 0 失败 / 0 错误 / 0 跳过** |
| QQInboundEventMapperTest | **21**（原 15 + 新 6），0 失败；6 个新用例名已在 XML 报告中逐一核对 |
| :app:compileDebugKotlin | **BUILD SUCCESSFUL**，该任务实际执行，无 e: 错误 |

### 设备重新连接后的判定流程（一步出结论）
    adb shell run-as com.yunian.ai cat /data/data/com.yunian.ai/files/qqbot_debug.log
- 有 [Bridge] event received ... key=<群openid>:<成员openid> → 群消息**进来了**，问题在出站或 AI 层。
- 有 [Repo] inbound DROPPED type=GROUP_AT_MESSAGE_CREATE reason=group: author.member_openid 缺失
  → **根因即此**，字段名与预期不符。
- 完全没有 event received → 群消息**根本没进应用**，问题在平台订阅 / 沙箱配置 / 机器人未入群。

## 决定性证据：QQ 持久化状态（设备重连后取得）

读取 `/data/data/com.yunian.ai/files/datastore/qqbot_prefs.preferences_pb`（435 字节，base64 后本地解码）得到全部键：

| 键 | 值 | 结论 |
|---|---|---|
| qqbot_user_companion_map | {"D84CF321BAF70F0813F2CAF495AED9D7":1} | **只有裸 openid 键（单聊形状）；无 <群openid>:<成员openid> 键** |
| qqbot_bot_openid | 10639578352595943150 | READY 已捕获 |
| qqbot_host_user_openid | D84CF321BAF70F0813F2CAF495AED9D7 | 单聊通路正常 |
| qqbot_access_token | 有值 | 已鉴权 |
| qqbot_session_id | 1e2c9019-7ade-439c-9262-fa232b56d832 | 网关会话存在 |
| qqbot_last_sequence | 有值 | 已收到过 dispatch |
| qqbot_default_companion_id | （空） | 走 getAllCompanions().firstOrNull() 兜底 |

### 由此确证的事实
1. **群消息从来没有成功进入过应用。** `getOrCreateMapping` 的键对群消息是
   `"<groupOpenid>:<memberOpenid>"`（QQBotChatBridge.runReply），该形状的键不存在。
2. **出站层与 AI 层被完全排除**：映射在 runReply 内部、发送之前建立；映射不存在，
   说明流程根本没走到出站。
3. **网关本身是活的**：access_token / session_id / last_sequence / bot_openid 四项齐备。
4. **单聊通路完好**：宿主的裸 openid 映射存在。

### 故障被压缩为一个二选一
- (a) 平台没有推送 GROUP_AT_MESSAGE_CREATE（机器人未入群 / 群事件未订阅 / 沙箱限制）；
- (b) 推送了，但 `author.member_openid` 缺失或改名，被 QQInboundEventMapper 丢弃。

两者在旧包上**无法区分**（丢弃点当时无任何日志，且 logcat 被系统抑制）。
本轮的日志修复正是为区分它们而做：判定流程见上一节。

## 根因确认：QQ 通道重启后无法重连（用户复现 + 真机实测）

### 用户给出的症状（决定性线索）
> QQ通道第一次扫码建立成功，但是余年退出后，再次登录，QQ通道会尝试重连，但是一直连不上

该症状把范围从「群聊链路」收窄到**重连/鉴权路径**，并且与 `qqbot_user_companion_map`
只有单聊键这一事实自洽：重启后网关从未真正建立，自然不会有任何群事件。

### 根因一：cachedToken 刷新后从未回填内存缓存

`QQBotApiClient` 中 `cachedToken` 是**纯内存**字段，且此前**只由 `createAuthenticatedRestApi()` 赋值**，
而它只在「发起一次 REST 调用」时才会被调到。

```
重启 → cachedToken == null（从未赋值）
  → QQBotWebSocketClient.sendIdentify()/sendResume() 走 getCachedToken() 拿到 null → 返回 false
  → refreshTokenAndRetryHandshake() 调 getOrRefreshToken() 刷新成功，
    但该方法只 return 新 token，且先执行 clearApiCache()（把 cachedToken 置空）
  → 调用方丢弃返回值（握手只认 getCachedToken()）
  → 握手仍然发不出去 → reconnect() → attempt++ ... 循环 ...
  → attempt > MAX_RECONNECT_ATTEMPTS(10) → onAuthFailed → return@launch → 永久停止
```

首次扫码绑定可用，是因为绑定流程会调 `createAuthenticatedRestApi()` 填充缓存。

**修复**：`getOrRefreshToken()`（及同模式的 `refreshToken()`）在 `clearApiCache()` **之后**
回填 `cachedToken` / `cachedTokenExpireAt`。

### 根因二：RESUMED 不结算握手 → 每 30 秒无限重连

修好根因一之后，RESUME 真正发了出去，真机日志暴露出更深的一层：

```
17:26:45.870 [Repo] dispatch type=RESUMED
17:27:15.778 [Repo] dispatch type=RESUMED     ← 精确 30 秒
17:27:45.850 [Repo] dispatch type=RESUMED     ← 精确 30 秒
```

`opDispatch` 原先只对 `t == "READY"` 结算握手，`RESUMED` 什么都不做，于是：

```
重启走 RESUME → 服务端回 RESUMED → 但 stateMachine 永不进入 CONNECTED
  → 握手看门狗 delay(HANDSHAKE_TIMEOUT_MS = 30_000L)
     若 !stateMachine.isConnected() 则强制断开并 scheduleReconnect()
  → 每 30 秒一个周期，reconnectAttempt 永不归零
  → 最终撞上 MAX_RECONNECT_ATTEMPTS 永久放弃
```

30 秒这个数字不是巧合：`HANDSHAKE_TIMEOUT_MS = 30_000L` 与实测周期精确一致。

**修复**：`RESUMED` 与 `READY` 同等对待，共用同一套收尾
（`reconnectAttempt.set(0)` + `cancelHandshakeWatchdog()` + `stateMachine.onReady()`）。

### 真机验证（决定性证据）

验证方法：安装修复包 → `am force-stop`（等价于用户「退出应用」）→ 重新启动 → 观察文件日志。

修复后，观察 100 秒（覆盖 3 个以上看门狗周期）：

```
17:32:17.005 [Bridge] start: collecting inbound events
17:32:17.308 [WS] handshake settled by RESUMED
17:32:17.309 [Repo] dispatch type=RESUMED
```

| 指标 | 修复前 | 修复后 |
|---|---|---|
| RESUMED 出现次数（100 秒内） | 每 30 秒一次，无限循环 | **1** |
| handshake settled | **0** | **1** |

### 单元测试

| 测试类 | 用例 | 失败 |
|---|---|---|
| QQBotApiClientTokenCacheSourceTest（新增） | 4 | 0 |
| QQBotWebSocketHandshakeSourceTest（新增） | 4 | 0 |
| QQBotChannelPluginTest | 30 | 0 |
| QQBotSentenceSplitterTest | 21 | 0 |
| QQInboundEventMapperTest | 21 | 0 |
| QQBotOutboundProjectionTest | 11 | 0 |
| ConnectionStateMachineTest | 10 | 0 |
| HeartbeatAckTrackerTest | 7 | 0 |
| **合计** | **108** | **0** |

两个新增护栏类为源码级（该类依赖 Retrofit/DataStore，无法 JVM 直测），
锁定的不变量分别是「clearApiCache 之后必须回填 token」与「RESUMED 必须结算握手」。

## QQ 群聊「能读到但模型说找不到路」

### 结论：被动回复已通，缺的是主动工具的可发现目标

真机文件日志确认同一条 `GROUP_AT_MESSAGE_CREATE` 事件已完成完整被动回复：

```text
17:57:45.672 [Repo] dispatch type=GROUP_AT_MESSAGE_CREATE
17:57:45.698 [Route] captured recent group target
17:57:45.703 [Bridge] event received type=GroupAtMessage autoReply=true text_len=3
17:57:56.109 [Bridge] dialogue done blocked=false reply_len=89 turn=true
17:57:56.114 [Bridge] send reply type=GroupAtMessage text_len=20
17:57:56.739 [Bridge] send reply success type=GroupAtMessage
... 共 4 段均 success
```

因此 QQ 群发送 API、令牌和被动回复锚点都正常。“找不到路”来自 App 内主动发送工具的目标契约：
它要求模型填写 `target="group:<group_openid>"`，但 `group_openid` 是 QQ 平台不透明 ID，只存在于
Gateway 入站事件，模型没有列举目标的工具，也不应猜测内部标识。

### 修复

- 收到 `GroupAtMessage` 时，在 autoReply 开关之前捕获可信 `group_openid`；
- 使用 DataStore 键 `qqbot_recent_group_openid` 持久化最近群目标，解绑时清除；
- 主动工具支持稳定别名 `target="group"` / `target="recent_group"`；
- 工具描述与 JSON Schema 推荐模型使用 `target=group`，不再要求它知道内部 ID；
- 既有显式 `group:<openid>`、空目标发宿主单聊、内容安全过滤和 appLocalOnly 授权全部保留；
- 增加不含消息内容和 OpenID 的 `[Route]` 文件日志，记录解析来源及发送成功/失败。

### 验证

- `:feature:qqbot:testDebugUnitTest assembleDebug --offline --console=plain`：BUILD SUCCESSFUL；
- 9 个测试类，**114 tests / 0 failures / 0 errors**；
- 新增目标别名覆盖：`group`、`recent_group`、无历史群 fail-closed；
- 新增源码护栏覆盖：入站群路由捕获早于 autoReply 门、DataStore 持久化/解绑清理、工具提示可发现；
- APK 串行安装成功；真机 DataStore 已存在 `qqbot_recent_group_openid`；
- `QQBotForegroundService isForeground=true`。

### 冲突预审

- 保活/心跳/重连：未改 FGS、Worker、WebSocket、heartbeat、connectMutex 或重试调度；
- 消息/typing 时序：只在已存在的入站 collector 中做幂等 DataStore 写，且位于自动回复排队之前；
  未向 typing 或 AI 生成路径插入新的同步网络调用；
- 网络：被动回复和主动发送仍走原 `QQBotMessageRepository` / OkHttp API；
- 安全：`ContentFilter`、`appLocalOnly=true`、确认门和通道事件 bail 语义未变；
- 数据库：无 Room Entity、DAO、版本或 Migration 变更，仅新增 DataStore preference key。

**已验证不影响熄屏保活 / typing 时序 / 重连循环。**

## QQ 已实际发出但聊天模型口头报错

### 决定性证据

真机 `agent_dispatch_log` 记录 65：

- `send_channel_message` 参数为 `channelKey=qqbot, target=group`；
- 工具宿主记录 `ok=true`，耗时 278ms；
- QQ 侧实际收到消息；
- 但工具结果回灌后的模型 reasoning 明确写出 `The send failed again.`，随后气泡称“直接报错”。

这排除了 QQ 路由/API 未执行：失败发生在**模型解释工具成功回执**这一层。旧成功 JSON 同时包含
`ok=true`、`delivery_receipt=false` 与“不代表对方已收到”，模型抓住 `false`/否定措辞，将
“没有对端送达回执”误读为“发送失败”。

### 修复

成功结果改为无歧义正向契约：

```json
{
  "ok": true,
  "send_succeeded": true,
  "status": "sent",
  "delivery_status": "unknown",
  "model_instruction": "发送调用已成功；请向用户确认已发出。delivery_status=unknown 只表示无对端送达回执。"
}
```

- 成功结果不再出现布尔 `false` 或失败式措辞；
- `delivery_status=unknown` 仍诚实表达 QQ 没有对端送达回执，不声称对方已收到；
- 工具 system prompt 明确：仅 `ok=false/status=failed` 可称失败；
- `send_succeeded=true/status=sent` 必须按发送成功处理。

### 验证

- `:core:agent:testDebugUnitTest`：290 tests / 0 failures / 0 errors；
- `:feature:qqbot:testDebugUnitTest`：114 tests / 0 failures / 0 errors；
- `cargo test --lib`：**167 passed / 0 failed**；其中新增两条完整回合测试：
  - 工具成功而模型称“没发出去” → `bubble` 与 `final_text` 均确定性纠正；
  - 工具真实失败而模型称失败 → 原样保留，不误报成功；
- 成功契约增加 Rust 发布前事实一致性保护，只严格接受
  `ok=true && send_succeeded=true && status=sent`；
- `scripts/build_agent.ps1 -SkipTest`：4 ABI release 原生库交叉编译成功；
- `assembleDebug`：BUILD SUCCESSFUL，`mergeDebugNativeLibs` 与 `packageDebug` 均重新执行；
- 最终 APK 安装被 vivo 连续两次拒绝：
  `INSTALL_FAILED_ABORTED: User rejected permissions`；设备当前仍是上一版，不能宣称真机验收完成；
- 待用户解锁设备并允许 USB 安装后，再跑一次真实 App 聊天工具回合验证最终口头表述。
