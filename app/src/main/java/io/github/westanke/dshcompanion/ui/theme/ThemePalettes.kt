package io.github.westanke.dshcompanion.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.github.westanke.dshcompanion.data.AppTheme

/**
 * 配色主题注册表：每套主题提供 **light + dark** 两份 [ColorScheme]。
 *
 * ## 为什么单独一个文件
 *
 * `Theme.kt` 是**每次改界面都必然被读**的那一份，而色板是四套 × 两份 × 约 30 个角色
 * 的纯数据。把它塞在一起会让「主题怎么装配」淹没在色值里；分出来之后，
 * `Theme.kt` 只回答「取哪份色板」，这里只回答「色值是什么」。
 *
 * ## [AppTheme.PAPER] 的零漂移承诺
 *
 * PAPER 的两份色板**逐字段等于**加这套机制之前 `Theme.kt` 里写死的 `LightColors` /
 * `DarkColors`（连「哪个角色用 `Color.White`、哪个用 `0xFFF9F9F7`」这种细节都一样）。
 * 用户不切换主题，屏幕上就该一个像素都不变。
 *
 * 这条承诺由 `AppThemeTest.`PAPER 与本机制之前的配色逐字段相同`` 钉住：
 * 它把 PAPER 与**独立写死的**期望值逐个 `Color` 比对。改任何一个色值都会红。
 *
 * ## 对比度
 *
 * 每套主题的关键色对都按 WCAG 2.1 相对亮度公式算过（`AppThemeTest` 里跑的是同一套公式），
 * 实测数字见 `研究类/安全与界面优化-20261021/界面调整-实施记录.md`。
 * 判据取 **AA 正文 4.5:1**，不是大字 3:1 —— 这个 App 的正文是 15sp、次要文字 12–14sp，
 * 都不算大字。
 *
 * > PAPER **未全部达标**（`tertiary` on `background` 3.84、`onTertiary/tertiary` 4.05、
 * > `outline` on `background` 3.32、`onSurfaceVariant` on `surfaceContainerHighest` 3.64）。
 * > 那是**现状**，本次刻意不动（零漂移优先，修色属于「会改手机端」的批准项 A-4）。
 * > 新加的三套主题不继承这些短板：它们的对应色对全部 ≥ 4.5:1。
 */

// ─────────────────────────── PAPER（现状，默认） ───────────────────────────

/**
 * PAPER 浅色：**逐字段照抄**改造前 `Theme.kt` 的 `LightColors`。
 *
 * 命名沿用当时的语义（Paper / Oat / Ink / Graphite / Muted / DeepSeekTeal / Clay），
 * 这样任何人拿旧版本对账都能一眼对上。
 */
private val PaperLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF131313),            // Ink
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2EF),
    onPrimaryContainer = Color(0xFF073B36),
    secondary = Color(0xFF006A60),          // DeepSeekTeal
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEDE7),
    onSecondaryContainer = Color(0xFF073B36),
    tertiary = Color(0xFFC6613F),           // Clay
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBCF),
    onTertiaryContainer = Color(0xFF542013),
    background = Color(0xFFF9F9F7),         // Paper
    onBackground = Color(0xFF131313),       // Ink
    surface = Color(0xFFF9F9F7),            // Paper
    onSurface = Color(0xFF131313),          // Ink
    surfaceVariant = Color(0xFFF0EFEC),     // Oat
    onSurfaceVariant = Color(0xFF74726D),   // Muted
    surfaceDim = Color(0xFFE7E5E0),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F4F1),
    surfaceContainer = Color(0xFFF0EFEC),   // Oat
    surfaceContainerHigh = Color(0xFFE9E7E2),
    surfaceContainerHighest = Color(0xFFE2E0DA),
    inverseSurface = Color(0xFF383835),     // Graphite
    inverseOnSurface = Color(0xFFF9F9F7),   // Paper
    surfaceTint = Color(0xFF006A60),        // DeepSeekTeal
    outline = Color(0xFF8B8983),
    outlineVariant = Color(0xFFD7D5CF),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/** PAPER 深色：**逐字段照抄**改造前 `Theme.kt` 的 `DarkColors`。 */
