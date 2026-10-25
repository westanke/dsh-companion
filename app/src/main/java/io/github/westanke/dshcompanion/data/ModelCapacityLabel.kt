package io.github.westanke.dshcompanion.data

import java.util.Locale

/**
 * 把上下文窗口的 token 数压成给人看的短标签。
 *
 * 桥 v2.7.0 起 `/chat/sessions/{id}/models` 会带 `contextWindow`（DSH 0.2.1 的
 * `deepseek-flash` 是 1 048 576），原样打印会是七位数，在模型行里挤成一团。
 *
 * 阈值取 1024 而不是 1000：token 数本来就是 2 的幂附近的值，用 1024 才能让
 * 1048576 干净地显示成 `1M`；用 1000 会得到 `1049K` 这种没意义的中间形态。
 *
 * 小数只在小于 10 时保留一位（`1.5M`），再大就取整 —— 容量是量级信息，
 * 精确到个位既不准也没人看。
 */
internal fun formatContextWindow(tokens: Long): String {
    val kilo = 1024L
    return when {
        tokens >= kilo * kilo -> {
            val value = tokens.toDouble() / (kilo * kilo)
            if (value < 10) String.format(Locale.US, "%.1fM", value) else String.format(Locale.US, "%.0fM", value)
        }
        tokens >= kilo -> {
            val value = tokens.toDouble() / kilo
            if (value < 10) String.format(Locale.US, "%.1fK", value) else String.format(Locale.US, "%.0fK", value)
        }
        else -> tokens.toString()
    }
}
