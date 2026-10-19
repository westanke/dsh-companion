package io.github.westanke.dshcompanion
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.westanke.dshcompanion.debug.DiagLog
import io.github.westanke.dshcompanion.data.QUESTION_STATE_CONTINUED
import io.github.westanke.dshcompanion.data.ChatContentParts
import io.github.westanke.dshcompanion.data.ChatHistory
import io.github.westanke.dshcompanion.data.ChatSession
import io.github.westanke.dshcompanion.data.ChatWorkspace
import io.github.westanke.dshcompanion.data.CommandDescriptor
import io.github.westanke.dshcompanion.data.CustomProviderCreate
import io.github.westanke.dshcompanion.data.localizedErrorRes
import io.github.westanke.dshcompanion.data.DeviceView
import io.github.westanke.dshcompanion.data.displayItems
import io.github.westanke.dshcompanion.data.retryableUserText
import io.github.westanke.dshcompanion.data.supportsArchiveListing
import io.github.westanke.dshcompanion.data.DirectoryPage
import io.github.westanke.dshcompanion.data.DshApiException
import io.github.westanke.dshcompanion.data.DshClient
import io.github.westanke.dshcompanion.data.DshConnectionException
import io.github.westanke.dshcompanion.data.FileEntry
import io.github.westanke.dshcompanion.data.handoffPathOf
import io.github.westanke.dshcompanion.data.LiveChatState
import io.github.westanke.dshcompanion.data.RootView
import io.github.westanke.dshcompanion.data.AgentPreset
import io.github.westanke.dshcompanion.data.ModelSelection
import io.github.westanke.dshcompanion.data.PendingApproval
import io.github.westanke.dshcompanion.data.PendingQuestionSet
import io.github.westanke.dshcompanion.data.PluginInventory
import io.github.westanke.dshcompanion.data.ProviderModel
import io.github.westanke.dshcompanion.data.ProviderPatch
import io.github.westanke.dshcompanion.data.ProviderSettings
import io.github.westanke.dshcompanion.data.PromptPart
import io.github.westanke.dshcompanion.data.QuestionAnswer
import io.github.westanke.dshcompanion.data.ResolvedPath
import io.github.westanke.dshcompanion.data.SessionModels
import io.github.westanke.dshcompanion.data.ActiveConnection
import io.github.westanke.dshcompanion.data.AppPreferences
import io.github.westanke.dshcompanion.data.BusySendMode
import io.github.westanke.dshcompanion.data.ComposerSendPolicy
import io.github.westanke.dshcompanion.data.ConnectionCoordinator
import io.github.westanke.dshcompanion.data.ConnectionShare
import io.github.westanke.dshcompanion.data.Credential
import io.github.westanke.dshcompanion.data.CredentialKind
import io.github.westanke.dshcompanion.data.Endpoint
import io.github.westanke.dshcompanion.data.EndpointKind
import io.github.westanke.dshcompanion.data.EndpointProbe
import io.github.westanke.dshcompanion.data.Host
import io.github.westanke.dshcompanion.data.HostStore
import io.github.westanke.dshcompanion.data.KeystoreSecretBox
import io.github.westanke.dshcompanion.data.LegacyConnectionMigration
import io.github.westanke.dshcompanion.data.SharedPreferencesKeyValueStore
import io.github.westanke.dshcompanion.data.TrashEntry
import io.github.westanke.dshcompanion.data.WorkspaceEvent
import io.github.westanke.dshcompanion.data.chatDelta
import io.github.westanke.dshcompanion.data.chatTurnFailure
import io.github.westanke.dshcompanion.data.PreviewKind
import io.github.westanke.dshcompanion.data.binaryPreviewByteLimit
import io.github.westanke.dshcompanion.data.previewKind
import io.github.westanke.dshcompanion.data.previewKindOf
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 「会话」域：会话的增删改查、工作区管理、发送消息、会话文件面板与会话模型选择。
 *
 * 从 `HarnessViewModel.kt`（2277 行）拆出，成员函数改成 **[HarnessViewModel] 的扩展函数**
 * —— 调用点写法完全不变。
 *
 * 三个最容易踩的地方：
 *
 * 1. **换会话要把问答状态与 `@` 补全清干净**（`selectSession`）：残留的草稿会张冠李戴，
 *    残留的文件列表会让用户在切过去的头几百毫秒里发出一条指向错误路径的消息；
 * 2. **防重复提交靠同步清空，不靠禁用按钮**（`sendMessage`）—— `launchOperation` 会切到 IO，
 *    双击的第二次点击可能抢在它前面读到同一批附件，于是同一条消息（连同同一张图）发两遍；
 * 3. **发送失败要把文字也还回去**（不只是附件）：附件能还而文字不能，本身就不讲道理。
 *
 * 会话文件面板（`openSessionFile`）走两步：先用服务端的 `roots/resolve` 把绝对路径换成
 * 「授权根 + 相对路径」（客户端做不了 —— `/roots` 刻意不返回根的绝对路径），再读内容。
 */