private val PaperDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFF3F2ED),
    onPrimary = Color(0xFF131313),          // Ink
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
    inverseOnSurface = Color(0xFF131313),   // Ink
    surfaceTint = Color(0xFF83D5C8),
    outline = Color(0xFF918F88),
    outlineVariant = Color(0xFF41413D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

// ─────────────────────────── WARM（暖米 / 护眼） ───────────────────────────

/**
 * WARM：米黄纸感，整体压暖、降蓝光。强调色由青换成**暖青（teal 偏黄绿）**，
 * 警告色由陶土压深到 `#A5562F`（同时修掉 PAPER 浅色警告 3.84:1 不达 AA 的短板）。
 */
private val WarmLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF1F1B16),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DDC9),
    onPrimaryContainer = Color(0xFF2B241A),
    secondary = Color(0xFF0C5F55),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E7DF),
    onSecondaryContainer = Color(0xFF0B3A34),
    tertiary = Color(0xFFA5562F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF4D8C6),
    onTertiaryContainer = Color(0xFF4A2410),
    background = Color(0xFFFAF6EF),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFAF6EF),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFEFE8DC),
    onSurfaceVariant = Color(0xFF625C52),
    surfaceDim = Color(0xFFE4DBC9),
    surfaceBright = Color(0xFFFFFCF6),
    surfaceContainerLowest = Color(0xFFFFFDF9),
    surfaceContainerLow = Color(0xFFF7F2E8),
    surfaceContainer = Color(0xFFF2EBE0),
    surfaceContainerHigh = Color(0xFFEDE5D7),
    surfaceContainerHighest = Color(0xFFE4DBC9),
    inverseSurface = Color(0xFF3E382F),
    inverseOnSurface = Color(0xFFFAF6EF),
    surfaceTint = Color(0xFF0C5F55),
    outline = Color(0xFF8C8578),
    outlineVariant = Color(0xFFD8CEBC),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val WarmDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFF2ECDF),
    onPrimary = Color(0xFF131313),
    primaryContainer = Color(0xFF1E4B44),
    onPrimaryContainer = Color(0xFFDEEDE7),
    secondary = Color(0xFF8FD6C6),
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF1E4B44),
    onSecondaryContainer = Color(0xFFCDEDE7),
    tertiary = Color(0xFFFFBE9C),
    onTertiary = Color(0xFF4A1F0C),
    tertiaryContainer = Color(0xFF6B3320),
    onTertiaryContainer = Color(0xFFFFDBC9),
    background = Color(0xFF1B1814),
    onBackground = Color(0xFFF1EBE0),
    surface = Color(0xFF1B1814),
    onSurface = Color(0xFFF1EBE0),
    surfaceVariant = Color(0xFF2C2823),
    onSurfaceVariant = Color(0xFFC4BCB0),
    surfaceDim = Color(0xFF15120F),
    surfaceBright = Color(0xFF3B362F),
    surfaceContainerLowest = Color(0xFF13110E),
    surfaceContainerLow = Color(0xFF201D19),
    surfaceContainer = Color(0xFF25211C),
    surfaceContainerHigh = Color(0xFF2E2A24),
    surfaceContainerHighest = Color(0xFF37322B),
    inverseSurface = Color(0xFFF1EBE0),
    inverseOnSurface = Color(0xFF131313),
    surfaceTint = Color(0xFF8FD6C6),
    outline = Color(0xFF968F83),
    outlineVariant = Color(0xFF443E35),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

// ─────────────────────────── MIST（青灰 / 冷静） ───────────────────────────

/**
 * MIST：冷调偏青灰、低饱和。色相往青绿挪一点（不是蓝紫），保持「温暖编辑感」
 * 之外的另一种克制选择；生产环境长时间盯屏时不刺眼。
 */
