# 恋语模块化重构实施计划

> **目标：** 将单模块 `:app` 重构为 14 个模块的模块化架构，功能完全不受影响。

---

## 文件结构规划

```
LianYu/
├── app/                                    # 入口模块
│   ├── build.gradle.kts
│   └── src/main/java/com/lianyu/ai/
│       ├── MainActivity.kt                 # 导航路由
│       └── LianYuApplication.kt            # Application
│
├── core/
│   ├── common/                             # 公共工具
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/lianyu/ai/common/
│   │       ├── BanManager.kt
│   │       ├── BatteryOptimizationHelper.kt
│   │       ├── ContentFilter.kt
│   │       ├── FrameRateManager.kt
│   │       ├── HardwareInfo.kt
│   │       ├── ImageUtils.kt
│   │       └── AppForegroundTracker.kt
│   │
│   ├── database/                           # Room 数据库
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/lianyu/ai/database/
│   │       ├── AppDatabase.kt
│   │       ├── dao/
│   │       │   ├── ApiConfigDao.kt
│   │       │   ├── ChatGroupDao.kt
│   │       │   ├── ChatMessageDao.kt
│   │       │   ├── CompanionDao.kt
│   │       │   ├── GroupMessageDao.kt
│   │       │   └── MemoryDao.kt
│   │       └── model/
│   │           ├── ApiConfig.kt
│   │           ├── ChatGroup.kt
│   │           ├── ChatMessage.kt
│   │           ├── Companion.kt
│   │           ├── GroupMessage.kt
│   │           └── MemoryEntry.kt
│   │
│   ├── network/                            # 网络层
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/lianyu/ai/network/
│   │       ├── AiService.kt
│   │       ├── OpenAiApi.kt
│   │       ├── AnthropicApi.kt
│   │       ├── GeminiApi.kt
│   │       └── dto/
│   │           └── (request/response data classes)
│   │
│   ├── security/                           # 安全加密
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── java/com/lianyu/ai/security/
│   │       │   ├── NativeBridge.kt
│   │       │   └── SecurityGuard.kt
│   │       └── cpp/
│   │           ├── Android.mk
│   │           ├── Application.mk
│   │           ├── native-bridge.cpp
│   │           └── version-script.map
│   │
│   └── ui-common/                          # 通用 UI
│       ├── build.gradle.kts
│       └── src/main/java/com/lianyu/ai/uicommon/
│           ├── theme/
│           │   ├── Theme.kt
│           │   ├── Color.kt
│           │   └── Type.kt
│           └── component/
│               ├── AnimatedBackground.kt
│               ├── LiquidGlass.kt
│               ├── ShimmerEffect.kt
│               ├── SpringAnimations.kt
│               ├── UpdateDialog.kt
│               ├── ChatBackground.kt
│               └── ChatBackgroundCache.kt
│
├── feature/
│   ├── companion/
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/lianyu/ai/feature/companion/
│   │       ├── ui/
│   │       │   ├── screen/
│   │       │   │   ├── CompanionListScreen.kt
│   │       │   │   ├── CreateCompanionScreen.kt
│   │       │   │   └── ContactsScreen.kt
│   │       │   └── viewmodel/
│   │       │       ├── CompanionListViewModel.kt
│   │       │       └── CreateCompanionViewModel.kt
│   │       └── data/
│   │           └── CompanionRepository.kt
│   │
│   ├── chat/
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/lianyu/ai/feature/chat/
│   │       ├── ui/
│   │       │   ├── screen/
│   │       │   │   └── ChatScreen.kt
│   │       │   └── viewmodel/
│   │       │       └── ChatViewModel.kt
│   │       └── data/
│   │           └── ChatRepository.kt
│   │
│   ├── groupchat/
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/lianyu/ai/feature/groupchat/
│   │       ├── ui/
│   │       │   ├── screen/
│   │       │   │   ├── GroupChatScreen.kt
│   │       │   │   └── CreateGroupScreen.kt
│   │       │   └── viewmodel/
│   │       │       ├── GroupChatViewModel.kt
│   │       │       └── ChatGroupViewModel.kt
│   │       └── data/
│   │           ├── ChatGroupRepository.kt
│   │           └── GroupMessageRepository.kt
│   │
│   ├── memory/
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/lianyu/ai/feature/memory/
│   │       ├── ui/
│   │       │   ├── screen/
│   │       │   │   └── MemoryScreen.kt
│   │       │   └── viewmodel/
│   │       │       └── MemoryViewModel.kt
│   │       └── data/
│   │           └── MemoryRepository.kt
│   │
│   ├── settings/
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/lianyu/ai/feature/settings/
│   │       ├── ui/
│   │       │   ├── screen/
│   │       │   │   ├── SettingsScreen.kt
│   │       │   │   ├── ThemeScreen.kt
│   │       │   │   ├── LanguageScreen.kt
│   │       │   │   ├── FrameRateScreen.kt
│   │       │   │   └── CheckUpdateScreen.kt
│   │       │   └── viewmodel/
│   │       │       ├── SettingsViewModel.kt
│   │       │       ├── ThemeViewModel.kt
│   │       │       └── LanguageViewModel.kt
│   │       └── data/
│   │           ├── UserRepository.kt
│   │           └── ApiConfigRepository.kt
│   │
│   ├── profile/
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/lianyu/ai/feature/profile/
│   │       ├── ui/
│   │       │   ├── screen/
│   │       │   │   ├── ProfileScreen.kt
│   │       │   │   ├── HomeScreen.kt
│   │       │   │   ├── AboutScreen.kt
│   │       │   │   ├── AgreementScreen.kt
│   │       │   │   ├── AgreementViewScreen.kt
│   │       │   │   └── BanScreen.kt
│   │       │   └── viewmodel/
│   │       │       ├── HomeViewModel.kt
│   │       │       └── ProfileViewModel.kt
│   │       └── data/
│   │           └── (repositories if needed)
│   │
│   ├── update/
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/lianyu/ai/feature/update/
│   │       └── AppUpdateManager.kt
│   │
│   └── notification/
│       ├── build.gradle.kts
│       └── src/main/java/com/lianyu/ai/feature/notification/
│           ├── NotificationHelper.kt
│           ├── CompanionKeepAliveService.kt
│           ├── CompanionMessageWorker.kt
│           ├── AiReplyWorker.kt
│           └── BootReceiver.kt
│
├── settings.gradle.kts                     # 注册所有模块
└── gradle/
    └── libs.versions.toml                  # 版本号管理
```

