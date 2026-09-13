package com.yunian.ai.feature.profile
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.security.MessageDigest

@Composable
fun AuthScreen(
    onLoginSuccess: () -> Unit,
    viewModel: AuthViewModel = viewModel()
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isLoading = uiState.isLoading
    val errorMessage = uiState.errorMessage

    val isEmailValid = email.matches(Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))
    val canSubmit = isEmailValid && password.length >= 6

    LaunchedEffect(uiState.loginSucceeded) {
        if (uiState.loginSucceeded) {
            viewModel.consumeLoginSuccess()
            onLoginSuccess()
        }
    }

    fun handleLogin() {
        if (!canSubmit || isLoading) return
        viewModel.login(email, password)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        AppTheme.colors.surface,
                        AppTheme.colors.background,
                        AppTheme.colors.surface
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = stringResource(R.string.login_title),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                ),
                color = AppTheme.colors.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.login_subtitle),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp
                ),
                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(40.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(20.dp),
                        surfaceColor = AppTheme.colors.surfaceVariant
                    )
                    .padding(24.dp)
            ) {
                Column {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; viewModel.clearError() },
                        label = { Text(stringResource(R.string.email_label)) },
                        placeholder = { Text(stringResource(R.string.email_placeholder)) },
                        leadingIcon = {
                            Icon(
                                imageVector = AppIcons.Mail,
                                contentDescription = null,
                                tint = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.colors.primary.copy(alpha = 0.8f),
                            unfocusedBorderColor = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.3f),
                            focusedLabelColor = AppTheme.colors.primary.copy(alpha = 0.8f),
                            unfocusedLabelColor = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.6f),
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        isError = errorMessage != null && !isEmailValid
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; viewModel.clearError() },
                        label = { Text(stringResource(R.string.password_label)) },
                        placeholder = { Text(stringResource(R.string.password_placeholder)) },
                        leadingIcon = {
                            Icon(
                                imageVector = AppIcons.Lock,
                                contentDescription = null,
                                tint = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) AppIcons.Eye else AppIcons.EyeOff,
                                    contentDescription = null,
                                    tint = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.colors.primary.copy(alpha = 0.8f),
                            unfocusedBorderColor = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.3f),
                            focusedLabelColor = AppTheme.colors.primary.copy(alpha = 0.8f),
                            unfocusedLabelColor = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.6f),
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        isError = errorMessage != null && password.length < 6
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = errorMessage!!,
                            color = AppTheme.colors.error,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { handleLogin() },
                        enabled = canSubmit && !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppTheme.colors.primary.copy(alpha = 0.9f),
                            disabledContainerColor = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.2f)
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = AppTheme.colors.staticWhite,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.login_button),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

data class UserInfo(
    val id: Int = 0,
    val email: String = "",
    val nickname: String? = null,
    val avatar: String? = null
)

data class AuthResult(
    val success: Boolean,
    val token: String = "",
    val refreshToken: String = "",
    val user: UserInfo? = null,
    val message: String = ""
)

fun hashPassword(password: String, salt: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val salted = password + salt
    val hashBytes = digest.digest(salted.toByteArray(Charsets.UTF_8))
    return hashBytes.joinToString("") { "%02x".format(it) }
}

suspend fun loginUser(email: String, password: String): AuthResult {
    val salt = getSaltForEmail(email)
    val passwordHash = hashPassword(password, salt)

    if (BuildConfig.DEBUG) {
        if (email == "666@qq.com" && password == "123456") {
            return AuthResult(
                success = true,
                token = "test_token_666",
                refreshToken = "test_refresh_666",
                user = UserInfo(id = 666, email = email, nickname = "测试用户", avatar = null),
                message = ""
            )
        }
        return AuthResult(success = true, token = "mock_token", message = "")
    }

    return AuthResult(success = false, message = "请使用服务器端登录")
}

suspend fun getSaltForEmail(email: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    // ⚠️ 此盐参与老账号凭据派生（password 哈希），必须永久保持 "com.lianyu.ai:$email:auth_salt_v2"
    //    的历史前缀，禁止随品牌改名（恋语/LianYu → 予念/Yunian）而修改，否则老用户将无法登录。
    val input = "com.lianyu.ai:$email:auth_salt_v2"
    val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
    return hashBytes.joinToString("") { "%02x".format(it) }
}
