package io.github.westanke.dshcompanion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Paper = Color(0xFFF9F9F7)
private val Oat = Color(0xFFF0EFEC)
private val Ink = Color(0xFF131313)
private val Graphite = Color(0xFF383835)
private val Muted = Color(0xFF74726D)
private val DeepSeekTeal = Color(0xFF006A60)
private val Clay = Color(0xFFC6613F)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2EF),
    onPrimaryContainer = Color(0xFF073B36),
    secondary = DeepSeekTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEDE7),
    onSecondaryContainer = Color(0xFF073B36),
    tertiary = Clay,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBCF),
    onTertiaryContainer = Color(0xFF542013),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Oat,
    onSurfaceVariant = Muted,
    surfaceDim = Color(0xFFE7E5E0),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F4F1),
    surfaceContainer = Oat,
    surfaceContainerHigh = Color(0xFFE9E7E2),
    surfaceContainerHighest = Color(0xFFE2E0DA),
    inverseSurface = Graphite,
    inverseOnSurface = Paper,
    surfaceTint = DeepSeekTeal,
    outline = Color(0xFF8B8983),
    outlineVariant = Color(0xFFD7D5CF),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF3F2ED),
    onPrimary = Ink,
    primaryContainer = Color(0xFF164B45),
    onPrimaryContainer = Color(0xFFCEF2EC),
    secondary = Color(0xFF83D5C8),
    onSecondary = Color(0xFF003730),
    secondaryContainer = Color(0xFF164B45),
    onSecondaryContainer = Color(0xFFCDEDE7),
    tertiary = Color(0xFFFFB59B),
    onTertiary = Color(0xFF5D1B0A),
    tertiaryContainer = Color(0xFF75321F),
    onTertiaryContainer = Color(0xFFFFDBCF),
    background = Color(0xFF181816),
    onBackground = Color(0xFFF0EFEA),
    surface = Color(0xFF181816),
    onSurface = Color(0xFFF0EFEA),
    surfaceVariant = Color(0xFF2A2A27),
    onSurfaceVariant = Color(0xFFBEBBB3),
    surfaceDim = Color(0xFF121210),
    surfaceBright = Color(0xFF383834),
    surfaceContainerLowest = Color(0xFF10100F),
    surfaceContainerLow = Color(0xFF1D1D1B),
    surfaceContainer = Color(0xFF22221F),
    surfaceContainerHigh = Color(0xFF2B2B28),
    surfaceContainerHighest = Color(0xFF343430),
    inverseSurface = Color(0xFFF0EFEA),
    inverseOnSurface = Ink,
    surfaceTint = Color(0xFF83D5C8),
    outline = Color(0xFF918F88),
    outlineVariant = Color(0xFF41413D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val Baseline = Typography()
private val AppTypography = Typography(
    displaySmall = Baseline.displaySmall.copy(
        fontFamily = FontFamily.Serif,
        fontSize = 29.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    headlineMedium = Baseline.headlineMedium.copy(
        fontFamily = FontFamily.Serif,
        fontSize = 24.sp,
        lineHeight = 31.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    headlineSmall = Baseline.headlineSmall.copy(
        fontFamily = FontFamily.Serif,
        fontSize = 20.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    titleLarge = Baseline.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    titleMedium = Baseline.titleMedium.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleSmall = Baseline.titleSmall.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyLarge = Baseline.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    bodyMedium = Baseline.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodySmall = Baseline.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.sp),
    labelLarge = Baseline.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    labelMedium = Baseline.labelMedium.copy(letterSpacing = 0.sp),
    labelSmall = Baseline.labelSmall.copy(letterSpacing = 0.sp),
)

/**
 * 应用主题。
 *
 * - [darkTheme] 由调用方决定，而不是在这里直接读 `isSystemInDarkTheme()`：
 *   设置里新增了「跟随系统 / 浅色 / 深色」三档，配色必须由那条偏好驱动。
 *   参数给了**默认值**，于是老的调用点（测试、预览）不传也仍然可用。
 * - [fontScale] 是应用内字号倍数（1f = 空操作），见 [AppFontScale]。
 */
@Composable
fun DeepSeekHarnessTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    AppFontScale(fontScale) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(4.dp),
                small = RoundedCornerShape(6.dp),
                medium = RoundedCornerShape(8.dp),
                large = RoundedCornerShape(8.dp),
                extraLarge = RoundedCornerShape(8.dp),
            ),
            content = content,
        )
    }
}
