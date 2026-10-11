# 予念 iOS · 单聊页实现规格（V1）

> 本规格同时受 `PRODUCT.md` 与 `APPLE-DESIGN-CONSTRAINTS.md` 约束。
> 功能语义来源于 Android 当前实现；可实现边界来源于 iOS 保留逻辑层。
> **禁止为了看起来完整而展示无实现的入口。**

## 1. 产品目标

聊天页要让用户感到**温暖、活泼、有陪伴感**，交互语言以 Apple Messages 为参照。
角色通过头像、名字、typing、回复节奏和内容存在；UI 不靠满屏紫色、渐变、发光或大幅角色背景抢戏。

## 2. V1 范围

### 必须完成

1. `companionId` 隔离会话、草稿与生成生命周期。
2. 加载伴侣头像/名字与最近 100 条历史。
3. 文本发送：trim；空文本不可发；清空草稿；用户消息保留。
4. typing 在发送后立即出现，不等待网络。
5. 流式显示正文与推理；推理默认收起，生成中可展开。
6. 显式“停止生成”；停止中有状态，结束后回 idle。
7. AI 正文持久化由现有 `ChatSession` 负责，不在 ViewModel 双重落库。
8. 展示文本、模型/用户表情、AI 生图结果。
9. **时间分隔线**：相邻消息间隔 ≥5 分钟时插入（Android 规格 §5；与 Apple Messages 一致）。
   这要求 `ChatSession.Message` 携带 `timestamp` —— 该字段原先不存在，属本规格新增的
   最小逻辑层改动（加法式，带默认值，不影响既有构造点）。
9. 打开后 mark read。
10. 错误可见且可关闭；无 API / Agent 未就绪 / 生成失败不静默。
11. Dynamic Type、VoiceOver、Reduce Motion、Reduce Transparency、44×44pt 命中区。
12. 用户阅读历史时新消息不抢滚动；用户发送时回到底部；底部有新消息提示。

### 明确隐藏（逻辑层未完成）

- 用户图片视觉输入；
- 语音消息 / STT / TTS / 通话；
- 可靠的重试 / 重新生成；
- 引用与消息定位；
- 精确 `(timestamp,id)` 分页；
- 表情持久化与真实发送；
- BanManager、完整输出过滤；
- 消息队列/连续输入 committed 协议；
- 完整领域工具确认。

这些能力不会显示 disabled 图标，不用“敬请期待”占位置；逻辑层补齐后再渐进出现。

## 3. 信息架构

### 导航层（系统 toolbar）

- leading：系统返回按钮；
- principal：44pt 头像 + 角色名；生成中副标题“正在输入…”；
- trailing：详情按钮（V1 无详情页时不展示）；
- 禁止自绘 toolbar 背景；由系统管理 Liquid Glass / scroll edge effect。

### 内容层（禁止 Liquid Glass）

- `ScrollView` + `LazyVStack`；
- 助手消息左对齐，用户消息右对齐；
- 消息不是“每条一个大卡片”：正文只包裹内容宽度；
- 用户气泡用 accent；助手气泡用标准 `Material`/surface；
- 相邻同角色消息缩小间距，角色切换/时间段放大间距；
- REASONING 是过程态，不进入普通消息视觉层级。

### 输入控制层

- 使用 `safeAreaInset(edge: .bottom)` 提供输入区域；它不是自绘导航 bar，不使用 Liquid Glass 背景；
- 多行 `TextField(axis: .vertical)`，上限约 5 行；
- 草稿为空：发送 disabled；生成中且有内容：发送仍可用（未来接消息队列前 V1 暂时防重入）；
- 生成中显示明确停止按钮；发送/停止命中区 ≥44×44pt；
- 不显示无实现的 + / 相册 / 语音 / 表情按钮。

## 4. 状态机

```text
booting
  ├─ companion/history ready → idle(empty|messages)
  └─ failed → error

idle
  └─ send(trimmed non-empty) → typing

typing
  ├─ first text delta → streaming
  ├─ reasoning delta → streaming(reasoning)
  ├─ stop → stopping
  └─ error → error(partial preserved)

streaming
  ├─ more deltas → streaming
  ├─ done → idle (正式消息由 ChatSession 追加/落库)
  ├─ stop → stopping
  └─ error → error(partial preserved)

stopping
  └─ session.isRunning == false → idle
```

`typing` 的现实映射：`session.isRunning && session.streamingText.isEmpty`。这不是 Android 完整 typing 状态机，UI 不得展示虚假的队列深度或工具阶段。

## 5. 数据与线程

- `ChatViewModel` 必须 `@MainActor`；页面只观察它，不散取 `AppEnvironment` 十几个对象；
- 持有一个 `ChatSession(companionId:)`；
- `appear()`：读取 companion → `session.loadHistory(limit:100)` → mark read；
- `sendText()`：清 draft → `session.send(text,in:environment)`；禁止自行调用 `runTurnStream`；
- `stopGenerating()`：先 phase=stopping，再 `session.cancel`；
- 页面消失时若仍生成，V1 不自动 cancel（避免切页导致用户回复消失）；但切换 companion 前必须避免多 session 同时订阅全局 sink；
- 消息 type 映射大小写不敏感（`text/TEXT`、`image/IMAGE`）。

## 6. Apple 约束

1. 内容层消息/卡片/气泡禁止 `.glassEffect()`（M1）。
2. toolbar 不加 `.toolbarBackground` / 自绘 blur（M3）。
3. 系统 toolbar 默认 scroll edge effect；禁止额外渐隐 overlay（M5）。
4. 正文使用系统语义字体；禁止固定字号（T1）。
5. 状态不能只靠颜色（T6）。
6. Reduce Motion 时去掉滚动/插入弹簧，只保留无动画或淡入（A2）。
7. Reduce Transparency 时自定义半透明 surface 改为不透明（A1）。
8. 常用控件命中区 ≥44×44pt（C4）。
9. destructive action 不使用 primary/prominent（C3）。

## 7. 真机验收

- 首次进入：标题、头像、最近历史正确；无历史时没有紫色大插画或卡片空态。
- 键盘：输入区随键盘移动；多行增长；点击消息区收键盘；不遮住最后一条。
- 发送：点击后用户文本即时出现、draft 清空、typing 立即出现。
- 流式：正文稳定增长；滚动到底时跟随；阅读旧消息时不抢位置。
- 停止：停止按钮可见可点；部分正文不消失；状态最终收束。
- 错误：无 API / 网络失败有明确文本且可关闭。
- 无障碍：最大 Dynamic Type、VoiceOver、Reduce Motion、Reduce Transparency、高对比模式逐项验证。
