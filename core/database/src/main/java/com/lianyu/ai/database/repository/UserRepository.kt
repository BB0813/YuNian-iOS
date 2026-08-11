package com.lianyu.ai.database.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.lianyu.ai.common.CompanionRole
import com.lianyu.ai.common.ImageUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class UserRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    private val _userName = MutableStateFlow(prefs.getString("user_name", "我") ?: "我")
    val userName: StateFlow<String> = _userName

    private val _userAvatar = MutableStateFlow(prefs.getString("user_avatar", null))
    val userAvatar: StateFlow<String?> = _userAvatar

    private val _selectedRole = MutableStateFlow(
        CompanionRole.fromName(prefs.getString("selected_role", null))
    )
    val selectedRole: StateFlow<CompanionRole> = _selectedRole

    private val _userStatus = MutableStateFlow(prefs.getString("user_status", "") ?: "")
    val userStatus: StateFlow<String> = _userStatus

    private val _userSignature = MutableStateFlow(prefs.getString("user_signature", "") ?: "")
    val userSignature: StateFlow<String> = _userSignature

    /**
     * 用户性别，稳定键：`male` / `female`，空字符串表示未设置。
     * UI 层负责映射到本地化文案，避免把展示文案写入偏好。
     */
    private val _userGender = MutableStateFlow(
        normalizeGender(prefs.getString("user_gender", "") ?: "")
    )
    val userGender: StateFlow<String> = _userGender

    /** 用户地区（自由文本，如「广东·深圳」），空字符串表示未设置。 */
    private val _userRegion = MutableStateFlow(prefs.getString("user_region", "") ?: "")
    val userRegion: StateFlow<String> = _userRegion

    fun updateUserName(name: String) {
        prefs.edit { putString("user_name", name) }
        _userName.value = name
    }

    fun updateUserAvatar(avatarUri: String?) {
        if (avatarUri != null) {
            prefs.edit { putString("user_avatar", avatarUri) }
        } else {
            prefs.edit { remove("user_avatar") }
        }
        _userAvatar.value = avatarUri
    }

    /**
     * 修复历史头像脏数据：早期版本把裁剪头像写入 cacheDir，系统可能在存储不足时清空，
     * 导致存储的头像路径悬空、头像显示为白底。
     * - 文件仍在（cacheDir 内）→ 迁入 filesDir/avatars 持久目录并更新引用；
     * - 文件已丢失 → 清除引用，UI 回退为首字母兜底。
     * 在 Application 启动时调用一次。
     */
    suspend fun repairUserAvatar(context: Context) {
        val current = prefs.getString("user_avatar", null) ?: return
        val file = runCatching { File(current) }.getOrNull() ?: return
        // 仅处理本地绝对路径；content:// / http 等由 Coil 按需加载，无需迁移
        if (!file.isAbsolute) return
        if (file.absolutePath.startsWith(context.cacheDir.absolutePath)) {
            val migrated = ImageUtils.saveUriToInternalStorage(context, current)
            if (migrated != null) {
                prefs.edit { putString("user_avatar", migrated) }
                _userAvatar.value = migrated
            } else {
                prefs.edit { remove("user_avatar") }
                _userAvatar.value = null
            }
        } else if (!file.exists()) {
            // 持久目录内文件也被删（如备份还原丢失）→ 回退首字母
            prefs.edit { remove("user_avatar") }
            _userAvatar.value = null
        }
    }

    fun updateSelectedRole(role: CompanionRole) {
        prefs.edit { putString("selected_role", role.name) }
        _selectedRole.value = role
    }

    fun updateUserStatus(status: String) {
        prefs.edit { putString("user_status", status) }
        _userStatus.value = status
    }

    fun updateUserSignature(signature: String) {
        prefs.edit { putString("user_signature", signature) }
        _userSignature.value = signature
    }

    fun updateUserGender(gender: String) {
        val normalized = normalizeGender(gender)
        prefs.edit {
            if (normalized.isEmpty()) {
                remove("user_gender")
            } else {
                putString("user_gender", normalized)
            }
        }
        _userGender.value = normalized
    }

    fun updateUserRegion(region: String) {
        val value = region.trim()
        prefs.edit {
            if (value.isEmpty()) {
                remove("user_region")
            } else {
                putString("user_region", value)
            }
        }
        _userRegion.value = value
    }

    private fun normalizeGender(raw: String): String {
        return when (raw.trim().lowercase()) {
            "male", "m", "男", "man", "boy" -> "male"
            "female", "f", "女", "woman", "girl" -> "female"
            else -> ""
        }
    }
}
