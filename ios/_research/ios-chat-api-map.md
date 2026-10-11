# iOS 单聊逻辑层 API 地图

> 范围：只分析 `ios/YuNian/Data`、`Agent`、`Platform`、`Security`、`App`。未读取、未采用归档旧视觉层或旧 `ChatView` 的设计。调用点完整性以 `D:\Project\YuNian-iOS` 全仓 Swift grep 复核；Android 差异以当前 `D:\Project\予念` 逻辑实现对照。

## 1. 入口依赖与对象图

### 1.1 启动装配

`AppEnvironment` 是当前逻辑层的组合根，类型本身为 `@MainActor ObservableObject`，单例由 `shared` 暴露。`boot()` 幂等执行以下装配：

```text
AppEnvironment.shared (@MainActor)
├─ YuNianDatabase (GRDB DatabasePool / WAL / schema v45)
│  ├─ CompanionRepository
│  ├─ MessageRepository
│  ├─ ApiConfigRepository
│  ├─ MemoryRepository（管理 UI 的读侧）
│  └─ AgentStores
│     ├─ MemoryStore（Rust 回调；统一记忆 CRUD/召回）
│     ├─ SkillStore（Rust 回调；技能索引/正文/保存）
│     └─ StickerPreferenceStore（Rust 回调；偏好与使用记录）
├─ AgentStreamSinkImpl（Rust StreamSink → AsyncStream）
├─ AgentToolHostImpl（Rust ToolHost → Swift handler）
├─ AgentSignatureProviderImpl（PARTNER 请求签名；fail-closed）
├─ PromptOrchestrator
│  ├─ MemorySelector(store: AgentStores)
│  └─ SkillSelector(store: AgentStores)
└─ AgentRuntime(AgentGlobalConfig)
   ├─ dbPath（Rust 只读 companions/api_configs）
   ├─ settingsJson / credentialsJson / stickers
   ├─ orchestrator
   └─ signatureProvider
```

启动后还会：注册记忆工具、设备工具、`load_skill`、`sticker_pick`；播种默认伴侣、API 配置、安全词表、内置聊天协议技能；加载 `ContentFilter`；执行数据库维护。

### 1.2 `AppEnvironment` 对页面实际暴露的对象/状态

| 成员 | 类型 | 单聊用途 |
|---|---|---|
| `database` | `YuNianDatabase?` | 原始 GRDB 入口、世界书同步、表情读写 |
| `stores` | `AgentStores?` | Rust 的记忆/技能/表情偏好回调宿主 |
| `companions` | `CompanionRepository?` | 伴侣读取、编辑、时间戳、亲密度 |
| `messages` | `MessageRepository?` | 消息 CRUD、历史、FTS、归档 |
| `apiConfigs` | `ApiConfigRepository?` | 当前 API 配置与伴侣绑定判断 |
| `stickerTags` | `StickerTagProvider?` | 每回合下发 Top-30 表情标签 |
| `memoryRepo` | `MemoryRepository?` | 记忆管理读侧/软删除 |
| `runtime` | `AgentRuntime?` | 发起、更新配置、中止 Agent 回合 |
| `streamSink` | `AgentStreamSinkImpl` | 文本/推理增量事件流 |
| `toolHost` | `AgentToolHostImpl` | 工具执行回调 |
| `signatureProvider` | `AgentSignatureProviderImpl` | 请求签名 |
| 启动/诊断状态 | `startupError`、`contentFilterReady`、`resolvedFTSVersion` 等 | 启动门控和错误展示 |
| 配置动作 | `setAPIKey`、`setOwnerName`、`syncRuntimeConfig`、`testConnection`、`loadServerModels` | API 设置与回合前同步 |
| `defaultCompanion` | `Companion?` | 默认伴侣便捷读取 |

**未暴露：** `ConversationRepository`。该类型存在，但 AppEnvironment 不持有它；全仓保留逻辑层 grep 也没有 `ConversationRepository(...)` 生产调用点（只有测试）。新页面若需要已读游标/会话列表，可直接用 `database` 构造，或在 ViewModel 内持有；这不要求改逻辑层。

### 1.3 页面侧建议依赖关系

```text
ChatViewModel (@MainActor, 新增 UI 适配层)
├─ AppEnvironment（注入，不在 View 中散取单例）
├─ ChatSession(companionId:)（现成回合状态机）
├─ CompanionRepository（标题/头像/人设）
├─ MessageRepository（分页、删除、检索）
└─ ConversationRepository(database:)（mark read / unread，可选）
```

