package io.github.hakunm.deepseekharness.data
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Locale
import androidx.annotation.StringRes
import io.github.hakunm.deepseekharness.R

/**
 * 会话展示的**单一出口**（门面）。
 *
 * 按 `AGENTS.md` §4.1 的约定，会话展示只走这一个出口：[displayItems] 把一整段
 * 历史变成一串 [ChatDisplayItem]，其余三个读的是会话投影里的结构化片段
 * （[todoItems] 待办、[permissionSelect] 权限、[deliverables] 显式交付物）。
 *
 * ## 为什么拆成五个文件
 *
 * 原文件 825 行，超过 `docs/project/TECH-DEBT.md` 的 800 行预算。按**职责边界**
 * 拆开，每个函数的实现逐字保留，只换所在文件：
 *
 * | 文件 | 职责 |
 * |---|---|
 * | `ChatPresentation.kt`（本文件） | 单一出口 + 会话投影读取 |
 * | `ChatModels.kt` | 数据模型、事件帧解析、JSON 取值助手 |
 * | `ChatHistoryMapping.kt` | 逐事件映射成展示项（用户/助手/上下文/失败行） |
 * | `ChatHandoff.kt` | 「纯文本模型发图」落进工作区的交接附件 |
 * | `ChatToolPresentation.kt` | 工具行动作短语、连续调用合并、逐条详情 |
 *
 * `internal` / `private` 可见性与拆分前一致，现有单测无需改动。
 */

fun ChatHistory.displayItems(): List<ChatDisplayItem> {
    // 先按 callId 把 tool/result 建成索引，再逐条渲染：
    // 结果的 callId 藏在 `data.message.toolCallId` 里，配对要跨事件 ——
    // 单看一条 entry 是配不上的（这正是以前 tool/result 整类被丢掉的原因）。
    val results = toolResultsByCallId(events)
    return mergeToolRuns(events.flatMap { displayItems(it, results) })
}

fun ChatHistory.todoItems(): List<TodoItem> {
    val values = (projections as? JsonObject)?.objectValue("values") ?: return emptyList()
    return (values["todos"] as? JsonArray).orEmpty().mapNotNull { raw ->
        val item = raw as? JsonObject ?: return@mapNotNull null
        val content = item.stringValue("content")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val status = item.stringValue("status") ?: return@mapNotNull null
        TodoItem(content, status)
    }
}

fun ChatHistory.permissionSelect(): PermissionSelect? {
    val values = (projections as? JsonObject)?.objectValue("values") ?: return null
    val raw = values.objectValue("permissions") ?: return null
    val currentValue = raw.stringValue("currentValue") ?: return null
    val options = (raw["options"] as? JsonArray).orEmpty().mapNotNull { entry ->
        val option = entry as? JsonObject ?: return@mapNotNull null
        val value = option.stringValue("value") ?: return@mapNotNull null
        val name = option.stringValue("name") ?: return@mapNotNull null
        PermissionOption(value, name, option.stringValue("description"))
    }
    return PermissionSelect(options, currentValue)
}
/**
 * 会话里 agent **显式交付**给用户的一个文件。
 *
 * 来源是内核的结构化事件 `deliverables/presented`（`present` 工具触发）：
 * `data.files[] = { path, description }` —— 绝对路径 + 人写的说明，
 * **不是**从工具参数里猜出来的。
 */
data class ChatDeliverable(
    val path: String,
    val description: String? = null,
    val seq: Int = 0,
)

/**
 * 取出会话里被显式交付的文件。
 *
 * 与 [io.github.hakunm.deepseekharness.data.SessionFileRefs] 是**两回事**：
 * 那个是从工具参数里嗅「提到过哪些路径」（带一堆刻意的取舍与白名单），
 * 这个读的是内核明说的「这是我给你的东西」，可靠度高得多、还带说明。
 *
 * 为什么以前 App 里看不到交付物：[displayItems] 只认 user/message、assistant/message、
 * tool/call 三类，`deliverables/presented` 落到 `else -> emptyList()` 被整类丢掉。
 * 于是用户问「我要你写的报告呢、你打好的包呢」—— 数据一直在日志里，就是没人读。
 *
 * 同一个路径出现多次时保留**最后一次**的说明（后写的更新）。
 */
fun ChatHistory.deliverables(): List<ChatDeliverable> {
    val out = LinkedHashMap<String, ChatDeliverable>()
    events.forEach { entry ->
        if (entry.event.type != "deliverables/presented") return@forEach
        val data = entry.event.data as? JsonObject ?: return@forEach
        val files = data["files"] as? JsonArray ?: return@forEach
        files.forEach { raw ->
            val file = raw as? JsonObject ?: return@forEach
            val path = file.stringValue("path")?.takeIf { it.isNotBlank() } ?: return@forEach
            out[path] = ChatDeliverable(path, file.stringValue("description"), entry.event.seq)
        }
    }
    return out.values.toList()
}
