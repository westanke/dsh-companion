package io.github.westanke.dshcompanion.data

import io.github.westanke.dshcompanion.debug.DiagLog
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.source

class DshClient(
    endpoint: String,
    private val token: String? = null,
    private val http: OkHttpClient = defaultHttpClient,
) {
    val endpoint: String = normalizeEndpoint(endpoint)
    private val base: HttpUrl = (this.endpoint.trimEnd('/') + "/").toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }

    fun health(): HealthView = get("healthz", HealthView.serializer(), authenticated = false)

    fun pair(code: String, deviceName: String): PairingResult = post(
        "pairings/exchange",
        PairingRequest(code.trim(), deviceName.trim()),
        PairingRequest.serializer(),
        PairingResult.serializer(),
        authenticated = false,
    )

    fun currentDevice(): DeviceView = get("devices/self", DeviceView.serializer())

    fun roots(): List<RootView> = get("roots", RootList.serializer()).items

    fun entries(rootId: String, path: String = "", cursor: String? = null): DirectoryPage {
        val url = pathUrl("roots", rootId, "entries").newBuilder()
            .addQueryParameter("path", path)
            .apply { if (cursor != null) addQueryParameter("cursor", cursor) }
            .build()
        return executeJson(Request.Builder().url(url).get(), DirectoryPage.serializer())
    }

    fun readFile(rootId: String, path: String): FileContent {
        val url = pathUrl("roots", rootId, "content").newBuilder().addQueryParameter("path", path).build()
        execute(Request.Builder().url(url).get()).use { response ->
            ensureSuccess(response)
            return FileContent(
                response.body?.bytes() ?: ByteArray(0),
                response.header("ETag").orEmpty(),
                response.header("Content-Type"),
            )
        }
    }

    fun downloadFile(rootId: String, path: String, output: OutputStream) {
        val url = pathUrl("roots", rootId, "content").newBuilder().addQueryParameter("path", path).build()
        execute(Request.Builder().url(url).get()).use { response ->
            ensureSuccess(response)
            response.body?.byteStream()?.use { input -> output.use { destination -> input.copyTo(destination) } }
        }
    }

    fun writeFile(rootId: String, path: String, bytes: ByteArray, etag: String? = null): String {
        return writeFile(rootId, path, bytes.toRequestBody(OCTET_STREAM), etag)
    }

    fun writeFile(
        rootId: String,
        path: String,
        length: Long,
        openInput: () -> InputStream,
        etag: String? = null,
    ): String = writeFile(rootId, path, object : okhttp3.RequestBody() {
        override fun contentType() = OCTET_STREAM
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            openInput().source().use(sink::writeAll)
        }
    }, etag)

    private fun writeFile(rootId: String, path: String, body: okhttp3.RequestBody, etag: String?): String {
        val url = pathUrl("roots", rootId, "content").newBuilder().addQueryParameter("path", path).build()
        val request = Request.Builder().url(url).put(body).apply {
            if (etag == null) header("If-None-Match", "*") else header("If-Match", etag)
        }
        execute(request).use { response ->
            ensureSuccess(response)
            return response.header("ETag").orEmpty()
        }
    }

    fun createEntry(rootId: String, path: String, directory: Boolean) {
        postUnit(
            pathUrl("roots", rootId, "entries"),
            CreateEntryRequest(path, if (directory) "directory" else "file"),
            CreateEntryRequest.serializer(),
        )
    }

    fun moveEntry(rootId: String, path: String, destinationPath: String) {
        patchUnit(pathUrl("roots", rootId, "entries"), MoveEntryRequest(path, destinationPath), MoveEntryRequest.serializer())
    }

    fun trashEntry(rootId: String, path: String) {
        val url = pathUrl("roots", rootId, "entries").newBuilder().addQueryParameter("path", path).build()
        execute(Request.Builder().url(url).delete()).use(::ensureSuccess)
    }

    fun trash(): List<TrashEntry> = get("trash", TrashList.serializer()).items

    fun restoreTrash(id: String) {
        execute(Request.Builder().url(pathUrl("trash", id, "restore")).post(EMPTY_BODY)).use(::ensureSuccess)
    }

    /**
     * 会话列表；`archived = true` 时**只列已归档**的那批。
     *
     * 注意老 bridge 会**忽略**这个查询参数、照样返回未归档列表（不是报错），
     * 所以调用方必须先确认能力位 `session-archive-list-v1`（见 `data/BridgeCapabilities.kt`），
     * 否则会把「服务端不支持」读成「一条归档都没有」。
     */
    fun sessions(archived: Boolean = false): List<ChatSession> =
        get(if (archived) "chat/sessions?archived=true" else "chat/sessions", SessionList.serializer()).items

    fun chatWorkspaces(): List<ChatWorkspace> = get("chat/workspaces", ChatWorkspaceList.serializer()).items

    fun createChatWorkspace(rootId: String, path: String): ChatWorkspace = post(
        "chat/workspaces",
        CreateWorkspaceRequest(rootId, path),
        CreateWorkspaceRequest.serializer(),
        CreatedWorkspaceEnvelope.serializer(),
    ).workspace

    fun renameChatWorkspace(workspaceId: String, title: String): ChatWorkspace = patch(
        pathUrl("chat", "workspaces", workspaceId),
        RenameRequest(title),
        RenameRequest.serializer(),
        CreatedWorkspaceEnvelope.serializer(),
    ).workspace

    fun deleteChatWorkspace(workspaceId: String) {
        execute(Request.Builder().url(pathUrl("chat", "workspaces", workspaceId)).delete()).use(::ensureSuccess)
    }

    fun agentPresets(): List<AgentPreset> = get("chat/agent-presets", AgentPresetList.serializer()).items

