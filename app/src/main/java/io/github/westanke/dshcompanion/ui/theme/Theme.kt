package io.github.westanke.dshcompanion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.westanke.dshcompanion.data.AppTheme

/**
 * 语义色（连接通/断）的 **CompositionLocal**。
 *
 * 为什么走 CompositionLocal 而不是让组件自己判明暗：`Components.kt` 里的
 * `StatusLabel` 是**跨页复用**的哑组件，它不该知道「现在是哪套主题、深还是浅」——
 * 那是主题层的知识。主题在这里下发一次，组件只消费。
 *
 * 默认值取 PAPER 浅色那一对：任何在 `DeepSeekHarnessTheme` 之外渲染的场景
 * （预览、单测、将来某个忘了包主题的入口）都不会拿到 null。
 */
val LocalSemanticColors = staticCompositionLocalOf {
    SemanticColors(online = Color(0xFF2E7D32), offline = Color(0xFFC62828))
}

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
 * - [appTheme] 是**配色主题**（PAPER / WARM / MIST / INK），默认 [AppTheme.PAPER] ——
 *   与加这套机制之前的配色逐字段相同。它与 [darkTheme] 正交：
 *   主题定色相与基调，明暗定深浅，两者组合出 8 份色板。
 */
@Composable
fun DeepSeekHarnessTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    appTheme: AppTheme = AppTheme.PAPER,
    content: @Composable () -> Unit,
) {
    val palette = remember(appTheme) { paletteOf(appTheme) }
    AppFontScale(fontScale) {
        CompositionLocalProvider(LocalSemanticColors provides palette.semantics(darkTheme)) {
            MaterialTheme(
                colorScheme = palette.colorScheme(darkTheme),
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
}
