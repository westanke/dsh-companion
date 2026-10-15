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
 * 会话展示层的**数据模型**与事件帧解析。
 *
 * 这里只放「长什么样」和「从一帧里取出什么」，不放任何渲染或组合逻辑：
 * - 展示项（[ChatDisplayItem]）、工具明细（[ToolCallDetail]）、终局失败（[ChatTurnFailure]）
 *   与附件（[ChatAttachment]）这些结构；
 * - 流式状态（[LiveChatState]）及其增量累积；
 * - 两个事件帧解析：[chatDelta]（`chat.message.delta` 的 token 增量）与
 *   [chatTurnFailure]（`chat.turn.failed` 的终局失败）；
 * - 全模块共用的 JSON 取值助手与两个 [Json] 实例。
 *
 * 从 `ChatPresentation.kt`（825 行）拆出，实现逐字未改。JSON 助手由 `private` 调整为
 * `internal`：拆分后同包的其他文件（历史映射、工具行）要继续用它们。
 */

enum class ChatItemKind { USER, ASSISTANT, REASONING, CONTEXT, TOOL, FAILURE }

/**
 * 一轮模型运行的**终局失败**（限流 429、配额用尽、密钥无效、登出停止…）。
 *
 * 与服务端 `chat.turn.failed` 帧的 `data.failure` 一一对应。所有字段都有默认值：
 * 这是从事件里解出来的结构，而服务端的 `code` 可能是空串（免费额度限流就是），
 * 所以 [code] 必须**可区分「空串」与「没有」** —— 客户端显示错误码那一位时，
 * 空串也应该原样显示，不能当成缺失。
 *
 * [stopped] 为 true 表示这不是模型故障，而是「退出登录导致任务停止」。
 */
data class ChatTurnFailure(
    val message: String = "",
    val code: String? = null,
    val stopped: Boolean = false,
) {
    /**
     * 给人看的一行标题 —— 用**资源 id** 表达，具体语言交给界面。
     *
     * 这里以前直接写死中文（「任务已停止」/「本轮运行失败」），于是英文界面下也弹中文。
     * data 层不碰 Context，所以只给 id，渲染时再取字符串。
     */
    @get:StringRes
    val titleRes: Int get() = if (stopped) R.string.turn_stopped_title else R.string.turn_failed_title
}

/**
 * 会话历史里的一个图片附件。
 *
 * 注意这里**只有引用没有字节**：消息发送时图片是 base64 内联的，但存进历史后就变成
 * `{type:'image', attachment:{attachmentId:'sha256:…', …}}` —— 字节在服务端的附件存储里。
 * 所以显示一张历史图片需要第二步：拿 [attachmentId] 去 `/api/v1/attachments/:id` 取字节。
 *
 * 所有字段都有默认值：这是从服务端事件里解出来的结构，缺字段比多字段常见得多
 * （本项目已经因为严格解码踩过三次坑）。
 */
data class ChatAttachment(
    val attachmentId: String = "",
    val mediaType: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Int = 0,
    val name: String? = null,
    /**
     * 纯文本模型发图时的**交接文件路径**（服务器绝对路径），非交接图是 null。
     *
     * 为什么单独一个字段：这类图**不在附件存储里**（那只存会话里的原生 image 部件），
     * 它是插件落在会话工作目录 `.dsh-images/` 下的普通文件。所以取字节不能走
     * `readAttachment`，要走「路径 → 授权根」的官方解析（`resolvePath` + `readFile`）——
     * 那条路客户端早就有了，正是「已接入的图片显示方法」。
     */
    val handoffPath: String? = null,
)

data class ChatDisplayItem(
    val id: String,
    val seq: Int,
    val kind: ChatItemKind,
    val title: String? = null,
    val body: String = "",
    val streaming: Boolean = false,
    /** 这条消息附带的图片；只发图不发字的消息正是靠它才不会被丢掉。 */
    val attachments: List<ChatAttachment> = emptyList(),
    /**
     * 工具调用的动作短语，如 ["运行了代码", "修改了文件"]。
     *
     * 单独放一个字段而不是揉进 [title]：连续的多个工具调用要**合并成一行**，
     * 合并就是把各条的短语按首次出现顺序去重求并集。揉成字符串就得再拆开，
     * 那是在用分隔符当数据结构。
     */
    val actions: List<String> = emptyList(),
    /**
     * 工具调用的**逐条明细**（输入 / 输出 / 成败 / 耗时）。
     *
     * 为什么是列表：连续的多次调用会被 [mergeToolRuns] 合并成一行，而详情视图要求
     * **每条都能单独展开** —— 只留第一条就等于把其余调用的输出全丢了。
     * 空列表 = 这一项不是工具调用，或者拿不到 tool/call 数据。
     */
    val tools: List<ToolCallDetail> = emptyList(),
    /**
     * 这一项是不是一轮的**终局失败**（限流 / 配额 / 密钥 / 登出停止）。
     *
     * 为什么单独一个字段而不是塞进 [body]：失败行有自己的一套版式（标题 + 正文 +
     * 错误码），而 [body] 在所有渲染器里都是「一段 Markdown 正文」。
     * 塞进去的话每个渲染器都要反向嗅探，与其那样不如直接给类型。
     */
    val failure: ChatTurnFailure? = null,
)

