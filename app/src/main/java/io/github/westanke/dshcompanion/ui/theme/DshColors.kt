package io.github.westanke.dshcompanion.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * DSH 官方语义色（浅色 / 深色两份）。
 *
 * 与 dsh-Chat 的 `app/lib/design/tokens.dart` 里的 `DsColors` **逐值对齐**。
 * 这份类不做审美，只做搬运：官方 App 的观感就是这些常量的组合。
 *
 * ## 为什么要有「语义层」而不用 Material3 的 ColorScheme 直接写死
 *
 * Material3 的角色名（primary / surfaceContainer / outline…）是按 M3 规范组织的，
 * 但官方 DSH 的层级是另一套（bgBase / bgLayer1 / labelTertiary / chipSelectedBg…）。
 * 两种命名直接混用，半年后没人说得清某个 `surfaceContainerHigh` 到底对应官方的哪一层。
 * 所以这里保留官方命名，另建一份 M3 色板映射（见 [dshColorScheme]）专门喂给 M3 组件。
 */
data class DshColors(
    val bgBase: Color,
    val bgLayer1: Color,
    val bgLayer2: Color,
    val bgOverlay: Color,
    val borderL1: Color,
    val borderL2: Color,
    val borderL3: Color,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val brand: Color,
    val brandMark: Color,
    val link: Color,
    val chipSelectedBg: Color,
    val chipSelectedFg: Color,
    val onBrand: Color,
    val shadow: Color,
    val danger: Color,
    val inputFill: Color,
    val inputBorder: Color,
    val inputHintText: Color,
    val drawerSearchFill: Color,
    val drawerGroupLabel: Color,
    val drawerScrim: Color,
    val settingsSurface: Color,
    val settingsField: Color,
    val settingsWarnBg: Color,
    val settingsWarnFg: Color,
    val statusOnline: Color,
    val statusOffline: Color,
    val okContainer: Color,
    val onOkContainer: Color,
    val warnContainer: Color,
    val onWarnContainer: Color,
    val errContainer: Color,
    val onErrContainer: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
) {
    /**
     * 折算成 companion 既有的语义色结构。
     *
     * `StatusLabel` / `WarningBanner` 这类跨页哑组件只认 [SemanticColors]，
     * 不该知道「现在是官方模式还是暖调模式」——那是主题层的知识（见 Theme.kt 的注释）。
     */
    val semantics: SemanticColors
        get() = SemanticColors(
            online = statusOnline,
            offline = statusOffline,
            warn = settingsWarnFg,
            info = brand,
            okContainer = okContainer,
            onOkContainer = onOkContainer,
            warnContainer = warnContainer,
            onWarnContainer = onWarnContainer,
            errContainer = errContainer,
            onErrContainer = onErrContainer,
            infoContainer = infoContainer,
            onInfoContainer = onInfoContainer,
        )

    companion object {
        /** 浅色语义色（官方 App 浅色模式实测值）。 */
        val Light = DshColors(
            bgBase = DshPalette.c00,
            bgLayer1 = DshPalette.c00,
            bgLayer2 = DshPalette.c00,
            bgOverlay = DshPalette.c150,
            borderL1 = Color(0x0A000000),
            borderL2 = Color(0x1A000000),
            borderL3 = Color(0x1F000000),
            labelPrimary = DshPalette.c1000,
            labelSecondary = DshPalette.c700,
            labelTertiary = DshPalette.c400,
            brand = DshPalette.brandLight,
            brandMark = DshPalette.linkLight,
            link = DshPalette.linkLight,
            chipSelectedBg = DshPalette.bubbleLight,
            chipSelectedFg = DshPalette.chipSelectedFgLight,
            onBrand = DshPalette.c00,
            shadow = Color(0x14000000),
            danger = Color(0xFFD93F3F),
            inputFill = DshPalette.c00,
            inputBorder = Color(0xFFEDEDED),
            inputHintText = DshPalette.c400,
            drawerSearchFill = Color(0xFFF5F5F5),
            drawerGroupLabel = DshPalette.c400,
            drawerScrim = Color(0x00000000),
            settingsSurface = DshPalette.c50,
            settingsField = DshPalette.c00,
            settingsWarnBg = Color(0x1AF5A623),
            settingsWarnFg = Color(0xFF8A5A00),
            statusOnline = DshPalette.statusOnline,
            statusOffline = DshPalette.statusOffline,
            okContainer = Color(0xFFC6F0D0),
            onOkContainer = Color(0xFF14532D),
            warnContainer = Color(0xFFFDF3D8),
            onWarnContainer = Color(0xFF8A5A00),
            errContainer = Color(0xFFF9C9C9),
            onErrContainer = Color(0xFF7F1D1D),
            infoContainer = Color(0xFFE3ECFF),
            onInfoContainer = Color(0xFF1B3A8C),
        )

        /** 深色语义色（官方 App 深色模式实测值）。 */
        val Dark = DshColors(
            bgBase = DshPalette.appDarkBg,
            bgLayer1 = DshPalette.c875,
            bgLayer2 = DshPalette.c850,
            bgOverlay = DshPalette.c750,
            borderL1 = Color(0x0FFFFFFF),
            borderL2 = Color(0x1FFFFFFF),
            borderL3 = Color(0x29FFFFFF),
            labelPrimary = DshPalette.c50,
            labelSecondary = DshPalette.c300,
            labelTertiary = DshPalette.c600,
            brand = DshPalette.brandDark,
            brandMark = DshPalette.brandDark,
            link = DshPalette.brandDark,
            chipSelectedBg = DshPalette.chipSelectedDark,
            chipSelectedFg = DshPalette.chipSelectedFgDark,
            onBrand = DshPalette.c00,
            shadow = Color(0x14000000),
            danger = DshPalette.c300,
            inputFill = DshPalette.appDarkSurface,
            inputBorder = DshPalette.appDarkChipBorder,
            inputHintText = DshPalette.c600,
            drawerSearchFill = DshPalette.c800,
            drawerGroupLabel = DshPalette.c500,
            drawerScrim = Color(0x50000000),
            settingsSurface = DshPalette.c875,
            settingsField = DshPalette.c850,
            settingsWarnBg = Color(0x1AF5A623),
            settingsWarnFg = Color(0xFFFFC24B),
            statusOnline = DshPalette.statusOnline,
            statusOffline = DshPalette.statusOffline,
            okContainer = Color(0xFF1E3A2A),
            onOkContainer = Color(0xFF9BE29B),
            warnContainer = Color(0xFF4A3410),
            onWarnContainer = Color(0xFFFFC24B),
            errContainer = Color(0xFF4A1A1A),
            onErrContainer = Color(0xFFFF9B94),
            infoContainer = Color(0xFF1E2A45),
            onInfoContainer = Color(0xFF9FC0FF),
        )
    }
}

