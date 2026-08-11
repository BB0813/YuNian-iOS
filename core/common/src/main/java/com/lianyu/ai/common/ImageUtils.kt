package com.lianyu.ai.common

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object ImageUtils {

    /**
     * 把头像源（content:// URI 或本地文件路径）落盘到应用持久目录 filesDir/avatars。
     *
     * - content:// → 读取并复制；
     * - 已在 filesDir 持久目录内的文件 → 直接复用，避免重复拷贝；
     * - 缓存目录（cacheDir）等易失位置的文件 → 复制到 filesDir/avatars。
     *   否则路径存进偏好后，一旦系统清缓存，头像文件丢失 → Coil 加载失败 → 头像变白。
     */
    suspend fun saveUriToInternalStorage(context: Context, uri: String): String? = withContext(Dispatchers.IO) {
        try {
            val existing = if (!uri.startsWith("content://") && File(uri).exists()) File(uri) else null
            // 已在持久目录内 → 直接复用，避免重复拷贝
            if (existing != null && existing.absolutePath.startsWith(context.filesDir.absolutePath)) {
                return@withContext uri
            }

            val inputUri = Uri.parse(uri)
            val fileName = "avatar_${UUID.randomUUID()}.jpg"
            val avatarsDir = File(context.filesDir, "avatars")
            if (!avatarsDir.exists()) {
                avatarsDir.mkdirs()
            }
            val file = File(avatarsDir, fileName)

            if (existing != null) {
                // 本地文件（如裁剪后落在 cacheDir）→ 复制到持久目录
                existing.inputStream().use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                context.contentResolver.openInputStream(inputUri)?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: run {
                    return@withContext null
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun deleteAvatarFile(path: String?) {
        path?.let {
            try {
                File(it).delete()
            } catch (_: Exception) { }
        }
    }
}
