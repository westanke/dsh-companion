package io.github.westanke.dshcompanion.data

/**
 * cron 表达式的**人话预览**。
 *
 * 为什么需要它：契约收的是五段 Vixie 表达式，而用户要输的是「每天 08:00」。直接丢一个
 * `0 8 * * *` 输入框，用户只能靠猜；猜错时服务端回 `400 BODY_INVALID`，等于什么都没说。
 * 这里在客户端先做一层「语法是否成立 + 它到底是什么意思」，输入时就地给提示。
 *
 * ## 刻意只覆盖常见形态
 *
 * 五段 cron 的表达力远大于这里列出的形态（范围 `1-5`、列表 `1,3,5`、步进（`*` 后接 `/7`）、
 * `L`/`W`/`#` 等扩展）。**不认识的形态返回 [CronHint.Custom]**，界面照样允许提交 ——
 * 判据的权威始终是服务端：客户端只负责「看得懂的给人话，看不懂的不拦人」。
 * 反过来（本地拒绝合法表达式）才是最坏的结果：用户被一个不懂装懂的客户端拦住。
 */
internal sealed interface CronHint {
    /** 每分钟（`* * * * *`）。 */
    data object EveryMinute : CronHint

    /** 每 N 分钟（分钟位是步进写法，如 每 15 分钟）。 */
    data class EveryMinutes(val minutes: Int) : CronHint

    /** 每小时的第 M 分钟（`30 * * * *`）。 */
    data class Hourly(val minute: Int) : CronHint

    /** 每 N 小时的第 M 分钟（小时位是步进写法）。 */
    data class EveryHours(val hours: Int, val minute: Int) : CronHint

    /** 每天固定时刻（`0 8 * * *`）。 */
    data class Daily(val hour: Int, val minute: Int) : CronHint

    /** 每周固定几天（`0 8 * * 1-5`）；[days] 0=周日 … 6=周六。 */
    data class Weekly(val days: List<Int>, val hour: Int, val minute: Int) : CronHint

    /** 每月固定日（`0 8 1 * *`）。 */
    data class Monthly(val dayOfMonth: Int, val hour: Int, val minute: Int) : CronHint

    /** 看得懂的语法，但没有对应的固定人话模板（原样显示表达式）。 */
    data object Custom : CronHint
}

/** 校验结果。[error] 只在 [valid] = false 时有值，且已是「给人看的」说明（不含行号等内部细节）。 */
internal data class CronValidation(
    val valid: Boolean,
    val hint: CronHint?,
    val error: CronHintError?,
)

/** 本地能判定的语法错误种类。文案由 UI 层映射成中英双语（数据层不拼死字符串）。 */
internal enum class CronHintError {
    /** 不是五段。 */
    FIELD_COUNT,

    /** 某一段不是数字、`*`、步进、`a-b` 或 `a,b,c`。 */
    BAD_FIELD,

    /** 分钟/小时/日/月越界。 */
    OUT_OF_RANGE,
}

/**
 * 解析五段 cron（分 时 日 月 周）。
 *
 * 支持：`*`、`N`、步进、`a-b`、`a,b,c`（列表里每项再按前三种解析）。周允许 0 和 7 都表示周日。
 * 其它扩展语法（`L`、`W`、`#`、`?`）不认，直接 [CronHintError.BAD_FIELD] —— 但**只有**
 * 用户显然在写这些扩展时才可能被误判，而本桥的调度器（node-cron 口径）同样不吃它们。
 */
internal fun parseCron(expression: String): CronValidation {
    val fields = expression.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
    if (fields.size != 5) return CronValidation(false, null, CronHintError.FIELD_COUNT)
    // `L` / `W` / `#` / `?` 这些扩展本文件不解释，但**不拦人**：权威判据在服务端，
    // 客户端拒绝一个合法表达式才是最坏的结果。
    if (fields.any(::looksExtended)) return CronValidation(true, CronHint.Custom, null)

    val parsed = listOf(
        parseField(fields[0], 0, 59),
        parseField(fields[1], 0, 23),
        parseField(fields[2], 1, 31),
        parseField(fields[3], 1, 12),
        parseField(fields[4], 0, 7),
    )
    parsed.forEach { if (it is FieldParse.Error) return bad(it.error) }
    @Suppress("UNCHECKED_CAST")
    val values = parsed.map { (it as FieldParse.Ok).values }

    val hint = describe(values[0], values[1], values[2], values[3], values[4])
    return CronValidation(true, hint, null)
}

private fun bad(error: CronHintError) = CronValidation(false, null, error)

/** 单段的解析结果：要么是允许值集合，要么是「哪一类错」。 */
private sealed interface FieldParse {
    data class Ok(val values: Set<Int>) : FieldParse
    data class Error(val error: CronHintError) : FieldParse
}

