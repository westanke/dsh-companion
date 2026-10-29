package io.github.westanke.dshcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * ============================================================================
 * 快照只该覆盖「服务端那份」，其余字段必须原样带过
 * ============================================================================
 *
 * ## 这个文件为谁而立
 *
 * 2026-10-29 真机发现：点顶栏刷新后顶栏立刻显示「已断开」，而事件流其实好好的。
 * 根因不是网络，是 `refreshDashboard` 里用了 `loadSnapshot` 返回的**新构造**状态 ——
 * 而 `HarnessState` 有 70 多个字段，快照只填其中十几个，剩下五十几个全被打回默认值。
 *
 * 当时只在调用点手工补了 `eventsConnected` / `goalAction` 两个，但同一次刷新还会冲掉：
 * `permissionCatalog`（权限档位切换消失）、`bridgeVersion`（「关于」页显示版本未知）、
 * `bridgeCapabilities`（依赖能力位的定时任务/语音入口凭空消失）、`history`
 * （正在看的对话正文变空）、`sessionModels`、`commands`、`jobsPanel`、`schedulePanel`……
 * **加一个补一个必然漏**，所以修法是把方向反过来：默认全带，只覆盖服务端那份。
 *
 * ## 为什么用源码断言而不是构造状态去跑
 *
 * `loadSnapshot` 需要真 `DshClient`（要打网络），单测里跑不起来。而这条不变量本质是
 * **写法约束**：「不许在这个函数里 `return HarnessState(...)`，必须 `return base.copy(...)`」；
 * 以及「刷新路径必须把当前状态作为 base 传进去」。这正是 `ArchitectureGuardTest` 已经在做的
 * 那类文本门禁（上游 Swift 侧用 `contains` 扫源码也是同一手段），放在这里单独成文，
 * 免得那支已经两千多行的守卫再长。
 *
 * 危险失败模式仍然是**静默全绿**：路径解析错了、什么都没扫到也"通过"。所以每个测试
 * 都先做盘面自检（文件必须存在、函数体必须抽得出来），抽不出来就红。
 */
class SnapshotCarryOverTest {

    @Test
    fun `loadSnapshot 用 base copy 而不是重新构造 HarnessState`() {
        val body = functionBody(VIEW_MODEL, "internal suspend fun loadSnapshot(")
        assertTrue(
            "loadSnapshot 里又出现 `HarnessState(` 了 —— 那会让七十多个字段里没被显式赋值的一律打回默认值。\n" +
                "怎么办：写成 `base.copy(...)`，只覆盖服务端字段。",
            !body.contains("HarnessState("),
        )
        assertTrue(
            "loadSnapshot 没有 `base.copy(` —— 说明它不再承接上一份状态，刷新会把运行态冲掉。",
            body.contains("base.copy("),
        )
    }

    @Test
    fun `loadSnapshot 的 base 参数默认是空状态`() {
        // 连接建立时传的是默认值（新连接就该是干净状态），刷新时传当前状态。
        // 若默认值被改成别的（比如干脆去掉默认值），两处语义就会混淆。
        //
        // 注意：参数表在签名里、不在花括号里，所以这里搜的是「签名起点到函数体起点」那一段。
        val declared = signatureOf(VIEW_MODEL, "internal suspend fun loadSnapshot(")
        assertTrue(
            "loadSnapshot 的参数表里找不到 `base: HarnessState = HarnessState()`。\n" +
                "怎么改：保持默认值为空状态，让连接建立路径继续拿到干净状态。",
            declared.contains("base: HarnessState = HarnessState()"),
        )
    }

    @Test
    fun `刷新路径把当前状态作为 base 传入`() {
        val body = functionBody(SESSIONS, "internal fun HarnessViewModel.refreshDashboard()")
        assertTrue(
            "refreshDashboard 调 loadSnapshot 时没传 `base = mutableState.value` —— 刷新会把运行态字段冲成默认值。\n" +
                "怎么办：`loadSnapshot(api, device, base = mutableState.value)`。",
            body.contains("base = mutableState.value"),
        )
    }

    @Test
    fun `刷新路径不再逐个手工补要保留的字段`() {
        // 手工补字段正是「加一个补一个必然漏」的旧写法。允许留注释说明历史，
        // 但不允许再把 `eventsConnected = eventsConnected` 这种自我赋值写回来 ——
        // 它看着像在保留，实际只保住了被点到的那一个。
        val body = functionBody(SESSIONS, "internal fun HarnessViewModel.refreshDashboard()")
        val handPatched = SELF_ASSIGNMENT_LABELS.filter { label ->
            Regex("""\b${Regex.escape(label)}\s*=\s*${Regex.escape(label)}\b""").containsMatchIn(body)
        }
        assertTrue(
            "refreshDashboard 里又出现了自我赋值的字段：${handPatched.joinToString("、")}。\n" +
                "这些字段已经由 `base = mutableState.value` 整体带过，再补一遍只会让人以为" +
                "「保留字段」是逐个点到为止的。",
            handPatched.isEmpty(),
        )
    }