internal fun HarnessViewModel.refreshDashboard() = withClient { api ->
    val device = api.currentDevice()
    val snapshot = loadSnapshot(api, device)
    update {
        snapshot.copy(
            restoring = false,
            busy = true,
            selectedSessionId = selectedSessionId?.takeIf { id -> snapshot.sessions.any { it.id == id } },
            selectedRootId = selectedRootId?.takeIf { id -> snapshot.roots.any { it.id == id } }
                ?: snapshot.roots.firstOrNull()?.id,
        )
    }
}

internal fun HarnessViewModel.selectSession(id: String?) {
    // 换会话 = 换上下文：在飞的历史/模型/命令请求的结果从此作废，
    // 否则它们回来会把**新**会话的状态覆盖成旧会话的（界面显示 B 标题、内容是 A）。
    requestGate.invalidate()
    update {
        copy(
            selectedSessionId = id,
            history = null,
            liveChat = null,
            sessionModels = null,
            commands = emptyList(),
            approvalState = if (id == null) ApprovalUiState.None else ApprovalUiState.Loading,
            // 换会话就把问答状态清干净：上一组的草稿/已答标记留在界面上会张冠李戴。
            questions = emptyList(),
            answeringCallId = null,
            answeredCallIds = emptySet(),
            // 序号序列是**每个会话各自**的：不清掉，A 的最后一个序号会拿去比 B 的第一条事件，
            // 断档检测必然误判（还会连带触发一次没必要的全量重取）。
            lastEventSeq = null,
            baselineCursor = null,
            // `@` 补全的文件列表属于**上一个会话的工作目录**，换会话必须清掉：
            // 不清的话，切过去的头几百毫秒里 `@` 会列出上个会话的文件，
            // 用户手快选中就发出一条指向错误路径的消息。
            mentionEntries = null,
            mentionFailed = false,
        )
    }
    if (id != null) {
        refreshHistory()
        refreshSessionModels()
        refreshPendingApprovals()
        refreshCommands()
        // 进会话就顺手把工作目录的文件捞回来，等用户打 `@` 时直接可用 ——
        // 放在这里而不是等 `@` 触发再拉：那时用户已经在等补全列表了，
        // 多一次网络往返就是列表「慢半拍」的来源。
        loadMentionEntries()
    }
}

internal fun HarnessViewModel.refreshHistory() = withClient { api ->
    val id = mutableState.value.selectedSessionId ?: return@withClient
    val history = api.history(id)
    update { copy(history = history) }
    // 顺手把待答问题也拉一次。这个函数只有两个调用点（切会话、顶栏那个刷新按钮），
    // 而顶栏刷新正是用户遇到「卡片该出现却没出现」时**唯一**的自救入口 ——
    // 把问答挂在它上面，就不必再教用户「去哪里重试」。
    refreshPendingQuestions()
}

