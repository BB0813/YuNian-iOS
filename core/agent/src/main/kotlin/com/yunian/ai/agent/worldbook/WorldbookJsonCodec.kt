package com.yunian.ai.agent.worldbook

import com.yunian.ai.database.model.EntryRole
import com.yunian.ai.database.model.InjectionPosition
import com.yunian.ai.database.model.LorebookEntity
import com.yunian.ai.database.model.LorebookEntryEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地结构化世界书 ⟷ SillyTavern World Info JSON 双向编解码器。
 *
 * ## 为什么放在 `core:agent`
 * `:core:agent` 同时依赖 `:core:database`（拿到 `LorebookEntity` / `LorebookEntryEntity`），
 * 且被 `:feature:settings`（UI 编辑）与 `:feature:worldbook`（数据层）依赖，
 * 因而是唯一能同时服务「迁移 / 运行时合并 / UI 反向解码」三处的最小公共模块。
 *
 * ## ★ 为什么 `entries` 必须是 **对象(map)** 而不是数组
 * Rust `Lorebook::parse()`（`agent-native/src/lorebook.rs`）仅在 `entries` 为 JSON 数组时
 * 才走 `chara_card` 格式校验：
 * ```text
 * if let Some(entries) = value.get("entries") {
 *     if entries.is_array() { serde_json::from_str::<chara_card::raw::Lorebook>(json)? }
 * }
 * ```
 * 而 `chara_card 0.4.1` 的 `v2::LorebookEntry.position` 是
 * `enum EntryPosition { BeforeChar, AfterChar }`（`snake_case`）——**只认 2 个值**，
 * `top_of_chat` / `bottom_of_chat` / `at_depth` 会直接校验失败；同时它不认识
 * `depth` / `role` / `scan_depth`（Q2 新增字段），数组模式下会静默丢失。
 *
 * 因此迁移与运行时合成**一律输出 map 格式**：`entries` 为 `{ "<uid>": { ... } }`，
 * 绕过 `chara_card` 校验，直接落到 Rust 自研的 `LorebookEntry`（5 值 `position` 全支持）。
 * Rust 侧 `Entries::Map(HashMap<String, _>)` 顺序不保证，但 `scan()` 会
 * `sort_by_key(insertion_order)` 显式重排 —— 只要 `insertion_order` 唯一，顺序即确定。
 * （本文件仍按 `insertion_order` 升序写入，仅用于产出稳定可 diff 的 JSON。）
 *
 * ## 字段映射（plan doc §5.2）
 * | 本地 | ST JSON | 规则 |
 * |---|---|---|
 * | `keywordsJson` | `keys` | JSON 数组 |
 * | `content` | `content` | 直填 |
 * | `enabled` | `enabled` | `1→true`（显式写出，勿省） |
 * | `caseSensitive` | `case_sensitive` | `1→true` |
 * | `useRegex` | `use_regex` | `1→true` |
 * | `constantActive` | `constant` | `1→true`（constant 不被预算裁剪） |
 * | `priority` | `insertion_order` | ★ 取反：`2^32 - priority`（本地降序 = ST 升序） |
 * | `injectionPosition` | `position` | 5 值直译 |
 * | `injectDepth` | `depth` | 仅 `at_depth` 写出 |
 * | `role` | `role` | 小写直译 |
 * | `scanDepth` | `scan_depth` | 条目级，直填 |
 * | — | `secondary_keys` | 恒 `[]` |
 * | — | `extensions` | `{"_bookId":…,"_bookName":…}` 回写来源书本 |
 */
object WorldbookJsonCodec {

    /** `insertion_order` 取反基数（2^32）：`priority` 越大 → `insertion_order` 越小 → 越先注入。 */
    const val ORDER_BASE: Long = 4_294_967_296L

    /** 本地 `MAX_TOTAL_INJECTION_CHARS = 20000` 字符 ≈ 10000 token。 */
    const val DEFAULT_TOKEN_BUDGET: Long = 10_000L

    /** 本地 `LorebookEntryEntity.scanDepth` 默认值。 */
    const val DEFAULT_SCAN_DEPTH: Long = 10L

