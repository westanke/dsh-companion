package io.github.hakunm.deepseekharness.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dsh-ui 围栏的切割与解析。
 *
 * 这批用例钉的是**退化行为**，不是理想路径：
 *
 * 1. 流式输出时围栏逐字到达，开头 ``` 已在而结尾未到。此时若就切出去，得到的是半截
 *    JSON，解析必然失败，用户看到卡片凭空消失。所以未闭合必须**整段按原文保留**。
 * 2. 围栏里的 JSON 坏了，必须能回落到代码块，而不是让整条消息消失。
 * 3. 未实现的组件类型整条丢弃，不能画一个点不动的按钮假装支持。
 */
class GenUiBlocksTest {

    private fun segments(body: String) = splitMessageBody(body)

    private fun prose(segment: MessageSegment) = (segment as MessageSegment.Prose).text

    private fun ui(segment: MessageSegment) = (segment as MessageSegment.Ui).json

    @Test
    fun `没有围栏时整段是 prose`() {
        val result = segments("普通一句话")
        assertEquals(1, result.size)
        assertEquals("普通一句话", prose(result[0]))
    }

    @Test
    fun `单个围栏切成 prose 加 ui`() {
        val result = segments(
            """
            这是说明文字。

            ```dsh-ui
            {"items":[{"type":"text","content":"你好"}]}
            ```
            """.trimIndent(),
        )
        assertEquals(2, result.size)
        assertEquals("这是说明文字。", prose(result[0]))
        assertEquals("""{"items":[{"type":"text","content":"你好"}]}""", ui(result[1]).trim())
    }

    /** 流式输出的常态：围栏只到了一半。绝不能切，否则卡片凭空消失。 */
    @Test
    fun `未闭合围栏整段按原文保留`() {
        val body = "说明文字。\n\n```dsh-ui\n{\"items\":[{\"type\":\"text\""
        val result = segments(body)
        assertEquals(1, result.size)
        assertTrue(result[0] is MessageSegment.Prose)
        assertEquals(body, prose(result[0]))
    }

    @Test
    fun `前后都有文字时保留中间卡片`() {
        val body = "前\n\n```dsh-ui\n{\"items\":[{\"type\":\"badge\",\"label\":\"x\"}]}\n```\n\n后"
        val result = segments(body)
        assertEquals(3, result.size)
        assertEquals("前", prose(result[0]))
        assertEquals("后", prose(result[2]))
        assertTrue(result[1] is MessageSegment.Ui)
    }

    /** 一条回复里连续弹两个问题很常见，第二个不能漏切。 */
    @Test
    fun `同一条消息里两个卡片都切出来`() {
        val body = "开头\n\n```dsh-ui\n{\"items\":[{\"type\":\"text\",\"content\":\"一\"}]}\n```\n\n中间\n\n" +
            "```dsh-ui\n{\"items\":[{\"type\":\"text\",\"content\":\"二\"}]}\n```\n\n结尾"
        val result = segments(body)
        assertEquals(5, result.size)
        assertEquals("开头", prose(result[0]))
        assertEquals("中间", prose(result[2]))
        assertEquals("结尾", prose(result[4]))
        assertTrue(result[1] is MessageSegment.Ui)
        assertTrue(result[3] is MessageSegment.Ui)
    }

    /**
     * ` ```json dsh-ui ` 是 genui skill 文档里的合法写法，网页版能渲染。
     * 只认精确的 ` ```dsh-ui ` 会让它在 App 里退化成 JSON 代码块 ——
     * 这正是用户说的「有的能弹、有的显示成 md 模式」。
     */
    @Test
    fun `带 json 语言标记的围栏同样切成卡片`() {
        val result = segments(
            "说明\n\n```json dsh-ui\n{\"items\":[{\"type\":\"text\",\"content\":\"x\"}]}\n```",
        )
        assertEquals(2, result.size)
        assertEquals("说明", prose(result[0]))
        assertTrue(result[1] is MessageSegment.Ui)
    }

    @Test
    fun `语言标记顺序颠倒也能识别`() {
        val result = segments("```dsh-ui json\n{\"items\":[{\"type\":\"text\",\"content\":\"x\"}]}\n```")
        assertEquals(1, result.size)
        assertTrue(result[0] is MessageSegment.Ui)
    }

    /** 别的语言围栏绝不能被当成卡片吞掉。 */
    @Test
    fun `非 dsh-ui 围栏保持原样`() {
        val body = "看这段代码：\n\n```kotlin\nval x = 1\n```\n\n就这些。"
        val result = segments(body)
        assertEquals(1, result.size)
        assertEquals(body, prose(result[0]))
    }

    /** 正文里提到 dsh-ui 但没有围栏，不能被误切。 */
    @Test
    fun `正文提到 dsh-ui 不会被误切`() {
        val body = "用 ```dsh-ui 围栏就能弹卡片，这里只是文字。"
        val result = segments(body)
        assertEquals(1, result.size)
        assertEquals(body, prose(result[0]))
    }

    @Test
    fun `解析出标题与多个节点`() {
        val spec = parseGenUi(
            """{"title":"发布状态","gap":8,"items":[{"type":"text","content":"a"},{"type":"badge","label":"b"}]}""",
        )!!
        assertEquals("发布状态", spec.title)
        assertEquals(8, spec.gap)
        assertEquals(listOf("text", "badge"), spec.items.map { it.type })
    }

    /** 整段坏掉时返回 null，UI 层据此回落到代码块。 */
    @Test
    fun `坏 JSON 返回 null`() {
        assertNull(parseGenUi("{ 不是 JSON"))
        assertNull(parseGenUi("[]"))
        assertNull(parseGenUi("""{"title":"空壳"}"""))
    }

    /**
     * 未知组件被丢弃，但**已知组件要留下**。
     *
     * 这条对应网页版的「坏组件被丢弃、其余照常渲染」——一处坏节点不该拖垮整张卡。
     */
    @Test
    fun `未知类型被丢弃其余保留`() {
        val spec = parseGenUi(
            """{"items":[{"type":"mermaid","code":"x"},{"type":"text","content":"留着"}]}""",
        )!!
        assertEquals(listOf("text"), spec.items.map { it.type })
    }

    /** 认得但没实现的类型不能被当成支持 —— SUPPORTED 白名单挡住了它。 */
    @Test
    fun `未实现的交互类型不进白名单`() {
        assertNull(parseGenUi("""{"items":[{"type":"scene3d","meshes":[]}]}"""))
    }

    @Test
    fun `list 同时收字符串与对象两种写法`() {
        val spec = parseGenUi(
            """{"items":[{"type":"list","items":["纯字符串",{"title":"对象","desc":"说明"}]}]}""",
        )!!
        val items = spec.items[0].listItems()
        assertEquals(listOf("纯字符串", "对象"), items.map { it.title })
        assertEquals("说明", items[1].desc)
    }

    @Test
    fun `keyvalue 取键值对`() {
        val spec = parseGenUi("""{"items":[{"type":"keyvalue","pairs":[{"key":"版本","value":"2.0.0"}]}]}""")!!
        val pair = spec.items[0].value.array("pairs")!![0] as kotlinx.serialization.json.JsonObject
        assertEquals("版本", pair.str("key"))
        assertEquals("2.0.0", pair.str("value"))
    }

    /** 行内标记要剥掉：手机端不渲染 ** 与 ==，留着只会看到裸符号。 */
    @Test
    fun `剥掉行内标记`() {
        assertEquals("重点", inlineMarkup("**重点**"))
        assertEquals("重点", inlineMarkup("==重点=="))
        assertEquals("code", inlineMarkup("`code`"))
    }

    // ---- 诊断（diagnoseGenUi）----
    //
    // 诊断行存在的意义就是「用户截图发回来就能定位」，所以这几条钉的是**报告内容**而不是
    // 渲染结果：渲染在真机上没法验，文字报告可以。

    @Test
    fun `诊断 纯文字不带围栏`() {
        val report = diagnoseGenUi("就一句话")
        assertTrue(report.summary, report.summary.contains("无 dsh-ui 围栏"))
    }

    @Test
    fun `诊断 有围栏但没被识别时必须报警`() {
        // 真机上最像的一种故障：卡片被当正文，屏幕上读起来「像有又像没有」。
        // 裸 ``` 天生二义（头或闭合），所以只断言「报了警」，不断言是头还是闭合。
        val report = diagnoseGenUi("```\n{\"items\":[{\"type\":\"text\"}]}\n```")
        assertTrue(report.summary, report.summary.contains("⚠"))
        assertTrue(report.summary, report.summary.contains("无标记"))
    }

    @Test
    fun `诊断 正常卡片报成功并列出节点类型`() {
        val report = diagnoseGenUi(
            "```dsh-ui\n{\"items\":[{\"type\":\"list\",\"items\":[{\"title\":\"甲\"}]}]}\n```",
        )
        assertTrue(report.summary, report.summary.contains("dsh-ui ✓"))
        assertTrue(report.details, report.details.contains("list×1"))
    }

    @Test
    fun `诊断 JSON 坏了要报出原因`() {
        val report = diagnoseGenUi("```dsh-ui\n{这不是 JSON}\n```")
        assertTrue(report.summary, report.summary.contains("JSON 解析失败"))
    }

    @Test
    fun `诊断 未闭合围栏属流式中间态 不误报为故障`() {
        // 围栏头认出来了，只是还没闭合 —— 这是流式输出的正常中间态，
        // 报成「⚠」会让用户盯着一个永远不消失的警告。
        val report = diagnoseGenUi("```dsh-ui\n{\"items\":[]")
        assertTrue(report.summary, report.summary.contains("尚未闭合"))
        assertTrue(report.summary, !report.summary.contains("⚠"))
    }
}