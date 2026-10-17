package io.github.westanke.dshcompanion.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/**
 * 文件列表左侧的类型图标 —— **直接用 Android 系统已有的**，不自己画。
 *
 * 做法与系统「文件」应用一致：按扩展名取 MIME，再问系统「哪种 Activity 能打开
 * `type/mime`」，拿它的图标。这样：
 *
 * - 装了什么应用就显示什么的图标（PDF 显示阅读器图标、APK 显示安装器图标、
 *   图片显示相册图标），**零维护** —— 不需要我们自己收集几十个类型的图标资源；
 * - 与用户在系统里看到的一致，认得出；
 * - 扩展名不认识时退化成 null，调用方继续用内置的通用文档图标。
 *
 * 为什么不用 `Resources.getSystem().getIdentifier("ic_...")` 那类做法：系统私有
 * 资源名不稳定（不同 ROM 一套名字），编译期也校验不到，换台机器就可能静默变空白。
 */
@Composable
internal fun rememberFileTypeIcon(fileName: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(fileName) { resolveSystemIcon(context, fileName) }
}

private fun resolveSystemIcon(context: Context, fileName: String): ImageBitmap? {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension.isEmpty()) return null
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: return null
    val intent = Intent(Intent.ACTION_VIEW).setType(mime)
    val drawable: Drawable = context.packageManager.resolveActivity(intent, 0)
        ?.loadIcon(context.packageManager)
        ?: return null
    return runCatching { drawable.toBitmap(width = ICON_PX, height = ICON_PX).asImageBitmap() }
        .getOrNull()
}

/** 解析图标用的位图边长（px）。48 足够 21~24dp 的显示位，再大只是白占内存。 */
private const val ICON_PX = 48

/**
 * 文件类型图标：有系统图标就画系统图标，没有就退回 [fallback]。
 *
 * 抽成独立 composable 是为了让「解析失败」这条路径也走同一处 —— 否则每个调用点
 * 都要写一遍 `if (icon != null)`，忘了写就画出空白方块。
 */
@Composable
internal fun FileTypeIcon(
    fileName: String,
    fallback: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = rememberFileTypeIcon(fileName)
    if (icon == null) {
        fallback()
    } else {
        Image(bitmap = icon, contentDescription = null, modifier = modifier.size(21.dp))
    }
}

/** 给 [DrawScope] 用的空实现占位，避免 lint 误报未使用参数。 */
private fun DrawScope.noop() = drawIntoCanvas { }
