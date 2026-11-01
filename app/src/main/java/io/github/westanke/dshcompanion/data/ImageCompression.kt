package io.github.westanke.dshcompanion.data

/**
 * 发图前的**压缩策略**（issue #12，2026-11-01）。
 *
 * ## 为什么需要它
 *
 * 桥的 `/ws/mobile` 单帧上限是 **1 MiB**（`dsh-remote-bridge` `server.ts` 的
 * `DEFAULT_MAX_PAYLOAD_BYTES`），而 `ws` 库在**读到帧头**时就判长度：
 * 超了直接 `close(1009)`，桥的 `message` 处理器**根本不会被调用**。
 *
 * 客户端这边 [ChatContentParts.MAX_SOURCE_IMAGE_BYTES] 却是 8 MiB（那是按 REST 的
 * 300 MiB 请求体定的）。一张手机原图 2–5 MiB，base64 后 2.7–6.7 MiB，**必然**超 1 MiB。
 * 于是发原图必定失败 —— 而失败表现还不是「太大」，是等满 15 秒的假超时（见
 * `WsRequestRouter` 的断线缺口）。2026-11-01 真机截图：`280827.jpg` →
 * 「WS 请求超时（15000ms）：message（期望应答 sent）」。
 *
 * ## 策略
 *
 * 长边夹到 [MAX_IMAGE_LONG_EDGE]、JPEG 质量从 [IMAGE_START_QUALITY] 起按
 * [IMAGE_QUALITY_STEP] 往下试，直到编码结果 ≤ [MAX_ENCODED_IMAGE_BYTES]；
 * 一轮质量阶梯打完还超标就把长边乘 [IMAGE_ROUND_SHRINK] 再打一轮，最多
 * [IMAGE_MAX_ROUNDS] 轮。全都超标就返回**最小的那个**（至少比原图强），由调用方决定报错。
 *
 * 700 KiB 这个数字不是拍的：base64 膨胀 4/3 → 约 933 KiB，加上帧的 JSON 外壳与
 * 其它 part，仍稳稳落在 1 MiB 以下，留了约 90 KiB 余量。
 *
 * ## 已经在阈值内的图**一个字节都不动**
 *
 * 这是 #11 的不回归条件：287.8 KiB 的 PNG 截图原本能发，压一遍只会掉画质、还会把
 * mediaType 从 `image/png` 改成 `image/jpeg`。所以 [needsImageCompression] 为 false 时
 * 直接返回**同一个 ByteArray 实例**（单测用 `assertSame` 钉住）。
 *
 * ## 为什么把编解码抽成 [ImageCodec]
 *
 * `BitmapFactory` / `Bitmap.compress` 是 Android API，在没有 Robolectric 的 JVM 单测里
 * 一调就抛 "not mocked"。而这段逻辑真正的坑（质量阶梯走完没、轮数会不会无限、已经够小的
 * 图有没有被白压一遍、压缩后 mediaType 与文件名对不对得上）**全在策略层**，与
 * 「怎么解码一张 JPEG」无关。抽出来后策略层可以在纯 JVM 里被钉死，Android 实现
 * （[AndroidImageCodec]）退化成一层薄壳。
 */

/** 编码后的图片体积上限：700 KiB（理由见文件头「策略」一节）。 */
const val MAX_ENCODED_IMAGE_BYTES: Long = 700L * 1024

/** 编码后的长边上限（像素）。 */
const val MAX_IMAGE_LONG_EDGE: Int = 1600

/** 起始 JPEG 质量。 */
const val IMAGE_START_QUALITY: Int = 85

/** 质量阶梯的下限：再低就只剩马赛克，不如报错让用户自己处理。 */
const val IMAGE_MIN_QUALITY: Int = 45

/** 质量阶梯的步长。 */
const val IMAGE_QUALITY_STEP: Int = 10

/** 一轮质量阶梯打完仍超标时，最多再缩几轮长边。 */
const val IMAGE_MAX_ROUNDS: Int = 3

/** 每轮缩长边的系数。 */
const val IMAGE_ROUND_SHRINK: Double = 0.75

/** 编码后的媒体类型：压缩产物一律是 JPEG。 */
const val COMPRESSED_IMAGE_MEDIA_TYPE: String = "image/jpeg"

/** 一张图的像素尺寸。 */
internal data class ImageSize(val width: Int, val height: Int) {

    /** 长边（宽高里大的那个）。 */
    val longEdge: Int get() = maxOf(width, height)

    /** 是否是一个能拿去编解码的有效尺寸。 */
    val isValid: Boolean get() = width > 0 && height > 0
}

/**
 * 图像编解码的最小接口。
 *
 * 生产实现是 [AndroidImageCodec]；单测注入假的，用来验证策略而不是验证 `BitmapFactory`。
 */
internal interface ImageCodec {

    /** 只读尺寸（不解全图）。解不出来（不是图片 / 已损坏）返回 null。 */
    fun bounds(bytes: ByteArray): ImageSize?

    /**
     * 把 [bytes] 缩到长边 [longEdge] 并以 [quality] 编成 JPEG。
     *
     * 失败返回 null（调用方会继续试别的质量，全失败则退回原图）。
     */
    fun encodeJpeg(bytes: ByteArray, longEdge: Int, quality: Int): ByteArray?
}

