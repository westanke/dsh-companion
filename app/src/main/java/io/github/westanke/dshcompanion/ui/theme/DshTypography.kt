package io.github.westanke.dshcompanion.ui.theme

import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * DSH 官方字体阶梯。
 *
 * 与 dsh-Chat 的 `app/lib/design/tokens.dart` 里的 `DsTypography` **逐值对齐**：
 * h1 24/34、h2 22/32、h3 20/30、h4 16/28、base 16/28、table 15/25、
 * small 14/24、caption 12、pageTitle 16/600、greeting 18/600。
 *
 * **无衬线**是官方与 companion 原先最大的字体差别：companion 的标题用 Serif（暖调编辑风），
 * 官方是纯无衬线。所以这里全部用系统默认字体族（`FontFamily.Default`）。
 */
object DshTypography {
    val h1 = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
    val h2 = TextStyle(fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
    val h3 = TextStyle(fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
    val h4 = TextStyle(fontSize = 16.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val base = TextStyle(fontSize = 16.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal)
    val baseStrong = TextStyle(fontSize = 16.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val table = TextStyle(fontSize = 15.sp, lineHeight = 25.sp, fontWeight = FontWeight.Normal)
    val tableHead = TextStyle(fontSize = 15.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(fontSize = 14.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
    val smallStrong = TextStyle(fontSize = 14.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val inlineCode = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontFamily = FontFamily.Monospace)
    val codeBlock = TextStyle(fontSize = 13.sp, lineHeight = 22.sp, fontFamily = FontFamily.Monospace)
    val inputPlaceholder = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal)
    val pageTitle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val chipLabel = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val greeting = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)

    /**
     * 官方阶梯映射到 Material3 的排版槽位。
     *
     * 壳层里既有新写的官方版式（会话行、设置卡片），也有沿用的 M3 组件
     * （`NavigationDrawerItem` / `Switch` / `OutlinedButton`…）—— 它们吃的是
     * `MaterialTheme.typography.*`。把官方值灌进这些槽位，那些组件就自动是官方字号，
     * 不必逐个组件改 `fontSize`。
     */
    val material = Typography(
        displaySmall = h1,
        headlineMedium = h3,
        headlineSmall = h4,
        titleLarge = h4,
        titleMedium = baseStrong,
        titleSmall = smallStrong,
        bodyLarge = base,
        bodyMedium = small,
        bodySmall = caption,
        labelLarge = base.copy(fontWeight = FontWeight.Medium),
        labelMedium = small.copy(fontWeight = FontWeight.Medium),
        labelSmall = caption.copy(fontWeight = FontWeight.Medium),
    )
}

/**
 * 官方圆角。
 *
 * 官方 App 的圆角明显大于 companion 原先的 4–8dp 上限（卡片 14dp、搜索框 22dp），
 * 这是「官方模式」最容易被一眼认出来的特征之一，所以单独给一套，不去动
 * 暖调模式那套（后者有 4–8dp 的硬规矩，见 `design-system/deepseek-harness-android/MASTER.md`）。
 */
val DshShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
)