    /** 对齐 Rust `LorebookHit.depth = e.depth.unwrap_or(4)` 与本地 `?: 4`。 */
    const val DEFAULT_DEPTH: Long = 4L

    // ────────────────────────────── 枚举映射 ──────────────────────────────

    /** 本地 5 值 → ST `position`。 */
    fun positionToSt(position: InjectionPosition): String = when (position) {
        InjectionPosition.BEFORE_SYSTEM_PROMPT -> "before_char"
        InjectionPosition.AFTER_SYSTEM_PROMPT -> "after_char"
        InjectionPosition.TOP_OF_CHAT -> "top_of_chat"
        InjectionPosition.BOTTOM_OF_CHAT -> "bottom_of_chat"
        InjectionPosition.AT_DEPTH -> "at_depth"
    }

    /** ST `position` → 本地 5 值。未知/缺失回落 `AT_DEPTH`（对齐 Rust `parse(None)`）。 */
    fun stToPosition(raw: String?): InjectionPosition =
        when (raw?.trim()?.lowercase()) {
            "before_char" -> InjectionPosition.BEFORE_SYSTEM_PROMPT
            "after_char" -> InjectionPosition.AFTER_SYSTEM_PROMPT
            "top_of_chat" -> InjectionPosition.TOP_OF_CHAT
            "bottom_of_chat" -> InjectionPosition.BOTTOM_OF_CHAT
            else -> InjectionPosition.AT_DEPTH
        }

    /** 本地 `EntryRole` → ST `role`（小写）。 */
    fun roleToSt(role: EntryRole): String = when (role) {
        EntryRole.SYSTEM -> "system"
        EntryRole.USER -> "user"
        EntryRole.ASSISTANT -> "assistant"
    }

    /** ST `role` → 本地 `EntryRole`。未知/缺失回落 `SYSTEM`（本地 DB 默认值）。 */
    fun stToRole(raw: String?): EntryRole =
        when (raw?.trim()?.lowercase()) {
            "user" -> EntryRole.USER
            "assistant" -> EntryRole.ASSISTANT
            else -> EntryRole.SYSTEM
        }

    /** ★ 排序取反：本地 `priority`（降序）→ ST `insertion_order`（升序）。 */
    fun priorityToInsertionOrder(priority: Int): Long = ORDER_BASE - priority.toLong()

    /** ★ 排序取反的逆运算。超出 `Int` 表示范围时钳制。 */
    fun insertionOrderToPriority(insertionOrder: Long): Int =
        (ORDER_BASE - insertionOrder).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    // ────────────────────────────── 关键词 ──────────────────────────────

