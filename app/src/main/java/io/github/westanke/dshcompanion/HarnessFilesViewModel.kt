package io.github.westanke.dshcompanion
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.westanke.dshcompanion.data.QUESTION_STATE_CONTINUED
import io.github.westanke.dshcompanion.data.ChatContentParts
import io.github.westanke.dshcompanion.data.ChatHistory
import io.github.westanke.dshcompanion.data.ChatSession
import io.github.westanke.dshcompanion.data.ChatWorkspace
import io.github.westanke.dshcompanion.data.CommandDescriptor
import io.github.westanke.dshcompanion.data.CustomProviderCreate
import io.github.westanke.dshcompanion.data.localizedErrorRes
import io.github.westanke.dshcompanion.data.DeviceView
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
 * 「文件」域：授权根切换、目录浏览与分页、打开文件、保存、增删改移、上传下载与回收站，
 * 以及 UTF-8 / BOM / 行尾的解码与还原。
 *
 * 从 `HarnessViewModel.kt`（2277 行）拆出，成员函数改成 **[HarnessViewModel] 的扩展函数**。
 *
 * 解码规则（`decodeUtf8`）：含 NUL 一律算二进制 —— 真正的文本文件不会有 `\u0000`，
 * 而图片 / 压缩包解成 UTF-8 时反而经常「成功」，只判解码是否报错会把一屏替换字符
 * 当成文件内容。
 *
 * 行尾要**原样记住**：保存时按 `lineEnding` 还原，否则一个 CRLF 文件被存成 LF，
 * 用户在别的工具里就是一整文件的 diff。
 */
internal fun HarnessViewModel.selectRoot(id: String) {
    update { copy(selectedRootId = id, currentPath = "", document = null) }
    loadDirectory("")
}

internal fun HarnessViewModel.loadDirectory(path: String = mutableState.value.currentPath) = withClient { api ->
    val root = mutableState.value.selectedRootId ?: return@withClient
    val normalized = path.trim('/')
    val page = api.entries(root, normalized)
    update { copy(currentPath = normalized, directory = page, document = null) }
}

internal fun HarnessViewModel.loadNextDirectoryPage() = withClient { api ->
    val current = mutableState.value.directory ?: return@withClient
    val cursor = current.nextCursor ?: return@withClient
    val root = mutableState.value.selectedRootId ?: return@withClient
    val next = api.entries(root, current.path, cursor)
    update {
        copy(directory = current.copy(
            entries = (current.entries + next.entries).distinctBy { it.path },
            nextCursor = next.nextCursor,
        ))
    }
}

/**
 * 加载当前会话**工作目录**的条目，供输入框 `@` 补全（对齐官方「@ 调取当前工作目录
 * 文件或文件夹」那条功能）。
 *
 * 为什么不直接用 [loadDirectory] 那套：那是「文件」页的浏览状态，用户会自己切根、
 * 进子目录；`@` 必须**始终**指向本会话的 cwd，共用一个字段会导致「在文件页翻到别处，
 * 回会话里补全出的是别的目录」。所以单独一个字段、单独一次请求。
 *
 * **只用两步，不走 `resolvePath`**：会话里已经有 `rootId`，而 `cwd` 本身就是
 * 「相对授权根」的 wire 路径 —— 列举接口要的正是这两样。此前这里先把 `cwd` 送去
 * `resolvePath`「翻译」成「根 id + 相对路径」，可那个端点**要求绝对路径输入**、
 * 对相对路径直接 400（`PATH_NOT_ABSOLUTE`），于是第二步必然失败、补全永远列不出
 * 文件。多绕的这一圈是纯粹的误解：服务端给的 `cwd` 从来就是相对路径。
 *
 * **`cwd` 为空字符串是合法值**，表示会话工作目录就是授权根本身
 * （`path.relative(root, root) === ''`）。此前 `.takeIf { it.isNotBlank() }` 把它
 * 当成「没有工作目录」丢掉 —— 线上那句「读不到这个会话的工作目录」就是这么来的。
 *
 * 拿不到时把 [HarnessUiState.mentionFailed] 置真，让界面如实说「读不到」，
 * 而不是谎称「目录是空的」。`@` 补全出不来不影响打字与发送，所以不弹错误提示打断用户。
 */