/**
 * 权限档位**候选项**清单（`GET /chat/permission-presets`，bridge ≥ 2.4.2）。
 *
 * 为什么必须单独取、不能只读会话投影：内核把预设清单放在一个独立的远程服务
 * （`permissionPresets.catalog()`）上，而投影里那条 `permissions` wire 视图**只给
 * `currentValue`**（`wire.view: state => ({ currentValue })`）——
 * 拿得到当前档位、列不出候选。v2.2.44 之前 App 读 `options[]`，于是整条恒为 null、
 * 权限 Pill 与 ⋯ 菜单的权限项在真机上**从不渲染**（PARITY 上的 `done` 是假的）。
 *
 * 老 bridge 没有这条路由（404 `ROUTE_NOT_FOUND`），调用方必须容忍失败继续跑。
 */
fun permissionPresets(): PermissionCatalog = get("chat/permission-presets", PermissionCatalog.serializer())

    /**
     * 某会话可见的后台任务清单（`GET /chat/sessions/{sessionId}/jobs`，bridge ≥ 2.4.3）。
     *
     * 返回的是**完整清单**：服务端只读内核 roster 流的第一帧，内核保证
     * 「Whole-set replacement, so a reconnect's first frame is already the truth」。
     * 所以这里没有分页，也没有游标 —— 拉一次就是全部。
     *
     * 空清单是正常结果（这个会话确实没有任务），不是失败；失败走 [DshApiException]。
     */
    fun sessionJobs(sessionId: String): List<JobView> =
        get(pathUrl("chat", "sessions", sessionId, "jobs"), JobListEnvelope.serializer()).jobs

    /**
     * 读一个后台任务已保留的输出（`GET .../jobs/{jobId}/output`）。
     *
     * [from] 是**绝对字节偏移**：传上一次返回的 [JobOutput.next] 即增量续读，
     * null 从最旧的保留字节开始。任务留存策略可能丢旧字节，丢过就会在
     * [JobOutput.lossy] 上标记 —— 界面必须提示「这不是完整输出」。
     */
    fun jobOutput(sessionId: String, jobId: String, from: Long? = null): JobOutput {
        val builder = pathUrl("chat", "sessions", sessionId, "jobs", jobId, "output").newBuilder()
        if (from != null) builder.addQueryParameter("from", from.toString())
        return get(builder.build(), JobOutput.serializer())
    }

    /**
     * 终止一个后台任务（`POST .../jobs/{jobId}/kill`）。
     *
     * 两种成功语义必须区分：`requested`（已受理）与 `already-finished`
     * （已是终态、无需再杀）。后者要显示「它已经结束了」—— 用户在远程场景下
     * 看到这句话才知道**不用再管了**，与「已终止」不是一回事。
     * 任务不存在或本会话看不到它时服务端返回 404 `JOB_NOT_FOUND`。
     */
    fun killJob(sessionId: String, jobId: String): JobKillResult =
        post(pathUrl("chat", "sessions", sessionId, "jobs", jobId, "kill"), Unit, Unit.serializer(), JobKillResult.serializer())

    fun selectAgentPreset(sessionId: String, agentPreset: String): String = put(
        pathUrl("chat", "sessions", sessionId, "agent-preset"),
        AgentPresetSelection(agentPreset),
        AgentPresetSelection.serializer(),
        SelectedAgentPresetEnvelope.serializer(),
    ).agentPreset

    fun sessionModels(sessionId: String): SessionModels =
        get(pathUrl("chat", "sessions", sessionId, "models"), SessionModels.serializer())

    fun selectModel(sessionId: String, selection: ModelSelection): ModelSelection = put(
        pathUrl("chat", "sessions", sessionId, "model"),
        selection,
        ModelSelection.serializer(),
        SelectedModelEnvelope.serializer(),
    ).selected

    /**
     * 把服务器绝对路径解析成 `rootId` + 相对路径。
     *
     * 返回 null 表示**服务端明确拒绝了这个路径**（`PATH_OUTSIDE_ROOTS` /
     * `PATH_NOT_ABSOLUTE` / `PATH_NOT_FILE` / `PATH_NOT_FOUND`），这在会话里是常态而不是故障：
     * agent 完全可能提到授权根之外的文件。所以这些情况不抛异常，让调用方把「不可打开」
     * 当作正常结果展示。
     *
     * **但其他 404/400 必须抛出去**：服务端插件版本过旧时这个端点根本不存在，会返回
     * `404 ROUTE_NOT_FOUND`。若按状态码笼统地把所有 404 都当成「不在授权根内」，
     * 用户会去怀疑自己的路径，而真正该做的是升级插件。
     */
    fun resolvePath(absolutePath: String): ResolvedPath? {
        val url = resolve("roots/resolve").newBuilder()
            .addQueryParameter("path", absolutePath)
            .build()
        execute(Request.Builder().url(url).get()).use { response ->
            if (response.isSuccessful) {
                return json.decodeFromString(ResolvedPath.serializer(), response.body?.string() ?: "{}")
            }
            val raw = response.body?.string().orEmpty()
            val error = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), raw).error }.getOrNull()
            val code = error?.code ?: "HTTP_ERROR"
            // 留痕：这条路径为什么打不开，必须有据可查（真机上没有 logcat）。
            // 交付物路径带中文时曾出现「服务端能解析、App 却说不在了」，就是靠它定位的。
            DiagLog.w("PATH", "resolve 失败 code=$code path=$absolutePath")
            if (code in UNRESOLVABLE_CODES) return null
            throw DshApiException(response.code, code, error?.message ?: "HTTP ${response.code}")
        }
    }

    fun providerSettings(): ProviderSettings = get("settings/providers", ProviderSettings.serializer())

    fun pluginInventory(): PluginInventory = get("settings/plugins", PluginInventory.serializer())

    /**
     * 带版本检查地拉插件清单（`?checkUpdates=1`）。
     *
     * 与 [pluginInventory] 分开是有意的：版本检查要对每个已装包发一次 registry 请求，
     * 清单会从毫秒级变成好几秒。用户点进「插件」页九成只想看「装了啥、哪个没启用」，
     * 所以默认走轻的那条，只有用户**主动要看更新**时才走这条。
     */
    fun pluginInventoryWithUpdates(): PluginInventory =
        get("settings/plugins?checkUpdates=1", PluginInventory.serializer())

    /**
     * 拉「按插件划分的设置命名空间」（每项含 schema 与当前值）。
     *
     * 响应用 `JsonElement` 承接 schema/value，见 `data/SettingsNamespaces.kt` 顶部：
     * 命名空间是动态的，强类型化就等于把插件名单焊死在 App 里。
     *
     * **桥与 App 分批发版**：老桥没有这条路由时回 404，属于正常降级而不是故障，
     * 调用方（`HarnessNamespacesViewModel.refreshSettingsNamespaces`）自己拦下 404/403，
     * 让入口消失/置灰即可 —— 详情见那里的注释。
     */
    fun settingsNamespaces(): SettingsNamespaceList =
        get("settings/namespaces", SettingsNamespaceList.serializer())

    /**
     * 按路径改一个命名空间；[expectedRevision] 是乐观并发闸门（桥发现文档被别处改过回 409）。
     *
     * 命名空间名走 [pathUrl] 而不是拼字符串：`addPathSegment` 会做 URL 编码，
     * 名字里带点、斜杠、空格都不会把路径拆坏。
     */
    fun updateSettingsNamespace(
        ns: String,
        ops: List<SettingsOp>,
        expectedRevision: Int? = null,
    ): SettingsNamespace = patch(
        pathUrl("settings", "namespaces", ns),
        SettingsNamespaceUpdate(ops, expectedRevision),
        SettingsNamespaceUpdate.serializer(),
        SettingsNamespace.serializer(),
    )

    /**
     * 启用 / 停用一个插件。
     *
     * 服务端只改写 profile 的 `dsh.profile.bundles`（加载清单），**不碰依赖、不装包**，
     * 且写前会备份 profile 的 package.json。返回结果里的 `restartRequired` 恒为 true ——
     * 那份清单只在 DSH 启动时读一次，所以必须提示用户重启才生效。
     */
    fun setPluginLoaded(name: String, loaded: Boolean): PluginToggleResult = patch(
        pathUrl("settings", "plugins"),
        PluginToggleRequest(name, loaded),
        PluginToggleRequest.serializer(),
        PluginToggleResult.serializer(),
    )

    fun updateProvider(providerId: String, patch: ProviderPatch): ProviderSettings = patch(
        pathUrl("settings", "providers", providerId),
        patch,
        ProviderPatch.serializer(),
        ProviderSettings.serializer(),
    )

    fun createCustomProvider(provider: CustomProviderCreate): ProviderSettings = post(
        "settings/providers",
        provider,
        CustomProviderCreate.serializer(),
        ProviderSettings.serializer(),
    )

    fun discoverModels(providerId: String, baseURL: String?, api: String?, apiKey: String?): List<ProviderModel> = post(
        pathUrl("settings", "providers", providerId, "discover"),
        DiscoverModelsRequest(baseURL, api, apiKey),
        DiscoverModelsRequest.serializer(),
        DiscoveredModelsEnvelope.serializer(),
    ).models

    /**
     * 删除供应商（bridge 先 unset 其配置段；expectedRevision 防并发冲突）。
     * 成功返回 204 空体，调用方应自行刷新 [providerSettings]。
     */
    fun deleteProvider(providerId: String, expectedRevision: Int?) {
        val url = pathUrl("settings", "providers", providerId).newBuilder()
            .apply { if (expectedRevision != null) addQueryParameter("expectedRevision", expectedRevision.toString()) }
            .build()
        execute(Request.Builder().url(url).delete()).use(::ensureSuccess)
    }

    /**
     * 恢复供应商的适配器默认模型目录（wire `models: null`）。
     *
     * 不能走 [updateProvider]：kotlinx 默认不编码等于默认值的字段，`models = null`
     * 会被整个省略，服务端无法区分「没改」和「恢复默认」。所以这里手工拼 body。
     */
    fun resetProviderModels(providerId: String, expectedRevision: Int?): ProviderSettings {
        val body = buildString {
            append("{\"models\":null")
            if (expectedRevision != null) append(",\"expectedRevision\":").append(expectedRevision)
            append('}')
        }
        val request = Request.Builder()
            .url(pathUrl("settings", "providers", providerId))
            .patch(body.toRequestBody("application/json".toMediaType()))
        return executeJson(request, ProviderSettings.serializer())
    }

    // `reuseBlank` 是桥 v2.7.0 的可选字段：省略即不编码，老 bridge 收到的请求体逐字节不变。
    fun createSession(workspaceId: String, agentPreset: String? = null, reuseBlank: Boolean? = null): CreatedSession = post(
        "chat/sessions",
        CreateSessionRequest(workspaceId, agentPreset, UUID.randomUUID().toString(), reuseBlank),
        CreateSessionRequest.serializer(),
        CreatedSessionEnvelope.serializer(),
    ).session

    fun renameSession(sessionId: String, title: String): RenamedSession = patch(
        pathUrl("chat", "sessions", sessionId),
        RenameRequest(title),
        RenameRequest.serializer(),
        RenamedSession.serializer(),
    )

    fun forkSession(sessionId: String, atSeq: Int? = null): String = post(
        pathUrl("chat", "sessions", sessionId, "fork"),
        ForkSessionRequest(atSeq),
        ForkSessionRequest.serializer(),
        ForkedSessionEnvelope.serializer(),
    ).sessionId

    fun archiveSession(sessionId: String) {
        execute(Request.Builder().url(pathUrl("chat", "sessions", sessionId, "archive")).post(EMPTY_BODY)).use(::ensureSuccess)
    }

    fun history(sessionId: String, beforeSeq: Int? = null, maxMessages: Int? = null): ChatHistory {
        val url = pathUrl("chat", "sessions", sessionId, "messages").newBuilder().apply {
            beforeSeq?.let { addQueryParameter("beforeSeq", it.toString()) }
            maxMessages?.let { addQueryParameter("maxMessages", it.toString()) }
        }.build()
        return get(url, ChatHistory.serializer())
    }

    fun pendingApprovals(sessionId: String): List<PendingApproval> =
        get(pathUrl("chat", "sessions", sessionId, "approvals"), PendingApprovalList.serializer()).items

    fun decideApproval(sessionId: String, approvalId: String, allowOnce: Boolean) {
        postUnit(
            pathUrl("chat", "sessions", sessionId, "approvals", approvalId, "decision"),
            ApprovalDecision(if (allowOnce) "allowed-once" else "rejected"),
            ApprovalDecision.serializer(),
        )
    }

    /**
     * 当前会话里待回答的问题（`ask_user_question`）。
     *
     * ⚠️ 服务端在这条路上会走 `resolveAgent(..., resume)` —— 对**冷**会话调它会把那边的
     * Agent 拉活。所以调用方**只能**传当前打开的那个会话，绝不能拿它去遍历会话列表
     * （见 [io.github.westanke.dshcompanion.HarnessViewModel.refreshPendingQuestions]）。
     */
    fun pendingQuestions(sessionId: String): List<PendingQuestionSet> =
        get(pathUrl("chat", "sessions", sessionId, "questions"), QuestionSetList.serializer()).items

    /**
     * 回答一组问题。
     *
     * 插件在「答复已经在队列里」（`QUESTION_REPLY_QUEUED`）与「这个问题已经不作答」
     * （`QUESTION_NOT_PENDING`）两种情况下回 409；调用方要把这两个 code 翻成人话。
     */
    fun answerQuestions(sessionId: String, callId: String, answers: List<QuestionAnswer>) {
        postUnit(
            pathUrl("chat", "sessions", sessionId, "questions", callId, "answer"),
            QuestionAnswerRequest(answers),
            QuestionAnswerRequest.serializer(),
        )
    }

    fun sessionCommands(sessionId: String): List<CommandDescriptor> =
        get(pathUrl("chat", "sessions", sessionId, "commands"), CommandDescriptorList.serializer()).items

    fun executeCommand(sessionId: String, line: String): CommandExecution = post(
        pathUrl("chat", "sessions", sessionId, "commands"),
        CommandRequest(line),
        CommandRequest.serializer(),
        CommandExecutionEnvelope.serializer(),
    ).execution

    /**
     * 发一条消息，内容是一个 part 列表（文本 / 内联图片 / 上传后的文件）。
     *
     * 请求体两种形状由 [ChatContentParts.messageBody] 决定：**纯文本走旧的 `{"text":...}`**，
     * 带附件才用 `{"content":[...]}`。服务端两种都吃，保持旧形状是为了不强迫老插件升级。
     */
    fun sendMessage(sessionId: String, parts: List<PromptPart>, steer: Boolean) {
        val body = ChatContentParts.messageBody(
            parts = parts,
            mode = if (steer) "steer" else "queue",
            clientTimeZone = TimeZone.getDefault().id,
            clientRequestId = UUID.randomUUID().toString(),
        )
        val url = pathUrl("chat", "sessions", sessionId, "messages")
        execute(Request.Builder().url(url).post(body.toString().toRequestBody(JSON_MEDIA))).use(::ensureSuccess)
    }

    /** 纯文本重载：内部转成单个 text part，其余行为与线上格式都不变。 */
    fun sendMessage(sessionId: String, text: String, steer: Boolean) =
        sendMessage(sessionId, listOf(PromptPart.TextPart(text)), steer)

    /**
     * 上传一个文件附件，换回消息里引用的 `receiptId`。
     *
     * 与「文件」页的 `writeFile` 是两套东西：那个是往工作区写文件，这个是给**这一条消息**
     * 换一张临时凭证，文件本身不进工作区。图片不需要走这里 —— 直接内联在消息里。
     */
    fun uploadAttachment(sessionId: String, bytes: ByteArray, name: String): UploadedAttachment {
        val payload = ChatContentParts.uploadPayload(bytes, name)
        val url = pathUrl("chat", "sessions", sessionId, "attachments")
        return executeJson(
            Request.Builder().url(url).post(payload.toString().toRequestBody(JSON_MEDIA)),
            UploadedAttachment.serializer(),
        )
    }

    fun cancel(sessionId: String) {
        execute(Request.Builder().url(pathUrl("chat", "runs", sessionId, "cancel")).post(EMPTY_BODY)).use(::ensureSuccess)
    }

    /**
     * 取回会话历史里某个图片附件的字节。
     *
     * 为什么历史图片需要这一步：发送时图片是 **base64 内联**在消息里的，但存进历史后被
     * 归一化成 `{type:'image', attachment:{attachmentId:'sha256:…'}}` —— 字节搬去了
     * 服务端的附件存储。所以「显示一张历史图片」永远是两步：先读事件拿 id，再来这里取字节。
     *
     * `attachmentId` 形如 `sha256:<hex>`，**含冒号**；`addPathSegment` 会把它编码成
     * `sha256%3A…`，这正是服务端 `decodeURIComponent` 期待的形式。
     *
     * ### 为什么必须带上 [reference] 的四项元数据
     *
     * 服务端拿字节时，内核 `readImage` 会做一次**完整性校验**：把引用里的
     * `mediaType` / `bytes` / `width` / `height` 与**实测元数据**逐项比对，
     * 任何一项对不上就抛 `ATTACHMENT_CORRUPT`。
     *
     * 早先这里只传 `attachmentId`，那四项全缺 —— 结果是**每一张历史图都必然校验失败**：
     * 字节读到了却被拒之门外。症状是「附件存储里明明有数据，代码看着全对，
     * 图片就是永远显示不出来」。官方那条路（`SessionController.attachment()`）
     * 同样是传完整引用。
     *
     * 四项缺失时按「不传」处理（而不是补 0 / 空串）：补出来的假值同样过不了校验，
     * 还会让服务端的报错更难懂。
     */
    /**
     * 取回会话历史里某个图片附件的字节。
     *
     * 为什么历史图片需要这一步：发送时图片是 **base64 内联**在消息里的，但存进历史后被
     * 归一化成 `{type:'image', attachment:{attachmentId:'sha256:…'}}` —— 字节搬去了内核。
     * 所以「显示一张历史图片」永远是两步：先读事件拿 id，再来这里取字节。
     *
     * `attachmentId` 形如 `sha256:<hex>`，**含冒号**；`addPathSegment` 会把它编码成
     * `sha256%3A…`，这正是服务端 `decodeURIComponent` 期待的形式。
     *
     * ### 为什么必须带 [sessionId]
     *
     * 服务端走内核自己的 `sessionController.attachment({sessionId, attachmentId})`：
     * 它先读这个会话的事件，确认该会话引用了这张图、并取出**真实的附件引用**
     * （含 mediaType/bytes/width/height），再据此做完整性校验。
     *
     * 这是踩了两轮坑后的正路：
     * - 第一轮只传 id、让插件自己调 `ctx.attachments` → 完整性校验缺四项元数据，
     *   每张图都抛 `ATTACHMENT_CORRUPT`（「字节读到了，却被拒之门外」）；
     * - 第二轮改成客户端把四项带上来 → 真机诊断发现这条 profile 里
     *   `ctx.attachments` 注入属性**根本不存在**（MISSING），路走不通。
     *
     * 内核手里本来就有真实 ref，所以正确做法是**让内核自己取**，
     * 客户端只需说明「我在看哪个会话里的哪张图」。
     */
    fun readAttachment(sessionId: String, attachmentId: String): ByteArray {
        val url = pathUrl("attachments", attachmentId).newBuilder()
            .addQueryParameter("sessionId", sessionId)
            .build()
        execute(Request.Builder().url(url).get()).use { response ->
            ensureSuccess(response)
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    fun events(listener: (WorkspaceEvent) -> Unit, connection: (Boolean) -> Unit): Closeable {
        val request = authenticated(Request.Builder().url(eventsUrl())).build()
        return ReconnectingEvents(http, request, json, listener, connection).apply { start() }
    }

    internal fun eventsUrl(): HttpUrl = base.newBuilder().addPathSegment("events").build()

    private fun <T> get(relative: String, serializer: KSerializer<T>, authenticated: Boolean = true): T =
        get(resolve(relative), serializer, authenticated)

    private fun <T> get(url: HttpUrl, serializer: KSerializer<T>, authenticated: Boolean = true): T =
        executeJson(Request.Builder().url(url).get(), serializer, authenticated)

    private fun <B, T> post(
        relative: String,
        body: B,
        bodySerializer: KSerializer<B>,
        responseSerializer: KSerializer<T>,
        authenticated: Boolean = true,
    ): T {
        val requestBody = json.encodeToString(bodySerializer, body).toRequestBody(JSON_MEDIA)
        return executeJson(Request.Builder().url(resolve(relative)).post(requestBody), responseSerializer, authenticated)
    }

    private fun <B, T> post(
        url: HttpUrl,
        body: B,
        bodySerializer: KSerializer<B>,
        responseSerializer: KSerializer<T>,
    ): T {
        val requestBody = json.encodeToString(bodySerializer, body).toRequestBody(JSON_MEDIA)
        return executeJson(Request.Builder().url(url).post(requestBody), responseSerializer)
    }

    private fun <B, T> put(
        url: HttpUrl,
        body: B,
        bodySerializer: KSerializer<B>,
        responseSerializer: KSerializer<T>,
    ): T {
        val requestBody = json.encodeToString(bodySerializer, body).toRequestBody(JSON_MEDIA)
        return executeJson(Request.Builder().url(url).put(requestBody), responseSerializer)
    }

    private fun <B, T> patch(
        url: HttpUrl,
        body: B,
        bodySerializer: KSerializer<B>,
        responseSerializer: KSerializer<T>,
    ): T {
        val requestBody = json.encodeToString(bodySerializer, body).toRequestBody(JSON_MEDIA)
        return executeJson(Request.Builder().url(url).patch(requestBody), responseSerializer)
    }

    private fun <B> postUnit(url: HttpUrl, body: B, serializer: KSerializer<B>) {
        val requestBody = json.encodeToString(serializer, body).toRequestBody(JSON_MEDIA)
        execute(Request.Builder().url(url).post(requestBody)).use(::ensureSuccess)
    }

    private fun <B> patchUnit(url: HttpUrl, body: B, serializer: KSerializer<B>) {
        val requestBody = json.encodeToString(serializer, body).toRequestBody(JSON_MEDIA)
        execute(Request.Builder().url(url).patch(requestBody)).use(::ensureSuccess)
    }

    private fun <T> executeJson(
        builder: Request.Builder,
        serializer: KSerializer<T>,
        authenticated: Boolean = true,
    ): T {
        execute(builder, authenticated).use { response ->
            ensureSuccess(response)
            return json.decodeFromString(serializer, response.body?.string() ?: "{}")
        }
    }

    private fun execute(builder: Request.Builder, authenticated: Boolean = true): Response =
        http.newCall(if (authenticated) authenticated(builder).build() else builder.build()).execute()

    private fun authenticated(builder: Request.Builder): Request.Builder =
        builder
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${requireNotNull(token)}")
            // 设备身份随每个请求出海：桥在认证路径上据此把配对时的占位名
            // 「Mobile device」换成「Android · Pixel 9」。**必须显式上报** ——
            // OkHttp 的默认 UA 是 `okhttp/x.y.z`，里面既没有平台也没有机型，
            // 桥的 UA 兜底对本 App 完全失效，不上报就永远只有占位名。
            // Build.MODEL 可能含空格（`Pixel 9`），HTTP 头允许，桥只做清洗与截断。
            .header("X-Dsh-Device", "android/${Build.MODEL}")
            // 界面语言随每个请求出海：服务端的图片交接说明要说**用户看得懂**的语言
            // （拿不到就按中文，那是插件原本的口径）。App 的语言由设置页经
            // AppCompatDelegate 管理，这里如实转达，不在客户端另做翻译。
            .header("Accept-Language", interfaceLanguageTag())

    /** 当前界面语言（BCP-47，如 `zh-CN` / `en`）；从未设置过时按 `zh-CN`。 */
    private fun interfaceLanguageTag(): String =
        AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank { "zh-CN" }

    private fun ensureSuccess(response: Response) {
        if (response.isSuccessful) return
        val raw = response.body?.string().orEmpty()
        val error = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), raw).error }.getOrNull()
        throw DshApiException(response.code, error?.code ?: "HTTP_ERROR", error?.message ?: "HTTP ${response.code}")
    }

    private fun resolve(relative: String): HttpUrl = base.resolve(relative)
        ?: throw IllegalArgumentException("Invalid API path: $relative")

    private fun pathUrl(vararg segments: String): HttpUrl = base.newBuilder().apply {
        segments.forEach(::addPathSegment)
    }.build()

    companion object {
        /**
         * 「这个路径打不开」的权威错误码集合 —— 都属于正常结果，不是故障。
         *
         * 刻意**不含**其他 404：`ROUTE_NOT_FOUND` 表示服务端压根没有这个端点（通常是插件
         * 版本过旧）。把它一并当成「不在授权根内」会把用户引向错误的方向 ——
         * 他该做的是升级插件，而不是反复检查自己的路径。
         *
         * **2026-10-19 收紧**：原先这里连 `PATH_NOT_FOUND` / `PATH_NOT_FILE` 也算「打不开的
         * 正常结果」，于是「文件不存在」会被显示成「不在授权根内」——
         * 用户按那句话去检查授权根，而真正该做的是看看路径是不是变了。
         * 现在只留真正属于「这个根管不了它」的码，其余一律抛异常，
         * 让 `data/ErrorMessages.kt` 的错误码表给出**准确**文案
         * （`PATH_NOT_FOUND` →「找不到这个路径，可能已经被删了」）。
         */
        private val UNRESOLVABLE_CODES = setOf(
            "PATH_OUTSIDE_ROOTS",
        )

        private val JSON_MEDIA = "application/json".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(OCTET_STREAM)
        /**
         * 全 App 唯一的共享 OkHttp 客户端。
         *
         * **为什么是 `internal` 而不是 `private`**：`AGENTS.md` §4.1 立了「`DshClient` 是唯一网络
         * 边界」，而 `ArchitectureGuardTest` 把这条变成会红的断言 —— 它只认 `data/DshClient.kt`
         * 里的 `OkHttpClient` 构造点。应用内更新检查（`data/UpdateChecker.kt`）要访问发布 API，
         * 必须复用这个客户端；若在 `UpdateChecker` 里再建一个，门禁直接红。
         * 复用同一个实例也意味着连接池、超时、ping 策略只有一处定义。
         */
        internal val defaultHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        fun normalizeEndpoint(value: String): String {
            val input = value.trim().trimEnd('/')
            require(input.startsWith("http://") || input.startsWith("https://")) {
                "Endpoint must start with http:// or https://"
            }
            val url = input.toHttpUrl()
            return if (url.encodedPath.trimEnd('/').endsWith("/api/v1")) input else "$input/api/v1"
        }
    }
}

