package io.github.westanke.dshcompanion.debug

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/*
 * ============================================================================
 * 诊断日志：三通道落盘，为「抓不到 logcat」的真机准备的退路
 * ============================================================================
 *
 * ## 为什么不用 Log 了事
 *
 * `docs/project/PITFALLS.md` 有一条实测过的坑：**vivo 真机上自有 App 的 `Log.i`
 * 不出现在 logcat**。上一轮给 `ChatDetail.kt` 的 composable 顶层和 `LaunchedEffect`
 * 入口都加了 `Log.i` 探针，`adb logcat -s` 抓到的是
 * `--------- beginning of main` 空输出 —— 连顶层探针都没打。
 * 当时只能改用「把探针数字渲染到屏幕上」取证，探针用完就删。
 *
 * 这条坑没有根治，只是被绕开了。本轮要观察的是**会话页的两条实现路径**
 * （原生 `ChatDetail` vs WebView `ChatWebScreen`）到底哪条在渲染，以及窗口/屏幕
 * 变化在什么时机发生 —— 这类问题光靠「看一眼屏幕」答不了，必须有可读的数字。
 *
 * 所以这里做三通道，任一生效即可取证，且互不依赖：
 *
 * 1. **logcat**（`Log.d/w/e`，不只用 `i`）—— 通道若通，`adb logcat -s DSH_DIAG`
 *    即可看；vivo 若封了，走下面两条。
 * 2. **文件**（`filesDir/diag.log`）—— vivo 封的是 logcat，不是文件系统。
 *    `adb shell run-as <pkg> cat files/diag.log` 或 `adb pull` 都能读，
 *    这是**最可靠**的那条。环形覆盖（超过上限从头写），不会把存储写满。
 * 3. **内存环形缓冲** —— 给屏上探针用：不依赖 adb、不依赖 logcat，
 *    把最近 N 条直接画在屏幕上。手机不在手上也能看。
 *
 * ## 铁律：默认只写文件，logcat 只在 debug 包走
 *
 * release 包里 `DiagLog.enabled` 为 false，三个通道全静默（只有 `DiagLog.enabled`
 * 一处判断），零行为变化、零 IO。看门禁：`scripts/build.sh assembleDebug`。
 */

/** 诊断日志的单一出口。所有方法都先看 [enabled]，关了就什么都不做。 */
object DiagLog {

    /**
     * 总开关：**运行时偏好 × buildType** 两道相乘。
     *
     * - [io.github.westanke.dshcompanion.BuildConfig.DIAG_LOG] 决定这条代码路径
     *   **是否存在**：release 包里它是 false，整个对象静默、屏上浮层不渲染，
     *   诊断能力不随发布包出厂。
     * - 设置页那个滑动开关（`AppPreferences.diagLog`）决定它**是否工作**：
     *   debug 包里默认仍是 false，要拨到开才开始写。
     *
     * 为什么初值只取 BuildConfig 而不管偏好：Kotlin `object` 是懒初始化的，
     * 这个字段在**第一次被碰到时**求值，那时通常还没有 Context 可读偏好。
     * 真正的同步发生在 `MainActivity.onCreate` → `DiagLog.bind(context)` 之后：
     * `HarnessViewModel` 构造时读偏好并调用 [syncFromPreference]，
     * 于是设置页显示的状态与这里从那一刻起就一致了。
     *
     * 之后由用户拨开关时 `HarnessViewModel.setDiagLog()` 直接赋值 —— 日志写在
     * 滑动跟手的每一帧里，不能每写一条都去走一次 SharedPreferences IO。
     */
    @Volatile
    var enabled: Boolean = io.github.westanke.dshcompanion.BuildConfig.DIAG_LOG

    /** logcat 的 tag。`adb logcat -s DSH_DIAG` 只看它。 */
    const val TAG = "DSH_DIAG"

    /** 文件日志名，位于 `context.filesDir/` 下。 */
    const val FILE_NAME = "diag.log"

    /** 环形覆盖上限：128 KB。再小就装不下一次完整的会话操作观察。 */
    private const val MAX_FILE_BYTES = 128L * 1024

    /** 内存环形缓冲条数：屏上探针一屏放得下的量级。 */
    private const val RING_CAPACITY = 200

    private val lock = Any()
    private val ring = ArrayDeque<String>(RING_CAPACITY)
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 已写入文件的累计行数（供屏上探针显示「日志在写」）。 */
    private val fileLineCount = AtomicLong(0)

