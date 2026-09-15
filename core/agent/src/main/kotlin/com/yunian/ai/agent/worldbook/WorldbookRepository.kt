package com.yunian.ai.agent.worldbook

import android.content.Context
import android.util.Log
import com.yunian.ai.agent.AgentFacade
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.dao.WorldbookDao
import com.yunian.ai.database.model.WorldbookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 世界书仓库（③：持久化 + 激活管理 + 注入 AgentRuntime）。
 *
 * 世界书为社区 SillyTavern World Info 格式 JSON；激活者经 AgentFacade.setWorldbook
 * 注入 Rust 引擎（回合组装自动扫描注入，keys/正则/constant/scan_depth/预算语义）。
 */
class WorldbookRepository(private val context: Context) {

    private val dao: WorldbookDao =
        AppDatabase.getDatabase(context.applicationContext).worldbookDao()

    suspend fun list(): List<WorldbookEntity> = withContext(Dispatchers.IO) { dao.all() }
    suspend fun active(): WorldbookEntity? = withContext(Dispatchers.IO) { dao.active() }

    /** 伴侣级激活世界书（无则回退全局；伴侣 id <= 0 时仅全局）。 */
    suspend fun activeFor(companionId: Long): WorldbookEntity? =
        withContext(Dispatchers.IO) {
            if (companionId > 0L) dao.activeForCompanion(companionId) ?: dao.active() else dao.active()
        }

    /** 保存（新增或更新）；[companionId] 非空 = 伴侣级。成功后同步注入。 */
    suspend fun upsert(name: String, json: String, enabled: Boolean = false, companionId: Long? = null): Long =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val id = dao.upsert(
                WorldbookEntity(name = name.takeIf { it.isNotBlank() } ?: "未命名世界书", json = json, enabled = enabled, companionId = companionId, updatedAt = now),
            )
            if (enabled) {
                dao.clearEnabled()
                dao.upsert(WorldbookEntity(id = id, name = name, json = json, enabled = true, companionId = companionId, updatedAt = now))
            }
            syncActiveToRuntime(companionId ?: 0L)
            id
        }

    /** 启用/停用；启用时先清除其他启用项（同作用域单激活：伴侣级只清伴侣，全局清全局）。 */
    suspend fun setEnabled(id: Long, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val book = dao.byId(id) ?: return@withContext false
        if (enabled) {
            dao.clearEnabled()
            dao.upsert(book.copy(enabled = true, updatedAt = System.currentTimeMillis()))
        } else {
            dao.upsert(book.copy(enabled = false, updatedAt = System.currentTimeMillis()))
        }
        syncActiveToRuntime(book.companionId ?: 0L)
        true
    }

    suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) {
        val book = dao.byId(id)
        dao.delete(id)
        if (book?.enabled == true) syncActiveToRuntime()
        true
    }

    /** 把激活世界书注入 AgentRuntime（启动/变更后调用；companionId>0 优先伴侣级）。 */
    suspend fun syncActiveToRuntime(companionId: Long = 0L) {
        val active = if (companionId > 0L) dao.activeForCompanion(companionId) ?: dao.active() else dao.active()
        runCatching {
            AgentFacade.setWorldbook(context, active?.json ?: "")
        }.onFailure { Log.w(TAG, "sync worldbook failed", it) }
    }

    private companion object {
        private const val TAG = "WorldbookRepository"
    }
}