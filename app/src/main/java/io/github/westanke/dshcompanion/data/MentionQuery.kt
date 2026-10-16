package io.github.westanke.dshcompanion.data

/**
 * 输入框 `@` 提及的识别与替换（对齐官方「@ 调取当前工作目录文件或文件夹」）。
 *
 * 单独放一个文件而不是留在 Composable 里：这块是**纯字符串逻辑**，边界情况又多
 * （邮箱、多行、`@` 出现在词中间），值得单测覆盖；混在 UI 文件里就只能靠手点验证。
 */
object MentionQuery {

    /**
     * 找出当前应当触发补全的那个 `@` 的下标；没有则返回 null。
     *
     * 规则：从字符串末尾往回扫，谁先遇到谁说了算 ——
     * - 碰到 `@` 且它前面是**行首或空白**：命中，返回它的下标；
     * - 碰到空白/换行：说明光标已经离开那个词了，不命中；
     * - 扫到头还没遇到任何一个：不命中。
     *
     * 为什么要判「前面是行首或空白」：`foo@bar.com` 这种文本里的 `@` 前面是字母，
     * 它不是提及。宽松匹配会导致**正常打字时突然弹出文件列表**，比不弹更烦人。
     */
    fun activeStart(value: String): Int? {
        var i = value.length - 1
        while (i >= 0) {
            val ch = value[i]
            if (ch == '@') {
                return if (i == 0 || value[i - 1].isWhitespace()) i else null
            }
            if (ch.isWhitespace()) return null
            i--
        }
        return null
    }

    /**
     * 当前正在输入的查询串（`@` 之后到末尾那一段）；没有活跃提及则返回 null。
     */
    fun query(value: String): String? = activeStart(value)?.let { value.substring(it + 1) }

    /**
     * 把选中的条目插回去：**只替换「@…末尾」那一段**，保留它前面的内容。
     *
     * 不做整行替换：用户很可能已经写了半句「帮我看看 @xxx」，整行替换会把前半句吃掉。
     * 插入的是**绝对路径**（调用方负责拼好），目录结尾补 `/` 且不加空格 ——
     * 用户大概率要继续往里选下一级；文件结尾补一个空格把这个词结束掉。
     */
    fun applySelection(value: String, inserted: String): String {
        val at = activeStart(value) ?: return value + inserted
        return value.substring(0, at) + inserted
    }

    /**
     * 把条目的路径拼成**绝对路径**，供插进输入框。
     *
     * 官方与内核给模型看的都是绝对路径，相对路径会让模型先猜「相对于谁」，
     * 猜错就是一次无效工具调用。
     *
     * 拼接不是简单的「cwd + 相对路径」—— 列举接口返回的 [entryPath] 是
     * **相对授权根**的完整路径（不是相对会话目录），会话目录在它里面是一段前缀。
     * 所以正确做法是**换前缀**：
     *
     * ```
     * cwdAbsolute = /r/ws          cwdRelative = ws        entryPath = ws/a.txt
     * 绝对路径 = /r/ws + "/" + (entryPath 去掉 "ws/") = /r/ws/a.txt
     * ```
     *
     * `cwdRelative` 为空串是合法且常见的：那表示会话工作目录**就是授权根本身**，
     * 此时 [cwdAbsolute] 就是根的绝对路径，直接接上 [entryPath] 即可。
     *
     * 两种降级都返回相对根的路径而不是硬拼一个错的绝对路径：
     * 老服务端不返回 `cwdAbsolute`（null/空），或 [entryPath] 竟然不在 `cwdRelative`
     * 之下（理论上不该发生）。
     */
    fun absolute(cwdAbsolute: String?, cwdRelative: String, entryPath: String): String {
        val root = cwdAbsolute?.trim()?.trimEnd('/').orEmpty()
        if (root.isEmpty()) return entryPath

        val prefix = cwdRelative.trim().trim('/')
        val tail = when {
            prefix.isEmpty() -> entryPath
            entryPath == prefix -> ""
            // 必须带分隔符比对：否则 "ws" 会误配 "wsx/a.txt" 的前缀。
            entryPath.startsWith("$prefix/") -> entryPath.substring(prefix.length + 1)
            else -> return entryPath
        }
        val clean = tail.trimStart('/')
        return if (clean.isEmpty()) root else "$root/$clean"
    }
}
