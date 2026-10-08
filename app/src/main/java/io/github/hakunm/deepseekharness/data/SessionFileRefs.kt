package io.github.hakunm.deepseekharness.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 会话里被 agent 提到过的一个文件路径。
 *
 * [path] 是**服务器上的绝对路径**，原样保留 —— 客户端无法把它映射成「哪个授权根 + 相对
 * 路径」，那一步必须交给服务端（`GET /roots/resolve`）。
 */
data class SessionFileRef(
    val path: String,
    /** 在整个会话历史里出现的次数。 */
    val count: Int,
    /** 最后一次出现的事件序号（[SessionEvent.seq]）。 */
    val lastSeq: Int,
)

/**
 * 从会话历史里提取「agent 提到过哪些文件」。
 *
 * ## 只认绝对路径
 *
 * 相对路径一律丢弃。它相对于谁是不确定的：`readme.md` 可能相对会话 `cwd`、相对工具
 * 默认目录、或只是命令参数里的一个词。猜错会把用户带到别的文件上，所以宁可他不列出。
 * （实测会话的 `cwd` 字段 71/71 全为空，连猜的起点都没有。）
 *
 * ## 采集来源（按可靠度）
 *
 * 1. `data.meta.diffs[].path` —— 结构化字段，由文件编辑产生，最可靠。也兼容顶层的
 *    `data.diffs[]`（旧版事件形态）。
 * 2. 工具调用参数里的 `file_path`，从 `data.arguments`、`data.message.content[].arguments`
 *    与 `data.stream[].chunk.block.arguments` 三处取；这些位置存的是参数 JSON 的**字符串**，
 *    需要先解析。
 *
 * ## 为什么 `bash` 的 command 一律不解析（有意的取舍）
 *
 * shell 命令里的路径绝大多数不是「一个文件」：`grep -rn "foo" /src` 里是搜索范围、
 * `--out=$DIR/x` 里是变量、`echo "/etc/passwd"` 里是一段正文。都解析出来只会得到一堆
 * 点不开的路径，**误报比没有功能更糟**：它会训练用户忽略这个面板，于是真能打开的那些
 * 也被忽略掉。所以 [UNPARSED_TOOLS] 里的工具整体跳过，不做任何启发式猜测。
 *
 * 同理，`grep`/`glob` 也不在解析之列：它们的 `path` 是搜索目录而不是「被改动的文件」。
 * `path`（相对 `file_path` 的兜底键）只在 [FILE_PATH_TOOLS] 上才采信；未知工具仍然接受
 * `file_path`，因为这个键名本身已经足够明确，而它出现在 shell 命令里的可能性为零。
 *
 * 本对象是纯逻辑：不碰网络、不碰 Android，可直接在 JVM 单测里跑。
 */
object SessionFileRefs {

    /** 参数语义就是「一个文件」的工具；只有它们才采信兜底的 `path` 键。 */
    private val FILE_PATH_TOOLS = setOf(
        "read",
        "write",
        "edit",
        "multi_edit",
        "notebook_edit",
        "str_replace_editor",
    )

    /**
     * 参数里多半是模式、变量或正文的工具：一律不解析。
     *
     * 见类注释里的理由 —— 这里刻意不做「聪明」的启发式提取。
     */
    private val UNPARSED_TOOLS = setOf("bash", "pwsh", "run_code")

    /** Windows 盘符开头的绝对路径，例如 `C:\Users\x\a.txt`。 */
    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:[\\\\/]")

    private val json = Json { ignoreUnknownKeys = true }

    /** 绝对路径判定：以 `/` 开头，或以 Windows 盘符开头。 */
    fun isAbsolutePath(value: String): Boolean =
        value.startsWith("/") || WINDOWS_DRIVE.containsMatchIn(value)

