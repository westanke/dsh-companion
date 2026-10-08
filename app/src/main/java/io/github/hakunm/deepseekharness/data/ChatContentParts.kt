package io.github.hakunm.deepseekharness.data

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
     * 单张**原图**的字节上限。
     *
     * 限制的是原始字节而不是 base64 长度：base64 会膨胀约 33%，所以 8 MiB 原图
     * 在请求体里约 10.7 MiB。先在这里拦下来，用户拿到的是「先压缩再试」这种能照做的
     * 提示，而不是等一个 413。
     */
    const val MAX_IMAGE_BYTES: Long = 8L * 1024 * 1024

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
     * 超限直接抛 [IllegalArgumentException]（`IMAGE_TOO_LARGE`），而不是造一个超大
     * 请求体让服务端去拒绝 —— 那时用户已经等了一轮上传。
     */
    fun imagePart(mediaType: String, bytes: ByteArray, name: String? = null): PromptPart {
        require(bytes.size.toLong() <= MAX_IMAGE_BYTES) { "IMAGE_TOO_LARGE" }
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
     * 图片的 mediaType。
     *
     * 系统选择器给的 MIME 是首选；拿不到时就按扩展名推，最后兜底 `image/png` ——
     * 能走到这里的文件都是从图片选择器里挑出来的，确实是一张图，
     * 缺一个精确的 MIME 不该让用户发不出去。
     */
    fun imageMediaType(contentType: String?, fileName: String): String {
        val declared = contentType?.trim()?.lowercase().orEmpty()
        if (declared.startsWith("image/")) return declared
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heic"
            "bmp" -> "image/bmp"
            else -> "image/png"
        }
    }

    /** 附件条上显示的体积，与「文件」页的口径一致（KiB / MiB）。 */
    fun formatBytes(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "%.1f KiB".format(size / 1024.0)
        else -> "%.1f MiB".format(size / 1024.0 / 1024.0)
    }
}
