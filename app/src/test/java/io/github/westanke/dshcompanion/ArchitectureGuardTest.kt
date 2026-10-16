package io.github.westanke.dshcompanion

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/*
 * ============================================================================
 * 架构纪律的可执行门禁
 * ============================================================================
 *
 * ## 这个文件为什么存在
 *
 * `AGENTS.md` §4.1/§4.2/§4.3 与 §5 立了一堆纪律：分层、800 行预算、`@patch` 五要素、
 * 测试指向真实测试。但这些纪律此前**只活在文档里**，全靠人自觉 —— 文件里第 8 节
 * 甚至自己承认「台账暂不进 CI（Kotlin 侧无等价解析器）」「四要素登记表暂不入 CI」。
 *
 * 本轮把这个顾虑兑现掉：**不需要 AST，文本匹配就够**（同源制度的上游用 4 个 XCTest
 * 扫 Swift 源码禁止 reducer 符号，手段也只是 `contains` + 文件存在性断言）。
 * 于是这里用纯 JVM + `java.io.File` 递归读行，把纪律变成会红的断言。
 *
 * ## 危险失败模式：静默全绿
 *
 * 门禁最可怕的不是「太严」，是**「路径解析错了、什么都没扫到、于是全绿」** ——
 * 那比没有门禁更坏，因为它会冒充证据。所以每个测试开头都先跑「盘面自检」
 * (`sanity`)：仓库根必须定位成功（否则抛错而不是跳过）、扫到的文件数必须 ≥ 阈值、
 * 关键正则必须真的命中过东西。任何一条不成立就**红**。
 *
 * 路径解析是自愈式的：Gradle 跑单测时工作目录通常是模块目录（`app/`），不是仓库根，
 * 所以从 `File(".")` 向上找到同时含 `app/src/main/java/` 与 `AGENTS.md` 的目录，
 * **不写死绝对路径**。刻意不把 `docs/project/TECH-DEBT.md` 也塞进这个判据：
 * 那样台账万一被删，报错会变成含糊的「定位仓库根失败」，而不是「找不到台账」。
 *
 * ## 本次实测盘面（2026-10-17，基线 commit 53f8be3）
 *
 * 注意：测量时**另有并发写者正在改主源码**（`HarnessModels.kt`、`SettingsScreen.kt`、
 * 新增 `data/ChatDisplayGroups.kt` 等），所以下面的数字是快照，不要当常量背。门禁本身
 * 不依赖任何硬编码行号/文件数，只依赖「盘面自检阈值 + 目录语义」，因此对移动的树免疫。
 *
 * - 最大文件 `ui/chat/ChatSheets.kt` = 797 行 → 预算内；
 * - `SharedPreferences` 在 `ui/` 下只命中 1 处，且是整行注释（`SettingsScreen.kt`）；
 *   `getSharedPreferences(` 在 `ui/` 下 0 命中 —— 所以判据取符号而不是那个字面串；
 * - `// @patch` 在主源码里 **0 处**（本条门禁「已上膛未击发」，见 SelfCheck）；
 * - `OkHttpClient` 构造点 **2 处**：`data/DshClient.kt:548` 与 `web/ChatWebBridge.kt:75`
 *   → 第 4 条断言**当前会红**，详见该测试的 KDoc；
 * - `fun …displayItems(` 声明 **3 处**，全部在 `data/`。
 */
class ArchitectureGuardTest {

    // ---------------------------------------------------------------- §4.2 行数预算

    /**
     * 守 `AGENTS.md` §4.2：**800 行/文件**；超预算文件必须先登记进
     * `docs/project/TECH-DEBT.md` 再写码（「新增超预算文件必须先入台账再写码」）。
     *
     * 豁免不是「在台账里被提到过」就算 —— 台账那行必须**有记录的行数且 ≥ 实际行数**，
     * 也就是「有人按 §4.2 带理由上调过这个文件的预算并更新了数字」。只在散文里提一嘴
     * 路径、或留着过期的旧行数（台账自己说「不留旧值，旧值会骗人」），一律不算登记。
     */
    /**
     * `AGENTS.md` 的坑位索引与 `PITFALLS.md` 的正文标题必须**双向一致**。
     *
     * 索引里写的是「触发词」，正文里是同名 `###` 标题。两边漂移时，读者照着索引去翻会
     * **翻不到** —— 而这类不一致不会让任何别的检查变红，所以它得自己有一条。
     *
     * 判据上有三处讲究（每一条都是踩过或差点踩的）：
     * - **双向**：只查「索引 → 正文」时，索引一旦解析失败（0 条）会永远满足、被判成通过。
     * - **下限断言**：第二条保险 —— 小节格式变了导致只解析出几条（甚至 0 条）时，
     *   双向检查可能「双双为空而一致」，下限直接对着「解析失败」报警
     *   （姊妹仓同一检查第一次跑就靠它抓出了「PITFALLS.md 不在必需文档缓存里」）。
     * - **不按编号找小节**：本仓索引在 §7、姊妹仓在 §6 —— 写死编号必然有一边解析成空。
     */
    @Test
    fun `坑位索引与 PITFALLS 正文双向一致`() {
        val lines = AGENTS_MD.readLines()
        val start = lines.indexOfFirst { it.startsWith("## ") && it.contains("坑位索引") }
        sanity(start >= 0, "AGENTS.md 里找不到「坑位索引」小节")
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith("## ") }
        val section = if (end < 0) rest else rest.take(end)

