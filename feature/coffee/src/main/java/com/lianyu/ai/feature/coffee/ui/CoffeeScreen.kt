package com.lianyu.ai.feature.coffee.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lianyu.ai.feature.coffee.data.model.OrderCreated
import com.lianyu.ai.feature.coffee.data.model.OrderDetail
import com.lianyu.ai.feature.coffee.data.model.OrderPreview
import com.lianyu.ai.feature.coffee.data.model.ProductInfo
import com.lianyu.ai.feature.coffee.data.model.ShopInfo

// 瑞幸品牌色
private val LuckinBlue = Color(0xFF0066CC)
private val LuckinDarkBlue = Color(0xFF003D7A)
private val LuckinLightBlue = Color(0xFFE6F0FF)
private val LuckinRed = Color(0xFFE1251B)
private val LuckinGray = Color(0xFFF5F5F5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoffeeScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: CoffeeViewModel = viewModel(factory = CoffeeViewModel.factory(context))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 错误提示
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Coffee, contentDescription = null, tint = LuckinBlue)
                        Spacer(Modifier.width(8.dp))
                        Text("瑞幸咖啡", fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.currentStep == OrderStep.TOKEN_CONFIG || uiState.currentStep == OrderStep.SHOP_SELECT) {
                            onBack()
                        } else {
                            viewModel.goBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LuckinBlue,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LuckinGray)
                .padding(padding)
        ) {
            when (uiState.currentStep) {
                OrderStep.TOKEN_CONFIG -> TokenConfigContent(viewModel, uiState)
                OrderStep.SHOP_SELECT -> ShopSelectContent(viewModel, uiState)
                OrderStep.PRODUCT_SELECT -> ProductSelectContent(viewModel, uiState)
                OrderStep.ORDER_CONFIRM -> OrderConfirmContent(viewModel, uiState)
                OrderStep.ORDER_PREVIEW -> OrderPreviewContent(viewModel, uiState)
                OrderStep.PAYMENT -> PaymentContent(viewModel, uiState)
                OrderStep.ORDER_STATUS -> OrderStatusContent(viewModel, uiState)
            }

            // 全局加载遮罩
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = LuckinBlue)
                            Spacer(Modifier.height(12.dp))
                            Text("加载中…", color = LuckinDarkBlue)
                        }
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 步骤 1: Token 配置
// ════════════════════════════════════════════════════════════════

