package io.github.westanke.dshcompanion.data.ws

import io.github.westanke.dshcompanion.data.WorkspaceEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * `/ws/mobile` 的线路帧常量与编解码（`docs/project/WS-FIRST-DESIGN.md` §3.1/§3.3）。
 *
 * 全集在桥侧 `src/host/ws-protocol.ts:70-160`：客户端 `type` 约 70 个、服务端 `kind` 约 90 个。
 * **这里只收 P1/P2/P3 用得到的**，其余在 [PENDING_CLIENT_FRAMES] / [PENDING_SERVER_FRAMES] 里
 * 用注释列名留待后续阶段 —— 一次全抄进来会让「本阶段到底支持什么」失去判据，
 * 而抄错一个字符串（`session-archived` vs `session-archive`）的代价是运行时静默超时。
 *
 * 帧形状（两端一致，`encodeFrame`/`decodeFrame` 就是裸 JSON）：
 * - 客户端 → 服务端：`{ "type": "<类型>", ... }`，请求类另带 `requestId`
 * - 服务端 → 客户端：`{ "kind": "<类型>", "data": {...} }`（`id`/`time` 是服务端主帽头，客户端不依赖）
 */

/** WS 升级路径（桥 `src/host/ws-protocol.ts:15`）。与 REST 的 `/api/v1/events` 并存。 */
internal const val WS_PATH: String = "/ws/mobile"

// ───────────────────────── 子协议 ─────────────────────────

/**
 * `Sec-WebSocket-Protocol` 的取值规则（桥 `src/host/ws-handler.ts:233-260`）。
 *
 * **令牌只能走这里，不许进 URL query** —— 桥侧对 `?token=` / `?code=` 是**直接关连接**的
 * （`ws-handler.ts:262-267`），不是忽略。query 里的令牌还会进访问日志与反代日志，
 * 那正是 WS 鉴权要避开的泄漏面。
 */
internal object WsSubprotocol {
    /** 首项固定是它；桥在升级完成后拿 `webSocket.protocol` 与它逐字比较。 */
    const val PROTOCOL: String = "dsh-mobile-v1"

    /** 首次配对：`dsh-pair.<6-hex-code>`。 */
    const val PAIR_PREFIX: String = "dsh-pair."

    /** 长期令牌：`dsh-auth.<token>`。 */
    const val AUTH_PREFIX: String = "dsh-auth."

    /**
     * 通用内核调用面的可选子协议（桥侧 issue #26 阶段 5）。
     *
     * 桥按**请求头里声明的清单**判定能力面（`ws-handler.ts` 的 `offersKernelSubprotocol`），
     * 声明了它才启用 `kernel-call` / `kernel-call-cancel` 与两条下行帧；
     * 握手回执仍是 [PROTOCOL]（= `dsh-mobile-v1`），不是这个值。
     *
     * 老桥不认识它，会直接忽略这一项 —— 所以它只追加在**末项**，永不动首项。
     */
    const val KERNEL_CALL_PROTOCOL: String = "dshrb-v1"

    /**
     * 已配对设备的重连子协议清单。
     *
     * 顺序**有讲究**：`dsh-mobile-v1` 必须在前 —— `ws` 库取 `protocols[0]` 作为协商结果，
     * 而桥用它判「是不是我的客户端」。反过来写会让每一次连接都被 `close(4001)`。
     *
     * [KERNEL_CALL_PROTOCOL] 追加在末项：它只影响**能力面**（桥按请求头清单判定），
     * 不影响协商结果；老桥忽略未知项，行为与之前完全一致。
     */
    fun forToken(token: String): List<String> =
        listOf(PROTOCOL, AUTH_PREFIX + token, KERNEL_CALL_PROTOCOL)

    /** 首次配对用的子协议清单（配对成功后服务端推 `paired` 帧带回长期令牌）。 */
    fun forPairing(code: String): List<String> =
        listOf(PROTOCOL, PAIR_PREFIX + code, KERNEL_CALL_PROTOCOL)
}