`ChatSession` 已经是逻辑层中最接近页面 ViewModel 的对象，但它不是完整的产品级单聊状态机：没有消息队列、显式 typing 文案、重试/再生成、用户图片/语音发送、精确 DB id 分页等。

## 2. 核心类型/签名表

### 2.1 Companion / Conversation / Message

| 类型/签名 | 语义与约束 |
|---|---|
| `CompanionRepository.Companion` | `Sendable, Equatable, Identifiable, Hashable`；字段含 `id/name/avatarUrl/age/personality/backstory/speakingStyle/tags/rawPrompt/systemPrompt/intimacy/lorebookIdsJson/apiConfigId/createdAt/updatedAt` |
| `fetch(id:) throws -> Companion?` | 按 id 读取 |
| `fetchAll() throws -> [Companion]` | `updatedAt DESC` |
| `fetchDefault() throws -> Companion?` | 最近更新伴侣 |
| `create(...) throws -> Int64` | 新建并返回 id |
| `update(_:) throws` | 整体更新；不自动刷新 `updatedAt` |
| `touch(id:timestamp:) throws -> Bool` | 显式刷新会话排序时间 |
| `increaseIntimacy(id:amount:) throws -> Int` | 亲密度累加，不钳制、不 touch |
| `delete(id:) throws` | 不级联删消息；页面若“删伴侣并清记录”须先 `MessageRepository.deleteForConversation` |
| `ConversationRepository.ConvRow` | 会话项：伴侣信息、最后消息、来源、未读布尔、亲密度 |
| `rows(limit:) throws -> [ConvRow]` | 从 `companions + messages + message_bodies` 实时联表；排除 `REASONING/TOOL_ACTIVITY` |
| `unreadCount(companionId:) throws -> Int` | `(timestamp,id)` 二元游标之后的 AI 消息数 |
| `markReadThroughLatest(companionId:) throws -> Bool` | 把游标推进到最新可见消息；必要时 upsert summary 行 |
| `MessageRepository.ConversationType` | `.chat = "chat"`、`.group = "group"` |
| `MessageRepository.HistoryRow` | `messageId/isFromUser/timestamp/type/turnId/content/searchContent/linkString` |
| `insert(...) throws -> Int64` | 单事务写 `messages + message_bodies + FTS`；支持 `turnId/eventIndex/durationMs/anchorMessageId` |
| `history(conversationId:type:limit:before:) throws -> [HistoryRow]` | 倒序截取后升序返回；`before` 只按时间戳，不含 id，不能无损处理同毫秒分页边界 |
| `updateContent(...) throws -> Bool` | 同步重建 FTS |
| `updateLinkString(...) throws -> Bool` | 附件路径回填 |
| `delete(messageId:)` / `deleteForConversation(...)` | 维护 FTS、正文、元数据 |
| `archiveOldMessages(...retainCount:)` | 保留最新 N 条热消息，其余归档；负数抛 `RepositoryError.invalidRetainCount` |
| `searchHot/searchArchived/search` | 中文应用层分词后的 FTS 查询 |

> 注意：schema 注释说明 `messages.type/fileFormat` 是 Room 枚举名大写，而当前 `ChatSession` 实际插入和读取使用小写 `"text"/"image"`。新 UI 必须按当前 iOS Repository/Session 的实际值消费；跨端导入数据可能为大写，建议 ViewModel 映射时大小写不敏感，否则 Android 导入的 `IMAGE` 可能无法恢复为图片气泡。

### 2.2 ChatSession 页面可调用面

```swift
@MainActor final class ChatSession: ObservableObject {
    @Published private(set) var messages: [Message]
    @Published private(set) var streamingText: String
    @Published private(set) var reasoningText: String
    @Published private(set) var isRunning: Bool
    @Published private(set) var lastError: String?
    @Published private(set) var lastRoundsUsed: UInt32
    var companionId: Int64?

    init(companionId: Int64? = nil)
    func send(_ text: String, in environment: AppEnvironment) async
    func sendSticker(entryId: Int64, in environment: AppEnvironment)
    func cancel(in environment: AppEnvironment)
    func loadHistory(from environment: AppEnvironment, limit: Int = 100)
}
```

`ChatSession.Message` 当前字段：临时 `UUID id`、`role`、`text`、`isSticker`、`imageData`、`imagePrompt`。它**没有数据库 messageId、timestamp、turnId、发送状态、错误状态、附件 URL/duration**，因此不足以直接支撑消息级删除、重试、定位和稳定 diff。

### 2.3 Agent DTO / 请求 / 结果

