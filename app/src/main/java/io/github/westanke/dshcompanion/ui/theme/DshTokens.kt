package io.github.westanke.dshcompanion.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * DSH 官方色板（唯一真值来源）。
 *
 * 与 dsh-Chat（Flutter 客户端）的 `app/lib/design/tokens.dart` 里的 `DsPalette`
 * **逐值对齐**——那一份是从官方 DSH App 实测像素反推出来的
 * （见 dsh-Chat 的 `docs/design-tokens.md` 与 `ref/main.pretty.css`）。
 * 这里只做「Dart 常量 → Kotlin 常量」的搬运，不做任何审美发挥：
 * 调色板一漂，壳层与官方 App 就不像了。
 *
 * 中性色是 neutral-bluish（偏蓝的灰），不是 companion 原先的暖调 Paper/Teal/Clay。
 */
object DshPalette {
    // ── 中性色阶（neutral-bluish）──
    val c00 = Color(0xFFFFFFFF)
    val c50 = Color(0xFFF9FAFB)
    val c60 = Color(0xFFF5F6F7)
    val c75 = Color(0xFFF1F3F5)
    val c100 = Color(0xFFEBEEF2)
    val c150 = Color(0xFFE9ECF2)
    val c200 = Color(0xFFE1E5EE)
    val c300 = Color(0xFFCFD3D6)
    val c400 = Color(0xFFADB2B8)
    val c500 = Color(0xFF979DA6)
    val c600 = Color(0xFF81858C)
    val c700 = Color(0xFF61666B)
    val c750 = Color(0xFF43454A)
    val c800 = Color(0xFF353638)
    val c850 = Color(0xFF2C2C2E)
    val c875 = Color(0xFF232324)
    val c900 = Color(0xFF1B1B1C)
    val c950 = Color(0xFF151517)
    val c1000 = Color(0xFF0F1115)

    // ── 品牌蓝 ──
    val brandLight = Color(0xFF3964FE)
    val brandDark = Color(0xFF5686FE)
    val linkLight = Color(0xFF426EFE)

    // ── 内容色 ──
    val quoteBarLight = Color(0xFFE5E5E5)
    val tableBorderLight = Color(0xFFDCDCDC)
    val bubbleLight = Color(0xFFEDF3FE)
    val codeTextDark = Color(0xFFE3EAF2)
    val codeSelectionDark = Color(0xFF3C526D)

    // ── 语法高亮（Atom One Light）──
    val synKeyword = Color(0xFFA626A4)
    val synString = Color(0xFF50A14F)
    val synFunction = Color(0xFFE45649)
    val synNumber = Color(0xFF4078F2)
    val synComment = Color(0xFFA0A1A7)
    val synPlain = Color(0xFF383A42)

    // ── 深色底与选中态 ──
    val appDarkBg = Color(0xFF0F0F0F)
    val appDarkSurface = Color(0xFF242424)
    val appDarkChipBorder = Color(0xFF474849)
    val chipSelectedDark = Color(0xFF283142)
    val chipSelectedFgDark = Color(0xFF679EFE)
    val chipSelectedFgLight = Color(0xFF426EFE)

    // ── 状态点 ──
    val statusOnline = Color(0xFF34C759)
    val statusOffline = Color(0xFFFF3B30)
}

/**
 * DSH 官方间距 / 尺寸。
 *
 * 与 dsh-Chat 的 `app/lib/design/design_tokens_layout.dart` 里的 `DsSpacing` 对齐。
 * 壳层（顶栏、抽屉、会话列表、设置页）只从这里取尺寸，不写裸数字——
 * 否则「官方模式」很快又会退化成「看着差不多」。
 */
object DshSpacing {
    // 页面
    val screenPad: Dp = 22.dp
    val contentPadRight: Dp = 15.dp
    val msgPad: Dp = 30.dp
    val hMargin: Dp = 32.dp
    val hMarginBottom: Dp = 16.dp
    val blockGap: Dp = 16.dp

    // 顶栏
    val topBarHeight: Dp = 44.dp

    // 抽屉
    val drawerWidth: Dp = 288.dp
    val drawerEdgeDragWidth: Dp = 20.dp
    val drawerPageShift: Dp = 288.dp
    val drawerSearchHeight: Dp = 44.dp
    val drawerSearchPadH: Dp = 15.dp
    val drawerSearchPadRight: Dp = 4.dp
    val drawerSearchPadTop: Dp = 11.dp
    val drawerSearchRadius: Dp = 22.dp
    val drawerSearchIconGap: Dp = 6.dp
    val drawerSearchInset: Dp = 11.dp
    val drawerGroupPadH: Dp = 21.dp
    val drawerGroupPadV: Dp = 15.dp
    val drawerSectionPadTop: Dp = 8.dp
    val drawerSectionPadBottom: Dp = 2.dp
    val drawerItemPadH: Dp = 20.dp
    val drawerItemPadV: Dp = 12.dp
    val drawerLineHeight: Dp = 24.dp
    val drawerFooterPadH: Dp = 15.dp
    val drawerAvatarSize: Dp = 29.dp
    val drawerAvatarGap: Dp = 10.dp
    val brandMarkWidth: Dp = 40.dp
    val brandMarkHeight: Dp = 31.5.dp
    val brandMarkGap: Dp = 22.dp

    // 设置页
    val settingsMaxWidth: Dp = 720.dp
    val settingsPadH: Dp = 20.dp
    val settingsPadV: Dp = 28.dp
    val settingsSectionGap: Dp = 22.dp
    val settingsCardRadius: Dp = 14.dp
    val settingsCardPadH: Dp = 16.dp
    val settingsCardPadV: Dp = 6.dp
    val settingsRowMinHeight: Dp = 52.dp
    val settingsIconGap: Dp = 14.dp
    val settingsChipGap: Dp = 7.dp
    val settingsChipPadH: Dp = 10.dp
    val settingsChipPadV: Dp = 6.dp
    val settingsChipRadius: Dp = 16.dp

    /** 官方 `DsSpacing.inlineCodeRadius`：警告条等小色块的圆角。 */
    val inlineCodeRadius: Dp = 6.dp

    /** 官方 `_WarningBanner` 的内距：`EdgeInsets.symmetric(horizontal: 12, vertical: 10)`。 */
    val settingsWarnPadH: Dp = 12.dp
    val settingsWarnPadV: Dp = 10.dp
}

/**
 * DSH 官方动效时长。
 *
 * 与 dsh-Chat 的 `DsMotion` 对齐：抽屉 250ms、悬浮按钮淡出 140ms。
 * 壳层只用到抽屉与 FAB 两处，其余留给后续页面。
 */
object DshMotion {
    const val fabFadeMs: Int = 140
    const val drawerShiftMs: Int = 250
    const val freezeSettleMs: Int = 80
    const val scrollSettleMs: Int = 140
    const val processTitleMinimumMs: Int = 150
    const val shimmerCycleMs: Int = 900
    const val fabOpacity: Float = 0.88f
}