internal fun HarnessViewModel.loadOlderHistory() = withClient { api ->
    val id = mutableState.value.selectedSessionId ?: return@withClient
    val current = mutableState.value.history ?: return@withClient
    if (!current.hasMore) return@withClient
    val beforeSeq = current.events.minOfOrNull { it.event.seq } ?: return@withClient
    val older = api.history(id, beforeSeq)
    update {
        copy(history = current.copy(
            events = (older.events + current.events).distinctBy { it.event.seq },
            hasMore = older.hasMore,
            projections = current.projections ?: older.projections,
        ))
    }
}

/**
 * 打开会话里提到的一个文件（服务器绝对路径）。
 *
 * 分两步：先用服务端的 `roots/resolve` 把绝对路径换成「授权根 + 相对路径」
 * （这一步客户端做不了 —— `/roots` 刻意不返回根的绝对路径），再用既有的
 * [openEntry] 同款读取逻辑把内容取回来。
 *
 * 成功时顺带把「文件」页也切到这个文件（`selectedRootId` / `currentPath` /
 * `document`），于是用户切到「文件」分区就能拿到完整编辑器（含保存），
 * 面板里则只做只读预览。
 */
internal fun HarnessViewModel.openSessionFile(absolutePath: String) {
    val api = client ?: return
    val sessionId = mutableState.value.selectedSessionId ?: return
    update { copy(sessionFileOpen = SessionFileOpen(sessionId, absolutePath)) }
    viewModelScope.launch {
        val outcome = runCatching { withContext(Dispatchers.IO) { loadSessionFile(api, sessionId, absolutePath) } }
        // 用户可能在读取期间切了会话；结果只对发起它的那个会话有效。
        if (mutableState.value.selectedSessionId != sessionId) return@launch
        outcome
            .onSuccess { opened ->
                update {
                    copy(
                        sessionFileOpen = opened.open,
                        selectedRootId = opened.rootId ?: selectedRootId,
                        currentPath = opened.currentPath ?: currentPath,
                        document = opened.document ?: document,
                    )
                }
            }
            .onFailure { failure ->
                update {
                    copy(
                        sessionFileOpen = SessionFileOpen(
                            sessionId = sessionId,
                            requestedPath = absolutePath,
                            loading = false,
                            // 端点不存在几乎只有一个原因：服务端插件版本过旧。
                            // 这条提示必须可操作（「去升级插件」），否则用户会去
                            // 反复检查自己的路径 —— 那是错误的方向。
                            failure = if (failure is DshApiException && failure.code == "ROUTE_NOT_FOUND") {
                                "PLUGIN_TOO_OLD"
                            } else {
                                messageOf(failure)
                            },
                        ),
                    )
                }
            }
    }
}

/** 关闭面板里的文件详情，回到路径列表。 */
internal fun HarnessViewModel.closeSessionFile() = update { copy(sessionFileOpen = null) }

internal fun HarnessViewModel.loadSessionFile(api: DshClient, sessionId: String, absolutePath: String): SessionFileOutcome {
    val resolved = api.resolvePath(absolutePath)
        ?: return SessionFileOutcome(
            SessionFileOpen(sessionId, absolutePath, loading = false, outsideRoots = true),
        )
    val parent = resolved.path.substringBeforeLast('/', "")
    if (resolved.kind == "directory") {
        // 目录没有可预览的内容；但把「文件」页切过去，用户就能就地浏览。
        return SessionFileOutcome(
            open = SessionFileOpen(sessionId, absolutePath, loading = false, resolved = resolved),
            rootId = resolved.rootId,
            currentPath = resolved.path,
        )
    }
    val entry = sessionFileEntry(api, resolved, parent)
    // 图片 / PDF 与文本的上限不同：2 MiB 对一张手机截图太紧，但解码前整份字节要进内存，
    // 所以按类型各给一条闸（见 data/PreviewKind.kt 的 PREVIEW_*_MAX_BYTES）。
    val provisional = previewKind(resolved.path, resolved.contentType)
    val cap = binaryPreviewByteLimit(provisional) ?: MAX_EDIT_BYTES
    val tooLarge = entry.size > cap
    val content = if (tooLarge) null else api.readFile(resolved.rootId, resolved.path)
    val kind = previewKindOf(resolved.path, resolved.contentType, content?.bytes)
    val binary = kind == PreviewKind.IMAGE || kind == PreviewKind.PDF
    // 图片 / PDF 不解 UTF-8：那是白费一次解码，还会把二进制变成一屏替换字符。
    val decoded = if (binary) null else content?.let { decodeUtf8(it.bytes) }
    val text = decoded?.text
    return SessionFileOutcome(
        open = SessionFileOpen(
            sessionId = sessionId,
            requestedPath = absolutePath,
            loading = false,
            resolved = resolved,
            preview = text?.take(PREVIEW_CHARS),
            bytes = if (binary) content?.bytes else null,
            truncated = text != null && text.length > PREVIEW_CHARS,
            tooLarge = tooLarge,
        ),
        rootId = resolved.rootId,
        currentPath = parent,
        document = OpenDocument(
            entry = entry,
            text = text,
            bytes = if (binary) content?.bytes else null,
            etag = content?.etag.orEmpty(),
            hasBom = decoded?.hasBom == true,
            lineEnding = decoded?.lineEnding ?: "\n",
            contentType = content?.contentType,
        ),
    )
}

