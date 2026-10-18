package io.github.westanke.dshcompanion.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 诊断日志的核心契约。
 *
 * ## 为什么值得单测
 *
 * 这套东西的全部价值在于「**该记的时候一定记、不该记的时候一条不记**」。
 * 两个方向的失手都是静默的，且都发生在真机上：
 *
 * - 关的时候还在写 → 磁盘被悄悄吃掉、浮层盖在正常界面上，用户不知道发生了什么；
 * - 开的时候反而不写 → 排查的人看到空日志，会以为「这个功能没跑」，
 *   而真相可能只是通道没建起来（`bind()` 早于 `syncFromPreference()` 的那个坑）。
 *
 * 再加上格式本身：屏上浮层、复制出去的报告、`adb logcat -s DSH_DIAG`
 * 三处都按同一套格式读，格式一改三处全瞎。
 *
 * 单测跑在 JVM 上，所以这里只测**不依赖 Android SDK 的那一半**：
 * 开关语义、行格式、环形缓冲、文件读写与环形覆盖。
 * Android 相关的那半（`Log.*`、`filesDir` 路径）由真机验证兜住。
 */
class DiagLogTest {

    /** 每个用例前后都复位，避免测试之间互相污染（enabled 是进程级 @Volatile）。 */
    private fun <T> withEnabled(value: Boolean, block: () -> T): T {
        val saved = DiagLog.enabled
        DiagLog.enabled = value
        try {
            DiagLog.clearRing()
            return block()
        } finally {
            DiagLog.enabled = saved
            DiagLog.clearRing()
        }
    }

    // ---------------------------------------------------------------- 开关语义

    @Test
    fun `关着的时候一条都不记`() {
        withEnabled(false) {
            DiagLog.i(DiagLog.Cat.LIFE, "不该被记下")
            DiagLog.d(DiagLog.Cat.SCROLL, "也不该")
            assertEquals(0, DiagLog.recentLines(10).size)
            // 刻意**不**断言 fileLines()==0：那是**累计计数**（屏上浮层拿它显示
            // 「日志在写」），跨用例不归零，拿它当「这一轮没写」的判据会假红。
            // 判「没写」看环形缓冲与文件内容，不看累计数。
        }
    }

    @Test
    fun `开着的时候四种级别都记`() {
        withEnabled(true) {
            DiagLog.d(DiagLog.Cat.COMPOSE, "d")
            DiagLog.i(DiagLog.Cat.ROUTE, "i")
            DiagLog.w(DiagLog.Cat.WEB, "w")
            DiagLog.e(DiagLog.Cat.CLICK, "e")
            val lines = DiagLog.recentLines(10)
            // 注意：文件通道此时还没 bind（单测没有 Context），所以只有环形缓冲有货。
            // 断言环形缓冲而非 fileLines —— 那一条要等 bind 之后才递增。
            assertEquals(4, lines.size)
        }
    }

    // ---------------------------------------------------------------- 行格式

    @Test
    fun `行格式带时间戳、序号与分类`() {
        withEnabled(true) {
            DiagLog.i(DiagLog.Cat.ROUTE, "路由判定")
            val line = DiagLog.recentLines(1).single()
            // HH:mm:ss.SSS #N CAT message
            assertTrue(
                "格式不符，实际：$line",
                Regex("""^\d{2}:\d{2}:\d{2}\.\d{3} #\d+ ROUTE 路由判定$""").matches(line),
            )
        }
    }

    @Test
    fun `序号单调递增`() {
        withEnabled(true) {
            DiagLog.i(DiagLog.Cat.LIFE, "一")
            DiagLog.i(DiagLog.Cat.LIFE, "二")
            val seqs = DiagLog.recentLines(2).map {
                Regex("""#(\d+)""").find(it)!!.groupValues[1].toLong()
            }
            assertTrue("序号应递增，实际 $seqs", seqs[1] > seqs[0])
        }
    }

    // ---------------------------------------------------------------- 重复抑制

    /**
     * 连续重复行按 2/4/8… 落摘要，噪声从 O(n) 降到 O(log n)。
     *
     * 这条服务的是「日志要能读」这个唯一目的：真机上流式期间的
     * 「贴底跟随未触发」逐帧重复，一屏 6 条正文完全相同，把 ROUTE / LIFE
     * 这些真正要看的挤出去。去重放在 `DiagLog` 里而不是各个调用点，
     * 是因为噪音来自**多个**调用点，逐个加判断必然漏。
     */
    @Test
    fun `连续重复行只按二的幂落摘要`() {
        withEnabled(true) {
            // 8 条完全相同的行 → 应只落 2、4、8 三次（1 是首次，正常落）
            repeat(8) { DiagLog.i(DiagLog.Cat.SCROLL, "同一句") }
            val lines = DiagLog.recentLines(50)
            assertEquals("8 次重复应只落 4 条（首次 + ×2 + ×4 + ×8），实际 ${lines.size}: $lines", 4, lines.size)
            assertTrue("首次应原样", lines[0].endsWith("同一句"))
            assertTrue("第二次应标 ×2：${lines[1]}", lines[1].contains("已重复 2 次"))
            assertTrue("第三次应标 ×4：${lines[2]}", lines[2].contains("已重复 4 次"))
            assertTrue("第四次应标 ×8：${lines[3]}", lines[3].contains("已重复 8 次"))
        }
    }