/**
 * 官方语义色的 **CompositionLocal**。
 *
 * 默认值给浅色那一份：任何在主题之外渲染的场景（预览、单测）都不会拿到 null。
 */
val LocalDshColors = staticCompositionLocalOf { DshColors.Light }

/**
 * 把官方语义色映射成 Material3 的 [androidx.compose.material3.ColorScheme]。
 *
 * ## 为什么需要这一层映射
 *
 * 壳层里大量 M3 组件（`NavigationDrawerItem` / `Surface` / `Switch` / `OutlinedButton`…）
 * 直接吃 `MaterialTheme.colorScheme`，而它们并不知道 DSH 的语义命名。
 * 与其逐个组件改配色参数，不如把 M3 角色**一次性**对齐到官方色阶：
 * 之后这些组件自动就长成官方样子了。
 *
 * 映射原则：`surface` 系列 → 官方层级；`outline` 系列 → 官方边框层级；
 * `primary/secondary` → 品牌蓝；`error` → 官方 danger。
 */
internal fun dshColorScheme(dark: Boolean): androidx.compose.material3.ColorScheme = if (dark) {
    darkColorScheme(
        primary = DshPalette.brandDark,
        onPrimary = DshPalette.c00,
        primaryContainer = DshPalette.chipSelectedDark,
        onPrimaryContainer = DshPalette.chipSelectedFgDark,
        secondary = DshPalette.brandDark,
        onSecondary = DshPalette.c00,
        secondaryContainer = DshPalette.chipSelectedDark,
        onSecondaryContainer = DshPalette.chipSelectedFgDark,
        tertiary = DshPalette.brandDark,
        onTertiary = DshPalette.c00,
        tertiaryContainer = DshPalette.c875,
        onTertiaryContainer = DshPalette.c300,
        background = DshPalette.appDarkBg,
        onBackground = DshPalette.c50,
        surface = DshPalette.appDarkBg,
        onSurface = DshPalette.c50,
        surfaceVariant = DshPalette.c875,
        onSurfaceVariant = DshPalette.c300,
        surfaceTint = DshPalette.brandDark,
        inverseSurface = DshPalette.c50,
        inverseOnSurface = DshPalette.c1000,
        error = DshPalette.c300,
        onError = DshPalette.c1000,
        errorContainer = Color(0xFF4A1A1A),
        onErrorContainer = Color(0xFFFF9B94),
        outline = DshPalette.c750,
        outlineVariant = DshPalette.c875,
        scrim = Color(0xFF000000),
        surfaceBright = DshPalette.c875,
        surfaceDim = DshPalette.c950,
        surfaceContainerLowest = DshPalette.appDarkBg,
        surfaceContainerLow = DshPalette.c950,
        surfaceContainer = DshPalette.c900,
        surfaceContainerHigh = DshPalette.c875,
        surfaceContainerHighest = DshPalette.c850,
    )
} else {
    lightColorScheme(
        primary = DshPalette.brandLight,
        onPrimary = DshPalette.c00,
        primaryContainer = DshPalette.bubbleLight,
        onPrimaryContainer = DshPalette.chipSelectedFgLight,
        secondary = DshPalette.brandLight,
        onSecondary = DshPalette.c00,
        secondaryContainer = DshPalette.bubbleLight,
        onSecondaryContainer = DshPalette.chipSelectedFgLight,
        tertiary = DshPalette.linkLight,
        onTertiary = DshPalette.c00,
        tertiaryContainer = DshPalette.c100,
        onTertiaryContainer = DshPalette.c750,
        background = DshPalette.c00,
        onBackground = DshPalette.c1000,
        surface = DshPalette.c00,
        onSurface = DshPalette.c1000,
        surfaceVariant = DshPalette.c75,
        onSurfaceVariant = DshPalette.c700,
        surfaceTint = DshPalette.brandLight,
        inverseSurface = DshPalette.c1000,
        inverseOnSurface = DshPalette.c50,
        error = Color(0xFFD93F3F),
        onError = DshPalette.c00,
        errorContainer = Color(0xFFF9C9C9),
        onErrorContainer = Color(0xFF7F1D1D),
        outline = DshPalette.c300,
        outlineVariant = DshPalette.c100,
        scrim = Color(0xFF000000),
        surfaceBright = DshPalette.c00,
        surfaceDim = DshPalette.c75,
        surfaceContainerLowest = DshPalette.c00,
        surfaceContainerLow = DshPalette.c50,
        surfaceContainer = DshPalette.c60,
        surfaceContainerHigh = DshPalette.c75,
        surfaceContainerHighest = DshPalette.c100,
    )
}