internal fun HarnessViewModel.loadMentionEntries() = withClient { api ->
    val sessionId = mutableState.value.selectedSessionId ?: return@withClient
    val session = mutableState.value.sessions.firstOrNull { it.id == sessionId } ?: return@withClient
    val page = runCatching {
        // 空 cwd（= 授权根本身）照常列举；trim('/') 只把结尾多余的斜杠收干净。
        withContext(Dispatchers.IO) { api.entries(session.rootId, session.cwd.trim('/')) }
    }.getOrNull()
    // 会话可能在请求期间被切走，回来发现已是另一个会话就别把那批文件塞进去。
    if (mutableState.value.selectedSessionId != sessionId) return@withClient
    update {
        if (page == null) copy(mentionEntries = null, mentionFailed = true)
        else copy(mentionEntries = page.entries, mentionFailed = false)
    }
}

/**
 * 把「输入框上方的补全面板开着」这个事实同步出去，供外层抽屉决定是否响应滑动手势
 * （见 [HarnessUiState.composerOverlay]）。
 *
 * 由 ChatScreen 在面板出现/消失时调用。只在**值真的变了**的时候写 state ——
 * 这个函数会在重组路径上被调到，每次无条件 update 会白白推一串快照。
 */
internal fun HarnessViewModel.setComposerOverlay(active: Boolean) {
    if (mutableState.value.composerOverlay == active) return
    update { copy(composerOverlay = active) }
}

internal fun HarnessViewModel.openEntry(entry: FileEntry) {
    if (entry.kind == "directory") {
        loadDirectory(entry.path)
        return
    }
    withClient { api ->
        val root = mutableState.value.selectedRootId ?: return@withClient
        // 图片与 PDF 有自己的字节上限：文本的 2 MiB 闸对它们太紧（一张手机截图就 3~8 MiB），
        // 但也不能不设闸 —— 解码前整份字节都要进内存。
        val provisional = previewKind(entry.name, null)
        val cap = binaryPreviewByteLimit(provisional) ?: MAX_EDIT_BYTES
        if (entry.size > cap) {
            update {
                copy(
                    document = OpenDocument(entry, skippedForSize = true),
                    // 文本超限沿用既有的全局提示；图片 / PDF 的提示由预览面板自己给
                    // （带「下载」出路），免得同一条信息在页面顶部与内容区各刷一遍。
                    error = if (provisional == PreviewKind.IMAGE || provisional == PreviewKind.PDF) null
                    else "FILE_TOO_LARGE",
                )
            }
            return@withClient
        }
        val content = api.readFile(root, entry.path)
        // 拿到字节后重判一次：FileEntry 里没有 contentType，服务端 MIME 也不可靠，
        // 所以「没有扩展名 / 扩展名是 .bin 的图片」只能靠魔数认出来。
        val kind = previewKindOf(entry.name, content.contentType, content.bytes)
        if (kind == PreviewKind.IMAGE || kind == PreviewKind.PDF) {
            update {
                copy(
                    document = OpenDocument(
                        entry = entry,
                        bytes = content.bytes,
                        etag = content.etag,
                        contentType = content.contentType,
                    ),
                    error = null,
                )
            }
            return@withClient
        }
        val decoded = decodeUtf8(content.bytes)
        update {
            copy(
                document = OpenDocument(
                    entry = entry,
                    text = decoded?.text,
                    etag = content.etag,
                    hasBom = decoded?.hasBom == true,
                    lineEnding = decoded?.lineEnding ?: "\n",
                    contentType = content.contentType,
                ),
                error = if (decoded == null) "BINARY_FILE" else null,
            )
        }
    }
}

internal fun HarnessViewModel.selectEntryForAction(entry: FileEntry) = update {
    copy(document = OpenDocument(entry))
}