/**
 * 一次工具调用的完整明细 —— 详情视图要渲染的东西都在这儿。
 *
 * 字段全部对着**真实会话日志**（105 份 `session.v4.jsonl.zstd`，共 19050 条 tool/result）：
 * - `tool/call` 的 data：`turn`/`step`/`callId`/`name`/`arguments`；`arguments` 是**字符串**
 *   （12471/12471 都能 JSON.parse），所以要格式化后再显示。
 * - `tool/result` 的 data.message：`role`/`source`/`toolCallId`/`content`/`isError`/`id`，
 *   六个 key 19050/19050 全在；`content` **一定是数组**（19050/19050），元素只有
 *   `text`（19050）与 `image`（73）两种；`isError` 为 true 的 689 条。
 * - 关联字段用 `message.toolCallId`（与 `message.source.callId` 实测完全相等：8006/8006）。
 */
data class ToolCallDetail(
    val callId: String = "",
    val name: String = "",
    /** 内核给的原始 arguments（JSON 字符串，未格式化）。空串 = 这个工具没有参数。 */
    val arguments: String = "",
    /** 结果正文；`null` = 还没有 `tool/result`（例如仍在运行）。空串是合法的「有结果但没文字」。 */
    val result: String? = null,
    /**
     * 内核 `message.isError`。
     *
     * 注意它**不等于**「命令没报错」：实测样本里 `curl` 超时、输出全是 stderr 的那条
     * `isError` 仍是 false。它只是内核自己标记的工具级失败。
     */
    val failed: Boolean = false,
    /** `result.time - call.time`（毫秒）。配不上、或时钟倒退时是 `null`，UI 直接不显示这一行。 */
    val durationMs: Long? = null,
)

data class ChatDelta(
    val sessionId: String,
    val turn: Int,
    val step: Int,
    val index: Int,
    val kind: ChatItemKind,
    val text: String,
)

data class LiveChatBlock(val index: Int, val kind: ChatItemKind, val text: String)

/**
 * 流式状态：**只装当前这一段（turn+step）**。
 *
 * 「turn/step 一变就整个换掉 base」是刻意的 —— 上一轮/上一段的内容由 REST 历史补回来，
 * 两边都留着就成了重复显示（见 LiveChatGroupingTest）。
 *
 * 但这条约定有个前提：**历史真的会被推回页面**。
 * 那个前提长期不成立（ChatWebScreen 里一处早退判据写反了，逢「liveChat 刚被置空」
 * 就 return），于是重置清掉的那部分没有任何东西补回来 —— 真机上就是
 * 「一直在同一个位置说这一段，就把上一段没了」。修的是那处推送，不是这里。
 */
data class LiveChatState(
    val sessionId: String,
    val turn: Int,
    val step: Int,
    val blocks: List<LiveChatBlock> = emptyList(),
    val revision: Long = 0,
) {
    fun append(delta: ChatDelta): LiveChatState {
        val base = if (sessionId == delta.sessionId && turn == delta.turn && step == delta.step) this else {
            LiveChatState(delta.sessionId, delta.turn, delta.step)
        }
        val existing = base.blocks.indexOfFirst { it.index == delta.index }
        val next = if (existing < 0) {
            base.blocks + LiveChatBlock(delta.index, delta.kind, delta.text)
        } else {
            base.blocks.toMutableList().also { blocks ->
                val previous = blocks[existing]
                blocks[existing] = LiveChatBlock(
                    delta.index,
                    delta.kind,
                    if (previous.kind == delta.kind) previous.text + delta.text else delta.text,
                )
            }
        }
        return base.copy(blocks = next.sortedBy(LiveChatBlock::index), revision = base.revision + 1)
    }

    fun displayItems(): List<ChatDisplayItem> = blocks.filter { it.text.isNotEmpty() }.map { block ->
        ChatDisplayItem(
            id = "live-$turn-$step-${block.index}-${block.kind}",
            seq = Int.MAX_VALUE,
            kind = block.kind,
            body = block.text,
            streaming = true,
        )
    }
}

fun WorkspaceEvent.chatDelta(): ChatDelta? {
    if (type != "chat.message.delta") return null
    val value = data as? JsonObject ?: return null
    val kind = when (value.stringValue("kind")) {
        "text" -> ChatItemKind.ASSISTANT
        "reasoning" -> ChatItemKind.REASONING
        else -> return null
    }
    return ChatDelta(
        sessionId = value.stringValue("sessionId") ?: return null,
        turn = value.intValue("turn") ?: return null,
        step = value.intValue("step") ?: return null,
        index = value.intValue("index") ?: return null,
        kind = kind,
        text = value.stringValue("text") ?: return null,
    )
}

/**
 * 从 `chat.turn.failed` 帧里取出失败详情。
 *
 * 判据是 **type 精确等于** `chat.turn.failed`（插件 `normalizeDshEvent` 专门为
 * `turn/end` + `reason.kind === 'error'` 发的那帧），不是「任何带 failure 的帧」。
 * 这样做的好处是：插件没升级、老服务端只发普通 `chat.turn.end` 时，这里安静返回
 * null，客户端仍旧走原来的收尾路径，不会因为一个新字段抽风。
 */
fun WorkspaceEvent.chatTurnFailure(): ChatTurnFailure? {
    if (type != "chat.turn.failed") return null
    val value = data as? JsonObject ?: return null
    val failure = value.objectValue("failure") ?: return null
    return ChatTurnFailure(
        message = failure.stringValue("message").orEmpty(),
        // 注意：空串 code 要**留空串**，不能退回 null。免费额度限流的 code 就是 ""，
        // 而 UI 要照原样显示「错误码：」后面那一格。
        code = failure.stringValue("code"),
        stopped = failure.booleanValue("stopped") == true,
    )
}