| 类型 | 字段/约束 |
|---|---|
| `AgentHistoryRole` | `system/user/assistant/tool` |
| `AgentHistoryMessage` | `role/content/tool_call_id/tool_calls/_agent_preserve_system/reasoning_content` |
| `AgentToolCall` | OpenAI 形状；`function.arguments` 是 JSON 字符串 |
| `AgentSettings` | `role/timezone/owner_name/working_memory_limit/image_gen_rules`；默认回合上限外的工作记忆 200 |
| `AgentCredentials` | `api_key/extra_api_keys/session/client_id`；空白省略，session/clientId 成对写入 |
| `AgentRequestBuilder.defaultMaxRounds` | `6`，与 Android 主对话一致 |
| `AgentTurnRequest`（UniFFI） | 当前构造字段：`groupId/historyJson/tools/maxRounds/toolChoice/stickerProbability/image/systemPrompt/companionNameMapJson` |
| `AgentTurnResult`（UniFFI） | 当前消费字段：`roundsUsed/error/events/finalText/finishedReason` |
| `AgentEvent`（UniFFI） | 当前消费 `kind/extra`；表情事件 `kind == "sticker"`，`extra` 是 `k=v;k=v` 而非 JSON |

当前流式调用签名（由真实调用点确认）：

```swift
runtime.runTurnStream(
    request: AgentTurnRequest,
    companionId: Int64?,
    toolHost: ToolHost,
    sink: StreamSink
) -> AgentTurnResult
runtime.cancelCurrentTurn()
```

### 2.4 Stream / Tool / Store

| 接口 | 关键签名 |
|---|---|
| `AgentStreamSinkImpl` | `makeStream() -> AsyncStream<Event>`、`closeAll()` |
| `Event` | `.textDelta(String)`、`.reasoningDelta(String)`、`.done(fullText:finishReason:)`、`.error(String)` |
| `AgentToolHostImpl` | `register(_:handler:)`、`unregister(_:)`、`execute(toolName:argumentsJson:contextJson:) -> String` |
| `AgentToolCatalog` | `install(memory:skill:host:)`、`definitions: [ToolDefinition]` |
| 记忆 Store | `listMemories/insertMemory/updateMemory/deleteMemory/getMemoryContent/getActivityTimestamps/setLastConsolidatedAt/embedText` |
| 技能 Store | `listSkills/getSkillContent/saveSkill/deleteSkill/searchSkills/reconcileSkills` |
| 表情偏好 Store | `listEntries/recordUsage/usageHistory` |

工具目录现已包括：Rust 记忆工具、`load_skill`、设备工具；仍没有 Android `ToolRegistry` 的完整领域工具集。`embedText` 固定返回 `nil`，记忆召回会降级为关键词方式。

### 2.5 Security / 错误

- `ContentFilter.checkInput`：只有 `HIGH+` 判违规；`checkInputOrNil` 可表达未加载。
- `ContentFilter.checkOutputSafety`：`HIGH+` 为不安全，但 `ChatSession` **没有调用它**。
- `ChatInputGuard.Outcome`：`.notChecked/.needsCheck/.blocked(reason:)/.checkFailed`。它严格复刻现有 Android UI 发送门：仅在全局无启用 API 且伴侣无可用绑定时检查输入。
- iOS 保留逻辑层**没有 BanManager 实现**，只在注释中出现 `BanManager.recordViolation`。
- 页面主路径错误不是 typed error，而是 `ChatSession.lastError: String?`；Rust 的 `AgentTurnResult.error`、sink `.error` 也被折叠成字符串。
- 相关 typed errors：`YuNianDatabase.BootstrapError`、`MessageRepository.RepositoryError`、`ImageGenClient.ImageGenError`、`ApiProbeService.ProbeError`、`KeychainStore.KeychainError`、`AppPaths.PathError`、签名/导入/备份错误。新 ViewModel 应将它们映射为 UI error enum，而不是解析文案。

## 3. 典型发送时序伪代码

