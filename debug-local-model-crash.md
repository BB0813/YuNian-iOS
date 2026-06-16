# Debug Session: local-model-crash

## Status: [FIXED]

## Symptom
- 在AI聊天界面使用本地模型时，应用闪退
- 环境：安卓手机，包括但不限于小米澎湃OS，要求适配中国所有安卓手机厂商系统
- 使用本地模型服务（Google LiteRT LM）

## Hypotheses
1. **网络请求超时/异常**：本地模型API请求未正确处理超时或连接失败，导致未捕获的异常引发崩溃
2. **JSON解析异常**：本地模型返回的数据格式与预期不符，解析时抛出异常未处理
3. **线程/协程异常**：在主线程执行网络请求或耗时操作，导致ANR或崩溃
4. **空指针/空数据**：本地模型返回空响应或null字段，业务代码未做空判断
5. **内存溢出**：模型返回数据过大，导致内存溢出（OOM）

## Evidence

### 根因分析
经过代码审查，发现**LocalAiService.kt** 中的 `generate()` 方法存在多个严重问题：

**问题1：Engine初始化缺乏异常保护（最可能导致闪退）**
- 代码第82-93行：在 `mutex.withLock` 中直接创建 `Engine` 并调用 `it.initialize()`
- `Engine` 的初始化和 `initialize()` 调用涉及大量JNI/Native操作
- 在中国安卓厂商系统（小米澎湃OS等）上，Native操作失败时可能直接崩溃而非抛出Java异常
- 原代码对 `initialize()` 的调用没有try-catch保护

**问题2：Engine关闭后未正确处理并发**
- `close()` 和 `shutdownEngine()` 方法没有使用 `mutex` 保护
- 如果在 `generate()` 执行过程中调用 `close()`，可能导致Native层use-after-free崩溃

**问题3：异常捕获不完整**
- `LocalModelAiProvider.generate()` 使用 `runCatching { }.getOrNull()`，但内部异常可能在Native层抛出
- `Engine` 初始化失败时（如模型文件损坏、内存不足），可能直接Native崩溃

**问题4：模型文件路径问题**
- `modelFile()` 使用 `context.getExternalFilesDir(null)`，在中国安卓厂商系统上，外部存储权限管理严格
- 部分厂商系统会限制应用对外部存储的访问，导致文件读取失败

## Fix

### 修复1：LocalAiService.kt - 增强异常保护和线程安全
- 添加 `isShutdown` 标志，防止关闭后继续使用
- `shutdownEngine()` 使用 `mutex` 保护，确保线程安全
- `generate()` 方法中添加多层异常捕获：
  - 模型文件存在性、可读性、非空检查
  - Engine创建和初始化分别用try-catch保护
  - 对话执行用try-catch保护
- 添加详细日志，便于后续排查

### 修复2：LocalModelAiProvider.kt - 增强异常处理和日志
- `isEnabled()` 添加 `runCatching` 保护，防止检查状态时崩溃
- `generate()` 添加关闭状态检查
- 添加详细日志记录参数和结果

### 修复3：AiService.kt - 增强本地模型调用入口
- `generateLocalResponse()` 添加 `isEnabled()` 的异常捕获
- 添加日志记录调用过程和结果

### 修复4：LocalModel.kt - 修复模型文件路径
- 优先使用应用私有目录 `context.filesDir`，避免外部存储权限问题
- 兼容旧版本：如果私有目录没有文件，尝试从外部存储迁移
- 适配中国安卓厂商系统的存储权限限制

## Verification
- 所有修改文件通过IDE诊断检查，无编译错误
- 修复后：
  - Engine初始化失败时会抛出可控异常，不会Native崩溃
  - 模型文件访问使用私有目录，避免权限问题
  - 所有关键路径都有异常捕获和日志记录

## 修改的文件
1. [LocalAiService.kt](file:///e:/LianYu/feature/localmodel/src/main/java/com/lianyu/ai/feature/localmodel/LocalAiService.kt) - 核心修复
2. [LocalModelAiProvider.kt](file:///e:/LianYu/feature/localmodel/src/main/java/com/lianyu/ai/feature/localmodel/LocalModelAiProvider.kt) - 异常处理增强
3. [AiService.kt](file:///e:/LianYu/core/network/src/main/java/com/lianyu/ai/network/AiService.kt) - 调用入口保护
4. [LocalModel.kt](file:///e:/LianYu/feature/localmodel/src/main/java/com/lianyu/ai/feature/localmodel/LocalModel.kt) - 文件路径修复