private val MistLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF16201E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7E3),
    onPrimaryContainer = Color(0xFF152420),
    secondary = Color(0xFF0B5D55),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFE3DE),
    onSecondaryContainer = Color(0xFF07332E),
    tertiary = Color(0xFF8C4A2B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3DACD),
    onTertiaryContainer = Color(0xFF3D2113),
    background = Color(0xFFF5F7F6),
    onBackground = Color(0xFF16201E),
    surface = Color(0xFFF5F7F6),
    onSurface = Color(0xFF16201E),
    surfaceVariant = Color(0xFFE6EBE9),
    onSurfaceVariant = Color(0xFF545E5A),
    surfaceDim = Color(0xFFD9E1DE),
    surfaceBright = Color(0xFFFBFDFC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F4F2),
    surfaceContainer = Color(0xFFEAEFED),
    surfaceContainerHigh = Color(0xFFE2E8E6),
    surfaceContainerHighest = Color(0xFFD9E1DE),
    inverseSurface = Color(0xFF2C3633),
    inverseOnSurface = Color(0xFFF5F7F6),
    surfaceTint = Color(0xFF0B5D55),
    outline = Color(0xFF7C8783),
    outlineVariant = Color(0xFFCBD5D1),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val MistDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFE6EDE9),
    onPrimary = Color(0xFF0E1512),
    primaryContainer = Color(0xFF134A44),
    onPrimaryContainer = Color(0xFFCFEDE4),
    secondary = Color(0xFF7ED8C8),
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF134A44),
    onSecondaryContainer = Color(0xFFCBEDE5),
    tertiary = Color(0xFFFFB894),
    onTertiary = Color(0xFF3D1C0C),
    tertiaryContainer = Color(0xFF5F3020),
    onTertiaryContainer = Color(0xFFFFDBCB),
    background = Color(0xFF121715),
    onBackground = Color(0xFFE4EBE7),
    surface = Color(0xFF121715),
    onSurface = Color(0xFFE4EBE7),
    surfaceVariant = Color(0xFF212B27),
    onSurfaceVariant = Color(0xFFAFBDB7),
    surfaceDim = Color(0xFF0C110F),
    surfaceBright = Color(0xFF2F3A36),
    surfaceContainerLowest = Color(0xFF0A0E0D),
    surfaceContainerLow = Color(0xFF171D1B),
    surfaceContainer = Color(0xFF1C2320),
    surfaceContainerHigh = Color(0xFF242D2A),
    surfaceContainerHighest = Color(0xFF2A3531),
    inverseSurface = Color(0xFFE4EBE7),
    inverseOnSurface = Color(0xFF0E1512),
    surfaceTint = Color(0xFF7ED8C8),
    outline = Color(0xFF8A9691),
    outlineVariant = Color(0xFF37423E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

// ─────────────────────────── INK（高对比） ───────────────────────────

/**
 * INK：纯白 / 近黑的高对比档，给白天强光下（户外、车内）看不清屏幕时用。
 *
 * 刻意**不是**纯 `#000000` 的浅色 / 纯 `#FFFFFF` 的深色反相：浅色态用 `#FFFFFF` 底 +
 * `#0A0A0A` 字（19.8:1），深色态才上真黑 `#000000`（21:1）。留一点余地，
 * 免得 OLED 上白底黑字的边缘振铃太硬。
 */
private val InkLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF0A0A0A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F4F1),
    onPrimaryContainer = Color(0xFF0B211E),
    secondary = Color(0xFF00473F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5EEE8),
    onSecondaryContainer = Color(0xFF052B26),
    tertiary = Color(0xFF8E3D1E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBDCCD),
    onTertiaryContainer = Color(0xFF3A1508),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0A0A0A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF4A4A4A),
    surfaceDim = Color(0xFFE0E0E0),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFEAEAEA),
    surfaceContainerHighest = Color(0xFFE0E0E0),
    inverseSurface = Color(0xFF1A1A1A),
    inverseOnSurface = Color(0xFFFFFFFF),
    surfaceTint = Color(0xFF00473F),
    outline = Color(0xFF6B6B6B),
    outlineVariant = Color(0xFFC4C4C4),
    error = Color(0xFFA3150D),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF3F0000),
)

