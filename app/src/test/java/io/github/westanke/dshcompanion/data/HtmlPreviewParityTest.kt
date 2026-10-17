package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 钉死「HTML 文件在两个入口都算 HTML 预览」这条约定。
 *
 * 真机症状（2026-10-17）：从**文件浏览器**点开 `.html` 看到的是一屏 `<style>` 源码，
 * 而从**会话附件面板**点同一个文件是结构预览 —— 同一份文件两个入口两副面孔。
 *
 * 根因：`PreviewKind.HTML` 这个类型定义了、判据也对，但消费方只有
 * `ui/chat/ChatSessionFiles.kt` 一处；`ui/FilesScreen.kt` 里没有对应分支，
 * 于是落到最后的通用源码视图。类型定义了没人接，编译期不会有任何提示。
 *
 * 第一条用纯函数钉判据，第二条用源码扫描钉「两个入口都真的渲染了它」——
 * 后者才能防住「判据对但没人消费」这类静默缺口。
 */
class HtmlPreviewParityTest {

    @Test
    fun `html 家族都判为 HTML 预览`() {
        listOf("report.html", "index.htm", "page.xhtml").forEach { name ->
            assertEquals("$name 应判为 HTML", PreviewKind.HTML, previewKind(name))
        }
    }

    @Test
    fun `大写扩展名同样判为 HTML`() {
        assertEquals(PreviewKind.HTML, previewKind("REPORT.HTML"))
    }

    /** 两个入口都必须引用 `PreviewKind.HTML` 的处理，缺一个就是那个入口的缺口。 */
    @Test
    fun `文件浏览器与会话附件面板都消费 PreviewKind HTML`() {
        val root = repoRoot()
        listOf(
            "app/src/main/java/io/github/westanke/dshcompanion/ui/FilesScreen.kt",
            "app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatSessionFiles.kt",
        ).forEach { relative ->
            val text = File(root, relative).readText()
            assertTrueAt(
                "$relative 里没有 `PreviewKind.HTML` 分支 —— 这个入口的 .html 会退化成源码视图",
                text.contains("PreviewKind.HTML"),
            )
        }
    }

    private fun assertTrueAt(message: String, condition: Boolean) {
        if (!condition) throw AssertionError(message)
    }

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile.canonicalFile
        while (dir != null && !(File(dir, "app/src/test").isDirectory && File(dir, "AGENTS.md").isFile)) {
            dir = dir.parentFile
        }
        return dir ?: error("找不到仓库根")
    }
}
