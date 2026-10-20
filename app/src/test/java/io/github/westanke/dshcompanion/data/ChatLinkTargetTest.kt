package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链接分流测试。
 *
 * 这组断言存在的理由：分流错了的代价不对称 —— 把相对路径猜成绝对路径可能打开
 * **另一个**文件；把 `mailto:` 丢给路径解析会得到一句莫名其妙的「路径不存在」。
 * 所以「不确定就明确说不支持」这条边界必须被钉住。
 */
class ChatLinkTargetTest {

    @Test
    fun absolutePathGoesToTheServerResolver() {
        val target = ChatLinkTarget.classify("/media/wangke/OFFICE/workspace/今日工作汇总_2026-10-09.md")
        assertEquals(
            ChatLinkTarget.ServerPath("/media/wangke/OFFICE/workspace/今日工作汇总_2026-10-09.md"),
            target,
        )
    }

    @Test
    fun markdownAutolinkAngleBracketsAreUnwrappedFirst() {
        // 真实案例（2026-10-19 真机）：模型写的是合法 Markdown
        //   [报告](</media/.../dsh-public-deployments-项目适配评估.md>)
        // 目标用尖括号包裹（CommonMark 的 autolink 写法）。若先不剥尖括号，
        // `<...>` 不以 `/` 开头也没有 scheme → 判成相对路径 → 又被拼一次 cwd，
        // 得到 `/workspace/</media/...>` 的翻倍路径（实测 PATH_NOT_FOUND）。
        val absolute = "/media/wangke/OFFICE/workspace/report/dsh-public-deployments-项目适配评估_2026-10-19.md"
        assertEquals(ChatLinkTarget.ServerPath(absolute), ChatLinkTarget.classify("<$absolute>"))
        // 前后空白也要容忍（Markdown 里常见）。
        assertEquals(ChatLinkTarget.ServerPath(absolute), ChatLinkTarget.classify("  <$absolute>  "))
    }

    @Test
    fun angleBracketedUrlStillGoesToTheBrowser() {
        assertEquals(ChatLinkTarget.Web("https://example.com/a"), ChatLinkTarget.classify("<https://example.com/a>"))
    }

    @Test
    fun httpAndHttpsGoToTheBrowser() {
        assertEquals(ChatLinkTarget.Web("http://example.com/a"), ChatLinkTarget.classify("http://example.com/a"))
        assertEquals(ChatLinkTarget.Web("https://example.com/a"), ChatLinkTarget.classify("https://example.com/a"))
        // scheme 大小写不敏感是 URL 的既有约定。
        assertEquals(ChatLinkTarget.Web("HTTPS://example.com"), ChatLinkTarget.classify("HTTPS://example.com"))
    }

    @Test
    fun protocolRelativeUrlIsAWebPageNotAServerPath() {
        // `//host/path` 以 / 开头，但它是网页。这条如果判错，用户点网页会去解析服务端路径。
        assertEquals(ChatLinkTarget.Web("https://example.com/x"), ChatLinkTarget.classify("//example.com/x"))
    }

    @Test
    fun relativePathIsRefusedRatherThanGuessed() {
        // 用户真机上点不开的就是这一种：我一度写成相对路径的 Markdown 链接。
        // 服务端 roots/resolve 只接受绝对路径，猜基准目录可能打开另一个文件。
        val target = ChatLinkTarget.classify("今日工作汇总_2026-10-09.md")
        assertTrue(target is ChatLinkTarget.Unsupported)
        assertEquals(ChatLinkTarget.Reason.RELATIVE_PATH, (target as ChatLinkTarget.Unsupported).reason)
    }

    @Test
    fun emptyTargetIsRefused() {
        listOf("", "   ", "\n").forEach { raw ->
            val target = ChatLinkTarget.classify(raw)
            assertTrue("空串应被拒绝: '$raw'", target is ChatLinkTarget.Unsupported)
            assertEquals(ChatLinkTarget.Reason.EMPTY, (target as ChatLinkTarget.Unsupported).reason)
        }
    }

    @Test
    fun anchorIsRefused() {
        val target = ChatLinkTarget.classify("#section-3")
        assertEquals(ChatLinkTarget.Reason.ANCHOR, (target as ChatLinkTarget.Unsupported).reason)
    }

    @Test
    fun otherSchemesAreRefused() {
        // mailto / tel / data / file 一律不处理：手机与服务端不是同一台机器，
        // 「本地文件」在远程场景下没有意义；而 mailto 丢给 Intent 之外的路径解析只会产生噪音。
        listOf("mailto:a@b.com", "tel:+8613800000000", "data:text/plain,hi", "file:///etc/passwd").forEach { raw ->
            val target = ChatLinkTarget.classify(raw)
            assertTrue("应被拒绝: $raw", target is ChatLinkTarget.Unsupported)
            assertEquals("$raw 应归为未知 scheme", ChatLinkTarget.Reason.UNKNOWN_SCHEME, (target as ChatLinkTarget.Unsupported).reason)
        }
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        // Markdown 链接里出现换行/空格是常见的（尤其从聊天里复制粘贴来的）。
        assertEquals(
            ChatLinkTarget.ServerPath("/tmp/x.md"),
            ChatLinkTarget.classify("  /tmp/x.md  "),
        )
    }

    @Test
    fun unsupportedKeepsTheRawTargetForDiagnosis() {
        val raw = "some/relative/file.md"
        assertEquals(raw, (ChatLinkTarget.classify(raw) as ChatLinkTarget.Unsupported).raw)
    }

