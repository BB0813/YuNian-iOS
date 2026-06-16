package com.lianyu.ai.feature.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lianyu.ai.uicommon.theme.WeChatDarkBackground
import com.lianyu.ai.uicommon.theme.WeChatDarkCard
import com.lianyu.ai.uicommon.theme.WeChatDarkDivider
import com.lianyu.ai.uicommon.theme.WeChatDarkTextPrimary
import com.lianyu.ai.uicommon.theme.WeChatDarkTextSecondary
import com.lianyu.ai.uicommon.theme.WeChatLightBackground
import com.lianyu.ai.uicommon.theme.WeChatLightCard
import com.lianyu.ai.uicommon.theme.WeChatLightDivider
import com.lianyu.ai.uicommon.theme.WeChatLightTextPrimary
import com.lianyu.ai.uicommon.theme.WeChatLightTextSecondary
import com.lianyu.ai.uicommon.theme.ThemeViewModel
import com.lianyu.ai.uicommon.theme.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.res.stringResource
import com.lianyu.ai.feature.profile.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    onAgreementClick: () -> Unit = {}
) {
    val themeViewModel: ThemeViewModel = viewModel()
    val themeMode by themeViewModel.themeMode.collectAsState()
    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val backgroundColor = if (isDark) WeChatDarkBackground else WeChatLightBackground
    val cardColor = if (isDark) WeChatDarkCard else WeChatLightCard
    val textPrimary = if (isDark) WeChatDarkTextPrimary else WeChatLightTextPrimary
    val textSecondary = if (isDark) WeChatDarkTextSecondary else WeChatLightTextSecondary
    val dividerColor = if (isDark) WeChatDarkDivider else WeChatLightDivider

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardColor, shape = RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Text(
                        text = stringResource(R.string.about_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        ),
                        color = textPrimary
                    )

                    Spacer(modifier = Modifier.width(32.dp))
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // App Logo
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = stringResource(R.string.app_brand),
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(22.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.app_brand),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    letterSpacing = 2.sp
                ),
                color = textPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.app_slogan),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp
                ),
                color = textSecondary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.version),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp
                ),
                color = textSecondary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Description
            AboutCard(
                isDark = isDark,
                cardColor = cardColor,
                textPrimary = textPrimary,
                textSecondary = textSecondary
            ) {
                Text(
                    text = stringResource(R.string.about_desc_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    ),
                    color = textPrimary,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.about_desc),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    ),
                    color = textSecondary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Developer info
            AboutCard(
                isDark = isDark,
                cardColor = cardColor,
                textPrimary = textPrimary,
                textSecondary = textSecondary
            ) {
                Text(
                    text = stringResource(R.string.developer),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    ),
                    color = textPrimary,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.developer_douyin),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = Color(0xFF07C160)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.developer_hint),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp
                    ),
                    color = textSecondary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Features
            AboutCard(
                isDark = isDark,
                cardColor = cardColor,
                textPrimary = textPrimary,
                textSecondary = textSecondary
            ) {
                Text(
                    text = stringResource(R.string.features),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    ),
                    color = textPrimary,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(12.dp))

                FeatureItem(stringResource(R.string.feature_multi_ai), stringResource(R.string.feature_multi_ai_desc), textPrimary, textSecondary)
                FeatureItem(stringResource(R.string.feature_memory), stringResource(R.string.feature_memory_desc), textPrimary, textSecondary)
                FeatureItem(stringResource(R.string.feature_refresh), stringResource(R.string.feature_refresh_desc), textPrimary, textSecondary)
                FeatureItem(stringResource(R.string.feature_ui), stringResource(R.string.feature_ui_desc), textPrimary, textSecondary)
                FeatureItem(stringResource(R.string.feature_edit), stringResource(R.string.feature_edit_desc), textPrimary, textSecondary)
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Agreement link
            Text(
                text = stringResource(R.string.view_agreement),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    textDecoration = TextDecoration.Underline,
                    color = Color(0xFF07C160)
                ),
                modifier = Modifier
                    .clickable { onAgreementClick() }
                    .padding(vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Made with love",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp
                ),
                color = textSecondary.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun AboutCard(
    isDark: Boolean,
    cardColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(cardColor, shape = RoundedCornerShape(16.dp))
            .padding(20.dp)
    ) {
        content()
    }
}

@Composable
fun FeatureItem(title: String, description: String, textPrimary: Color, textSecondary: Color) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp
            ),
            color = textPrimary
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.sp
            ),
            color = textSecondary
        )
    }
}
