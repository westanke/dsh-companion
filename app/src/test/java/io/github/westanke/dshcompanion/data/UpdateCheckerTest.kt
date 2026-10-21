package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 更新检查纯逻辑的回归。
 *
 * ## 为什么值得单测
 *
 * 这批函数全是「取错值不会崩、只会悄悄装错包」的类型：版本比较用字符串比会让
 * `2.2.9` 打赢 `2.2.47`（Gitee tag 真涨过这一路，不是假想）；校验清单按行号取会
 * 因为资产顺序变化而拿错哈希；中英文界面分流写反会让英文用户永远查不到新版。
 * 这些症状在真机上都表现为「更新按钮点了没反应」，无从下手，而它们本质上都是纯函数。
 *
 * ## 覆盖范围
 *
 * 只测**纯函数**：`channelForLanguage` / `isNewerVersion` / `pickApkAsset` /
 * `parseSha256Sums` / `summarizeReleaseNotes` / `downloadProgressPercent` /
 * `sha256Hex` / `verifySha256`。带网络的 `checkLatest` / `downloadApk` 与需要
 * `Context` 的 `buildInstallIntent` 不在此处（网络边界由 `DshClientTest` 守）。
 *
 * ## 临时文件位置
 *
 * 刻意不用 `java.io.tmpdir`：项目规矩禁止 `/tmp`（见用户规则「严禁使用/tmp目录」）。
 * 与 `debug/DiagLogTest.kt` 一致，测试临时文件放 `build/` 下，Gradle 每次 clean 带走。
 */
class UpdateCheckerTest {

    // ——— 语言判据：中文→Gitee，其余→GitHub ———

    /**
     * 判据是 `startsWith("zh")` 而不是 `== "zh"` —— 因为语言 tag 可以带地区后缀
     * （`zh-CN` / `zh-TW`）。写成等值判断会让繁体中文界面悄悄走 GitHub。
     */
    @Test
    fun `中文语言标签走 Gitee 包括带地区后缀`() {
        assertEquals(ReleaseChannel.GITEE, channelForLanguage("zh"))
        assertEquals(ReleaseChannel.GITEE, channelForLanguage("zh-CN"))
        assertEquals(ReleaseChannel.GITEE, channelForLanguage("zh-TW"))
    }

    @Test
    fun `英文与其他语言走 GitHub`() {
        assertEquals(ReleaseChannel.GITHUB, channelForLanguage("en"))
        assertEquals(ReleaseChannel.GITHUB, channelForLanguage("en-US"))
        // `ja` 这类非 zh/en 的语言按「非中文」走 GitHub，行为合理但值得钉住（AGENTS 风险清单第 5 条）
        assertEquals(ReleaseChannel.GITHUB, channelForLanguage("ja"))
    }

    /**
     * 空 tag = 用户在设置里选了「跟随系统」。此时必须回落到系统语言，
     * 且与「显式传系统语言」得到同一个渠道 —— 否则「跟随系统」会和「手动选系统语言」打架。
     */
    @Test
    fun `空标签回落到系统语言且与显式传系统语言同渠道`() {
        val systemLanguage = Locale.getDefault().language
        assertEquals(systemLanguage, effectiveLanguageTag(""))
        assertEquals(
            channelForLanguage(systemLanguage),
            channelForLanguage(""),
        )
    }

    @Test
    fun `语言标签取冒号前的部分`() {
        assertEquals("zh", effectiveLanguageTag("zh-CN"))
        assertEquals("en", effectiveLanguageTag("en-US"))
    }

    /**
     * Gitee 不需要 `User-Agent`，GitHub 强制要 —— 缺了它 GitHub 返回 403。
     * 这是实测结论（方案 §三），不是猜测，所以做成断言：哪天有人「顺手清理」掉
     * 这个头，英文界面的更新检查会立刻 403，而这条测试会先红。
     */
    @Test
    fun `GitHub 渠道带 User-Agent 而 Gitee 不带`() {
        assertEquals("dsh-companion", ReleaseChannel.GITHUB.headers["User-Agent"])
        assertEquals("application/vnd.github+json", ReleaseChannel.GITHUB.headers["Accept"])
        assertNull(ReleaseChannel.GITEE.headers["User-Agent"])
        assertTrue(ReleaseChannel.GITEE.headers.isEmpty())
    }