internal fun HarnessViewModel.updateDocument(text: String) = update {
    copy(document = document?.copy(text = text))
}

internal fun HarnessViewModel.saveDocument() = withClient { api ->
    val root = mutableState.value.selectedRootId ?: return@withClient
    val document = mutableState.value.document ?: return@withClient
    val text = document.text ?: return@withClient
    val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    val encoded = when (document.lineEnding) {
        "\r\n" -> normalized.replace("\n", "\r\n")
        "\r" -> normalized.replace("\n", "\r")
        else -> normalized
    }
        .toByteArray(Charsets.UTF_8)
    val bytes = if (document.hasBom) UTF8_BOM + encoded else encoded
    val etag = api.writeFile(root, document.entry.path, bytes, document.etag)
    update { copy(document = document.copy(etag = etag)) }
    val page = api.entries(root, mutableState.value.currentPath)
    update { copy(directory = page) }
}

internal fun HarnessViewModel.createEntry(name: String, directory: Boolean) = withClient { api ->
    val root = mutableState.value.selectedRootId ?: return@withClient
    val path = childPath(mutableState.value.currentPath, name)
    api.createEntry(root, path, directory)
    update { copy(directory = api.entries(root, mutableState.value.currentPath)) }
}

internal fun HarnessViewModel.moveSelected(destination: String) = withClient { api ->
    val root = mutableState.value.selectedRootId ?: return@withClient
    val document = mutableState.value.document ?: return@withClient
    api.moveEntry(root, document.entry.path, destination.trim('/'))
    update { copy(document = null, directory = api.entries(root, mutableState.value.currentPath)) }
}

internal fun HarnessViewModel.trashSelected() = withClient { api ->
    val root = mutableState.value.selectedRootId ?: return@withClient
    val document = mutableState.value.document ?: return@withClient
    api.trashEntry(root, document.entry.path)
    update {
        copy(document = null, directory = api.entries(root, currentPath), trash = api.trash())
    }
}

internal fun HarnessViewModel.upload(name: String, length: Long, openInput: () -> InputStream) = withClient { api ->
    val root = mutableState.value.selectedRootId ?: return@withClient
    api.writeFile(root, childPath(mutableState.value.currentPath, name), length, openInput)
    update { copy(directory = api.entries(root, mutableState.value.currentPath)) }
}

internal fun HarnessViewModel.replaceSelected(length: Long, openInput: () -> InputStream) = withClient { api ->
    val root = mutableState.value.selectedRootId ?: return@withClient
    val document = mutableState.value.document ?: return@withClient
    val etag = document.etag.ifBlank { api.readFile(root, document.entry.path).etag }
    api.writeFile(root, document.entry.path, length, openInput, etag)
    openEntry(document.entry.copy(size = length))
}

internal fun HarnessViewModel.downloadSelected(openOutput: () -> OutputStream) = withClient { api ->
    val root = mutableState.value.selectedRootId ?: return@withClient
    val entry = mutableState.value.document?.entry ?: return@withClient
    api.downloadFile(root, entry.path, openOutput())
}

internal fun HarnessViewModel.loadTrash() = withClient { api -> update { copy(trash = api.trash()) } }

internal fun HarnessViewModel.restoreTrash(id: String) = withClient { api ->
    api.restoreTrash(id)
    update { copy(trash = api.trash()) }
    loadDirectory()
}

internal fun HarnessViewModel.decodeUtf8(bytes: ByteArray): DecodedText? {
    val hasBom = bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals(UTF8_BOM)
    val content = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = runCatching { decoder.decode(ByteBuffer.wrap(content)).toString() }.getOrNull() ?: return null
    if ('\u0000' in text) return null
    val lineEnding = when {
        "\r\n" in text -> "\r\n"
        '\r' in text -> "\r"
        else -> "\n"
    }
    return DecodedText(text, hasBom, lineEnding)
}

internal fun HarnessViewModel.childPath(parent: String, name: String): String =
    if (parent.isBlank()) name.trim('/') else "$parent/${name.trim('/')}"
