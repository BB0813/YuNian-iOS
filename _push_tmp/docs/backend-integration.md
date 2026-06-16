# 恋语 App 后端接入文档

## 概述

本文档描述了恋语 App 注册登录模块的后端接口规范。当前版本为预留接口阶段，开发者需要按照以下规范实现后端服务。

---

## 当前状态

- **测试账号**: `666@qq.com` / `123456`（客户端内置，用于开发调试）
- **Token 存储**: 登录成功后保存到 `SharedPreferences("auth_prefs")`
- **登录状态保持**: Token 持久化存储，App 启动时自动检查

---

## 接口规范

### 基础信息

| 项目 | 值 |
|------|-----|
| 协议 | HTTPS |
| 数据格式 | JSON |
| 编码 | UTF-8 |
| 请求头 | `Content-Type: application/json` |

### 1. 用户登录

**请求**

```http
POST /api/v1/auth/login
Content-Type: application/json

{
    "email": "user@example.com",
    "password_hash": "SHA256(密码 + 盐值)"
}
```

> **注意**: 密码禁止明文传输。客户端应先对密码进行 SHA-256 哈希（加盐），再发送到服务端。盐值由服务端在注册时分配或通过预请求获取。

**响应成功 (200)**

```json
{
    "success": true,
    "code": 200,
    "message": "登录成功",
    "data": {
        "token": "eyJhbGciOiJIUzI1NiIs...",
        "refresh_token": "eyJhbGciOiJIUzI1NiIs...",
        "expires_in": 604800,
        "user": {
            "id": 1,
            "email": "user@example.com",
            "nickname": "用户昵称",
            "avatar": "https://example.com/avatar.jpg",
            "created_at": "2024-01-01T00:00:00Z"
        }
    }
}
```

**响应失败 (400/401)**

```json
{
    "success": false,
    "code": 401,
    "message": "邮箱或密码错误",
    "data": null
}
```

### 2. 用户注册

**请求**

```http
POST /api/v1/auth/register
Content-Type: application/json

{
    "email": "user@example.com",
    "password_hash": "SHA256(密码 + 盐值)",
    "verify_code": "123456"
}
```

> **注意**: 密码禁止明文传输。客户端应先对密码进行 SHA-256 哈希（加盐），再发送到服务端。

**响应成功 (200)**

```json
{
    "success": true,
    "code": 200,
    "message": "注册成功",
    "data": {
        "token": "eyJhbGciOiJIUzI1NiIs...",
        "refresh_token": "eyJhbGciOiJIUzI1NiIs...",
        "expires_in": 604800,
        "user": {
            "id": 1,
            "email": "user@example.com",
            "nickname": null,
            "avatar": null,
            "created_at": "2024-01-01T00:00:00Z"
        }
    }
}
```

**响应失败 (400)**

```json
{
    "success": false,
    "code": 400,
    "message": "验证码错误或已过期",
    "data": null
}
```

### 3. 获取盐值

**请求**

```http
POST /api/v1/auth/salt
Content-Type: application/json

{
    "email": "user@example.com"
}
```

**响应成功 (200)**

```json
{
    "success": true,
    "code": 200,
    "message": "",
    "data": {
        "salt": "a1b2c3d4e5f6..."
    }
}
```

> **说明**: 注册时若邮箱不存在，返回随机盐值；登录时返回该用户注册时的盐值。

### 4. 发送验证码

**请求**

```http
POST /api/v1/auth/send-verify-code
Content-Type: application/json

{
    "email": "user@example.com",
    "type": "register"
}
```

**响应成功 (200)**

```json
{
    "success": true,
    "code": 200,
    "message": "验证码已发送",
    "data": {
        "expire_seconds": 300
    }
}
```

**响应失败 (429)**

```json
{
    "success": false,
    "code": 429,
    "message": "发送过于频繁，请60秒后重试",
    "data": null
}
```

### 5. Token 刷新

**请求**

```http
POST /api/v1/auth/refresh
Content-Type: application/json

{
    "refresh_token": "eyJhbGciOiJIUzI1NiIs..."
}
```

**响应成功 (200)**

```json
{
    "success": true,
    "code": 200,
    "message": "刷新成功",
    "data": {
        "token": "eyJhbGciOiJIUzI1NiIs...",
        "refresh_token": "eyJhbGciOiJIUzI1NiIs...",
        "expires_in": 604800
    }
}
```

### 6. 退出登录

**请求**

```http
POST /api/v1/auth/logout
Authorization: Bearer {token}
Content-Type: application/json
```

**响应成功 (200)**

```json
{
    "success": true,
    "code": 200,
    "message": "退出成功",
    "data": null
}
```

---

## 客户端代码接入位置

### 文件: `feature/profile/src/main/java/com/lianyu/ai/feature/profile/AuthScreen.kt`

需要修改以下两个函数：

#### `hashPassword()` - 密码哈希函数

```kotlin
fun hashPassword(password: String, salt: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val salted = password + salt
    val hashBytes = digest.digest(salted.toByteArray(Charsets.UTF_8))
    return hashBytes.joinToString("") { "%02x".format(it) }
}
```

#### `loginUser()` - 第 390 行

```kotlin
suspend fun loginUser(email: String, password: String): AuthResult {
    val salt = getSaltForEmail(email)
    val passwordHash = hashPassword(password, salt)

    // TODO: 替换为实际 API 调用
    // 当前为测试账号逻辑
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
```

