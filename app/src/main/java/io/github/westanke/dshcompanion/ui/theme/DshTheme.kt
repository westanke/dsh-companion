package io.github.westanke.dshcompanion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import io.github.westanke.dshcompanion.data.AppTheme

/**
 * **DSH 官方模式**的外壳主题。
 *
 * 官方 App 的观感来自 `dsh-Chat` 的 `app/lib/design/tokens.dart`（色板 / 字号 / 圆角）。
 * 这份实现把那套 token 灌进 Material3，让壳层（顶栏、抽屉、会话列表、设置页）
 * 直接长成官方的样子，而不必逐个组件写死颜色。
 *
 * ## 为什么是「包一层」而不是「换掉全局主题」
 *
 * 用户明确要求**聊天详情页一个像素都不动**（仍然是暖调 PAPER 主题）。
 * 全局 MaterialTheme 只有一份，所以官方色板只能靠**局部覆盖**来生效：
 * 外壳走 [DshTheme]，聊天详情页走 [LegacyChatTheme] 把旧色板与旧字号原样包回去。
 * 两层互不干扰，切换页面时各自还原。
 *
 * ## 为什么连语义色一起换
 *
 * `StatusLabel` / `WarningBanner` 是跨页哑组件，只认 [LocalSemanticColors]。
 * 外壳里的连接状态点、警告条要跟着官方走，所以 [DshTheme] 一并下发官方语义色；
 * 聊天详情页由 [LegacyChatTheme] 还原成暖调那组，两边各看各的。
 */
@Composable
fun DshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DshColors.Dark else DshColors.Light
    CompositionLocalProvider(
        LocalDshColors provides colors,
        LocalSemanticColors provides colors.semantics,
    ) {
        MaterialTheme(
            colorScheme = dshColorScheme(darkTheme),
            typography = DshTypography.material,
            shapes = DshShapes,
            content = content,
        )
    }
}

/**
 * 把某一子树还原成**暖调老主题**（PAPER / WARM / MIST / INK + 衬线标题 + 4–8dp 圆角）。
 *
 * 目前唯一的调用点是聊天详情页（`ChatScreen` 里包 `ChatDetail` / `ChatWebScreen`）。
 * 它的存在意义是：官方色板是包在壳层上的，聊天详情页如果直接继承就会被一起改色，
 * 与「聊天页完全不动」的要求冲突。这里把旧 palette + 旧 typography 原样包回去，
 * 保证聊天页面的观感与改造前逐像素一致。
 *
 * 注意：[appTheme] 传当前用户选的配色主题（不是硬编码 PAPER），否则用户在设置里
 * 切到 WARM 时聊天页会「回不到」自己选的那套色。
 */
@Composable
fun LegacyChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    appTheme: AppTheme = AppTheme.PAPER,
    content: @Composable () -> Unit,
) {
    val palette = remember(appTheme) { paletteOf(appTheme) }
    CompositionLocalProvider(LocalSemanticColors provides palette.semantics(darkTheme)) {
        MaterialTheme(
            colorScheme = palette.colorScheme(darkTheme),
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
