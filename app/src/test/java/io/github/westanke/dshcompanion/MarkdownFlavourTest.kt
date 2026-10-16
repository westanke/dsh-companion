package io.github.westanke.dshcompanion

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码级守卫：所有 `Markdown(...)` 调用必须传 `flavour = GFMFlavourDescriptor()`。
 *
 * ## 为什么需要它（真机反馈的根因）
 *
 * 渲染库 `com.mikepenz.markdown` 默认用 **CommonMark** flavour，而 CommonMark
 * **不解析 GFM 表格** —— 于是 `MarkdownComponents.table` 回调根本不会被调用，
 * 表格会被当成普通段落渲染（表现：竖排密排、没有表格结构）。
 *
 * 这个坑**两次**咬到我们：
 * 1. 先以为「库自带的表格横向滚动会截断」，加了组件覆盖却不生效 —— flavour 没给，
 *    回调压根没被调；
 * 2. 修 flavour 时漏了其中一处调用。
 *
 * UI 测试抓不到它（渲染结果在设备上才看得出来），单测也不必起 Compose ——
 * 直接在源码里查每个调用点即可。**下一处新增 Markdown(...) 会由这条测试提醒。**
 *
 * 判据：`app/src/main/**/*.kt` 里每个 `Markdown(` 调用，从该行起往下 25 行内必须出现
 * `flavour = GFMFlavourDescriptor()`。用「行级窗口」而不是括号配平 —— 参数块里可能出现
 * 字符串字面量含括号，配平会被骗；25 行窗口对本项目足够（最长的调用约 20 行），
 * 且**宁可多报也不漏报**（漏报的代价是表格静默退化，比误报严重得多）。
 */
class MarkdownFlavourTest {

    @Test
    fun `every Markdown call site passes the GFM flavour`() {
        val root = repoRoot()
        val sourceRoot = File(root, "app/src/main")
        require(sourceRoot.isDirectory) { "找不到 $sourceRoot" }

        val missing = mutableListOf<String>()
        var checked = 0
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .forEach { file ->
                val lines = file.readText().lines()
                for ((index, line) in lines.withIndex()) {
                    if (!line.contains("Markdown(")) continue
                    val trimmed = line.trimStart()
                    // 只数「调用」：调用点的形态是 `Markdown(` 出现在 **箭头之后**（`-> Markdown(`）
                    // 或**行首**（`            Markdown(`）。别用排除法（`= isMarkdown(` 这种会被漏成误报）。
                    val before = line.substringBefore("Markdown(").trimEnd()
                    val isCall = before.endsWith("->") || before.isEmpty()
                    if (!isCall) continue

                    checked += 1
                    val window = lines.drop(index).take(WINDOW).joinToString("\n")
                    if ("flavour = GFMFlavourDescriptor()" !in window) {
                        missing += "${file.relativeTo(root).path}:${index + 1}"
                    }
                }
            }

        require(checked >= 6) { "只扫到 $checked 处 Markdown( 调用（预期 ≥ 6），盘面不对" }
        assertTrue(
            "下面这些 Markdown( 调用没传 GFMFlavourDescriptor() —— 表格不会被解析，" +
                "table 回调也不会被调用，表格会退化成普通段落：\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    private companion object {
        /** 参数块窗口：最长的一处调用约 20 行，留 25 行余量。 */
        const val WINDOW = 25
    }

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile.canonicalFile
        while (dir != null && !File(dir, "app/src/main").isDirectory) {
            dir = dir.parentFile
        }
        return dir ?: error("找不到仓库根（app/src/main）")
    }
}