---

## Phase 1: 创建 Core 基础模块

### Task 1.1: 创建 `core:common` 模块

**目标：** 迁移所有公共工具类到独立模块

**步骤：**
1. 创建 `core/common/build.gradle.kts`
2. 创建目录结构
3. 迁移工具类（保持包名不变或调整为 `com.lianyu.ai.common`）
4. 更新 `:app` 的依赖

**文件清单：**
- `core/common/build.gradle.kts` (新建)
- `core/common/src/main/java/com/lianyu/ai/common/BanManager.kt` (迁移)
- `core/common/src/main/java/com/lianyu/ai/common/BatteryOptimizationHelper.kt` (迁移)
- `core/common/src/main/java/com/lianyu/ai/common/ContentFilter.kt` (迁移)
- `core/common/src/main/java/com/lianyu/ai/common/FrameRateManager.kt` (迁移)
- `core/common/src/main/java/com/lianyu/ai/common/HardwareInfo.kt` (迁移)
- `core/common/src/main/java/com/lianyu/ai/common/ImageUtils.kt` (迁移)
- `core/common/src/main/java/com/lianyu/ai/common/AppForegroundTracker.kt` (迁移)

---

### Task 1.2: 创建 `core:database` 模块

**目标：** 迁移 Room 数据库、DAO、Entity

**步骤：**
1. 创建 `core/database/build.gradle.kts`（需要 Room、KSP 插件）
2. 创建目录结构
3. 迁移所有 Entity 和 DAO
4. 迁移 `AppDatabase.kt`
5. 更新 `:app` 的依赖

**注意：**
- `AppDatabase` 中的 `Converters` 类也要迁移
- 数据库版本号保持不变（version = 6）
- Schema 导出路径需要调整

---

### Task 1.3: 创建 `core:ui-common` 模块

**目标：** 迁移主题和通用 Compose 组件

**步骤：**
1. 创建 `core/ui-common/build.gradle.kts`
2. 创建目录结构
3. 迁移主题文件（Theme.kt, Color.kt, Type.kt）
4. 迁移通用组件（AnimatedBackground, LiquidGlass 等）
5. 更新 `:app` 的依赖