/**
 * `roots/resolve` 只说「是文件还是目录、多大、何时改」，**没有 `writable`**，
 * 而「文件」页的保存按钮正是看这个字段。目录列表能给出权威值（顺带修正大小与时间），
 * 所以优先取列表里的那一条；列表读不到时退回保守构造：`writable = false` ——
 * 宁可让只读文件显示为只读，也不要放开保存按钮换一个 403。
 */
internal fun HarnessViewModel.sessionFileEntry(api: DshClient, resolved: ResolvedPath, parent: String): FileEntry {
    val listed = runCatching {
        api.entries(resolved.rootId, parent).entries.firstOrNull { it.path == resolved.path }
    }.getOrNull()
    if (listed != null) return listed
    return FileEntry(
        name = resolved.path.substringAfterLast('/').ifBlank { resolved.path },
        path = resolved.path,
        kind = resolved.kind,
        size = resolved.size ?: 0L,
        modifiedAt = resolved.modifiedAt,
        writable = false,
    )
}

internal fun HarnessViewModel.createSession(workspaceId: String, preset: String?, permission: String = "") = withClient { api ->
    val created = api.createSession(workspaceId, preset?.takeIf(String::isNotBlank))
    val sessions = api.sessions()
    val history = api.history(created.id)
    val models = api.sessionModels(created.id)
    val commands = api.sessionCommands(created.id)
    update {
        copy(
            sessions = sessions,
            selectedSessionId = created.id,
            history = history,
            sessionModels = models,
            commands = commands,
            approvalState = ApprovalUiState.None,
        )
    }
    // 这里**不走** selectSession（那是「换到已有会话」的完整语义，含代际作废），
    // 所以 selectSession 里那句 loadMentionEntries() 不会跟着跑 —— 漏掉它的症状是
    // 「新建会话后打 @ 永久显示正在读取」，退出会话再进入反而正常（那次走 selectSession）。
    // 新会话第一条消息用 @ 的概率很高，所以创建完就地拉一次。
    loadMentionEntries()
    // 创建时定的权限档位：会话建好后立刻用 `/permission` 切过去。
    //
    // 为什么是**建完再切**而不是创建接口带参数：bridge 的 `POST /chat/sessions`
    // 只接受 `workspaceId` / `agentPreset`，没有权限字段 —— 加它要动 wire schema
    // 与内核的创建流程，而内核本来就把权限做成**会话级命令**（`/permission`）。
    // 走命令这条路的额外好处：**与用户在会话中手动切档位是同一套机制**，
    // 不会出现「创建时定的」与「切出来的」两套语义。
    applyPermissionPreset(created.id, permission)
}

/**
 * 把会话切到指定权限档位。
 *
 * 空串 = 不动（跟随服务端当前档位）。
 * 失败**不抛**：创建会话是主流程，为一个附加设定失败而让整个创建失败不划算 ——
 * 但要留痕，并且把失败说给用户听（否则就是「选了没生效」）。
 */