    @Volatile
    private var logFile: File? = null

    /** 进程内单调序号：同一毫秒内的多条也能排出来。 */
    private val seq = AtomicLong(0)

    /** 上一条的「分类 \0 正文」，用于连续重复行抑制。见 [log] 里那段注释。 */
    private var lastKey: String? = null

    /** 当前这一串连续重复已经出现过几次。 */
    private var dupCount = 0

    /**
     * 绑定日志文件。必须在**主进程的 Application/首个 Activity** 里调一次，
     * 且要能拿到 `context`。重复调用是安全的（只重绑路径）。
     */
    /**
     * 绑定日志文件路径。
     *
     * **与开关状态无关**：这个函数永远只做一件事 —— 记住文件在哪，
     * 不判断「现在要不要写」。判断留给每条日志的 `if (!enabled) return`。
     *
     * ## 为什么这里曾经有个 bug
     *
     * 初版写的是 `if (!enabled) return`，理由是「开关关着就不必绑」。
     * 结果真机上全哑：启动时开关默认关 → `bind` 直接跳过 → `logFile` 一直是 null；
     * 用户随后在设置页把开关拨开，`enabled` 变成真，**却再没有人调 `bind`** ——
     * 文件通道整条死掉，而屏上浮层（走内存环形缓冲）照常work，
     * 于是现象是「浮层有字、文件里一条没有」，比全哑更难查。
     *
     * 教训：「开关」只该影响**是否记录**，不该影响**通道是否建立**。
     * 两者混在一处，就会出现「先关后开」这条唯一坏路径 —— 而它恰好是默认路径。
     *
     * 存 `applicationContext` 而不是 Activity 的 context：这个引用要活到进程结束，
     * 拿 Activity 会泄漏它。
     */
    fun bind(context: android.content.Context) {
        val app = context.applicationContext ?: context
        bindTo(File(app.filesDir, FILE_NAME))
    }

    /**
     * 测试接缝：直接绑定一个文件。
     *
     * 单测跑在 JVM 上没有 `Context`，但「先关后开时文件通道会不会断」正是
     * 那个真机 bug 的要害，必须有回归用例盯着（见 `DiagLogTest`
     * §先关后开）。所以把「绑定路径」这一步拆成接受 `File` 的形式，
     * `bind(context)` 只是它的一层包装。
     */
    internal fun bindTo(file: File) {
        synchronized(lock) { logFile = file }
    }

    /**
     * 用偏好里的开关值同步一次 [enabled]。由 `HarnessViewModel` 构造时调用
     * （那时才刚读过 SharedPreferences，且 Context 一定已经就绪）。
     *
     * 与 [bind] 的分工：这个只管**要不要记**，那个只管**文件在哪**。
     * 两个都不判断对方的状态，因此谁先谁后都不会出现「通道没建起来」。
     */
    fun syncFromPreference(value: Boolean) {
        enabled = io.github.westanke.dshcompanion.BuildConfig.DIAG_LOG && value
    }

    /** 分类前缀。写日志时用这些常量，别在调用点拼字符串。 */
    object Cat {
        const val LIFE = "LIFE"      // Activity / 窗口生命周期
        const val ROUTE = "ROUTE"    // 原生 vs WebView 的路由判定
        const val COMPOSE = "COMPOSE" // 组合入口 / 重组
        const val SCROLL = "SCROLL"  // 滚动、落底、collect
        const val WEB = "WEB"        // WebView 加载与桥
        const val CLICK = "CLICK"    // 点击、返回、抽屉
        const val SCREEN = "SCREEN"  // 亮灭屏、旋转、尺寸变化
    }

    // ---------------------------------------------------------------- 写入口

    fun d(cat: String, msg: String) = log(Log.DEBUG, cat, msg)
    fun i(cat: String, msg: String) = log(Log.INFO, cat, msg)
    fun w(cat: String, msg: String) = log(Log.WARN, cat, msg)
    fun e(cat: String, msg: String, tr: Throwable? = null) = log(Log.ERROR, cat, msg, tr)

