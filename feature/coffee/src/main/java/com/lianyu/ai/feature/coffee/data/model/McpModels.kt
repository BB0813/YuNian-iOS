package com.lianyu.ai.feature.coffee.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ════════════════════════════════════════════════════════════════
// MCP JSON-RPC 2.0 协议层
// ════════════════════════════════════════════════════════════════

/**
 * MCP JSON-RPC 请求信封。
 * 瑞幸 MCP Server 使用 Streamable HTTP，单次 POST 携带一个 JSON-RPC 请求。
 */
@Serializable
internal data class McpRequest(
    @SerialName("jsonrpc") val jsonrpc: String = "2.0",
    val method: String,
    val params: McpParams,
    val id: Int = 1
)

@Serializable
internal data class McpParams(
    val name: String,
    val arguments: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()
)

/**
 * MCP JSON-RPC 响应信封。
 * result.content 是工具返回的文本块数组，text 字段里是 JSON 字符串，需二次解析。
 */
@Serializable
internal data class McpResponse(
    @SerialName("jsonrpc") val jsonrpc: String = "2.0",
    val id: Int = 1,
    val result: McpResult? = null,
    val error: McpError? = null
)

@Serializable
internal data class McpResult(
    val content: List<McpContent> = emptyList(),
    @SerialName("structuredContent") val structuredContent: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("isError") val isError: Boolean = false
)

@Serializable
internal data class McpContent(
    val type: String = "text",
    val text: String = ""
)

@Serializable
internal data class McpError(
    val code: Int = 0,
    val message: String = ""
)

// ════════════════════════════════════════════════════════════════
// 工具参数模型（按 SKILL.md 工具参考定义）
// ════════════════════════════════════════════════════════════════

/** queryShopList 参数 */
@Serializable
internal data class QueryShopArgs(
    val longitude: Double,
    val latitude: Double,
    @SerialName("deptName") val deptName: String? = null
)

/** searchProductForMcp 参数 */
@Serializable
internal data class SearchProductArgs(
    @SerialName("deptId") val deptId: Long,
    val query: String
)

/** queryProductDetailInfo 参数 */
@Serializable
internal data class ProductDetailArgs(
    @SerialName("deptId") val deptId: Long,
    @SerialName("productId") val productId: Long,
    val delivery: String = "pick"
)

/** switchProduct 参数 */
@Serializable
internal data class SwitchProductArgs(
    @SerialName("deptId") val deptId: Long,
    @SerialName("productId") val productId: Long,
    @SerialName("skuCode") val skuCode: String,
    @SerialName("attrOperationParam") val attrOperationParam: AttrOperationParam,
    val amount: Int
)

@Serializable
internal data class AttrOperationParam(
    @SerialName("attributeId") val attributeId: Long,
    @SerialName("subAttr") val subAttr: SubAttr
)

@Serializable
internal data class SubAttr(
    @SerialName("attributeId") val attributeId: Long,
    val operation: Int
)

/** previewOrder / createOrder 商品项 */
@Serializable
data class ProductListItem(
    val amount: Int,
    @SerialName("productId") val productId: Long,
    @SerialName("skuCode") val skuCode: String
)

/** previewOrder 参数 */
@Serializable
internal data class PreviewOrderArgs(
    @SerialName("deptId") val deptId: Long,
    @SerialName("productList") val productList: List<ProductListItem>
)

/** createOrder 参数 */
@Serializable
internal data class CreateOrderArgs(
    @SerialName("deptId") val deptId: Long,
    @SerialName("productList") val productList: List<ProductListItem>,
    val longitude: Double,
    val latitude: Double,
    @SerialName("couponCodeList") val couponCodeList: List<String>? = null
)

/** queryOrderDetailInfo / cancelOrder 参数 */
@Serializable
internal data class OrderIdArgs(
    @SerialName("orderId") val orderId: String
)

// ════════════════════════════════════════════════════════════════
// 工具返回业务模型（从 result.content[0].text 二次解析）
// ════════════════════════════════════════════════════════════════

/** 门店信息 */
@Serializable
data class ShopInfo(
    @SerialName("deptId") val deptId: Long = 0,
    @SerialName("deptName") val deptName: String = "",
    val address: String = "",
    @SerialName("deptTags") val deptTags: List<String> = emptyList(),
    val longitude: Double = 0.0,
    val latitude: Double = 0.0,
    @SerialName("workTimeStart") val workTimeStart: String = "",
    @SerialName("workTimeEnd") val workTimeEnd: String = "",
    val distance: Double = 0.0,
    val number: String = ""
)

