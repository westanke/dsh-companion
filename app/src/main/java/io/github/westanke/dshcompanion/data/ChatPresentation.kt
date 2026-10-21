package io.github.westanke.dshcompanion.data
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Locale
import androidx.annotation.StringRes
import io.github.westanke.dshcompanion.R

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
    // 显式交付物卡片：对齐桌面端，钉在**轮次收尾**（收尾消息之后），而不是钉在
    // present 工具调用的位置上 —— 模型总在交付后补一句「文件在这儿」，卡片出现在
    // 那句之后才叫「轮次收尾」。做法：按出现顺序缓冲交付物，每轮 turn/end 处冲刷；
    // 会话尾部没有 turn/end（仍在运行、或老格式历史）时补在最后，不丢。
    val pending = LinkedHashMap<String, ChatDeliverable>()
    val items = ArrayList<ChatDisplayItem>()
    events.forEach { entry ->
        if (entry.event.type == "deliverables/presented") {
            deliverablesOf(entry).forEach { pending[it.path] = it }
            return@forEach
        }
        // 先冲卡片、再渲染 turn/end 本身：交付发生在轮次结束**之前**（失败也一样），
        // 时间序上卡片必须排在收尾消息/失败行前面。
        if (entry.event.type == "turn/end") items += pending.takeCards()
        items += displayItems(entry, results)
    }
    items += pending.takeCards()
    return mergeToolRuns(items)
}

/**
 * 把缓冲的交付物换成卡片并清空缓冲。
 *
 * id 规则 `"${seq}-deliverable-$index"`：seq 是事件序号（全局唯一），index 是本轮
 * 冲刷批内的序号 —— 两批卡片绝不撞 key（LazyColumn 的 key 必须唯一）。
 */
private fun LinkedHashMap<String, ChatDeliverable>.takeCards(): List<ChatDisplayItem> =
    values.mapIndexed { index, file ->
        ChatDisplayItem(
            id = "${file.seq}-deliverable-$index",
            seq = file.seq,
            kind = ChatItemKind.DELIVERABLE,
            deliverable = file,
        )
    }.also { clear() }

fun ChatHistory.todoItems(): List<TodoItem> {
    val values = (projections as? JsonObject)?.objectValue("values") ?: return emptyList()
    return (values["todos"] as? JsonArray).orEmpty().mapNotNull { raw ->
        val item = raw as? JsonObject ?: return@mapNotNull null
        val content = item.stringValue("content")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val status = item.stringValue("status") ?: return@mapNotNull null
        TodoItem(content, status)
    }
}

/**
 * 会话当前的权限档位。
 *
 * ## 两个来源，一个结果
 *
 * **当前值**来自会话投影的 `permissions`；**候选项**来自 `GET /chat/permission-presets`
 * （内核 `permissionPresets.catalog()` 的 BFF）。两者必须分开取，这不是本仓的选择而是
 * 内核的形状：那条 wire 视图是 `wire.view: state => ({ currentValue })` —— 只产当前值，
 * 候选项在**另一个远程服务**上，两边没有任何交叉引用。
 *
 * @param catalog 候选项清单。老 bridge 没有那条路由时传 null，此时**仍然返回**
 *   一个可用的 [PermissionSelect]（候选项为空）—— 因为「显示当前档位」这件事
 *   不依赖候选项，把它一起判成不可用，就是 v2.2.44 那个「Pill 永远不渲染」的病。
 *   候选项为空时 UI 只渲染当前档位那一个只读标签，点击不弹菜单。
 *
 * 返回 null 只发生在「投影里连一个可用的档位名都读不出」时 —— 那时调用方应当隐藏整条，
 * 而不是显示一个编出来的档位。
 */
fun ChatHistory.permissionSelect(catalog: PermissionCatalog?): PermissionSelect? {
    val values = (projections as? JsonObject)?.objectValue("values") ?: return null
    val raw = values.objectValue("permissions") ?: return null
    // @patch
    // id: permission-projection-shape-drift
    // for: 内核 `permissions` 投影的形状随版本变：旧版给 currentValue/options，
    //      新版给 { preset, sandbox, approval, seeded }，currentValue 消失。
    // why: wire schema 由内核定义，本仓不能要求上游保持某个字段；只能按「读到什么算什么」
    //      的顺序依次降级，并把降级路径都测住。
    // until: 内核稳定提供 currentValue，且 bridge 的 BFF 覆盖全部形态。
    // test: PermissionProjectionTest.kt
    val currentValue = raw.stringValue("currentValue")
        ?: raw.stringValue("preset")
        ?: raw.stringValue("sandbox")
        ?: return null
    // options 优先取同一条投影里的内联清单（bridge 老版本也可能是这么给的），
    // 都没有才用 BFF 的目录。
    val inline = (raw["options"] as? JsonArray).orEmpty().mapNotNull { entry ->
        val option = entry as? JsonObject ?: return@mapNotNull null
        val value = option.stringValue("value") ?: return@mapNotNull null
        PermissionOption(
            value,
            option.stringValue("name") ?: value,
            option.stringValue("description"),
        )
    }
    val options = inline.ifEmpty { catalog?.options.orEmpty() }
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
 * 与 [io.github.westanke.dshcompanion.data.SessionFileRefs] 是**两回事**：
 * 那个是从工具参数里嗅「提到过哪些路径」（带一堆刻意的取舍与白名单），
 * 这个读的是内核明说的「这是我给你的东西」，可靠度高得多、还带说明。
 *
 * 下个版本起 [displayItems] 也把交付物渲染成**消息流里的卡片**（对齐桌面端钉在
 * 轮次收尾）。此前 [displayItems] 只认 user/message、assistant/message、tool/call
 * 三类，`deliverables/presented` 落到 `else -> emptyList()` 被整类丢掉 —— 数据一直
 * 在日志里，就是没人读，用户问「我要你写的报告呢」找不到入口。
 *
 * 同一个路径出现多次时保留**最后一次**的说明（后写的更新）。
 */
fun ChatHistory.deliverables(): List<ChatDeliverable> {
    val out = LinkedHashMap<String, ChatDeliverable>()
    events.forEach { entry ->
        if (entry.event.type != "deliverables/presented") return@forEach
        deliverablesOf(entry).forEach { out[it.path] = it }
    }
    return out.values.toList()
}

/**
 * 单条 `deliverables/presented` 事件的文件清单（按出现顺序，不做跨事件去重 ——
 * 去重语义由调用方决定：[deliverables] 全局保留最后说明，[displayItems] 按轮冲刷）。
 */
internal fun deliverablesOf(entry: HistoryEntry): List<ChatDeliverable> {
    val data = entry.event.data as? JsonObject ?: return emptyList()
    val files = data["files"] as? JsonArray ?: return emptyList()
    return files.mapNotNull { raw ->
        val file = raw as? JsonObject ?: return@mapNotNull null
        val path = file.stringValue("path")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ChatDeliverable(path, file.stringValue("description"), entry.event.seq)
    }
}