internal fun HarnessViewModel.applyPermissionPreset(sessionId: String, permission: String) {
    if (permission.isBlank()) return
    val api = client ?: return
    viewModelScope.launch {
        val outcome = runCatching {
            withContext(Dispatchers.IO) { api.executeCommand(sessionId, "/permission $permission") }
        }
        if (mutableState.value.selectedSessionId != sessionId) return@launch
        outcome
            .onSuccess { execution ->
                if (execution.result.kind == "error") {
                    // 内核回报「命令执行失败」：说清楚是权限没切成功，而不是静默。
                    DiagLog.w("PERM", "权限档位切换失败：${execution.result.text}")
                    update { copy(error = execution.result.text ?: "PERMISSION_CHANGE_FAILED") }
                } else {
                    DiagLog.i("PERM", "权限档位已切到 $permission")
                    // 重拉历史：权限档位在**投影**里，不重拉的话界面上还是旧的那一档。
                    refreshHistory()
                }
            }
            .onFailure { failure ->
                DiagLog.w("PERM", "权限档位切换异常：${failure::class.java.simpleName}: ${failure.message}")
                update { copy(error = messageOf(failure)) }
            }
    }
}

internal fun HarnessViewModel.prepareWorkspacePicker() {
    val rootId = mutableState.value.workspacePickerRootId
        ?.takeIf { id -> mutableState.value.roots.any { it.id == id } }
        ?: mutableState.value.selectedRootId
        ?: mutableState.value.roots.firstOrNull()?.id
    update { copy(workspacePickerRootId = rootId, workspacePickerPath = "", workspacePickerDirectory = null) }
    if (rootId != null) loadWorkspacePickerDirectory("")
}

internal fun HarnessViewModel.selectWorkspacePickerRoot(rootId: String) {
    update { copy(workspacePickerRootId = rootId, workspacePickerPath = "", workspacePickerDirectory = null) }
    loadWorkspacePickerDirectory("")
}

internal fun HarnessViewModel.loadWorkspacePickerDirectory(path: String) = withClient { api ->
    val rootId = mutableState.value.workspacePickerRootId ?: return@withClient
    val normalized = path.trim('/')
    val page = api.entries(rootId, normalized)
    update { copy(workspacePickerPath = normalized, workspacePickerDirectory = page) }
}

internal fun HarnessViewModel.createWorkspaceAndSession(rootId: String, path: String, preset: String?, permission: String = "") = withClient { api ->
    val workspace = api.createChatWorkspace(rootId, path.trim('/'))
    val created = api.createSession(workspace.id, preset?.takeIf(String::isNotBlank))
    val sessions = api.sessions()
    val workspaces = api.chatWorkspaces()
    update {
        copy(
            chatWorkspaces = workspaces,
            sessions = sessions,
            selectedSessionId = created.id,
            history = api.history(created.id),
            sessionModels = api.sessionModels(created.id),
            commands = api.sessionCommands(created.id),
            approvalState = ApprovalUiState.None,
        )
    }
    // 与 createSession 同一个理由：创建路径不经过 selectSession，`@` 补全得自己拉。
    loadMentionEntries()
    // 权限档位：与 createSession 同一套机制（建完再切）。
    applyPermissionPreset(created.id, permission)
}

internal fun HarnessViewModel.renameWorkspace(workspaceId: String, title: String) = withClient { api ->
    api.renameChatWorkspace(workspaceId, title)
    update { copy(chatWorkspaces = api.chatWorkspaces(), sessions = api.sessions()) }
}

internal fun HarnessViewModel.deleteWorkspace(workspaceId: String) = withClient { api ->
    api.deleteChatWorkspace(workspaceId)
    update { copy(chatWorkspaces = api.chatWorkspaces(), sessions = api.sessions()) }
}

internal fun HarnessViewModel.renameSession(sessionId: String, title: String) = withClient { api ->
    api.renameSession(sessionId, title)
    update { copy(sessions = api.sessions()) }
}

