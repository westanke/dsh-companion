package io.github.westanke.dshcompanion.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * 把内核不收的图片格式（HEIC / HEIF / AVIF / BMP…）就地转成内核收的格式之一（issue #17）。
 *
 * ## 为什么要在客户端转
 *
 * 内核对图片 MIME 的白名单是 `Object.freeze` 的死字面量（见
 * [ChatContentParts.SUPPORTED_IMAGE_MEDIA_TYPES]），只收 png/jpeg/webp/gif，没有配置入口。
 * 而用户手里最常见的手机原图恰恰是 HEIC（华为/荣耀「高效格式」、iPhone 默认）。
 * 让用户「自己先转成 JPG」是不体面的 —— 他选图时根本不知道内核收什么，而且报错发生在
 * **点发送之后**，输入框的内容已经白填了。
 *
 * ## 为什么不做成压缩 / 缩图
 *
 * 这与 #15 的裁定直接冲突：#15 明确取消了「为了塞进 WS 帧而无谓重编码」。这里的转码
 * **不是**为了减小体积，而是把「内核根本不接受的类型」变成「接受的类型」——
 * 是格式转换，不是降质压缩。所以：
 * - 有透明通道 → PNG（无损，保住 alpha，HEIC 也常带透明）；
 * - 没有透明通道 → JPEG 质量 [JPEG_QUALITY]，这是 HEIC/AVIF 这种有损原图的无损级重编码，
 *   视觉上不会比原图更糊；
 * - **不主动缩尺寸**。只有当像素数大到会吃穿内存时才降采样（[MAX_DECODE_PIXELS]），
 *   那是防 OOM，不是省体积。
 *
 * ## 可测性
 *
 * 本对象一半是 Android API（`BitmapFactory` / `Bitmap.compress`），在 JVM 单测里一碰就抛
 * "not mocked"（本模块没有 Robolectric，理由见已删除的 `ImageCompression.kt` 文件头）。
 * 所以**所有取舍都抽成了纯函数**：[targetMediaType] 决定编成什么、[sampleSizeFor] 决定降几档、
 * [Result] 描述三种结局。这三样有单测钉住，Android 那半层只剩「照做」。
 */
internal object ImageTranscoder {

    /**
     * 解码后允许的最大像素数。
     *
     * 4096×4096 ≈ 16 M 像素，ARGB_8888 下约 64 MB —— 已经是低端机上危险的量级，
     * 但还留得住一张 12 MP 的手机原图（4000×3000）。超过这个数才降采样。
     */
    private const val MAX_DECODE_PIXELS: Long = 4096L * 4096L

    /**
     * 转成 JPEG 时用的质量。
     *
     * 90 是常用「视觉无损」线：再高体积涨得快而肉眼分不出，再低在暗部与渐变色上会出色带。
     * 注意这里的输入通常已经是**有损编码过的**原图（HEIC 质量约 90+），
     * 所以这是一次「再压一遍」，90 能保证二次压缩的损失看不出来。
     */
    const val JPEG_QUALITY: Int = 90

    /** 转码结局。刻意不抛异常：三种情况都要在 UI 上给人话，而不是让协程崩掉。 */
    sealed interface Result {
        /** 转成功了。[mediaType] 保证在 [ChatContentParts.SUPPORTED_IMAGE_MEDIA_TYPES] 里。 */
        class Success(val bytes: ByteArray, val mediaType: String) : Result

        /** 解不开：文件损坏、伪装扩展名、或这台设备缺这个格式的解码器（老机型缺 HEIF 是常态）。 */
        object Undecodable : Result

        /** 转出来了但超过上限 —— 理论上少见（JPEG 90 比 HEIC 大），仍要如实告诉用户。 */
        class TooLarge(val sizeBytes: Long) : Result
    }

    /**
     * 有透明通道就编 PNG，否则编 JPEG。
     *
     * 为什么不一律编 PNG：PNG 对照片是无损但体积能到原图十倍，一张 8 MiB 的 HEIC 会变成
     * 几十 MB，直接撞上限。照片类内容几乎都没有 alpha，JPEG 是正确选择。
     * 反过来，带 alpha 的图编成 JPEG 会把透明区变成黑块 —— 所以不能反过来一律 JPEG。
     */
    fun targetMediaType(hasAlpha: Boolean): String = if (hasAlpha) "image/png" else "image/jpeg"

    /**
     * 按像素数算 `inSampleSize`（2 的幂，`BitmapFactory` 的约定）。
     *
     * 只为了防 OOM，不为了缩图：像素数在 [MAX_DECODE_PIXELS] 以内时返回 1，
     * 也就是**原分辨率解码**。超过时逐次翻倍直到落回预算内。
     */
    fun sampleSizeFor(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width.toLong() / sample * (height.toLong() / sample) > MAX_DECODE_PIXELS) {
            sample *= 2
        }
        return sample
    }

    /**
     * 转码。`limitBytes` 是转码结果的上限（与源图上限同一个口径）。
     *
     * 先只读尺寸（`inJustDecodeBounds`）再决定降采样档位，避免把一张 4000×3000 的原图
     * 整个读进内存 —— 这个套路沿用 `ui/chat/ChatAttachments.kt` 的 `decodeThumbnail`。
     */
    fun transcode(bytes: ByteArray, limitBytes: Long): Result {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions) }
        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight
        if (width <= 0 || height <= 0) return Result.Undecodable

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(width, height)
        }
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        }.getOrNull() ?: return Result.Undecodable

        // 解出来的位图必须回收：一次转码就是一张全尺寸 ARGB，不回收会在连续选图时堆到 OOM。
        return try {
            val mediaType = targetMediaType(decoded.hasAlpha())
            val format = if (mediaType == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val encoded = runCatching {
                ByteArrayOutputStream().use { out ->
                    if (!decoded.compress(format, JPEG_QUALITY, out)) null else out.toByteArray()
                }
            }.getOrNull() ?: return Result.Undecodable

            if (encoded.size.toLong() > limitBytes) Result.TooLarge(encoded.size.toLong())
            else Result.Success(encoded, mediaType)
        } finally {
            decoded.recycle()
        }
    }
}