    // ——— 版本语义化比较 ———

    /**
     * 这一条是整个更新检查最容易写错的地方。字符串比较下 `"2.2.9" > "2.2.47"` 成立
     * （`"9" > "4"`），会把「更旧」报成「更新」，用户点完安装发现版本反而降了。
     */
    @Test
    fun `版本必须逐段比数字而不是字符串`() {
        assertFalse("字符串比较会让 2.2.9 打赢 2.2.47", isNewerVersion("2.2.9", "2.2.47"))
        assertTrue(isNewerVersion("2.2.48", "2.2.47"))
    }

    @Test
    fun `版本相等时不触发更新`() {
        assertFalse(isNewerVersion("2.2.47", "2.2.47"))
        assertFalse(isNewerVersion("v2.2.47", "2.2.47"))
    }

    /**
     * 段数不等时短的一侧按 0 补齐：`2.3` vs `2.2.9` 应当判「更新」，
     * 否则 `2.3` 会被当成「段数更少所以更旧」，漏掉一次真实升级。
     */
    @Test
    fun `段数不等时短的一侧按零补齐`() {
        assertTrue(isNewerVersion("2.3", "2.2.9"))
        assertTrue(isNewerVersion("2.2.9.1", "2.2.9"))
        assertFalse("2.2.9 vs 2.2.9.1 是更旧，不能判成更新", isNewerVersion("2.2.9", "2.2.9.1"))
    }

    /**
     * 前导 `v` 与任意非数字字符都应由正则只取数字段消化掉 ——
     * 发布 tag 历史上带过 `v`，不带处理就会 `split(".")` 出 `v2` 这种段。
     */
    @Test
    fun `带前导 v 与杂字符的版本仍能正确比较`() {
        assertTrue(isNewerVersion("v2.2.47", "v2.2.46"))
        assertTrue(isNewerVersion("release-2.2.47", "release-2.2.46"))
    }

    /**
     * 远程版本解析不出数字时必须返回 `false`（保守：不触发安装）。
     * 反过来，本地版本为空时任何有数字的远程版本都算更新。
     */
    @Test
    fun `解析不出数字的远程版本不触发更新`() {
        assertFalse(isNewerVersion("", "2.2.47"))
        assertFalse(isNewerVersion("latest", "2.2.47"))
        assertTrue(isNewerVersion("v2.2.47", ""))
    }

    // ——— APK 资产筛选 ———

    /**
     * 资产顺序不保证（GitHub / Gitee 都不承诺顺序），所以必须按扩展名挑，
     * 不能按下标取第一个 —— 否则某次上传换了个顺序就取到 `SHA256SUMS.txt` 装上去。
     */
    @Test
    fun `APK 资产按扩展名挑而不是按下标`() {
        val assets = listOf(
            ReleaseAsset(name = "SHA256SUMS.txt", downloadUrl = "https://x/sums"),
            ReleaseAsset(name = "source-code.zip", downloadUrl = "https://x/zip"),
            ReleaseAsset(name = "dsh-companion-v2.2.47.apk", downloadUrl = "https://x/app.apk"),
        )
        assertEquals("dsh-companion-v2.2.47.apk", pickApkAsset(assets)?.name)
    }

    /**
     * 校验清单本身不是 APK。只有它时必须返回 `null`，让 UI 走「发布里没有 APK」的
     * 明确报错，而不是拿清单文件去调安装器。
     */
    @Test
    fun `只有校验清单时挑不出 APK`() {
        val assets = listOf(ReleaseAsset(name = "SHA256SUMS.txt", downloadUrl = "https://x/sums"))
        assertNull(pickApkAsset(assets))
        assertNull(pickApkAsset(emptyList()))
    }