        val indexed = section.mapNotNull { PITFALL_INDEX_ENTRY.find(it.trim())?.groupValues?.get(1) }
        val titled = PITFALLS.readLines()
            .mapNotNull { PITFALL_TITLE.find(it)?.groupValues?.get(1)?.trim() }

        sanity(indexed.size >= 10, "坑位索引只解析出 ${indexed.size} 条（预期 ≥ 10），小节格式可能变了")
        sanity(titled.size >= 10, "PITFALLS.md 只解析出 ${titled.size} 个标题（预期 ≥ 10）")

        val problems = buildList {
            indexed.filterNot { it in titled }
                .forEach { add("索引里有「$it」，PITFALLS.md 没有同名标题") }
            titled.filterNot { it in indexed }
                .forEach { add("PITFALLS.md 有「$it」，没登记进 AGENTS.md 的坑位索引") }
        }
        if (problems.isNotEmpty()) {
            fail("坑位索引与正文不一致（${problems.size} 处）：\n" + problems.joinToString("\n") { "- $it" })
        }
    }

    @Test
    fun `行数预算 800 超预算文件必须已在债务台账登记`() {
        val debt = readDebtRegistry()
        val files = ktFiles(MAIN_ROOT)
        sanity(files.size >= 40, "app/src/main/java 下只扫到 ${files.size} 个 .kt（预期 ≥ 40），盘面不对")

        val violations = mutableListOf<String>()
        var exempt = 0
        files.forEach { file ->
            val actual = file.readLines().size
            if (actual <= LINE_BUDGET) return@forEach
            val candidates = listOfNotNull(packagePath(file), repoPath(file))
            val registered = registryLine(debt, candidates, actual)
            if (registered == null) {
                violations += buildString {
                    append("- 文件：${repoPath(file)}（台账里的写法：${packagePath(file) ?: repoPath(file)}）\n")
                    append("  实际行数：$actual，超出预算 ${actual - LINE_BUDGET} 行\n")
                    append("  台账状态：docs/project/TECH-DEBT.md 里没有「表格行 + 记录行数 ≥ $actual」的登记")
                }
            } else {
                exempt++
            }
        }

        assertTrue(
            "违反 AGENTS.md §4.2（行数预算 800 行/文件，债务台账只许收紧）：\n" +
                violations.joinToString("\n") + "\n" +
                "怎么办：把文件按职责拆到 800 行以内（首选）；确需保留就按 §4.2 在 " +
                "docs/project/TECH-DEBT.md 里立一行表格登记「路径 + 新行数 + 为什么是债 + 清账阶段」，" +
                "并保证登记的行数 ≥ 实际行数。已合规登记并豁免的文件数：$exempt。",
            violations.isEmpty(),
        )
    }

    // ---------------------------------------------------------------- §4.1 ui 不读 SharedPreferences

    /**
     * 守 `AGENTS.md` §4.1：Compose 表现层**不得**读 `SharedPreferences` 当状态。
     * 那会导致「点了开关不动、得再点别的才跳」（见 `PITFALLS.md` §Compose 状态与 SharedPreferences）。
     *
     * **判据刻意取 `SharedPreferences` 符号，而不是 `getSharedPreferences(`**：
     * 本仓库 `ui/` 下 `getSharedPreferences(` **一次都不出现**（零命中），按字面串写就是一条
     * 恒绿的空转断言；而唯一那处相关命中恰好只含裸符号。取符号还顺带拦住
     * `import android.content.SharedPreferences`、`prefs: SharedPreferences` 这类同性质的写法
     * （界面层拿到 SharedPreferences 实例 = 同一个病）。**这是收紧，不是放宽。**
     *
     * **刻意放行整行注释**：`ui/SettingsScreen.kt` 里有一行解释性注释
     * （「读 state 而不是 viewModel：读 SharedPreferences 不是 Compose 状态…」）——
     * 它是在**解释这条纪律**，不是违反它。让门禁为一句正确的话变红，只会教人删掉那句话。
     *
     * 放行范围严格限定为**整行**注释（`//` `/*` `*` `*/` 开头）。行尾注释里的命中**仍然报错**：
     * 宁可让人把说明挪到独立一行，也不要在「行内注释剥离」这种半吊子解析上漏掉真实代码。
     */
    @Test
    fun `ui 层不得把 SharedPreferences 当状态`() {
        val uiFiles = ktFiles(MAIN_ROOT).filter { packagePath(it)?.startsWith("ui/") == true }
        sanity(uiFiles.size >= 10, "ui/ 下只扫到 ${uiFiles.size} 个 .kt（预期 ≥ 10），盘面不对")

        val violations = mutableListOf<String>()
        val allowedComments = mutableListOf<String>()
        uiFiles.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (!line.contains(PREF_SYMBOL)) return@forEachIndexed
                val at = "${repoPath(file)}:${index + 1}"
                if (isCommentLine(line)) allowedComments += at else violations += "- $at 代码里出现 `$PREF_SYMBOL`：${line.trim()}"
            }
        }

        assertTrue(
            "违反 AGENTS.md §4.1：Compose 表现层不得读 SharedPreferences 当状态" +
                "（根因见 PITFALLS.md §Compose 状态与 SharedPreferences：读了不会触发重组）。\n" +
                violations.joinToString("\n") + "\n" +
                "怎么办：`ui/` 只消费 `HarnessState`；偏好值由 ViewModel（data/ 层）读出来放进 state，" +
                "界面用 `state.xxx` + `viewModel::setXxx`。\n" +
                "（本次放行的整行注释：${allowedComments.joinToString("、").ifEmpty { "无" }}——" +
                "放行是刻意的，理由见本测试 KDoc。）",
            violations.isEmpty(),
        )
    }

    // ---------------------------------------------------------------- §4.3 @patch 五要素

    /**
     * 守 `AGENTS.md` §4.3（也是 `TECH-DEBT.md` DEBT-004 的「清账条件」）：
     * 凡因上游/内核/bridge 行为写的适配代码，必须在源码里自报
     * `id:` / `for:` / `why:` / `until:` / `test:` 五要素，且 `test:` 指向的测试**必须真实存在**。
     *
     * 扫描范围 = 主源码 + 测试源码（测试里也可能有补丁），**排除本文件自身**：
     * 本文件的夹具里就写着 `// @patch` 字样，自扫等于自己咬自己（且循环无意义）。
     *
     * 注意：当前仓库 `app/src` 下 **0 个 `@patch` 块**，所以这条此刻是「已上膛未击发」。
     * 空转会变成假绿，因此解析器本身由 `ArchitectureGuardSelfCheckTest` 用夹具盯着。
     */
    @Test
    fun `patch 补丁五要素齐全 且 test 指向真实测试`() {
        val files = (ktFiles(MAIN_ROOT) + ktFiles(TEST_ROOT))
            .filter { it.canonicalFile != GUARD_FILE.canonicalFile }
        sanity(files.size >= 60, "主源码 + 测试源码只扫到 ${files.size} 个 .kt（预期 ≥ 60），盘面不对")

        val testClasses = ktFiles(TEST_ROOT).map { it.nameWithoutExtension }.toSet()
        val ids = mutableMapOf<String, String>()
        val problems = mutableListOf<String>()
        var blocks = 0

        files.forEach { file ->
            parsePatchBlocks(repoPath(file), file.readLines()).forEach { block ->
                blocks++
                val at = "${block.file}:${block.lineNo}（// @patch）"

                val missing = PATCH_FIELDS.filterNot { block.fields[it]?.isNotBlank() == true }
                if (missing.isNotEmpty()) {
                    problems += "- $at 缺字段 ${missing.joinToString("、")}；AGENTS.md §4.3 要求五要素齐全"
                }

                block.fields["id"]?.takeIf { it.isNotBlank() }?.let { id ->
                    if (!KEBAB.matches(id)) {
                        problems += "- $at id `$id` 不是 kebab-case（形如 `fix-ws-handshake-origin`）"
                    }
                    val previous = ids.put(id, at)
                    if (previous != null) problems += "- $at id `$id` 与 $previous 重复（登记表要靠它去重）"
                }

                block.fields["test"]?.let { target ->
                    val names = TEST_CLASS_NAME.findAll(target).map { it.value }.toList()
                    if (names.isEmpty()) {
                        problems += "- $at test 字段里没有 `XxxTest` 类名：`$target`"
                    }
                    names.filterNot { it in testClasses }.forEach { name ->
                        problems += "- $at test 指向 `$name`，但 app/src/test 下没有 `$name.kt`"
                    }
                }
            }
        }

        assertTrue(
            "违反 AGENTS.md §4.3（@patch 五要素，test 必须指向真实存在的测试）：\n" +
                problems.joinToString("\n") + "\n" +
                "怎么办：补全五要素（id/for/why/until/test），或把 test 改成真实存在的测试类名。" +
                "没有 @patch 的适配代码 = 没人认领的补丁，后来者不敢删，只会在上面继续堆。\n" +
                "本次共解析 @patch 块：$blocks。",
            problems.isEmpty(),
        )
    }

    // ---------------------------------------------------------------- §4.1 唯一网络边界

    /**
     * 守 `AGENTS.md` §4.1：「`DshClient` 是唯一网络边界」。
     *
     * 判据比字面要求（`OkHttpClient(`）**更严**，因为字面串在本仓库**一处都不命中**：
     * 真实写法是 `OkHttpClient.Builder()...build()`。只按字面串扫会得到一条恒绿的空转断言 ——
     * 那种门禁比没有更坏。这里同时认 `OkHttpClient(` 与 `OkHttpClient.Builder(`。
     *
     * 白名单只有 `data/DshClient.kt`。若判定别处（如 WebView 的通用转发桥）是合理特例，
     * **不许只改这里的白名单让测试变绿**：要同时改 `AGENTS.md` §4.1（把豁免写进宪法）
     * 并在 `TECH-DEBT.md` 立账说明为什么它绕开单一网络边界。
     */
    @Test
    fun `DshClient 是唯一网络边界`() {
        val files = ktFiles(MAIN_ROOT)
        sanity(files.size >= 40, "app/src/main/java 下只扫到 ${files.size} 个 .kt（预期 ≥ 40），盘面不对")

        var sites = 0
        val violations = mutableListOf<String>()
        files.forEach { file ->
            val pkgPath = packagePath(file) ?: repoPath(file)
            file.readLines().forEachIndexed { index, line ->
                if (isCommentLine(line)) return@forEachIndexed
                val hit = OKHTTP_CONSTRUCTION.find(line) ?: return@forEachIndexed
                sites++
                if (pkgPath !in NETWORK_BOUNDARY_ALLOWLIST) {
                    violations += "- ${repoPath(file)}:${index + 1} 构造了 `${hit.value}`：${line.trim()}"
                }
            }
        }
        // 正则失效（比如以后网关换成别的客户端库）必须红，不能悄悄变成恒绿。
        sanity(sites >= 1, "全仓扫不到任何 OkHttp 客户端构造点，本断言的正则已失效")

        assertTrue(
            "违反 AGENTS.md §4.1（DshClient 是唯一网络边界，阻塞 IO 只在 Dispatchers.IO）：\n" +
                violations.joinToString("\n") + "\n" +
                "怎么办：优先复用 DshClient 的 http client（或让它开一个「通用转发」方法），" +
                "凭据仍从 SecureConnectionStore / ActiveConnection 取。\n" +
                "若确属合理特例：必须同时改 AGENTS.md §4.1 写明豁免理由 + 在 TECH-DEBT.md 立账 + " +
                "把文件加进本测试的 NETWORK_BOUNDARY_ALLOWLIST（当前白名单：$NETWORK_BOUNDARY_ALLOWLIST）。",
            violations.isEmpty(),
        )
    }

    // ---------------------------------------------------------------- §4.1 会话展示单一出口

    /**
     * 守 `AGENTS.md` §4.1：「会话展示走 `ChatPresentation` 单一出口」。
     *
     * **为什么换了断言**：任务原话是「`ChatPresentation` 是唯一产出 `displayItems` 的地方」，
     * 这与代码事实不符 —— 全仓有 **3 个** `displayItems` 声明，都是**刻意**的职责拆分
     * （`ChatPresentation.kt` 单一出口门面、`ChatHistoryMapping.kt` 逐事件映射、
     * `ChatModels.kt` 消息块级映射）。照字面写要么恒红、要么得把 3 个文件写死成白名单
     * （那样任何人往 data/ 里加拆分都会误报，断言不稳定）。
     *
     * 换成三条**稳定且真守分层**的检查：
     * 1. 所有 `fun …displayItems(` 的**声明**必须落在 `data/` 包内 —— 界面层不许自建第二个出口；
     * 2. 单一出口的门面 `ChatPresentation.kt` 仍在，且仍声明 `ChatHistory.displayItems()`；
     * 3. `ui/` 里不得出现内部事件字面量（`"step/end"` `"turn/end"` `"chat.message.delta"`）——
     *    「哪些事件不渲染成卡片」这个决策必须留在 data/ 层，不能在界面里重写一遍。
     */
    @Test
    fun `会话展示仍只由 data 层产出`() {
        val files = ktFiles(MAIN_ROOT)
        sanity(files.size >= 40, "app/src/main/java 下只扫到 ${files.size} 个 .kt（预期 ≥ 40），盘面不对")

        val producers = files.filter { file ->
            codeLines(file).any { DISPLAY_ITEMS_DECL.containsMatchIn(it.second) }
        }
        sanity(producers.isNotEmpty(), "全仓扫不到 displayItems 的声明，本断言的正则已失效")

        val outsideData = producers.filterNot { packagePath(it)?.startsWith("data/") == true }
        val facadeDeclares = FACADE_FILE.isFile &&
            FACADE_FILE.readLines().any { CHAT_HISTORY_FACADE.containsMatchIn(it) }
        val uiLeaks = files
            .filter { packagePath(it)?.startsWith("ui/") == true }
            .flatMap { file ->
                codeLines(file)
                    .filter { (_, line) -> INTERNAL_EVENT_LITERALS.any { line.contains(it) } }
                    .map { (no, text) -> "- ${repoPath(file)}:$no 出现内部事件字面量：${text.trim()}" }
            }

        val problems = mutableListOf<String>()
        if (outsideData.isNotEmpty()) {
            problems += "- 以下文件在 data/ 之外声明了 displayItems（界面层不该产出展示项）：\n" +
                outsideData.joinToString("\n") { "    ${repoPath(it)}" }
        }
        if (!facadeDeclares) {
            problems += "- 单一出口门面丢了：${repoPath(FACADE_FILE)} 里找不到 `fun ChatHistory.displayItems(`"
        }
        problems += uiLeaks

        assertTrue(
            "违反 AGENTS.md §4.1（会话展示走 ChatPresentation 单一出口）：\n" +
                problems.joinToString("\n") + "\n" +
                "怎么办：展示项只由 data/ 层的 displayItems 产出，ui/ 只消费 `HarnessState`；" +
                "`step/end` `turn/end` token 序列与未知内部事件不渲染为聊天卡片，这个过滤留在 data/ 层。\n" +
                "（本条是「ChatPresentation 唯一产出 displayItems」的稳定化版本，理由见本测试 KDoc。当前声明点：" +
                producers.joinToString("、") { packagePath(it) ?: repoPath(it) } + "）",
            problems.isEmpty(),
        )
    }
}

