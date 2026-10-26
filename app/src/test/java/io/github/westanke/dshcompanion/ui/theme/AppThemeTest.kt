package io.github.westanke.dshcompanion.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.westanke.dshcompanion.data.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.pow

/**
 * 配色主题注册表的两条铁律。
 *
 * ## 1. 默认主题（PAPER）必须与本机制之前**逐字段相同**（防漂移，本文件最重要的一条）
 *
 * `dispatchDefaultThemeIsByteIdenticalToLegacyPalette`：把 PAPER 的两份色板与
 * **独立写死的**旧色值逐个 `Color` 比对。这条测试刻意不 import 生产代码里的任何
 * 色值常量 —— 它自带一份「改造前 Theme.kt 的抄本」，两边分别演进才能真的发现漂移。
 * 谁在 PAPER 里动一个色值（哪怕只是把 `Color.White` 换成 `0xFFFFFFFF` 之外的写法），
 * 这条就会红。
 *
 * 为什么这条比截图对比更可靠：截图会受渲染、抗锯齿、压缩影响，肉眼看不出 1/255 的差别；
 * 而 `Color` 是 ARGB 整数，逐字段比对是精确的。
 *
 * ## 2. 每套主题的关键色对必须过 WCAG AA（4.5:1）
 *
 * 判据用**正文** 4.5:1，不是大字 3:1 —— 本 App 正文 15sp、次要文字 12–14sp，都不算大字。
 * 比值按 WCAG 2.1 的相对亮度公式本地计算（sRGB 线性化 + 0.2126/0.7152/0.0722 加权）。
 *
 * **PAPER 被排除在对比度断言之外，理由是它是「现状」**：它有四处不达标
 * （`tertiary` on background 3.84、`onTertiary/tertiary` 4.05、`outline` on background 3.32、
 * `onSurfaceVariant` on `surfaceContainerHighest` 3.64）。修它们属于「会改手机端」的
 * 批准项（报告 A-4 / O-08 / O-09），本次不动 —— 零漂移优先。
 * 这条排除是**显式且带理由的**，不是漏测：见 `paperLegacyShortfallsAreKnown`，
 * 它反过来断言「那四处确实还不达标」，于是将来真去修时会因为这条也变红而被迫一起改注释。
 */
class AppThemeTest {

    // ───────────── 一、PAPER 零漂移 ─────────────

    /**
     * 改造前 `Theme.kt` 的浅色色板抄本（**独立写死**，不从生产代码 import）。
     *
     * 顺序与旧文件的 `lightColorScheme(...)` 参数顺序一致，方便人工对账。
     */
    private val legacyLight = mapOf(
        "primary" to 0xFF131313UL,
        "onPrimary" to 0xFFFFFFFFUL,
        "primaryContainer" to 0xFFE0F2EFUL,
        "onPrimaryContainer" to 0xFF073B36UL,
        "secondary" to 0xFF006A60UL,
        "onSecondary" to 0xFFFFFFFFUL,
        "secondaryContainer" to 0xFFCDEDE7UL,
        "onSecondaryContainer" to 0xFF073B36UL,
        "tertiary" to 0xFFC6613FUL,
        "onTertiary" to 0xFFFFFFFFUL,
        "tertiaryContainer" to 0xFFFFDBCFUL,
        "onTertiaryContainer" to 0xFF542013UL,
        "background" to 0xFFF9F9F7UL,
        "onBackground" to 0xFF131313UL,
        "surface" to 0xFFF9F9F7UL,
        "onSurface" to 0xFF131313UL,
        "surfaceVariant" to 0xFFF0EFECUL,
        "onSurfaceVariant" to 0xFF74726DUL,
        "surfaceDim" to 0xFFE7E5E0UL,
        "surfaceBright" to 0xFFFFFFFFUL,
        "surfaceContainerLowest" to 0xFFFFFFFFUL,
        "surfaceContainerLow" to 0xFFF5F4F1UL,
        "surfaceContainer" to 0xFFF0EFECUL,
        "surfaceContainerHigh" to 0xFFE9E7E2UL,
        "surfaceContainerHighest" to 0xFFE2E0DAUL,
        "inverseSurface" to 0xFF383835UL,
        "inverseOnSurface" to 0xFFF9F9F7UL,
        "surfaceTint" to 0xFF006A60UL,
        "outline" to 0xFF8B8983UL,
        "outlineVariant" to 0xFFD7D5CFUL,
        "error" to 0xFFB3261EUL,
        "onError" to 0xFFFFFFFFUL,
        "errorContainer" to 0xFFFFDAD6UL,
        "onErrorContainer" to 0xFF410002UL,
    )

