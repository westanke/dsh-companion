package io.github.hakunm.deepseekharness.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `@` 提及识别与替换的边界用例。
 *
 * 这块逻辑的坑全在「什么时候**不**该触发」上：宽松一点就会在正常打字时弹出文件列表，
 * 而那种打扰比不弹更烦人。所以下面一半用例是在测「不该触发」。
 */
class MentionQueryTest {

    // ── activeStart：什么时候该触发 ──

    @Test
    fun `行首的 at 触发`() {
        assertEquals(0, MentionQuery.activeStart("@"))
        assertEquals(0, MentionQuery.activeStart("@src"))
    }

    @Test
    fun `空白之后的 at 触发`() {
        // 「帮我看看 」是 5 个字符（下标 0..4），@ 落在下标 5。
        assertEquals(5, MentionQuery.activeStart("帮我看看 @src"))
        // " @" 里空格占下标 0，@ 在下标 1。
        assertEquals(1, MentionQuery.activeStart(" @"))
    }

    @Test
    fun `换行之后的 at 也触发`() {
        // 多行输入时用户在第二行打 @ —— 换行属于空白，同样算新词开始。
        assertEquals(3, MentionQuery.activeStart("ab\n@x"))
    }

    // ── activeStart：什么时候不该触发（重点）──

    @Test
    fun `邮箱形态不触发`() {
        // `foo@bar.com` 里的 @ 前面是字母，那是个邮箱不是提及。
        // 这条是整块逻辑存在的理由：宽松匹配会在用户正常打字时弹列表。
        assertNull(MentionQuery.activeStart("foo@bar.com"))
        assertNull(MentionQuery.activeStart("联系我 foo@bar.com"))
    }

    @Test
    fun `光标已离开提及词时不触发`() {
        // 打完 @src 又敲了空格，说明这个词结束了 —— 补全不该还挂着。
        assertNull(MentionQuery.activeStart("@src "))
        assertNull(MentionQuery.activeStart("@src 后面还有字"))
    }

    @Test
    fun `完全没有 at 时不触发`() {
        assertNull(MentionQuery.activeStart(""))
        assertNull(MentionQuery.activeStart("普通文本"))
    }

    @Test
    fun `取的是最后一个未闭合的 at`() {
        // "@src @li"：下标 0 那个 @src 已经闭合（后面有空格），
        // 真正的光标在第二个 @ 后面，它在下标 5。
        assertEquals(5, MentionQuery.activeStart("@src @li"))
    }

    // ── query：正在输入的查询串 ──

    @Test
    fun `query 取 at 之后那段`() {
        assertEquals("src", MentionQuery.query("@src"))
        assertEquals("li", MentionQuery.query("看看 @li"))
        assertEquals("", MentionQuery.query("@"))
    }

    @Test
    fun `没有活跃提及则 query 为 null`() {
        assertNull(MentionQuery.query("普通文本"))
        assertNull(MentionQuery.query("foo@bar.com"))
    }

    // ── applySelection：替换而非整行覆盖 ──

    @Test
    fun `只替换 at 到末尾那一段`() {
        // 关键：前半句必须留着。整行替换会把「帮我看看」吃掉。
        assertEquals(
            "帮我看看 @/w/src ",
            MentionQuery.applySelection("帮我看看 @src", "@/w/src "),
        )
    }

    @Test
    fun `纯提及被完整替换`() {
        assertEquals("@/w/a.txt ", MentionQuery.applySelection("@a", "@/w/a.txt "))
    }

    @Test
    fun `目录结尾不带空格以便继续下拉`() {
        // 目录补 `/` 不补空格：用户接着打下一级正好接上。
        assertEquals("@/w/src/", MentionQuery.applySelection("@s", "@/w/src/"))
    }

    // ── absolute：相对路径拼绝对路径 ──

    @Test
    fun `cwd 与相对路径拼接`() {
        assertEquals("/home/u/proj/src/Main.kt", MentionQuery.absolute("/home/u/proj", "src/Main.kt"))
    }

    @Test
    fun `cwd 结尾多余斜杠不会拼出双斜杠`() {
        assertEquals("/home/u/proj/a.txt", MentionQuery.absolute("/home/u/proj/", "a.txt"))
    }

    @Test
    fun `cwd 为空时退化成相对路径`() {
        // 降级路径：解析不出工作目录时至少还能看出是哪个文件，比什么都不给强。
        assertEquals("a.txt", MentionQuery.absolute(null, "a.txt"))
        assertEquals("a.txt", MentionQuery.absolute("", "a.txt"))
    }
}
