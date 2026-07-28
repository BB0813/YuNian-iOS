package com.lianyu.ai.feature.wechat.data

import com.lianyu.ai.common.StickerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * S7：表情物化纯逻辑（无 Android Runtime）。
 */
class WeChatStickerMaterializerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun materialize_reusesExistingLocalFile() {
        val src = tmp.newFile("sticker_src_test.png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }
        val sticker = StickerInfo(
            name = "test_sticker",
            path = src.absolutePath,
            description = "测试表情",
            fileName = "sticker_src_test.png",
        )
        val material = WeChatStickerMaterializer.materialize(
            sticker = sticker,
            cacheDir = tmp.newFolder("cache"),
            loadBytes = { error("should not load when local file exists") },
        )
        assertNotNull(material)
        assertEquals(src.absolutePath, material!!.localPath)
        assertEquals("sticker_src_test.png", material.fileName)
        assertEquals("测试表情", material.description)
    }

    @Test
    fun materialize_writesCacheWhenBytesProvided() {
        val cacheDir = tmp.newFolder("cache")
        val sticker = StickerInfo(
            name = "asset_sticker",
            path = "asset://stickers/happy.png",
            description = "开心",
            fileName = "happy.png",
        )
        val payload = byteArrayOf(9, 8, 7, 6)
        val material = WeChatStickerMaterializer.materialize(
            sticker = sticker,
            cacheDir = cacheDir,
            loadBytes = { payload },
        )
        assertNotNull(material)
        val out = File(material!!.localPath)
        assertTrue(out.exists())
        assertTrue(out.parentFile!!.absolutePath.startsWith(cacheDir.absolutePath))
        assertEquals(payload.toList(), out.readBytes().toList())
        assertEquals("happy.png", material.fileName)
        assertEquals("开心", material.description)
    }

    @Test
    fun materialize_missingBytes_returnsNull() {
        val sticker = StickerInfo(
            name = "missing",
            path = "asset://stickers/missing.png",
            fileName = "missing.png",
        )
        val material = WeChatStickerMaterializer.materialize(
            sticker = sticker,
            cacheDir = tmp.newFolder("cache"),
            loadBytes = { null },
        )
        assertEquals(null, material)
    }
}