// ───────────────────────── 客户端帧 type ─────────────────────────

/**
 * 本阶段（P1 + P2 高频三域）用得到的客户端帧 `type`。
 *
 * 与桥 `src/host/ws-protocol.ts` 的 `ClientFrameType` 联合类型逐字对齐；常量名去掉连字符转大写下划线。
 */
internal object WsClientFrame {
    // —— 基础帧（不依赖域处理器，ws-handler.ts:508-537）——
    const val PING: String = "ping"
    const val SUBSCRIBE: String = "subscribe"
    const val UNSUBSCRIBE: String = "unsubscribe"

    // —— SessionPort ——
    const val SESSIONS: String = "sessions"
    const val HISTORY: String = "history"
    const val SESSION_CREATE: String = "session-create"
    const val SESSION_RENAME: String = "session-rename"
    const val SESSION_ARCHIVE: String = "session-archive"
    const val SESSION_CANCEL: String = "session-cancel"

    /**
     * 会话分叉。
     *
     * **刻意不在桥的 `ClientFrameType` 联合里**（`grep -n "fork" src/host/ws-protocol.ts` 无命中），
     * 但 `ws-sessions.ts` 的 `handle()` 里有 `case 'fork'`，且 `ClientFrame` 声明是
     * `interface ClientFrame { type: string; [key: string]: unknown }` —— 运行时合法，
     * asyncapi（`docs/api/asyncapi.yaml:450,589`）也已声明。所以这里照字面量收下。
     */
    const val FORK: String = "fork"

    // —— MessagePort ——
    const val MESSAGE: String = "message"
    const val ATTACHMENT: String = "attachment"

    // —— EventPort ——
    // 事件域没有独立 type：靠 SUBSCRIBE 建订阅，服务端用 `event` 帧回推。

    // —— HitlPort（P3）——
    //
    // 只有这两条：桥侧 HITL 处理器一共就 3 个写帧，第三条 `question-cancel` 刻意不接 ——
    // App 里根本没有「撤回提问」的入口，REST 侧也没有对等端点（`DshClient` 只有
    // pendingQuestions / answerQuestions），接进来就是一段永远跑不到的死代码。
    const val APPROVAL_RESPONSE: String = "approval-response"
    const val QUESTION_ANSWER: String = "question-answer"

    // —— CommandPort（P3）——
    //
    // `command-options` / `command-select` 不接：App 的 `/model`、`/permission` 二级菜单
    // 是**本地渲染**的（候选来自 `sessionModels` 与 `/permission-presets`），换成桥的
    // opaque optionId 会连着改 UI 状态机与命令模型，而收益只是少一次 REST —— 不划算。
    const val COMMANDS: String = "commands"
    const val COMMAND_EXECUTE: String = "command-execute"

    // —— ModelPort（P3）——
    //
    // `default-model` / `save-default-model` 桥明确回 NOT_IMPLEMENTED（ws-models.ts:38/41），
    // 全局目录仍然只有 REST `GET /settings/providers` 一条路，所以不接。
    const val MODELS: String = "models"

    /**
     * 会话级切模型。
     *
     * **应答 kind 是 `sent`**（`ws-models.ts:80`），与 `message` 同 kind —— 两边必须靠
     * `data.type` / `data.accepted` 互相排除，见 [WsMessagePort] 与 [WsModelPort] 的 matcher。
     */
    const val SELECT_MODEL: String = "select-model"

    /**
     * 会话级切 agent 预设。
     *
     * **应答 kind 也是 `sent`**（`ws-presets.ts:82`），且 `data.type` 就是 `select-agent-preset`。
     * 至此 `sent` 这个 kind 有 **五个** 生产者：`message`、`unsubscribe`、`select-model`、
     * [SESSION_AGENT_PRESET]、[SELECT_AGENT_PRESET] —— 判别一律看 `data.type`，见
     * [WsMessagePort] 的 matcher 表。
     */
    const val SELECT_AGENT_PRESET: String = "select-agent-preset"