**注意：**
- 主题文件中的 `LianYuTheme` 需要保留在 `:app` 中引用
- `ChatBackgroundCache` 需要 `Context`，确保依赖正确

---

## Phase 2: 创建 Core 能力模块

### Task 2.1: 创建 `core:network` 模块

**目标：** 迁移 Retrofit、API 接口、AiService

**步骤：**
1. 创建 `core/network/build.gradle.kts`
2. 迁移 API 接口和数据类
3. 迁移 `AiService.kt`
4. 更新 `:app` 的依赖

**注意：**
- `AiService` 依赖 `core:database` 中的 `ApiConfigRepository` 和 `MemoryRepository`
- 需要处理循环依赖问题（network 依赖 database，database 不应依赖 network）
- 解决方案：`AiService` 不直接依赖 Repository，而是通过参数传递或使用接口抽象

**调整方案：**
```kotlin
// AiService 改为不直接依赖 Repository
class AiService(
    private val apiConfigDao: ApiConfigDao,
    private val memoryDao: MemoryDao,
    context: Context
)
```

---

### Task 2.2: 创建 `core:security` 模块

**目标：** 迁移 JNI 安全层

**步骤：**
1. 创建 `core/security/build.gradle.kts`
2. 迁移 `NativeBridge.kt` 和 `SecurityGuard.kt`
3. 迁移 C++ 代码和 NDK 构建配置
4. 更新 `:app` 的依赖

**注意：**
- NDK 构建配置需要正确设置 `externalNativeBuild`
- `version-script.map` 文件也要迁移

---

## Phase 3: 创建 Feature 模块

### Task 3.1: 创建 `feature:update` 模块

**依赖：** `core:common`, `core:ui-common`

**迁移文件：**
- `AppUpdateManager.kt`
- `CheckUpdateScreen.kt`

---

### Task 3.2: 创建 `feature:memory` 模块

**依赖：** `core:database`, `core:ui-common`

**迁移文件：**
- `MemoryScreen.kt`
- `MemoryViewModel.kt`
- `MemoryRepository.kt`

---

### Task 3.3: 创建 `feature:companion` 模块

**依赖：** `core:database`, `core:ui-common`

**迁移文件：**
- `CompanionListScreen.kt`
- `CreateCompanionScreen.kt`
- `ContactsScreen.kt`
- `CompanionListViewModel.kt`
- `CreateCompanionViewModel.kt`
- `CompanionRepository.kt`

---

### Task 3.4: 创建 `feature:chat` 模块

**依赖：** `core:database`, `core:network`, `core:ui-common`, `core:common`

**迁移文件：**
- `ChatScreen.kt`
- `ChatViewModel.kt`
- `ChatRepository.kt`

---

### Task 3.5: 创建 `feature:groupchat` 模块

**依赖：** `core:database`, `core:network`, `core:ui-common`, `core:common`

**迁移文件：**
- `GroupChatScreen.kt`
- `CreateGroupScreen.kt`
- `GroupChatViewModel.kt`
- `ChatGroupViewModel.kt`
- `GroupMessageRepository.kt`
- `ChatGroupRepository.kt`

---

### Task 3.6: 创建 `feature:notification` 模块

**依赖：** `core:database`, `core:network`, `core:common`

**迁移文件：**
- `NotificationHelper.kt`
- `CompanionKeepAliveService.kt`
- `CompanionMessageWorker.kt`
- `AiReplyWorker.kt`
- `BootReceiver.kt`

**注意：**
- `CompanionKeepAliveService` 需要引用 `MainActivity`，使用 `Class.forName()` 或 Intent action 解耦
- `BootReceiver` 同样需要解耦对 Service 的直接引用

---

### Task 3.7: 创建 `feature:settings` 模块

**依赖：** `core:database`, `core:ui-common`, `core:common`, `feature:update`

**迁移文件：**
- `SettingsScreen.kt`
- `ThemeScreen.kt`
- `LanguageScreen.kt`
- `FrameRateScreen.kt`
- `CheckUpdateScreen.kt`
- `SettingsViewModel.kt`
- `ThemeViewModel.kt`
- `LanguageViewModel.kt`
- `UserRepository.kt`
- `ApiConfigRepository.kt`

---

### Task 3.8: 创建 `feature:profile` 模块

**依赖：** `core:database`, `core:ui-common`, `core:common`, `feature:settings`, `feature:memory`, `feature:update`

