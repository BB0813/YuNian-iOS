package com.lianyu.ai.domain

import kotlinx.serialization.json.JsonObject

/**
 * 通用 MCP (Model Context Protocol) 管理器接口。
 *
 * 支持多个 MCP 服务器连接，动态发现工具，并将工具注册为 AiTool 供 AI 调用。
 * 属于 core:domain 零依赖模块，不引入任何传输层实现。
 */

/** MCP 服务器配置 */
data class McpServerConfig(
    val id: String,                    // 唯一标识
    val name: String,                  // 显示名称
    val url: String,                   // 服务器 URL (SSE endpoint 或 Streamable HTTP endpoint)
    val transportType: TransportType,  // 传输类型
    val headers: Map<String, String> = emptyMap(),  // 自定义头部
    val enabledTools: List<String> = emptyList(),   // 启用的工具名列表（空=全部启用）
    val disabledTools: List<String> = emptyList(),  // 禁用的工具名列表
    val enabled: Boolean = true,       // 是否启用
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** MCP 传输类型 */
enum class TransportType {
    SSE,                    // Server-Sent Events
    STREAMABLE_HTTP        // Streamable HTTP (MCP 2025-06-18 规范)
}

/** MCP 工具定义 */
data class McpTool(
    val serverId: String,              // 来源服务器 ID
    val name: String,                  // 工具名（全局唯一，建议前缀：mcp_<serverId>_<toolName>）
    val description: String,           // 工具描述
    val inputSchema: JsonObject,       // OpenAI function parameters JSON Schema
    val needsApproval: Boolean = false // 是否需用户确认
)

/** MCP 服务器状态 */
data class McpServerStatus(
    val config: McpServerConfig,
    val connected: Boolean,
    val tools: List<McpTool>,
    val lastError: String? = null,
    val lastConnectedAt: Long? = null
)

/**
 * MCP 管理器接口。
 *
 * Feature 模块实现此接口，core:network 通过 ServiceRegistry 获取并调用。
 * 负责管理多个 MCP 服务器连接、工具发现、工具调用代理。
 */
interface McpManager {
    /** 获取所有服务器状态 */
    suspend fun getServerStatuses(): List<McpServerStatus>

    /** 获取所有可用工具（跨所有启用服务器） */
    suspend fun getAvailableTools(): List<McpTool>

    /** 添加/更新服务器配置 */
    suspend fun upsertServer(config: McpServerConfig): Boolean

    /** 删除服务器配置 */
    suspend fun removeServer(serverId: String): Boolean

    /** 启用/禁用服务器 */
    suspend fun setServerEnabled(serverId: String, enabled: Boolean): Boolean

    /** 调用工具 */
    suspend fun callTool(serverId: String, toolName: String, argumentsJson: String): String

    /** 连接所有启用的服务器 */
    suspend fun connectAll(): List<McpServerStatus>

    /** 断开所有连接 */
    suspend fun disconnectAll()

    /** 刷新单个服务器的工具列表 */
    suspend fun refreshServerTools(serverId: String): List<McpTool>
}

/** MCP 管理器状态监听器 */
interface McpManagerListener {
    fun onServerStatusChanged(status: McpServerStatus)
    fun onToolListChanged(serverId: String, tools: List<McpTool>)
    fun onError(serverId: String, error: String)
}