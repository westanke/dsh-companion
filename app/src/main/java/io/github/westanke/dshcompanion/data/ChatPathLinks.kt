package io.github.westanke.dshcompanion.data

/**
 * 把消息正文里**已知的会话文件**变成可点链接。
 *
 * ## 为什么需要
 *
 * 模型经常在正文里顺手写一句「已落盘：`dsh-public-deployments-项目适配评估_2026-10-19.md`」
 * 或者带个 📄 图标加文件名 —— 那是**纯文本**，不是 Markdown 链接，于是 App 只把它当
 * 一句话渲染，用户去点它自然毫无反应（2026-10-19 真机反馈的就是这个：
 * 「会话中有个文件，我试了打不开」；同一文件从「本会话的文件」面板点得开，
 * 说明链路本身没问题，缺的只是**正文里那句文字不是链接**）。
 *
 * ## 为什么按「文件名」匹配，而不是整条绝对路径
 *
 * 模型写进正文的通常只有**文件名**（整条 `/media/wangke/...` 太长，它一般省略）。
 * 所以拿会话已知文件的 `fileName` 去正文里找。
 *
 * ## 三条安全约束（宁可不加链接，也不打开错的文件）
 *
 * 1. **只有唯一文件名才替换**：同一个文件名同时出现在多个目录时**跳过** ——
 *    点开后打开另一个目录里的同名文件，比「点不开」严重得多。
 * 2. **不碰已有的 Markdown 链接 / 代码块**：正文里 `[text](path)` 已经能点，
 *    再包一层会得到 `[[text](path)](path)`。
 * 3. **只替换第一次出现**：同一消息里重复提到同一个文件名时，只让第一处可点，
 *    避免整段文字被链接语法淹没。
 */
internal fun linkifyKnownFiles(text: String, knownAbsolutePaths: Collection<String>): String {
    if (text.isEmpty() || knownAbsolutePaths.isEmpty()) return text

    // 文件名 → 绝对路径；同名多路径的整条丢弃（见约束 1）。
    val uniqueByName: Map<String, String> = knownAbsolutePaths
        .mapNotNull { path ->
            val name = path.substringAfterLast('/').takeIf(String::isNotBlank) ?: return@mapNotNull null
            name to path
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, paths) -> paths.distinct() }
        .filterValues { it.size == 1 }
        .mapValues { (_, paths) -> paths.single() }

    if (uniqueByName.isEmpty()) return text

    var result = text
    // 长名优先：`a.md` 不能抢在 `report-a.md` 前面命中。
    for ((name, absolutePath) in uniqueByName.entries.sortedByDescending { it.key.length }) {
        if (!result.contains(name)) continue
        // 边界：前后不能紧贴 URL 字符**与方/圆括号** —— 后者正是
        // `[看报告](xxx.md)` 的形状；只排除 \w 会把里面那层再包一遍，
        // 得到 `[看报告]([xxx.md](/abs/path))`（单测 `doesNotWrapAnExistingMarkdownLinkTwice`
        // 抓到的就是这个）。
        val pattern = Regex("""(?<![\w./\-()\[\]])""" + Regex.escape(name) + """(?![\w/\-()\[\]])""")
        result = pattern.replaceFirst(result, "[$name]($absolutePath)")
    }
    return result
}