/**
 * 扩展语法标记：出现这些字符就不硬判，转 [CronHint.Custom]。
 *
 * 只认 cron 的扩展字母，不认任意字母 —— 否则 `0 8 * * mon` 这种真笔误也会被放过，
 * 用户要到服务端 400 才知道写错了。
 */
private fun looksExtended(field: String): Boolean {
    val upper = field.uppercase()
    return ('L' in upper) || ('W' in upper) || ('#' in upper) || ('?' in upper)
}

private fun parseField(field: String, min: Int, max: Int): FieldParse {
    val values = mutableSetOf<Int>()
    for (item in field.split(',')) {
        if (item.isEmpty()) return FieldParse.Error(CronHintError.BAD_FIELD)
        if (item == "*") {
            values += min..max
            continue
        }
        if (item.startsWith("*/")) {
            val step = item.removePrefix("*/").toIntOrNull() ?: return FieldParse.Error(CronHintError.BAD_FIELD)
            if (step <= 0) return FieldParse.Error(CronHintError.BAD_FIELD)
            var value = min
            while (value <= max) {
                values += value
                value += step
            }
            continue
        }
        val range = item.split('-')
        if (range.size == 2) {
            val start = range[0].toIntOrNull() ?: return FieldParse.Error(CronHintError.BAD_FIELD)
            val end = range[1].toIntOrNull() ?: return FieldParse.Error(CronHintError.BAD_FIELD)
            if (start > end) return FieldParse.Error(CronHintError.BAD_FIELD)
            // 越界单独报：用户看到「分钟只能 0-59」比看到「写法认不出来」有用得多。
            if (start < min || end > max) return FieldParse.Error(CronHintError.OUT_OF_RANGE)
            values += start..end
            continue
        }
        val single = item.toIntOrNull() ?: return FieldParse.Error(CronHintError.BAD_FIELD)
        if (single < min || single > max) return FieldParse.Error(CronHintError.OUT_OF_RANGE)
        values += single
    }
    if (values.isEmpty()) return FieldParse.Error(CronHintError.BAD_FIELD)
    return FieldParse.Ok(values)
}

/**
 * 把「允许值集合」翻成人话形态。判断顺序刻意从**最具体**到最一般：
 * 周/日限定 > 小时步进 > 固定时刻 > 全通配。
 */
private fun describe(
    minute: Set<Int>,
    hour: Set<Int>,
    dom: Set<Int>,
    month: Set<Int>,
    dow: Set<Int>,
): CronHint {
    val everyDay = dom.size == 31 && dow.size == 8
    val everyMonth = month.size == 12
    if (!everyMonth) return CronHint.Custom

    // 周限定（`0 8 * * 1-5`）：周字段不是全通配，且日字段通配。
    if (everyDay.not() && dow.size != 8 && dom.size == 31) {
        val singleHour = hour.singleOrNull() ?: return CronHint.Custom
        val singleMinute = minute.singleOrNull() ?: return CronHint.Custom
        val days = dow.map { if (it == 7) 0 else it }.distinct().sorted()
        return CronHint.Weekly(days, singleHour, singleMinute)
    }
    // 月内固定日（`0 8 1 * *`）。
    if (dom.size == 1 && dow.size == 8) {
        val singleHour = hour.singleOrNull() ?: return CronHint.Custom
        val singleMinute = minute.singleOrNull() ?: return CronHint.Custom
        return CronHint.Monthly(dom.single(), singleHour, singleMinute)
    }
    if (!everyDay) return CronHint.Custom

    // 分钟全通配 + 小时全通配 = 每分钟。
    if (minute.size == 60 && hour.size == 24) return CronHint.EveryMinute

    // 小时全通配 + 分钟是步进（分钟位为 步进 写法）。
    if (hour.size == 24) {
        val step = stepOf(minute, 0, 59)
        if (step != null) return if (step == 1) CronHint.EveryMinute else CronHint.EveryMinutes(step)
        val singleMinute = minute.singleOrNull() ?: return CronHint.Custom
        return CronHint.Hourly(singleMinute)
    }

    // 分钟固定 + 小时是步进。
    val singleMinute = minute.singleOrNull() ?: return CronHint.Custom
    val hourStep = stepOf(hour, 0, 23)
    if (hourStep != null) return CronHint.EveryHours(hourStep, singleMinute)

    // 固定时刻（`0 8 * * *`）。
    val singleHour = hour.singleOrNull() ?: return CronHint.Custom
    return CronHint.Daily(singleHour, singleMinute)
}

/** 判断集合是否恰好是 `min..max` 上步进 [step] 的等差序列；是则返回步长。 */
private fun stepOf(values: Set<Int>, min: Int, max: Int): Int? {
    if (values.isEmpty() || min !in values) return null
    val sorted = values.sorted()
    if (sorted.first() != min) return null
    val step = if (sorted.size >= 2) sorted[1] - sorted[0] else return null
    if (step <= 0) return null
    var expected = min
    for (value in sorted) {
        if (value != expected) return null
        expected += step
    }
    return if (expected - step <= max) step else null
}
