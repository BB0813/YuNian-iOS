package com.lianyu.ai.uicommon.picker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lianyu.ai.uicommon.picker.model.AlbumInfo

/**
 * 文件夹列表 BottomSheet。
 *
 * 展示所有相册文件夹（包括「全部照片」），点击切换当前相册。
 * 每个相册显示封面缩略图、名称和图片数量。
 */
@Composable
internal fun AlbumListSheet(
    viewModel: PickerViewModel,
    onAlbumSelected: (Long, String) -> Unit,
    onDismiss: () -> Unit
) {
    val pickerState by viewModel.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---------- 顶部栏 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(48.dp))

                Text(
                    "选择相册",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, "关闭", tint = Color.White)
                }
            }

            // ---------- 加载中 ----------
            if (pickerState.isAlbumsLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF4FC3F7))
                }
            }

            // ---------- 错误 ----------
            if (pickerState.error != null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(pickerState.error!!, color = Color(0xFFFF6E6E))
                }
            }

            // ---------- 相册列表 ----------
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(pickerState.albums) { album ->
                    AlbumRow(
                        album = album,
                        isSelected = album.bucketId == pickerState.currentBucketId,
                        onClick = { onAlbumSelected(album.bucketId, album.displayName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(
    album: AlbumInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面缩略图
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF333333)),
            contentAlignment = Alignment.Center
        ) {
            if (album.coverUri != null) {
                AsyncImage(
                    model = album.coverUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                album.displayName,
                color = if (isSelected) Color(0xFF4FC3F7) else Color.White,
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${album.count} 张",
                color = Color(0xFF999999),
                fontSize = 12.sp
            )
        }

        if (isSelected) {
            Text("✓", color = Color(0xFF4FC3F7), fontSize = 18.sp)
        }
    }
}