```swift
@MainActor
func sendText(_ raw: String) async {
    let text = raw.trimmed
    guard !text.isEmpty, !session.isRunning else { return }
    guard environment.runtime != nil else { show(.agentNotReady); return }

    // 1) 当前移植语义：只有“完全无可用 API”才跑输入过滤
    switch ChatInputGuard.evaluate(...) {
    case .blocked(let reason): show(.contentBlocked(reason)); return
    case .checkFailed:         show(.safetyUnavailable); return
    case .needsCheck:          show(.apiRequired); return
    case .notChecked:          break
    }

    // 2) 乐观持久化与 UI 追加；DB 失败不阻断回合
    let userId = try? messages.insert(type: "text", content: text, ...)
    session.messages.append(user)
    session.streamingText = ""
    session.reasoningText = ""
    session.isRunning = true

    // 3) 每回合重新同步易变配置
    environment.syncRuntimeConfig()              // settings/credentials/stickers
    WorldbookRuntimeSync.syncActiveToRuntime(...) // 覆盖式世界书注入

    // 4) 历史映射 + 清洗
    let history = DialogueHistoryPolicy.sanitizeForModel(
        session.messages.map(toAgentHistory)
    )
    let request = AgentTurnRequest(
        historyJson: encodeHistory(history),
        tools: AgentToolCatalog.definitions,
        maxRounds: 6,
        toolChoice: "auto",
        stickerProbability: 0,
        image: nil,
        systemPrompt: nil
    )

    // 5) 先订阅流，再把阻塞 Rust 调用送到专用串行后台队列
    let stream = sink.makeStream()
    async let consume: Void = consumeOnMainActor(stream) {
        textDelta      -> streamingText += delta
        reasoningDelta -> reasoningText += delta
        done           -> completeAndPersistAssistant(fullText)
        error          -> lastError = message; completePartialIfAny()
    }
    let result = await on(AgentHostThreading.turnQueue) {
        runtime.runTurnStream(request, companionId, toolHost, sink)
    }

    // 6) 无论 Rust 是否发 done/error，都必须收束 stream
    sink.closeAll()
    await consume

    // 7) 处理最终结果、表情事件、非流式降级
    lastRoundsUsed = result.roundsUsed
    if result.error.nonEmpty { lastError = result.error }
    applyStickerEvents(result.events)
    if noStreamText && !result.finalText.isEmpty {
        completeAndPersistAssistant(result.finalText)
    }

    // 8) 若用户配置过生图，尝试生图；图片写 Application Support 并落 IMAGE 消息
    await maybeTriggerImageGen(userText: text, aiText: result.finalText)
    isRunning = false
}
```

当前链路完成文本闭环，但发送成功后**没有**自动 `companions.touch`、`increaseIntimacy(+2)`、显式对话记忆抽取、输出安全过滤或 ban 记录；这与 Android `AgentDialogueCoordinator` 的完整闭环不同。

## 4. 流式更新机制

1. `AgentStreamSinkImpl` 用 `NSLock` 保护 `[UUID: AsyncStream<Event>.Continuation]`，支持多个订阅者广播。
2. Rust 回调发生在回合执行线程；sink 只转发，不切主线程。
3. `ChatSession` 的 consumer 明确为 `Task { @MainActor ... }`，增量安全写入 Published 状态。
4. `.done` 和 `.error` 都会广播后 `closeAll()`；阻塞 `runTurnStream` 返回后再强制 `closeAll()`，覆盖 Rust 非流式降级/异常不回调的路径。
5. `textDelta` 累加到 `streamingText`，`reasoningDelta` 累加到 `reasoningText`；完成后把全文追加为一条 assistant 消息并清空两个 buffer。
6. 工具/表情事件**不走 StreamSink**。只有回合返回后的 `AgentTurnResult.events` 可见，因此表情必须在结果阶段 `applyEvents`。
7. 中止是合作式：`cancelCurrentTurn()` 设置 Rust turn cancel 标志，可让重试循环中断；Swift `Task.cancel()` 本身不能打断阻塞 FFI。
8. sink 是 AppEnvironment 全局共享实例；Rust 又有全局 turn mutex。当前 `ChatSession` 用 `isRunning` 只防单 session 重入，**不同 ChatSession 同时发送仍可能共同订阅同一 sink 并收到彼此广播**。新页面若只有一个可见单聊风险较低，但多窗口/快速切会话时应由 UI 适配层增加 app-wide active-turn 所有权，或至少在切换前 cancel 并等待结束。

### typing 的真实含义

逻辑层没有 `isTyping/typingText`。可用的近似状态是：

- 入队/回合执行：`isRunning`
- 正文流：`streamingText`
- 推理流：`reasoningText`

因此新 ViewModel 可以将 `isRunning && streamingText.isEmpty` 映射成 typing，但这不是 Android 的 `ChatTypingState`：Android 在消息入队即乐观开启 typing，并维护独立 typing 文案、队列深度、工具确认暂停/恢复。

## 5. 必须在 MainActor 的状态

### 明确隔离

