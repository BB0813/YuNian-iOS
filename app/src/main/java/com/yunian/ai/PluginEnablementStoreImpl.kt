package com.yunian.ai

import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.repository.AppMetaStore
import com.yunian.ai.domain.plugin.PluginEnablementStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 插件启停存储的日志出口（与 `core:agent` 的 `CapabilityGrantStoreLog` 同一手法）。
 *
 * 存在理由：`SecureLog` 底层是 `android.util.Log`，在纯 JVM 单测里是抛
 * `RuntimeException("Stub!")` 的空壳（本仓库无 Robolectric）。把日志收敛到可替换接口后，
 * 单测可以注入替身、纯 JVM 驱动真实的 [PluginEnablementStoreImpl]（含读失败 / 坏数据分支）。
 *
 * 可见性说明：本接口是 `:app` 的实现细节，但**必须 public**——[PluginEnablementStoreImpl]
 * 的构造函数带本类型的默认参数，Kotlin 禁止 public 构造函数暴露 internal 参数类型。
 * 它不是对外契约的一部分，调用方不需要、也不应该实现它。
 *
 * 行为约定：实现**不得**抛出异常（日志失败不得影响启停语义）。
 */
interface PluginEnablementStoreLog {
    /** 告警级日志（对齐 `SecureLog.w`）。 */
    fun w(tag: String, message: String)

    /** 错误级日志（对齐 `SecureLog.e`，可带 throwable）。 */
    fun e(tag: String, message: String, throwable: Throwable?)
}

/** 生产实现：转发到 [SecureLog]。 */
internal object SecureLogPluginEnablementStoreLog : PluginEnablementStoreLog {
    override fun w(tag: String, message: String) = SecureLog.w(tag, message)

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable == null) SecureLog.e(tag, message) else SecureLog.e(tag, message, throwable)
    }
}

/**
 * 停用集合的**纯逻辑**编解码：一段多行文本 ↔ 插件 id 集合。
 *
 * 抽成独立对象（而不是塞进 [PluginEnablementStoreImpl] 的私有方法）的理由与
 * `CapabilityGrantCodec` 相同：编解码不碰 `android.util.Log`、不碰 Room，
 * 因此可以被纯 JVM 单测直接驱动（`:app` 的测试源集没有 Robolectric）。
 *
 * ## 格式
 * 每行一个插件 id，`\n` 分隔：
 * ```
 * wechat.channel
 * qqbot.channel
 * ```
 *
 * 空行忽略；首尾空白裁剪；**行内含空白字符的行按坏行丢弃**（插件 id 是点分标识符，
 * 不可能含空白）。丢弃坏行而不是「一行脏数据判废整段」，是为了让一次局部损坏不至于
 * 把用户其余所有停用决定一起吃掉——方向上只会更偏向「启用」，与契约的 fail-safe 一致。
 */
object PluginEnablementCodec {

    /** 解码结果：[ids] 为有效 id 集合，[droppedLineCount] 为被丢弃的坏行数（供调用方记日志）。 */
    data class Decoded(val ids: Set<String>, val droppedLineCount: Int)

    /** 解码。**任何输入都不抛异常**：null / 空白 / 全坏行都只会得到空集。 */
    fun decode(raw: String?): Decoded {
        if (raw.isNullOrBlank()) return Decoded(emptySet(), 0)
        val ids = LinkedHashSet<String>()
        var dropped = 0
        for (line in raw.split('\n')) {
            val id = line.trim()
            if (id.isEmpty()) continue // 空行是排版噪声，不算坏行
            if (!isValidId(id)) {
                dropped++
                continue
            }
            ids += id
        }
        return Decoded(ids, dropped)
    }

    /** 编码：坏 id 直接跳过（不写脏数据），排序后每行一个。 */
    fun encode(disabled: Set<String>): String =
        disabled.filter { isValidId(it) }.sorted().joinToString("\n")

    /**
     * 插件 id 的取值域：非空、无内部空白、无控制字符。
     *
     * 与 `PluginManifest.id` / `LianYuPlugin.id` 同域（生产取值形如 `channel.wechat`、
     * `skill.builtin_chat_protocol`）。**刻意宽松**：只排除无法在多行文本里安全往返的字符，
     * 不假设「id 必须是点分小写」——过严的校验会让一个合法 id 的启停被静默忽略，
     * 那才是真正的 bug。控制字符（如 NUL）不是任何合法 id 的一部分，排除它能让
     * 「整段内容损坏」的 KV 收敛回空集而不是解出一堆垃圾 id。
     */
    fun isValidId(id: String): Boolean =
        id.isNotEmpty() && id.none { it.isWhitespace() || it.isISOControl() }
}

