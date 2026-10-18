package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.JsonObject

/**
 * 工具调用的**专用渲染视图模型**与分发逻辑（对标官方 Web 的 ReadBlock / TerminalBlock /
 * DiffBlock 专用块；依据《官方Web界面与APP功能对比 2026-10-18》第一梯队①）。
 *
 * 为什么在 data 层做分发而不是 UI 层嗅探字符串：分发规则（工具名 → 块类型、参数键 → 内容）
 * 是**可测的纯函数**，与 Compose 无关；UI 只认 [ToolBlockView] 这一个结构。
 *
 * 分发依据是本机真实会话日志的实测分布（3000 条 tool/call）：
 * bash 71%、edit 9%、read/read_image 8%、write 2% —— 四种视图覆盖 ~88% 的调用，
 * 其余（grep、web_fetch、subagent…）一律 [ToolBlockView.Generic]，走原来的通用渲染。
 *
 * 铁律：**解析失败一律回落 [ToolBlockView.Generic]**。专用块是增强，不是依赖 ——
 * arguments 缺字段、result 格式变化，都不能让详情块比改动前少显示任何东西。
 */

/** 工具调用详情的一种**专用视图**。UI 层按此渲染，不再关心工具名。 */
sealed class ToolBlockView {

    /** 通用块：与现状一致 —— 格式化后的输入 + 原样输出，两个方向都能滚。 */
    data class Generic(val formattedArguments: String) : ToolBlockView()

    /**
     * 文件读取块（`read` / `read_image`）：路径头 + 带行号正文。
     *
     * [startLine] 是正文第一行的**真实行号**（result 自带 `N: ` 前缀或 arguments.offset 推出），
     * UI 从它开始编号。result 已经带 `N: ` 前缀时 UI 直接用 result 文本（前缀即行号），
     * 没带时（例如 read_image）只显示路径头，正文走原样。
     */
    data class Read(
        val path: String,
        /** 参数里给的读取范围（1 基，可空 —— 没传就是全文件）。 */
        val offset: Int?,
        val limit: Int?,
        /** 去掉 `<path>…<content>` 包装后的正文（保留内核自带的 `N: ` 行号前缀）。 */
        val body: String,
        /** 正文是否自带「N: 」行号前缀（决定 UI 是否再渲染行号列）。 */
        val hasLineNumbers: Boolean,
    ) : ToolBlockView()

    /**
     * 终端块（`bash`）：`$ command` 提示符 + 已剥 ANSI 转义的输出（等宽暗底由 UI 定）。
     */
    data class Terminal(
        val command: String,
        val description: String?,
        val output: String,
        val running: Boolean,
    ) : ToolBlockView()

    /**
     * 差异块（`edit` / `write`）：旧/新文本对照。
     *
     * - `edit`：[oldText] / [newText] 都有（UI 做 `- ` / `+ ` 着色对照）；
     * - `write`：[oldText] 为 null，[newText] 是新建文件全文（UI 标「新建」）。
     */
    data class Diff(
        val path: String,
        val oldText: String?,
        val newText: String?,
        /** `replace_all` 为 true 时 UI 可加「全部替换」标。 */
        val replaceAll: Boolean,
    ) : ToolBlockView()
}

private val toolBlockJson = chatJson

/**
 * 把一次工具调用分发为专用视图。
 *
 * [arguments] 是内核原样的 JSON **字符串**；[result] 是 content 里 text 段拼接的结果
 * （`null` = 仍在运行）。任何一步解析失败都退回 [ToolBlockView.Generic]。
 */
internal fun toolBlockView(tool: ToolCallDetail): ToolBlockView {
    val args: JsonObject = runCatching {
        toolBlockJson.parseToJsonElement(tool.arguments.ifBlank { "{}" })
    }.getOrNull() as? JsonObject ?: return ToolBlockView.Generic(prettyToolArguments(tool.arguments))

    return when (tool.name) {
        "read", "read_image" -> readBlockView(args, tool)
        "bash" -> terminalBlockView(args, tool)
        "edit" -> diffBlockView(args, path = args.stringValue("file_path"), tool = tool, isWrite = false)
        "write" -> diffBlockView(args, path = args.stringValue("file_path"), tool = tool, isWrite = true)
        else -> ToolBlockView.Generic(prettyToolArguments(tool.arguments))
    }
}

