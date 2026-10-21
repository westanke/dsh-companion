package io.github.westanke.dshcompanion.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 宽度档位：把 `App.kt` 里那个裸 `maxWidth >= 840.dp` 魔数收敛成**可读、可测、可扩展**的判据。
 *
 * ## 为什么要有这个文件
 *
 * 现状是一个布尔 + 一个魔数，谁都不知道 840 是从哪来的、改成 900 会有什么后果。
 * 收成具名常量 + 纯函数之后：阈值有名字、边界有单测、将来上三栏只需要多一档——
 * 而不是在下游到处 grep `840.dp`。
 *
 * ## 档位定义（对齐 Material 3 window size classes）
 *
 * | 档 | 宽度 | 典型设备 |
 * |---|---|---|
 * | [Compact] | < 600dp | 手机竖屏（360–412dp） |
 * | [Medium] | 600–839dp | 手机横屏 / 小平板分屏 |
 * | [Expanded] | 840–1199dp | 平板横屏（**既有 `wide` 的下界**） |
 * | [Large] | ≥ 1200dp | 12" 平板横屏 / 桌面窗口 |
 *
 * ## 与既有行为的关系（**必须二进制等价**）
 *
 * 840dp 作 Expanded 下界是**刻意保留**的：改造前 `App.kt` 就是
 * `val wide = maxWidth >= 840.dp`。所以
 * `paneModeFor(width) >= PaneMode.Expanded` 与原来那个布尔**完全等价** ——
 * 手机（Compact / Medium）一律 `wide=false`，既有布局一个字符都不变。
 * 这条等价性由 `PaneModeTest` 在边界上钉住。
 *
 * 为什么**不**引入 `material3-adaptive` 的 `WindowSizeClass`：那是新增两个 artifact、
 * 一次依赖变更，而它带来的主要能力（折叠屏姿态）本项目一时用不上。先做零依赖版（O-02），
 * 真需要三栏时再评估升级（O-01）。
 */
internal enum class PaneMode {
    Compact,
    Medium,
    Expanded,
    Large,
}

/** Compact 上界（M3 的 600dp 惯例）。 */
internal val CompactMaxWidth: Dp = 600.dp

/** Expanded 下界 —— 与既有的「手机 vs 平板」分界**必须是同一个值**（840dp）。 */
internal val ExpandedMinWidth: Dp = 840.dp

/** Large 下界（Jetpack WindowManager 1.5 的 large 档起点）。 */
internal val LargeMinWidth: Dp = 1200.dp

/**
 * 按可用宽度取档位。
 *
 * 纯函数：不读 LocalWindowInfo、不碰 CompositionLocal，所以能直接单测边界。
 * 判据一律用**闭开区间**（`>= 下界` 归入上一档），与原布尔语义一致。
 */
internal fun paneModeFor(width: Dp): PaneMode = when {
    width >= LargeMinWidth -> PaneMode.Large
    width >= ExpandedMinWidth -> PaneMode.Expanded
    width >= CompactMaxWidth -> PaneMode.Medium
    else -> PaneMode.Compact
}

/**
 * 是否走「平板（wide）」布局 —— 与改造前的 `maxWidth >= 840.dp` **完全等价**。
 *
 * 单独提一个函数而不是让调用点自己写比较：`App.kt` 之外将来若有第二处判 wide，
 * 两处判据必须同源，否则会出现「抽屉按平板排、内容还按手机排」的错位。
 */
internal fun isWideLayout(width: Dp): Boolean = paneModeFor(width) >= PaneMode.Expanded