    /**
     * 给会话**锁定** agent 预设（与 [SELECT_AGENT_PRESET] 的「切一次」不同，这条会写进会话配置）。
     *
     * 应答同样是 `sent` + `data.type == "session-agent-preset"`（`ws-presets.ts:73`）。
     * App 目前没有「锁定」入口（只切一次），列在这里是为了让 `sent` 的判别表保持完整 ——
     * 少列一个，将来谁接了就会莫名抢走 `message` 的应答。
     */
    const val SESSION_AGENT_PRESET: String = "session-agent-preset"

    // —— 通用内核调用面（桥侧 issue #26 阶段 5；仅在声明 [WsSubprotocol.KERNEL_CALL_PROTOCOL] 时可用）——

    /**
     * 调一个内核端点。`callId` 由客户端自铸，结果帧原样回显它（**不回显 requestId**）。
     *
     * 同一 `(principal, callId)` 在 24h 内重试按幂等回放 —— 所以重试要复用同一个 callId，
     * 而不是重新铸一个。
     */
    const val KERNEL_CALL: String = "kernel-call"

    /**
     * 取消一次还在跑的内核调用（best-effort）。
     *
     * 已经结算的调用照样会收到结果帧（客户端按 callId 去重）；没有匹配的 callId 时
     * 桥回 `error` 帧、code 为 `call/not-found`。
     */
    const val KERNEL_CALL_CANCEL: String = "kernel-call-cancel"
}

/**
 * **待接**的客户端帧（桥侧已实现，本仓尚未接）。按域分组，P3/P4 逐个上。
 *
 * 留成注释而不是常量：常量摆着不用会让人以为「已经支持」，而实际调用点一个都没有 ——
 * 这比没写更糟。等哪个域真要切了，把名字挪进 [WsClientFrame] 并补一条用例。
 *
 * - HITL：`question-cancel`（App 无撤回入口，且 REST 侧无对等端点）
 * - 命令：`command-options` `command-select`（二级菜单本地渲染，换桥的 opaqueId 要连 UI 一起改）
 * - 模型：`providers` `default-model` `permission-options` `permission`
 *   （`default-model` 桥回 NOT_IMPLEMENTED；全局目录仍只有 REST 一条路）
 * - 投影：`tasks` `goal` `session-stats` `context-usage` `agent-team` `subagents`
 *   （**读**这六项刻意不迁，见设计 §3.4；只迁 `goal-edit`/`goal-pause`/`goal-resume`/`goal-clear` 写操作）
 * - 文件：`file-download-open` `file-download-read` `file-download-cancel` `file-resolve`
 * - 搜索/队列/归档恢复：`search` `queue-update` `session-archives`
 * - 工作区与设置：`workspaces` `host` `plugin-catalog` `plugin-inspect` `plugin-install` `plugin-settings`
 *   `agent-presets` `defaults` `set-default` `settings-namespaces`
 * - **不迁**（设计 §3.5）：`speech-*`（10 个）、`schedule-*`（5 个）、`file-upload-*`（4 个）
 */
internal const val PENDING_CLIENT_FRAMES: String = "见上方注释：HITL/命令/模型/投影/文件/搜索/设置，语音与定时不迁"

// ───────────────────────── 服务端帧 kind ─────────────────────────

/** 本阶段用得到的服务端帧 `kind`（与桥 `ServerFrameKind` 逐字对齐）。 */
internal object WsServerFrame {
    /** 连接后首帧，必到；载荷见 [HelloFrame]。 */
    const val HELLO: String = "hello"

    /** `ping` 的应答；**无 requestId**，走推送路径。 */
    const val PONG: String = "pong"