/** 质量阶梯：85 → 75 → 65 → 55 → 45。 */
internal fun imageQualityLadder(
    start: Int = IMAGE_START_QUALITY,
    min: Int = IMAGE_MIN_QUALITY,
    step: Int = IMAGE_QUALITY_STEP,
): List<Int> {
    require(step > 0) { "质量步长必须为正" }
    val ladder = mutableListOf<Int>()
    var quality = start
    while (quality >= min) {
        ladder += quality
        quality -= step
    }
    return ladder
}

/**
 * 这张图要不要压。
 *
 * **只看体积**：帧限是字节数，不是像素数。一张 4000×3000 但只有 200 KiB 的纯色 PNG
 * 完全塞得进 1 MiB 的帧，压它纯属白掉画质。
 *
 * 长边上限（[MAX_IMAGE_LONG_EDGE]）不是触发条件，而是**压缩时**的目标尺寸 ——
 * 这个区别很要紧：手机截图普遍是 1080×2400（长边 2400 > 1600），但它们通常只有
 * 300 KiB 左右，本来就能发。若把长边也当触发条件，这类截图会被重新编码成 JPEG、
 * 分辨率降到 1600，截图里的文字直接糊掉 —— 那是在修 #12 的同时破坏 #11。
 */
internal fun needsImageCompression(
    sizeBytes: Long,
    limit: Long = MAX_ENCODED_IMAGE_BYTES,
): Boolean = sizeBytes > limit

/**
 * 把长边夹到 [maxLongEdge]，**不放大**。
 *
 * 已经比上限小的图保持原长边：放大既不会让内容更清楚，只会让体积变大。
 */
internal fun fitLongEdge(size: ImageSize, maxLongEdge: Int = MAX_IMAGE_LONG_EDGE): Int =
    minOf(size.longEdge, maxLongEdge).coerceAtLeast(1)

/**
 * 压缩主流程。
 *
 * 返回值语义：
 * - **同一个实例**（`===`）→ 不需要压，调用方原样发；
 * - 新数组 → 压缩产物，调用方按 [COMPRESSED_IMAGE_MEDIA_TYPE] 发送；
 * - null → 连尺寸都读不出来，**不是图片**（调用方该报错，而不是硬发一个必然被拒的帧）。
 */
internal fun compressImageForWire(
    codec: ImageCodec,
    bytes: ByteArray,
    limit: Long = MAX_ENCODED_IMAGE_BYTES,
    maxLongEdge: Int = MAX_IMAGE_LONG_EDGE,
): ByteArray? {
    val bounds = codec.bounds(bytes) ?: return null
    if (!bounds.isValid) return null
    if (!needsImageCompression(bytes.size.toLong(), limit)) return bytes

    var edge = fitLongEdge(bounds, maxLongEdge)
    var smallest: ByteArray? = null
    var round = 0
    while (round < IMAGE_MAX_ROUNDS) {
        for (quality in imageQualityLadder()) {
            val encoded = codec.encodeJpeg(bytes, edge, quality) ?: continue
            if (smallest == null || encoded.size < smallest.size) smallest = encoded
            if (encoded.size.toLong() <= limit) return encoded
        }
        edge = (edge * IMAGE_ROUND_SHRINK).toInt().coerceAtLeast(1)
        round += 1
    }
    // 全都超标：给最小的那个。调用方按 [MAX_ENCODED_IMAGE_BYTES] 复核后决定报错，
    // 这里不替它做决定 —— 「压完仍超标」和「压根不是图片」是两种不同的用户提示。
    return smallest ?: bytes
}

/** 压缩后的待发图片：字节、媒体类型、文件名三者必须自洽。 */
internal class PreparedImage(
    val bytes: ByteArray,
    val mediaType: String,
    val name: String,
)

/**
 * 把选中的图片整理成可以上线缆的形态。
 *
 * 三条规则：
 * 1. 不需要压（体积与长边都在阈值内）→ **原样返回**，mediaType 与文件名都不动；
 * 2. 压过了 → mediaType 改成 `image/jpeg`，**文件名扩展名也要跟着改成 `.jpg`**。
 *    只改 mediaType 不改名字会让「附件卡片显示 .png」和「实际内容是 JPEG」对不上，
 *    排查时非常误导；
 * 3. 读不出尺寸 → 返回 null（不是图片）。
 */
internal fun prepareImageForWire(
    codec: ImageCodec,
    bytes: ByteArray,
    mediaType: String,
    name: String,
    limit: Long = MAX_ENCODED_IMAGE_BYTES,
    maxLongEdge: Int = MAX_IMAGE_LONG_EDGE,
): PreparedImage? {
    val compressed = compressImageForWire(codec, bytes, limit, maxLongEdge) ?: return null
    if (compressed === bytes) return PreparedImage(bytes, mediaType, name)
    return PreparedImage(compressed, COMPRESSED_IMAGE_MEDIA_TYPE, jpegName(name))
}

/** 把文件名的扩展名换成 `.jpg`（没有扩展名就补一个）。 */
internal fun jpegName(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "image.jpg"
    val dot = trimmed.lastIndexOf('.')
    // 隐藏文件（`.nomedia`）没有扩展名可言，补一个而不是把点当分隔符。
    if (dot <= 0 || dot == trimmed.length - 1) return "$trimmed.jpg"
    return trimmed.substring(0, dot) + ".jpg"
}
