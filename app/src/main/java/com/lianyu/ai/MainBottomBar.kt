package com.lianyu.ai

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lianyu.ai.R
import com.lianyu.ai.uicommon.theme.WeChatDarkBackground
import com.lianyu.ai.uicommon.theme.WeChatDarkTextPrimary
import com.lianyu.ai.uicommon.theme.WeChatLightBackground
import com.lianyu.ai.uicommon.theme.WeChatLightTextPrimary

/** 底部导航项数据类 */
data class BottomNavItem(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val route: String
)

/** 底部毛玻璃导航栏 — 独立重组域 */
@Composable
fun FloatingGlassBottomNav(
    items: List<BottomNavItem>,
    currentIndex: Int,
    onItemClick: (Int) -> Unit,
    isDark: Boolean
) {
    val selectedColor = Color(0xFFF4A6B5)
    val unselectedColor = if (isDark) Color(0xFF8E8E93) else Color(0xFF8A8A8E)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(
                    if (isDark) Color(0xFF2D2228).copy(alpha = 0.95f)
                    else Color(0xFFFFFFFF).copy(alpha = 0.95f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    val selected = currentIndex == index
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onItemClick(index) }
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.title,
                            modifier = Modifier.size(20.dp),
                            tint = if (selected) selectedColor else unselectedColor
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 9.sp
                            ),
                            color = if (selected) selectedColor else unselectedColor
                        )
                    }
                }
            }
        }
    }
}

/** 微信风格顶部栏 — 独立重组域 */
@Composable
fun WeChatTopBar(
    title: String,
    isDark: Boolean,
    isVisible: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val bgColor = if (isDark) WeChatDarkBackground else WeChatLightBackground

    Box(
        modifier = Modifier
            .background(if (isVisible) bgColor else Color.Transparent)
            .windowInsetsPadding(WindowInsets.statusBars)
            .fillMaxWidth()
            .height(44.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isVisible) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp
                    ),
                    color = if (isDark) WeChatDarkTextPrimary else WeChatLightTextPrimary,
                    textAlign = TextAlign.Start
                )
                Row(
                    modifier = Modifier.padding(end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = actions
                )
            }
        }
    }
}

/** 首页顶部栏操作按钮 */
@Composable
fun HomeTopBarActions(navController: NavHostController, isDark: Boolean) {
    val iconBgColor = if (isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F2)
    val iconTint = if (isDark) WeChatDarkTextPrimary else WeChatLightTextPrimary

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(iconBgColor)
                .clickable { navController.navigate("contacts") },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Group,
                contentDescription = stringResource(R.string.nav_contacts),
                modifier = Modifier.size(20.dp),
                tint = iconTint
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(iconBgColor)
                .clickable { navController.navigate("profile") },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.PersonOutline,
                contentDescription = stringResource(R.string.nav_profile),
                modifier = Modifier.size(20.dp),
                tint = iconTint
            )
        }
    }
}
