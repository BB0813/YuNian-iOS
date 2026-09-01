package com.lianyu.ai.feature.mcp

import com.lianyu.ai.domain.AiTool
import com.lianyu.ai.domain.McpManager
import com.lianyu.ai.domain.McpTool
import com.lianyu.ai.domain.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * MCP 工具适配器：将 MCP 工具包装为 AiTool。
 *
 * 使 MCP 工具可以像本地工具一样被 AI 调用。
 */
class McpToolAdapter(
    private val mcpManager: McpManager,
    private val mcpTool: McpTool
) : AiTool {

    override val name = mcpTool.name
    override val description = mcpTool.description
    override val parametersJsonSchema = mcpTool.inputSchema.toString()

    override fun systemPrompt(): String {
        return "MCP Tool: $name - ${mcpTool.description}. Server: ${mcpTool.serverId}"
    }

    override val requiresConfirmation: Boolean = mcpTool.needsApproval

    override suspend fun execute(argumentsJson: String): String {
        return mcpManager.callTool(mcpTool.serverId, mcpTool.name.removePrefix("mcp_${mcpTool.serverId}_"), argumentsJson)
    }
}

/**
 * MCP 工具注册器：负责将 MCP 管理器发现的工具注册/注销到 ToolRegistry。
 */
class McpToolRegistrar(
    private val mcpManager: McpManager
) {
    private val registeredTools = mutableSetOf<String>()

    /** 同步注册所有可用 MCP 工具 */
    suspend fun syncTools() {
        val availableTools = mcpManager.getAvailableTools()
        val currentToolNames = availableTools.map { it.name }.toSet()

        // 注销已移除的工具
        for (toolName in registeredTools - currentToolNames) {
            ToolRegistry.unregister(toolName)
            registeredTools.remove(toolName)
        }

        // 注册新工具
        for (tool in availableTools) {
            if (tool.name !in registeredTools) {
                val adapter = McpToolAdapter(mcpManager, tool)
                ToolRegistry.register(adapter)
                registeredTools.add(tool.name)
            }
        }
    }

    /** 注销所有 MCP 工具 */
    fun unregisterAll() {
        for (toolName in registeredTools) {
            ToolRegistry.unregister(toolName)
        }
        registeredTools.clear()
    }

    /** 获取当前注册的工具名列表 */
    fun getRegisteredToolNames(): List<String> = registeredTools.toList()
}