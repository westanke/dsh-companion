import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
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
     *  "Class Build_gradle.DocsCheckTask is a non-static inner class" 拒绝创建任务。
     *
     *  ⚠️ 必须标 **@Internal 而不是 @InputDirectory**（2026-10-20 CI 实锤）：
     * 仓库根目录**包含** app/build/，Gradle 9.5 的隐式依赖检查会认为本任务
     * 消费了 :app:generateDebugResources / processDebugNavigationResources /
     * generateDebugBuildConfig 的输出，于是报
     * "uses this output of task ':app:xxx' without declaring an explicit or implicit dependency"
     * 而**直接失败**。本地因为 `scripts/build.sh` 的任务图不同而不复现，只有 CI 挂。
     *
     * 改成 @Internal 后，Gradle 不再把它当输入；真正被读的那几个文件在下面
     * 单独用 @InputFile 声明 —— 依赖关系准确，且不会有「整棵目录算输入」的副作用。 */
    @get:Internal
    abstract val repoDirectory: DirectoryProperty

    /** 本任务实际读取的根级文件（声明为输入，让 Gradle 知道变化）。
     *  刻意**只声明这几个文件**而不是根目录：声明目录会把 app/build/ 一起算进去，
     *  触发上面那条隐式依赖检查。 */
    @get:InputFile
    abstract val rootReadme: RegularFileProperty

    @get:InputFile
    abstract val rootReadmeEn: RegularFileProperty

    @get:InputFile
    abstract val appBuildGradle: RegularFileProperty

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
            // 2026-10-21 新增：给 AI 与改代码的人用的两张导航图。
            // 它们不是散文型文档，末尾带机检锚点区，由下面「目录结构与接口说明」一段双向校验。
            "STRUCTURE.md",
            "INTERFACES.md",
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

        // ========== 目录结构与接口说明（2026-10-21 新增） ==========
        // 用户要求：「加目录结构与接口说明，方便 AI 阅读改代码，**后期改代码要同步改**」。
        // 前两句写两份 md 就够；难的是第三句——没有门禁的「请记得同步」等于没写。
        // 本仓已有实锤先例（README 双站漂移连续三版无人发现，见 PITFALLS.md 同名条目），
        // 所以这里把它做成机检。
        //
        // 为什么**双向**：
        // - 只查「文档 → 源码」（锚点路径必须存在）时，**新增文件永远发现不了** ——
        //   新加一个 ui/Xxx.kt 压根没登记，旧条目全部有效，检查照样全绿。那只是
        //   惩罚「删文件忘删条目」，漏掉真正高频的那一半。
        // - 只查「源码 → 文档」时，锚点里写错路径、指向不存在的文件同样发现不了。
        //
        // 为什么**下限断言**：解析失败（标记被改、表格格式变了、锚点被套进代码块）会让
        // 两个集合双双为空而「一致」，于是「零解析」被当成「零问题」——这是本仓坑位索引
        // 那一节最初撞上的假绿，教训已写进 docsCheck 的注释里。
        val sourceRoot = rootDirectory.resolve("app").resolve("src").resolve("main").resolve("java")
        val sourceFiles = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(rootDirectory).invariantSeparatorsPath }
            .toList()
        verify(sourceFiles.size > 50) {
            "app/src/main/java 下只扫到 ${sourceFiles.size} 个 .kt（预期 > 50）——源码树结构变了，" +
                "请同步更新 docsCheck 的扫描根目录，不许静默通过。"
        }

        val structureDoc = directory.file("STRUCTURE.md").asFile
        verify(structureDoc.isFile) { "Missing docs/project/STRUCTURE.md（目录结构，给 AI 读的代码地图）" }
        val structureText = structureDoc.readText()
        val structureBlock = anchorBlock(structureText, "<!-- structure-check:begin -->", "<!-- structure-check:end -->")
        verify(structureBlock != null) {
            "STRUCTURE.md 缺少 `<!-- structure-check:begin/end -->` 机检锚点（格式见文末），解析不出任何条目。" +
                "若改了锚点格式，请同步更新 docsCheck——不许静默通过。"
        }
        // RegexOption.MULTILINE 不能省：Kotlin 的 `^` 默认只匹配**输入开头**（不像 JS 的 /gm）。
        // 少了它，多行表格只会解析出 0 行 —— 下面那条下限断言会立刻红，但红的原因看着像「格式变了」，
        // 实际是解析器自己写错了。踩过一次，留注释。
        val structureRows = Regex("""^\|\s*`([^`]+)`\s*\|\s*(file|dir)\s*\|""", RegexOption.MULTILINE)
            .findAll(structureBlock ?: "")
            .map { it.groupValues[1].trim() to it.groupValues[2] }
            .toList()
        verify(structureRows.size >= 100) {
            "STRUCTURE.md 锚点只解析出 ${structureRows.size} 行（预期 ≥ 100），表格格式可能变了——不许静默通过。"
        }
        for ((relative, kind) in structureRows) {
            val target = rootDirectory.resolve(relative)
            verify(target.exists()) {
                "STRUCTURE.md 锚点里的 `$relative` 不存在——文件已删除/移动，请同步锚点（AGENTS.md §4.4）。"
            }
            verify(if (kind == "file") target.isFile else target.isDirectory) {
                "STRUCTURE.md 锚点把 `$relative` 标成 $kind，但它的实际类型不符。"
            }
        }
        val declaredSources = structureRows.map { it.first }.toSet()
        // **反向**：app/src/main/java 下每个 .kt 都必须登记。这条才是「新增文件必须同步文档」的兑现。
        for (relative in sourceFiles) {
            verify(relative in declaredSources) {
                "`$relative` 未登记进 STRUCTURE.md 机检锚点——新增/移动/删除 app/src/main/java 下文件时请同步（AGENTS.md §4.4）。"
            }
        }

        val interfacesDoc = directory.file("INTERFACES.md").asFile
        verify(interfacesDoc.isFile) { "Missing docs/project/INTERFACES.md（接口说明，签名与契约清单）" }
        val interfacesText = interfacesDoc.readText()
        val interfacesBlock = anchorBlock(interfacesText, "<!-- interfaces-check:begin -->", "<!-- interfaces-check:end -->")
        verify(interfacesBlock != null) {
            "INTERFACES.md 缺少 `<!-- interfaces-check:begin/end -->` 机检锚点（格式见文末），解析不出任何条目。"
        }
        val interfaceRows = Regex("""^\|\s*`([^`]+)`\s*\|\s*`([^`]+)`\s*\|\s*([a-z]+)\s*\|""", RegexOption.MULTILINE)
            .findAll(interfacesBlock ?: "")
            .map { Triple(it.groupValues[1], it.groupValues[2].trim(), it.groupValues[3]) }
            .toList()
        verify(interfaceRows.size >= 25) {
            "INTERFACES.md 锚点只解析出 ${interfaceRows.size} 行（预期 ≥ 25），表格格式可能变了——不许静默通过。"
        }
        val sourceCache = mutableMapOf<String, String>()
        for ((symbol, relative, _) in interfaceRows) {
            val target = rootDirectory.resolve(relative)
            if (!target.isFile) {
                verify(false) {
                    "INTERFACES.md 锚点里的 `$relative` 不存在——文件已删除/移动，请同步锚点（AGENTS.md §4.4）。"
                }
                continue
            }
            // 从签名片段里取最后一个标识符：`fun ChatHistory.displayItems(...)` → displayItems。
            val identifier = Regex("""[A-Za-z_][A-Za-z0-9_]*""").findAll(symbol).lastOrNull()?.value
            verify(identifier != null) {
                "INTERFACES.md 锚点条目「$symbol」里取不出标识符，格式应为 `<签名片段>` | `<文件路径>` | <类型>。"
            }
            val body = sourceCache.getOrPut(relative) { target.readText() }
            verify(Regex("""\b${Regex.escape(identifier!!)}\b""").containsMatchIn(body)) {
                "INTERFACES.md 锚点说 `$relative` 有「$identifier」，但源码里找不到——" +
                    "导出改名/搬文件后请同步（AGENTS.md §4.4）。"
            }
        }

        println("docsCheck: ${required.size} documents and ${tasks.size} tasks are consistent")
        println("docsCheck: root README bilingual / versionName / CHANGELOG / pitfalls baseline — $rootCheckCount new checks passed")
        println("docsCheck: STRUCTURE.md ${structureRows.size} anchors (${sourceFiles.size} source files all registered) / INTERFACES.md ${interfaceRows.size} anchors, both directions consistent")
    }

    /** 取出 begin/end 标记之间的正文；缺任意一个标记都返回 null（调用方按解析失败处理）。 */
    private fun anchorBlock(text: String, begin: String, end: String): String? {
        val from = text.indexOf(begin)
        if (from < 0) return null
        val to = text.indexOf(end, from + begin.length)
        if (to < 0) return null
        return text.substring(from + begin.length, to)
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
    rootReadme.set(layout.projectDirectory.file("README.md"))
    rootReadmeEn.set(layout.projectDirectory.file("README.en.md"))
    appBuildGradle.set(layout.projectDirectory.file("app/build.gradle.kts"))
}