    @Test
    fun `连接建立路径仍然拿干净状态`() {
        // 反向断言：新连接不该继承上一台的会话正文、选中会话、问答状态。
        // 若这里也传 base，切换电脑后会看到上一台的对话 —— 那比刷新掉线更吓人。
        val body = functionBody(CONNECTION, "internal suspend fun HarnessViewModel.connectHostInternal(")
        assertTrue(
            "连接建立路径给 loadSnapshot 传了 base —— 换一台电脑会继承上一台的会话正文/选中会话。\n" +
                "怎么办：新连接用默认空状态（不传 base）。",
            !body.contains("base ="),
        )
    }
}

// ---------------------------------------------------------------------- 盘面定位

private const val SOURCE_SET = "app/src/main/java"
private const val PACKAGE_DIR = "io/github/westanke/dshcompanion"

/**
 * 仓库根：自愈式向上查找（Gradle 下工作目录通常是模块目录 `app/`，IDE 里可能是仓库根）。
 * 与 `ArchitectureGuardTest` 同一套判据，不写死绝对路径。
 */
private val REPO_ROOT: File by lazy {
    var dir: File? = File(".").absoluteFile.canonicalFile
    while (dir != null) {
        if (File(dir, SOURCE_SET).isDirectory && File(dir, "AGENTS.md").isFile) return@lazy dir
        dir = dir.parentFile
    }
    error("定位仓库根失败：从 ${File(".").absolutePath} 向上没找到同时含 $SOURCE_SET 与 AGENTS.md 的目录")
}

private val PACKAGE_ROOT: File by lazy { File(REPO_ROOT, "$SOURCE_SET/$PACKAGE_DIR") }
private val VIEW_MODEL: File by lazy { File(PACKAGE_ROOT, "HarnessViewModel.kt") }
private val SESSIONS: File by lazy { File(PACKAGE_ROOT, "HarnessSessionsViewModel.kt") }
private val CONNECTION: File by lazy { File(PACKAGE_ROOT, "HarnessConnectionViewModel.kt") }

/** 曾经被手工补过（因而暴露了这个问题）的字段名，用来反向断言旧写法没有复活。 */
private val SELF_ASSIGNMENT_LABELS = listOf("eventsConnected", "goalAction")

/**
 * 抽出以 [signature] 开头的那个函数/表达式的花括号体。
 *
 * 判据刻意宽松（先找签名、再从签名后的第一个 `{` 起做花括号配对），因为 Kotlin 的
 * 表达式体函数（`= withClient { ... }`）与普通块体都会走到这里，两者的 `{` 配对规则一致。
 * 抽不出来直接抛错 —— 宁可红，也不要"什么都没扫到于是全绿"。
 */
private fun functionBody(file: File, signature: String): String {
    val (text, start, bodyStart) = locate(file, signature)
    var depth = 0
    var index = bodyStart
    while (index < text.length) {
        when (text[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return text.substring(bodyStart + 1, index)
            }
        }
        index++
    }
    error("`$signature` 的花括号没有配对 —— 源码可能只写了一半")
}

/**
 * 抽「签名段」：从签名起点到函数体 `{` 之前。参数表的默认值写在这里，
 * 不在花括号体内 —— 搜参数必须用这个区间，否则会把「已声明」误判成「没声明」。
 */
private fun signatureOf(file: File, signature: String): String {
    val (text, start, bodyStart) = locate(file, signature)
    return text.substring(start, bodyStart)
}

/** 返回 (全文, 签名起点, 函数体 `{` 的下标)。抽不出来直接抛错，不做静默降级。 */
private fun locate(file: File, signature: String): Triple<String, Int, Int> {
    check(file.isFile) { "找不到源文件：${file.path} —— 盘面自检失败，本测试不能算通过" }
    val text = file.readText()
    val start = text.indexOf(signature)
    check(start >= 0) { "在 ${file.name} 里找不到 `$signature` —— 函数被改名/搬家了，请同步本测试" }
    val bodyStart = text.indexOf('{', start)
    check(bodyStart > start) { "`$signature` 后面没有函数体，抽不出源码" }
    return Triple(text, start, bodyStart)
}
