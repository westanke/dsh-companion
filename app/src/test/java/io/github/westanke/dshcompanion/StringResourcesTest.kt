package io.github.westanke.dshcompanion

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 中英双站字符串资源的**结构与占位符**一致性。
 *
 * ## 为什么这值得一条门禁
 *
 * 这两类问题都不会让构建变红，但用户看得见：
 *
 * 1. **英文缺键** → 切到英文界面时那几个字符串**显示中文**（或反之）。本项目的用户里
 *    英文界面占相当一部分，而这种缺口只在「恰好切到英文、恰好用到那个界面」时才被发现。
 *    当前基线 453 / 453 双向对齐，但那是**手查**出来的 —— 加一条就会一直对齐。
 * 2. **占位符不一致** → 更严重：调用处按中文的占位符个数传参，英文若少一个 `%1$s`，
 *    `String.format` 会抛 `MissingFormatArgumentException`；多一个则会显示成
 *    「参数没填」的丑陋结果。**这类不一致是崩溃，不是显示问题。**
 *
 * 判据上的讲究与姊妹仓的坑位索引检查同族：**双向**（只查「中文有、英文没有」会漏掉反方向），
 * 外加**下限断言**（解析失败出 0 条时，双向检查会「双双为空而一致」）。
 */
class StringResourcesTest {

    @Test
    fun `中英 strings 键集合双向一致`() {
        val zh = stringResources(valuesFile("values"))
        val en = stringResources(valuesFile("values-en"))

        sanity(zh.size >= 400, "中文只解析出 ${zh.size} 条字符串（预期 ≥ 400），解析可能失败")
        sanity(en.size >= 400, "英文只解析出 ${en.size} 条字符串（预期 ≥ 400），解析可能失败")

        val problems = buildList {
            (zh.keys - en.keys).sorted().forEach { add("英文缺 `$it` —— 切到英文会显示中文") }
            (en.keys - zh.keys).sorted().forEach { add("中文缺 `$it` —— 切到中文会显示英文") }
        }
        report(problems, "中英 strings 键集合不一致")
    }

    @Test
    fun `中英 strings 的格式占位符一致`() {
        val zh = stringResources(valuesFile("values"))
        val en = stringResources(valuesFile("values-en"))

        sanity(zh.size >= 400, "中文只解析出 ${zh.size} 条字符串（预期 ≥ 400），解析可能失败")

        val problems = buildList {
            // 只比两边都有的键：缺键由上面那条测试负责，这里专管「都有但参数对不上」。
            zh.keys.intersect(en.keys).sorted().forEach { key ->
                val zhHolders = placeholders(zh.getValue(key))
                val enHolders = placeholders(en.getValue(key))
                if (zhHolders != enHolders) {
                    add("`$key` 占位符不一致：中文 ${zhHolders} / 英文 ${enHolders} —— 按中文传参会崩或显示错")
                }
            }
        }
        report(problems, "中英 strings 占位符不一致")
    }
}

/** `%s` / `%1$s` / `%d` / `%1$.2f` 之类的格式化占位符（`%%` 不算）。 */
private val FORMAT_PLACEHOLDER = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]""")

private fun placeholders(text: String): List<String> =
    FORMAT_PLACEHOLDER.findAll(text).map { it.value }.sorted().toList()

private fun sanity(condition: Boolean, message: String) {
    assertTrue("【门禁自检失败】$message", condition)
}

private fun report(problems: List<String>, headline: String) {
    if (problems.isNotEmpty()) {
        fail("$headline（${problems.size} 处）：\n" + problems.joinToString("\n") { "- $it" })
    }
}

private fun valuesFile(dirName: String): File = File(RES_DIR, "$dirName/strings.xml")

/**
 * 解析 `<string name="…">…</string>`（含跨行文本与 `translatable="false"` 之类属性）。
 *
 * 用 XML 解析器而不是正则：strings.xml 里合法的转义、跨行、注释、`<xliff:g>` 标记
 * 都会让正则写出「看起来能用、边界上悄悄漏键」的实现 —— 而这正是本条门禁要防的失败模式。
 */
private fun stringResources(file: File): Map<String, String> {
    if (!file.isFile) error("找不到字符串资源：${file.path}")
    val document = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = false }
        .newDocumentBuilder()
        .parse(file)
    val nodes = document.getElementsByTagName("string")
    return buildMap {
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            put(element.getAttribute("name"), element.textContent ?: "")
        }
    }
}

/** 资源目录：从当前工作目录向上找仓库根，再拼上 `app/src/main/res`。 */
private val RES_DIR: File by lazy {
    val relative = "app/src/main/res"
    var dir: File? = File(".").absoluteFile.canonicalFile
    while (dir != null && !File(dir, "$relative/values/strings.xml").isFile) {
        dir = dir.parentFile
    }
    val root = dir
        ?: error("找不到仓库根：从 ${File(".").absolutePath} 向上没有 $relative/values/strings.xml")
    File(root, relative)
}
