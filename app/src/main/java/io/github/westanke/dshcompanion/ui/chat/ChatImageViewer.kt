package io.github.westanke.dshcompanion.ui.chat

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.ImageFormat
import io.github.westanke.dshcompanion.data.PreviewKind
import io.github.westanke.dshcompanion.data.sniffImageFormat
import io.github.westanke.dshcompanion.ui.BinaryFilePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 消息里的一张图片：缩略图 + **点开看全屏**。
 *
 * 为什么解码与点击在这里而不是留在消息气泡文件里：这两件事是「一张图片」自己的细节，
 * 留在 `ChatMessageItems.kt` 会让那个文件贴着 800 行预算的上限（见 AGENTS.md §4.2）。
 * 气泡那边只保留「这一条附件取到了没有」的判定（加载中 / 失败），与图片本身无关。
 *
 * 尺寸上限（260×340）与老实现一致：缩略图只是让人认出是哪张图，看清内容是点开之后的事。
 */
@Composable
internal fun ChatImageThumbnail(name: String?, bytes: ByteArray) {
    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    if (bitmap == null) {
        // 字节取到了但解不出图：数据有问题，与网络无关 —— 提示不能让用户去重试网络
        AttachmentNotice(stringResource(R.string.attachment_undecodable))
        return
    }
    // 查看器的开关挂在**这一张**图上：两条消息里的两张图互不影响，一张关掉不会连带另一张
    var viewerOpen by remember { mutableStateOf(false) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .widthIn(max = 260.dp)
            .heightIn(max = 340.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable { viewerOpen = true },
    )
    if (viewerOpen) {
        ChatImageDialog(name = name.orEmpty(), bytes = bytes, onDismiss = { viewerOpen = false })
    }
}

/**
 * 消息里的图片点开后的**全屏查看**。
 *
 * 为什么复用 [BinaryFilePreview] 而不是自己写 Image + 手势：仓库里已经有一份打磨过的
 * 缩放实现（`ui/Components.kt` 的 `ZoomableSurface` + 两趟采样解码），文件页、会话文件页、
 * 消息预览三处共用它。再写第二份的下场一定是「两处缩放手感不一样、大图崩不崩只修了一边」。
 *
 * 交互是**刻意**这样定的：
 * - 关闭 = 右上角 ✕ / 系统返回键。没做「点任意处关闭」：复用来的缩放层在整屏上消费按下的
 *   手势（它要留双击复位与双指缩放），叠在外面那层「点一下就关」要么收不到事件，要么与
 *   双击复位抢手势，最终表现为「想双击复位却把图关了」。
 * - 保存 = 底部按钮，写系统相册（[saveToGallery]），失败一定有可见提示。
 */
@Composable
internal fun ChatImageDialog(name: String, bytes: ByteArray, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    // 三条提示文案在**组合期**取好，再传进协程与 IO 函数：
    // 那边不是 Composable 作用域，读不到 stringResource；而且放在组合期取值，
    // 用户切换界面语言后这几句提示也会跟着变（与 lint 的 LocalContextGetResourceValueCall
    // 同一条道理 —— context.getString 不订阅 Configuration）。
    val savedHint = stringResource(R.string.image_saved_to_gallery)
    val needsPermissionHint = stringResource(R.string.image_save_needs_permission)
    val failedHint = stringResource(R.string.image_save_failed)
    Dialog(
        onDismissRequest = onDismiss,
        // 默认宽度是「对话框那么宽」，全屏看图必须自己拿满整屏
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            BinaryFilePreview(
                kind = PreviewKind.IMAGE,
                bytes = bytes,
                sizeBytes = bytes.size.toLong(),
                // 字节已经在手上（列表里那张缩略图就是它），没有「太大跳过预览」这一档
                skippedForSize = false,
                fallbackMessage = stringResource(R.string.attachment_undecodable),
                skippedHint = "",
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    // 半透明黑条：一张浅色截图铺满时，白底上的白字按钮会看不见
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name,
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.close), tint = Color.White)
                }
            }
            Button(
                onClick = {
                    if (saving) return@Button
                    saving = true
                    scope.launch {
                        // 写相册是阻塞 IO，绝不能在主线程做（大图会卡住界面甚至 ANR）
                        val failure = withContext(Dispatchers.IO) {
                            saveToGallery(context, name, bytes, needsPermissionHint, failedHint)
                        }
                        saving = false
                        // 失败必须说出来：静默失败的用户会以为「存上了」，去相册里找不到才回头骂
                        toast(context, failure ?: savedHint)
                    }
                },
                enabled = !saving,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.save), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * 把图片字节写进系统相册（`MediaStore`）。
 *
 * 返回 `null` 表示成功；否则返回**可直接展示给用户的失败原因**。为什么给原因而不是
 * Boolean：失败的原因对用户的意义完全不同（缺权限 / 没有可写的相册目录 / 写一半断了），
 * 一句笼统的「保存失败」只会让人反复重试同一个必败的动作。
 *
 * **已知限制**：API 28 及以下往共享相册写需要 `WRITE_EXTERNAL_STORAGE`，而清单里没有这条
 * 权限（本次改动只允许动这两个 UI 文件，加不了权限声明），所以 Android 8/9 上这一档必定
 * 失败 —— 这里在动手前就认出来并给出明确原因，而不是让 SecurityException 冒上来。
 */