    @Test
    fun `校验清单名大小写不敏感且排除 APK`() {
        val assets = listOf(
            ReleaseAsset(name = "dsh-companion-v2.2.47.apk", downloadUrl = "https://x/app.apk"),
            ReleaseAsset(name = "sha256sums.txt", downloadUrl = "https://x/sums"),
        )
        assertEquals("sha256sums.txt", pickShaSumsAsset(assets)?.name)
        assertNull(pickShaSumsAsset(listOf(assets[0])))
    }

    // ——— SHA256SUMS 解析 ———

    private val sumsFixture = """
        # dsh-companion 发布产物校验清单 v2.2.47
        # 由发布工作流生成，请勿手工编辑。
        ${"a".repeat(64)}  dsh-companion-v2.2.47.apk
        ${"b".repeat(64)}  dsh-companion-v2.2.46.apk
    """.trimIndent()

    /**
     * 按文件名匹配而不是按行号 —— 同上，资产顺序不保证。
     * 另外首行注释必须跳过：`#` 开头的行不是数据行。
     */
    @Test
    fun `校验清单按文件名匹配并跳过注释`() {
        val expected = "a".repeat(64)
        assertEquals(expected, parseSha256Sums(sumsFixture, "dsh-companion-v2.2.47.apk"))
    }

    @Test
    fun `校验清单大小写不敏感`() {
        val upper = sumsFixture.replace("a".repeat(64), "A".repeat(64))
        assertEquals("a".repeat(64), parseSha256Sums(upper, "dsh-companion-v2.2.47.apk")?.lowercase())
    }

    @Test
    fun `校验清单顺序无关 且 容忍多余空白`() {
        val reordered = """
            ${"b".repeat(64)}    dsh-companion-v2.2.46.apk
            ${"a".repeat(64)}  dsh-companion-v2.2.47.apk
        """.trimIndent()
        assertEquals("a".repeat(64), parseSha256Sums(reordered, "dsh-companion-v2.2.47.apk"))
    }

    /**
     * 清单里没有对应 APK 的记录时返回 `null`：宁可让 UI 报「找不到校验记录」，
     * 也不能随便挑一条哈希去比 —— 拿错哈希会让**校验通过**但装的是别的包。
     */
    @Test
    fun `校验清单缺对应记录返回 null`() {
        assertNull(parseSha256Sums(sumsFixture, "dsh-companion-v9.9.9.apk"))
        assertNull(parseSha256Sums(sumsFixture, ""))
        assertNull(parseSha256Sums("", "dsh-companion-v2.2.47.apk"))
    }

    /**
     * 哈希长度不对（不是 64 位）的行必须当作无效行跳过，
     * 否则半截哈希也能「匹配成功」。
     */
    @Test
    fun `校验清单跳过长度不对的哈希`() {
        val bad = """
            ${"a".repeat(32)}  dsh-companion-v2.2.47.apk
            ${"b".repeat(64)}  dsh-companion-v2.2.47.apk
        """.trimIndent()
        assertEquals("b".repeat(64), parseSha256Sums(bad, "dsh-companion-v2.2.47.apk"))
    }

    // ——— SHA-256 计算与校验 ———

    @Test
    fun `SHA-256 计算结果与已知值一致`() {
        val dir = File("build/dsh-update-checker-test").apply { mkdirs() }
        val file = File(dir, "apk-fixture").apply { writeText("hello world") }
        try {
            // 已知哈希（不是用被测代码算出来的期望值，否则测试会自我证明）
            assertEquals(
                "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
                sha256Hex(file),
            )
        } finally {
            file.delete()
            dir.delete()
        }
    }

