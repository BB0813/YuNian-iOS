# Android 单聊聊天页功能规格（iOS 原生重写对照）

> 调研对象：Android 仓库 `D:\Project\予念` 当前代码。本文只提取功能语义、状态与数据契约，不复制 Compose/微信式/液态玻璃视觉。
>
> 完整性方法：先阅读仓库 `AGENTS.md`、`CLAUDE.md`，再逐项检查 `feature:chat`、`core:agent`、`core:database`、`core:network` 输入组件及 `app` 导航；最后对附件、图片、语音、引用、再生成、删除、拉黑、过滤、typing、流式、中止、错误、分页、键盘、角色、记忆、技能、世界书等关键词做全仓 grep。以下也明确标出“声明存在但当前没有实际 UI/链路”的能力，避免 iOS 误抄伪功能。

## 1. 页面入口与参数

### 1.1 路由与必需参数

- 单聊路由为 `chat/{companionId}`，参数类型为 `Long`；详情为 `chat_detail/{companionId}`，免打扰设置为 `chat_detail_dnd/{companionId}`，语音通话为 `voice_call/{companionId}`。[MainRoute.kt:9-13](../../../予念/app/src/main/java/com/yunian/ai/MainRoute.kt#L9-L13) [MainNavGraph.kt:133-180](../../../予念/app/src/main/java/com/yunian/ai/MainNavGraph.kt#L133-L180)
- `ChatScreen` 只接收 `companionId` 和返回、详情、用户资料、语音通话导航回调；业务状态由以 `companionId` 为 key 的 `ChatViewModel` / `ChatGenerationManager` 获取。[ChatScreen.kt:140-153](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L140-L153)
- 路由解析失败会回退 `0L`，但聊天页自身没有显式“非法 companionId”页面；伴侣加载失败时只在发送阶段提示“系统正在加载伴侣信息，请稍后再试”。[MainRoute.kt:133-136](../../../予念/app/src/main/java/com/yunian/ai/MainRoute.kt#L133-L136) [ChatGenerationManager.kt:952-955](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L952-L955)

### 1.2 入口来源

- 首页会话列表和联系人页点击角色均调用 `openCompanionChat(companionId)`；500ms 内重复导航被防抖。[MainNavGraph.kt:442-454](../../../予念/app/src/main/java/com/yunian/ai/MainNavGraph.kt#L442-L454) [MainScreen.kt:75-87](../../../予念/app/src/main/java/com/yunian/ai/MainScreen.kt#L75-L87)
- 系统通知/外部 Intent 可用 `open_chat=true` + `companion_id` 直接进入单聊，消费后移除 extras。[MainScreen.kt:94-104](../../../予念/app/src/main/java/com/yunian/ai/MainScreen.kt#L94-L104)
- 打开会话时保存“最近打开角色”，并预热角色聊天设置与背景；这是启动/性能语义，不是聊天业务必需语义。[MainScreen.kt:81-86](../../../予念/app/src/main/java/com/yunian/ai/MainScreen.kt#L81-L86)
- 点击对方头像或标题进入该角色聊天详情；点击用户头像进入只读用户资料。[ChatScreen.kt:158-167](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L158-L167) [ChatTopBarRegion.kt:202-208](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatTopBarRegion.kt#L202-L208)

## 2. 功能清单（P0/P1/P2）

### P0：首版 iOS 必须保持

1. **按 companionId 隔离的会话**：角色信息、消息、草稿、详情设置、生成任务均按角色隔离。
2. **消息历史与增量刷新**：首屏优先读进程缓存，随后读 Room 元数据；正文只对可见消息懒加载；数据库/缓存变化持续合并。[ChatViewModel.kt:87-101](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L87-L101) [ChatViewModel.kt:299-371](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L299-L371)
3. **文本发送**：发送前 trim、空文本不可发；用户消息先落库，再进入生成队列；2秒同内容防重复；连续输入允许合并成一轮。[WeChatChatInputBar.kt:150-159](../../../予念/core/ui-common/src/main/java/com/yunian/ai/uicommon/component/WeChatChatInputBar.kt#L150-L159) [ChatGenerationManager.kt:249-280](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L249-L280)
4. **即时 typing 反馈**：成功入队即开启，而不是等安全检查、合并窗口或网络请求；完成/失败后关闭。[ChatGenerationManager.kt:270-276](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L270-L276) [ChatGenerationManager.kt:1394-1406](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L1394-L1406)
5. **AI 多气泡结果**：一次 Agent 回合可产生多条 bubble/sticker；每条按顺序独立落库，气泡间有模拟延迟；重复内容按本轮+跨轮窗口去重。[ChatGenerationManager.kt:1059-1089](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L1059-L1089) [AiResponseFinalizer.kt:163-170](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/AiResponseFinalizer.kt#L163-L170)
6. **图片发送/理解**：相册最多选9张并逐张发送；相机拍摄；每张用户图片先落库，再走 vision 回复。图片可全屏预览并左右浏览会话图片。[ChatScreen.kt:1235-1256](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L1235-L1256) [ChatGenerationManager.kt:284-308](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L284-L308)
7. **语音消息**：长按录音、松开发送、上滑取消；不足1秒不发送；语音消息先落库，再做 STT，识别成功后把转写作为文本发给 AI。[ChatScreen.kt:1078-1108](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L1078-L1108) [ChatViewModel.kt:639-658](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L639-L658)
8. **消息操作**：引用、选中文字复制、AI 消息重新生成、撤回/删除；图片额外支持保存到相册。[ChatMessageMenu.kt:96-142](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/message/ChatMessageMenu.kt#L96-L142)
9. **历史分页和滚动锚点**：接近顶部自动拉更早消息；加载后恢复原可见锚点与偏移；正文失败可点按重试。[ChatScreen.kt:617-655](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L617-L655) [ChatListItemRenderer.kt:60-65](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/message/ChatListItemRenderer.kt#L60-L65)
10. **生成、安全与错误状态**：封禁、API 未配置、输入阻断、网络/超时、空响应、生成被打断均有用户可见反馈，不允许静默挂起。
11. **持久化草稿**：每个 companionId 单独保存；发送文本即清空，离页时再次保存当前值。[ChatViewModel.kt:138-139](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L138-L139) [ChatViewModel.kt:227-233](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L227-L233)
12. **角色/用户身份**：标题、头像、气泡归属、引用作者取伴侣资料与用户资料；资料可实时更新。[ChatViewModel.kt:493-519](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L493-L519)

### P1：应保持，允许后续迭代完成

- 表情包面板、ZIP 导入、单图拖拽导入/命名、清空已导入表情；表情在数据库仍编码为普通 `TEXT` + `[stickerId]`，无独立 STICKER 枚举。[AiResponseFinalizer.kt:440-449](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/AiResponseFinalizer.kt#L440-L449)
- 视频发送和系统播放器打开；文件类型已有渲染/打开能力，但**当前单聊输入扩展面板未发现文件选择发送入口**。
- 引用文字/图片/视频/语音/文件，带原消息 ID；点引用块定位原消息，若不在内存则围绕目标补拉前后各半页。[QuoteReply.kt:69-100](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/QuoteReply.kt#L69-L100) [ChatViewModel.kt:548-579](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L548-L579)
- 推理过程：设置开启时可展示 REASONING，进行中默认展开，完成后可自动折叠并显示耗时；关闭时既不展示临时推理，也跳过持久推理行。[ChatStatusItems.kt:279-340](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/message/ChatStatusItems.kt#L279-L340)
- 工具执行活动：本轮实时卡片 + 回合结束持久化 `TOOL_ACTIVITY` 卡片；参数/结果不泄露；过程消息不进 AI 上下文、摘要或未读数。[ChatGenerationManager.kt:1458-1489](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L1458-L1489) [ChatRepository.kt:369-380](../../../予念/core/database/src/main/java/com/yunian/ai/database/repository/ChatRepository.kt#L369-L380)
- 高风险工具二次确认：对话框确认/取消，最多连续确认3次，批准或拒绝后重新跑 Agent 回合。[ChatGenerationManager.kt:652-688](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L652-L688) [ChatScreen.kt:1260-1285](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L1260-L1285)
- API 切换：长按发送按钮（多于1个 API 时）或扩展面板选择；选择后禁用其他配置并启用目标配置。[WeChatChatInputBar.kt:174-189](../../../予念/core/ui-common/src/main/java/com/yunian/ai/uicommon/component/WeChatChatInputBar.kt#L174-L189) [ChatViewModel.kt:597-609](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L597-L609)
- AI 回复可合成为“语音条”：模式仅有 `VOICE_BAR`/静音；落库类型为 VOICE、正文保留文本、附件保存音频路径。[TurnCommitCoordinator.kt:40-59](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/timeline/TurnCommitCoordinator.kt#L40-L59)
- 生图：聊天主回复完成后独立触发，聊天主链路不等待；显示跨页面保持的生成中状态和等待秒数；结果为 IMAGE 消息；失败仅提示，不破坏聊天回复。[ChatGenerationManager.kt:1413-1455](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L1413-L1455) [ChatStatusItems.kt:91-139](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/message/ChatStatusItems.kt#L91-L139)
- 语音通话入口与麦克风权限；视频通话按钮目前复用同一 `VoiceCall` 导航，不能误写成已实现真正视频通话。[ChatScreen.kt:1010-1025](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L1010-L1025)
- 聊天详情：角色信息、独立/全局背景、主动消息、连续追问、未回复提醒、心理活动、精确时间、表情概率、单会话生图覆盖、免打扰、拉黑、清空记录、重置设置。[ChatDetailScreen.kt:197-427](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatDetailScreen.kt#L197-L427)

### P2：可延后或按 iOS 平台重做

- 微信主动同步/消息镜像、Android FileProvider、拖放 URI/MIME 嗅探、Android 权限弹窗的具体实现。
- NTP 精确时间、主动消息调度、DND、未回复追问属于聊天生态但不是单聊主界面的首版核心。
- 果冻入场、液态玻璃、背景捕获、硬件分档降级、ADPF 性能会话等 Android 视觉/性能实现。
- Token 用量记录、Agent 调度审计与 dry-run 审计应保留后端/诊断语义，但不必进入 iOS 聊天首屏。
- 位置分享当前明确返回“位置分享暂不可用”，首版可不展示入口；如展示必须保持 disabled/提示，而非伪发送。[ChatViewModel.kt:174](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L174)

## 3. 状态机（事件 / 状态 / 转移）

### 3.1 页面与历史状态机

| 当前状态 | 事件 | 条件/动作 | 下一状态 |
|---|---|---|---|
| `Initial` | 创建 VM | 读 `MessageCache`，同步提供可用缓存；启动元数据、缓存、角色、API、用户资料观察 | `LoadingInitialHistory` |
| `LoadingInitialHistory` | 缓存命中 / hydrate / DB 元数据返回 | 设置 metadata/body，计算 `hasMore`; 空会话也算完成 | `Ready(empty|messages)` |
| `LoadingInitialHistory` | 异常 | 发“消息加载失败”；仍设置 `initialHistoryLoaded=true` | `Ready(partial/error)` |
| `Ready` | 可见行变化 | 对正文缺失/错误的可见 metadata 批量加载 | `BodyLoading` → `Ready` / `BodyError` |
| `Ready` | 接近历史顶部 | 若 `hasMore && !isLoadingMore`，保存锚点并按 `(timestamp,id)` 向前分页 | `LoadingEarlier` |
| `LoadingEarlier` | 返回 | 合并去重，判断是否到历史起点，恢复锚点偏移 | `Ready` |
| 任意 | 清空记录 | 删除热/冷消息与摘要，清缓存并显式清所有内存列表 | `Ready(empty)` |

依据：[ChatViewModel.kt:299-380](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L299-L380) [ChatViewModel.kt:454-485](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L454-L485) [ChatViewModel.kt:521-545](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L521-L545)

### 3.2 文本发送/生成状态机

```text
Idle
 └─ SendText(trimmed non-empty)
     ├─ duplicate within 2s → Ignore
     └─ persist user message → Queueing + Typing(optimistic)
         ├─ queue full → Error (用户消息已经落库)
         └─ merge window collects up to batch max → Validating
             ├─ device banned → Error → Idle
             ├─ no usable API → input safety check
             │   ├─ violation → ContentBlocked → Idle
             │   └─ safe → APIConfigError → Idle
             ├─ pipeline timeout/fail → ContentBlocked → Idle
             └─ startAiResponse → Generating(isLoading=true, typing=true)
                 ├─ tool confirm request → AwaitingConfirmation
                 │   ├─ approve → rerun Agent
                 │   └─ reject → rerun Agent
                 ├─ Agent bubble/sticker/reasoning/tool events → Delivering
                 ├─ new user message before commit → Cancel stale + merge old/new batch
                 ├─ new user message after any reply committed → Cancel stale + discard already answered old batch
                 ├─ regenerate/image request → Cancel current, replace generation
                 ├─ cancellation not caused by stale batch → “回复被打断，请重试”
                 ├─ error/timeout/empty → Error
                 └─ reply commits → memory extraction / optional image / follow-up / audit
                     → stop typing, isLoading=false → Idle
```

关键约束：用户消息落库发生在安全/API检查之前，所以“被过滤、无 API、队列满”时用户气泡仍可能存在；iOS 若改变此顺序，会改变用户可见历史语义。[ChatGenerationManager.kt:252-280](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L252-L280) [ChatGenerationManager.kt:863-923](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L863-L923)

### 3.3 再生成状态机

- 只对非用户消息提供“重新生成”。触发后：取消当前生成 → `isRegenerating=true` → UI 乐观移除目标消息及同 turnId 工具卡 → 数据库删除目标与工具卡 → 用删除后的历史重新生成；不新增用户消息，`userContentForMemory=""`；最终关闭再生成状态。[ChatMessageMenu.kt:115-123](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/message/ChatMessageMenu.kt#L115-L123) [ChatViewModel.kt:178-204](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L178-L204) [ChatGenerationManager.kt:311-330](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L311-L330)
- **边界**：菜单对所有非用户消息统一开放，包含 REASONING/TOOL_ACTIVITY/系统化文本等潜在类型；iOS 最好收敛为“助手可见回复”，否则可能删除过程消息后生成语义古怪。

### 3.4 拉黑/封禁/内容过滤是三种不同状态

- `settings.blocked` 是联系人级本地设置：当前 UI 直接用“你已拉黑该联系人”替代整个输入栏，无法手动发送；同时详情确认文案却写“你仍可以手动发送消息”，二者冲突。[ChatInputRegion.kt:84-105](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatInputRegion.kt#L84-L105) [ChatDetailScreen.kt:451-465](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatDetailScreen.kt#L451-L465)
- `BanManager` 是设备/账号安全封禁：用户消息先落库，生成前阻断并显示剩余封禁时间/次数。[ChatGenerationManager.kt:867-877](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L867-L877)
- `ContentFilter` 是内容级阻断。当前单聊文本主路径中：有 API 时 `MessagePipelineRunner` 明确记录“ContentFilter skipped (disabled)”并直接进入 SEND；只有“无 API”分支显式检查输入。Agent 回复主路径的输出也没有在 Kotlin `ChatGenerationManager` 再做通用输出过滤；通道/语音通话用的 `AgentDialogueCoordinator` 则同时做输入、输出过滤。iOS 不应假定所有路径过滤一致。[MessagePipelineRunner.kt:20-34](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/MessagePipelineRunner.kt#L20-L34) [AgentDialogueCoordinator.kt:108-148](../../../予念/core/agent/src/main/kotlin/com/yunian/ai/agent/AgentDialogueCoordinator.kt#L108-L148)

## 4. 发送 → typing → 流式 → 落库完整时序

### 4.1 文本主路径（Cordis Agent）

1. 输入栏仅在 trim 后非空时触发；发送后 ViewModel 清草稿。[WeChatChatInputBar.kt:150-159](../../../予念/core/ui-common/src/main/java/com/yunian/ai/uicommon/component/WeChatChatInputBar.kt#L150-L159) [ChatViewModel.kt:164-169](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L164-L169)
2. `DuplicateSendGuard` 拒绝2秒内同内容连击；否则构造用户 TEXT 并经 `MessageWriteCoordinator.enqueueChat` 等待落库；随后微信镜像、入生成队列。[ChatGenerationManager.kt:249-270](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L249-L270)
3. Message writer 走不可取消的消费协程；Room 事务写 metadata/body、加密正文、更新会话摘要，再更新进程缓存，使 UI 即时看到正 ID 消息。[MessageWriteCoordinator.kt:27-38](../../../予念/core/database/src/main/java/com/yunian/ai/database/repository/MessageWriteCoordinator.kt#L27-L38) [ChatRepository.kt:338-360](../../../予念/core/database/src/main/java/com/yunian/ai/database/repository/ChatRepository.kt#L338-L360)
4. 入队成功立刻 `startTyping()`；此时 `typingText` 被清空。因此当前 UI 顶栏会显示“对方正在输入...”，但消息流内 `TypingIndicatorItem` 要求 `typingText` 非空才出现。[TypingIndicator.kt:54-71](../../../予念/core/network/src/main/java/com/yunian/ai/network/TypingIndicator.kt#L54-L71) [ChatScreen.kt:854-868](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L854-L868)
5. 消费器在合并窗口内收集连续消息，最多取一个 batch；开始新 batch 前取消旧生成。打断后依据不可取消落库回调产生的 `commitSignal` 判断：已落库则旧消息不重答，未落库则与新消息合并重发。[ChatGenerationManager.kt:455-526](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L455-L526)
6. 做封禁/API/输入 pipeline 检查；读取历史（解密失败、REASONING、TOOL_ACTIVITY 被排除）与会话设置。[ChatGenerationManager.kt:863-919](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L863-L919) [ChatContextResolver.kt:46-65](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/data/ChatContextResolver.kt#L46-L65)
7. 建立 `PendingTurn`、进入 loading/typing；装配角色资料、图片生成规则、模型历史、全量工具、记忆工具、技能加载工具和编排选项。[ChatGenerationManager.kt:925-1017](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L925-L1017)
8. 每回合同步当前角色有效世界书；执行 Agent。若工具要求确认，UI 响应后 approve/reject 并重跑。[ChatGenerationManager.kt:652-688](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L652-L688)
9. 消费 Agent **完成结果中的事件列表**：`bubble` 延迟后逐条交给 finalizer，`sticker` 落表情，`reasoning` 汇总，`usage` 记 token，工具生命周期另行实时投影。[ChatGenerationManager.kt:1050-1119](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L1050-L1119)
10. Finalizer 清除模型伪造的“用户回合”，处理表情标签、按空行/工具协议分气泡、查重；可先落 REASONING，再逐条构造带 turnId/eventIndex/anchor 的助手消息，通过 writer 加密落库并更新缓存。[AiResponseFinalizer.kt:90-170](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/AiResponseFinalizer.kt#L90-L170) [TurnCommitCoordinator.kt:27-60](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/timeline/TurnCommitCoordinator.kt#L27-L60)
11. 只要任一回复段真正落库，`onPersisted` 置 committed 信号；之后即使生成协程取消，也不会把已回答的旧 batch 再合入下一轮。[AiResponseFinalizer.kt:76-82](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/AiResponseFinalizer.kt#L76-L82) [MessageWriteCoordinator.kt:103-118](../../../予念/core/database/src/main/java/com/yunian/ai/database/repository/MessageWriteCoordinator.kt#L103-L118)
12. 主回复完成后结束 loading/typing，再旁路触发记忆抽取、可选生图、可选追问、工具卡持久化、调度/回合审计和待委派任务。[ChatGenerationManager.kt:1162-1217](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L1162-L1217) [AiResponseFinalizer.kt:270-288](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/AiResponseFinalizer.kt#L270-L288)

### 4.2 “流式输出”的真实边界

- 架构具备 `AssistantStreamEvent`、推理 delta 节流、负 ID 临时 REASONING 消息和最终替换/落库机制。[PendingTurnStreamApplier.kt:19-94](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/timeline/PendingTurnStreamApplier.kt#L19-L94) [StreamingReasoningMessagePipeline.kt:9-73](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/timeline/StreamingReasoningMessagePipeline.kt#L9-L73)
- **但当前单聊文本主路径不是逐 token UI 流式**：`AgentFacade.runTurn` 返回完整 `AgentTurnResult` 后才遍历 events；全仓 grep 未发现 `ChatTypingState.appendText()` 的业务调用，因此 `typingText` 不会累积正文，列表内 typing 文本气泡通常不出现，只有顶栏 loading 文案。
- 图片理解分支把一次性 `sendMessageWithImage` 结果包装为 `NonStreamingAssistantStreamAdapter`，虽然复用流事件 applier，仍不是网络实时流。[ChatGenerationManager.kt:1220-1243](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L1220-L1243)
- iOS 必须保留的是“生成中状态、可取消/替换、分段结果渐进送达、推理临时态与正式落库态分离”；是否实现真正 token streaming 可升级，但不要声称 Android 当前正文正在逐 token 输出。

## 5. 消息类型与每类交互

数据库类型来源为 `TEXT / IMAGE / AUDIO / VIDEO / VOICE / FILE / REASONING / TOOL_ACTIVITY`。[MessageType.kt:7-25](../../../予念/core/database/src/main/java/com/yunian/ai/database/model/MessageType.kt#L7-L25)

| 类型/投影 | 数据语义 | 点按/长按交互 | 边界 |
|---|---|---|---|
| TEXT | 普通用户/助手正文；引用也编码在正文；`[id]` 可能投影为表情 | 文本选择后长按菜单；引用、复制选中片段、再生成（助手）、撤回 | 助手显示前会去角色前缀、`<think>`、孤立 `enc:`；清洗后空则不渲染 [TextMessageItem.kt:24-40](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/message/TextMessageItem.kt#L24-L40) |
| IMAGE | `linkString` 优先为本地路径 | 点按全屏预览；长按引用/保存/再生成/撤回 | 预览失败回退系统打开；保存时文件已清理会报错 |
| VOICE/AUDIO | 用户录音或 AI 语音条；AI 语音条同时保留文字正文 | 点按播放；可引用/撤回；AI 带 transcript 时可选择复制 | 用户录音 STT 失败仍保留语音，但不触发 AI 文本轮 |
| VIDEO | `linkString` 路径 | 点按系统播放器；引用/撤回 | 当前只落用户视频，不自动触发 AI 理解 |
| FILE | 附件路径 | 点按系统应用打开；引用/撤回 | 有渲染，无当前输入入口；文件不存在/无 handler 提示失败 |
| Sticker（TEXT 投影） | 正文严格为 `[非系统标签]` | 表情展示；引用/撤回/AI再生成 | `[语音]` 等系统标签被排除；TOOL_ACTIVITY 必须先于此判定，避免 JSON 数组误判 [ChatListItem.kt:163-186](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatListItem.kt#L163-L186) |
| REASONING | 与助手 turn 关联，含 duration；负 ID 表示临时流态 | 点按展开/收起 | 设置关闭时整行跳过；不进上下文/摘要/未读 |
| TOOL_ACTIVITY | content 为活动 JSON，一轮聚合一条 | 展示运行/成功/失败过程；历史可回放 | 不暴露 args/result；不进上下文/摘要/未读 |
| SystemTip（TEXT 投影） | 内容匹配加入/退出/移出/撤回文案 | 居中提示，无普通消息操作 | 单聊“撤回”当前实现直接删行并 toast，不写撤回 tip |
| TimeDivider（UI 派生） | 相邻可见消息间隔 `>=5分钟` 时插入 | 无 | 推理隐藏时不应推进可见时间游标 [ChatListItem.kt:99-127](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatListItem.kt#L99-L127) |
| BodyLoading / BodyError | metadata 已有，正文未加载/失败 | error 点按重试 | metadata/body 分表必须允许这一中间态 |

引用块支持点击定位 `messageId`；目标删除/跨会话时提示“原消息已不存在”。[ChatMessageContent.kt:224-230](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/message/ChatMessageContent.kt#L224-L230) [ChatViewModel.kt:548-553](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L548-L553)

## 6. 输入区能力

- 多行文本，最多5行；空文本不可发，发送 trim 后内容；输入草稿实时按 companionId 保存。[WeChatChatInputBar.kt:135-159](../../../予念/core/ui-common/src/main/java/com/yunian/ai/uicommon/component/WeChatChatInputBar.kt#L135-L159)
- 文本为空时显示长按语音按钮；长按开始、拖动上滑标记取消、松开提交/取消。[WeChatChatInputBar.kt:162-169](../../../予念/core/ui-common/src/main/java/com/yunian/ai/uicommon/component/WeChatChatInputBar.kt#L162-L169)
- “+”面板与表情面板、系统键盘互斥；打开面板先隐藏键盘/清焦点；键盘一旦显示会关闭两个面板并滚到底部。[ChatScreen.kt:604-610](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L604-L610) [ChatScreen.kt:1049-1076](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L1049-L1076)
- 扩展能力：相册、相机、视频/语音通话、AI语音条模式、位置（未实现）、语音输入、表情包、API切换。[ChatInputRegion.kt:152-165](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatInputRegion.kt#L152-L165)
- 相册最多9图；逐图复制到缓存并逐条触发 `SendImage`，因每次图片发送会 `replaceActiveGeneration`，快速多选时后图会取消前图的理解生成，历史仍保留所有用户图片。这是重要边界，iOS 应明确采取“逐图各回复”还是“多图一轮”，不能无意复刻竞态。[ChatScreen.kt:1235-1253](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L1235-L1253) [ChatGenerationManager.kt:284-308](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L284-L308)
- 引用预览出现在输入栏内，显示作者、单行摘要及图片/视频缩略图，可取消；实际发送时将引用头和正文编码进同一 TEXT content。[ChatInputRegion.kt:133-138](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatInputRegion.kt#L133-L138) [QuoteReply.kt:82-100](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/QuoteReply.kt#L82-L100)
- `isLoading` 传入输入组件但当前并不禁用文本输入或发送；允许生成中继续发消息，语义是“打断旧轮并合并/开启新轮”，不是串行锁死。[WeChatChatInputBar.kt:63-75](../../../予念/core/ui-common/src/main/java/com/yunian/ai/uicommon/component/WeChatChatInputBar.kt#L63-L75)
- 页面无显式“停止生成”按钮；可发生的中止来自新文本、发图片、重新生成、manager 最终释放。`stopTts()` 只停止音频播放/合成，不等于停止 AI。

## 7. 导航 / 菜单 / 详情

### 顶栏与头像

- 标题空闲显示角色名，点击进详情；加载时替换为“对方正在输入...”，可选 spinner；返回时清焦点、关闭键盘相关面板和录音层，再 pop。[ChatTopBarRegion.kt:145-173](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatTopBarRegion.kt#L145-L173) [ChatScreen.kt:710-718](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L710-L718)
- 对方头像 → 聊天详情；用户头像 → 只读用户资料。

### 长按菜单

- 所有普通消息：引用、撤回。
- 图片且路径非空：保存图片。
- 非用户消息：重新生成。
- 仅存在可复制选区时：复制（不是默认复制整条）。[ChatMessageMenu.kt:96-142](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/message/ChatMessageMenu.kt#L96-L142)
- 撤回是乐观删除，立即提示成功，再后台删库；若删库失败只报错，**不会自动恢复 UI 消息**。[ChatViewModel.kt:612-629](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L612-L629)

### 聊天详情

- 显示头像、名称、人格摘要、状态（已拉黑/免打扰/主动消息关闭/正常）。[ChatDetailScreen.kt:130-192](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatDetailScreen.kt#L130-L192)
- 清空历史需二次确认且不可恢复；会删除热表、归档表、FTS索引、会话摘要及缓存。[ChatDetailScreen.kt:472-488](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatDetailScreen.kt#L472-L488) [ChatRepository.kt:158-164](../../../予念/core/database/src/main/java/com/yunian/ai/database/repository/ChatRepository.kt#L158-L164)
- 重置聊天设置恢复背景、主动消息、免打扰等默认，但不删除消息。[ChatDetailScreen.kt:492-509](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatDetailScreen.kt#L492-L509)

## 8. 错误与边界

1. **空会话没有专用空态文案/CTA**：列表为空，仍显示顶栏和输入区。这是 grep 与 `ChatScreen` 结构确认结果，不要凭常规聊天产品经验添加 Android 不存在的欢迎语。
2. **无伴侣**：可进入页面；发送生成时提示角色加载中。iOS 可改善为明确错误/返回，但需记录为平台差异。
3. **用户消息先落库后失败**：无 API、封禁、内容阻断、队列满均可能留下只有用户消息的轮次。
4. **队列满**：用户消息已经落库；仅生成请求丢弃并提示。[ChatGenerationManager.kt:263-280](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L263-L280)
5. **同内容防连击**：2秒内第二次静默丢弃，不 toast；但不同内容可快速发送并参与合并。
6. **打断**：新消息导致的 stale/batch cancellation 不提示；其他取消提示“回复被打断，请重试”；临时推理与实时工具卡必须清理。[ChatGenerationManager.kt:1354-1363](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L1354-L1363)
7. **Agent 空结果**：无 bubble/sticker 时尝试 finalText 兜底；仍无有效内容则提示重试；error 且已有部分气泡视为部分成功，不回滚。[ChatGenerationManager.kt:1122-1159](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L1122-L1159)
8. **Vision 错误**：超时/失败、toast 协议、空内容分别提示；临时推理清理。[ChatGenerationManager.kt:1291-1312](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L1291-L1312)
9. **正文缺失/解密失败**：正文懒加载可进入 Error 并重试；AI 上下文会 `filterDecrypted` 丢弃解密失败占位，避免污染模型。
10. **媒体文件被清理**：图片保存提示失败；系统打开失败提示；全屏图片加载失败回退系统打开。[ChatViewModel.kt:213-224](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L213-L224) [ChatScreen.kt:1217-1230](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L1217-L1230)
11. **语音**：麦克风拒绝有提示；不足1秒/stop 无路径则静默丢弃；STT 超时或空转写只保留语音，不触发 AI；异常提示发送失败。
12. **分页**：以 `(timestamp,id)` 双游标保证同时间戳稳定；热消息与归档消息合并、按时间/id排序去重。[ChatRepository.kt:45-54](../../../予念/core/database/src/main/java/com/yunian/ai/database/repository/ChatRepository.kt#L45-L54) [ChatRepository.kt:321-336](../../../予念/core/database/src/main/java/com/yunian/ai/database/repository/ChatRepository.kt#L321-L336)
13. **UI 数量上限**：合并后的 UI 消息超过 `MAX_UI_MESSAGES` 时丢最老部分；数据库并未删除。[ChatContextResolver.kt:79-84](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/data/ChatContextResolver.kt#L79-L84)
14. **滚动规则**：用户在底部或新消息是自己发送时自动到底；用户读历史时新对方消息不抢位置，而累加“n条新消息”按钮；点按钮回到底。[ChatScreen.kt:520-566](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L520-L566) [ChatScreen.kt:935-970](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L935-L970)
15. **键盘**：点消息空白区隐藏键盘并关闭扩展/表情；键盘出现自动关闭面板并尝试滚底；返回直接退出页面而非优先逐层关闭面板（当前 BackHandler 行为）。
16. **拉黑文案/实现矛盾**：详情说仍可手动发，但输入栏被替换。iOS 产品必须先选定统一语义；建议以产品文案为目标语义：“禁止主动消息，但用户仍可手动发”，不要把 Android UI 冲突固化。
17. **删除/再生成乐观态失败不回滚**：iOS 应至少提示并重新拉取/恢复，否则会出现 UI 与库不一致。
18. **多图竞态**：多选逐张 `replaceActiveGeneration`，只有最后一张更可能得到回复；建议 iOS 定义为一个多图 turn。
19. **输出安全不一致**：App 单聊 Cordis 主路径与 `AgentDialogueCoordinator` 路径的 ContentFilter 覆盖不同；重写时应明确后端统一责任，不能仅靠 UI。
20. **无用户停止键**：不要在 iOS 规格中误标“支持手动停止”；若新增是产品增强，需定义部分结果是否落库及临时态清理。

## 9. 所有关键源码路径 + 行号

> 下列为实现功能对照所需的关键路径集合；视觉辅助组件未穷举。

### 导航与入口

- `app/.../MainRoute.kt`：单聊/详情/DND/语音通话路由 [9-13](../../../予念/app/src/main/java/com/yunian/ai/MainRoute.kt#L9-L13)、路由解析 [133-139](../../../予念/app/src/main/java/com/yunian/ai/MainRoute.kt#L133-L139)
- `app/.../MainNavGraph.kt`：ChatScreen 与子页面注册 [133-180](../../../予念/app/src/main/java/com/yunian/ai/MainNavGraph.kt#L133-L180)
- `app/.../MainScreen.kt`：入口防抖、最近角色、Intent 直达 [75-104](../../../予念/app/src/main/java/com/yunian/ai/MainScreen.kt#L75-L104)

### 主页面、输入、详情

- `feature/chat/.../ui/screen/ChatScreen.kt`：页面参数 [140-168](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L140-L168)；附件权限 [240-311](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L240-L311)；事件 [446-457](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L446-L457)；滚动/分页 [503-655](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L503-L655)；消息与输入 [816-1109](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L816-L1109)；预览/多图/确认 [1188-1285](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt#L1188-L1285)
- `feature/chat/.../ui/screen/ChatInputRegion.kt`：拉黑态与输入区 [42-166](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatInputRegion.kt#L42-L166)；引用预览 [169-223](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatInputRegion.kt#L169-L223)
- `core/ui-common/.../WeChatChatInputBar.kt`：输入、发送、语音、API切换 [63-199](../../../予念/core/ui-common/src/main/java/com/yunian/ai/uicommon/component/WeChatChatInputBar.kt#L63-L199)
- `feature/chat/.../ui/screen/ChatTopBarRegion.kt`：标题与 loading/typing [145-220](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatTopBarRegion.kt#L145-L220)
- `feature/chat/.../ui/screen/ChatDetailScreen.kt`：全部详情设置 [197-427](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatDetailScreen.kt#L197-L427)；拉黑/清空/重置确认 [451-509](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatDetailScreen.kt#L451-L509)
- `feature/chat/.../data/ChatDetailSettingsStore.kt`：会话设置数据模型 [15-46](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/data/ChatDetailSettingsStore.kt#L15-L46)

### ViewModel / 生成 / 时序

- `feature/chat/.../ui/viewmodel/ChatIntent.kt`：完整意图集合 [8-27](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatIntent.kt#L8-L27)
- `feature/chat/.../ui/viewmodel/ChatUiEvent.kt`：Info/Error/Blocked/完成/定位 [5-17](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatUiEvent.kt#L5-L17)
- `feature/chat/.../ui/viewmodel/ChatState.kt`：聚合状态定义 [14-34](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatState.kt#L14-L34)（注意当前 VM 实际仍暴露多个 StateFlow，此结构不是单一实际数据源）
- `feature/chat/.../ui/viewmodel/ChatViewModel.kt`：依赖/状态 [52-150](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L52-L150)；intent [164-210](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L164-L210)；历史观察 [299-433](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L299-L433)；懒加载/分页/定位 [454-579](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L454-L579)；撤回/语音/视频 [612-679](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt#L612-L679)
- `feature/chat/.../ui/viewmodel/ChatGenerationManager.kt`：发送/图片/再生成 [249-330](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L249-L330)；队列合并/打断 [455-601](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L455-L601)；世界书/确认 [652-688](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L652-L688)；记忆/技能/工具装配 [691-748](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L691-L748)；安全前置 [863-923](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L863-L923)；Agent事件与落地 [925-1217](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L925-L1217)；vision流适配 [1220-1353](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L1220-L1353)；错误/typing/生图/工具卡 [1354-1489](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt#L1354-L1489)
- `feature/chat/.../ui/viewmodel/AiResponseFinalizer.kt`：推理与气泡落库 [69-268](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/AiResponseFinalizer.kt#L69-L268)；记忆/追问 [270-367](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/AiResponseFinalizer.kt#L270-L367)；跨轮查重 [381-405](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/AiResponseFinalizer.kt#L381-L405)；表情落地 [408-530](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/AiResponseFinalizer.kt#L408-L530)
- `feature/chat/.../ui/viewmodel/MessagePipeline.kt`：阶段枚举 [7-44](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/MessagePipeline.kt#L7-L44)
- `feature/chat/.../ui/viewmodel/MessagePipelineRunner.kt`：当前实际 pipeline [20-49](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/MessagePipelineRunner.kt#L20-L49)
- `core/network/.../TypingIndicator.kt`：ChatTypingState [54-95](../../../予念/core/network/src/main/java/com/yunian/ai/network/TypingIndicator.kt#L54-L95)
- `feature/chat/.../timeline/PendingTurnStreamApplier.kt`：流事件状态归并 [19-95](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/timeline/PendingTurnStreamApplier.kt#L19-L95)
- `feature/chat/.../timeline/StreamingReasoningMessagePipeline.kt`：负 ID 临时推理 [9-73](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/timeline/StreamingReasoningMessagePipeline.kt#L9-L73)
- `feature/chat/.../timeline/TurnCommitCoordinator.kt`：最终助手消息结构/落库 [18-75](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/timeline/TurnCommitCoordinator.kt#L18-L75)

### 消息渲染与交互

- `feature/chat/.../ui/viewmodel/ChatListItem.kt`：UI 类型与投影 [9-97](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatListItem.kt#L9-L97)、分类规则 [163-235](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatListItem.kt#L163-L235)
- `feature/chat/.../ui/message/ChatMessageMenu.kt`：消息菜单 [46-168](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/message/ChatMessageMenu.kt#L46-L168)
- `feature/chat/.../ui/viewmodel/QuoteReply.kt`：引用格式与解析 [6-57](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/QuoteReply.kt#L6-L57)、编码/解析 [69-179](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/QuoteReply.kt#L69-L179)
- `feature/chat/.../ui/message/ChatStatusItems.kt`：typing、生图、再生成、推理 [53-342](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/message/ChatStatusItems.kt#L53-L342)
- `feature/chat/.../ui/message/VoiceMessageItem.kt`：语音/AI transcript [21-90](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/message/VoiceMessageItem.kt#L21-L90)
- `feature/chat/.../ui/message/AttachmentMessageItem.kt`：视频/文件打开 [11-50](../../../予念/feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/message/AttachmentMessageItem.kt#L11-L50)

### 数据库

- `core/database/.../model/ChatMessage.kt`：领域消息字段 [8-25](../../../予念/core/database/src/main/java/com/yunian/ai/database/model/ChatMessage.kt#L8-L25)
- `core/database/.../model/Message.kt`：metadata 表/索引 [7-47](../../../予念/core/database/src/main/java/com/yunian/ai/database/model/Message.kt#L7-L47)
- `core/database/.../model/MessageBody.kt`：body 表与级联删除 [7-24](../../../予念/core/database/src/main/java/com/yunian/ai/database/model/MessageBody.kt#L7-L24)
- `core/database/.../model/MessageType.kt`：消息枚举 [7-25](../../../予念/core/database/src/main/java/com/yunian/ai/database/model/MessageType.kt#L7-L25)
- `core/database/.../dao/MessageDao.kt`：元数据分页/正文批取 [20-83](../../../予念/core/database/src/main/java/com/yunian/ai/database/dao/MessageDao.kt#L20-L83)；写入/删除 [229-383](../../../予念/core/database/src/main/java/com/yunian/ai/database/dao/MessageDao.kt#L229-L383)
- `core/database/.../repository/ChatRepository.kt`：缓存/分页/正文 [25-113](../../../予念/core/database/src/main/java/com/yunian/ai/database/repository/ChatRepository.kt#L25-L113)；写删清空 [115-164](../../../予念/core/database/src/main/java/com/yunian/ai/database/repository/ChatRepository.kt#L115-L164)；上下文过滤 [179-215](../../../予念/core/database/src/main/java/com/yunian/ai/database/repository/ChatRepository.kt#L179-L215)；批写/摘要 [338-435](../../../予念/core/database/src/main/java/com/yunian/ai/database/repository/ChatRepository.kt#L338-L435)
- `core/database/.../repository/MessageWriteCoordinator.kt`：写入队列与不可取消 committed 回调 [13-43](../../../予念/core/database/src/main/java/com/yunian/ai/database/repository/MessageWriteCoordinator.kt#L13-L43)、批处理 [69-118](../../../予念/core/database/src/main/java/com/yunian/ai/database/repository/MessageWriteCoordinator.kt#L69-L118)

### Agent / 记忆 / 技能 / 世界书

- `core/agent/.../AgentDialogueCoordinator.kt`：通道/通话路径安全、落库、记忆 [75-183](../../../予念/core/agent/src/main/kotlin/com/yunian/ai/agent/AgentDialogueCoordinator.kt#L75-L183)、工具装配 [279-326](../../../予念/core/agent/src/main/kotlin/com/yunian/ai/agent/AgentDialogueCoordinator.kt#L279-L326)
- `core/agent/.../AgentFacade.kt`：技能目录与按需加载 [322-386](../../../予念/core/agent/src/main/kotlin/com/yunian/ai/agent/AgentFacade.kt#L322-L386)、记忆选择/工具 [497-552](../../../予念/core/agent/src/main/kotlin/com/yunian/ai/agent/AgentFacade.kt#L497-L552)
- `core/agent/.../worldbook/WorldbookRepository.kt`：角色有效世界书选择与每回合同步 [165-195](../../../予念/core/agent/src/main/kotlin/com/yunian/ai/agent/worldbook/WorldbookRepository.kt#L165-L195)

## 10. iOS 重写必须保持的语义 vs Android 特有可舍弃部分

### 必须保持的功能语义

1. `companionId` 是会话、草稿、设置、角色上下文和生成生命周期的主隔离键。
2. 用户消息先可靠落库、再排队生成；失败不应吞掉用户已发送内容。
3. typing 必须入队即乐观出现，不能被合并窗口、安全 pipeline、工具执行或网络预处理阻塞。
4. 生成中允许继续发消息；新消息打断旧轮时必须用“是否已有助手结果真正落库”决定合并还是丢弃旧 batch，防重复回复。
5. 临时过程态（typing、负 ID reasoning、实时工具活动、生图等待）与正式持久消息必须区分；取消/失败要清临时态。
6. 助手一轮可落多条气泡/表情；每条有独立稳定 ID，同时共享 turnId/eventIndex，保证再生成、工具卡和审计关联。
7. 引用必须携带原消息 ID、作者、摘要、媒体类型；点击能定位并在必要时补拉历史；原消息删除时明确报错。
8. REASONING/TOOL_ACTIVITY 不进入模型历史、会话摘要或未读数；正文解密失败也不进入模型上下文。
9. Room 的 metadata/body 分离语义需要在 iOS 对应为“列表骨架可先到、正文懒加载/失败重试”，不要求复制表结构，但要保留状态。
10. 消息顺序由 `(timestamp,id)` 稳定排序，分页也用双游标；加载更早消息不能跳动当前阅读位置。
11. 用户读历史时，新消息不能强抢滚动位置；应累计新消息提示；用户自己发送则回到底部。
12. 图片、语音、视频等本地媒体必须处理文件失效、权限拒绝、外部打开失败；语音 STT 失败仍保留原语音。
13. 再生成先替换目标助手消息及同轮过程卡，不新增用户消息；撤回/清空需要真正删除数据库、索引、缓存/摘要对应状态。
14. Agent 回合必须接入角色资料、会话历史、记忆、技能、世界书、工具授权与确认；这些不是 Android UI 装饰，而是回复语义来源。
15. 错误分类要可见：无 API、封禁、内容阻断、角色缺失、超时/网络、空响应、中止、媒体失败、正文加载失败。
16. 拉黑语义需产品统一。建议保持详情文案所表达的业务语义：禁止对方主动发消息，但允许用户手动发；Android 当前隐藏输入栏属于冲突实现，不应盲抄。

### Android 特有、可舍弃或替换

- Compose `LazyColumn(reverseLayout=true)`、CompositionLocal、StateFlow 的具体组织方式。
- 微信式外形、液态玻璃、果冻动画、渐变/自定义背景的绘制技术；功能上只需支持会话背景设置即可。
- Android `FileProvider`、Activity Result contracts、URI cache copy、MIME/魔数拖放实现；iOS 用 PhotosPicker、PHPicker/AVFoundation、UIDocumentPicker/Transferable 等原生机制重做。
- Android 麦克风/相机权限 API 和 toast/snackbar 组件。
- ADPF、硬件 tier、RenderNode/backdrop 性能策略。
- 微信 proactive sync 的 Android 接线；若 iOS 产品不支持该渠道可舍弃，但不要影响本地消息落库顺序。
- 500ms Compose 导航防抖、进入转场后延迟 mark-read 等纯平台性能细节。
- 当前未完成/伪能力：位置分享、真正视频通话、文件发送入口、用户手动停止生成、正文逐 token typing。可以不复制；若新增要作为 iOS 增强单独设计。

---

## 附：调研结论中的高风险差异

- Android 当前“文本流式”更多是架构接口与分段送达，不是正文逐 token 实时显示。
- `ChatState.pipelineStage` 写着完整阶段，但实际 `MessagePipelineRunner` 只经历 VALIDATE→SEND，且 ContentFilter 被禁用；不要按注释虚构完整可见 pipeline。
- Android 拉黑 UI 与确认文案冲突。
- Android 多图选择是逐张替换生成，存在只有最后一图得到回复的行为。
- Android 没有显式空态，也没有手动停止 AI 按钮。
- 撤回/再生成使用乐观删除，数据库失败不回滚。
