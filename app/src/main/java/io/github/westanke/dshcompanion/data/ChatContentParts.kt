package io.github.westanke.dshcompanion.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把「文本 + 待发附件」组装成 DSH 的 messages 请求体，并守住客户端的资源上限。
 *
 * 为什么这段逻辑不写在 [DshClient] 里：它是**纯逻辑**（没有网络、没有 Android），
 * 而它恰好踩点最多 —— 旧格式兼容、空内容拒绝、图片上限、base64 口径。放在一个能跑
 * JVM 单测的对象里，"发出去的东西长什么样"才可以被钉死，而不是靠手点验证。
 *
 * 序列化的两条硬规则：
 * 1. **只有纯文本才走旧格式** `{"text": "..."}`。服务端两种都吃，但旧格式向后兼容
 *    已发布的老插件，能不升级就不升级；一旦带附件就必须用 `{"content": [...]}`。
 * 2. 编码 [PromptPart] 时用 [wireJson]（`encodeDefaults = true`）。默认的 `Json` 会
 *    **省略等于默认值的字段** —— 那么 `mediaType = "image/png"` 会被悄悄丢掉，
 *    服务端收到一个没有 mediaType 的 image part。这是个安静的坑，所以这里显式打开。
 */
object ChatContentParts {
    /**
     * 单张**原图**（用户选中的那个文件）的字节上限。
     *
     * 限制的是原始字节而不是 base64 长度：base64 会膨胀约 33%，所以 8 MiB 原图
     * 在请求体里约 10.7 MiB。先在这里拦下来，用户拿到的是「先压缩再试」这种能照做的
     * 提示，而不是等一个 413。
     *
     * 注意这里只管**源文件**大小。真正上线缆的字节受两条不同约束：
     * - REST：桥的 `POST /chat/sessions/:id/messages` 请求体上限 **12 MiB**
     *   （`dsh-remote-bridge` `src/host/routes/chat.ts:223`，注释写明「12 MiB ≈ 8 MiB 原图」），
     *   8 MiB 原图正好对齐；
     * - WS：桥的 `/ws/mobile` 单帧上限 **1 MiB**（`server.ts` 的 `DEFAULT_MAX_PAYLOAD_BYTES`），
     *   超了 `ws` 库直接 `close(1009)`。
     *
     * 两条差了 12 倍，所以**按体积选通道**而不是一律压缩（issue #15）：
     * 装得进 WS 帧就走 WS（快），装不进就走 REST 保原画质（大图不再被重编码糊掉）。
     * 判据见 [fitsInWsFrame]。
     */
    const val MAX_SOURCE_IMAGE_BYTES: Long = 8L * 1024 * 1024

    /**
     * 一条消息里**所有图片的原始字节之和**的上限，用来决定走不走 WS。
     *
     * 取 500 KiB 而不是贴着 1 MiB 帧上限：base64 膨胀约 33%、加上 JSON 外壳与
     * `{"content":[...]}` 的键名开销，500 KiB 原图在线缆上约 700 KiB，对 1 MiB 还有
     * 三成余量。贴着上限算的话，一次桥侧的帧头字段调整就会让原本能发的图开始失败。
     *
     * **按整条消息求和，不是按单张图**：三张 200 KiB 的截图各自都「不到 500K」，
     * 合起来却会超帧。见 [fitsInWsFrame]。
     */
    const val WS_INLINE_IMAGE_BUDGET_BYTES: Long = 500L * 1024

    /**
     * 这条消息的图片装得进一帧 WS 吗？
     *
     * 只数 [PromptPart.ImagePart]：文本与文件引用（`receiptId`）体积都可忽略，
     * 真正会把帧撑爆的只有内联 base64。
     *
     * base64 长度换算回原始字节用 `length / 4 * 3`（含 `=` 填充时略高估，**安全的方向**：
     * 宁可多判一次「走 REST」，也不要少判一次然后被桥 `close(1009)`）。
     */
    fun fitsInWsFrame(parts: List<PromptPart>): Boolean {
        var rawBytes = 0L
        for (part in parts) {
            if (part is PromptPart.ImagePart) {
                rawBytes += part.data.length / 4 * 3
            }
            if (rawBytes > WS_INLINE_IMAGE_BUDGET_BYTES) return false
        }
        return true
    }

    /**
     * 单个**文件附件**的字节上限。
     *
     * 文件要先整段读进内存再 base64 成 JSON，没有上限时一个几百 MB 的文件会在
     * 手机上直接 OOM —— 那比「文件太大」糟糕得多。24 MiB 约对应 32 MiB 的请求体，
     * 是低端机也能扛住的量级。
     */
    const val MAX_FILE_BYTES: Long = 24L * 1024 * 1024

    /**
     * 专门用于编码的 Json 实例，**不要换成 DshClient 里那个**：
     * `encodeDefaults = false` 会省略等于默认值的字段（见类注释）；
     * `explicitNulls = false` 让 `name = null` 直接不出现，而不是发一个 `"name":null`。
     */
    private val wireJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private val partListSerializer = ListSerializer(PromptPart.serializer())

