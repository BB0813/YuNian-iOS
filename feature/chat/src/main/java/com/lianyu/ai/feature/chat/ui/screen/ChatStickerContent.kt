package com.lianyu.ai.feature.chat.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.common.StickerManager
import com.lianyu.ai.feature.chat.ui.theme.ChatTheme

@Composable
fun StickerContentBubble(
    stickerName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(stickerName) {
        val manager = StickerManager.getInstance(context)
        var sticker = manager.findStickerByDescriptionExact(stickerName)
        if (sticker == null && !stickerName.endsWith(".png")) {
            sticker = manager.findStickerByDescriptionExact("$stickerName.png")
        }
        if (sticker == null) {
            sticker = manager.findStickerByDescription(stickerName)
        }
        if (sticker != null) {
            bitmap = manager.loadStickerBitmap(sticker.path)
        } else {
            val importedDir = java.io.File(context.filesDir, "stickers/imported")
            val possibleFiles = listOf(
                "$stickerName.png", "$stickerName.jpg", "$stickerName.jpeg",
                "$stickerName.gif", "$stickerName.webp",
                "sticker_$stickerName.png"
            )
            for (fileName in possibleFiles) {
                val file = java.io.File(importedDir, fileName)
                if (file.exists()) {
                    bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    break
                }
            }
            if (bitmap == null) {
                try {
                    context.assets.list("stickers")?.filter { it.equals("$stickerName.png", ignoreCase = true) || it.equals(stickerName, ignoreCase = true) }?.firstOrNull()?.let {
                        context.assets.open("stickers/$it").use { stream ->
                            bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = stickerName,
            modifier = modifier
                .sizeIn(maxWidth = 140.dp, maxHeight = 140.dp)
                .width(120.dp)
                .height(120.dp),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = modifier.size(120.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "🎨",
                fontSize = 32.sp,
                color = ChatTheme.colors.metadata
            )
        }
    }
}