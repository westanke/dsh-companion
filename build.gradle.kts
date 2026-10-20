import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

abstract class DocsCheckTask : DefaultTask() {
    @get:InputDirectory
    abstract val docsDirectory: DirectoryProperty

    /** 仓库根目录（docs/project 的上一级）。根 README 与 app/build.gradle.kts 都在它下面，
     *  所以必须显式传进来——直接在任务类里写 layout.projectDirectory 会让 Kotlin 把
     *  layout 解析到脚本外层作用域，嵌套类因此捕获脚本实例，Gradle 会以
     *  "Class Build_gradle.DocsCheckTask is a non-static inner class" 拒绝创建任务。 */
    @get:InputDirectory
    abstract val repoDirectory: DirectoryProperty

    /** 根 README 侧新增检查项的计数，最后统一汇总打印。
     *  每条断言都走 verify()：既计数又 check()，「解析类」断言在一条都没匹配到时同样会红。 */
    private var rootCheckCount = 0

    /** 计数版断言。定义在类里而不是 @TaskAction 内：局部函数会被 Kotlin 编译成非静态内部类，
     *  Gradle 会以 "Class Build_gradle.DocsCheckTask is a non-static inner class" 拒绝创建任务。 */
    private fun verify(condition: Boolean, message: () -> String) {
        rootCheckCount++
        check(condition, message)
    }