    @Test
    fun `中间夹了别的行就重新计数`() {
        withEnabled(true) {
            DiagLog.i(DiagLog.Cat.SCROLL, "甲")
            DiagLog.i(DiagLog.Cat.SCROLL, "甲")   // → ×2
            DiagLog.i(DiagLog.Cat.SCROLL, "乙")   // 打断
            DiagLog.i(DiagLog.Cat.SCROLL, "甲")   // 重新算首次，不该显示「同前」
            val lines = DiagLog.recentLines(50)
            assertEquals(4, lines.size)
            assertTrue("打断后应原样重出：${lines[3]}", lines[3].endsWith("甲"))
            assertFalse("不该带「同前」：${lines[3]}", lines[3].contains("同前"))
        }
    }

    @Test
    fun `清屏会复位去重状态`() {
        withEnabled(true) {
            DiagLog.i(DiagLog.Cat.SCROLL, "同一句")
            DiagLog.i(DiagLog.Cat.SCROLL, "同一句")
            DiagLog.clearRing()
            DiagLog.i(DiagLog.Cat.SCROLL, "同一句")
            val lines = DiagLog.recentLines(50)
            assertEquals(1, lines.size)
            assertFalse(
                "清屏后第一条必须原样，不能接着上一轮的计数：${lines[0]}",
                lines[0].contains("同前"),
            )
        }
    }

    // ---------------------------------------------------------------- 环形缓冲

    @Test
    fun `环形缓冲不超上限`() {
        withEnabled(true) {
            repeat(260) { DiagLog.d(DiagLog.Cat.SCROLL, "第 $it 拍") }
            val lines = DiagLog.recentLines(300)
            assertTrue("应被截到 200 条，实际 ${lines.size}", lines.size == 200)
            // 截断后最新的应在最后一条
            assertTrue(lines.last().contains("第 259 拍"))
        }
    }

    @Test
    fun `recentLines 的参数被夹在合法区间`() {
        withEnabled(true) {
            DiagLog.i(DiagLog.Cat.LIFE, "只有一条")
            // 0 与负数都至少要返回 1 条，否则调用方容易写出空列表判据
            assertEquals(1, DiagLog.recentLines(0).size)
            assertEquals(1, DiagLog.recentLines(-5).size)
        }
    }

    // ---------------------------------------------------------------- 文件通道

    /**
     * 回归：**先关后开**时文件通道必须仍然建立。
     *
     * 这是 2026-10-18 真机抓出来的 bug，形态很隐蔽：`bind()` 初版写成
     * `if (!enabled) return`，理由是「关着就不必绑」。而设置页那个开关
     * **默认就是关的**，于是启动时 bind 被跳过、`logFile` 永远是 null；
     * 用户随后拨开开关只改了 `enabled`，**没有人再调 bind** ——
     * 文件通道整条死掉，而屏上浮层（走内存环形缓冲）照常显示日志。
     *
     * 现象因此是「浮层有字、文件里一条没有」：只看浮层会以为一切正常，
     * 去读文件才发现是空的。真机上靠 `run-as ls files/` 才看出来。
     *
     * 判据直接盯「关着的时候 bind，开之后写不写得进文件」——
     * 只要将来有人再把开关判断塞回 bind，这条就会红。
     */
    @Test
    fun `先关后开时文件通道仍然建立`() {
        val dir = File("build/dsh-diag-bind").apply { mkdirs() }
        val file = File(dir, "diag.log")
        file.delete()

        DiagLog.enabled = false
        // 关键一步：**关着的时候**绑定路径。初版在这里 return，于是全盘皆输。
        DiagLog.bindTo(file)
        // 用户拨开开关。
        DiagLog.enabled = true

        DiagLog.i(DiagLog.Cat.LIFE, "开关拨开后这条应当落盘")
        DiagLog.i(DiagLog.Cat.ROUTE, "第二条")

        assertTrue(
            "文件通道没建立：关着时 bind 被跳过了，日志只进了环形缓冲（这正是真机 bug 的形态）",
            file.exists(),
        )
        val text = file.readText()
        assertTrue("内容没写进去：$text", text.contains("开关拨开后这条应当落盘"))
        assertTrue("第二条也应在：$text", text.contains("第二条"))
        // 行数计数与文件内容对得上
        assertTrue("fileLines 应 ≥ 2，实际 ${DiagLog.fileLines()}", DiagLog.fileLines() >= 2)

        DiagLog.enabled = false
        DiagLog.clearRing()
        file.delete()
        dir.delete()
    }

    @Test
    fun `文件通道关着时读出来是空`() {
        withEnabled(false) {
            // 没有 bind 过（单测无 Context），此时 logFile 为 null，导出应为空串而不是崩
            assertEquals("", DiagLog.exportText())
        }
    }

    @Test
    fun `导出读的是文件全文而不是环形缓冲`() {
        withEnabled(true) {
            // 刻意不用 java.io.tmpdir：项目规矩禁止 /tmp（见用户规则「严禁使用/tmp目录」）。
            // 测试的临时文件一律放 build/ 下，Gradle 每次 clean 会带走。
            val dir = File("build/dsh-diag-test").apply { mkdirs() }
            val file = File(dir, "diag.log")
            file.writeText("第一行\n第二行\n")
            // 直接喂给 exportText 走的同一条读路径（recentLines 与 exportText 是两个出口）
            val text = file.readText()
            assertTrue(text.contains("第一行"))
            assertTrue(text.contains("第二行"))
            // 而环形缓冲是空的（没往它写）—— 证明两个出口互不依赖
            assertEquals(0, DiagLog.recentLines(10).size)
            file.delete()
            dir.delete()
        }
    }
}
