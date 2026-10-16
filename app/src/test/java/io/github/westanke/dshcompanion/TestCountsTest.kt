package io.github.westanke.dshcompanion

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 「测试类数 / 用例数」四处声明必须一致，且用例数与**源码静态计数**相符。
 *
 * ## 为什么值得一条门禁
 *
 * `AGENTS.md` §3 把「测试类数 / 用例数」列为**必须同步**的项（README 中英双站 + `TESTING.md`），
 * 并在同一条里写明「README 中英双站是最容易漂移的地方（v2.2.33–2.2.35 三版漂移过：
 * 中文停 2.2.32、英文停 24 类/256 用例）」。也就是说：**这个坑本项目真踩过三版**，
 * 而此前只有「发版前人工对账」一道防线。
 *
 * ## 判据（以及为什么这样选）
 *
 * 1. 三处声明（`README.md` / `README.en.md` / `AGENTS.md` §5）的类数与用例数**必须相同**；
 * 2. **用例数**必须等于源码里 `@Test` 注解的总数 —— 静态数得出来，不依赖测试结果 XML
 *    （XML 正在被本次运行写入，拿它做判据会自我引用）；
 * 3. **类数**只要求 ≥「含 `@Test` 的文件数」，因为一个 `.kt` 可以声明多个测试类
 *    （本仓确有两例：`PreviewKindTest.kt` 里还有 `HtmlPreviewTest`、
 *    `ArchitectureGuardTest.kt` 里还有 `ArchitectureGuardSelfCheckTest`），
 *    静态数不出类数，**能确证的部分才断言**。
 *
 * `docs/project/TESTING.md` 不在判据内：本仓那份是**按版本的历史演进记录**（「本 fork」段
 * 与「上游 v1.0.0 时期」段），本来就不该跟着当前数字走。
 *
 * ## 这条门禁会「要求你改文档」
 *
 * 新增一个测试后它就会红，直到你把三处数字同步 —— 这正是 `AGENTS.md` §3 想要的效果：
 * 让漂移在提交前被发现，而不是留到发版前的人工对账。
 */
class TestCountsTest {

    @Test
    fun `测试类数与用例数在三处声明一致且用例数与源码相符`() {
        val zh = declared(Regex("""单元测试：(\d+)\s*个测试类\s*/\s*(\d+)\s*用例"""), "README.md")
        val en = declared(Regex("""Unit tests:\s*(\d+)\s*test classes\s*/\s*(\d+)\s*cases"""), "README.en.md")
        val agents = declared(Regex("""当前\s*(\d+)\s*类\s*/\s*(\d+)\s*用例"""), "AGENTS.md")

        val problems = mutableListOf<String>()

        // 1) 三处必须都写得出数字、且彼此相同。
        val declarations = listOf("README.md" to zh, "README.en.md" to en, "AGENTS.md" to agents)
        declarations.forEach { (name, value) ->
            if (value == null) problems += "$name 里找不到「测试类数 / 用例数」声明（格式变了？）"
        }
        if (problems.isEmpty()) {
            val distinct = declarations.map { it.second }.distinct()
            if (distinct.size > 1) {
                problems += "三处声明不一致：" + declarations.joinToString("；") { (name, value) ->
                    "$name = ${value?.first} 类 / ${value?.second} 用例"
                }
            }
        }

        // 2) 用例数 == 源码 @Test 注解总数。
        val testFiles = sourceTestFiles()
        sanity(testFiles.size >= 30, "只扫到 ${testFiles.size} 个含 @Test 的测试文件（预期 ≥ 30），盘面不对")
        val annotations = testFiles.sumOf { file ->
            Regex("""^\s*@Test\b""", RegexOption.MULTILINE).findAll(file.readText()).count()
        }
        sanity(annotations >= 100, "只数到 $annotations 个 @Test（预期 ≥ 100），解析可能失效")

        val declaredCases = zh?.second
        if (declaredCases != null && declaredCases != annotations) {
            problems += "声明的用例数 $declaredCases 与源码里的 @Test 注解数 $annotations 不符 —— " +
                "加/删测试后请同步 README 中英双站与 AGENTS.md §5"
        }

        // 3) 类数至少有「含 @Test 的文件数」那么多（一个文件可含多个测试类）。
        val declaredClasses = zh?.first
        if (declaredClasses != null && declaredClasses < testFiles.size) {
            problems += "声明的测试类数 $declaredClasses 少于含 @Test 的文件数 ${testFiles.size}（不可能）"
        }

        if (problems.isNotEmpty()) {
            fail("测试数字不一致（${problems.size} 处）：\n" + problems.joinToString("\n") { "- $it" })
        }
    }
}

private fun declared(pattern: Regex, fileName: String): Pair<Int, Int>? {
    val file = File(REPO_ROOT, fileName)
    if (!file.isFile) error("找不到 $fileName（${file.path}）")
    val match = pattern.find(file.readText()) ?: return null
    return match.groupValues[1].toInt() to match.groupValues[2].toInt()
}

/** 测试源码目录下所有**含 `@Test`** 的 `.kt` 文件。 */
private fun sourceTestFiles(): List<File> =
    File(REPO_ROOT, "app/src/test").walkTopDown()
        .filter { it.isFile && it.name.endsWith(".kt") && it.readText().contains("@Test") }
        .toList()

private fun sanity(condition: Boolean, message: String) {
    assertTrue("【门禁自检失败】$message", condition)
}

/** 从当前工作目录向上找仓库根（判据：`app/src/test` 与 `AGENTS.md` 都在）。 */
private val REPO_ROOT: File by lazy {
    var dir: File? = File(".").absoluteFile.canonicalFile
    while (dir != null && !(File(dir, "app/src/test").isDirectory && File(dir, "AGENTS.md").isFile)) {
        dir = dir.parentFile
    }
    dir ?: error("找不到仓库根：从 ${File(".").absolutePath} 向上没有 app/src/test + AGENTS.md")
}
