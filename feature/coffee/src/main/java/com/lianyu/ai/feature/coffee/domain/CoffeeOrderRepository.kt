package com.lianyu.ai.feature.coffee.domain

import com.lianyu.ai.feature.coffee.data.LuckinMcpClient
import com.lianyu.ai.feature.coffee.data.LuckinTokenStore
import com.lianyu.ai.feature.coffee.data.McpException
import com.lianyu.ai.feature.coffee.data.model.CancelResult
import com.lianyu.ai.feature.coffee.data.model.CreateOrderArgs
import com.lianyu.ai.feature.coffee.data.model.OrderCreated
import com.lianyu.ai.feature.coffee.data.model.OrderDetail
import com.lianyu.ai.feature.coffee.data.model.OrderIdArgs
import com.lianyu.ai.feature.coffee.data.model.OrderPreview
import com.lianyu.ai.feature.coffee.data.model.PreviewOrderArgs
import com.lianyu.ai.feature.coffee.data.model.ProductDetailResult
import com.lianyu.ai.feature.coffee.data.model.ProductInfo
import com.lianyu.ai.feature.coffee.data.model.ProductSearchResult
import com.lianyu.ai.feature.coffee.data.model.QueryShopArgs
import com.lianyu.ai.feature.coffee.data.model.SearchProductArgs
import com.lianyu.ai.feature.coffee.data.model.ShopInfo
import com.lianyu.ai.feature.coffee.data.model.ShopListResult
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 瑞幸咖啡订单仓库。
 *
 * 封装 8 个 MCP 工具调用，对上层提供领域语义 API。
 * 严格遵循 SKILL.md 下单流程约束：
 *   确认门店 → 确认商品 → previewOrder → createOrder（不可跳步）
 *
 * @throws McpException 当 Token 无效或 MCP 调用失败时抛出
 */
