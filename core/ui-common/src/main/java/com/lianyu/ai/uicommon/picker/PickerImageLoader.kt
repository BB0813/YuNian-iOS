package com.lianyu.ai.uicommon.picker

import android.content.Context
import android.graphics.Bitmap
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * 图片选择器专用 ImageLoader 单例。
 *
 * 性能要点：
 * - RGB_565：放弃透明通道，内存占用减半（选图场景不需要 alpha）
 * - 关闭 crossfade：滑动时瞬间切换，无淡入动画开销
 * - 内存缓存 25%：大幅提升回滚命中率
 */
object PickerImageLoader {

    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: build(context).also { instance = it }
        }
    }

    private fun build(context: Context): ImageLoader {
        return ImageLoader.Builder(context.applicationContext)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .crossfade(false)
            .memoryCache {
                MemoryCache.Builder(context.applicationContext)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("picker_thumbnails"))
                    .maxSizeBytes(50L * 1024 * 1024) // 50MB
                    .build()
            }
            .build()
    }
}