- `AppEnvironment` 整个类型：所有 `@Published` 依赖、`boot`、配置同步和 runtime 引用访问。
- `ChatSession` 整个类型：`messages/streamingText/reasoningText/isRunning/lastError/lastRoundsUsed/companionId` 及其公开动作。
- `ChatSession` 的流 consumer：显式 `Task { @MainActor ... }`。
- `ImageGenCoordinator.run(...)`：`@MainActor`，回调会变更会话消息。
- 建议新增的 `ChatViewModel`：`@MainActor`，只在此处发布 UI state。

### 必须离开 MainActor

- `AgentRuntime.runTurnStream` 是阻塞 HTTP + sleep + Rust 全局 mutex，必须走 `AgentHostThreading.turnQueue`，禁止直接从 MainActor 调用。
- Rust foreign-trait 回调在回合后台线程同步发生。`AgentStreamSinkImpl`、`AgentToolHostImpl`、`AgentSignatureProviderImpl` 标为 `@unchecked Sendable` 并自行加锁。
- 工具回调不能长时间同步 IO，也不能同步重入 Rust，否则可能自死锁。
- `DeviceTools.executeOpenUrl` 从 Rust 线程派发 `UIApplication.open` 到 main queue，并用 semaphore 等结果；依赖主 actor 没被 FFI 阻塞，需真机验证 10 秒超时风险。

### 数据库线程约束

Repository 自身未标 actor；GRDB `DatabasePool` 负责 WAL 并发与读写调度。可从后台调用，但不要在 MainActor 上做大分页/FTS/归档。Rust 同时以只读连接访问同一 DB，因此不能改成 `DatabaseQueue` 或关闭 WAL。Store 回调是同步 Rust 回调，查询应保持短小。

## 6. 缺口（Android 有但 iOS 逻辑层没有）

| 能力 | Android 当前能力 | iOS 保留逻辑层现状 | 不改逻辑层能否补在 ViewModel |
|---|---|---|---|
| 独立 typing 状态 | `ChatTypingState`，入队即显示，工具确认暂停/恢复 | 只有 `isRunning/streamingText` | 可做近似；无法得到完全同款 typing 文案/工具确认状态 |
| 消息队列/合并/防连击 | Channel(100)、queueDepth、2 秒重复保护、合并与打断策略 | `isRunning` 时新发送直接丢弃 | 可在 VM 建队列/防抖，但“打断后保留/丢弃 batch”需要更多 turn commit 信息 |
| 再生成/发送重试 | `regenerate` 删除目标回复与同轮工具卡片后重跑 | 无 retry/regenerate API；只有正文加载重试在 Android | 可用公开 Repository + `send` 做简化重发，但不能无损重建“最后用户轮”与工具历史；完整语义需要扩展逻辑层 |
| 用户图片→视觉对话 | `sendImage(imagePath)`，`AgentTurnRequest.image` | 只支持 AI 生图结果；文本 send 固定 `image:nil` | **不能完整实现**，需新增视觉发送入口 |
| 用户语音/STT | VOICE 落库 + Android STT → 文本生成 | 无录音/STT/语音消息 API | 不能，仅 UI 不够 |
| AI 语音条/TTS/语音通话 | TTS controller、VOICE_BAR、VoiceCallManager | 无对应逻辑 | 不能 |
| 视频消息 | VIDEO 落库路径 | 无 | 不能 |
| 用户表情完整闭环 | 用户表情计数、作为消息发送/生成输入 | `sendSticker` 只追加内存且计数；不落 `messages`，不触发 Agent | 只能显示，不能算完整发送闭环 |
| 输出 ContentFilter | 回答落地前 `checkOutputSafety` | 有 API，但 `ChatSession.complete` 未调用 | VM 无法可靠拦截已在 Session 内落库的流式完成；需改逻辑层 |
| BanManager | 输入/输出违规记录、封禁 | 完全无实现 | 不能 |
| 伴侣时间戳/亲密度 | 成功落库后 touch、`+2` | Repository 有接口，ChatSession 未调用 | VM 可在成功后补，但缺可靠“仅提交一次”事件；有重复风险 |
| 对话后记忆抽取 | `extractAndSaveFromConversation` | 有记忆工具与 prompt 召回；没有等价显式抽取调用 | Rust 工具可能主动写，但不等价；VM 无公开高层抽取接口 |
| 向量记忆召回 | EmbeddingProvider | `embedText -> nil`，关键词降级 | 不能 |
| 完整领域工具与确认门 | ToolRegistry + capability grant + confirmation state | 记忆、技能、设备工具；缺完整领域 registry/确认 UI 状态 | 不能完整补 |
| per-companion 生图设置 | 伴侣覆盖、概率等 | override 恒空；`stickerProbability=0` | 不能做到 Android 等价 |
| 会话物化摘要维护 | 写消息时更新 `conversation_summary` | 消息发送不维护；列表实时联表，markRead 可 upsert 游标 | 单聊页面本身可工作；列表 pin/mute/unread 物化语义不完整 |
| 精确分页/稳定消息 identity | `(timestamp,id)` cursor，DB id 驱动 | `history(before:)` 只有 timestamp；Message 用 UUID，近似去重 | 不能无损处理同毫秒边界，需扩 Repository/Message |
| 召回/删除/清空后的响应式刷新 | Room Flow + metadata/body 状态 | 同步 Repository，无观察流 | VM 可手动刷新；多写入方实时同步缺失 |
| API 切换与伴侣绑定 UI 状态流 | Flow 观察配置 | Repository 可读写但无页面级 observation | 可轮询/动作后刷新，非实时 |
| Android 通道/微信广播 | 发送后广播等 | iOS 单聊逻辑无 | 非 iOS 单聊必需 |