/**
 * [PluginEnablementStore] 的持久化实现：**一段多行文本存在 KV 单键里**。
 *
 * ## 存储位
 * 走 `AppMetaStore`（`core:database` 的 `app_meta` KV 表，`getString` / `putString`），
 * 键 = [KEY]（`plugin.enablement`，沿用仓库既有的 `<模块>.<用途>` 约定）：
 * - 不新增 Room 表 / 字段 / 索引（复用已存在的 `app_meta` 表，符合 `AppDatabase` 的
 *   「schema 冻结基线：新增功能走 AppMetaStore(KV)」红线）；
 * - 不新增任何模块依赖（`:app` 已直接依赖 `core:database`）。
 *
 * ## fail-safe：读不到 = 全部启用（硬要求）
 * [disabledIds] 的失败路径**全部收敛到空集**，与引入本契约之前的现状逐字一致：
 * - KV **读取失败**（DB 异常等）⇒ 记错误日志后返回空集；
 * - 从没写过 / 被清空 ⇒ 空集；
 * - 内容损坏 ⇒ [PluginEnablementCodec.decode] 只丢弃坏行，不抛异常，最坏也是空集；
 * - 任何情况下都**不抛异常**、**不返回「全停用」**——设置区本身可能来自插件，
 *   全停用会让用户无法在设置页里自救。
 *
 * 唯一照常向上传播的是 [CancellationException]（协程取消不是「读失败」，不该被吞）。
 *
 * ## 写语义
 * [setEnabled] 先在 [writeLock] 串行化，再做「读 → 改 → 整体覆盖写」；
 * 幂等（状态未变不落盘）。写失败**记日志后吞掉**，不抛给设置页调用方——
 * 这与 `CapabilityGrantStoreImpl` 的「写失败原样抛出」**刻意不同**，
 * 因为本契约的 KDoc 明文要求实现方自行吞掉。
 */
class PluginEnablementStoreImpl(
    private val metaStore: AppMetaStore,
    private val log: PluginEnablementStoreLog = SecureLogPluginEnablementStoreLog,
) : PluginEnablementStore {

    /** 串行化「读-改-写」，避免并发 setEnabled 互相覆盖。 */
    private val writeLock = Mutex()

    override suspend fun disabledIds(): Set<String> {
        val raw = readRaw() ?: return emptySet()
        val decoded = PluginEnablementCodec.decode(raw)
        if (decoded.droppedLineCount > 0) {
            log.w(TAG, "dropped ${decoded.droppedLineCount} malformed line(s); key=$KEY")
        }
        return decoded.ids
    }

    override suspend fun setEnabled(pluginId: String, enabled: Boolean) {
        if (!PluginEnablementCodec.isValidId(pluginId)) {
            // 坏 id 不落盘：写进去也永远不会被命中，只会污染 KV。
            log.w(TAG, "ignored setEnabled for malformed plugin id; key=$KEY")
            return
        }
        writeLock.withLock {
            val current = disabledIds()
            val next = if (enabled) current - pluginId else current + pluginId
            if (next == current) return@withLock // 幂等：状态未变不写
            write(next)
        }
    }

    /** 读原始文本；任何失败按「没有任何插件被停用」处理（fail-safe），取消照常向上传播。 */
    private suspend fun readRaw(): String? = try {
        metaStore.getString(KEY)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        log.e(TAG, "read failed, treating as nothing disabled (all plugins enabled). key=$KEY", failure)
        null
    }

    /** 整体覆盖写；失败记日志后**吞掉**（本次设置不跨重启保留，但不抛给调用方）。 */
    private suspend fun write(disabled: Set<String>) {
        try {
            metaStore.putString(KEY, PluginEnablementCodec.encode(disabled))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            log.e(TAG, "write failed, this toggle will not survive restart. key=$KEY", failure)
        }
    }

    companion object {
        /** KV 键（`<模块>.<用途>` 约定）。 */
        const val KEY: String = "plugin.enablement"

        private const val TAG = "PluginEnablementStore"
    }
}
