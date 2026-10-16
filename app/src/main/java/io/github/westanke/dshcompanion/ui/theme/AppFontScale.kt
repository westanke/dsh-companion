package io.github.westanke.dshcompanion.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/**
 * 应用内字号缩放：把 `LocalDensity` 换成「系统密度 × 应用倍数」的那一份。
 *
 * ## 为什么不能简单地把 sp 全部乘一遍
 *
 * 界面里大量 `sp` 与 `dp` 是**成对**出现的（图标 20.dp 配 20.sp 的文字、
 * 药丸标签的 `padding(horizontal = 10.dp)` 配 `labelMedium`）。只放大 sp，
 * 图标与内边距原地不动，结果是「字大了、框没大」——文字被裁、标签错位。
 * 正确做法是放大 **fontScale**（sp → px 的系数），让 Compose 自己去换算，
 * 同时**把 `TextUnit.toDp()` 与 `Dp.toSp()` 两条反向换算钉回基准**，
 * 否则反向换算会把「10.dp 的内边距」按放大后的比例算回，等于把间距也放大了两遍。
 *
 * ## 与系统字号的关系
 *
 * 这里是**相乘**，不是覆盖：`base.fontScale` 是用户在系统设置里调的字号。
 * 应用内倍数只在此基础上再乘一层——既尊重系统无障碍设置，也允许「只让这个 App 字大」。
 *
 * 取值为 [normalizeFontScale] 的结果（0.8–1.5），非法值已在偏好层兜底。
 */
private class ScaledDensity(
    private val base: Density,
    private val appScale: Float,
) : Density by base {

    /**
     * 相乘而非覆盖。注意 `Density by base` 会把其余成员（density / toPx / roundToPx…）
     * 原样委托给系统密度，只有这一项被改写——这正是我们要的最小切口。
     */
    override val fontScale: Float = base.fontScale * appScale

    /** 反向换算保持基准：sp → dp 不该随字号放大而变大（间距/图标尺寸的基准不变）。 */
    override fun TextUnit.toDp(): Dp = with(base) { this@toDp.toDp() }

    /** 同上，dp → sp 也钉回基准。 */
    override fun Dp.toSp(): TextUnit = with(base) { this@toSp.toSp() }
}

/**
 * 给 [content] 注入应用内字号缩放。
 *
 * [fontScale] 传 1f 时**不替换 Density**（直接透传）：省掉一次 CompositionLocal
 * 变化，也避免默认路径上任何换算差异——1f 必须是彻底的空操作。
 */
@Composable
internal fun AppFontScale(fontScale: Float, content: @Composable () -> Unit) {
    if (fontScale == 1f) {
        content()
        return
    }
    val base = LocalDensity.current
    val scaled = remember(base, fontScale) { ScaledDensity(base, fontScale) }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}