private val InkDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF003D36),
    onPrimaryContainer = Color(0xFFE8F6F3),
    secondary = Color(0xFF7FE5D2),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF0A4A43),
    onSecondaryContainer = Color(0xFFD6F0EA),
    tertiary = Color(0xFFFFC29C),
    onTertiary = Color(0xFF1F0D00),
    tertiaryContainer = Color(0xFF6B2E14),
    onTertiaryContainer = Color(0xFFFFDCC8),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFFC9C9C9),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF383838),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF121212),
    surfaceContainer = Color(0xFF181818),
    surfaceContainerHigh = Color(0xFF242424),
    surfaceContainerHighest = Color(0xFF2E2E2E),
    inverseSurface = Color(0xFFFFFFFF),
    inverseOnSurface = Color(0xFF000000),
    surfaceTint = Color(0xFF7FE5D2),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFF3A3A3A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * 语义色：**四对**独立 token（绿 / 黄 / 红 / 蓝）+ 各自的容器色。
 *
 * ## 为什么从「一对」扩到「四对」（2026-10-26 路线 C）
 *
 * 改造前只有 `online`/`offline` 一对。问题不在于「少」，而在于**其余语义无处安放**：
 * 「等你确认」「权限警告」这类**警告**语义只能借 `colorScheme.tertiary`（陶土色，
 * 读起来像「强调」不像「警告」），「工具名/信息」只能借 `secondary`（青色）。
 * 全仓 79 处 `colorScheme.tertiary/secondary/error` 的借用里，有相当一部分是
 * **想表达语义但没有语义色可用**才去借的。
 *
 * 四对 token 各归其位：
 *
 * | token | 语义 | 用在哪 |
 * |---|---|---|
 * | `online` | 绿 · 成功 / 已连接 | 状态点、成功标记 |
 * | `offline` | 红 · 失败 / 已断开 | 状态点、失败标记 |
 * | `warn` | 黄 · 待确认 / 需批准 | 审批卡、权限警告 |
 * | `info` | 蓝 · 中性信息 / 工具 | 工具名、代码高亮 |
 *
 * ## 为什么不直接用 `colorScheme` 的角色
 *
 * Material 3 的语义角色里**没有**这四对，塞进 `primary`/`error` 会把
 * 「连接断了」画成「出错了」。但也不能像改造前那样在屏幕组件里写死色值 ——
 * 那样深色底上只有 3.47 / 3.16:1，小字号读不清（原 `Components.kt:117-118`）。
 *
 * 所以做成**主题的一部分**（每个主题给浅/深两值），既保住「语义色不随明暗随机漂」，
 * 又让对比度随主题各自达标。
 *
 * ## `online`/`offline` 的零漂移
 *
 * 这两个字段的值**一个字都没改**（PAPER 浅 `#2E7D32`/`#C62828` 等四套原值），
 * 由 `AppThemeTest` 的语义色断言继续钉住。新增的 `warn`/`info`/容器色是**纯增量**，
 * 不影响任何现有像素。
 *
 * ## 容器色为什么单独给
 *
 * banner/卡片底需要「同色系的浅底 + 深字」而不是「语义色本身当底」。直接把 `warn`
 * 当底色会得到「黄底黄字」；而用 `warn.copy(alpha=0.1f)` 叠在任意底色上，
 * 对比度**不可预测**（叠在深色底上会发灰）。所以每对容器色都是**算过对比度**的实色，
 * 见 [okContainer] 等字段的注释。
 *
 * 判据：容器内文字 ≥ 4.5:1（AA 正文），容器 vs 页面底 ≥ 1.15:1（肉眼可察觉）。
 * 32 组色对全部实测达标，数字见 `docs/project/` 的主题实施记录。
 */
data class SemanticColors(
    /** 绿 · 成功 / 已连接。四套主题各自取值，**保持改造前的原值**。 */
    val online: Color,
    /** 红 · 失败 / 已断开。四套主题各自取值，**保持改造前的原值**。 */
    val offline: Color,
    /** 黄 · 待确认 / 需批准。浅深两套统一值，四套主题共用（实测均达标）。 */
    val warn: Color,
    /** 蓝 · 中性信息 / 工具名 / 代码高亮。浅深两套统一值。 */
    val info: Color,
    /** 成功态容器底（浅绿）。 */
    val okContainer: Color,
    /** 成功态容器内文字/图标（深绿）。 */
    val onOkContainer: Color,
    /** 警告态容器底（浅黄）。 */
    val warnContainer: Color,
    /** 警告态容器内文字/图标（深棕）。 */
    val onWarnContainer: Color,
    /** 失败态容器底（浅红）。 */
    val errContainer: Color,
    /** 失败态容器内文字/图标（深红）。 */
    val onErrContainer: Color,
    /** 信息态容器底（浅青）。 */
    val infoContainer: Color,
    /** 信息态容器内文字/图标（深青）。 */
    val onInfoContainer: Color,
) {
    /** 成功语义的别名：`online` 在「连接状态」语境读着自然，在「操作成功」语境不自然。 */
    val ok: Color get() = online

    /** 失败语义的别名：同上，`offline` 只管连接，`err` 管一切失败。 */
    val err: Color get() = offline
}

/**
 * 浅色语义色。
 *
 * `online`/`offline` 是**原值不动**（零漂移）；`warn`/`info` 与四对容器色是新增，
 * 四套主题共用同一组值 —— 因为四套浅色底的相对亮度都在 0.90~1.00 之间，
 * 实测这组值在最暖的 WARM `#FAF6EF` 与最白的 INK `#FFFFFF` 上**都 ≥ 4.5:1**，
 * 没必要为每套主题各配一份（配了反而更容易漂）。
 */
