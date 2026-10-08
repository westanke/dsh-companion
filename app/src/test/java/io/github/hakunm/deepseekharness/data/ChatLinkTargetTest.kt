package io.github.hakunm.deepseekharness.data

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
}