**接入示例**（使用 Retrofit）：

```kotlin
suspend fun loginUser(email: String, password: String): AuthResult {
    return try {
        val salt = apiService.getSalt(email).data.salt
        val passwordHash = hashPassword(password, salt)
        val response = apiService.login(LoginRequest(email, passwordHash))
        if (response.success) {
            AuthResult(
                success = true,
                token = response.data.token,
                refreshToken = response.data.refresh_token,
                user = response.data.user,
                message = ""
            )
        } else {
            AuthResult(success = false, message = response.message)
        }
    } catch (e: Exception) {
        AuthResult(success = false, message = e.message ?: "网络错误")
    }
}
```

#### `registerUser()` - 第 398 行

```kotlin
suspend fun registerUser(email: String, password: String, verifyCode: String): AuthResult {
    val salt = generateSalt()
    val passwordHash = hashPassword(password, salt)

    // TODO: 替换为实际 API 调用
    return AuthResult(
        success = true,
        token = "mock_token",
        refreshToken = "mock_refresh",
        user = UserInfo(id = 1, email = email, nickname = null, avatar = null),
        message = ""
    )
}
```

**接入示例**：

```kotlin
suspend fun registerUser(email: String, password: String, verifyCode: String): AuthResult {
    return try {
        val salt = generateRandomSalt()
        val passwordHash = sha256(password + salt)
        val response = apiService.register(RegisterRequest(email, passwordHash, verifyCode))
        if (response.success) {
            AuthResult(
                success = true,
                token = response.data.token,
                refreshToken = response.data.refresh_token,
                user = response.data.user,
                message = ""
            )
        } else {
            AuthResult(success = false, message = response.message)
        }
    } catch (e: Exception) {
        AuthResult(success = false, message = e.message ?: "网络错误")
    }
}
```

#### `sendVerifyCode()` - 需要新增

当前客户端的验证码倒计时逻辑在 UI 层，需要添加实际发送请求：

```kotlin
suspend fun sendVerifyCode(email: String): Boolean {
    return try {
        val response = apiService.sendVerifyCode(SendVerifyCodeRequest(email, "register"))
        response.success
    } catch (e: Exception) {
        false
    }
}
```

---

## 数据模型定义

### 请求模型

```kotlin
@Serializable
data class LoginRequest(
    val email: String,
    val password_hash: String
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password_hash: String,
    val verify_code: String
)

@Serializable
data class SendVerifyCodeRequest(
    val email: String,
    val type: String // "register" | "reset_password"
)

@Serializable
data class RefreshTokenRequest(
    val refresh_token: String
)
```

### 响应模型

```kotlin
@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val code: Int,
    val message: String,
    val data: T?
)

@Serializable
data class AuthData(
    val token: String,
    val refresh_token: String,
    val expires_in: Int,
    val user: UserInfo?
)

@Serializable
data class UserInfo(
    val id: Int,
    val email: String,
    val nickname: String?,
    val avatar: String?,
    val created_at: String
)

@Serializable
data class SaltResponse(
    val salt: String
)
```

---

## 安全要求

1. **HTTPS 强制**: 所有接口必须使用 HTTPS
2. **密码传输**: 
   - 禁止明文传输密码
   - 客户端使用 SHA-256(密码 + 盐值) 哈希后传输
   - 盐值获取方式：注册时服务端分配，或登录前通过 `/api/v1/auth/salt` 获取
3. **密码存储**: 服务端使用 bcrypt/argon2 二次哈希存储
4. **Token 机制**: 
   - Access Token: 有效期 7 天
   - Refresh Token: 有效期 30 天
   - Token 格式: JWT
5. **验证码安全**:
   - 有效期 5 分钟
   - 同一邮箱 60 秒内只能发送一次
   - 失败 5 次后锁定 1 小时
6. **设备绑定**: 首次注册后需要设备绑定（后续功能）

---

## 错误码规范

| 错误码 | 含义 |
|--------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未授权/Token 过期 |
| 403 | 禁止访问 |
| 404 | 资源不存在 |
| 409 | 邮箱已注册 |
| 429 | 请求过于频繁 |
| 500 | 服务器内部错误 |

---

## 测试账号

| 邮箱 | 密码 | 用途 |
|------|------|------|
| 666@qq.com | 123456 | 开发测试（客户端内置）|

**删除方式**: 删除 `AuthScreen.kt` 中 `loginUser()` 函数内的测试账号判断逻辑即可。

---

## 接入检查清单

- [ ] 后端服务部署 HTTPS
- [ ] 实现 `/api/v1/auth/salt`
- [ ] 实现 `/api/v1/auth/login`
- [ ] 实现 `/api/v1/auth/register`
- [ ] 实现 `/api/v1/auth/send-verify-code`
- [ ] 实现 `/api/v1/auth/refresh`
- [ ] 实现 `/api/v1/auth/logout`
- [ ] 配置 JWT Token 生成与验证
- [ ] 配置密码哈希存储（bcrypt/argon2）
- [ ] 配置邮箱 SMTP 服务发送验证码
- [ ] 修改客户端 `loginUser()` 函数
- [ ] 修改客户端 `registerUser()` 函数
- [ ] 添加客户端 `sendVerifyCode()` 函数
- [ ] 删除测试账号逻辑（上线前）
