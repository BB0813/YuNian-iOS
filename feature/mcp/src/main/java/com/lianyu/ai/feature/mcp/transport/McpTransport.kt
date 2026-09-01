package com.lianyu.ai.feature.mcp.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import okhttp3.Response

/**
 * MCP 传输层接口。
 *
 * 定义与 MCP 服务器通信的底层协议，支持 SSE 和 Streamable HTTP。
 */
interface McpTransport {
    /** 连接到服务器 */
    suspend fun connect(): Boolean

    /** 断开连接 */
    suspend fun disconnect()

    /** 发送 JSON-RPC 请求 */
    suspend fun sendRequest(request: JsonRpcRequest): JsonRpcResponse?

    /** 发送通知（无响应） */
    suspend fun sendNotification(notification: JsonRpcNotification)

    /** 服务器发起的消息流（工具列表变更、进度等） */
    val serverMessages: ReceiveChannel<JsonRpcMessage>

    /** 当前连接状态 */
    val isConnected: Boolean
}

/** JSON-RPC 2.0 请求 */
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Any,  // string | number | null
    val method: String,
    val params: Any? = null
)

/** JSON-RPC 2.0 响应 */
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Any,
    val result: Any? = null,
    val error: JsonRpcError? = null
)

/** JSON-RPC 2.0 错误 */
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null
)

/** JSON-RPC 2.0 通知（无 id） */
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: Any? = null
)

/** JSON-RPC 消息（请求/响应/通知的统一类型） */
sealed interface JsonRpcMessage {
    data class Request(val request: JsonRpcRequest) : JsonRpcMessage
    data class Response(val response: JsonRpcResponse) : JsonRpcMessage
    data class Notification(val notification: JsonRpcNotification) : JsonRpcMessage
    
    // Dummy for sealed interface completeness
    data class Unknown(val data: String) : JsonRpcMessage
}

/** MCP 初始化参数 */
data class McpInitializeParams(
    val protocolVersion: String = "2025-06-18",
    val capabilities: McpClientCapabilities = McpClientCapabilities(),
    val clientInfo: McpClientInfo = McpClientInfo()
)

data class McpClientCapabilities(
    val roots: McpRootsCapability? = null,
    val sampling: McpSamplingCapability? = null
)

data class McpRootsCapability(
    val listChanged: Boolean = true
)

data class McpSamplingCapability(
    val dummy: String = ""
)

data class McpClientInfo(
    val name: String = "LianYu",
    val version: String = "1.0.0"
)

/** MCP 初始化响应 */
data class McpInitializeResult(
    val protocolVersion: String,
    val capabilities: McpServerCapabilities,
    val serverInfo: McpServerInfo
)

data class McpServerCapabilities(
    val tools: McpToolsCapability? = null,
    val resources: McpResourcesCapability? = null,
    val prompts: McpPromptsCapability? = null,
    val logging: McpLoggingCapability? = null
)

data class McpToolsCapability(
    val listChanged: Boolean = true
)

data class McpResourcesCapability(
    val subscribe: Boolean = false,
    val listChanged: Boolean = true
)

data class McpPromptsCapability(
    val listChanged: Boolean = true
)

data class McpLoggingCapability(
    val dummy: String = ""
)

data class McpServerInfo(
    val name: String,
    val version: String
)

/** MCP 工具列表请求/响应 */
data class McpListToolsParams(
    val cursor: String? = null
)

data class McpListToolsResult(
    val tools: List<McpToolDefinition>,
    val nextCursor: String? = null
)

data class McpToolDefinition(
    val name: String,
    val description: String? = null,
    val inputSchema: kotlinx.serialization.json.JsonObject
)

/** MCP 工具调用请求/响应 */
data class McpCallToolParams(
    val name: String,
    val arguments: kotlinx.serialization.json.JsonObject? = null
)

data class McpCallToolResult(
    val content: List<McpContent>,
    val isError: Boolean = false
)

sealed interface McpContent {
    data class Text(val type: String = "text", val text: String) : McpContent
    data class Image(val type: String = "image", val data: String, val mimeType: String) : McpContent
    data class Resource(val type: String = "resource", val resource: McpResource) : McpContent
}

data class McpResource(
    val uri: String,
    val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null
)