private fun saveToGallery(
    context: Context,
    name: String,
    bytes: ByteArray,
    needsPermissionMessage: String,
    failedMessage: String,
): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return needsPermissionMessage
    }
    val format = sniffImageFormat(bytes)
    return runCatching<String?> {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, galleryFileName(name, format))
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeOf(format))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/DSH Companion")
                // IS_PENDING：写完之前不让相册索引到半截文件 —— 否则用户在写入途中打开相册
                // 会看到一张裂图，而且它会一直留在那儿
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error(failedMessage)
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error(failedMessage)
        } catch (t: Throwable) {
            // 半截文件比「没存上」更糟：相册里躺着一张打不开的图，还得用户自己找出来删
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        }
        null
    }.getOrElse { failure ->
        failure.message?.takeIf { it.isNotBlank() } ?: failedMessage
    }
}

/**
 * 相册里的文件名：**必须有与真实格式一致的扩展名**。
 *
 * 附件名常常没有扩展名（服务端给的是 `screenshot`、`image-1` 这种），而相册是按扩展名
 * 决定「用什么打开」的 —— 补错或不补，存进去就是一条点不开的记录。
 * 名字里已经有图片扩展名时原样保留：用户自己起的名字优先。
 */
private fun galleryFileName(name: String, format: ImageFormat?): String {
    val base = name.substringAfterLast('/').ifBlank { "image" }
    val extension = when (format) {
        ImageFormat.PNG -> "png"
        ImageFormat.JPEG -> "jpg"
        ImageFormat.GIF -> "gif"
        ImageFormat.WEBP -> "webp"
        ImageFormat.BMP -> "bmp"
        ImageFormat.HEIF -> "heic"
        ImageFormat.AVIF -> "avif"
        null -> null
    }
    if (extension == null) return base
    val existing = base.substringAfterLast('.', "").lowercase()
    return if (existing in IMAGE_FILE_EXTENSIONS) base else "$base.$extension"
}

/** 认得出格式时给准确的 MIME：相册与「分享到」都靠它决定怎么处理这一项。 */
private fun mimeTypeOf(format: ImageFormat?): String = when (format) {
    ImageFormat.PNG -> "image/png"
    ImageFormat.JPEG -> "image/jpeg"
    ImageFormat.GIF -> "image/gif"
    ImageFormat.WEBP -> "image/webp"
    ImageFormat.BMP -> "image/bmp"
    ImageFormat.HEIF -> "image/heic"
    ImageFormat.AVIF -> "image/avif"
    // 认不出来（TIFF、SVG 之类）只能给通用 MIME，总比硬塞一个错误类型好
    null -> "image/*"
}

private val IMAGE_FILE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif",
)

// 三条提示文案已搬进 `values/strings.xml` 与 `values-en/strings.xml`（2026-10-17）：
// 由 [ChatImageDialog] 在**组合期**取好再传进协程与 IO 函数 —— 这样切换界面语言时
// 提示也跟着变，而不是写死一种语言。
