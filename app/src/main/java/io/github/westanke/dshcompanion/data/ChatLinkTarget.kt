package io.github.westanke.dshcompanion.data

/** 末尾的 `#L12` / `#L12-L34` 片段（大小写不敏感；行范围第二段也常带 `L`）。 */
private val HASH_LINE_SUFFIX = Regex("""#L\d+(?:-L?\d+)?$""", RegexOption.IGNORE_CASE)

/** 末尾的 `:12` / `:12-34`（行号或行范围）。 */
private val COLON_LINE_SUFFIX = Regex(""":\d+(?:-\d+)?$""")

/**
 * 剥掉链接目标末尾的**位置后缀**，只留文件路径。
 *
 * ## 为什么需要
 *
 * 模型引用文档时几乎总带位置：`docs/project/STATUS.md:1`、
 * `/abs/STATUS.md#L101`、编译器风格的 `src/a.ts:12:5`。这些后缀**不是路径的一部分**，
 * 原样丢给 `roots/resolve` 会被判成「不是普通文件 / 不在授权根内」——
 * 用户看到的就是「文档打不开」，而文件本身明明存在（2026-10-20 真机截图：
 * `/…/STATUS.md#L101` 面板报「找不到这个路径」）。
 *
 * ## 只认两种确定形态，不做泛化猜测
 *
 * `:数字` / `#L数字`（含行范围 `:12-34`、`#L12-L34`、行:列 `:12:5`）。
 * 文件名里真带冒号的极罕见，而「猜错就把路径改坏」的代价比「漏剥一次」高 ——
 * 与本类开头那条「只处理能确定的」原则同源。
 *
 * ## 刻意不处理的两处
 *
 * - `https://e.com/a#L1` 的 `#` 是**页内跳转**，剥掉会把用户送回页面顶部；
 * - `http://host:8080` 的端口也不是行号。
 *
 * 两处都在 [ChatLinkTarget.classify] 里靠「网页分支返回**原文**」保住 ——
 * 本函数只用于路径分支，网页永远拿回原串。
 *
 * ## 边界
 *
 * 最多剥两段（覆盖「行:列」）；剥完会变成空串就**不剥**（例如目标本身就是 `:12`），
 * 否则只是换个错误再报一次。
 *
 * 正则形态受 `PITFALLS.md` §长文本正则与重复分组 约束：锚定行尾、无重复分组
 * （`(?:…)?` 是可选组，不是 `(…)*`），与仓里已放行的 `\b\d+(\.\d+)?\b` 同型。
 */
internal fun stripLocationSuffix(path: String): String {
    var out = path
    HASH_LINE_SUFFIX.find(out)?.let { match ->
        val kept = out.substring(0, match.range.first)
        if (kept.isNotEmpty()) out = kept
    }
    // 最多两段：`file.ts:12:5` 是「行:列」，一次只剥得掉最后一个。
    // 用 while 而不是 `repeat(2)`：剥成空串要**跳出**，而 `repeat` 里的 `return@repeat`
    // 只是跳过本次迭代、循环照转 —— 那种写法读起来像 break，实际不是。
    var stripped = 0
    while (stripped < 2) {
        val match = COLON_LINE_SUFFIX.find(out) ?: break
        val kept = out.substring(0, match.range.first)
        // 剥成空串就停手：`:`+数字 就是目标的全部内容时，再剥只会换个错误再报一次。
        if (kept.isEmpty()) break
        out = kept
        stripped++
    }
    return out
}

/**
 * 把**相对路径目标**拼到会话 cwd 之下，供 `roots/resolve` 使用。
 *
 * 抽成纯函数是为了能被 JVM 单测钉住：真正的调用点在 Compose 的 `UriHandler` 里，
 * 那里测不了，而「拼错基准」恰好是本仓历史上最贵的一类错（拼出翻倍路径 / 打开另一个文件）。
 *
 * 位置后缀在**拼接之前**剥：`docs/x.md:1` 直接拼会得到 `cwd/docs/x.md:1`，
 * 服务端只会回「不是普通文件」—— 换汤不换药地打不开（2026-10-20）。
 */
internal fun joinUnderCwd(cwdAbsolute: String, raw: String): String {
    val cwd = cwdAbsolute.trim().trimEnd('/')
    val tail = stripLocationSuffix(raw.trim()).trimStart('/')
    return if (cwd.isEmpty()) "/$tail" else "$cwd/$tail"
}

/**
 * 一条 Markdown 链接点了之后应该发生什么。
 *
 * 抽成纯函数是因为「点错东西」的代价不对称：把 `mailto:` 当成文件路径去解析，
 * 用户会看到一个莫名其妙的「路径不存在」；把相对路径猜成绝对路径，可能打开**另一个**文件。
 * 所以这里的原则是**只处理能确定的，其余一律明确告知不支持**，绝不猜。
 */