    /**
     * 扫描 [events]，按**首次出现顺序**返回去重后的文件引用。
     *
     * 输入为整个会话历史（[HistoryEntry.event] 的 `seq` 用来标注最后出现位置）；
     * 事件形态不认识、参数不是合法 JSON、字段类型不对时一律跳过，绝不抛异常 ——
     * 会话历史里有大量与文件无关的事件，任何一个畸形事件都不该让整个面板失效。
     */
    fun refs(events: List<HistoryEntry>): List<SessionFileRef> {
        val collected = LinkedHashMap<String, SessionFileRef>()
        events.forEach { entry ->
            val data = entry.event.data as? JsonObject ?: return@forEach
            val seq = entry.event.seq
            diffPaths(data).forEach { path -> collected.record(path, seq) }
            argumentPaths(data).forEach { path -> collected.record(path, seq) }
        }
        return collected.values.toList()
    }

    private fun MutableMap<String, SessionFileRef>.record(raw: String?, seq: Int) {
        val path = raw?.takeIf { it.isNotBlank() && isAbsolutePath(it) } ?: return
        val existing = this[path]
        this[path] = if (existing == null) {
            SessionFileRef(path, count = 1, lastSeq = seq)
        } else {
            existing.copy(count = existing.count + 1, lastSeq = maxOf(existing.lastSeq, seq))
        }
    }

    /** `data.meta.diffs[].path`（首选）或顶层 `data.diffs[].path`（旧版形态）。 */
    private fun diffPaths(data: JsonObject): List<String> {
        val meta = data["meta"] as? JsonObject
        val diffs = meta?.get("diffs") ?: data["diffs"]
        return (diffs as? JsonArray).orEmpty().mapNotNull { entry ->
            (entry as? JsonObject)?.string("path")
        }
    }

    /** 从三处已知的事件形态里刮出「工具名 + 参数」配对，再按工具名决定要不要取路径。 */
    private fun argumentPaths(data: JsonObject): List<String> {
        val outerName = data.string("name") ?: data.string("toolName") ?: data.string("tool")
        val candidates = mutableListOf<Pair<String?, JsonElement?>>()
        data["arguments"]?.let { candidates += outerName to it }

        contentBlocks(data["content"], outerName).let(candidates::addAll)
        contentBlocks(data["stream"], outerName).let(candidates::addAll)

        (data["message"] as? JsonObject)?.let { message ->
            val messageName = message.string("name") ?: message.string("toolName") ?: outerName
            message["arguments"]?.let { candidates += messageName to it }
            candidates += contentBlocks(message["content"], messageName)
        }

        (data["stream"] as? JsonArray).orEmpty().forEach { entry ->
            val streamed = entry as? JsonObject ?: return@forEach
            val chunk = streamed["chunk"] as? JsonObject ?: streamed
            val block = chunk["block"] as? JsonObject ?: chunk
            val name = block.string("name") ?: block.string("toolName")
                ?: chunk.string("name") ?: outerName
            val arguments = block["arguments"] ?: chunk["arguments"]
            if (arguments != null) candidates += name to arguments
        }

        return candidates.mapNotNull { (name, arguments) -> filePath(name, arguments) }
    }

    /** `content[]` 里带 `arguments`/`input` 的块；工具名优先取块自己的，其次继承外层。 */
    private fun contentBlocks(content: JsonElement?, fallbackName: String?): List<Pair<String?, JsonElement?>> =
        (content as? JsonArray).orEmpty().mapNotNull { block ->
            val value = block as? JsonObject ?: return@mapNotNull null
            val arguments = value["arguments"] ?: value["input"] ?: return@mapNotNull null
            val name = value.string("name") ?: value.string("toolName") ?: fallbackName
            name to arguments
        }

    private fun filePath(name: String?, arguments: JsonElement?): String? {
        val tool = name?.substringAfterLast('/')?.lowercase()
        if (tool != null && tool in UNPARSED_TOOLS) return null
        val args = arguments.asObject() ?: return null
        args.string("file_path")?.let { return it }
        if (tool != null && tool in FILE_PATH_TOOLS) return args.string("path")
        return null
    }

    /** 参数既可能是 JSON 字符串（实测形态），也可能是已解析好的对象；两者都接受。 */
    private fun JsonElement?.asObject(): JsonObject? = when (this) {
        is JsonObject -> this
        is JsonPrimitive -> runCatching { json.parseToJsonElement(content) as? JsonObject }.getOrNull()
        else -> null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
