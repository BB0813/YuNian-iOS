package com.lianyu.ai.feature.profile

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

// ============================================================================
// 原子化相册权限申请中间件
//
// 职责:
//   1. 根据 SDK 版本自动匹配正确权限 (≥33: READ_MEDIA_IMAGES, <33: READ_EXTERNAL_STORAGE)
//   2. 先检查权限 → 未授予则申请 → 授予后打开系统图片选择器
//   3. 权限被拒后展示引导对话框（引导用户去系统设置开启）
//   4. 暴露 `pickImage()` 作为唯一的调用入口，调用方无需关心权限细节
// ============================================================================

/**
 * 使用示例:
 * ```
 * val galleryLauncher = rememberGalleryPermissionLauncher { uri -> ... }
 * galleryLauncher.pickImage()
 * ```
 *
 * @param onImagePicked 图片选择成功后的回调
 * @return { pickImage: () -> Unit, showPermissionDialog: Boolean } 调用 pickImage() 即可发起完整流程
 */
@Composable
fun rememberGalleryPermissionLauncher(
    onImagePicked: (Uri) -> Unit
): GalleryPermissionHandle {
    val context = LocalContext.current

    // ---------- 权限名选择 ----------
    val storagePermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    // ---------- 状态 ----------
    var showPermissionGuideDialog by remember { mutableStateOf(false) }
    val hasPermission = remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, storagePermission) == PackageManager.PERMISSION_GRANTED
    ) }

    // ---------- 图片选择器 ----------
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let(onImagePicked)
    }

    // ---------- 权限请求器 ----------
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            hasPermission.value = true
            imagePickerLauncher.launch("image/*")
        } else {
            // 用户拒绝了 → 显示引导对话框
            showPermissionGuideDialog = true
        }
    }

    // ---------- 对外句柄 ----------
    val handle = remember {
        GalleryPermissionHandle(
            pickImage = {
                when {
                    hasPermission.value -> imagePickerLauncher.launch("image/*")
                    else -> permissionLauncher.launch(storagePermission)
                }
            }
        )
    }

    // ---------- 权限引导对话框 ----------
    if (showPermissionGuideDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionGuideDialog = false },
            title = {
                Text(
                    stringResource(R.string.permission_storage_title),
                    fontWeight = FontWeight.Medium
                )
            },
            text = {
                Text(
                    stringResource(R.string.permission_storage_rationale),
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionGuideDialog = false
                    // 引导用户去系统设置
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }) {
                    Text(stringResource(R.string.permission_go_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionGuideDialog = false }) {
                    Text(stringResource(R.string.profile_cancel))
                }
            }
        )
    }

    return handle
}

/**
 * 权限中间件对外暴露的调用句柄
 */
class GalleryPermissionHandle(
    /** 调用此函数发起完整的"检查权限→申请权限→打开相册"流程 */
    val pickImage: () -> Unit
)
