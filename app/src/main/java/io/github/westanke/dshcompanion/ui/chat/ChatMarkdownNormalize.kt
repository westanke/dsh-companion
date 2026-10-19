package io.github.westanke.dshcompanion.ui.chat

// @patch
// id: glued-gfm-table-needs-blank-line
// for: org.intellij.markdown（markdown-jvm 0.7.3，com.mikepenz.markdown 底层解析器）——
//      表格头紧贴上一行、中间无空行时不产出 TABLE 节点，整表被并入上一个 PARAGRAPH。
// why: 这是第三方解析器比 GitHub 更严格的既定行为，无法用渲染参数关掉，也不能要求模型
//      「记得加空行」（GitHub 风味本就允许紧凑写法，输出不可控）；客户端只能在渲染前补行。
// until: markdown-jvm 升级到「无空行也能识别紧贴上一行的表格」的版本，并删掉本函数后，
//        ChatMarkdownNormalizeTest 里「修复前为 PARAGRAPH」那条断言随之调整。
// test: ChatMarkdownNormalizeTest
/**
 * 渲染前的 Markdown 规范化：给「紧贴在上一行后面」的 GFM 表格补前导空行。
 *
 * ## 根因（2026-10-20 真机取证）
 *
 * 模型常这样写表格——标题行与表头之间**不留空行**：
 *
 * ```
 * **三层结构**
 * | 层 | 安装形态 | 升级方式 |
 * |---|---|---|
 * | 内核 | npm 全局 | npm i -g |
 * ```
 *
 * GitHub 网页端能渲染，但 App 用的 org.intellij.markdown（markdown-jvm 0.7.3）
 * 在这种写法下**根本不产出 TABLE 节点**：表格行被并进上一个 PARAGRAPH，
 * 竖线原样显示、换行被折叠成空格 —— 用户看到的就是「一坨竖线文字」而不是表格。
 *
 * 用同款解析器做过对照实验（证据脚本在工作区 `研究类/table-debug/Probe.java`）：
 * - 前一行非空 + 紧接表格头 + 分隔行 → AST 只有 PARAGRAPH，无 TABLE；
 * - 表头行前补一个空行 → 正常产出 TABLE；
 * - 表格**后**面贴不贴文字、文档开头直接是表格 → 都不影响，所以只修前边界；
 * - 代码围栏里的伪表格不受影响（本函数也会跳过围栏内部）。
 *
 * 为什么放在客户端而不是要求模型「记得加空行」：模型输出不可控，而且 GitHub 风味
 * 本来就允许这种紧凑写法，是我们这颗解析器更严格；客户端兜住一次，所有模型都受益。
 *
 * 流式安全：只有「表头行」与「分隔行」都到达时才补行——半截表格期间不插入，
 * 不会在输出过程中制造无意义的空行抖动。
 */
internal fun normalizeMarkdownTables(content: String): String {
    // 统一成 \n 处理；\r\n 输入最后整体换回去（聊天正文几乎不会有，但纯文本处理不丢风格）。
    val crlf = content.contains("\r\n")
    val lines = content.replace("\r\n", "\n").replace("\r", "\n").split('\n').toMutableList()
    val out = ArrayList<String>(lines.size + 4)

    var fenceMarker: Char? = null // null = 围栏外；'`' / '~' = 围栏内，且只认同种符号闭合
    for (index in lines.indices) {
        val line = lines[index]
        val head = fenceHeadOf(line)
        if (head != null) {
            if (fenceMarker == null) {
                fenceMarker = head
            } else if (fenceMarker == head) {
                fenceMarker = null
            }
            out += line
            continue
        }
        if (fenceMarker == null && index + 1 < lines.size &&
            looksLikeTableRow(line) && looksLikeSeparator(lines[index + 1])
        ) {
            // 表头行的上一行非空（不是空行、不是文档开头）→ 在它前面补一个空行，
            // 把表格从上一个段落里「顶」出来。
            if (out.isNotEmpty() && out.last().isNotEmpty()) {
                out += ""
            }
        }
        out += line
    }

    val result = out.joinToString("\n")
    return if (crlf) result.replace("\n", "\r\n") else result
}

/** 行首（允许缩进）围栏头返回围栏符号，否则 null；与 splitMessageBody 的行首规则一致。 */
private fun fenceHeadOf(line: String): Char? {
    val trimmed = line.trimStart()
    return when {
        trimmed.startsWith("```") -> '`'
        trimmed.startsWith("~~~") -> '~'
        else -> null
    }
}

/** 一行里**含竖线**且去掉竖线/空白后还有内容，才配当表格候选行（单列 `| x |` 也算）。 */
private fun looksLikeTableRow(line: String): Boolean =
    '|' in line && line.replace("|", "").isNotBlank()

/** 分隔行：按竖线切出的每一格都是 `---` / `:--` / `--:` / `:-:` 形态（至少一个 `-`）。 */
private fun looksLikeSeparator(line: String): Boolean {
    if ('|' !in line) return false
    val cells = line.trim().trim('|').split('|').map { it.trim() }
    return cells.isNotEmpty() && cells.all { it.matches(Regex("^:?-+:?$")) }
}