    private fun log(level: Int, cat: String, msg: String, tr: Throwable? = null) {
        if (!enabled) return

        // ── 连续重复行抑制（指数退避）────────────────────────────────
        //
        // 诊断日志里有一大类是**逐帧/逐次**重复的：「贴底跟随未触发」在流式期间
        // 每个 revision 都发一次，真机实测一屏 6 条一模一样（时间戳不同、正文全同）。
        // 这类行会把真正要看的路由/生命周期挤出去 —— 而「能读」正是这套日志
        // 存在的唯一理由，读不动就等于没有。
        //
        // 做法：与**上一条正文相同**时只累加计数，且只在计数为 2、4、8、16…
        // 时落一条（`n & (n-1) == 0` 即 2 的幂）。于是 n 次重复从 n 行降到
        // ⌊log₂n⌋+1 行，且人仍能看出「它重复了很多次」。
        //
        // 只跟**紧邻的上一条**比，不做全局去重：中间夹了别的行就说明这不是
        // 连续刷屏，重新计数才对。
        val key = cat + "\u0000" + msg
        val repeatCount: Int
        synchronized(lock) {
            if (key == lastKey) {
                dupCount++
                // 不是 2 的幂 → 这一条丢掉（它已经被上一次的摘要代表了）
                if (dupCount and (dupCount - 1) != 0) return
                repeatCount = dupCount
            } else {
                lastKey = key
                dupCount = 1
                repeatCount = 1
            }
        }

        val n = seq.incrementAndGet()
        val body = if (repeatCount > 1) "$cat $msg（同前，已重复 $repeatCount 次）" else "$cat $msg"
        val line = "${stamp.format(Date())} #$n $body"

        // 三条通道**互不依赖**：任一生效即可取证，任一挂了不影响另外两条。
        //
        // 这里刻意不用 `logFile ?: return` 那种早退写法：文件还没 bind 时
        // （冷启动的头几条、或单测环境）该退的只是文件那一条，
        // 早退会把环形缓冲一起吞掉 —— 屏上浮层就永远空着，
        // 而它的存在意义正是「不需要 bind 也能看」。

        // 1) logcat —— vivo 可能封，封了也不影响下面两条。
        //    刻意 d/w/e 都试过：万一只封 i，别的级别还能漏出来。
        //    runCatching 是给 JVM 单测兜底的：那里 android.util.Log 没被 mock，
        //    一调就抛 RuntimeException；诊断日志绝不该让调用方崩。
        runCatching {
            when (level) {
                Log.DEBUG -> Log.d(TAG, line)
                Log.INFO -> Log.i(TAG, line)
                Log.WARN -> Log.w(TAG, line)
                else -> Log.e(TAG, line, tr)
            }
        }

        // 2) 文件 —— 最可靠的那条。环形覆盖。
        val file = logFile
        if (file != null) {
            synchronized(lock) {
                runCatching {
                    if (file.length() > MAX_FILE_BYTES) {
                        file.writeText("") // 环形：直接截断重开，不保留旧内容
                    }
                    file.appendText(line + "\n")
                    fileLineCount.incrementAndGet()
                }
            }
        }

        // 3) 内存环形缓冲 —— 屏上探针读它，也是唯一不依赖任何外部条件的通道。
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > RING_CAPACITY) ring.removeFirst()
        }
    }

    // ---------------------------------------------------------------- 读出口

    /** 屏上探针用：最近 [count] 条，按时间正序。 */
    fun recentLines(count: Int = 40): List<String> = synchronized(lock) {
        if (ring.isEmpty()) emptyList() else ring.takeLast(count.coerceIn(1, RING_CAPACITY))
    }

    /** 已写入文件的行数。 */
    fun fileLines(): Long = fileLineCount.get()

    /**
     * 导出当前日志全文，供设置页「读日志」复制走。
     *
     * 与 [recentLines] 的区别：那个只给屏上浮层用（有 200 条上限），
     * 这个返回**文件里的全部内容**，是要交给别人读的完整现场。
     *
     * 读失败（文件不存在、IO 异常）时返回空串而不是抛：设置页那个按钮点了
     * 没反应顶多是困惑，崩了就是事故。空串由调用方转成「暂无日志」提示。
     */
    fun exportText(): String {
        val file = logFile ?: return ""
        return runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
    }

    /** 清空环形缓冲（屏上探针的「清」按钮用；文件不动，保留取证现场）。 */
    fun clearRing() = synchronized(lock) {
        ring.clear()
        // 去重状态一并复位：清了屏之后，下一条哪怕和清屏前那条正文相同，
        // 也该照原样打出来 —— 否则用户会看到「（同前，已重复 4 次）」
        // 指着一个屏幕上并不存在的上一条。
        lastKey = null
        dupCount = 0
    }
}
