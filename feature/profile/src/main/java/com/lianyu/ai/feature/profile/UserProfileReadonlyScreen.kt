package com.lianyu.ai.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lianyu.ai.common.CompanionRole
import com.lianyu.ai.uicommon.component.bounceVerticalScroll
import com.lianyu.ai.uicommon.theme.AppTheme

/**
 * 聊天页点击用户头像后进入的只读个人主页。
 * 仅展示资料，不提供编辑入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileReadonlyScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProfileViewModel = viewModel()
) {
    val colors = AppTheme.colors
    val userName by viewModel.userName.collectAsState()
    val userAvatar by viewModel.userAvatar.collectAsState()
    val userSignature by viewModel.userSignature.collectAsState()
    val userStatus by viewModel.userStatus.collectAsState()
    val userGender by viewModel.userGender.collectAsState()
    val selectedRole by viewModel.selectedRole.collectAsState()
    val companionCount by viewModel.companionCount.collectAsState()

    val genderLabel = when (userGender) {
        "male" -> "男"
        "female" -> "女"
        else -> "未设置"
    }
    val roleLabel = when (selectedRole) {
        CompanionRole.GIRLFRIEND -> "女友视角"
        CompanionRole.BOYFRIEND -> "男友视角"
    }
    val displayName = userName.ifBlank { "未设置昵称" }
    val displaySignature = userSignature.ifBlank { "这个人很懒，还没有写签名" }
    val displayStatus = userStatus.ifBlank { "暂无状态" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 顶部导航
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.onBackground
                )
            }
            Text(
                text = "个人主页",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                ),
                color = colors.onBackground,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(48.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .bounceVerticalScroll(resistance = 0.32f, maxOverscrollDp = 96f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // 头图 + 头像区
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    colors.primary.copy(alpha = 0.22f),
                                    colors.surface
                                )
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(colors.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!userAvatar.isNullOrBlank()) {
                            AsyncImage(
                                model = userAvatar,
                                contentDescription = "用户头像",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = "用户头像",
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp
                        ),
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = displaySignature,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProfileChip(text = genderLabel)
                        ProfileChip(text = roleLabel)
                        ProfileChip(text = displayStatus)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 数据概览
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.surface)
                    .padding(horizontal = 12.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ReadonlyStatItem(
                    value = "$companionCount",
                    label = "AI 伴侣",
                    modifier = Modifier.weight(1f)
                )
                ReadonlyStatDivider()
                ReadonlyStatItem(
                    value = genderLabel,
                    label = "性别",
                    modifier = Modifier.weight(1f)
                )
                ReadonlyStatDivider()
                ReadonlyStatItem(
                    value = displayStatus,
                    label = "状态",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 资料卡片（只读，无箭头）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.surface)
            ) {
                ReadonlyInfoRow(label = "昵称", value = displayName)
                ReadonlyInfoDivider()
                ReadonlyInfoRow(label = "性别", value = genderLabel)
                ReadonlyInfoDivider()
                ReadonlyInfoRow(label = "状态", value = displayStatus)
                ReadonlyInfoDivider()
                ReadonlyInfoRow(label = "角色视角", value = roleLabel)
                ReadonlyInfoDivider()
                ReadonlyInfoRow(
                    label = "个性签名",
                    value = displaySignature,
                    multiline = true
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "此页面为只读资料卡，不可编辑",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ProfileChip(text: String) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.primary.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = colors.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ReadonlyStatItem(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            ),
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = colors.onSurfaceVariant
        )
    }
}

@Composable
private fun ReadonlyStatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(28.dp)
            .background(AppTheme.colors.outline.copy(alpha = 0.25f))
    )
}

@Composable
private fun ReadonlyInfoRow(
    label: String,
    value: String,
    multiline: Boolean = false
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = if (multiline) 4 else 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ReadonlyInfoDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(0.5.dp)
            .background(AppTheme.colors.outline.copy(alpha = 0.25f))
    )
}