/**
 * 门禁自身的自检。
 *
 * 存在的唯一理由：**防止上面那些断言空转成假绿**。
 * 主源码里现在 0 个 `@patch` 块、台账里现在 0 个超预算文件 —— 两条分支的真实触发条件
 * 眼下都不存在。夹具在这里把「残缺块会被拒收」「过期行数不算登记」钉住，
 * 这样等真有人写补丁/超预算时，门禁是真的会响，而不是第一次响的时候才发现它坏了。
 */
class ArchitectureGuardSelfCheckTest {

    @Test
    fun `patch 解析器认完整块 拒残缺块`() {
        val complete = listOf(
            "internal fun normalize() {",
            "    // @patch",
            "    // id: fix-ws-handshake-origin",
            "    // for: bridge 2.3.x 的 WebSocket 握手只认 http(s) 同源 URL",
            "    // why: 官方无版本化写法，构造 HttpUrl 时改写 ws:// 会被 OkHttp 拒掉",
            "    // until: bridge 提供版本化握手 API",
            "    // test: DshClientTest",
            "    doIt()",
            "}",
        )
        val parsed = parsePatchBlocks("data/Foo.kt", complete)
        assertTrue("完整块必须被认出来，实际解析到 ${parsed.size} 个", parsed.size == 1)
        assertTrue("五要素必须全部解析到：${parsed[0].fields}", parsed[0].fields.keys.containsAll(PATCH_FIELDS))
        assertTrue("块起始行号应为 2，实际 ${parsed[0].lineNo}", parsed[0].lineNo == 2)

        val missingUntil = complete.filterNot { it.contains("// until:") }
        val broken = parsePatchBlocks("data/Foo.kt", missingUntil)
        assertTrue(
            "缺 until 的块必须被判缺字段",
            PATCH_FIELDS.filterNot { broken[0].fields[it]?.isNotBlank() == true } == listOf("until"),
        )

        // 空值等于没写：`// why:` 后面什么都没有，不算填了 why。
        val emptyWhy = complete.map { if (it.contains("// why:")) "    // why:" else it }
        assertTrue(
            "空值字段必须算缺失",
            PATCH_FIELDS.filterNot { parsePatchBlocks("data/Foo.kt", emptyWhy)[0].fields[it]?.isNotBlank() == true } == listOf("why"),
        )

        // 遇到代码行就收窗，不能把下面无关的 `// test:` 蹭进来。
        val crossFile = listOf(
            "// @patch",
            "// id: a-b",
            "fun x() {}",
            "// test: DshClientTest",
        )
        assertTrue(
            "块窗口必须止于代码行",
            parsePatchBlocks("data/Foo.kt", crossFile)[0].fields["test"] == null,
        )
    }

