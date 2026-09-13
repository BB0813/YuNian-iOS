# 数据库结构冻结规范（Schema Freeze）

> 适用模块：`core:database`
> 冻结基线：**Room version = 41**（`AppDatabase.SCHEMA_FROZEN_VERSION`）
> 目标：自 v41 起，无论新增多少功能、修复多少问题，**不再改动已有表、字段与索引**；用户覆盖升级后数据绝不丢失。

## 一、冻结基线

- 当前数据库 `yunian_database`，`version = 41`，共 22 个实体（Entity）。
- v41 是**最后一次** schema 变更：新增了一张通用键值表 `app_meta`，用于承载未来所有新增功能的持久化数据。
- 迁移链完整覆盖 1 → 41（`MIGRATION_1_6` … `MIGRATION_40_41`），`exportSchema = true`。

## 二、通用扩展机制（新增功能的数据落点）

新增功能需要持久化时，按以下优先级选择，**不得新增表/字段/索引**：

### 1. 键值对表 `AppMetaStore`（首选，全局配置/状态）

```kotlin
val store = ServiceRegistry.getOrThrow(AppMetaStore::class.java)

// 存任意可序列化对象
store.put("feature_x.config", MyConfig(a = 1), MyConfig.serializer())
// 读
val cfg: MyConfig? = store.get("feature_x.config", MyConfig.serializer())
// 读或初始化
val cfg = store.getOrPut("feature_x.config", MyConfig.serializer()) { MyConfig() }
// 删 / 判断存在
store.remove("feature_x.config")
store.contains("feature_x.config")
```

- 底层表：`app_meta(key TEXT PRIMARY KEY, value TEXT, updatedAt INTEGER)`，`value` 为 JSON。
- `key` 命名约定：`<模块>.<用途>`，如 `wechat.last_poll_seq`、`qqbot.session_id`。

### 2. JSON 扩展字段（实体上的局部扩展）

对既有实体的记录做局部扩展，用 `ExtJson` 工具（`readExt` / `writeExt` / `removeExt` / `hasExt`），把扩展数据塞进已有 `TEXT` 字段，**不改表结构**。

### 3. 确需关系查询的新数据

先评估能否用既有表 + ExtJson 表达。仍不满足时，**提冻结评审**，按第五节走向后兼容迁移，而不是直接加表。

## 三、建表与初始化：幂等、非破坏式（硬性约定）

- 建表只用 `CREATE TABLE IF NOT EXISTS`；建索引只用 `CREATE INDEX IF NOT EXISTS`。
- 种子数据只用 `INSERT OR IGNORE` 或先 `SELECT` 判断存在，禁止无脑 `INSERT`。
- **禁止**：`DROP TABLE`、`TRUNCATE`、`DROP INDEX`、删库重建、启动时重置数据（测试环境除外）。
- 已存在的历史迁移（如 `api_configs_new` 重建、`messages_v28` 重建）是**向后兼容的重建**（先建新表 → 拷贝数据 → 改名 → 删旧），非破坏式，保留即可，勿新增此类写法。

## 四、会改动数据库的代码清单与解耦

| 位置 | 现状 | 冻结后的约定 |
|------|------|--------------|
| `AppDatabase.kt` `@Database(entities=…)` | 22 个实体，version 41 | 冻结，禁止增删实体 |
| `AppDatabase.kt` `MIGRATIONS` 数组 | 1→41 全覆盖 | 冻结，禁止追加（除非走评审） |
| `AppDatabase.kt` `onCreate` 回调 | `seedApiProviderPresets`（`INSERT OR IGNORE`，幂等） | 保持幂等 |
| `DefaultCompanionSeeder.kt` | 已有 `existing != null` 幂等判断 | 保持幂等 |
| `SecurityDataSeeder.kt` | 安全基线种子 | 保持幂等，禁止重建 |
| `RolePresetStore.kt` / `RolePresets.kt` | 角色预设 | 新预设改走 `AppMetaStore` |
| `dao/*.kt`、`model/*.kt` | 业务读写 | 冻结；新查询只读现有表 |

**解耦原则**：业务功能（feature 模块）通过 `AppMetaStore` 或 Repository 读写，不再直接触碰 `@Database`/`Migration`。schema 与业务彻底分离。

## 五、必须调整结构时的向后兼容迁移

冻结不是永不迁移，而是"非必要不迁移、迁移必兼容"。流程：

1. 提冻结评审，说明为何 KV/ExtJson 无法满足。
2. 新增 `MIGRATION_<old>_<new>`，`version +1`，规则：
   - 加表：`CREATE TABLE IF NOT EXISTS`（允许新增表，但需评审）。
   - 加字段：`ALTER TABLE ... ADD COLUMN`（给默认值，旧行自动填充）。
   - 改字段/重建：必须走"建新表 → `INSERT INTO new SELECT ... FROM old` → `RENAME` → 删旧"的拷贝式重建，**全程保留旧数据**。
3. 旧数据平滑过渡：迁移内用 `INSERT ... SELECT` 搬运，绝不 `DROP` 后重建空表。
4. 迁移上线前，按第六节跑升级验证。

## 六、验证"升级后数据不丢失"

### 方法 A：自动化迁移测试（推荐，纳入 CI）

利用 `exportSchema = true` 导出的 schema JSON，用 `androidx.room:room-testing` 的 `MigrationTestHelper`：

```kotlin
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate40To41_preservesData() {
        // 1. 用 v40 schema 建库并写入真实数据
        helper.createDatabase("test-db", 40).apply {
            execSQL("INSERT INTO chat_messages(...) VALUES(...)")
            execSQL("INSERT INTO companions(...) VALUES(...)")
            close()
        }
        // 2. 跑迁移到 v41
        val db = helper.runMigrationsAndValidate("test-db", 41, true, MIGRATION_40_41)
        // 3. 断言旧数据完好
        db.query("SELECT COUNT(*) FROM chat_messages").use { it.moveToFirst(); assert(it.getInt(0) > 0) }
    }
}
```

### 方法 B：真机/模拟器手动验证（覆盖安装升级）

1. 安装旧版本（v40 schema），创建伴侣、发若干聊天消息、写入日记/记忆。
2. 用 `adb shell run-as <pkg> ls databases/` 确认 `yunian_database` 存在。
3. **覆盖安装**新版本（v41），不卸载。
4. 启动 App，确认：伴侣列表、聊天记录、记忆、设置全部原样存在，无"重建/清空"日志。
5. 查看日志无 `identity hash mismatch`、`recreating database` 等异常恢复字样（这些只在真正损坏时才应出现）。

### 方法 C：冷启动幂等性

1. 连续多次启动 App，确认 `app_meta`、`api_provider_presets`、companion 种子等**不重复插入**（`INSERT OR IGNORE` 生效）。
2. 用 `adb shell run-as <pkg> sqlite3 databases/yunian_database "SELECT COUNT(*) FROM app_meta"` 确认数值稳定。