/**
 * 分叉会话。
 *
 * `atSeq` 省略 = 由内核在「最近一个完成的边界」分叉；给定 `atSeq` = 在**该事件序号处**分叉
 * （语义取自 OpenAPI：`Forks the session at the latest completed boundary, or at the supplied
 * event sequence`）。
 */
internal fun HarnessViewModel.forkSession(sessionId: String, atSeq: Int? = null) = withClient { api ->
    val forkedId = api.forkSession(sessionId, atSeq)
    update {
        copy(
            sessions = api.sessions(),
            selectedSessionId = forkedId,
            history = api.history(forkedId),
            sessionModels = api.sessionModels(forkedId),
            commands = api.sessionCommands(forkedId),
            approvalState = ApprovalUiState.None,
            liveChat = null,
        )
    }
}

/**
 * 拉取**已归档**会话（归档视图的入口动作）。
 *
 * 两道闸：① 能力位不支持就直接返回（老 bridge 会忽略 `archived` 参数并返回未归档列表，
 * 那样展示出来的「没有归档会话」是假结论）；② 每次进视图都重拉，不做本地缓存 ——
 * 归档状态可能在电脑端被改过，缓存会让手机端长期显示过期结果。
 */
internal fun HarnessViewModel.loadArchivedSessions() {
    if (!supportsArchiveListing(mutableState.value.bridgeCapabilities)) return
    withClient { api ->
        val archived = api.sessions(archived = true)
        update { copy(archivedSessions = archived) }
    }
}

internal fun HarnessViewModel.archiveSession(sessionId: String) = withClient { api ->
    api.archiveSession(sessionId)
    val sessions = api.sessions()
    update {
        if (selectedSessionId == sessionId) {
            copy(
                sessions = sessions,
                selectedSessionId = null,
                history = null,
                liveChat = null,
                sessionModels = null,
                commands = emptyList(),
                approvalState = ApprovalUiState.None,
            )
        } else copy(sessions = sessions)
    }
}

/**
 * 发一条消息：文本 + 待发附件一起组装成 content 数组。
 *
 * `/命令` 分支只在不带附件时走 —— 命令是发给宿主的一行文本，附件跟着它没有意义；
 * 带附件时按普通消息发。
 *
 * 防重复提交靠的是**在调用线程上同步**「取走并清空待发附件」，**不是**靠禁用按钮：
 * 清空之后 `canSubmit` 自然为 false，第二次点击组装出的 parts 就是空的。
 * 之所以不能把取状态留在协程里做：`launchOperation` 会切到 IO，双击的第二次点击
 * 完全可能抢在它前面读到同一批附件，于是同一条消息（连同同一张图）发两遍。
 * 发送失败时把附件还回去，别让一次网络抖动吃掉用户挑好的图。
 */
/**
 * 重试上一轮：把**最后一条用户消息**原样再发一次。
 *
 * 为什么不是「这条失败行对应的那条消息」：`ChatTurnFailure` 里没有 turn/seq
 * （内核只给失败摘要），拿不到精确归属；而失败行永远紧跟在触发它的用户消息之后，
 * 所以「最后一条用户消息」就是它。判据与排除条件见 `data/ChatRetry.kt`。
 *
 * 找不到（历史上没有带正文的用户消息）时**什么都不做**：这是用户主动点的动作，
 * 报一个他看不懂的错反而更糟。
 */
internal fun HarnessViewModel.retryLastUserMessage() {
    val text = retryableUserText(mutableState.value.history?.displayItems().orEmpty()) ?: return
    sendMessage(text, steer = false)
}

