# 移除本地模型功能 — 变更说明

## 背景
本地模型（Gemma/LiteRT）初始化会阻塞远程 API 调用，导致 AI 已连接但一直不回复。现彻底移除本地模型功能，仅保留远程 API 调用路径。

---

## 变更摘要
- **删除文件**: 14 个
- **修改文件**: 17 个
- **净减行数**: ~1,164 行

---

## 详细变更

### 1. 删除 `:feature:localmodel` 模块（整个模块）
| 文件 | 说明 |
|------|------|
| `feature/localmodel/build.gradle.kts` | 模块构建配置 |
| `feature/localmodel/src/main/AndroidManifest.xml` | 模块清单 |
| `feature/localmodel/src/main/java/.../LocalAiService.kt` | 本地 AI 推理服务（LiteRT 引擎封装） |
| `feature/localmodel/src/main/java/.../LocalModel.kt` | 本地模型数据类 |
| `feature/localmodel/src/main/java/.../LocalModelAiProvider.kt` | 本地模型 AI Provider 实现 |
| `feature/localmodel/src/main/java/.../LocalModelFileDeletion.kt` | 模型文件删除逻辑 |
| `feature/localmodel/src/main/java/.../LocalModelManager.kt` | 模型下载/启用/禁用管理器 |
| `feature/localmodel/src/main/java/.../LocalModelPreferences.kt` | 本地模型偏好设置 |
| `feature/localmodel/src/main/java/.../LocalModelState.kt` | 模型状态定义 |
| `feature/localmodel/src/test/.../LocalModelCatalogTest.kt` | 测试 |
| `feature/localmodel/src/test/.../LocalModelFileDeletionTest.kt` | 测试 |
| `feature/localmodel/src/test/.../LocalModelStateResolverTest.kt` | 测试 |

### 2. 删除 `core/common/localmodel/` 公共接口
| 文件 | 说明 |
|------|------|
| `core/common/.../LocalAiModelProvider.kt` | 本地模型 Provider 接口 |
| `core/common/.../LocalModelFileManager.kt` | 模型文件管理接口 |
| `core/common/.../LocalModelStateManager.kt` | 模型状态管理接口 |

### 3. 核心服务层修改
**`core/network/.../AiService.kt`**
- 删除 `LocalAiModelProvider` import
- 删除 `localModelProvider` 实例字段
- 删除 `providerFactory` 静态工厂
- 删除 `generateLocalResponse()` 方法（含 8 秒超时保护）
- 删除 `close()` 方法
- `sendMessage()` / `generateProactiveMessage()` 不再先尝试本地模型，直接走远程 API

### 4. ViewModel 层修改
**`feature/chat/.../ChatViewModel.kt`**
- 删除 `LocalModelStateManager` import
- `loadAvailableApis()` 不再收集本地模型启用状态，不再注入 `ApiProviderInfo.LOCAL`
- `switchApi()` 删除 LOCAL 特殊处理逻辑
- `onCleared()` 删除 `aiService.close()`

**`feature/settings/.../SettingsViewModel.kt`**
- 删除所有本地模型相关 import（`LocalModelCatalog`、`LocalModelManager`、`LocalModelUiState`）
- 删除 `localModelManager` 实例
- 删除 `localModelState`、`modelStates` 状态流
- 删除 init 块中的模型状态收集逻辑
- 删除 `selectModel()`、`startGemmaDownload()`、`downloadModel()`、`cancelGemmaDownload()`、`enableGemma()`、`disableGemma()`、`deleteGemma()`、`refreshLocalModel()`
- 删除 `onCleared()` 中的 `localModelManager.close()`

**`feature/groupchat/.../GroupChatViewModel.kt`**
- `onCleared()` 删除 `aiService.close()`

### 5. Application 层修改
**`app/.../LianYuApplication.kt`**
- 删除 `LocalModelAiProvider` import
- 删除 `AiService.providerFactory = { LocalModelAiProvider(it) }` 初始化

### 6. 数据模型修改
**`core/ui-common/.../ApiProviderInfo.kt`**
- 删除 `LOCAL` 常量定义（`ApiProviderInfo("LOCAL", "本地模型", Color(0xFF00C853))`）
- `fromName()` 删除 `"LOCAL" -> LOCAL` 映射

### 7. Worker 层修改
**`feature/notification/.../CompanionMessageWorker.kt`**
- 删除 `try/finally` 中的 `aiService.close()`

**`feature/notification/.../AiReplyWorker.kt`**
- 删除 `try/finally` 中的 `aiService.close()`

**`feature/wechat/.../WeChatChatBridge.kt`**
- 删除 `close()` 方法

**`feature/wechat/.../WeChatAiReplyWorker.kt`**
- 删除 `bridge.close()` 调用

### 8. Gradle 配置修改
**`settings.gradle.kts`**
- 删除 `include(":feature:localmodel")`

**`app/build.gradle.kts`**
- 删除 `implementation(project(":feature:localmodel"))`

**`feature/settings/build.gradle.kts`**
- 删除 `implementation(project(":feature:localmodel"))`

**`gradle/libs.versions.toml`**
- 删除 `litertlm-android` 依赖声明
- 保留 `litertLm = "0.11.0"` 版本号（未使用，可后续清理）

### 9. ProGuard 配置修改
**`app/proguard-rules.pro`**
- 删除 `LocalModelManager` 的 keep 规则

---

## 影响范围
- **API 设置页**: 不再显示"本地模型"选项
- **聊天页 API 切换**: 不再出现 LOCAL 选项
- **设置页**: 删除本地模型下载/管理 UI（如之前已存在）
- **包体积**: 移除 LiteRT 依赖后 APK 体积减小
- **启动速度**: 不再初始化本地模型 Provider

---

## 测试建议
1. 编译通过 `:app:assembleDebug`
2. 验证 API 设置页无本地模型选项
3. 验证聊天页 API 切换正常
4. 验证远程 API 调用不再被阻塞