    /** 解析 `keywordsJson`；非法 JSON 视为空列表（与本地 `WorldbookRepository` 行为一致）。 */
    fun parseKeywords(keywordsJson: String): List<String> = runCatching {
        val arr = JSONArray(keywordsJson)
        buildList {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
    }.getOrElse { emptyList() }

    /** 序列化关键词为 JSON 数组字符串。 */
    fun encodeKeywords(keywords: List<String>): String = JSONArray(keywords).toString()

    // ────────────────────────────── 编码：本地 → ST JSON ──────────────────────────────

    /**
     * 单条目 → ST entry JSON。
     *
     * @param entry 本地条目
     * @param bookId 来源书本 id（写入 `extensions._bookId`；迁移/合并时用于回溯）
     * @param bookName 来源书本名（写入 `extensions._bookName`）
     * @param insertionOrder 覆盖用注入顺序；`null` 时由 `priority` 推导
     */
    fun entryToJson(
        entry: LorebookEntryEntity,
        bookId: Long? = null,
        bookName: String? = null,
        insertionOrder: Long? = null,
    ): JSONObject {
        val obj = JSONObject()
        obj.put("id", entry.id)
        obj.put("keys", JSONArray(parseKeywords(entry.keywordsJson)))
        obj.put("secondary_keys", JSONArray(emptyList<String>()))
        obj.put("content", entry.content)
        // ★ 必须显式写出：Rust 侧 enabled 默认 true，省略会让禁用条目意外生效
        obj.put("enabled", entry.isEnabled())
        obj.put(
            "insertion_order",
            insertionOrder ?: priorityToInsertionOrder(entry.priority),
        )
        obj.put("case_sensitive", entry.isCaseSensitive())
        obj.put("use_regex", entry.isUseRegex())
        obj.put("constant", entry.isConstantActive())
        obj.put("position", positionToSt(entry.injectionPosition))
        obj.put("role", roleToSt(entry.role))
        obj.put("scan_depth", entry.scanDepth.coerceAtLeast(1).toLong())
        // depth 仅在 at_depth 下有意义；其余位置写 0 之外的默认值反而会干扰 Rust 的 clamp
        if (entry.injectionPosition == InjectionPosition.AT_DEPTH) {
            obj.put("depth", (entry.injectDepth ?: DEFAULT_DEPTH.toInt()).coerceAtLeast(1))
        }
        val ext = JSONObject()
        if (bookId != null) ext.put("_bookId", bookId)
        if (bookName != null) ext.put("_bookName", bookName)
        if (ext.length() > 0) obj.put("extensions", ext)
        return obj
    }

    /**
     * 合成 ST World Info 顶层 JSON（map 格式 `entries`）。
     *
     * @param entryObjects 已由 [entryToJson] 产出（**其 `insertion_order` 应已全局唯一化**）
     */
    fun assemble(
        name: String,
        description: String?,
        scanDepth: Long,
        tokenBudget: Long,
        entryObjects: List<JSONObject>,
    ): String {
        val root = JSONObject()
        root.put("name", name)
        if (!description.isNullOrEmpty()) root.put("description", description)
        root.put("scan_depth", scanDepth.coerceAtLeast(1))
        root.put("token_budget", tokenBudget.coerceAtLeast(1))
        // recursive_scanning 省略 → Rust `Option<bool>` 视为 false，与本地行为一致
        val entries = JSONObject()
        // 按 insertion_order 升序写入，保证产物稳定可 diff（Rust 侧不依赖此顺序）
        entryObjects
            .sortedBy { it.optLong("insertion_order", 0L) }
            .forEach { obj ->
                val uid = obj.optLong("id", 0L).takeIf { it > 0L }?.toString()
                    ?: "e${obj.optLong("insertion_order", 0L)}"
                entries.put(uid, obj)
            }
        root.put("entries", entries)
        return root.toString()
    }

    /**
     * 单本本地世界书（含条目）→ ST JSON。
     *
     * 顶层 `scan_depth` 取条目 `scanDepth` 的**众数**（并列时取较大值），
     * 缺省回落 [DEFAULT_SCAN_DEPTH]。
     */
    fun bookToJson(book: LorebookEntity, entries: List<LorebookEntryEntity>): String = assemble(
        name = book.name,
        description = book.description,
        scanDepth = dominantScanDepth(entries),
        tokenBudget = DEFAULT_TOKEN_BUDGET,
        entryObjects = entries.map { entryToJson(it, bookId = book.id, bookName = book.name) },
    )

    /** 条目 `scanDepth` 众数（并列取较大值）；空列表 → [DEFAULT_SCAN_DEPTH]。 */
    fun dominantScanDepth(entries: List<LorebookEntryEntity>): Long {
        if (entries.isEmpty()) return DEFAULT_SCAN_DEPTH
        return entries
            .groupingBy { it.scanDepth.coerceAtLeast(1).toLong() }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenByDescending { it.key })
            .first()
            .key
    }

    // ────────────────────────────── 解码：ST JSON → 本地结构 ──────────────────────────────