internal fun HarnessViewModel.sendMessage(text: String, steer: Boolean) {
    val api = client ?: return
    val id = mutableState.value.selectedSessionId ?: return
    val attachments = mutableState.value.pendingAttachments
    val line = text.trimEnd()
    if (attachments.isEmpty() && isHostCommandLine(line, mutableState.value.commands)) {
        launchOperation {
            val execution = api.executeCommand(id, line)
            if (execution.result.kind == "error") {
                throw DshApiException(409, "COMMAND_FAILED", execution.result.text ?: "Command failed.")
            }
            update { copy(history = api.history(id), commands = api.sessionCommands(id)) }
        }
        return
    }
    val parts = buildList {
        if (text.isNotBlank()) add(PromptPart.TextPart(text))
        attachments.forEach { add(it.part) }
    }
    if (parts.isEmpty()) return
    // 清空在这里做，但**原文记在局部变量里**：失败时才写回 [HarnessState.pendingText]。
    // 写成「提交时就存进 state」的话，UI 监听 pendingText 会把刚提交的正文立刻塞回输入框，
    // 用户看着就像"发了又没发" —— 比吞字还糟。
    //
    // 早先的版本只把附件还回去，文字直接吞了 —— 用户打了一长串字、发失败、
    // 输入框空了，一个字都找不回来。附件能还而文字不能，本身就不讲道理。
    update { copy(pendingAttachments = emptyList(), pendingText = "") }
    launchOperation {
        try {
            api.sendMessage(id, parts, steer)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            // 把附件与文字都放回输入区；期间新加的条目按 id 去重，不重复。
            update {
                copy(
                    pendingAttachments = (attachments + pendingAttachments).distinctBy { it.id },
                    pendingText = text,
                )
            }
            notify(appString(R.string.send_failed_restore, describeSendFailure(failure)))
            throw failure
        }
        chatSessionRefreshPending = true
        scheduleChatRefresh()
    }
}

/**
 * 把发送失败翻译成一句用户看得懂的话。
 *
 * 直接把 `BODY_INVALID: text is required.` 甩到屏幕上，用户能做的只有 staring。
 * 这里至少指明「是消息没发出去」以及大概的原因方向，剩下的交给下一步排查。
 */
internal fun HarnessViewModel.describeSendFailure(failure: Throwable): String {
    val raw = messageOf(failure)
    return when {
        raw.contains("401") || raw.contains("TOKEN") -> appString(R.string.send_failed_auth)
        raw.contains("413") || raw.contains("too large") -> appString(R.string.send_failed_too_large)
        raw.contains("BODY_INVALID") -> appString(R.string.send_failed_body)
        else -> raw
    }
}

internal fun HarnessViewModel.refreshCommands() = withClient { api ->
    val id = mutableState.value.selectedSessionId ?: return@withClient
    update { copy(commands = api.sessionCommands(id)) }
}

internal fun HarnessViewModel.selectPermissionPreset(preset: String) = withClient { api ->
    val id = mutableState.value.selectedSessionId ?: return@withClient
    val execution = api.executeCommand(id, "/permission $preset")
    if (execution.result.kind == "error") {
        throw DshApiException(409, "COMMAND_FAILED", execution.result.text ?: "Permission change failed.")
    }
    update { copy(history = api.history(id), commands = api.sessionCommands(id)) }
}

internal fun HarnessViewModel.refreshSessionModels() = withClient { api ->
    val id = mutableState.value.selectedSessionId ?: return@withClient
    update { copy(sessionModels = api.sessionModels(id)) }
}

internal fun HarnessViewModel.selectModel(selection: ModelSelection) = withClient { api ->
    val id = mutableState.value.selectedSessionId ?: return@withClient
    api.selectModel(id, selection)
    update { copy(sessionModels = api.sessionModels(id)) }
}

internal fun HarnessViewModel.selectAgentPreset(agentPreset: String) = withClient { api ->
    val id = mutableState.value.selectedSessionId ?: return@withClient
    api.selectAgentPreset(id, agentPreset)
    val sessions = api.sessions()
    val models = api.sessionModels(id)
    val commands = api.sessionCommands(id)
    update { copy(sessions = sessions, sessionModels = models, commands = commands) }
}

internal fun HarnessViewModel.cancelRun() = withClient { api ->
    val id = mutableState.value.selectedSessionId ?: return@withClient
    api.cancel(id)
    chatSessionRefreshPending = true
    scheduleChatRefresh()
}