    @Test
    fun `台账登记解析器 只认表格行 且行数要够`() {
        val debt = """
            | 文件 | 行数 | 说明 |
            |---|---|---|
            | `ui/chat/Big.kt` | 900 | 从 ChatScreen 拆出，下阶段再拆 |
            | `ui/chat/Stale.kt` | 700 | 旧数字没更新 |
            prose 里提到 `ui/chat/Prose.kt` 但这不是表格行。
            | `ui/chat/Tiny.kt` |
        """.trimIndent()

        assertTrue(
            "表格行 + 行数 900 ≥ 实际 810 才算登记",
            registryLine(debt, listOf("ui/chat/Big.kt", "app/src/main/java/ui/chat/Big.kt"), 810)?.first == 3,
        )
        assertTrue(
            "登记的行数小于实际 = 过期的旧值，不算登记",
            registryLine(debt, listOf("ui/chat/Stale.kt"), 810) == null,
        )
        assertTrue(
            "只在散文里提到的路径不算入账",
            registryLine(debt, listOf("ui/chat/Prose.kt"), 810) == null,
        )
        assertTrue(
            "表格行单元格不足三格不算入账",
            registryLine(debt, listOf("ui/chat/Tiny.kt"), 810) == null,
        )
    }

    @Test
    fun `AGENTS 4_3 列出的字段与门禁一致`() {
        // 门禁字段清单不能只活在本文件的记忆里：宪法改了字段名，这里必须跟着红。
        val section = agentsSection("### 4.3")
        sanity(section.isNotEmpty(), "AGENTS.md 里找不到 §4.3 小节，文档结构变了")
        val missing = PATCH_FIELDS.filterNot { field -> section.any { it.contains("// $field:") } }
        assertTrue(
            "AGENTS.md §4.3 的字段与门禁不一致，缺：${missing.joinToString("、")}。\n" +
                "怎么办：改文档就要同步改门禁（本文件的 PATCH_FIELDS）。",
            missing.isEmpty(),
        )
    }
}