/** queryShopList 返回 */
@Serializable
data class ShopListResult(
    val list: List<ShopInfo> = emptyList()
)

/** 商品 SKU */
@Serializable
data class ProductSku(
    @SerialName("skuCode") val skuCode: String = "",
    val name: String = "",
    val price: Double = 0.0,
    @SerialName("estimatePrice") val estimatePrice: Double = 0.0,
    val spec: String = ""
)

/** 商品信息 */
@Serializable
data class ProductInfo(
    @SerialName("productId") val productId: Long = 0,
    val name: String = "",
    val description: String = "",
    @SerialName("bigPicUrl") val bigPicUrl: String? = null,
    @SerialName("breviaryPicUrl") val breviaryPicUrl: String? = null,
    @SerialName("estimatePrice") val estimatePrice: Double = 0.0,
    val skus: List<ProductSku> = emptyList()
)

/** searchProductForMcp 返回 */
@Serializable
data class ProductSearchResult(
    val list: List<ProductInfo> = emptyList()
)

/** 商品属性选项 */
@Serializable
data class ProductAttribute(
    @SerialName("attributeId") val attributeId: Long = 0,
    val name: String = "",
    val options: List<AttributeOption> = emptyList()
)

@Serializable
data class AttributeOption(
    @SerialName("attributeId") val attributeId: Long = 0,
    val name: String = "",
    val operation: Int = 0
)

/** queryProductDetailInfo 返回 */
@Serializable
data class ProductDetailResult(
    @SerialName("productId") val productId: Long = 0,
    val name: String = "",
    val attributes: List<ProductAttribute> = emptyList(),
    @SerialName("currentSkuCode") val currentSkuCode: String = ""
)

/** previewOrder 返回 */
@Serializable
data class OrderPreview(
    @SerialName("totalInitialPrice") val totalInitialPrice: Double = 0.0,
    @SerialName("privilegeMoney") val privilegeMoney: Double = 0.0,
    @SerialName("discountPrice") val discountPrice: Double = 0.0,
    @SerialName("couponCodeList") val couponCodeList: List<String> = emptyList(),
    val products: List<PreviewProduct> = emptyList()
)

@Serializable
data class PreviewProduct(
    @SerialName("productId") val productId: Long = 0,
    val name: String = "",
    val amount: Int = 0,
    val price: Double = 0.0
)

/** createOrder 返回 */
@Serializable
data class OrderCreated(
    @SerialName("orderId") val orderId: String = "",
    @SerialName("payOrderQrCodeUrl") val payOrderQrCodeUrl: String = "",
    @SerialName("payOrderUrl") val payOrderUrl: String = "",
    @SerialName("discountPrice") val discountPrice: Double = 0.0,
    @SerialName("deptName") val deptName: String = ""
)

/** 订单商品 */
@Serializable
data class OrderProduct(
    @SerialName("productId") val productId: Long = 0,
    val name: String = "",
    val amount: Int = 0,
    @SerialName("additionDesc") val additionDesc: String = "",
    @SerialName("breviaryPicUrl") val breviaryPicUrl: String? = null
)

/** 取餐码信息 */
@Serializable
data class TakeMealCodeInfo(
    val code: String = "",
    @SerialName("takeOrderId") val takeOrderId: String = ""
)

/** 订单状态（queryOrderDetailInfo 返回） */
@Serializable
data class OrderDetail(
    @SerialName("orderId") val orderId: String = "",
    @SerialName("orderStatus") val orderStatus: Int = 0,
    @SerialName("orderStatusName") val orderStatusName: String = "",
    @SerialName("aboutTime") val aboutTime: Long = 0,
    @SerialName("takeMealTime") val takeMealTime: String = "",
    @SerialName("takeMealCodeInfo") val takeMealCodeInfo: TakeMealCodeInfo? = null,
    @SerialName("deptName") val deptName: String = "",
    val address: String = "",
    @SerialName("productInfoList") val productInfoList: List<OrderProduct>? = null,
    @SerialName("orderPayAmount") val orderPayAmount: Double = 0.0
) {
    companion object {
        // 订单状态码（来自瑞幸文档）
        const val STATUS_UNPAID = 10
        const val STATUS_SUCCESS = 20
        const val STATUS_MAKING = 30
        const val STATUS_WAITING = 60
        const val STATUS_DONE = 80
        const val STATUS_CANCELED = 100
    }
}

/** cancelOrder 返回 */
@Serializable
internal data class CancelResult(
    val success: Boolean = false,
    val message: String = ""
)