    /**
     * 校验失败**返回 false 而不是抛异常**：调用方要靠这个布尔决定「删掉已下载的文件」，
     * 抛异常的话删除逻辑就被跳过，坏包留在磁盘上会被下一次安装复用。
     */
    @Test
    fun `校验失败返回 false 而不是抛异常`() {
        val dir = File("build/dsh-update-checker-test").apply { mkdirs() }
        val file = File(dir, "apk-fixture").apply { writeText("hello world") }
        try {
            val good = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
            assertTrue(verifySha256(file, good))
            assertTrue("期望值大小写不应影响结果", verifySha256(file, good.uppercase()))
            assertTrue("前后空白应被忽略", verifySha256(file, "  $good  "))
            assertFalse(verifySha256(file, "0".repeat(64)))
        } finally {
            file.delete()
            dir.delete()
        }
    }

    @Test
    fun `空文件也能算出哈希不崩`() {
        val dir = File("build/dsh-update-checker-test").apply { mkdirs() }
        val empty = File(dir, "empty-fixture").apply { writeText("") }
        try {
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                sha256Hex(empty),
            )
        } finally {
            empty.delete()
            dir.delete()
        }
    }

    // ——— 发布说明摘要 ———

    /**
     * 两端 `body` 都是 Markdown（含 `##` 小标题、加粗、`SHA-256：…` 一行），
     * 界面只给一小段。压平与去强调必须在摘要层做掉，否则换行会撑爆行式条目。
     */
    @Test
    fun `发布说明被压平成一行并去掉强调`() {
        val body = """
            ## 本次更新

            **修复**了消息重复渲染的问题。
            SHA-256：`abc123`
        """.trimIndent()
        val flat = summarizeReleaseNotes(body)
        assertFalse("换行必须被压平", flat.contains('\n'))
        assertFalse("强调符号必须去掉", flat.contains("**"))
        assertTrue(flat.contains("修复了消息重复渲染的问题。"))
        assertTrue(flat.contains("SHA-256："))
        assertTrue("小标题应保留但被压平", flat.contains("## 本次更新"))
    }

    /**
     * 截断边界是最容易被写歪的地方：正好等于 `maxLength` 不该截断，
     * 多一个字符才该截断并补省略号。少测一个字符，边界就永远没被覆盖。
     */
    @Test
    fun `发布说明截断边界正好等于与超过一个字符`() {
        val text = "x".repeat(40)
        assertEquals("x".repeat(40), summarizeReleaseNotes(text, maxLength = 40))
        assertEquals(
            "x".repeat(39) + "…",
            summarizeReleaseNotes(text, maxLength = 39),
        )
    }

    @Test
    fun `发布说明的 CRLF 也被压平`() {
        assertEquals("a b", summarizeReleaseNotes("a\r\nb"))
    }

    // ——— 下载进度百分比 ———

    /**
     * Gitee 资产没有 `size` 字段，下载前拿不到总长（`total = -1`）。
     * 此时必须显示 0 并由界面改口「正在下载…」，而不是显示一个假的 0% 或除零崩溃。
     */
    @Test
    fun `拿不到总长时进度返回 0`() {
        assertEquals(0, downloadProgressPercent(5, -1))
        assertEquals(0, downloadProgressPercent(5, 0))
        assertEquals(0, downloadProgressPercent(0, 0))
    }

    @Test
    fun `进度百分比在边界处正确且被钳制`() {
        assertEquals(0, downloadProgressPercent(0, 100))
        assertEquals(50, downloadProgressPercent(50, 100))
        assertEquals(100, downloadProgressPercent(100, 100))
        // 已下载超过总长（服务器 Content-Length 与实际不符）不应显示 >100%
        assertEquals(100, downloadProgressPercent(200, 100))
    }

    /**
     * `MessageDigest.getInstance("SHA-256")` 的算法名若被改错，JVM 会抛
     * `NoSuchAlgorithmException` 而不是返回错误哈希。这条测试顺便把它钉住 ——
     * 上面几条哈希断言全部依赖它。
     */
    @Test
    fun `SHA-256 算法在当前 JVM 上可用`() {
        assertEquals(
            "SHA-256",
            MessageDigest.getInstance("SHA-256").algorithm,
        )
    }
}