private fun semanticOf(theme: AppTheme): SemanticColors {
    val onlineOffline = when (theme) {
        // PAPER 浅色沿用改造前的绿/红（它们原本就 ≥4.5：4.86 / 5.33）；
        // 深色的这对是本次**唯一会改变手机端像素**的地方，属于报告里的可读性修复。
        AppTheme.PAPER -> Color(0xFF2E7D32) to Color(0xFFC62828)
        AppTheme.WARM -> Color(0xFF2F6B2A) to Color(0xFFA32F1F)
        AppTheme.MIST -> Color(0xFF2A6B45) to Color(0xFFA32F1F)
        AppTheme.INK -> Color(0xFF0F6B22) to Color(0xFFB3150C)
    }
    return SemanticColors(
        online = onlineOffline.first,
        offline = onlineOffline.second,
        // 琥珀：白底上 4.66（WARM 最差）~ 5.02（INK）
        warn = Color(0xFFB45309),
        // 青蓝：白底上 4.97 ~ 5.36
        info = Color(0xFF0E7490),
        okContainer = Color(0xFFC6F0D0),
        onOkContainer = Color(0xFF14532D),
        warnContainer = Color(0xFFFBE3A8),
        onWarnContainer = Color(0xFF78350F),
        errContainer = Color(0xFFF9C9C9),
        onErrContainer = Color(0xFF7F1D1D),
        infoContainer = Color(0xFFB8E9F2),
        onInfoContainer = Color(0xFF164E63),
    )
}

/**
 * 深色语义色。
 *
 * `online`/`offline` 原值不动；`warn`/`info` 用**荧光档**（为暗底设计，白底上会失明）；
 * 容器色是「比页面底亮一档」的实色 —— 深色界面的容器不能靠「更黑」区分，
 * 只能靠「更亮」，实测 1.15~1.69:1，肉眼可辨。
 */
private fun semanticOfDark(theme: AppTheme): SemanticColors {
    val onlineOffline = when (theme) {
        AppTheme.PAPER -> Color(0xFF7FD48A) to Color(0xFFFF8A80)
        AppTheme.WARM -> Color(0xFF9DDC9A) to Color(0xFFFF9E8C)
        AppTheme.MIST -> Color(0xFF8EDCA8) to Color(0xFFFF9E8C)
        AppTheme.INK -> Color(0xFF7FE08C) to Color(0xFFFF8F84)
    }
    return SemanticColors(
        online = onlineOffline.first,
        offline = onlineOffline.second,
        // 暗底上 10.15 ~ 12.58
        warn = Color(0xFFFBBF24),
        // 暗底上 10.61 ~ 12.60
        info = Color(0xFF7DD3FC),
        okContainer = Color(0xFF1E3A2A),
        onOkContainer = Color(0xFFBBF7D0),
        warnContainer = Color(0xFF3D2A08),
        onWarnContainer = Color(0xFFFDE68A),
        errContainer = Color(0xFF421717),
        onErrContainer = Color(0xFFFECACA),
        infoContainer = Color(0xFF0C3448),
        onInfoContainer = Color(0xFFBAE6FD),
    )
}

/**
 * 一套主题的完整外观：明暗两份色板 + 两套语义色。
 *
 * [dark] 传 `true` 时同时取深色色板与深色语义色 —— 两者**必须同源**，
 * 否则会出现「背景变成年轻主题、状态点还是旧主题的绿」这种半截状态。
 */
data class ThemePalette(
    val light: ColorScheme,
    val dark: ColorScheme,
    val lightSemantics: SemanticColors,
    val darkSemantics: SemanticColors,
) {
    fun colorScheme(darkTheme: Boolean): ColorScheme = if (darkTheme) dark else light

    fun semantics(darkTheme: Boolean): SemanticColors =
        if (darkTheme) darkSemantics else lightSemantics
}

/** 主题 → 完整外观。**PAPER 必须逐字段等于改造前的配色**（见文件头注释）。 */
internal fun paletteOf(theme: AppTheme): ThemePalette = when (theme) {
    AppTheme.PAPER -> ThemePalette(PaperLight, PaperDark, semanticOf(theme), semanticOfDark(theme))
    AppTheme.WARM -> ThemePalette(WarmLight, WarmDark, semanticOf(theme), semanticOfDark(theme))
    AppTheme.MIST -> ThemePalette(MistLight, MistDark, semanticOf(theme), semanticOfDark(theme))
    AppTheme.INK -> ThemePalette(InkLight, InkDark, semanticOf(theme), semanticOfDark(theme))
}