// ---------------------------------------------------------------------- 纯函数（可被夹具直接喂）

/** 一个 `// @patch` 块的解析结果；`fields` 里只含解析到的字段。 */
private data class PatchBlock(val file: String, val lineNo: Int, val fields: Map<String, String>)

/**
 * 从一个源文件的行里抽出所有 `@patch` 块。
 * 只吃紧跟在 `// @patch` 之后的**连续整行 `//` 注释**，最多 [PATCH_WINDOW] 行，遇代码/空行即收窗。
 */
private fun parsePatchBlocks(repoPath: String, lines: List<String>): List<PatchBlock> {
    val blocks = mutableListOf<PatchBlock>()
    lines.forEachIndexed { index, raw ->
        if (!raw.trim().startsWith(PATCH_MARKER)) return@forEachIndexed
        val fields = mutableMapOf<String, String>()
        var cursor = index + 1
        var taken = 0
        while (cursor < lines.size && taken < PATCH_WINDOW) {
            val text = lines[cursor].trim()
            if (!text.startsWith("//") || text.startsWith(PATCH_MARKER)) break
            PATCH_FIELDS.forEach { field ->
                if (fields.containsKey(field)) return@forEach
                val match = Regex("^//\\s*$field\\s*:\\s*(.*)$").find(text) ?: return@forEach
                fields[field] = match.groupValues[1].trim()
            }
            cursor++
            taken++
        }
        blocks += PatchBlock(repoPath, index + 1, fields)
    }
    return blocks
}