private class ReconnectingEvents(
    private val http: OkHttpClient,
    private val request: Request,
    private val json: Json,
    private val listener: (WorkspaceEvent) -> Unit,
    private val connection: (Boolean) -> Unit,
) : Closeable {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dsh-events").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    @Volatile private var socket: WebSocket? = null
    private var retries = 0

    fun start() = connect()

    private fun connect() {
        if (closed) return
        val terminal = AtomicBoolean(false)
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retries = 0
                connection(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { json.decodeFromString(WorkspaceEvent.serializer(), text) }.onSuccess(listener)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reconnectOnce(terminal)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = reconnectOnce(terminal)
        })
    }

    private fun reconnectOnce(terminal: AtomicBoolean) {
        if (!terminal.compareAndSet(false, true)) return
        connection(false)
        if (closed) return
        val delay = (1L shl retries.coerceAtMost(5)).coerceAtMost(30L)
        retries += 1
        executor.schedule(::connect, delay, TimeUnit.SECONDS)
    }

    override fun close() {
        closed = true
        connection(false)
        socket?.close(1000, "App closed")
        socket = null
        executor.shutdownNow()
    }

}


@Serializable private data class RootList(val items: List<RootView>)
@Serializable private data class TrashList(val items: List<TrashEntry>)
@Serializable private data class SessionList(val items: List<ChatSession>)
@Serializable private data class PendingApprovalList(val items: List<PendingApproval>)
@Serializable private data class QuestionSetList(val items: List<PendingQuestionSet>)
@Serializable private data class ApprovalDecision(val outcome: String)
@Serializable private data class CommandDescriptorList(val items: List<CommandDescriptor>)
@Serializable private data class CommandRequest(val line: String)
@Serializable private data class CommandExecutionEnvelope(val execution: CommandExecution)
@Serializable private data class ChatWorkspaceList(val items: List<ChatWorkspace>)
@Serializable private data class CreatedWorkspaceEnvelope(val workspace: ChatWorkspace)
@Serializable private data class RenameRequest(val title: String)
@Serializable private data class ForkSessionRequest(val atSeq: Int? = null)
@Serializable private data class ForkedSessionEnvelope(val sessionId: String)
@Serializable private data class AgentPresetList(val items: List<AgentPreset>)
@Serializable private data class AgentPresetSelection(val agentPreset: String)
@Serializable private data class SelectedAgentPresetEnvelope(val agentPreset: String)
@Serializable private data class SelectedModelEnvelope(val selected: ModelSelection)
@Serializable private data class DiscoveredModelsEnvelope(val models: List<ProviderModel>)
@Serializable private data class DiscoverModelsRequest(
    val baseURL: String? = null,
    val api: String? = null,
    val apiKey: String? = null,
)
@Serializable private data class PairingRequest(val code: String, val deviceName: String)
@Serializable private data class CreateEntryRequest(val path: String, val kind: String)
@Serializable private data class MoveEntryRequest(val path: String, val destinationPath: String)
@Serializable private data class CreateWorkspaceRequest(val rootId: String, val path: String)
@Serializable private data class CreateSessionRequest(
    val workspaceId: String,
    val agentPreset: String? = null,
    val clientRequestId: String,
    val reuseBlank: Boolean? = null,
)
@Serializable private data class CreatedSessionEnvelope(val session: CreatedSession)
@Serializable private data class ErrorEnvelope(val error: ApiErrorBody)
@Serializable private data class ApiErrorBody(val code: String, val message: String, val requestId: String? = null)