    /** 解码后的 ST 条目（UI 编辑用；不含 `lorebookId`，由调用方补）。 */
    data class StEntry(
        val uid: String,
        val keywords: List<String>,
        val content: String,
        val enabled: Boolean,
        val insertionOrder: Long,
        val constant: Boolean,
        val caseSensitive: Boolean,
        val useRegex: Boolean,
        val position: InjectionPosition,
        val depth: Long,
        val role: EntryRole,
        val scanDepth: Long,
        val bookId: Long?,
        val bookName: String?,
    ) {
        fun toEntity(lorebookId: Long): LorebookEntryEntity = LorebookEntryEntity(
            id = uid.toLongOrNull() ?: 0L,
            lorebookId = lorebookId,
            keywordsJson = encodeKeywords(keywords),
            content = content,
            injectionPosition = position,
            priority = insertionOrderToPriority(insertionOrder),
            injectDepth = depth.toInt(),
            role = role,
            caseSensitive = if (caseSensitive) 1 else 0,
            useRegex = if (useRegex) 1 else 0,
            sortOrder = 0,
            scanDepth = scanDepth.toInt(),
            constantActive = if (constant) 1 else 0,
            enabled = if (enabled) 1 else 0,
        )
    }

    /** ST JSON 顶层元信息。 */
    data class StBookMeta(
        val name: String,
        val description: String,
        val scanDepth: Long,
        val tokenBudget: Long,
    )

    /** 解析顶层元信息；非法 JSON 回落安全默认值。 */
    fun bookMeta(raw: String): StBookMeta {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return defaultMeta()
        return StBookMeta(
            name = root.optString("name", ""),
            description = root.optString("description", ""),
            scanDepth = root.optLong("scan_depth", DEFAULT_SCAN_DEPTH).coerceAtLeast(1),
            tokenBudget = root.optLong("token_budget", DEFAULT_TOKEN_BUDGET).coerceAtLeast(1),
        )
    }

    private fun defaultMeta() = StBookMeta("", "", DEFAULT_SCAN_DEPTH, DEFAULT_TOKEN_BUDGET)

    /**
     * 解析 ST JSON 的 `entries`（**同时兼容 map 与数组两种容器**），按 `insertion_order` 升序返回。
     * 非法 JSON → 空列表。
     */
    fun entries(raw: String): List<StEntry> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val container = root.opt("entries") ?: return emptyList()
        val out = ArrayList<StEntry>()
        when (container) {
            is JSONObject -> {
                val names = container.keys()
                while (names.hasNext()) {
                    val uid = names.next()
                    val obj = container.optJSONObject(uid) ?: continue
                    out += decodeEntry(uid, obj)
                }
            }
            is JSONArray -> {
                for (i in 0 until container.length()) {
                    val obj = container.optJSONObject(i) ?: continue
                    out += decodeEntry(obj.optLong("id", 0L).toString(), obj)
                }
            }
            else -> return emptyList()
        }
        return out.sortedBy { it.insertionOrder }
    }

    /** 统计 ST JSON 中 `entries` 的条目数（迁移自检用）。 */
    fun entryCount(raw: String): Int {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return 0
        return when (val c = root.opt("entries")) {
            is JSONObject -> c.length()
            is JSONArray -> c.length()
            else -> 0
        }
    }

    private fun decodeEntry(uid: String, obj: JSONObject): StEntry {
        val keysArr = obj.optJSONArray("keys")
        val keywords = buildList {
            if (keysArr != null) for (i in 0 until keysArr.length()) keysArr.optString(i).takeIf { it.isNotEmpty() }?.let { add(it) }
        }
        val ext = obj.optJSONObject("extensions")
        val position = stToPosition(obj.opt("position") as? String)
        return StEntry(
            uid = uid,
            keywords = keywords,
            content = obj.optString("content", ""),
            enabled = obj.optBoolean("enabled", true),
            insertionOrder = obj.optLong("insertion_order", 0L),
            constant = obj.optBoolean("constant", false),
            caseSensitive = obj.optBoolean("case_sensitive", false),
            useRegex = obj.optBoolean("use_regex", false),
            position = position,
            depth = obj.optLong("depth", DEFAULT_DEPTH).coerceAtLeast(1),
            role = stToRole(obj.opt("role") as? String),
            scanDepth = obj.optLong("scan_depth", DEFAULT_SCAN_DEPTH).coerceAtLeast(1),
            bookId = ext?.optLong("_bookId", 0L)?.takeIf { it > 0L },
            bookName = ext?.optString("_bookName", "")?.takeIf { it.isNotEmpty() },
        )
    }
}