    /** 流式事件（`data` 是一条 `WorkspaceEvent` 形状的对象）；无 requestId。 */
    const val EVENT: String = "event"

    /** `subscribe` 的应答。 */
    const val SUBSCRIBED: String = "subscribed"

    /** `message` / `unsubscribe` 的通用确认。 */
    const val SENT: String = "sent"

    /** 结构化错误。`data.code` 是 `AUTH_REQUIRED` / `UNKNOWN_TYPE` / `NOT_IMPLEMENTED` 等。 */
    const val ERROR: String = "error"

    /**
     * 服务端主动告知「刚才因为背压丢了帧」：`data` 是 `{dropped, reason}`，**无 requestId**。
     *
     * 桥在发送队列溢出后补发这一帧（`ws-handler.ts` 的 `drain`，2026-10-29 补）。丢掉的一定是
     * **可重建帧**（`event` 与 `tasks-updated`/`goal-updated` 这类通知），应答帧一条都不会丢 ——
     * 所以收到它正确的反应是「重取一次权威状态」，而不是「认为这条连接废了」。
     *
     * 名字是 kebab-case，因为 `/ws/mobile` 的 kind 全是 kebab；它在老通道
     * （`/api/v1/events`）上的对应物叫 `connection.resyncRequired`（那个通道的 type 带点）。
     */
    const val RESYNC_REQUIRED: String = "connection-resync-required"

    // —— SessionPort 的应答 ——
    const val SESSIONS: String = "sessions"
    const val HISTORY: String = "history"
    const val SESSION_CREATED: String = "session-created"
    const val SESSION_RENAMED: String = "session-renamed"
    const val SESSION_ARCHIVED: String = "session-archived"
    const val SESSION_CANCELLED: String = "session-cancelled"

    /**
     * `fork` 的应答：`data.sessionId` 是**新**会话 id，**不含原 sessionId**。
     *
     * 正因为没有判别字段，[WsRequestRouter] 只能靠「同 kind 串行」把它认出来。
     */
    const val FORK: String = "fork"

    // —— HitlPort 的应答（P3）——

    /**
     * `approval-response` 的确认，**kind 与请求 type 同名**（`ws-hitl.ts:44`）。
     *
     * 这不是命名巧合而是桥的写法：`data` 里 `sessionId` + `approvalId` 都在，因此还能配对；
     * 但看到 `approval-resolved` 时**不要**拿它当应答 —— 那是随后额外推的一条推送帧。
     */
    const val APPROVAL_RESPONSE: String = "approval-response"

    /**
     * `question-answer` 的应答（`ws-hitl.ts:66-75`）。
     *
     * `data.accepted == false` 时带 `data.reason`，取值 `not-pending` / `reply-queued` /
     * `bad-response` / `not-answerable` —— 这是**正常应答里的业务失败**，不是 error 帧。
     */
    const val QUESTION_RESPONSE: String = "question-response"

    /**
     * `approval-response` 成功之后桥**额外推**的一条（`ws-hitl.ts:50-51`）。
     *
     * 它在 `REQUEST_ID_ECHO_EXCLUDED_KINDS` 里，且是主动推送 —— 永远不会有 pending 等它，
     * 拿它当应答只会让那条 pending 超时。列在这里是为了让读代码的人认得出「这是推送」。
     */
    const val APPROVAL_RESOLVED: String = "approval-resolved"

    // —— CommandPort 的应答（P3）——
    const val COMMANDS: String = "commands"
    const val COMMAND_EXECUTED: String = "command-executed"

    // —— ModelPort 的应答（P3）——
    const val MODELS: String = "models"

    // —— 通用内核调用面（桥侧 issue #26 阶段 5）——

    /**
     * 一次内核调用的结果。**没有 requestId**（在桥的 `REQUEST_ID_ECHO_EXCLUDED_KINDS` 里），
     * 只按 `data.callId` 与请求关联 —— 不要拿它当普通应答塞进 pending-by-requestId。
     */
    const val KERNEL_CALL_RESULT: String = "kernel-call-result"