sealed interface ChatLinkTarget {
    /** 服务端绝对路径。交给 `roots/resolve` 解析成「授权根 + 相对路径」。 */
    data class ServerPath(val path: String) : ChatLinkTarget

    /** 普通网页。交给系统浏览器。 */
    data class Web(val url: String) : ChatLinkTarget

    /**
     * 无法在本机安全处理的目标（相对路径、锚点、mailto、其他 scheme）。
     *
     * `reason` 用枚举而不是拼好的文案，是为了让 UI 侧决定怎么本地化，
     * 也为了让测试断言的是**分类结果**而不是某一句中文。
     */
    data class Unsupported(val raw: String, val reason: Reason) : ChatLinkTarget

    enum class Reason {
        /** 相对路径：服务端只接受绝对路径，且相对路径的基准是服务端的 cwd，语义不确定。 */
        RELATIVE_PATH,

        /** 空串或纯空白。 */
        EMPTY,

        /** 页内锚点。 */
        ANCHOR,

        /** 其他 scheme（mailto:、tel:、data: …）。 */
        UNKNOWN_SCHEME,
    }

    companion object {
        /**
         * 分类一个 Markdown 链接目标。
         *
         * 判定顺序有讲究：**先认绝对路径，再认 scheme**。
         * 因为 `//example.com/x` 这种协议相对 URL 以 `/` 开头但它是网页而不是服务端路径；
         * 而 `file:///abs/path` 虽然带 scheme，我们也不当本地文件处理（手机与服务端不是同一台机器，
         * 「本地文件」这个概念在远程场景下没有意义）。
         */
        fun classify(raw: String): ChatLinkTarget {
            // **先剥 Markdown autolink 的尖括号**：`[text](</abs/path>)` 是 CommonMark
            // 允许的写法（路径含空格/特殊字符时常用），模型也真的这么写。
            // 不剥的话 `<...>` 既不以 `/` 开头、也没有 scheme，会被一路判成**相对路径** ——
            // 于是绝对路径又被拼了一次 cwd，得到
            // `/media/wangke/OFFICE/workspace/</media/wangke/.../x.md>` 这种翻倍路径
            // （2026-10-19 真机实测 `PATH resolve 失败 code=PATH_NOT_FOUND` 就是它）。
            val unwrapped = raw.trim().let { text ->
                if (text.length >= 2 && text.startsWith("<") && text.endsWith(">")) {
                    text.substring(1, text.length - 1).trim()
                } else {
                    text
                }
            }
            val target = unwrapped
            if (target.isEmpty()) return Unsupported(raw, Reason.EMPTY)

            // 协议相对 URL（//host/path）是网页，不是服务端路径。必须先排掉。
            if (target.startsWith("//")) return Web("https:$target")

            if (target.startsWith("#")) return Unsupported(raw, Reason.ANCHOR)

            if (target.startsWith("/")) {
                // 以 / 开头且不是 //，就是我们唯一认得的服务端绝对路径形态。
                // 先剥位置后缀：`.../STATUS.md#L101` 里的 `#L101` 不是路径的一部分，
                // 原样发给 `roots/resolve` 只会得到「不是普通文件」（2026-10-20 真机）。
                return ServerPath(stripLocationSuffix(target))
            }

            // 相对路径也可能带位置后缀（`docs/x.md:1`）。它的冒号**不是 scheme 分隔符**：
            // 不先排掉，`docs/x.md` 会被当成 scheme（`docs/x.md` 不是合法 scheme 名，
            // 于是判成「未知协议」），界面连拼 cwd 的机会都没有 —— 真机上这就是
            // 「带 :数字 的相对路径打不开」的另一半。
            //
            // 只在**剥掉后不再含冒号**时才认：否则 `http://host:8080`、`C:\x:1` 这类
            // 会被改坏（前者是端口，后者根本不是我们支持的形态）。剥完仍是同一个
            // `RELATIVE_PATH`，只是 `raw` 保留原文供界面拼 cwd 时再剥一次
            // （见 `joinUnderCwd`）—— 分类与拼接两处都不猜基准。
            val scheme = target.substringBefore(':', missingDelimiterValue = "").lowercase()
            if (scheme == "http" || scheme == "https") return Web(target)

            // 网页分支**返回原文**（上面的 Web(target)），所以 `https://e.com/a#L1`
            // 的页内跳转、`http://host:8080` 的端口都不会被剥 —— 这条边界有单测钉住。
            val relativeHead = stripLocationSuffix(target)
            val isPlainRelative = relativeHead.isNotEmpty() &&
                !relativeHead.contains(':') &&
                relativeHead.none { it.isWhitespace() }
            if (isPlainRelative) {
                // `docs/x.md:1` / `docs/x.md#L1` → 相对路径（界面会拼 cwd）。
                return Unsupported(raw, Reason.RELATIVE_PATH)
            }
            if (scheme.isEmpty()) return Unsupported(raw, Reason.RELATIVE_PATH)
            return Unsupported(raw, Reason.UNKNOWN_SCHEME)
        }
    }
}