    @TaskAction
    fun checkDocuments() {
        val directory = docsDirectory.get()
        val required = listOf(
            "README.md",
            "STATUS.md",
            "ROADMAP.md",
            "PARITY.md",
            "ARCHITECTURE.md",
            "SECURITY.md",
            "TESTING.md",
            "CHANGELOG-DEV.md",
        )
        val missing = required.filterNot { directory.file(it).asFile.isFile }
        check(missing.isEmpty()) { "Missing project documents: ${missing.joinToString()}" }

        val roadmap = directory.file("ROADMAP.md").asFile.readText()
        val tasks = Regex("""\| ([A-Z]+-\d{3}) \| ([a-z-]+) \|""")
            .findAll(roadmap)
            .associate { it.groupValues[1] to it.groupValues[2] }
        check(tasks.isNotEmpty()) { "ROADMAP.md contains no task rows." }
        val validStatuses = setOf("planned", "in-progress", "blocked", "done")
        val invalid = tasks.filterValues { it !in validStatuses }
        check(invalid.isEmpty()) { "Invalid task statuses: $invalid" }

        val status = directory.file("STATUS.md").asFile.readText()
        val unknown = Regex("""`([A-Z]+-\d{3})`""").findAll(status)
            .map { it.groupValues[1] }
            .filterNot(tasks::containsKey)
            .toSet()
        check(unknown.isEmpty()) { "STATUS.md references unknown tasks: ${unknown.joinToString()}" }

        // ========== 根 README 双站 + 版本同步（历史最高频失分点） ==========
        // 背景：docsCheck 原先只覆盖 docs/project/ 下 8 份文档，根 README 是敞口，
        // v2.2.33 → v2.2.35 连续三版漂移无人发现（见 docs/project/PITFALLS.md §README 双站漂移）。
        // 这一段把「根 README 中英双站 + app/build.gradle.kts 的 versionName + CHANGELOG /
        // PITFALLS 基线表」纳入同一道门禁。注意：下面的 required 里那个 "README.md" 指的是
        // docs/project/README.md（文档索引），与仓库根的 README.md 不是一回事，语义不动。
        // 局部函数会被 Kotlin 编译成非静态内部类，Gradle 会拒绝
        // （"Class Build_gradle.DocsCheckTask is a non-static inner class"），故断言走类方法 verify()。
        val rootDirectory = repoDirectory.get().asFile
        val readmeZh = rootDirectory.resolve("README.md")
        val readmeEn = rootDirectory.resolve("README.en.md")
        verify(readmeZh.isFile) { "Missing root document: README.md（与 docs/project/README.md 索引不是一回事）" }
        verify(readmeEn.isFile) { "Missing root document: README.en.md——英文版是最容易漏同步的那一份" }
        val readmeZhText = readmeZh.readText()
        val readmeEnText = readmeEn.readText()

        // ① bridge 安装门槛：中英双站都必须写，且写的值必须完全相等。
        //    历史事故是「安装段 ≥2.3.3、项目信息段 ≥2.3.1」自相矛盾（PITFALLS.md §README 双站漂移）。
        //    真实文本有三种写法，都必须认：
        //      需 bridge **≥ 2.4.1** / needs bridge **≥ 2.4.1**（裸词）
        //      `dsh-remote-bridge` **≥ 2.3.7**（反引号包着的插件名）
        //      [dsh-remote-bridge](https://…) **≥ 2.3.7**（链接）
        //    所以 bridge 与 **≥ 之间允许一段不含换行的填充（≤120 字符）。
        //    散落的「2.3.1+」「**2.3.7+**」是分项说明，不是加粗门槛，不参与相等性判定，
        //    免得把「分项」误判成「门槛」。
        val thresholdRegex = Regex("""bridge[^\n]{0,120}?\*\*≥ (\d+\.\d+\.\d+)\*\*""")
        val thresholdsZh = thresholdRegex.findAll(readmeZhText).map { it.groupValues[1] }.toSortedSet()
        val thresholdsEn = thresholdRegex.findAll(readmeEnText).map { it.groupValues[1] }.toSortedSet()
        verify(thresholdsZh.isNotEmpty()) {
            "README.md 未解析出任何 bridge 安装门槛（形如「需 bridge **≥ X.Y.Z**」或「`dsh-remote-bridge` **≥ X.Y.Z**」。" +
                "若 README 改了写法，请同步更新 docsCheck 正则——不许静默通过。"
        }
        verify(thresholdsEn.isNotEmpty()) {
            "README.en.md 未解析出任何 bridge 安装门槛（形如「needs bridge **≥ X.Y.Z**」或「`dsh-remote-bridge` **≥ X.Y.Z**」。" +
                "若 README 改了写法，请同步更新 docsCheck 正则——不许静默通过。"
        }
        verify(thresholdsZh == thresholdsEn) {
            "README 中英双站的 bridge 安装门槛不一致：中文 $thresholdsZh / 英文 $thresholdsEn。"
        }

        // ② 当前版本号：真值只有一个来源 —— app/build.gradle.kts 的 versionName（该文件在 app/ 子目录）。
        //    README 双站都必须与它一致；这正是当年「中文停 v2.2.32 三版没人发现」的那个数。
        val appBuildFile = rootDirectory.resolve("app").resolve("build.gradle.kts")
        verify(appBuildFile.isFile) { "Missing app/build.gradle.kts（versionName 的真值来源）" }
        val versionName = Regex("""versionName\s*=\s*"([^"]+)"""").findAll(appBuildFile.readText())
            .map { it.groupValues[1] }
            .lastOrNull()
        verify(versionName != null) {
            "app/build.gradle.kts 未解析出 versionName（形如 versionName = \"2.2.45\"）。" +
                "若写法变了，请同步更新 docsCheck 正则——不许静默通过。"
        }
        val version = versionName!!
        val readmeZhVersions = Regex("""当前版本[：:]\s*`v(\d+\.\d+\.\d+)`""").findAll(readmeZhText)
            .map { it.groupValues[1] }
            .toSortedSet()
        verify(readmeZhVersions.isNotEmpty()) {
            "README.md 未解析出当前版本（形如「当前版本：`vX.Y.Z`）。" +
                "若写法变了，请同步更新 docsCheck 正则——不许静默通过。"
        }
        verify(readmeZhVersions == setOf(version)) {
            "README.md 的当前版本 $readmeZhVersions 与 app/build.gradle.kts 的 versionName=$version 不一致。"
        }
        val readmeEnVersions = Regex("""Version:\s*`v(\d+\.\d+\.\d+)`""").findAll(readmeEnText)
            .map { it.groupValues[1] }
            .toSortedSet()
        verify(readmeEnVersions.isNotEmpty()) {
            "README.en.md 未解析出当前版本（形如「- Version: `vX.Y.Z`」）。" +
                "若写法变了，请同步更新 docsCheck 正则——不许静默通过。"
        }
        verify(readmeEnVersions == setOf(version)) {
            "README.en.md 的 current version $readmeEnVersions 与 app/build.gradle.kts 的 versionName=$version 不一致。"
        }

        // ③ CHANGELOG 必须有当前版本的条目，否则「改了版本号没写日志」能被当场抓住。
        val changelog = directory.file("CHANGELOG-DEV.md").asFile.readText()
        verify(
            Regex("""^#+\s+v${Regex.escape(version)}\b""", RegexOption.MULTILINE).containsMatchIn(changelog)
        ) {
            "docs/project/CHANGELOG-DEV.md 缺少当前版本 v$version 的条目（形如 `## v$version · …`）。"
        }

        // ④ PITFALLS 的 bridge 版本基线表：表中最高版本必须 ≥ README 声明的门槛。
        //    防止表格漏更让排错者低估门槛——2.3.8 / 2.4.1 / 2.4.2 三版都漏过，害人得出「2.3.7 就够」。
        //    只认表格行（以 `|` 开头）里的 `**X.Y.Z**`，正文里单独出现的版本号不算数。
        val pitfalls = directory.file("PITFALLS.md").asFile.readText()
        val baselineVersions = Regex("""^\|[^\n]*\*\*(\d+\.\d+\.\d+)\*\*""", RegexOption.MULTILINE)
            .findAll(pitfalls)
            .map { it.groupValues[1] }
            .toSortedSet()
        verify(baselineVersions.isNotEmpty()) {
            "docs/project/PITFALLS.md 的 bridge 版本基线表未解析出任何版本（形如 `**X.Y.Z**`）。" +
                "若表格式变了，请同步更新 docsCheck 正则——不许静默通过。"
        }
        val versionComparator = Comparator<String> { a, b -> compareVersions(a, b) }
        val baselineMax = baselineVersions.maxWithOrNull(versionComparator)!!
        val threshold = thresholdsZh.maxWithOrNull(versionComparator)!!
        verify(compareVersions(baselineMax, threshold) >= 0) {
            "docs/project/PITFALLS.md 的 bridge 版本基线表最高只到 $baselineMax，" +
                "低于 README 声明的门槛 $threshold——表格疑似漏更，请补表（PITFALLS.md §bridge 版本基线）。"
        }

        println("docsCheck: ${required.size} documents and ${tasks.size} tasks are consistent")
        println("docsCheck: root README bilingual / versionName / CHANGELOG / pitfalls baseline — $rootCheckCount new checks passed")
    }

    /** 三段 X.Y.Z 语义化比较：a > b 返回正数，相等返回 0。字符串比大小会把 2.4.10 排到 2.4.2 之后，故不能直接用 compareTo。 */
    private fun compareVersions(a: String, b: String): Int {
        val left = a.split(".").map { it.toIntOrNull() ?: 0 }
        val right = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(index) { 0 }
            val r = right.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }
}

tasks.register<DocsCheckTask>("docsCheck") {
    group = "verification"
    description = "Checks the persistent Android project tracking documents."
    docsDirectory.set(layout.projectDirectory.dir("docs/project"))
    repoDirectory.set(layout.projectDirectory)
}
