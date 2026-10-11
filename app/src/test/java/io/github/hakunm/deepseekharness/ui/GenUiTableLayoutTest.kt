package io.github.hakunm.deepseekharness.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片内表格列宽的纯函数测试。
 *
 * 为什么值得单测：这一版把列宽从写死的 140dp 换成按可用宽度算出来的值，
 * 规则照抄网页版（chat.html `.card table{min-width:100%}`）—— 装得下就铺满卡片，
 * 装不下就退回下限、交给外层横向滚。
 *
 * 这条规则在界面上看不出来：算错了只是「表格右边空一块」，或者
 * 「该滚的时候滚不动、列被压得只剩两个字」，肉眼很容易放过。
 *
 * 只测纯函数，不碰 Compose —— 单元格是否真的对齐、Divider 有没有宽度，
 * 是布局事实，由阅读 + 真机验证，不是单测能替代的。
 */
class GenUiTableLayoutTest {

    @Test
    fun `装得下时按列等分铺满可用宽度`() {
        // 3 列 / 360dp：一列 120dp，总宽正好等于卡片宽度 —— 不该出现横向滚动。
        assertEquals(120f, genUiTableColumnWidthDp(360f, 3), 0.001f)
    }

    @Test
    fun `正好卡在下限时不溢出`() {
        // 4 列 / 384dp：等分恰好等于下限，取等分（总宽 == 可用宽度），刚好不需要滚。
        assertEquals(
            GENUI_TABLE_MIN_COLUMN_WIDTH_DP,
            genUiTableColumnWidthDp(GENUI_TABLE_MIN_COLUMN_WIDTH_DP * 4, 4),
            0.001f,
        )
    }

    @Test
    fun `装不下时退回下限并让总宽超出卡片交给外层滚`() {
        // 7 列 / 360dp：等分只有 51.4dp，一列放不下两个字，改用下限。
        val width = genUiTableColumnWidthDp(360f, 7)
        assertEquals(GENUI_TABLE_MIN_COLUMN_WIDTH_DP, width, 0.001f)
        assertTrue("总宽必须超出卡片宽度，否则外层 horizontalScroll 就是摆设", width * 7 > 360f)
    }

    @Test
    fun `列数非法或宽度不可用时退回下限`() {
        // 父级给无界约束时 maxWidth 是 Infinity —— 直接除会得到 NaN 或 0，整张表会消失。
        assertEquals(GENUI_TABLE_MIN_COLUMN_WIDTH_DP, genUiTableColumnWidthDp(Float.POSITIVE_INFINITY, 2), 0.001f)
        assertEquals(GENUI_TABLE_MIN_COLUMN_WIDTH_DP, genUiTableColumnWidthDp(Float.NaN, 2), 0.001f)
        assertEquals(GENUI_TABLE_MIN_COLUMN_WIDTH_DP, genUiTableColumnWidthDp(0f, 2), 0.001f)
        assertEquals(GENUI_TABLE_MIN_COLUMN_WIDTH_DP, genUiTableColumnWidthDp(360f, 0), 0.001f)
    }
}
