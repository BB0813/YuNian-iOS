package com.lianyu.ai.common

/**
 * SuFlow API 常量 — Clove API 对接
 * 统一管理 URL、端点路径、认证方式
 */
object SuFlowApi {
    /** SuFlow API 基础地址 */
    const val BASE_URL = "http://localhost:9876/v1"
    
    /** Chat Completions 端点 */
    const val CHAT_PATH = "/chat/completions"
    
    /** 模型列表端点 */
    const val MODELS_PATH = "/models"
    
    /** 用量查询端点 */
    const val USAGE_PATH = "/usage"
    
    /** Handshake 端点（客户端，非管理端） */
    const val HANDSHAKE_PATH = "/api/auth/handshake"
    
    /** Key 分发端点 */
    const val KEYS_FETCH_PATH = "/api/keys/fetch"
    
    /** 请求超时（秒） */
    const val TIMEOUT_SECONDS = 30L
    
    /** 连接测试模型 */
    const val TEST_MODEL = "gpt-4o-mini"
}
