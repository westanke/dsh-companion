package io.github.westanke.dshcompanion.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * [ImageCodec] 的 Android 实现（`BitmapFactory` + `Bitmap.compress`）。
 *
 * 这一层刻意**只做**「解一张图 / 编一张图」两件事，所有取舍（质量阶梯、缩几轮、
 * 已经够小的图要不要压）都在 [ImageCompression] 的纯函数里，那些才有单测。
 * 原因见 `ImageCompression.kt` 文件头：本模块没有 Robolectric，`BitmapFactory`
 * 在 JVM 单测里一调就抛 "not mocked"。
 *
 * 降采样沿用 `ui/chat/ChatAttachments.kt` 里 `decodeThumbnail` 的老办法：
 * 先用 `inJustDecodeBounds` 只读尺寸，再按 2 的幂设 `inSampleSize`，
 * 避免把一张 4000×3000 的原图整个读进内存。
 */
internal class AndroidImageCodec : ImageCodec {

    override fun bounds(bytes: ByteArray): ImageSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ImageSize(options.outWidth, options.outHeight)
        }.getOrNull()?.takeIf { it.isValid }
    }

    override fun encodeJpeg(bytes: ByteArray, longEdge: Int, quality: Int): ByteArray? = runCatching {
        val bounds = bounds(bytes) ?: return null
        val sample = sampleSizeFor(bounds, longEdge)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null
        val scaled = scaleToLongEdge(decoded, longEdge)
        try {
            ByteArrayOutputStream().use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)) return null
                out.toByteArray()
            }
        } finally {
            // 缩放产生了新位图就回收它；没缩放（同一个实例）交给 GC，避免误回收调用方还需要的对象。
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }.getOrNull()

    /** 先按 2 的幂粗降采样，减少解码占用的内存。 */
    private fun sampleSizeFor(bounds: ImageSize, targetLongEdge: Int): Int {
        var sample = 1
        while (bounds.longEdge / (sample * 2) >= targetLongEdge) sample *= 2
        return sample
    }

    /** 把解码后的位图等比缩到长边 [longEdge]；已经够小就原样返回（不放大）。 */
    private fun scaleToLongEdge(source: Bitmap, longEdge: Int): Bitmap {
        val current = maxOf(source.width, source.height)
        if (current <= longEdge) return source
        val ratio = longEdge.toDouble() / current
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
}