// ---------------------------------------------------------------- read

/**
 * read 结果的固定包装：`<path>…</path><type>file</type><content>\n正文\n</content>`
 * （真实日志 220/220 命中这一形态）。解析失败时正文退回原样 result。
 */
internal fun readBlockView(args: JsonObject, tool: ToolCallDetail): ToolBlockView {
    val path = args.stringValue("file_path").orEmpty()
    val (body, hasLineNumbers) = extractReadBody(tool.result)
    return ToolBlockView.Read(
        path = path,
        offset = args.intValue("offset"),
        limit = args.intValue("limit"),
        body = body,
        hasLineNumbers = hasLineNumbers,
    )
}

/**
 * 从 read 的 result 里取正文，并判断是否自带行号。
 *
 * 行号判据：首行匹配 `^\s*\d+: `（内核的行号前缀是「右对齐数字 + 冒号 + 空格」）。
 * `<content>` 包装不存在、或 result 为 null（运行中）时正文给空串 —— UI 会显示「运行中」。
 */
internal fun extractReadBody(result: String?): Pair<String, Boolean> {
    if (result.isNullOrEmpty()) return "" to false
    val content = result
        .substringAfter("<content>", result)
        .substringBefore("</content>")
        .trimStart('\n')
    if (content.isEmpty()) return result to false
    val hasNumbers = content.lineSequence().firstOrNull { it.isNotBlank() }
        ?.contains(Regex("""^\s*\d+: """)) == true
    return content to hasNumbers
}

// ---------------------------------------------------------------- terminal

/**
 * bash 的输出要**剥 ANSI 转义**：内核透传的终端输出可能带颜色码
 * （`ESC[32m…ESC[0m`），Compose 的 Text 只会把它显示成一串乱码方块。
 * 官方 Web 的 TerminalBlock 有 ANSI 渲染，本仓先做「干净显示」——
 * 不做 ANSI→颜色的完整映射（投入产出比低，等真机反馈再议）。
 */
internal fun terminalBlockView(args: JsonObject, tool: ToolCallDetail): ToolBlockView {
    return ToolBlockView.Terminal(
        command = args.stringValue("command").orEmpty(),
        description = args.stringValue("description"),
        output = stripAnsiEscapes(tool.result ?: ""),
        running = tool.result == null,
    )
}

/** 剥离 CSI（`ESC[…字母`）与 OSC（`ESC]…BEL/ST`）两类转义序列，保留其余字符。 */
internal fun stripAnsiEscapes(text: String): String {
    if (!text.contains('\u001B')) return text
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\u001B' && i + 1 < text.length) {
            when (text[i + 1]) {
                '[' -> {
                    // CSI：ESC [ 参数字节(0x30-0x3F) 中间字节(0x20-0x2F)* 终止字节(0x40-0x7E)
                    var j = i + 2
                    while (j < text.length && text[j] in '\u0020'..'\u003F') j++
                    if (j < text.length && text[j] in '\u0040'..'\u007E') {
                        i = j + 1
                        continue
                    }
                    // 残缺序列：吞掉 ESC[ 与已扫描的参数部分（不吞后面的正文）
                    i = j
                    continue
                }
                ']' -> {
                    // OSC：到 BEL(0x07) 或 ST(ESC \) 结束
                    val bel = text.indexOf('\u0007', i + 2)
                    val st = text.indexOf("\u001B\\", i + 2)
                    when {
                        bel >= 0 && (st < 0 || bel < st) -> { i = bel + 1; continue }
                        st >= 0 -> { i = st + 2; continue }
                        else -> { i = text.length; continue } // 未闭合：吞到尾
                    }
                }
                else -> { i += 2; continue } // 两字节转义（ESC \ 等）
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

// ---------------------------------------------------------------- edit / write

internal fun diffBlockView(
    args: JsonObject,
    path: String?,
    tool: ToolCallDetail,
    isWrite: Boolean,
): ToolBlockView {
    return if (isWrite) {
        ToolBlockView.Diff(
            path = path.orEmpty(),
            oldText = null,
            newText = args.stringValue("content"),
            replaceAll = false,
        )
    } else {
        ToolBlockView.Diff(
            path = path.orEmpty(),
            oldText = args.stringValue("old_string"),
            newText = args.stringValue("new_string"),
            replaceAll = args.booleanValue("replace_all") == true,
        )
    }
}
