# Debug Session: settings-crash-local-model-stuck

## Status: [FIXED]

## Symptoms
1. 点API设置闪退
2. 本地模型无法使用，一直显示"对方正在输入"

## Hypotheses & Analysis
1. **API设置闪退**: `fetchTimestamps` 线程安全问题 - `ConcurrentModificationException`
2. **本地模型卡住**: `LocalAiService.generate()` 中 `synchronized` 块内调用 `newEngine.initialize()` 卡住导致死锁
3. **本地模型卡住**: `Mutex.withLock` 和 `withTimeout` 一起使用导致锁不被释放

## Root Causes

### 1. 本地模型"一直显示对方正在输入"
**根因**: `LocalAiService.generate()` 中使用了 `synchronized(engineLock)` 包裹引擎创建和初始化。如果 `newEngine.initialize()` 卡住（模型文件损坏/不兼容），整个锁会被长时间持有，后续所有调用都会阻塞。

**修复**:
- 使用 `AtomicBoolean` 的 `compareAndSet` 替代 `synchronized` 进行初始化互斥
- 将 `initialize()` 移出 `synchronized` 块，使用 `withTimeout` 保护
- 快速检查现有引擎时仍使用 `synchronized`，但只进行简单引用检查

### 2. API设置闪退
**根因**: `SettingsViewModel.fetchTimestamps` 是普通的 `mutableListOf`，在 `Dispatchers.IO` 线程中并发访问可能导致 `ConcurrentModificationException`。

**修复**:
- 使用 `Collections.synchronizedList()` 包装列表

### 3. 状态不一致
**根因**: `SettingsScreen` 中 `LocalModelUiState(model = model)` 只设置了 `model` 参数，`modelId` 使用默认值，可能导致状态不匹配。

**修复**:
- 显式设置 `modelId = model.id`

## Files Changed
1. `feature/localmodel/src/main/java/com/lianyu/ai/feature/localmodel/LocalAiService.kt` - 修复死锁问题
2. `feature/settings/src/main/java/com/lianyu/ai/feature/settings/ui/viewmodel/SettingsViewModel.kt` - 修复线程安全
3. `feature/settings/src/main/java/com/lianyu/ai/feature/settings/ui/screen/SettingsScreen.kt` - 修复状态构造

## Verification
- [x] 编译通过
- [ ] 运行时测试（需要用户验证）