    /**
     * 目录快照，鉴权通过后桥自动推一次（白名单 ∩ 内核目录，**不是**内核全量端点）。
     * 同样是主动推送、无 requestId；客户端要容忍未知字段与未知端点。
     */
    const val KERNEL_ENDPOINTS: String = "kernel-endpoints"
}

/**
 * **待接**的服务端帧（P4）：
 * `question-requested` `approval-requested` `question-resolved` `tasks` `goal` `session-stats`
 * `context-usage` `agent-team` `subagents` `file-list` `file-resolved` `file-download-*`
 * `search` `session-archives` `paired`。
 *
 * 注意 `question-requested` / `approval-requested` **天生无 requestId**（服务端主动推），
 * 所以它们即便将来接进来，也必须走推送回调而不是 pending map。
 *
 * P3 已经接掉的那几个（`question-response` `approval-response` `approval-resolved`
 * `commands` `command-executed` `models`）已挪进 [WsServerFrame]，不再列在这里。
 */
internal const val PENDING_SERVER_FRAMES: String = "见上方注释：HITL/命令/模型/投影/文件/搜索，均无 requestId 者走推送"

// ───────────────────────── 能力位 ─────────────────────────

/**
 * 本阶段**必需**的能力位（`hello.capabilities` 里必须有，否则 [ChannelState.WsDegraded]）。
 *
 * 为什么是这三个：它们是 P2 要切的三条高频路径所依赖的 REST 对等面 ——
 * `session-list-v1`（会话列表）、`history-v1`（历史）、`turn-cancel-v1`（停止生成）。
 * 缺任何一条，那个域就该老实走 REST，而不是「WS 发出去、服务端回 NOT_IMPLEMENTED」。
 *
 * **P1 自身一帧不发**，所以这份清单此刻是**为 P2 前置声明**的：`decideAfterHello` 是
 * 唯一判据，P2 接域时直接用同一个集合，不许在调用点另写一份。
 *
 * **P3 不往这里加位**：桥侧 `ws-hitl.ts` / `ws-commands.ts` / `ws-models.ts` 与
 * `ws-handler.ts` 是同一次提交引入的（`f7b5c44` → 拆域 `38e6f3b`，2026-10-28），
 * 也就是说「有 `/ws/mobile` 的桥就一定有这三个域」—— 再按 `commands-v1` /
 * `session-config-v1` 分支一次，只会多出一条永远不会走到的降级路径。
 */
internal val REQUIRED_CAPABILITIES_FOR_WS: Set<String> =
    setOf("session-list-v1", "history-v1", "turn-cancel-v1")

// ───────────────────────── hello 载荷 ─────────────────────────

/** `hello` 帧的 `data` 载荷（桥 `ws-protocol.ts` 的 `HelloData`）。 */
internal data class HelloFrame(
    /** 协议修订版；当前桥发 3。低于 [MIN_WS_PROTOCOL] 按能力不足处理。 */
    val protocol: Int,
    val capabilities: Set<String>,
    val authenticated: Boolean,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val scopes: List<String> = emptyList(),
    val rootIds: List<String> = emptyList(),
    /** 本连接实际接入的监听端口（诊断用）。 */
    val port: Int? = null,
    /** 当前已鉴权连接数（诊断用）。 */
    val clients: Int? = null,
)

// ───────────────────────── 通用内核调用面（桥侧 issue #26 阶段 5）─────────────────────────

/**
 * `kernel-endpoints` 目录里的一条端点。
 *
 * 元素形状桥侧**尚未冻结**（asyncapi 里 items 是空 schema），所以只取已知字段、
 * 忽略未知字段；`endpoint` 缺失的元素由解析层直接丢弃。
 */