/**
 * 在债务台账里找某文件的「已登记」行：必须是 Markdown 表格行、含该路径、
 * 且行内记录的最大数字 ≥ [minLines]（= 有人按 §4.2 带理由上调过预算并更新了数字）。
 * 返回 (行号, 原文)，没登记返回 null。
 */
private fun registryLine(debtText: String, candidates: List<String>, minLines: Int): Pair<Int, String>? {
    var found: Pair<Int, String>? = null
    debtText.lines().forEachIndexed { index, raw ->
        if (found != null) return@forEachIndexed
        val line = raw.trim()
        if (!line.startsWith("|")) return@forEachIndexed
        if (candidates.none { it.isNotBlank() && line.contains(it) }) return@forEachIndexed
        val cells = line.trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val recorded = Regex("""\d+""").findAll(line).map { it.value.toInt() }.maxOrNull()
        if (cells.size >= 3 && recorded != null && recorded >= minLines) found = (index + 1) to line
    }
    return found
}

// ---------------------------------------------------------------------- 盘面定位与文件工具

private const val LINE_BUDGET = 800
private const val PATCH_WINDOW = 12
private const val PATCH_MARKER = "// @patch"
private const val PREF_SYMBOL = "SharedPreferences"
private const val SOURCE_SET = "app/src/main/java"
private const val TEST_SET = "app/src/test/java"
private const val PACKAGE_DIR = "io/github/westanke/dshcompanion"
private const val GUARD_FILE_NAME = "ArchitectureGuardTest.kt"

