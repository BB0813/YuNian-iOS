package com.lianyu.ai.feature.coffee.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lianyu.ai.feature.coffee.data.LuckinMcpClient
import com.lianyu.ai.feature.coffee.data.LuckinTokenStore
import com.lianyu.ai.feature.coffee.data.McpException
import com.lianyu.ai.feature.coffee.data.model.OrderCreated
import com.lianyu.ai.feature.coffee.data.model.OrderDetail
import com.lianyu.ai.feature.coffee.data.model.OrderPreview
import com.lianyu.ai.feature.coffee.data.model.ProductInfo
import com.lianyu.ai.feature.coffee.data.model.ProductListItem
import com.lianyu.ai.feature.coffee.data.model.ShopInfo
import com.lianyu.ai.feature.coffee.domain.CoffeeOrderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 下单流程步骤（严格约束：不可跳步）
 */
enum class OrderStep {
    /** 配置 Token */
    TOKEN_CONFIG,
    /** 选择门店 */
    SHOP_SELECT,
    /** 搜索并选择商品 */
    PRODUCT_SELECT,
    /** 确认订单（展示明细+预估价） */
    ORDER_CONFIRM,
    /** 订单预览（previewOrder 返回真实价格+优惠） */
    ORDER_PREVIEW,
    /** 支付（展示二维码） */
    PAYMENT,
    /** 订单状态查询 */
    ORDER_STATUS
}

/**
 * UI 状态
 */
data class CoffeeUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isTokenConfigured: Boolean = false,
    val tokenInput: String = "",
    val currentStep: OrderStep = OrderStep.TOKEN_CONFIG,
    val shops: List<ShopInfo> = emptyList(),
    val selectedShop: ShopInfo? = null,
    val searchQuery: String = "",
    val products: List<ProductInfo> = emptyList(),
    val selectedProducts: List<Pair<ProductInfo, Int>> = emptyList(),
    val orderPreview: OrderPreview? = null,
    val createdOrder: OrderCreated? = null,
    val orderDetail: OrderDetail? = null,
    val queryOrderId: String = "",
    val longitude: Double = 0.0,
    val latitude: Double = 0.0,
    val hasPreciseLocation: Boolean = false
)