@Composable
private fun TokenConfigContent(viewModel: CoffeeViewModel, state: CoffeeUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        Icon(
            Icons.Default.Coffee,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = LuckinBlue
        )

        Spacer(Modifier.height(16.dp))
        Text("瑞幸 MCP Token 配置", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LuckinDarkBlue)
        Spacer(Modifier.height(8.dp))
        Text(
            "访问 open.lkcoffee.com/mcp 登录获取 Token\n有效期约 30 天，与瑞幸账号绑定",
            fontSize = 13.sp,
            color = Color.Gray,
            lineHeight = 20.sp
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = state.tokenInput,
            onValueChange = viewModel::updateTokenInput,
            label = { Text("Bearer Token") },
            placeholder = { Text("粘贴你的瑞幸 MCP Token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            minLines = 2,
            maxLines = 4
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = viewModel::saveToken,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("保存 Token", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
        }

        if (state.isTokenConfigured) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = viewModel::clearToken,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("清除已保存的 Token", color = LuckinRed)
            }
        }

        Spacer(Modifier.height(32.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = LuckinLightBlue)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("安全说明", fontWeight = FontWeight.Bold, color = LuckinDarkBlue)
                Spacer(Modifier.height(4.dp))
                Text(
                    "• Token 与瑞幸账号会话绑定，严禁泄露\n" +
                        "• 存储在应用私有目录，卸载后清除\n" +
                        "• 仅支持到店自取，不支持外送",
                    fontSize = 12.sp,
                    color = Color.DarkGray,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 步骤 2: 门店选择
// ════════════════════════════════════════════════════════════════

@Composable
private fun ShopSelectContent(viewModel: CoffeeViewModel, state: CoffeeUiState) {
    val context = LocalContext.current
    var locationMessage by remember { mutableStateOf("") }

    // 定位权限请求
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        if (granted) {
            fetchLocation(context) { lat, lng, precise ->
                viewModel.updateLocation(lng, lat, precise)
                locationMessage = if (precise) "定位成功" else "定位成功（粗略）"
            }
        } else {
            locationMessage = "定位权限被拒绝，可手动输入经纬度"
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 定位/地址输入区
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = LuckinBlue)
                    Spacer(Modifier.width(8.dp))
                    Text("门店定位", fontWeight = FontWeight.Bold, color = LuckinDarkBlue)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        state.hasPreciseLocation -> "已定位: %.6f, %.6f".format(state.longitude, state.latitude)
                        locationMessage.isNotEmpty() -> locationMessage
                        else -> "点击下方按钮获取定位"
                    },
                    fontSize = 13.sp,
                    color = Color.Gray
                )
                Spacer(Modifier.height(12.dp))

                // 获取定位按钮
                OutlinedButton(
                    onClick = {
                        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (hasFine || hasCoarse) {
                            fetchLocation(context) { lat, lng, precise ->
                                viewModel.updateLocation(lng, lat, precise)
                                locationMessage = if (precise) "定位成功" else "定位成功（粗略）"
                            }
                        } else {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("获取当前位置")
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    label = { Text("门店名称（可选）") },
                    placeholder = { Text("如：诺布中心店") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { viewModel.queryShops(state.searchQuery.ifBlank { null }) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("查找门店")
                }
            }
        }

        // 门店列表
        if (state.shops.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.shops) { shop ->
                    ShopCard(shop = shop, hasPreciseLocation = state.hasPreciseLocation) {
                        viewModel.selectShop(shop)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShopCard(shop: ShopInfo, hasPreciseLocation: Boolean, onSelect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(shop.deptName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = LuckinDarkBlue)
            Spacer(Modifier.height(4.dp))
            Text(shop.address, fontSize = 13.sp, color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("营业: ${shop.workTimeStart} - ${shop.workTimeEnd}", fontSize = 12.sp, color = Color.Gray)
                if (hasPreciseLocation && shop.distance > 0) {
                    Spacer(Modifier.width(12.dp))
                    Text("距离: ${shop.distance}km", fontSize = 12.sp, color = LuckinBlue)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("选择此门店", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 步骤 3: 商品搜索与选择
// ════════════════════════════════════════════════════════════════

@Composable
private fun ProductSelectContent(viewModel: CoffeeViewModel, state: CoffeeUiState) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 已选门店
        state.selectedShop?.let { shop ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = LuckinLightBlue)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = LuckinBlue, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(shop.deptName, fontWeight = FontWeight.Bold, color = LuckinDarkBlue, fontSize = 14.sp)
                }
            }
        }

        // 搜索栏
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Row(
                Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    placeholder = { Text("搜索商品，如：生椰拿铁") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = viewModel::searchProducts) {
                    Icon(Icons.Default.Search, contentDescription = "搜索", tint = LuckinBlue)
                }
            }
        }

        // 商品列表
        if (state.products.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.products) { product ->
                    ProductCard(product = product, onAdd = { viewModel.addProduct(product) })
                }
            }
        }

        // 已选商品 + 确认按钮
        if (state.selectedProducts.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("已选商品 (${state.selectedProducts.size})", fontWeight = FontWeight.Bold, color = LuckinDarkBlue)
                    Spacer(Modifier.height(8.dp))
                    state.selectedProducts.forEach { (product, amount) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${product.name} x$amount", fontSize = 14.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.removeProduct(product.productId) }) {
                                Icon(Icons.Default.Delete, contentDescription = "移除", tint = LuckinRed, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = viewModel::goToOrderConfirm,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("确认订单", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductCard(product: ProductInfo, onAdd: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            product.breviaryPicUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .background(LuckinGray, RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(product.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = LuckinDarkBlue)
                if (product.description.isNotBlank()) {
                    Text(product.description, fontSize = 12.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                Text("¥${product.estimatePrice}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LuckinRed)
            }
            Button(
                onClick = onAdd,
                colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text("添加", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 步骤 4: 订单确认
// ════════════════════════════════════════════════════════════════

@Composable
private fun OrderConfirmContent(viewModel: CoffeeViewModel, state: CoffeeUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        state.selectedShop?.let { shop ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("自提门店", fontWeight = FontWeight.Bold, color = LuckinDarkBlue)
                    Spacer(Modifier.height(4.dp))
                    Text(shop.deptName, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(shop.address, fontSize = 13.sp, color = Color.Gray)
                    Text("营业: ${shop.workTimeStart} - ${shop.workTimeEnd}", fontSize = 12.sp, color = Color.Gray)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("商品明细", fontWeight = FontWeight.Bold, color = LuckinDarkBlue)
                Spacer(Modifier.height(8.dp))
                var totalEstimate = 0.0
                state.selectedProducts.forEach { (product, amount) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${product.name} x$amount", fontSize = 14.sp)
                        val price = product.estimatePrice * amount
                        totalEstimate += price
                        Text("¥${"%.2f".format(price)}", fontSize = 14.sp, color = LuckinRed)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("预估总价", fontWeight = FontWeight.Bold)
                    Text("¥${"%.2f".format(totalEstimate)}", fontWeight = FontWeight.Bold, color = LuckinRed, fontSize = 18.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = LuckinLightBlue)
        ) {
            Text(
                "确认后将调用 previewOrder 获取真实价格和优惠。\n若最终价格不高于预估价，将直接创建订单。",
                Modifier.padding(16.dp),
                fontSize = 12.sp,
                color = LuckinDarkBlue,
                lineHeight = 18.sp
            )
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = viewModel::previewOrder,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("预览订单（获取真实价格）", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 步骤 5: 订单预览
// ════════════════════════════════════════════════════════════════

@Composable
private fun OrderPreviewContent(viewModel: CoffeeViewModel, state: CoffeeUiState) {
    val preview = state.orderPreview ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("订单预览", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = LuckinDarkBlue)
                Spacer(Modifier.height(16.dp))

                PriceRow("原价", preview.totalInitialPrice)
                PriceRow("优惠", -preview.privilegeMoney, color = LuckinRed)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("应付金额", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("¥${"%.2f".format(preview.discountPrice)}", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = LuckinRed)
                }

                if (preview.couponCodeList.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("已使用优惠券: ${preview.couponCodeList.size} 张", fontSize = 12.sp, color = LuckinBlue)
                }

                if (preview.products.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("商品明细", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    preview.products.forEach { p ->
                        Text("${p.name} x${p.amount}  ¥${"%.2f".format(p.price)}", fontSize = 13.sp, color = Color.Gray)
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = viewModel::createOrder,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("确认下单（创建订单）", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun PriceRow(label: String, price: Double, color: Color = Color.DarkGray) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = color)
        Text("¥${"%.2f".format(price)}", fontSize = 14.sp, color = color)
    }
}

// ════════════════════════════════════════════════════════════════
// 步骤 6: 支付
// ════════════════════════════════════════════════════════════════

@Composable
private fun PaymentContent(viewModel: CoffeeViewModel, state: CoffeeUiState) {
    val order = state.createdOrder ?: return
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        Text("订单已创建", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LuckinDarkBlue)
        Spacer(Modifier.height(8.dp))
        Text("订单号: ${order.orderId}", fontSize = 14.sp, color = Color.Gray)
        if (order.deptName.isNotBlank()) {
            Text("门店: ${order.deptName}", fontSize = 14.sp, color = Color.Gray)
        }

        Spacer(Modifier.height(24.dp))

        // 支付二维码（仅使用 payOrderQrCodeUrl，不展示 payOrderUrl）
        if (order.payOrderQrCodeUrl.isNotBlank()) {
            Card(
                modifier = Modifier.size(240.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                AsyncImage(
                    model = order.payOrderQrCodeUrl,
                    contentDescription = "支付二维码",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text("扫码支付", fontWeight = FontWeight.Bold, color = LuckinBlue)

            Spacer(Modifier.height(8.dp))
            // 可点击的支付链接（二维码无法展示时的备选）
            TextButton(onClick = {
                // 打开浏览器支付链接
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(order.payOrderQrCodeUrl))
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }) {
                Text("打开支付链接", color = LuckinBlue)
            }
        }

        Spacer(Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = LuckinLightBlue)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("支付完成后告诉我一声，我可以马上帮你查询订单状态和取餐码。", fontSize = 13.sp, color = LuckinDarkBlue)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = viewModel::queryCurrentOrderStatus,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("已支付，帮我查取餐码", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.navigateToStep(OrderStep.SHOP_SELECT) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("还没支付，稍后再查")
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// 步骤 7: 订单状态
// ════════════════════════════════════════════════════════════════

@Composable
private fun OrderStatusContent(viewModel: CoffeeViewModel, state: CoffeeUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 订单号输入（如果是从订单查询入口进来的）
        if (state.createdOrder == null) {
            OutlinedTextField(
                value = state.queryOrderId,
                onValueChange = viewModel::updateQueryOrderId,
                label = { Text("订单号") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::queryOrderStatus,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("查询订单状态")
            }
            Spacer(Modifier.height(16.dp))
        }

        state.orderDetail?.let { detail ->
            OrderDetailCard(detail)
            Spacer(Modifier.height(16.dp))

            // 取消订单按钮（仅未支付/未完成时可取消）
            if (detail.orderStatus == OrderDetail.STATUS_UNPAID ||
                detail.orderStatus == OrderDetail.STATUS_SUCCESS ||
                detail.orderStatus == OrderDetail.STATUS_MAKING
            ) {
                OutlinedButton(
                    onClick = viewModel::cancelOrder,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LuckinRed)
                ) {
                    Text("取消订单")
                }
            }
        }
    }
}

@Composable
private fun OrderDetailCard(detail: OrderDetail) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp)) {
            // 状态标签
            val statusColor = when (detail.orderStatus) {
                OrderDetail.STATUS_UNPAID -> Color(0xFFFF9800)
                OrderDetail.STATUS_MAKING -> LuckinBlue
                OrderDetail.STATUS_WAITING -> Color(0xFF4CAF50)
                OrderDetail.STATUS_DONE -> Color(0xFF4CAF50)
                OrderDetail.STATUS_CANCELED -> Color.Gray
                else -> LuckinDarkBlue
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(statusColor, RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.width(8.dp))
                Text(detail.orderStatusName, fontWeight = FontWeight.Bold, color = statusColor, fontSize = 16.sp)
            }

            Spacer(Modifier.height(12.dp))
            Text("订单号: ${detail.orderId}", fontSize = 13.sp, color = Color.Gray)
            if (detail.deptName.isNotBlank()) {
                Text("门店: ${detail.deptName}", fontSize = 13.sp, color = Color.Gray)
            }
            if (detail.address.isNotBlank()) {
                Text("地址: ${detail.address}", fontSize = 13.sp, color = Color.Gray)
            }

            // 取餐码（仅已支付时展示）
            detail.takeMealCodeInfo?.let { codeInfo ->
                if (codeInfo.code.isNotBlank() && detail.orderStatus >= OrderDetail.STATUS_SUCCESS) {
                    Spacer(Modifier.height(16.dp))
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = LuckinLightBlue)
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("取餐码", fontSize = 13.sp, color = LuckinDarkBlue)
                            Text(codeInfo.code, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = LuckinRed)
                            if (detail.aboutTime > 0) {
                                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                                    .format(java.util.Date(detail.aboutTime * 1000))
                                Text("预计取餐: $time", fontSize = 13.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }

            // 商品列表
            detail.productInfoList?.let { products ->
                if (products.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("商品", fontWeight = FontWeight.Bold, color = LuckinDarkBlue)
                    products.forEach { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${p.name} x${p.amount}", fontSize = 13.sp)
                            if (p.additionDesc.isNotBlank()) {
                                Text(p.additionDesc, fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("支付金额", fontWeight = FontWeight.Bold)
                Text("¥${"%.2f".format(detail.orderPayAmount)}", fontWeight = FontWeight.Bold, color = LuckinRed)
            }
        }
    }
}

/**
 * 获取当前位置（使用系统 LocationManager）
 * @param onResult 回调 (latitude, longitude, isPrecise)
 */
private fun fetchLocation(
    context: Context,
    onResult: (lat: Double, lng: Double, precise: Boolean) -> Unit
) {
    try {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled && !isNetworkEnabled) {
            // 定位服务未开启，使用默认位置（北京天安门）
            onResult(39.9087, 116.3975, false)
            return
        }

        // 优先使用 GPS，其次网络定位
        val provider = when {
            isGpsEnabled -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            val location: Location? = locationManager.getLastKnownLocation(provider)
            if (location != null) {
                onResult(location.latitude, location.longitude, provider == LocationManager.GPS_PROVIDER)
            } else {
                // 最后已知位置为空，使用网络定位的默认值
                val networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (networkLocation != null) {
                    onResult(networkLocation.latitude, networkLocation.longitude, false)
                } else {
                    // 无法获取位置，使用默认位置（北京天安门）
                    onResult(39.9087, 116.3975, false)
                }
            }
        } else {
            onResult(39.9087, 116.3975, false)
        }
    } catch (e: SecurityException) {
        // 权限被收回，使用默认位置
        onResult(39.9087, 116.3975, false)
    } catch (e: Exception) {
        onResult(39.9087, 116.3975, false)
    }
}