data class KernelEndpoint(
    /** 内核描述符 `namespace/method`，原样喂回 [WsFrames.kernelCall] 的 `endpoint`。 */
    val endpoint: String,
    /** 所属域（诊断展示用；不要据此推断能力）。 */
    val scope: String = "",
    /** 是否流式端点；流式结果在 `value.frames`（桥默认收 64 帧，可能带 `truncated`）。 */
    val stream: Boolean = false,
    /**
     * 该端点 `args` 允许的键集合。
     *
     * **客户端不要硬编码键名**：桥把它从内核描述符里算出来，正是为了让界面按它渲染/校验。
     */
    val wires: List<String> = emptyList(),
    /** 人读摘要（桥侧内核描述符给的，可能为空串）。 */
    val summary: String = "",
)

/**
 * `kernel-call-result` 的错误形状。
 *
 * `METHOD_NOT_ALLOWED`（不在白名单）与「端点不存在」在桥侧**同形**，不要据此推断端点存在性；
 * 真正的发现源只有 [KernelEndpoint] 目录。
 */
data class KernelCallError(val code: String, val message: String)

/**
 * 一次内核调用的结果。
 *
 * 成功与业务失败都在这条帧里（`ok:false` + [error]），所以调用方拿到它不等于成功；
 * `value` 的形状由端点自己决定（流式端点是 `{frames, truncated?}`），客户端不做假设。
 */
data class KernelCallResultFrame(
    val callId: String,
    val ok: Boolean,
    val value: JsonElement?,
    val error: KernelCallError?,
)

// ───────────────────────── 编解码 ─────────────────────────

/**
 * 帧编解码。**刻意用 `JsonObject` 而不是一堆 `@Serializable` data class**：
 *
 * `/ws/mobile` 的帧是「一个 type/kind + 任意附加字段」的开放形状，桥侧也一直在加字段
 * （`projectionState`、`asOfSeq`、`replayed`…）。给每种帧都写 data class 会让客户端
 * 在服务端加字段时被迫跟着发版 —— 而 `ignoreUnknownKeys` 只对**解码进 data class** 有效，
 * 对「先判 kind 再取 data」这条路本来就该保持宽松。类型安全的收益交给各 Port 的实现层。
 */
internal object WsFrames {

    /** 与 `DshClient.json` 同一套宽容策略；这里是独立实例（WS 层不依赖 DshClient 的实现细节）。 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** 客户端帧编码（桥侧 `encodeFrame` 就是 `JSON.stringify`）。 */
    fun encode(frame: JsonObject): String = json.encodeToString(JsonObject.serializer(), frame)

    /** 服务端帧解码；非法 JSON 或非对象返回 null（由调用方决定忽略还是关连接）。 */
    fun decode(text: String): JsonObject? =
        runCatching { json.decodeFromString(JsonObject.serializer(), text) }.getOrNull()

