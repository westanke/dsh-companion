package io.github.hakunm.deepseekharness
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hakunm.deepseekharness.data.QUESTION_STATE_CONTINUED
import io.github.hakunm.deepseekharness.data.ChatContentParts
import io.github.hakunm.deepseekharness.data.ChatHistory
import io.github.hakunm.deepseekharness.data.ChatSession
import io.github.hakunm.deepseekharness.data.ChatWorkspace
import io.github.hakunm.deepseekharness.data.CommandDescriptor
import io.github.hakunm.deepseekharness.data.CustomProviderCreate
import io.github.hakunm.deepseekharness.data.localizedErrorRes
import io.github.hakunm.deepseekharness.data.DeviceView
import io.github.hakunm.deepseekharness.data.DirectoryPage
import io.github.hakunm.deepseekharness.data.DshApiException
import io.github.hakunm.deepseekharness.data.DshClient
import io.github.hakunm.deepseekharness.data.DshConnectionException
import io.github.hakunm.deepseekharness.data.FileEntry
import io.github.hakunm.deepseekharness.data.handoffPathOf
import io.github.hakunm.deepseekharness.data.LiveChatState
import io.github.hakunm.deepseekharness.data.RootView
import io.github.hakunm.deepseekharness.data.AgentPreset
import io.github.hakunm.deepseekharness.data.ModelSelection
import io.github.hakunm.deepseekharness.data.PendingApproval
import io.github.hakunm.deepseekharness.data.PendingQuestionSet
import io.github.hakunm.deepseekharness.data.PluginInventory
import io.github.hakunm.deepseekharness.data.ProviderModel
import io.github.hakunm.deepseekharness.data.ProviderPatch
import io.github.hakunm.deepseekharness.data.ProviderSettings
import io.github.hakunm.deepseekharness.data.PromptPart
import io.github.hakunm.deepseekharness.data.QuestionAnswer
import io.github.hakunm.deepseekharness.data.ResolvedPath
import io.github.hakunm.deepseekharness.data.SessionModels
import io.github.hakunm.deepseekharness.data.ActiveConnection
import io.github.hakunm.deepseekharness.data.AppPreferences
import io.github.hakunm.deepseekharness.data.BusySendMode
import io.github.hakunm.deepseekharness.data.ComposerSendPolicy
import io.github.hakunm.deepseekharness.data.ConnectionCoordinator
import io.github.hakunm.deepseekharness.data.ConnectionShare
import io.github.hakunm.deepseekharness.data.Credential
import io.github.hakunm.deepseekharness.data.CredentialKind
import io.github.hakunm.deepseekharness.data.Endpoint
import io.github.hakunm.deepseekharness.data.EndpointKind
import io.github.hakunm.deepseekharness.data.EndpointProbe
import io.github.hakunm.deepseekharness.data.Host
import io.github.hakunm.deepseekharness.data.HostStore
import io.github.hakunm.deepseekharness.data.KeystoreSecretBox
import io.github.hakunm.deepseekharness.data.LegacyConnectionMigration
import io.github.hakunm.deepseekharness.data.SharedPreferencesKeyValueStore
import io.github.hakunm.deepseekharness.data.TrashEntry
import io.github.hakunm.deepseekharness.data.WorkspaceEvent
import io.github.hakunm.deepseekharness.data.chatDelta
import io.github.hakunm.deepseekharness.data.chatTurnFailure
import io.github.hakunm.deepseekharness.data.PreviewKind
import io.github.hakunm.deepseekharness.data.binaryPreviewByteLimit
import io.github.hakunm.deepseekharness.data.previewKind
import io.github.hakunm.deepseekharness.data.previewKindOf
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

internal fun HarnessViewModel.createSession(workspaceId: String, preset: String?) = withClient { api ->
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

internal fun HarnessViewModel.createWorkspaceAndSession(rootId: String, path: String, preset: String?) = withClient { api ->
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

internal fun HarnessViewModel.forkSession(sessionId: String) = withClient { api ->
    val forkedId = api.forkSession(sessionId)
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