    /** 改造前 `Theme.kt` 的深色色板抄本。 */
    private val legacyDark = mapOf(
        "primary" to 0xFFF3F2EDUL,
        "onPrimary" to 0xFF131313UL,
        "primaryContainer" to 0xFF164B45UL,
        "onPrimaryContainer" to 0xFFCEF2ECUL,
        "secondary" to 0xFF83D5C8UL,
        "onSecondary" to 0xFF003730UL,
        "secondaryContainer" to 0xFF164B45UL,
        "onSecondaryContainer" to 0xFFCDEDE7UL,
        "tertiary" to 0xFFFFB59BUL,
        "onTertiary" to 0xFF5D1B0AUL,
        "tertiaryContainer" to 0xFF75321FUL,
        "onTertiaryContainer" to 0xFFFFDBCFUL,
        "background" to 0xFF181816UL,
        "onBackground" to 0xFFF0EFEAUL,
        "surface" to 0xFF181816UL,
        "onSurface" to 0xFFF0EFEAUL,
        "surfaceVariant" to 0xFF2A2A27UL,
        "onSurfaceVariant" to 0xFFBEBBB3UL,
        "surfaceDim" to 0xFF121210UL,
        "surfaceBright" to 0xFF383834UL,
        "surfaceContainerLowest" to 0xFF10100FUL,
        "surfaceContainerLow" to 0xFF1D1D1BUL,
        "surfaceContainer" to 0xFF22221FUL,
        "surfaceContainerHigh" to 0xFF2B2B28UL,
        "surfaceContainerHighest" to 0xFF343430UL,
        "inverseSurface" to 0xFFF0EFEAUL,
        "inverseOnSurface" to 0xFF131313UL,
        "surfaceTint" to 0xFF83D5C8UL,
        "outline" to 0xFF918F88UL,
        "outlineVariant" to 0xFF41413DUL,
        "error" to 0xFFFFB4ABUL,
        "onError" to 0xFF690005UL,
        "errorContainer" to 0xFF93000AUL,
        "onErrorContainer" to 0xFFFFDAD6UL,
    )

    /**
     * **防漂移核心断言**：PAPER 的每个 `Color` 必须等于改造前的值。
     *
     * 失败信息逐字段列出差异（字段名 / 期望 / 实际），不需要再猜是哪一个。
     */
    @Test
    fun `PAPER 与本机制之前的配色逐字段相同`() {
        val palette = paletteOf(AppTheme.PAPER)
        assertSchemeIdentical("PAPER light", palette.light, legacyLight)
        assertSchemeIdentical("PAPER dark", palette.dark, legacyDark)
    }

    /** 默认主题必须是 PAPER：枚举顺序若被调整，设置页的默认落点就会变。 */
    @Test
    fun `默认主题是 PAPER 且顺序以 PAPER 打头`() {
        assertEquals(AppTheme.PAPER, AppTheme.entries.first())
        assertEquals(
            listOf(AppTheme.PAPER, AppTheme.WARM, AppTheme.MIST, AppTheme.INK),
            AppTheme.entries,
        )
    }

    // ───────────── 二、各主题的对比度 ─────────────