    // ------------------------------------------------------------------
    // 文档位置后缀（`:行号` / `#L行号`）—— 2026-10-20 真机截图回归
    // ------------------------------------------------------------------
    //
    // 症状：会话里 `docs/project/STATUS.md:1`、`/…/STATUS.md#L101` 这类链接点开后，
    // 文件详情面板报「找不到这个路径」，而文件明明存在。
    // 根因：位置后缀被当成路径的一部分原样发给 `roots/resolve`。

    @Test
    fun absolutePathLocationSuffixIsStrippedBeforeResolving() {
        val base = "/media/wangke/OFFICE/workspace/dsh-remote-bridge/docs/project/STATUS.md"
        // 四种真实写法都要归到同一个文件。真机截图里那一屏正好四种都有。
        listOf(
            "$base:1", // 第 1 行
            "$base:12-34", // 行范围
            "$base:12:5", // 行:列（编译器风格）
            "$base#L101", // GitHub 风格
            "$base#L101-L120",
            "$base#l7", // `#L` 大小写不敏感
        ).forEach { raw ->
            assertEquals("$raw 应剥成 $base", ChatLinkTarget.ServerPath(base), ChatLinkTarget.classify(raw))
        }
    }

    @Test
    fun relativePathWithLocationSuffixStillCountsAsRelative() {
        // 同源缺陷：分类用 indexOf(':') 一刀切认 scheme，于是 `docs/x.md:1` 的
        // 「scheme」是 `docs/x.md` —— 判成「未知协议」，连拼 cwd 的机会都没有。
        // 正确的归属仍然是 RELATIVE_PATH（基准不猜），拼 cwd 之前再剥后缀。
        listOf("docs/project/STATUS.md:1", "docs/x.md#L1", "docs/a.ts:12:5", "a.md:3").forEach { raw ->
            val target = ChatLinkTarget.classify(raw)
            assertTrue("$raw 应归相对路径，实际 $target", target is ChatLinkTarget.Unsupported)
            assertEquals(
                "$raw 的 reason 应为 RELATIVE_PATH（界面要能拼 cwd）",
                ChatLinkTarget.Reason.RELATIVE_PATH,
                (target as ChatLinkTarget.Unsupported).reason,
            )
            // 原文保留：拼 cwd 的那一步还要用到它（`joinUnderCwd` 会再剥一次）。
            assertEquals(raw, target.raw)
        }
    }

    @Test
    fun webUrlLocationFragmentsAndPortsAreUntouched() {
        // 刻意不动的两处：
        // ① `https://e.com/a#L1` 的 `#` 是**页内跳转**，剥掉会把用户送回页面顶部；
        // ② `http://host:8080` 的端口也不是行号。
        // 这条同时钉住「网页分支返回原文」这个实现约束 —— 改写成分支里剥后缀就会红。
        assertEquals(ChatLinkTarget.Web("https://e.com/a#L1"), ChatLinkTarget.classify("https://e.com/a#L1"))
        assertEquals(
            ChatLinkTarget.Web("http://example.com:8080/a"),
            ChatLinkTarget.classify("http://example.com:8080/a"),
        )
        assertEquals(
            ChatLinkTarget.Web("https://e.com/a#L1"),
            ChatLinkTarget.classify("<https://e.com/a#L1>"),
        )
    }

    @Test
    fun stripLocationSuffixOnlyTouchesTheCertainForms() {
        // 没有位置后缀的路径一字不改。
        assertEquals("/a/b/c.md", stripLocationSuffix("/a/b/c.md"))
        // `2026-10-20` 这种日期里的连字符不是行范围 —— 前面没有冒号就不该动。
        assertEquals("/a/2026-10-20", stripLocationSuffix("/a/2026-10-20"))
        // 行范围本身带连字符要剥掉。
        assertEquals("/a/b.md", stripLocationSuffix("/a/b.md:12-34"))
        // 目标本身就是 `:12`：剥完会变空串，那就不剥（否则只是换个错误再报一次）。
        assertEquals(":12", stripLocationSuffix(":12"))
        assertEquals("#L1", stripLocationSuffix("#L1"))
        // 只剥两段：三段冒号数字不是认得的形态，剥满两段就收手，
        // 剩下的 `:1` 宁可留着也不继续吃 —— 继续剥就可能把文件名的一部分当行号。
        assertEquals("/a/b.ts:1", stripLocationSuffix("/a/b.ts:1:2:3"))
        // 空串进来按原样出去，不制造新值。
        assertEquals("", stripLocationSuffix(""))
    }

    @Test
    fun relativeTargetIsJoinedUnderCwdWithSuffixStripped() {
        // 拼 cwd 那一步的真实实现（ChatLinkHandlers 调的就是它）：
        // 不剥后缀会得到 `cwd/docs/x.md:1` —— 换汤不换药地打不开。
        val cwd = "/media/wangke/OFFICE/workspace/dsh-remote-bridge"
        assertEquals(
            "$cwd/docs/project/STATUS.md",
            joinUnderCwd(cwd, "docs/project/STATUS.md:1"),
        )
        assertEquals("$cwd/docs/x.md", joinUnderCwd(cwd, "docs/x.md#L101"))
        // 前导 `/` 与尾随 `/` 都容忍：Markdown 里两种写法都常见。
        assertEquals("$cwd/a.md", joinUnderCwd("$cwd/", "/a.md:2"))
        assertEquals("/a.md", joinUnderCwd("/", "a.md"))
    }
}
