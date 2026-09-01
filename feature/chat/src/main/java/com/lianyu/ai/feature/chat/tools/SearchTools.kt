package com.lianyu.ai.feature.chat.tools

import com.lianyu.ai.common.AppSettingsStore
import com.lianyu.ai.domain.AiTool
import com.lianyu.ai.domain.ToolRegistry
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 搜索工具：为 AI 提供实时网络搜索能力。
 * 基于 Brave Search API（可配置）。
 */
object SearchTools {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun registerAll(appSettings: AppSettingsStore) {
        ToolRegistry.register(WebSearchTool(appSettings))
    }

    /**
     * 网络搜索工具：获取实时信息、最新事实、验证内容。
     */
    private class WebSearchTool(private val appSettings: AppSettingsStore) : AiTool {
        override val name = "search_web"
        override val description = """
            搜索网络获取实时信息或验证事实。
            当用户询问最新消息、当前事实、或需要验证时使用。
            生成聚焦的关键词，必要时进行多次搜索。
            结果包含标题、链接和摘要。
        """.trimIndent()
        override val parametersJsonSchema = """
            {"type":"object","properties":{"query":{"type":"string","description":"搜索关键词"},"max_results":{"type":"integer","description":"最多返回结果数（默认 5，最大 10）"}},"required":["query"]}
        """.trimIndent()

        override fun systemPrompt() =
            "search_web 结果以 JSON 数组返回，每项含 title/url/snippet。引用时请注明来源链接。"

        override suspend fun execute(argumentsJson: String): String {
            val obj = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
            val query = obj?.get("query")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val maxResults = obj?.get("max_results")?.jsonPrimitive?.intOrNull?.coerceIn(1, 10) ?: 5

            if (query.isBlank()) {
                return buildJsonObject { put("ok", false); put("error", "query 不能为空") }.toString()
            }

            val apiKey = appSettings.getSearchApiKey()
            if (apiKey.isBlank()) {
                return buildJsonObject {
                    put("ok", false)
                    put("error", "搜索 API 未配置。请在设置中添加搜索 API 密钥。")
                }.toString()
            }

            val results = withTimeoutOrNull(15_000L) {
                try {
                    braveSearch(apiKey, query, maxResults)
                } catch (e: Exception) {
                    emptyList()
                }
            } ?: emptyList()

            if (results.isEmpty()) {
                return buildJsonObject {
                    put("ok", true)
                    put("query", query)
                    put("empty", true)
                    put("note", "未找到相关结果，请尝试更换关键词")
                }.toString()
            }

            return buildJsonObject {
                put("ok", true)
                put("query", query)
                put("results", buildJsonArray {
                    results.forEach { r ->
                        add(buildJsonObject {
                            put("title", r.title)
                            put("url", r.url)
                            put("snippet", r.snippet)
                        })
                    }
                })
            }.toString()
        }

        private data class SearchResult(val title: String, val url: String, val snippet: String)

        private suspend fun braveSearch(apiKey: String, query: String, maxResults: Int): List<SearchResult> {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://api.search.brave.com/res/v1/web/search?q=$encodedQuery&count=$maxResults")
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .header("X-Subscription-Token", apiKey)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            val responseObj = json.parseToJsonElement(body).jsonObject
            val webResults = responseObj["web"]?.jsonObject?.get("results")?.jsonArray ?: return emptyList()

            return webResults
                .take(maxResults)
                .mapNotNull { element ->
                    val item = element.jsonObject
                    SearchResult(
                        title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        url = item["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        snippet = item["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
                .filter { it.title.isNotBlank() || it.snippet.isNotBlank() }
        }
    }
}