### 额外一致性风险

1. `ChatSession.complete` 没有清洗 `ImageGenProtocol` 标记，也没有输出安全检查；Android 在落库前执行两者。
2. `stickerProbability` 固定 0，虽内置 `send_sticker` 仍可调用，但与伴侣设置不同。
3. AI 文本/用户文本落库后没有更新 companion `updatedAt`，会话排序不会随聊天推进。
4. AI 成功后没有 `increaseIntimacy(+2)`。
5. `ChatSession.loadHistory` 用临时 UUID 和内容近似去重；相同文本的合法重复消息可能被漏载。
6. 表情事件只追加内存，不落库，重启丢失。
7. `ConversationRepository` 未装配、无生产调用点；会话页需自行构造并主动 mark read。
8. 图片类型大小写与 Android 导入存在兼容风险。

## 7. 关键源码路径 + 行号

### App / 装配

- `ios/YuNian/App/AppEnvironment.swift:13-44`：`@MainActor` 及暴露依赖/状态。
- `ios/YuNian/App/AppEnvironment.swift:74-174`：boot、DB/Repository/Runtime/PromptOrchestrator/工具装配。
- `ios/YuNian/App/AppEnvironment.swift:183-217`：sticker_pick、播种、ContentFilter、维护。
- `ios/YuNian/App/AppEnvironment.swift:470-513`：每回合 settings/credentials/stickers 同步。

### Agent / 会话

- `ios/YuNian/Agent/ChatSession.swift:16-71`：MainActor、Message 与 Published 状态。
- `ios/YuNian/Agent/ChatSession.swift:73-149`：发送门、用户消息落库、运行时/世界书同步。
- `ios/YuNian/Agent/ChatSession.swift:151-192`：AgentTurnRequest 与工具定义。
- `ios/YuNian/Agent/ChatSession.swift:194-254`：流消费、后台 FFI、结果/事件、生图。
- `ios/YuNian/Agent/ChatSession.swift:357-419`：表情事件、用户表情、中止。
- `ios/YuNian/Agent/ChatSession.swift:423-458`：assistant 完成与落库。
- `ios/YuNian/Agent/ChatSession.swift:460-503`：历史装载与近似去重。
- `ios/YuNian/Agent/ChatSession.swift:517-598`：图片持久化、contentForModel、历史清洗。
- `ios/YuNian/Agent/AgentHost.swift:20-33`：阻塞调用/回调线程约束与专用队列。
- `ios/YuNian/Agent/AgentHost.swift:40-115`：StreamSink → AsyncStream。
- `ios/YuNian/Agent/AgentHost.swift:123-168`：ToolHost 注册与执行。
- `ios/YuNian/Agent/AgentDTOs.swift:29-136`：history DTO。
- `ios/YuNian/Agent/AgentDTOs.swift:138-210`：settings DTO。
- `ios/YuNian/Agent/AgentDTOs.swift:213-318`：credentials DTO。
- `ios/YuNian/Agent/AgentDTOs.swift:323-352`：maxRounds=6 与历史编码。
- `ios/YuNian/Agent/AgentToolCatalog.swift:38-108`：记忆/设备/load_skill 定义与执行器。
- `ios/YuNian/Agent/DialogueHistoryPolicy.swift:93-127`：历史过滤和同角色合并。
- `ios/YuNian/Agent/DeviceTools.swift:279-320`：主线程 open URL 桥与超时风险。

