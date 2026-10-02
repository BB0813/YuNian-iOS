package com.yunian.ai.feature.settings.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.domain.CapabilityGrant
import com.yunian.ai.domain.CapabilityGrantStore
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.feature.settings.capability.CapabilityGrantBoard
import com.yunian.ai.feature.settings.capability.CompanionOption
import com.yunian.ai.feature.settings.capability.GrantScope
import com.yunian.ai.feature.settings.capability.GrantToolItem
import com.yunian.ai.feature.settings.capability.GrantWrite
import com.yunian.ai.feature.settings.capability.GrantableTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「工具授权」设置页的 ViewModel：把**纯逻辑**（[CapabilityGrantBoard]）接到三个既有能力上。
 *
 * - 伴侣列表：core:database 的 [CompanionRepository]（Room 既有查询，不新增 SQL / Entity）；
 * - 工具清单：core:domain 的 [ToolRegistry]（**全部**工具，`includeAppLocal = true`，不再按通道过滤）；
 * - 授权读写：core:domain 的 [CapabilityGrantStore]（经 [ServiceRegistry] 取 core:agent 的实现）。
 *
 * 页面逻辑本身没有放在这里：清单构造 / 合并展示 / 开关映射全在 [CapabilityGrantBoard]，由 JVM 单测覆盖。
 *
 * 生效时机：折叠点 `AgentFacade.toolDefinitionsFor` 在**每回合装配期**读存储，
 * 所以这里写完即生效，**不需要重启 App**（也不会去通知任何已有会话，避免时序副作用）。
 */
class CapabilityGrantViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * 授权存储（App 启动时由 YuNianApplication 注册）。
     *
     * `runCatching`：`ServiceRegistry.get` 在工厂返回 null 时会抛异常（未注册 / 尚未绑定），
     * 设置页不该因为这个崩溃——取不到就退化为只读展示 + 明确提示。
     */
    private val store: CapabilityGrantStore? =
        runCatching { ServiceRegistry.get(CapabilityGrantStore::class.java) }.getOrNull()

    private val companionRepository =
        CompanionRepository(AppDatabase.getDatabase(application).companionDao())

    /** 伴侣下拉数据（含「全部伴侣」这一项由 UI 自行前置）。 */
    val companions: StateFlow<List<CompanionOption>> = companionRepository.getAllCompanions()
        .map { entities -> entities.map { CompanionOption(it.id, it.name) } }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val scope = MutableStateFlow<GrantScope>(GrantScope.AllCompanions)

    /** 当前授权作用域（默认「全部伴侣」）。 */
    val selectedScope: StateFlow<GrantScope> = scope.asStateFlow()

    private val tools = MutableStateFlow<List<GrantableTool>>(emptyList())

    /** 全部显式决定（原始表，与存储同构；「全部伴侣」视角直接读它）。 */
    private val decisions = MutableStateFlow<List<CapabilityGrant>>(emptyList())

    private val _isLoading = MutableStateFlow(true)

    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * 页面主体：**一张清单**（全部 Agent tools，每个工具一行）。
     *
     * `SharingStarted.Eagerly`：开关回调要读当前项做写入映射，惰性订阅会让状态在
     * 没有收集者时停在初始值，从而误判开关状态。
     */
    val boardItems: StateFlow<List<GrantToolItem>> =
        combine(tools, decisions, scope) { currentTools, currentDecisions, currentScope ->
            CapabilityGrantBoard.items(currentTools, currentDecisions, currentScope)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 一次性提示（读取/保存失败等）。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 存储是否可用：false 时页面明确提示「授权不可用」，而不是让开关静默失效。 */
    val storageAvailable: Boolean get() = store != null

    init {
        viewModelScope.launch {
            // availableTools() 会对每个工具求值 isAvailable()（可能读系统状态），放到后台线程。
            // 兜一层异常：某个工具的实现若在 isAvailable() 里抛错，设置页不该因此整页不可用。
            tools.value = try {
                withContext(Dispatchers.Default) {
                    CapabilityGrantBoard.grantableTools(
                        // 只读**一份**全量注册池（includeAppLocal = true）：本页是工具授权，
                        // 本机敏感工具同样要能授权（App 内会话的执行宿主允许它们）。
                        // P2-2d 之后**不再**按通道过滤：清单 = 全部 Agent tools。
                        ToolRegistry.availableTools(includeAppLocal = true)
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _messages.tryEmit("读取工具列表失败：${failure.message ?: failure.javaClass.simpleName}")
                emptyList()
            }
            _isLoading.value = false
        }
        viewModelScope.launch { reloadDecisions(reportFailure = true) }
    }

    /** 切换作用域（「全部伴侣（通配）」/ 某个具体伴侣）。 */
    fun selectScope(newScope: GrantScope) {
        scope.value = newScope
    }

    /**
     * 用户拨动某一行的开关。
     *
     * 映射（含「什么都不做」的分支）全部由 [CapabilityGrantBoard.write] 决定，这里只负责执行：
     * 先乐观更新界面，再以存储回读为准（写失败时回读会把界面拨回真实状态）。
     */
    fun setAllowed(item: GrantToolItem, allowed: Boolean) {
        val write = CapabilityGrantBoard.write(
            scope = scope.value,
            tool = item.tool,
            allowed = allowed,
            // 通配给出的值：用于判断「清掉本伴侣的决定后会不会被通配重新放行」。
            inheritedAllowed = item.inheritedAllowed,
        )
        val target = store
        if (target == null) {
            _messages.tryEmit("授权存储不可用，无法保存")
            return
        }

        when (write) {
            GrantWrite.None -> return
            is GrantWrite.Decide -> {
                val grant = write.grant
                decisions.value = decisions.value
                    .filterNot { it.companionId == grant.companionId && it.toolName == grant.toolName } + grant
            }

            is GrantWrite.Clear -> decisions.value = decisions.value.filterNot {
                it.companionId == write.companionId && it.toolName == write.toolName
            }
        }

        viewModelScope.launch {
            try {
                when (write) {
                    GrantWrite.None -> Unit
                    is GrantWrite.Decide -> target.decide(write.grant)
                    is GrantWrite.Clear -> target.clear(write.companionId, write.toolName)
                }
                // 存储是「读-改-写」整体覆盖，回读一次可消除与其它写入方的漂移。
                reloadDecisions(reportFailure = false)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _messages.tryEmit("保存失败：${failure.message ?: failure.javaClass.simpleName}")
                reloadDecisions(reportFailure = false)
            }
        }
    }

    private suspend fun reloadDecisions(reportFailure: Boolean) {
        val snapshot = readDecisionsOrNull()
        if (snapshot != null) {
            decisions.value = snapshot
            return
        }
        if (reportFailure && store != null) {
            _messages.tryEmit("读取已有授权失败，页面按「工具默认」显示")
        }
    }

    /** 读取全部显式决定；失败返回 null（调用方决定提示与否），取消照常向上传播。 */
    private suspend fun readDecisionsOrNull(): List<CapabilityGrant>? {
        val target = store ?: return null
        return try {
            target.decisions()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
    }
}
