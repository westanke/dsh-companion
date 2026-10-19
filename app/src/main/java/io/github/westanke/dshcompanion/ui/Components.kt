package io.github.westanke.dshcompanion.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.ChatContentParts
import io.github.westanke.dshcompanion.data.EndpointKind
import io.github.westanke.dshcompanion.data.PREVIEW_PDF_MAX_PAGES
import io.github.westanke.dshcompanion.data.PreviewKind
import io.github.westanke.dshcompanion.data.binaryPreviewByteLimit
import io.github.westanke.dshcompanion.data.sampleSizeFor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(40.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.padding(7.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
fun WarningBanner(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
            message,
            modifier = Modifier.padding(start = 10.dp),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * 连接状态的**语义色**：绿=已连接、红=已断开。
 *
 * 刻意不用主题色：这两个是「通/断」的语义色，不该随主题变（深色主题下也要一眼可辨）。
 */
val ConnectionOnlineColor = Color(0xFF2E7D32)
val ConnectionOfflineColor = Color(0xFFC62828)

/**
 * 一行状态：**状态点 + 文字**。
 *
 * @param activeColor / @param inactiveColor 状态点（连带文字）的颜色。
 *   默认走主题色，保持「会话运行状态」那类中性语义不变；
 *   **连接状态**（已连接/已断开）由调用方传**绿/红**——用户要求这两态一眼可辨
 *   （2026-10-19 真机反馈），而主题色在深色主题下与普通文字区分度不够。
 */
@Composable
fun StatusLabel(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.outline,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(if (active) activeColor else inactiveColor, CircleShape),
        )
        Text(
            label,
            color = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.padding(14.dp).size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun SectionPanel(
    @StringRes title: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(title),
            modifier = Modifier.padding(horizontal = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
        ) {
            content()
        }
    }
}

@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            if (value.isNotEmpty()) {
                Text(
                    value,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        trailing?.invoke(this)
    }
}

/**
 * 地址类型的可读标签。
 *
 * 放在共享组件里是因为有两个地方要显示它（未连接首屏的电脑列表、设置页的连接信息），
 * 而它必须与 [EndpointKind] 的判定保持一致 —— 两处各写一份迟早会漂移。
 */
@Composable
fun endpointKindLabel(kind: EndpointKind): String = stringResource(
    when (kind) {
        EndpointKind.LAN -> R.string.endpoint_kind_lan
        EndpointKind.VIRTUAL_NET -> R.string.endpoint_kind_virtual
        EndpointKind.WAN -> R.string.endpoint_kind_wan
        EndpointKind.UNKNOWN -> R.string.endpoint_kind_unknown
    },
)

// ───────────────────────── 图片 / PDF 预览（两个入口共用） ─────────────────────────
//
// 文件页（`FilesScreen.EditorPane`）与会话附件面板（`ChatScreen`）共用这一份实现。
// 早先两处各写各的渲染分支，结果是「一处能看、另一处只有一行『二进制文件』」——
// 这类分歧在这个项目里反复出现过，所以这次直接抽成一个组件，谁都别再走偏。
//
// 依赖：**零新增**。图片用系统 `BitmapFactory`，PDF 用系统 `android.graphics.pdf.PdfRenderer`
// （API 21+，本项目 minSdk 26）。不引 Coil / Glide / 任何 PDF 库 —— 这个环境能不能从
// Maven 拉包不确定，而且为「看一眼截图」加一个几 MB 的依赖也不划算。

/**
 * 图片 / PDF 预览容器。
 *
 * 三种状态都必须有可见反馈，**不许留白**：
 * - [bytes] 有值 → 交给解码器（[ImagePreview] / [PdfPreview]）
 * - [skippedForSize] → 「文件超过 X」+ 下载出路（[PreviewSkippedNotice]）
 * - 其余 → 调用方给的兜底文案（文件页是 `binary_file`，会话面板是 `session_file_binary`）
 *
 * @param kind 只认 [PreviewKind.IMAGE] 与 [PreviewKind.PDF]；其他类型走兜底文案。
 * @param onDownload 能给就给：文件页有下载入口，会话附件面板没有（它只在手机上列路径）。
 */
@Composable
fun BinaryFilePreview(
    kind: PreviewKind,
    bytes: ByteArray?,
    sizeBytes: Long,
    skippedForSize: Boolean,
    fallbackMessage: String,
    skippedHint: String,
    modifier: Modifier = Modifier,
    onDownload: (() -> Unit)? = null,
) {
    val hasBytes = bytes != null && bytes.isNotEmpty()
    when {
        hasBytes && kind == PreviewKind.IMAGE -> ImagePreview(requireNotNull(bytes), modifier)
        hasBytes && kind == PreviewKind.PDF -> PdfPreview(requireNotNull(bytes), modifier)
        skippedForSize -> PreviewSkippedNotice(kind, sizeBytes, skippedHint, onDownload, modifier)
        else -> Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                fallbackMessage,
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 「文件太大，没预览」—— 说清上限是多少，并给一条出路。 */
@Composable
private fun PreviewSkippedNotice(
    kind: PreviewKind,
    sizeBytes: Long,
    hint: String,
    onDownload: (() -> Unit)?,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            // 两个数字都给：只说「超过上限」用户没法判断值不值得去下载
            stringResource(
                R.string.preview_skipped_too_large,
                ChatContentParts.formatBytes(sizeBytes),
                ChatContentParts.formatBytes(binaryPreviewByteLimit(kind) ?: 0L),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        if (onDownload != null) {
            Button(onClick = onDownload) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.download), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** 加载中的统一观感。 */
@Composable
private fun PreviewProgress(modifier: Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp)
        Text(
            stringResource(R.string.preview_loading),
            modifier = Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 解码 / 打开失败的统一观感：一句**人话**，不是空白，也不是原始异常。 */
@Composable
private fun PreviewFailure(message: String, modifier: Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 双指缩放 + 拖动 + 双击复位。
 *
 * 手势挂在**容器**上、变换挂在**内容**上 —— 如果把 graphicsLayer 也挂到手势节点，
 * 手势坐标会被缩放再乘一遍，缩放会越拖越飞。缩放只影响绘制（graphicsLayer 不触发重新布局），
 * 所以大图缩放不会掉帧式重排。
 */
@Composable
private fun ZoomableSurface(modifier: Modifier, content: @Composable () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(MIN_PREVIEW_SCALE, MAX_PREVIEW_SCALE)
                    // 缩回原始大小就把偏移清零，否则「复位」之后图还停在半边
                    offset = if (next <= 1f) Offset.Zero else offset + pan
                    scale = next
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) { content() }
    }
}

private sealed interface BinaryPreviewState {
    object Loading : BinaryPreviewState
    object Failed : BinaryPreviewState
    data class Ready(val bitmap: Bitmap) : BinaryPreviewState
}

/**
 * 图片：**自己解码**，不引图片库。
 *
 * 两趟解码是刻意的：先 `inJustDecodeBounds` 只读长宽（不解像素），用 [sampleSizeFor]
 * 算出 2 的幂采样率，再真解码。这样 8000×6000 的 48 MP 照片会被缩到 12 MP 以内，
 * 不会一进来就把 App 解到 OOM。解码在 [Dispatchers.Default]，不占主线程。
 */
@Composable
private fun ImagePreview(bytes: ByteArray, modifier: Modifier) {
    val state by produceState<BinaryPreviewState>(BinaryPreviewState.Loading, bytes) {
        value = withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                // 连长宽都读不出来：不是图片，或者文件截断
                BinaryPreviewState.Failed
            } else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
                    .getOrNull()
                if (bitmap == null) BinaryPreviewState.Failed else BinaryPreviewState.Ready(bitmap)
            }
        }
    }
    when (val current = state) {
        BinaryPreviewState.Loading -> PreviewProgress(modifier)
        BinaryPreviewState.Failed -> PreviewFailure(stringResource(R.string.preview_image_failed), modifier)
        is BinaryPreviewState.Ready -> ZoomableSurface(modifier) {
            Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/**
 * 一份已打开的 PDF：缓存文件、文件描述符、渲染器一起持有，`close()` 一把全放。
 *
 * [PdfRenderer] 只能吃**可随机访问**的 fd（管道不行），所以先把字节落到缓存目录。
 * 文件名带内容哈希：同一份文件重复预览复用同一个名字，不同文件不会互相覆盖。
 */
private class PdfDocument(
    private val file: File,
    private val descriptor: ParcelFileDescriptor,
    val renderer: PdfRenderer,
) {
    val pageCount: Int get() = renderer.pageCount

    fun close() {
        // 三个都要放：renderer 不 close 会一直占着 fd；descriptor 不 close 再漏一个；
        // 缓存文件不删则每预览一份 PDF 就在缓存目录留一份几十 MB 的尸体。
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
        runCatching { file.delete() }
    }

    companion object {
        fun open(context: android.content.Context, bytes: ByteArray): PdfDocument {
            val file = File(context.cacheDir, "preview-${bytes.size}-${bytes.contentHashCode()}.pdf")
            file.outputStream().use { it.write(bytes) }
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                return PdfDocument(file, descriptor, PdfRenderer(descriptor))
            } catch (t: Throwable) {
                // PdfRenderer 构造失败（不是合法 PDF）时，已经打开的 fd 与落盘的文件也要收掉
                runCatching { descriptor.close() }
                runCatching { file.delete() }
                throw t
            }
        }
    }
}

private sealed interface PdfState {
    object Loading : PdfState
    object Failed : PdfState
    data class Ready(val document: PdfDocument) : PdfState
}

/** 一份 PDF 的**唯一可变持有者**：组合退场时 onDispose 只认它。 */
private class PdfHolder {
    var document: PdfDocument? = null
        private set

    /** 登记：在 `open()` 成功那一行立刻调用，不等协程回到 UI 线程。 */
    fun adopt(document: PdfDocument?) {
        this.document = document
    }

    fun close() {
        document?.close()
        document = null
    }
}

/**
 * PDF：系统自带 [PdfRenderer] 逐页渲染成 Bitmap。
 *
 * **资源释放**（[PdfRenderer] 漏 close 会一直占着 fd，而 fd 是进程级稀缺资源，上限通常 1024）：
 * 1. [DisposableEffect] 负责退场时 close —— 组合被销毁、或 bytes 变化导致 holder 换新都会走到；
 * 2. 登记发生在 `open()` 成功的那一行（[PdfHolder.adopt]），**不是**等协程回到 UI 线程再登记。
 *    这样即使协程在「打开成功」与「交给界面」之间被取消，fd 也已经挂在 holder 上、
 *    照样会被 onDispose 收掉。这一步不做的话，快速退出预览就可能漏一个 fd ——
 *    而这类漏法在真机上只表现为「用久了打不开文件」，极难定位。
 */
@Composable
private fun PdfPreview(bytes: ByteArray, modifier: Modifier) {
    val context = LocalContext.current
    val holder = remember(bytes) { PdfHolder() }
    var state by remember(bytes) { mutableStateOf<PdfState>(PdfState.Loading) }

    DisposableEffect(holder) { onDispose { holder.close() } }

    LaunchedEffect(holder) {
        val document = withContext(Dispatchers.IO) {
            runCatching { PdfDocument.open(context, bytes) }.getOrNull()?.also { holder.adopt(it) }
        }
        state = if (document == null || document.pageCount <= 0) PdfState.Failed else PdfState.Ready(document)
    }

    when (val current = state) {
        PdfState.Loading -> PreviewProgress(modifier)
        PdfState.Failed -> PreviewFailure(stringResource(R.string.preview_pdf_failed), modifier)
        is PdfState.Ready -> PdfPageViewer(current.document, modifier)
    }
}

/** 一页一页翻：只保留当前页的 Bitmap，翻页时上一页交给 GC。 */
@Composable
private fun PdfPageViewer(document: PdfDocument, modifier: Modifier) {
    // 页数上限：几百页的标书没必要在手机上全渲染，前 100 页足够判断是不是要找的那份。
    val pages = document.pageCount.coerceIn(1, PREVIEW_PDF_MAX_PAGES)
    var page by remember { mutableStateOf(0) }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { page = (page - 1).coerceAtLeast(0) }, enabled = page > 0) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    stringResource(R.string.preview_pdf_prev),
                )
            }
            Text(
                stringResource(R.string.preview_pdf_page, page + 1, pages),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(onClick = { page = (page + 1).coerceAtMost(pages - 1) }, enabled = page < pages - 1) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    stringResource(R.string.preview_pdf_next),
                )
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // 按**屏宽**渲染而不是原始分辨率：A4 的 595×842 pt 渲染出来足够清楚，
            // 一份 300 DPI 的 PDF 全分辨率渲染一页就是几十 MB，翻两页就崩。
            val targetWidth = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
            val bitmap by produceState<Bitmap?>(null, page, targetWidth, document) {
                value = withContext(Dispatchers.IO) {
                    runCatching { renderPdfPage(document, page, targetWidth) }.getOrNull()
                }
            }
            val current = bitmap
            if (current == null) {
                PreviewProgress(Modifier.fillMaxSize())
            } else {
                ZoomableSurface(Modifier.fillMaxSize()) {
                    Image(
                        bitmap = current.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
        if (document.pageCount > pages) {
            Text(
                stringResource(R.string.preview_pdf_page_limit, document.pageCount, pages),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 渲染一页。
 *
 * 白底是必须的：PDF 页面本身是**透明**的，不铺白在深色主题下就是黑底黑字。
 * 另外长边封顶 [PDF_RENDER_MAX_HEIGHT]，异常比例（比如一页极高的图纸）不会撑爆内存。
 */
private fun renderPdfPage(document: PdfDocument, index: Int, targetWidth: Int): Bitmap {
    // openPage 必须 use：Page 不关，renderer.close() 会抛 IllegalStateException
    document.renderer.openPage(index).use { page ->
        val scale = minOf(
            targetWidth.toFloat() / page.width,
            PDF_RENDER_MAX_HEIGHT.toFloat() / page.height,
        )
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }
}

private const val MIN_PREVIEW_SCALE = 1f
private const val MAX_PREVIEW_SCALE = 8f

/** 单页渲染的高度上限（px）：按屏宽渲染后正常 A4 页约 1500 px，这里留足余量。 */
private const val PDF_RENDER_MAX_HEIGHT = 3000