    /**
     * 新加的三套主题：**所有关键色对 ≥ 4.5:1 且 ≤ 21:1**（AA 正文）。
     *
     * 取色对口径：
     * - 文本角色：`onX` on `X`，以及正文/次要文字 on 它实际会落的几种底色。
     * - `secondary` / `tertiary` 本身常被当**前景**用（警告横幅、图标、强调字），
     *   所以也按「前景 on background」验。
     * - `outline` 是**边框不是文字**，按 WCAG 1.4.11 的 3:1 验（见下面单独一条）。
     */
    @Test
    fun `新增主题的关键色对全部达到 WCAG AA`() {
        val problems = mutableListOf<String>()
        for (theme in listOf(AppTheme.WARM, AppTheme.MIST, AppTheme.INK)) {
            val palette = paletteOf(theme)
            for (dark in listOf(false, true)) {
                val scheme = palette.colorScheme(dark)
                val label = "$theme ${if (dark) "dark" else "light"}"
                for ((name, fg, bg) in textPairs(scheme)) {
                    val ratio = contrastRatio(fg, bg)
                    if (ratio < AA_BODY) {
                        problems += "$label · $name = $ratio（要求 ≥ $AA_BODY）"
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            fail("对比度不达标（${problems.size} 处）：\n" + problems.joinToString("\n") { "- $it" })
        }
    }

    /** 边框类角色按 WCAG 1.4.11 的非文本 3:1 验（新增主题同样不得低于它）。 */
    @Test
    fun `新增主题的 outline 达到非文本 3 比 1`() {
        val problems = mutableListOf<String>()
        for (theme in listOf(AppTheme.WARM, AppTheme.MIST, AppTheme.INK)) {
            val palette = paletteOf(theme)
            for (dark in listOf(false, true)) {
                val scheme = palette.colorScheme(dark)
                val ratio = contrastRatio(scheme.outline, scheme.background)
                if (ratio < AA_NON_TEXT) {
                    problems += "$theme ${if (dark) "dark" else "light"} · outline/background = $ratio"
                }
            }
        }
        if (problems.isNotEmpty()) {
            fail("非文本对比度不达标：\n" + problems.joinToString("\n") { "- $it" })
        }
    }

    /**
     * 语义色（连接通/断）：**每套主题的浅/深两态都要 ≥ 4.5:1**。
     *
     * 这条是 O-08 的回归钉子：改造前 PAPER 深色的硬编码绿/红只有 3.47 / 3.16。
     * 现在 PAPER 深色也必须达标 —— 那是本次唯一允许改变手机端像素的地方。
     */
    @Test
    fun `连接语义色在每套主题的明暗两态都达标`() {
        val problems = mutableListOf<String>()
        for (theme in AppTheme.entries) {
            val palette = paletteOf(theme)
            for (dark in listOf(false, true)) {
                val scheme = palette.colorScheme(dark)
                val semantics = palette.semantics(dark)
                val label = "$theme ${if (dark) "dark" else "light"}"
                val online = contrastRatio(semantics.online, scheme.background)
                val offline = contrastRatio(semantics.offline, scheme.background)
                if (online < AA_BODY) problems += "$label · online/background = $online"
                if (offline < AA_BODY) problems += "$label · offline/background = $offline"
            }
        }
        if (problems.isNotEmpty()) {
            fail("连接语义色不达标：\n" + problems.joinToString("\n") { "- $it" })
        }
    }

    /**
     * PAPER 的**已知短板**：这四处今天仍不达 AA，因为修它们会改手机端像素。
     *
     * 为什么要把「不达标」写成断言：它是**显式的债**，且这条测试会在有人真去修好时
     * 变红 —— 提醒他同批更新注释与报告，而不是让一句「PAPER 未全部达标」静静过期。
     */
    @Test
    fun `PAPER 的四处已知不达标必须如实反映`() {
        val light = paletteOf(AppTheme.PAPER).light
        val knownShortfalls = listOf(
            "tertiary on background" to (light.tertiary to light.background),
            "onTertiary on tertiary" to (light.onTertiary to light.tertiary),
            "outline on background" to (light.outline to light.background),
            "onSurfaceVariant on surfaceContainerHighest" to
                (light.onSurfaceVariant to light.surfaceContainerHighest),
        )
        for ((name, pair) in knownShortfalls) {
            val ratio = contrastRatio(pair.first, pair.second)
            assertTrue(
                "$name 现在 = $ratio，已经达标了 —— 请同批删掉这条断言与报告里的「已知短板」，" +
                    "不要让注释过期",
                ratio < AA_BODY,
            )
        }
    }

    /**
     * 四对语义色（绿/黄/红/蓝）在**每套主题的明暗两态**都要 ≥ 4.5:1（路线 C，2026-10-26）。
     *
     * 为什么这条必须存在：`warn`/`info` 是新增 token，四套主题**共用同一组值**
     * （因为四套浅色底的相对亮度都在 0.90~1.00 之间）。共用意味着**只要有人调一次色值，
     * 就可能只对 PAPER 达标、对 WARM 掉到 4.4** —— 肉眼几乎看不出，但已经违反 AA。
     * 实测 WARM 是最紧的那个底（`#FAF6EF`），所以这条测试本质上是在钉 WARM。
     *
     * 历史教训：规范初稿的 `#DC2626` 在 WARM 上只有 **4.48**、MIST 上 4.49，
     * 差 0.01 就是不合格。没有这条断言，这种差距会一路带到真机上。
     */
    @Test
    fun `四对语义色在每套主题的明暗两态都达标`() {
        val problems = mutableListOf<String>()
        for (theme in AppTheme.entries) {
            val palette = paletteOf(theme)
            for (dark in listOf(false, true)) {
                val scheme = palette.colorScheme(dark)
                val sem = palette.semantics(dark)
                val label = "$theme ${if (dark) "dark" else "light"}"
                val pairs = listOf(
                    "ok" to sem.ok,
                    "err" to sem.err,
                    "warn" to sem.warn,
                    "info" to sem.info,
                )
                for ((name, color) in pairs) {
                    val ratio = contrastRatio(color, scheme.background)
                    if (ratio < AA_BODY) problems += "$label · $name/background = $ratio"
                }
            }
        }
        if (problems.isNotEmpty()) {
            fail("四对语义色不达标：\n" + problems.joinToString("\n") { "- $it" })
        }
    }

    /**
     * 容器色两件事都要成立：**内文字 ≥ 4.5**、**容器 vs 页面底 ≥ 1.15**。
     *
     * 只测前者是不够的 —— 容器色与底色同亮度时，文字读得清，但**整块 banner 看不见**。
     * 实测把容器色按「同色系浅底」的直觉取值（`#DCFCE7` 之类）时，
     * vs 底的对比度只有 **1.02~1.16**，深色下更是 1.10，等于白加一层。
     * 所以两档都调深/调亮过，1.15 是「肉眼刚好能看出有块底色」的下限。
     */
    @Test
    fun `语义容器色内文字达标且容器本身可辨识`() {
        val problems = mutableListOf<String>()
        for (theme in AppTheme.entries) {
            val palette = paletteOf(theme)
            for (dark in listOf(false, true)) {
                val scheme = palette.colorScheme(dark)
                val sem = palette.semantics(dark)
                val label = "$theme ${if (dark) "dark" else "light"}"
                val containers = listOf(
                    Triple("ok", sem.okContainer, sem.onOkContainer),
                    Triple("warn", sem.warnContainer, sem.onWarnContainer),
                    Triple("err", sem.errContainer, sem.onErrContainer),
                    Triple("info", sem.infoContainer, sem.onInfoContainer),
                )
                for ((name, bg, fg) in containers) {
                    val text = contrastRatio(fg, bg)
                    if (text < AA_BODY) problems += "$label · $name 容器内文字 = $text"
                    val vsPage = contrastRatio(bg, scheme.background)
                    if (vsPage < CONTAINER_MIN) {
                        problems += "$label · $name 容器 vs 页面底 = $vsPage（应 ≥ $CONTAINER_MIN）"
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            fail("语义容器色不达标：\n" + problems.joinToString("\n") { "- $it" })
        }
    }

    // ───────────── 三、主题与明暗的装配 ─────────────

    /** 同一主题的 light / dark 必须是**不同的**色板（别把深色也指到浅色上）。 */
    @Test
    fun `每套主题的明暗两份色板不相同`() {
        for (theme in AppTheme.entries) {
            val palette = paletteOf(theme)
            assertTrue(
                "$theme 的浅色与深色背景相同 —— 深色那份大概指错了",
                palette.light.background != palette.dark.background,
            )
        }
    }

    /**
     * `colorScheme(dark)` / `semantics(dark)` 必须**同源**。
     *
     * 若两者各判各的，会出现「背景换了主题、状态点还是旧主题的绿」这种半截状态。
     */
    @Test
    fun `色板与语义色随明暗同步切换`() {
        for (theme in AppTheme.entries) {
            val palette = paletteOf(theme)
            assertEquals(palette.light, palette.colorScheme(darkTheme = false))
            assertEquals(palette.dark, palette.colorScheme(darkTheme = true))
            assertEquals(palette.lightSemantics, palette.semantics(darkTheme = false))
            assertEquals(palette.darkSemantics, palette.semantics(darkTheme = true))
        }
    }

    // ───────────── 辅助 ─────────────

    /** 文本类色对（前景 on 底色）。底色取它会实际落的几种 surface 角色。 */
    private fun textPairs(s: ColorScheme): List<Triple<String, Color, Color>> = listOf(
        Triple("onBackground/background", s.onBackground, s.background),
        Triple("onSurface/background", s.onSurface, s.background),
        Triple("onSurface/surfaceContainer", s.onSurface, s.surfaceContainer),
        Triple("onSurface/surfaceContainerHighest", s.onSurface, s.surfaceContainerHighest),
        Triple("onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant),
        Triple("onSurfaceVariant/background", s.onSurfaceVariant, s.background),
        Triple("onSurfaceVariant/surfaceContainerHighest", s.onSurfaceVariant, s.surfaceContainerHighest),
        Triple("onPrimary/primary", s.onPrimary, s.primary),
        Triple("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer),
        Triple("onSecondary/secondary", s.onSecondary, s.secondary),
        Triple("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
        Triple("onTertiary/tertiary", s.onTertiary, s.tertiary),
        Triple("onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer),
        Triple("onError/error", s.onError, s.error),
        Triple("onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer),
        // 这两个角色常被当**前景**用（警告条图标、强调文字），也得按正文标准验。
        Triple("secondary/background", s.secondary, s.background),
        Triple("tertiary/background", s.tertiary, s.background),
        Triple("inverseOnSurface/inverseSurface", s.inverseOnSurface, s.inverseSurface),
    )

    /** 逐字段比对：PAPER 的某个角色若与旧值不同，就把字段名、期望、实际一起报出来。 */
    private fun assertSchemeIdentical(
        label: String,
        actual: ColorScheme,
        expected: Map<String, ULong>,
    ) {
        val problems = mutableListOf<String>()
        for ((field, expectedArgb) in expected) {
            val actualColor = schemeField(actual, field)
            if (actualColor == null) {
                problems += "$label · `$field` 在 ColorScheme 里取不到（字段名写错了？）"
                continue
            }
            // 用 `toArgb()` 而不是 `.value`：后者是 **打包后的 ULong**（高位带
            // ColorSpace 编码），拿它比色值会把所有颜色都比成 0 —— 一条永远红的假测试。
            // `toArgb()` 才是「这个颜色最终画到屏幕上是什么 ARGB」的权威口径。
            val actualArgb = actualColor.toArgb().toUInt().toULong()
            if (actualArgb != expectedArgb) {
                problems += "$label · `$field` 期望 #${hex(expectedArgb)}，实际 #${hex(actualArgb)}"
            }
        }
        if (problems.isNotEmpty()) {
            fail("$label 的配色发生漂移（${problems.size} 处）—— 默认主题必须与改造前逐字段相同：\n" +
                problems.joinToString("\n") { "- $it" })
        }
    }

    /**
     * 按名字取 `ColorScheme` 的字段。
     *
     * 用显式的 when 而不是反射：反射在单测里能跑但会绕过编译器检查 ——
     * `ColorScheme` 将来新增角色时反射版会静默漏掉它，when 版会编译不过（正是我们要的）。
     */
    private fun schemeField(s: ColorScheme, name: String): Color? = when (name) {
        "primary" -> s.primary
        "onPrimary" -> s.onPrimary
        "primaryContainer" -> s.primaryContainer
        "onPrimaryContainer" -> s.onPrimaryContainer
        "secondary" -> s.secondary
        "onSecondary" -> s.onSecondary
        "secondaryContainer" -> s.secondaryContainer
        "onSecondaryContainer" -> s.onSecondaryContainer
        "tertiary" -> s.tertiary
        "onTertiary" -> s.onTertiary
        "tertiaryContainer" -> s.tertiaryContainer
        "onTertiaryContainer" -> s.onTertiaryContainer
        "background" -> s.background
        "onBackground" -> s.onBackground
        "surface" -> s.surface
        "onSurface" -> s.onSurface
        "surfaceVariant" -> s.surfaceVariant
        "onSurfaceVariant" -> s.onSurfaceVariant
        "surfaceDim" -> s.surfaceDim
        "surfaceBright" -> s.surfaceBright
        "surfaceContainerLowest" -> s.surfaceContainerLowest
        "surfaceContainerLow" -> s.surfaceContainerLow
        "surfaceContainer" -> s.surfaceContainer
        "surfaceContainerHigh" -> s.surfaceContainerHigh
        "surfaceContainerHighest" -> s.surfaceContainerHighest
        "inverseSurface" -> s.inverseSurface
        "inverseOnSurface" -> s.inverseOnSurface
        "surfaceTint" -> s.surfaceTint
        "outline" -> s.outline
        "outlineVariant" -> s.outlineVariant
        "error" -> s.error
        "onError" -> s.onError
        "errorContainer" -> s.errorContainer
        "onErrorContainer" -> s.onErrorContainer
        else -> null
    }

    /** ARGB → `RRGGBB`（不带 alpha，比对失败时更易读）。 */
    private fun hex(argb: ULong): String = argb.toString(16).uppercase().padStart(8, '0').takeLast(6)

    private companion object {
        /** WCAG AA 正文（小字号）门槛。 */
        const val AA_BODY = 4.5

        /** WCAG 1.4.11 非文本（边框/图标）门槛。 */
        const val AA_NON_TEXT = 3.0

        /**
         * 语义容器色 vs 页面底的最低可辨识度。
         *
         * 不是 WCAG 门槛（WCAG 不要求「容器底必须可辨」），而是**工程下限**：
         * 容器色按「同色系浅底」的直觉取值时实测只有 1.02~1.16，肉眼几乎看不出有块底色，
         * banner 就退化成了「一段换了颜色的文字」。1.15 是实测出的可察觉下限。
         */
        const val CONTAINER_MIN = 1.15

        /**
         * WCAG 2.1 对比度：[相对亮度](https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum.html)。
         *
         * sRGB 线性化：`c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ^ 2.4`，
         * 再按 0.2126 / 0.7152 / 0.0722 加权。
         * 这里与 Compose 的 `Color.luminance()` 是同一套公式，但**自己写一份**：
         * 被测的判据不该依赖被测框架的实现（否则公式写错时两边一起错，测试照样绿）。
         */
        fun contrastRatio(a: Color, b: Color): Double {
            val la = luminance(a)
            val lb = luminance(b)
            val high = maxOf(la, lb)
            val low = minOf(la, lb)
            return round2((high + 0.05) / (low + 0.05))
        }

        fun luminance(color: Color): Double {
            val r = linearize(color.red.toDouble())
            val g = linearize(color.green.toDouble())
            val b = linearize(color.blue.toDouble())
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }

        fun linearize(channel: Double): Double =
            if (channel <= 0.03928) channel / 12.92
            else ((channel + 0.055) / 1.055).pow(2.4)

        /** 保留两位小数，让失败信息里的数字与报告一致。 */
        fun round2(value: Double): Double = (value * 100).toInt() / 100.0
    }
}