**迁移文件：**
- `ProfileScreen.kt`
- `HomeScreen.kt`
- `AboutScreen.kt`
- `AgreementScreen.kt`
- `AgreementViewScreen.kt`
- `BanScreen.kt`
- `HomeViewModel.kt`
- `ProfileViewModel.kt`

---

## Phase 4: 精简 `:app` 模块

### Task 4.1: 重构 `MainActivity.kt`

**目标：** 移除所有业务 Screen 的导入，仅保留导航路由

**步骤：**
1. 更新所有 Screen 的 import 路径
2. 确保 `NavHost` 中引用的 Screen 来自正确的 feature 模块
3. 移除不再需要的 import

### Task 4.2: 重构 `LianYuApplication.kt`

**目标：** 更新初始化逻辑，引用新的模块路径

**步骤：**
1. 更新 `SecurityGuard` 和 `NativeBridge` 的 import
2. 更新 `ChatBackgroundCache` 的 import

### Task 4.3: 更新 `:app/build.gradle.kts`

**目标：** 添加所有 feature 和 core 模块的依赖

```kotlin
dependencies {
    // Core modules
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:ui-common"))

    // Feature modules
    implementation(project(":feature:companion"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:groupchat"))
    implementation(project(":feature:memory"))
    implementation(project(":feature:notification"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:update"))

    // ... 现有依赖
}
```

### Task 4.4: 更新 `settings.gradle.kts`

**目标：** 注册所有新模块

```kotlin
include(":app")
include(":core:common")
include(":core:database")
include(":core:network")
include(":core:security")
include(":core:ui-common")
include(":feature:companion")
include(":feature:chat")
include(":feature:groupchat")
include(":feature:memory")
include(":feature:notification")
include(":feature:profile")
include(":feature:settings")
include(":feature:update")
```

---

## Phase 5: 引入 Hilt（可选）

### Task 5.1: 添加 Hilt 依赖

在 `libs.versions.toml` 和根目录 `build.gradle.kts` 中添加 Hilt 插件。

### Task 5.2: 创建 Hilt Modules

为每个需要依赖注入的模块创建 Hilt Module。

### Task 5.3: 替换 ViewModelFactory

删除手动 Factory，使用 Hilt 的 `@HiltViewModel`。

---

## 验证清单

### 构建验证
- [ ] `./gradlew assembleDebug` 成功
- [ ] `./gradlew assembleRelease` 成功
- [ ] 无编译错误
- [ ] 无循环依赖警告

### 功能验证
- [ ] 应用正常启动
- [ ] 首页正常显示
- [ ] 创建 AI 伴侣正常
- [ ] 单聊功能正常
- [ ] 群聊功能正常
- [ ] 记忆系统正常
- [ ] 设置页面正常
- [ ] 主题切换正常
- [ ] 语言切换正常
- [ ] 通知推送正常
- [ ] 应用更新检查正常

---

## 风险与应对

| 风险 | 影响 | 应对方案 |
|------|------|----------|
| 包名变更导致编译错误 | 高 | 使用 IDE 全局重构，逐个模块验证 |
| 循环依赖 | 高 | 严格遵循依赖规则，发现问题立即调整架构 |
| 数据库迁移问题 | 中 | 保持数据库版本号不变，不修改 Entity 结构 |
| NDK 构建失败 | 中 | 保持 NDK 配置不变，路径正确迁移 |
| 主题/颜色引用错误 | 低 | 全局搜索颜色引用，更新 import |
| ViewModelFactory 失效 | 低 | Phase 5 引入 Hilt 解决 |

---

## 提交策略

每个 Phase 作为一个独立的 commit：

```bash
# Phase 1
git commit -m "refactor(core): create core-common, core-database, core-ui-common modules

- Migrate utility classes to core-common
- Migrate Room database to core-database
- Migrate theme and UI components to core-ui-common"

# Phase 2
git commit -m "refactor(core): create core-network and core-security modules

- Migrate Retrofit and API services to core-network
- Migrate JNI security layer to core-security"

# Phase 3 (每个 feature 一个 commit)
git commit -m "feat(feature): create feature-chat module

- Migrate ChatScreen, ChatViewModel, ChatRepository"

# Phase 4
git commit -m "refactor(app): slim down app module to entry point only

- Remove business logic from app module
- Update dependencies to include all feature modules
- Keep only MainActivity and LianYuApplication"
```