    /**
     * 构造一个内联图片 part。
     *
     * 上限是源图的 [MAX_SOURCE_IMAGE_BYTES]：图片**不再预压缩**（issue #15），大图由
     * [fitsInWsFrame] 判定后整条消息改走 REST，那边桥的请求体上限是 12 MiB，装得下。
     * 这里只守住内存边界，避免把一张几百 MB 的伪装图编成 base64。
     */
    fun imagePart(mediaType: String, bytes: ByteArray, name: String? = null): PromptPart {
        require(bytes.size.toLong() <= MAX_SOURCE_IMAGE_BYTES) { "IMAGE_TOO_LARGE" }
        return PromptPart.ImagePart(
            mediaType = mediaType,
            data = base64(bytes),
            name = name?.takeIf { it.isNotBlank() },
        )
    }

    /** 标准 base64（`Base64.getEncoder()`，带 `=` 填充，不是 URL-safe）。 */
    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /**
     * 读进内存，但**最多读 limit + 1 字节**。
     *
     * 为什么不能直接 `readBytes()`：选择器返回的 `OpenableColumns.SIZE` 可能是 -1
     * （内容提供者不肯说大小），于是一个几百 MB 的文件会在判断「超限」之前就把内存吃穿。
     * 多读的那一个字节是刻意的：它让调用方能区分
     * - 返回值长度 `<= limit` —— 文件完整读完了，放行；
     * - 返回值长度 `> limit` —— 还没读完就停手了，**必须拒绝**。
     * 没有这一字节，一个正好在缓冲边界上的超大文件会被当成"读完的完整文件"发出去。
     */
    fun readAtMost(limit: Long, input: InputStream, chunkSize: Int = 64 * 1024): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(chunkSize)
        var total = 0L
        while (total <= limit) {
            val want = minOf(chunk.size.toLong(), limit + 1 - total).toInt()
            if (want <= 0) break
            val read = input.read(chunk, 0, want)
            if (read < 0) break
            buffer.write(chunk, 0, read)
            total += read
        }
        return buffer.toByteArray()
    }

    /** `POST /chat/sessions/:id/attachments` 的请求体：`{"data":"<base64>","name":"..."}`。 */
    fun uploadPayload(bytes: ByteArray, name: String): JsonObject = buildJsonObject {
        put("data", base64(bytes))
        put("name", name)
    }

    /**
     * 组装发消息的请求体。
     *
     * - 只有单个非空 text part → **旧格式**（`text` 键），保持与老插件、老版本的兼容；
     * - 其他任何组合 → `content` 数组；
     * - 去掉空白 text part 之后什么都不剩（例如只发了一串空格）→ 抛 `EMPTY_CONTENT`，
     *   宁可本地报错，也不要发一个服务端必然拒绝的空 prompt。
     */
    fun messageBody(
        parts: List<PromptPart>,
        mode: String,
        clientTimeZone: String,
        clientRequestId: String,
    ): JsonObject {
        val content = parts.filterNot { it is PromptPart.TextPart && it.text.isBlank() }
        require(content.isNotEmpty()) { "EMPTY_CONTENT" }
        val singleText = (content.singleOrNull() as? PromptPart.TextPart)?.text
        return buildJsonObject {
            if (singleText != null) {
                put("text", singleText)
            } else {
                put("content", wireJson.encodeToJsonElement(partListSerializer, content))
            }
            put("mode", mode)
            put("clientTimeZone", clientTimeZone)
            put("clientRequestId", clientRequestId)
        }
    }

    /**
     * 内核对图片 MIME 的**唯一权威白名单**（issue #17）。
     *
     * 抄自 `@deepseek-ai/dsh-attachment-local` 的 `imageLimits.mediaTypes`
     * （`lib/index.js:1004`）。那个数组是 `Object.freeze` 的字面量，**没有配置入口** ——
     * 同一个 `imageLimits` 里 `maxImageBytes`、`maxImagesPerMessage` 等都读 `config.*`，
     * 只有媒体类型被写死。所以任何 deployment 都只收这四种，客户端必须自己对齐。
     *
     * 不在这个集合里的类型发出去必被拒（`UNSUPPORTED_IMAGE_TYPE`），
     * 且报错发生在**用户点发送之后**——附件都已经在输入框里了，所以这条校验
     * 必须在客户端、在选图那一刻做完。
     */
    val SUPPORTED_IMAGE_MEDIA_TYPES: Set<String> = setOf(
        "image/png",
        "image/jpeg",
        "image/webp",
        "image/gif",
    )

    /**
     * 内核收不收这个图片类型。大小写与前缀都按内核的口径来（它是精确字符串比较）。
     */
    fun isSupportedImageMediaType(mediaType: String): Boolean =
        mediaType.trim().lowercase() in SUPPORTED_IMAGE_MEDIA_TYPES

    /**
     * 图片的 mediaType。
     *
     * 系统选择器给的 MIME 是首选；拿不到时就按扩展名推，最后兜底 `image/png`。
     *
     * **但首选不等于照抄**（issue #17 的根因）：内核只认 [SUPPORTED_IMAGE_MEDIA_TYPES]
     * 四种，而系统给的 MIME 五花八门——`.bmp` 在 vivo 上给的是 `image/x-ms-bmp`，
     * HEIC 给 `image/heic`，苹果互传的可能给 `image/heif`。原来这里对任何
     * 以 `image/` 开头的类型一律原样透传，等于把「类型是不是内核能吃的」这件事完全交给设备。
     *
     * 现在的口径：**先归一化到内核认识的名字**，归不了就返回原值让调用方去决定
     * （转码还是拒绝），由 [isSupportedImageMediaType] 做最终裁决。
     * 把归一化与裁决分开，是因为「这张图实际是什么格式」和「内核吃不吃」是两个问题：
     * HEIC 确实是 `image/heic`，归一化只会把它变成 JPEG（转码后），而不是假装它是 PNG。
     */
    fun imageMediaType(contentType: String?, fileName: String): String {
        val declared = contentType?.trim()?.lowercase().orEmpty()
        val normalized = normalizeImageMediaType(declared)
        if (normalized != null) return normalized
        // 声明缺失或不认识时按扩展名推。**注意 `else` 兜底不再假装是 PNG**：
        // 猜错类型会让内核报一个和真实原因无关的错，不如把选择权交回调用方。
        val byExtension = when (fileName.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heic"
            "bmp", "dib" -> "image/bmp"
            "avif" -> "image/avif"
            else -> null
        }
        return byExtension ?: declared.takeIf { it.startsWith("image/") } ?: "image/png"
    }

    /**
     * 把设备/文件管理器给的 MIME 归一到内核认识的名字。
     *
     * 归一化只处理**同一格式的别名**，不做跨格式搬运：`image/x-ms-bmp` 与 `image/bmp`
     * 都是 BMP，归成同一个名字是安全的；但 HEIC 归成 PNG 就是撒谎（字节还是 HEIC，
     * 内核的魔数嗅探或解码器会发现对不上）。跨格式只能靠转码，见
     * `HarnessAttachmentsViewModel.attachImage`。
     *
     * 认不出来返回 null，交给调用方按扩展名推或拒绝。
     */
    private fun normalizeImageMediaType(declared: String): String? = when (declared) {
        "image/png", "image/x-png" -> "image/png"
        "image/jpeg", "image/jpg", "image/pjpeg" -> "image/jpeg"
        "image/webp" -> "image/webp"
        "image/gif" -> "image/gif"
        // BMP 没有统一的注册名，各平台各叫各的；两种写法都见过
        "image/bmp", "image/x-bmp", "image/x-ms-bmp", "image/x-ms-bmp2" -> "image/bmp"
        // 以下三种内核都不收，归一化只为让报错可读（避免把 image/heic 说成「未知」）
        "image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence" -> "image/heic"
        "image/avif", "image/avif-sequence" -> "image/avif"
        else -> null
    }

    /**
     * 这个类型需要客户端转码成 [SUPPORTED_IMAGE_MEDIA_TYPES] 里的一种吗。
     *
     * 内核不收的格式（HEIC/HEIF/AVIF/BMP 等）在客户端解成位图再编码，比让用户
     * "自己先转成 JPG" 体面得多 —— 用户选图时根本不知道内核收什么。
     */
    fun needsTranscode(mediaType: String): Boolean = !isSupportedImageMediaType(mediaType)

    /**
     * 给用户看的格式名。`image/x-ms-bmp` 这种内部名直接上屏只会让人更困惑。
     *
     * 认不出来的就取 `/` 后面那段转大写 —— 说「这张图是 OCTET-STREAM 格式」虽然也没多有用，
     * 但比裸 MIME 强，至少提示了「这是个格式问题」。
     */
    fun mediaTypeLabel(mediaType: String): String {
        val key = mediaType.trim().lowercase().substringBefore(';').trim()
        return when (key) {
            "image/png", "image/x-png" -> "PNG"
            "image/jpeg", "image/jpg", "image/pjpeg" -> "JPG"
            "image/webp" -> "WebP"
            "image/gif" -> "GIF"
            "image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence" -> "HEIC"
            "image/avif", "image/avif-sequence" -> "AVIF"
            "image/bmp", "image/x-bmp", "image/x-ms-bmp", "image/dib" -> "BMP"
            else -> key.substringAfter('/').uppercase().ifBlank { "未知格式" }
        }
    }

    /** 附件条上显示的体积，与「文件」页的口径一致（KiB / MiB）。 */
    fun formatBytes(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "%.1f KiB".format(size / 1024.0)
        else -> "%.1f MiB".format(size / 1024.0 / 1024.0)
    }
}
