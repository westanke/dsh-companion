package io.github.hakunm.deepseekharness.data

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
            val target = raw.trim()
            if (target.isEmpty()) return Unsupported(raw, Reason.EMPTY)

            // 协议相对 URL（//host/path）是网页，不是服务端路径。必须先排掉。
            if (target.startsWith("//")) return Web("https:$target")

            if (target.startsWith("#")) return Unsupported(raw, Reason.ANCHOR)

            if (target.startsWith("/")) {
                // 以 / 开头且不是 //，就是我们唯一认得的服务端绝对路径形态。
                return ServerPath(target)
            }

            val scheme = target.substringBefore(':', missingDelimiterValue = "").lowercase()
            if (scheme == "http" || scheme == "https") return Web(target)
            if (scheme.isEmpty()) return Unsupported(raw, Reason.RELATIVE_PATH)
            return Unsupported(raw, Reason.UNKNOWN_SCHEME)
        }
    }
}