/** `AGENTS.md` §4.3 的五个字段。注意：正文标题写「四要素」但代码块列了 5 个 —— 以代码块为准。 */
private val PATCH_FIELDS = listOf("id", "for", "why", "until", "test")
private val PITFALL_INDEX_ENTRY = Regex("^- `([^`]+)`\\s*$")
private val PITFALL_TITLE = Regex("^### (.+)$")
private val KEBAB = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
private val TEST_CLASS_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*Test")
private val DISPLAY_ITEMS_DECL = Regex("""\bfun\s+[A-Za-z0-9_.<>]*displayItems\s*\(""")
private val CHAT_HISTORY_FACADE = Regex("""fun\s+ChatHistory\.displayItems\s*\(""")
private val OKHTTP_CONSTRUCTION = Regex("""OkHttpClient\s*(\.\s*Builder\s*)?\(""")

/** 内部事件：`AGENTS.md` §4.1 明说它们不渲染为聊天卡片，过滤决策归 data/ 层。 */
private val INTERNAL_EVENT_LITERALS = listOf("\"step/end\"", "\"turn/end\"", "\"chat.message.delta\"")

/**
 * 唯一网络边界的白名单。加人要先改 `AGENTS.md` §4.1 + 在 `TECH-DEBT.md` 立账。
 *
 * `web/ChatWebBridge.kt` 是 **DEBT-006**：WebView 桥要转发「任意方法 + 任意路径 + 任意 JSON」，
 * 与 `DshClient` 的强类型 API 对不上，故自持一份客户端（凭据仍走 `ActiveConnection`）。
 * 它是**登记的债**，不是「允许」—— 再出现第三个自建客户端的文件照样判红，
 * 而且新增白名单项必须同时补台账；只改这里不改台账，等于把违规洗白。
 */
private val NETWORK_BOUNDARY_ALLOWLIST = setOf("data/DshClient.kt", "web/ChatWebBridge.kt")

/**
 * 仓库根：自愈式向上查找，**不写死绝对路径**。
 * Gradle 下测试工作目录通常是模块目录（`app/`），IDE 里可能是仓库根，两种都要能跑。
 *
 * 所有派生路径都用 `by lazy`：万一定位失败，错误要作为**该条测试的失败**抛出来
 * （带完整候选链），而不是在类静态初始化时炸成 ExceptionInInitializerError ——
 * 后者会把整个文件里的测试一起带走，还看不清真正原因。
 */
private val REPO_ROOT: File by lazy { locateRepoRoot() }
private val MAIN_ROOT: File by lazy { File(REPO_ROOT, SOURCE_SET) }
private val MAIN_PACKAGE_ROOT: File by lazy { File(MAIN_ROOT, PACKAGE_DIR) }
private val TEST_ROOT: File by lazy { File(REPO_ROOT, TEST_SET) }
private val TEST_PACKAGE_ROOT: File by lazy { File(TEST_ROOT, PACKAGE_DIR) }
private val GUARD_FILE: File by lazy { File(TEST_PACKAGE_ROOT, GUARD_FILE_NAME) }
private val FACADE_FILE: File by lazy { File(MAIN_PACKAGE_ROOT, "data/ChatPresentation.kt") }
private val TECH_DEBT: File by lazy { File(REPO_ROOT, "docs/project/TECH-DEBT.md") }
private val PITFALLS: File by lazy { File(REPO_ROOT, "docs/project/PITFALLS.md") }
private val AGENTS_MD: File by lazy { File(REPO_ROOT, "AGENTS.md") }