class CoffeeViewModel(
    private val repository: CoffeeOrderRepository,
    private val tokenStore: LuckinTokenStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoffeeUiState())
    val uiState: StateFlow<CoffeeUiState> = _uiState.asStateFlow()

    init {
        checkTokenStatus()
    }

    private fun checkTokenStatus() {
        viewModelScope.launch {
            val token = tokenStore.token.first()
            _uiState.value = _uiState.value.copy(
                isTokenConfigured = token.isNotBlank(),
                currentStep = if (token.isNotBlank()) OrderStep.SHOP_SELECT else OrderStep.TOKEN_CONFIG
            )
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Token 管理
    // ════════════════════════════════════════════════════════════════

    fun updateTokenInput(input: String) {
        _uiState.value = _uiState.value.copy(tokenInput = input)
    }

    fun saveToken() {
        val token = _uiState.value.tokenInput.trim()
        if (token.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Token 不能为空")
            return
        }
        viewModelScope.launch {
            tokenStore.saveToken(token)
            _uiState.value = _uiState.value.copy(
                isTokenConfigured = true,
                tokenInput = "",
                currentStep = OrderStep.SHOP_SELECT,
                errorMessage = null
            )
        }
    }

    fun clearToken() {
        viewModelScope.launch {
            tokenStore.clearToken()
            _uiState.value = CoffeeUiState(currentStep = OrderStep.TOKEN_CONFIG)
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 门店查询
    // ════════════════════════════════════════════════════════════════

    fun updateLocation(longitude: Double, latitude: Double, precise: Boolean) {
        _uiState.value = _uiState.value.copy(
            longitude = longitude,
            latitude = latitude,
            hasPreciseLocation = precise
        )
    }

    fun queryShops(deptName: String? = null) {
        val state = _uiState.value
        if (state.longitude == 0.0 || state.latitude == 0.0) {
            _uiState.value = state.copy(errorMessage = "需要定位来查找附近门店")
            return
        }
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, errorMessage = null)
            try {
                val shops = repository.queryShopList(
                    longitude = state.longitude,
                    latitude = state.latitude,
                    deptName = deptName
                )
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    shops = shops,
                    errorMessage = if (shops.isEmpty()) "未找到门店，请更换位置重试" else null
                )
            } catch (e: McpException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = if (e.isAuthError) "Token 无效或已过期，请重新获取" else e.message
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "网络错误: ${e.message}"
                )
            }
        }
    }

    fun selectShop(shop: ShopInfo) {
        _uiState.value = _uiState.value.copy(
            selectedShop = shop,
            currentStep = OrderStep.PRODUCT_SELECT,
            products = emptyList(),
            searchQuery = ""
        )
    }

    // ════════════════════════════════════════════════════════════════
    // 商品搜索
    // ════════════════════════════════════════════════════════════════

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun searchProducts() {
        val state = _uiState.value
        val shop = state.selectedShop
        if (shop == null || state.searchQuery.isBlank()) return

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, errorMessage = null)
            try {
                val products = repository.searchProduct(shop.deptId, state.searchQuery)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    products = products,
                    errorMessage = if (products.isEmpty()) "未找到相关商品" else null
                )
            } catch (e: McpException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = if (e.isAuthError) "Token 无效或已过期" else e.message
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "搜索失败: ${e.message}"
                )
            }
        }
    }

    fun addProduct(product: ProductInfo, amount: Int = 1) {
        val current = _uiState.value.selectedProducts.toMutableList()
        val existing = current.indexOfFirst { it.first.productId == product.productId }
        if (existing >= 0) {
            current[existing] = product to (current[existing].second + amount)
        } else {
            current.add(product to amount)
        }
        _uiState.value = _uiState.value.copy(selectedProducts = current)
    }

    fun removeProduct(productId: Long) {
        _uiState.value = _uiState.value.copy(
            selectedProducts = _uiState.value.selectedProducts.filterNot { it.first.productId == productId }
        )
    }

    fun goToOrderConfirm() {
        if (_uiState.value.selectedProducts.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorMessage = "请先选择商品")
            return
        }
        _uiState.value = _uiState.value.copy(currentStep = OrderStep.ORDER_CONFIRM)
    }

    // ════════════════════════════════════════════════════════════════
    // 订单预览 + 创建（强约束：previewOrder → createOrder 不可跳步）
    // ════════════════════════════════════════════════════════════════

    fun previewOrder() {
        val state = _uiState.value
        val shop = state.selectedShop
        if (shop == null || state.selectedProducts.isEmpty()) return

        val productList = state.selectedProducts.map { (product, amount) ->
            ProductListItem(
                amount = amount,
                productId = product.productId,
                skuCode = product.skus.firstOrNull()?.skuCode ?: ""
            )
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, errorMessage = null, currentStep = OrderStep.ORDER_PREVIEW)
            try {
                val preview = repository.previewOrder(shop.deptId, productList)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    orderPreview = preview
                )
            } catch (e: McpException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = if (e.isAuthError) "Token 无效或已过期" else e.message,
                    currentStep = OrderStep.ORDER_CONFIRM
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "预览失败: ${e.message}",
                    currentStep = OrderStep.ORDER_CONFIRM
                )
            }
        }
    }

    fun createOrder() {
        val state = _uiState.value
        val shop = state.selectedShop
        val preview = state.orderPreview
        if (shop == null || preview == null || state.selectedProducts.isEmpty()) return

        val productList = state.selectedProducts.map { (product, amount) ->
            ProductListItem(
                amount = amount,
                productId = product.productId,
                skuCode = product.skus.firstOrNull()?.skuCode ?: ""
            )
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, errorMessage = null)
            try {
                val order = repository.createOrder(
                    deptId = shop.deptId,
                    productList = productList,
                    longitude = shop.longitude,
                    latitude = shop.latitude,
                    couponCodeList = preview.couponCodeList.ifEmpty { null }
                )
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    createdOrder = order,
                    currentStep = OrderStep.PAYMENT
                )
            } catch (e: McpException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = if (e.isAuthError) "Token 无效或已过期" else e.message
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "下单失败: ${e.message}"
                )
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 订单查询
    // ════════════════════════════════════════════════════════════════

    fun updateQueryOrderId(orderId: String) {
        _uiState.value = _uiState.value.copy(queryOrderId = orderId)
    }

    fun queryOrderStatus() {
        val orderId = _uiState.value.queryOrderId.trim()
        if (orderId.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "请输入订单号")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, currentStep = OrderStep.ORDER_STATUS)
            try {
                val detail = repository.queryOrderDetail(orderId)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    orderDetail = detail
                )
            } catch (e: McpException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = if (e.isAuthError) "Token 无效或已过期" else e.message
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "查询失败: ${e.message}"
                )
            }
        }
    }

    /** 支付完成后查询取餐码 */
    fun queryCurrentOrderStatus() {
        val orderId = _uiState.value.createdOrder?.orderId
        if (orderId.isNullOrBlank()) return
        _uiState.value = _uiState.value.copy(queryOrderId = orderId)
        queryOrderStatus()
    }

    fun cancelOrder() {
        val orderId = _uiState.value.createdOrder?.orderId ?: _uiState.value.queryOrderId
        if (orderId.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val success = repository.cancelOrder(orderId)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = if (success) "订单已取消" else "取消失败，请重试"
                )
                if (success) {
                    queryOrderStatus()
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "取消失败: ${e.message}"
                )
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 导航
    // ════════════════════════════════════════════════════════════════

    fun navigateToStep(step: OrderStep) {
        _uiState.value = _uiState.value.copy(currentStep = step, errorMessage = null)
    }

    fun goBack() {
        val current = _uiState.value.currentStep
        val previous = when (current) {
            OrderStep.PRODUCT_SELECT -> OrderStep.SHOP_SELECT
            OrderStep.ORDER_CONFIRM -> OrderStep.PRODUCT_SELECT
            OrderStep.ORDER_PREVIEW -> OrderStep.ORDER_CONFIRM
            OrderStep.PAYMENT -> OrderStep.ORDER_CONFIRM
            OrderStep.ORDER_STATUS -> OrderStep.SHOP_SELECT
            else -> null
        }
        if (previous != null) {
            _uiState.value = _uiState.value.copy(currentStep = previous, errorMessage = null)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun resetOrder() {
        _uiState.value = _uiState.value.copy(
            selectedProducts = emptyList(),
            orderPreview = null,
            createdOrder = null,
            orderDetail = null,
            currentStep = OrderStep.SHOP_SELECT
        )
    }

    companion object {
        fun factory(context: android.content.Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val tokenStore = LuckinTokenStore(context)
                    val client = LuckinMcpClient()
                    val repository = CoffeeOrderRepository(client, tokenStore)
                    return CoffeeViewModel(repository, tokenStore) as T
                }
            }
    }
}