class CoffeeOrderRepository(
    private val client: LuckinMcpClient,
    private val tokenStore: LuckinTokenStore
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 获取已存储的 Token，未配置时抛出 IllegalStateException */
    private suspend fun requireToken(): String {
        val token = tokenStore.token.first()
        if (token.isBlank()) {
            throw IllegalStateException("请先配置瑞幸 MCP Token")
        }
        return token
    }

    // ════════════════════════════════════════════════════════════════
    // 工具 1: queryShopList — 查询门店
    // ════════════════════════════════════════════════════════════════

    suspend fun queryShopList(
        longitude: Double,
        latitude: Double,
        deptName: String? = null
    ): List<ShopInfo> {
        val token = requireToken()
        val args = buildJsonObject {
            put("longitude", longitude)
            put("latitude", latitude)
            if (!deptName.isNullOrBlank()) {
                put("deptName", deptName)
            }
        }
        val text = client.callTool(token, "queryShopList", args)
        return parseShopList(text)
    }

    private fun parseShopList(text: String): List<ShopInfo> {
        // 瑞幸返回格式可能是 {list: [...]} 或直接 [...]
        return try {
            val element = json.parseToJsonElement(text)
            when (element) {
                is JsonObject -> {
                    val listField = element["list"] as? JsonArray
                        ?: element["data"] as? JsonArray
                        ?: element["shops"] as? JsonArray
                    listField?.map { json.decodeFromString(ShopInfo.serializer(), it.toString()) }
                        ?: emptyList()
                }
                is JsonArray -> element.map { json.decodeFromString(ShopInfo.serializer(), it.toString()) }
                else -> emptyList()
            }
        } catch (e: Exception) {
            // 尝试直接解析为 ShopListResult
            try {
                json.decodeFromString(ShopListResult.serializer(), text).list
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 工具 2: searchProductForMcp — 搜索商品
    // ════════════════════════════════════════════════════════════════

    suspend fun searchProduct(deptId: Long, query: String): List<ProductInfo> {
        val token = requireToken()
        val args = buildJsonObject {
            put("deptId", deptId)
            put("query", query)
        }
        val text = client.callTool(token, "searchProductForMcp", args)
        return parseProductSearch(text)
    }

    private fun parseProductSearch(text: String): List<ProductInfo> {
        return try {
            val element = json.parseToJsonElement(text)
            when (element) {
                is JsonObject -> {
                    val listField = element["list"] as? JsonArray
                        ?: element["data"] as? JsonArray
                        ?: element["products"] as? JsonArray
                    listField?.map { json.decodeFromString(ProductInfo.serializer(), it.toString()) }
                        ?: emptyList()
                }
                is JsonArray -> element.map { json.decodeFromString(ProductInfo.serializer(), it.toString()) }
                else -> emptyList()
            }
        } catch (e: Exception) {
            try {
                json.decodeFromString(ProductSearchResult.serializer(), text).list
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 工具 3: queryProductDetailInfo — 商品详情（属性定制）
    // ════════════════════════════════════════════════════════════════

    suspend fun queryProductDetail(deptId: Long, productId: Long): ProductDetailResult {
        val token = requireToken()
        val args = buildJsonObject {
            put("deptId", deptId)
            put("productId", productId)
            put("delivery", "pick")
        }
        val text = client.callTool(token, "queryProductDetailInfo", args)
        return try {
            json.decodeFromString(ProductDetailResult.serializer(), text)
        } catch (e: Exception) {
            ProductDetailResult(productId = productId)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 工具 4: switchProduct — 切换 SKU（已知有 bug，可能失败）
    // ════════════════════════════════════════════════════════════════

    suspend fun switchProduct(
        deptId: Long,
        productId: Long,
        skuCode: String,
        attributeId: Long,
        subAttributeId: Long,
        operation: Int,
        amount: Int
    ): ProductDetailResult? {
        val token = requireToken()
        val args = buildJsonObject {
            put("deptId", deptId)
            put("productId", productId)
            put("skuCode", skuCode)
            put("amount", amount)
            putJsonObject("attrOperationParam") {
                put("attributeId", attributeId)
                putJsonObject("subAttr") {
                    put("attributeId", subAttributeId)
                    put("operation", operation)
                }
            }
        }
        return try {
            val text = client.callTool(token, "switchProduct", args)
            json.decodeFromString(ProductDetailResult.serializer(), text)
        } catch (e: McpException) {
            // switchProduct 已知有 bug（返回"非法参数"），返回 null 让上层降级
            null
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 工具 5: previewOrder — 订单预览（获取真实价格+优惠券）
    // ════════════════════════════════════════════════════════════════

    suspend fun previewOrder(
        deptId: Long,
        productList: List<com.lianyu.ai.feature.coffee.data.model.ProductListItem>
    ): OrderPreview {
        val token = requireToken()
        val args = buildJsonObject {
            put("deptId", deptId)
            putJsonArray("productList") {
                productList.forEach { item ->
                    add(buildJsonObject {
                        put("amount", item.amount)
                        put("productId", item.productId)
                        put("skuCode", item.skuCode)
                    })
                }
            }
        }
        val text = client.callTool(token, "previewOrder", args)
        return try {
            json.decodeFromString(OrderPreview.serializer(), text)
        } catch (e: Exception) {
            // 尝试从 JsonObject 提取
            try {
                val obj = json.parseToJsonElement(text) as JsonObject
                OrderPreview(
                    totalInitialPrice = obj["totalInitialPrice"]?.toString()?.toDoubleOrNull() ?: 0.0,
                    privilegeMoney = obj["privilegeMoney"]?.toString()?.toDoubleOrNull() ?: 0.0,
                    discountPrice = obj["discountPrice"]?.toString()?.toDoubleOrNull() ?: 0.0,
                    couponCodeList = (obj["couponCodeList"] as? JsonArray)
                        ?.map { it.toString().trim('"') }
                        ?: emptyList()
                )
            } catch (e2: Exception) {
                OrderPreview()
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 工具 6: createOrder — 创建订单（生成支付二维码）
    // ════════════════════════════════════════════════════════════════

    suspend fun createOrder(
        deptId: Long,
        productList: List<com.lianyu.ai.feature.coffee.data.model.ProductListItem>,
        longitude: Double,
        latitude: Double,
        couponCodeList: List<String>? = null
    ): OrderCreated {
        val token = requireToken()
        val args = buildJsonObject {
            put("deptId", deptId)
            put("longitude", longitude)
            put("latitude", latitude)
            putJsonArray("productList") {
                productList.forEach { item ->
                    add(buildJsonObject {
                        put("amount", item.amount)
                        put("productId", item.productId)
                        put("skuCode", item.skuCode)
                    })
                }
            }
            if (!couponCodeList.isNullOrEmpty()) {
                putJsonArray("couponCodeList") {
                    couponCodeList.forEach { add(JsonPrimitive(it)) }
                }
            }
        }
        val text = client.callTool(token, "createOrder", args)
        return try {
            json.decodeFromString(OrderCreated.serializer(), text)
        } catch (e: Exception) {
            val obj = json.parseToJsonElement(text) as? JsonObject
            OrderCreated(
                orderId = obj?.get("orderId")?.toString()?.trim('"') ?: "",
                payOrderQrCodeUrl = obj?.get("payOrderQrCodeUrl")?.toString()?.trim('"') ?: "",
                payOrderUrl = obj?.get("payOrderUrl")?.toString()?.trim('"') ?: "",
                discountPrice = obj?.get("discountPrice")?.toString()?.toDoubleOrNull() ?: 0.0,
                deptName = obj?.get("deptName")?.toString()?.trim('"') ?: ""
            )
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 工具 7: queryOrderDetailInfo — 查询订单详情
    // ════════════════════════════════════════════════════════════════

    suspend fun queryOrderDetail(orderId: String): OrderDetail {
        val token = requireToken()
        val args = buildJsonObject {
            put("orderId", orderId)
        }
        val text = client.callTool(token, "queryOrderDetailInfo", args)
        return try {
            json.decodeFromString(OrderDetail.serializer(), text)
        } catch (e: Exception) {
            OrderDetail(orderId = orderId)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 工具 8: cancelOrder — 取消订单
    // ════════════════════════════════════════════════════════════════

    suspend fun cancelOrder(orderId: String): Boolean {
        val token = requireToken()
        val args = buildJsonObject {
            put("orderId", orderId)
        }
        return try {
            client.callTool(token, "cancelOrder", args)
            true
        } catch (e: McpException) {
            false
        }
    }
}