### Data

- `ios/YuNian/Data/Repositories/CompanionRepository.swift:35-61`：Companion 模型和 Rust 直读列。
- `ios/YuNian/Data/Repositories/CompanionRepository.swift:65-92`：读取。
- `ios/YuNian/Data/Repositories/CompanionRepository.swift:96-188`：创建、更新、touch、亲密度。
- `ios/YuNian/Data/Repositories/ConversationRepository.swift:47-70`：ConvRow。
- `ios/YuNian/Data/Repositories/ConversationRepository.swift:89-136`：会话联表查询。
- `ios/YuNian/Data/Repositories/ConversationRepository.swift:150-247`：未读与已读游标。
- `ios/YuNian/Data/Repositories/MessageRepository.swift:46-72`：会话类型、错误、StoredMessage。
- `ios/YuNian/Data/Repositories/MessageRepository.swift:81-116`：消息事务插入。
- `ios/YuNian/Data/Repositories/MessageRepository.swift:136-220`：历史与 linkString。
- `ios/YuNian/Data/Repositories/MessageRepository.swift:222-331`：正文更新、删除、清会话。
- `ios/YuNian/Data/Repositories/MessageRepository.swift:359-428`：归档。
- `ios/YuNian/Data/Repositories/MessageRepository.swift:454-522`：热/归档 FTS。
- `ios/YuNian/Data/YuNianDatabase.swift:29-95`：DatabasePool/WAL/foreign keys/secure_delete。
- `ios/YuNian/Data/YuNianDatabase.swift:99-150`：v45 bootstrap。
- `ios/YuNian/Data/Schema/YuNianSchema.swift:37-68`：核心表 DDL。
- `ios/YuNian/Data/Repositories/MemoryRepository.swift:40-109`：有效记忆读取、计数、软删除。
- `ios/YuNian/Agent/AgentStores.swift:71-355`：MemoryStore；`embedText=nil` 在 351-355。
- `ios/YuNian/Agent/AgentStores.swift:397-632`：SkillStore。
- `ios/YuNian/Agent/AgentStores.swift:694-829`：StickerPreferenceStore。

### Security

- `ios/YuNian/Security/ContentFilter.swift:128-183`：输入/输出检查与 ban 天数映射。
- `ios/YuNian/Security/ContentFilter.swift:200-252`：正则 + SemanticDetector 汇总。
- `ios/YuNian/Agent/ChatInputGuard.swift:42-49`：安全检查触发条件。
- `ios/YuNian/Agent/ChatInputGuard.swift:76-140`：伴侣绑定判断与 Outcome。

### Android 对照证据

- `feature/chat/.../ChatGenerationManager.kt:149-226`：typing、队列、loading/regenerating、工具确认、Ban pipeline。
- `feature/chat/.../ChatGenerationManager.kt:249-309`：重复发送保护、入队 typing、用户图片视觉回合。
- `feature/chat/.../ChatGenerationManager.kt:311-330`：再生成。
- `feature/chat/.../ChatViewModel.kt:164-210`：单聊 intent 面（文本/图/视频/语音/表情/撤回/再生成）。
- `feature/chat/.../ChatViewModel.kt:488-579`：正文重试、精确 `(timestamp,id)` 分页与消息定位。
- `feature/chat/.../ChatViewModel.kt:612-674`：撤回、用户表情、语音/STT、视频。
- `core/agent/.../AgentDialogueCoordinator.kt:108-182`：输入/输出过滤、Ban、touch、亲密度、记忆抽取。

### repo-wide grep 完整性结论

- `ChatSession/sendSticker/cancelCurrentTurn/runTurnStream/loadHistory` 的 Swift 全仓命中只在上述逻辑实现、注释与测试；未发现另一套生产发送器。
- `ConversationRepository(` 在 `ios/YuNian` 生产 Swift 中零命中；仅测试构造。
- `typing/retry/resend/voice/audio/speech/BanManager/recordViolation` 在 iOS 保留逻辑层没有实现命中（`BanManager` 只存在于移植说明注释）。
- `ContentFilter` 的生产调用只有启动加载与 `ChatInputGuard` 输入路径；没有 ChatSession 输出检查调用。
- `CompanionRepository/MessageRepository` 的生产调用点集中在 AppEnvironment、ChatSession、维护/播种；不存在隐藏的第二条单聊闭环。

## 8. 建议最小 ViewModel API（只定义 UI 所需，不改逻辑层）