    /** 服务端帧的 `kind`；缺字段或不是字符串时返回 null。 */
    fun kindOf(frame: JsonObject): String? =
        (frame[KIND] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    /** 客户端帧的 `type`。 */
    fun typeOf(frame: JsonObject): String? =
        (frame[TYPE] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    /** 帧上的 `requestId`（服务端回显时在 `data` 里，也容忍放在顶层）。 */
    fun requestIdOf(frame: JsonObject): String? {
        val data = frame[DATA] as? JsonObject
        val fromData = (data?.get(REQUEST_ID) as? JsonPrimitive)?.contentOrNull
        val direct = (frame[REQUEST_ID] as? JsonPrimitive)?.contentOrNull
        return (fromData ?: direct)?.takeIf { it.isNotEmpty() }
    }

    /** 帧的 `data` 对象；没有或不是对象时给空对象（调用方不必层层判空）。 */
    fun dataOf(frame: JsonObject): JsonObject = frame[DATA] as? JsonObject ?: JsonObject(emptyMap())

    /**
     * [RESYNC_REQUIRED] 帧里「这次丢了几条」。
     *
     * 给 `null` 而不是 0 表示**这个字段不可信/缺失**，因为上报的数字会被写进诊断日志，
     * 用 0 会让人以为「丢了零条却还让我重取」，反而更难查。
     */
    fun resyncDroppedOf(frame: JsonObject): Int? =
        (dataOf(frame)["dropped"] as? JsonPrimitive)?.intOrNull

    /** [RESYNC_REQUIRED] 帧里丢帧的原因；桥目前只发 `backpressure`。 */
    fun resyncReasonOf(frame: JsonObject): String? =
        (dataOf(frame)["reason"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    /**
     * 把 `event` 帧的 `data` 解成 [WorkspaceEvent]。
     *
     * 桥侧 `ws-handler.ts:203-208` 的构造是 `{kind:'event', data: event}`，`data` **直接就是**
     * 那条 `WorkspaceEvent`（`{id, type, time, data}`），与 `/api/v1/events` 推的载荷同构 ——
     * 所以同一套 listener 能同时吃两条通道。
     *
     * 缺 `id` / `type` 这类必填字段时抛异常，由调用方决定丢弃（不在这里吞掉：
     * 静默丢事件比报错更难查，PITFALLS「delta 帧缺 turn/step 被静默丢弃」就是同类教训）。
     */
    fun decodeEvent(data: JsonObject): WorkspaceEvent =
        json.decodeFromJsonElement(WorkspaceEvent.serializer(), data)

    /**
     * 构造一条带 `requestId` 的客户端请求帧。
     *
     * `requestId` 放在**顶层** —— 桥侧所有处理器都读 `frame.requestId`（见 `ws-goals.ts` 的
     * `echoRequestId`、`ws-files.ts:60`），放进 `data` 里服务端看不到，请求会永远等不到应答。
     */
    fun request(type: String, requestId: String, payload: JsonObject = JsonObject(emptyMap())): JsonObject =
        buildJsonObject {
            put(TYPE, type)
            put(REQUEST_ID, requestId)
            payload.forEach { (key, value) -> put(key, value) }
        }

    /** 构造一条**不带** `requestId` 的客户端帧（`ping` / `unsubscribe` 这类不需要应答的）。 */
    fun command(type: String, payload: JsonObject = JsonObject(emptyMap())): JsonObject =
        buildJsonObject {
            put(TYPE, type)
            payload.forEach { (key, value) -> put(key, value) }
        }

    /**
     * 构造一条 `kernel-call` 帧。
     *
     * `callId` 由调用方自铸并**原样回显**在结果帧里；重试必须复用同一个 callId（24h 幂等回放）。
     * `args` 为空或 null 时**省略**字段 —— asyncapi 明确写「无参数时省略」，桥按描述符的
     * 键集校验，多发一个空对象在无参端点上也可能被当成「多了一个键」。
     */
    fun kernelCall(callId: String, endpoint: String, args: JsonObject? = null): JsonObject =
        buildJsonObject {
            put(TYPE, WsClientFrame.KERNEL_CALL)
            put("callId", callId)
            put("endpoint", endpoint)
            if (args != null && args.isNotEmpty()) put("args", args)
        }

    /** 构造一条 `kernel-call-cancel` 帧（best-effort；已结算的调用照样会回结果帧）。 */
    fun kernelCallCancel(callId: String): JsonObject =
        buildJsonObject {
            put(TYPE, WsClientFrame.KERNEL_CALL_CANCEL)
            put("callId", callId)
        }

    /**
     * 解析 `hello`。**字段缺失一律给默认值，不返回 null** ——
     *
     * `hello` 到达这件事本身已经证明「这条通道存在且已鉴权」，因为桥是**先鉴权再发 hello**
     * （`ws-handler.ts:457-476` 只在 `acceptSocket` 之后调用）。所以哪怕载荷被某个中间层
     * 截断，我们也该按「连上了、能力未知」处理（能力空集 → 缺位 → `WsDegraded`），
     * 而不是把一条健康的连接判成失败再触发重连风暴。
     *
     * 只有 `data` 根本不是对象时返回 null —— 那种情况协议已经不对了，继续用会更糟。
     */
    fun parseHello(frame: JsonObject): HelloFrame? {
        val data = frame[DATA] as? JsonObject ?: return null
        return HelloFrame(
            protocol = data.intOr("protocol") ?: 0,
            capabilities = data.stringSet("capabilities"),
            authenticated = (data["authenticated"] as? JsonPrimitive)?.booleanOrNull ?: false,
            deviceId = (data["device"] as? JsonObject)?.let { (it["id"] as? JsonPrimitive)?.contentOrNull },
            deviceName = (data["device"] as? JsonObject)?.let { (it["name"] as? JsonPrimitive)?.contentOrNull },
            scopes = (data["device"] as? JsonObject)?.stringList("scopes") ?: emptyList(),
            rootIds = (data["device"] as? JsonObject)?.stringList("rootIds") ?: emptyList(),
            port = data.intOr("port"),
            clients = data.intOr("clients"),
        )
    }

    /**
     * 解析 `kernel-call-result`。
     *
     * 严格要求 `kind` 匹配、`data` 是对象、`callId` 非空、`ok` 是布尔；
     * `error` 若出现必须是带非空 `code` 的对象（形状不对整帧返回 null，由调用方按超时处理）。
     * 未知字段一律忽略 —— 桥侧元素形状未冻结。
     */
    fun parseKernelCallResult(frame: JsonObject): KernelCallResultFrame? {
        if (kindOf(frame) != WsServerFrame.KERNEL_CALL_RESULT) return null
        val data = frame[DATA] as? JsonObject ?: return null
        val callId = data.stringOrNull("callId") ?: return null
        val ok = (data["ok"] as? JsonPrimitive)?.booleanOrNull ?: return null
        val error = when (val element = data["error"]) {
            null, is JsonNull -> null
            is JsonObject -> kernelCallErrorOf(element) ?: return null
            else -> return null
        }
        return KernelCallResultFrame(callId = callId, ok = ok, value = data["value"], error = error)
    }

    /**
     * 解析 `kernel-endpoints` 目录快照。
     *
     * `data.endpoints` 必须是数组；元素逐个容错（非对象或 `endpoint` 为空的跳过），
     * `scope`/`summary` 缺省空串、`stream` 缺省 false、`wires` 过滤空串。
     * 未知字段与未知端点一律忽略，不报错。
     */
    fun parseKernelEndpoints(frame: JsonObject): List<KernelEndpoint>? {
        if (kindOf(frame) != WsServerFrame.KERNEL_ENDPOINTS) return null
        val data = frame[DATA] as? JsonObject ?: return null
        val entries = data["endpoints"] as? JsonArray ?: return null
        return entries.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val endpoint = entry.stringOrNull("endpoint") ?: return@mapNotNull null
            KernelEndpoint(
                endpoint = endpoint,
                scope = entry.stringOrNull("scope") ?: "",
                stream = (entry["stream"] as? JsonPrimitive)?.booleanOrNull ?: false,
                wires = entry.stringList("wires"),
                summary = entry.stringOrNull("summary") ?: "",
            )
        }
    }

    private fun kernelCallErrorOf(error: JsonObject): KernelCallError? {
        val code = error.stringOrNull("code") ?: return null
        return KernelCallError(code = code, message = error.stringOrNull("message") ?: "")
    }

    private fun JsonObject.intOr(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    private fun JsonObject.stringSet(key: String): Set<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    const val TYPE: String = "type"
    const val KIND: String = "kind"
    const val DATA: String = "data"
    const val REQUEST_ID: String = "requestId"
}