private fun locateRepoRoot(): File {
    val cwd = File(".").absoluteFile.canonicalFile
    val chain = generateSequence(cwd) { it.parentFile }.toList()
    return chain.firstOrNull(::isRepoRoot) ?: throw AssertionError(
        "【门禁自检失败】定位仓库根失败：从 $cwd 向上没找到同时含 " +
            "$SOURCE_SET 与 AGENTS.md 的目录。\n" +
            "候选链：${chain.joinToString(" → ") { it.path }}\n" +
            "怎么办：确认测试是在仓库内跑的；若目录结构变了，同步改本文件的 isRepoRoot。",
    )
}

private fun isRepoRoot(dir: File): Boolean =
    File(dir, SOURCE_SET).isDirectory && File(dir, "AGENTS.md").isFile

/**
 * 盘面自检：不成立就抛错（红），**绝不跳过、绝不静默通过**。
 * 门禁最坏的失败是「什么都没扫到所以全绿」。
 */
private fun sanity(condition: Boolean, what: String) {
    if (!condition) {
        throw AssertionError(
            "【门禁自检失败】$what。\n" +
                "仓库根解析为：${REPO_ROOT.path}\n" +
                "自检文件：${GUARD_FILE.path}（存在=${GUARD_FILE.isFile}）\n" +
                "扫描盘面异常时宁可红：静默全绿会冒充证据，比没有门禁更坏。",
        )
    }
}

private fun ktFiles(root: File): List<File> =
    root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.sortedBy { it.path }.toList()

/** 路径前缀计算（自己写，不依赖实验性 API）。 */
private fun relativeOrNull(base: File, file: File): String? {
    val basePath = base.absoluteFile.canonicalFile.path
    val filePath = file.absoluteFile.canonicalFile.path
    if (filePath == basePath) return ""
    if (!filePath.startsWith(basePath + File.separator)) return null
    return filePath.substring(basePath.length + 1).replace(File.separatorChar, '/')
}

/** 仓库相对路径：`app/src/main/java/io/…/ui/Foo.kt`。 */
private fun repoPath(file: File): String = relativeOrNull(REPO_ROOT, file) ?: file.absolutePath

/** 包内相对路径：`ui/Foo.kt` —— 台账与文档里用的是这一种。 */
private fun packagePath(file: File): String? =
    relativeOrNull(MAIN_PACKAGE_ROOT, file) ?: relativeOrNull(TEST_PACKAGE_ROOT, file)

/**
 * 整行注释放行（`//` `/*` `*` `*/` 开头，忽略前导空白）。
 * 刻意只看**整行**：行尾注释不剥离，宁可让人把说明挪一行，也不在半个解析器上漏掉真代码。
 */
private fun isCommentLine(line: String): Boolean {
    val text = line.trimStart()
    return text.startsWith("//") || text.startsWith("/*") ||
        text.startsWith("*") || text.startsWith("*/")
}

/** 非注释行 + 1 基行号。 */
private fun codeLines(file: File): List<Pair<Int, String>> =
    file.readLines().withIndex()
        .filterNot { (_, line) -> isCommentLine(line) }
        .map { (index, line) -> (index + 1) to line }

private fun readDebtRegistry(): String {
    if (!TECH_DEBT.isFile) {
        throw AssertionError(
            "【门禁自检失败】找不到债务台账：${TECH_DEBT.path}\n" +
                "AGENTS.md §4.2 的豁免判据就写在那里；台账没了，这条门禁无法判断超预算文件是否已入账。",
        )
    }
    return TECH_DEBT.readText()
}

/** 取 `AGENTS.md` 里某个小节（到下一个 `###`/`##`/`---` 为止）的正文行。 */
private fun agentsSection(headingPrefix: String): List<String> {
    val lines = AGENTS_MD.readLines()
    val start = lines.indexOfFirst { it.trimStart().startsWith(headingPrefix) }
    if (start < 0) return emptyList()
    val rest = lines.drop(start + 1)
    val end = rest.indexOfFirst { it.startsWith("### ") || it.startsWith("## ") || it.trim() == "---" }
    return if (end < 0) rest else rest.take(end)
}