目标是把现有能力稳定地暴露给全新 SwiftUI 页面，同时明确不伪装不存在的能力。

```swift
@MainActor
protocol ChatViewModeling: ObservableObject {
    var state: ChatViewState { get }

    func appear() async
    func disappear()

    func setDraft(_ text: String)
    func sendText() async
    func stopGenerating()
    func sendSticker(entryId: Int64)

    func loadEarlier() async
    func markRead() async
    func clearError()
    func clearHistory() async

    // 只在能力探测为 true 时给 UI 开入口；当前应为 false
    func retryLastTurn() async
    func sendImage(url: URL) async
    func sendVoice(url: URL, durationMs: Int) async
}

struct ChatViewState: Equatable {
    var companion: ChatCompanionViewData?
    var items: [ChatItem]
    var draft: String
    var phase: Phase
    var streamingText: String
    var reasoningText: String
    var hasMoreHistory: Bool
    var error: ChatUIError?
    var capabilities: ChatCapabilities

    enum Phase: Equatable {
        case booting
        case idle
        case typing              // 映射 isRunning && streamingText.isEmpty
        case streaming
        case stopping
    }
}

struct ChatCapabilities: Equatable {
    var text = true
    var cancel = true
    var manualSticker = true      // 但提示“仅本地展示/当前不持久化”并不理想
    var generatedImage = true     // AI 生图，需已配置 ImageGenStore
    var retry = false
    var userImageVision = false
    var voiceMessage = false
    var voiceCall = false
    var video = false
}

enum ChatItem: Identifiable, Equatable {
    case text(id: ChatItemID, role: AgentHistoryRole, text: String)
    case sticker(id: ChatItemID, role: AgentHistoryRole, entryId: Int64)
    case image(id: ChatItemID, role: AgentHistoryRole, data: Data?, fileURL: URL?, prompt: String?)
    case reasoning(id: ChatItemID, text: String)
    case loading(id: ChatItemID)
}

enum ChatItemID: Hashable {
    case database(Int64)
    case transient(UUID)
    case streaming
}

enum ChatUIError: Equatable {
    case startup(String)
    case agentNotReady
    case apiRequired
    case contentBlocked(String)
    case safetyUnavailable
    case generation(String)
    case persistence(String)
    case unsupportedFeature(String)
}
```

### 适配规则

1. ViewModel 持有一个 `ChatSession(companionId:)`，页面不要直接读写 `AppEnvironment` 的十几个成员。
2. `appear()`：确认 `environment.boot()` 已执行；取 companion；`session.loadHistory`；构造 `ConversationRepository(database:)` 并 mark read。
3. `sendText()`：清 draft 后 await `session.send`。由于 `send` 在 MainActor 上异步挂起，不会阻塞 UI；不要自行调用 `runTurnStream`。
4. `stopGenerating()`：先将 phase 显示为 stopping，再 `session.cancel`；直到 `isRunning == false` 才回 idle。
5. `loadEarlier()`：现有 `ChatSession.loadHistory` 不支持 cursor 且近似去重。第一版最多调用 Repository 读一页映射到独立 `ChatItem`；同毫秒边界与 session 内存合并仍不完全可靠，应限制为“最近 100 条”或清楚标注技术债。
6. 不要向 UI 宣称 retry、视觉发送、语音、Ban 已支持；capability 为 false 时隐藏入口，而不是用空实现。
7. 不在 ViewModel 重做 ContentFilter 或 Agent 请求组装；这样可以避免与逻辑层双重落库/双重过滤。
8. 如果产品要求“完整”定义包含 Android 当前的图片理解、语音、重试/再生成、输出过滤与 Ban，则必须先扩逻辑层，不能靠 ViewModel 拼装。

## 最终判断

**仅实现“文本单聊主闭环”可以不改逻辑层：**启动 → 选伴侣 → 载入最近历史 → 文本发送 → typing 近似态 → 流式文本/推理 → 中止 → AI 文本落库 → AI 生图结果 → 模型/用户表情的基础展示，均已有可调用入口。

**实现与 Android 当前能力等价的“完整单聊闭环”不可以在完全不改逻辑层的前提下完成。** 阻断项至少包括：用户图片视觉输入、语音/STT/TTS/通话、可靠重试/再生成、队列与打断语义、输出安全过滤、BanManager、表情持久化与实际发送、touch/亲密度/显式记忆提取、精确 DB identity/cursor 分页、完整领域工具确认。新 ViewModel 只能对现有文本闭环做干净适配，不能补齐这些底层能力。