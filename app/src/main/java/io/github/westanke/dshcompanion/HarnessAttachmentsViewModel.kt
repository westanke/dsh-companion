package io.github.westanke.dshcompanion
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.westanke.dshcompanion.data.QUESTION_STATE_CONTINUED
import io.github.westanke.dshcompanion.data.CHUNKED_UPLOAD_THRESHOLD
import io.github.westanke.dshcompanion.data.ChatContentParts
import io.github.westanke.dshcompanion.data.MAX_UPLOAD_BYTES
import io.github.westanke.dshcompanion.data.UploadedAttachment
import io.github.westanke.dshcompanion.data.ChunkPlan
import io.github.westanke.dshcompanion.data.UploadSession
import io.github.westanke.dshcompanion.debug.DiagLog
import io.github.westanke.dshcompanion.data.beginUpload
import io.github.westanke.dshcompanion.data.completeUpload
import io.github.westanke.dshcompanion.data.missingChunks
import io.github.westanke.dshcompanion.data.planChunks
import io.github.westanke.dshcompanion.data.putChunk
import io.github.westanke.dshcompanion.data.supportsChunkedUpload
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
 * 「附件」域：待发附件（图片内联 / 文件上传）与历史图片的按需取回。
 *
 * 从 `HarnessViewModel.kt`（2277 行）拆出，成员函数改成 **[HarnessViewModel] 的扩展函数**。
 *
 * **图片有两种来源、两条读法**，前缀就是判据：
 *
 * - 原生附件（模型支持图片输入）：字节在服务端附件存储里，走 `readAttachment`，
 *   必须带当前会话 id（内核要读会话事件、拿真实 ref 做完整性校验）；
 * - 交接图片（纯文本模型发图时插件落进工作区 `.dsh-images/` 的文件）：字节根本不在附件存储里，
 *   走官方那条「绝对路径 → 授权根」的解析与读文件，与会话无关，所以不需要 sessionId。
 *
 * 为什么**不**把 `attachmentUploading` 算进发送键可用性：上一轮的缺陷正是用全局 `busy`
 * 禁用发送键，导致智能体运行期间根本发不出消息。
 *
 * 取图失败分四类翻成人话：401 授权过期（去重新配对）、403 缺 `files.read`、404 附件真不在了、
 * 其余多半是网络或解码。混成一句「可能已被清理」，用户只会去清缓存 —— 而缓存不是原因。
 */
/**
 * 把选中的图片内联进「待发附件」。
 *
 * 图片不走上传端点（内核的 image part 直接带 base64），所以这里唯一要防的就是
 * 体积：超限当场拒绝并给出可照做的提示，而不是等一个 413。
 */
internal fun HarnessViewModel.attachImage(name: String, sizeBytes: Long, mediaType: String, openInput: () -> InputStream) {
    if (sizeBytes > ChatContentParts.MAX_IMAGE_BYTES) {
        notify(appString(R.string.attachment_image_too_large, ChatContentParts.formatBytes(ChatContentParts.MAX_IMAGE_BYTES)))
        return
    }
    viewModelScope.launch {
        val bytes = readBytes(ChatContentParts.MAX_IMAGE_BYTES, openInput) ?: return@launch
        if (bytes.size.toLong() > ChatContentParts.MAX_IMAGE_BYTES) {
            notify(appString(R.string.attachment_image_too_large, ChatContentParts.formatBytes(ChatContentParts.MAX_IMAGE_BYTES)))
            return@launch
        }
        val part = runCatching { ChatContentParts.imagePart(mediaType, bytes, name) }.getOrElse {
            notify(appString(R.string.attachment_image_too_large, ChatContentParts.formatBytes(ChatContentParts.MAX_IMAGE_BYTES)))
            return@launch
        }
        update {
            copy(
                pendingAttachments = pendingAttachments + PendingAttachment(
                    id = java.util.UUID.randomUUID().toString(),
                    part = part,
                    name = name,
                    sizeBytes = bytes.size.toLong(),
                    imageBytes = bytes,
                ),
            )
        }
    }
}

/**
 * 上传选中的文件，换回 `receiptId` 后放进「待发附件」。
 *
 * 上传是网络动作，单独一个 [attachmentUploading] 只用于显示进度；**不**参与发送键的
 * 可用性判断，否则"选了个大文件"就等于"暂时发不出消息"，正是上一轮的毛病。
 */
internal fun HarnessViewModel.attachFile(name: String, sizeBytes: Long, openInput: () -> InputStream) {
    val api = client ?: return
    val sessionId = mutableState.value.selectedSessionId ?: return
    // 大文件改走分块，前提是**服务端自报了**能力位。老桥没有这条路由时退回单请求上传：
    // base64 单请求的天花板是 24 MiB，分块是 256 MiB，两者的上限必须分开判，
    // 否则用户会看到「明明插件支持大文件，App 却说太大了」。
    val chunked = shouldUploadInChunks(sizeBytes)
    val limit = if (chunked) MAX_UPLOAD_BYTES else ChatContentParts.MAX_FILE_BYTES
    if (sizeBytes > limit) {
        notify(appString(R.string.attachment_file_too_large, ChatContentParts.formatBytes(limit)))
        return
    }
    viewModelScope.launch {
        update { copy(attachmentUploading = true, chunkUpload = null) }
        try {
            val uploaded = if (chunked) {
                uploadInChunks(api, sessionId, name, sizeBytes, openInput)
            } else {
                val bytes = readBytes(ChatContentParts.MAX_FILE_BYTES, openInput) ?: return@launch
                if (bytes.size.toLong() > ChatContentParts.MAX_FILE_BYTES) {
                    notify(appString(R.string.attachment_file_too_large, ChatContentParts.formatBytes(ChatContentParts.MAX_FILE_BYTES)))
                    return@launch
                }
                withContext(Dispatchers.IO) { api.uploadAttachment(sessionId, bytes, name) }
            }
            // 上传期间用户可能换了会话：附件属于发起它的那条会话，别塞到别的会话里。
            if (mutableState.value.selectedSessionId != sessionId) return@launch
            update {
                copy(
                    pendingAttachments = pendingAttachments + PendingAttachment(
                        id = java.util.UUID.randomUUID().toString(),
                        part = PromptPart.FilePart(uploaded.receiptId),
                        name = uploaded.name.ifBlank { name },
                        sizeBytes = sizeBytes,
                    ),
                    chunkUpload = null,
                )
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            val message = when {
                // 服务端的上限可能比客户端更严；这时不能引用我们自己的 24 MiB，
                // 否则用户会被「明明没到 24 MiB」搞糊涂。
                failure is DshApiException && failure.code == "TOO_LARGE" ->
                    appString(R.string.attachment_file_rejected)
                // 端点不存在只有一个现实原因：服务端插件版本过旧。
                // 这条提示必须可操作（去升级插件），而不是甩一句 ROUTE_NOT_FOUND。
                failure is DshApiException && failure.code == "ROUTE_NOT_FOUND" ->
                    appString(R.string.attachment_upload_unsupported)
                else -> messageOf(failure)
            }
            // 分块失败留一条可重试的进度：重试会**重发同一次 beginUpload**，服务端按
            // 同名同大小复用 uploadId 并带回 alreadyReceived，于是断点续传是免费的。
            update {
                copy(
                    error = message,
                    chunkUpload = chunkUpload?.copy(failed = true, error = message),
                )
            }
            DiagLog.w("UPLOAD", "attachFile 失败（chunked=$chunked）: ${failure.message}")
        } finally {
            update { copy(attachmentUploading = false) }
        }
    }
}

/** 是否走分块：超阈值 + 服务端自报支持 + 没超分块上限。 */
private fun HarnessViewModel.shouldUploadInChunks(sizeBytes: Long): Boolean =
    sizeBytes > CHUNKED_UPLOAD_THRESHOLD &&
        sizeBytes <= MAX_UPLOAD_BYTES &&
        supportsChunkedUpload(mutableState.value.bridgeCapabilities)

/**
 * 分块上传：流式读文件，每块 1 MiB 直接 PUT，**不把整个文件读进内存**。
 *
 * 这是分块上传相对 base64 单请求的两个实际好处：大文件不再占两份内存（原文 + base64），
 * 而且断点续传靠服务端的 `alreadyReceived` 免费拿到，客户端不记账。
 */
private suspend fun HarnessViewModel.uploadInChunks(
    api: DshClient,
    sessionId: String,
    name: String,
    sizeBytes: Long,
    openInput: () -> InputStream,
): UploadedAttachment {
    val session = withContext(Dispatchers.IO) { api.beginUpload(sessionId, name, sizeBytes) }
    val plan = planChunks(sizeBytes, session.chunkSize, session.alreadyReceived)
    val alreadySent = plan.count - plan.pending.size
    update {
        copy(
            chunkUpload = ChunkUploadProgress(
                name = name,
                sent = alreadySent,
                total = plan.count,
                resumed = alreadySent > 0,
            ),
        )
    }
    openInput().use { stream ->
        var cursor = 0L
        plan.pending.forEachIndexed { position, index ->
            val offset = plan.offsetOf(index)
            if (offset > cursor) cursor = skipFully(stream, offset, cursor)
            val length = minOf(plan.chunkSize, sizeBytes - offset).toInt()
            val bytes = readExactly(stream, length)
            cursor = offset + bytes.size
            withContext(Dispatchers.IO) { api.putChunk(sessionId, session.uploadId, index, bytes) }
            update { copy(chunkUpload = chunkUpload?.copy(sent = alreadySent + position + 1)) }
        }
    }
    return completeWithRepair(api, sessionId, session, plan, sizeBytes, openInput)
}

/**
 * 收尾；遇 409 就按 `details.missing` **补一次**再重试。
 *
 * 只补一次是刻意的：服务端已经明确告诉我们缺哪几块，补完还缺说明它那边的状态在变，
 * 再循环就是拿网络打服务端 —— 交给用户点重试（那次会走完整的断点续传）。
 */
private suspend fun HarnessViewModel.completeWithRepair(
    api: DshClient,
    sessionId: String,
    session: UploadSession,
    plan: ChunkPlan,
    sizeBytes: Long,
    openInput: () -> InputStream,
): UploadedAttachment {
    try {
        return withContext(Dispatchers.IO) { api.completeUpload(sessionId, session.uploadId) }
    } catch (failure: DshApiException) {
        val missing = failure.missingChunks()
        if (failure.status != 409 || missing.isEmpty()) throw failure
        DiagLog.w("UPLOAD", "complete 报缺块 ${missing.joinToString()}，补传后重试一次")
        openInput().use { stream ->
            missing.sorted().forEach { index ->
                val offset = plan.offsetOf(index)
                val length = minOf(plan.chunkSize, sizeBytes - offset).toInt()
                val bytes = readChunkAt(stream, offset, length)
                withContext(Dispatchers.IO) { api.putChunk(sessionId, session.uploadId, index, bytes) }
            }
        }
        return withContext(Dispatchers.IO) { api.completeUpload(sessionId, session.uploadId) }
    }
}

/** 顺序读时把流推到 [target]；返回新的游标。 */
private fun skipFully(stream: InputStream, target: Long, from: Long): Long {
    var remaining = target - from
    while (remaining > 0) {
        val skipped = stream.skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
            continue
        }
        // skip 可能返回 0（某些 provider 就是这样）；退回读一个字节，别死循环。
        if (stream.read() < 0) throw IOException("文件在读取过程中变短了")
        remaining -= 1
    }
    return target
}

private fun readExactly(stream: InputStream, length: Int): ByteArray {
    val buffer = ByteArray(length)
    var read = 0
    while (read < length) {
        val count = stream.read(buffer, read, length - read)
        if (count < 0) throw IOException("文件在读取过程中变短了")
        read += count
    }
    return buffer
}

/** 从 [offset] 起读一段（补块用；按需跳过，不假设顺序）。 */
private fun readChunkAt(stream: InputStream, offset: Long, length: Int): ByteArray {
    skipFully(stream, offset, 0L)
    return readExactly(stream, length)
}

/** 从待发附件里删掉一条。 */
internal fun HarnessViewModel.removePendingAttachment(id: String) = update {
    copy(pendingAttachments = pendingAttachments.filterNot { it.id == id })
}

/** 清空待发附件（例如用户放弃了这条消息）。 */
internal fun HarnessViewModel.clearPendingAttachments() = update { copy(pendingAttachments = emptyList()) }

/**
 * 读进内存，但**最多读 limit + 1 字节**就停手（细节与理由见
 * [ChatContentParts.readAtMost]）：一个谎报体积的文件不该把内存吃穿。
 * 超限与否由调用方按返回长度判定。
 */
internal suspend fun HarnessViewModel.readBytes(limit: Long, openInput: () -> InputStream): ByteArray? =
    try {
        withContext(Dispatchers.IO) { openInput().use { ChatContentParts.readAtMost(limit, it) } }
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        notify(appString(R.string.attachment_read_failed))
        null
    }

/**
 * 按需取回一张会话图片。
 *
 * **两种来源、两条读法**，前缀就是判据：
 * - 原生附件（模型支持图片输入）：字节在服务端附件存储里，走 `readAttachment`，
 *   必须带当前会话 id（内核要读会话事件、拿真实 ref 做完整性校验）；
 * - 交接图片（纯文本模型发图时插件落进工作区 `.dsh-images/` 的文件）：
 *   字节根本不在附件存储里，走官方那条「绝对路径 → 授权根」的解析与读文件，
 *   与会话无关，所以不需要 sessionId。
 *
 * 幂等且可反复调用（界面在组合时直接调它）：已缓存的直接返回，正在取的会被
 * [pendingAttachments] 挡住，取失败的连同**原因**记进 [HarnessState.attachmentFailures]
 * 不再重试。前两条保证「一次会话里每张图最多下载一次」；记原因是因为「不再重试」
 * 只在原因稳定时才是对的 —— 而授权过期是能被恢复的，界面提示得告诉用户去做什么。
 */
internal fun HarnessViewModel.loadAttachmentImage(attachmentId: String) {
    if (attachmentId.isBlank()) return
    val api = client ?: return
    val current = mutableState.value
    // 交接图的 id 本身就是一条服务器绝对路径；判据与 ChatPresentation 共用一份实现。
    val handoffPath = handoffPathOf(attachmentId)
    val sessionId = current.selectedSessionId
    // 原生附件不在任何会话里就无从说明「这是哪个会话的图」：内核会拒绝，
    // 早一点返回能避免发一个必然 400 的请求。交接图是工作区里的普通文件，与会话无关。
    if (handoffPath == null && sessionId.isNullOrBlank()) return
    if (current.attachmentImages.containsKey(attachmentId)) return
    // 授权类失败**不永久记住**：用户重新配对之后图就该能显示，
    // 而记住失败会让它永远停在「不可用」，除非重启 App。附件真的没了（404）才记死。
    if (attachmentId in current.attachmentFailures && attachmentId !in retryableAttachmentFailures) return
    if (!pendingAttachments.add(attachmentId)) return
    viewModelScope.launch {
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                if (handoffPath != null) {
                    readHandoffImage(api, handoffPath)
                } else {
                    api.readAttachment(sessionId.orEmpty(), attachmentId)
                }
            }
        }
        pendingAttachments.remove(attachmentId)
        outcome
            .onSuccess { bytes ->
                update {
                    copy(
                        attachmentImages = attachmentImages + (attachmentId to bytes),
                        attachmentFailures = attachmentFailures - attachmentId,
                    )
                }
                retryableAttachmentFailures.remove(attachmentId)
            }
            .onFailure { failure ->
                val reason = describeAttachmentFailure(failure)
                val retryable = isRetryableAttachmentFailure(failure)
                if (retryable) retryableAttachmentFailures.add(attachmentId)
                update { copy(attachmentFailures = attachmentFailures + (attachmentId to reason)) }
            }
    }
}

/**
 * 读一张**交接图片**（纯文本模型发图时落进会话工作区 `.dsh-images/` 的那份）。
 *
 * 用官方那条现成的路：`resolvePath` 把服务器绝对路径解析成「授权根 + 相对路径」，
 * 再 `readFile` 取字节 —— 与文件页预览（[loadSessionFile]）同一个端点、同一份
 * `files.read` 授权，没有为图片另开口子。
 *
 * 解析不出来（路径不在任何授权根内）就按失败上报，界面会给出原因；
 * 错误文案里带上 `PATH_OUTSIDE_ROOTS` 这个码，好让 [describeAttachmentFailure]
 * 能把它和其它失败区分开。
 */
internal fun HarnessViewModel.readHandoffImage(api: DshClient, path: String): ByteArray {
    val resolved = api.resolvePath(path)
        ?: throw DshApiException(404, "PATH_OUTSIDE_ROOTS", "PATH_OUTSIDE_ROOTS: $path")
    return api.readFile(resolved.rootId, resolved.path).bytes
}

/**
 * 这类失败在重新配对后可能自愈，因此允许重试。
 *
 * 判据是**病因**而不是错误码：401/403 是授权状态问题，换一次配对就好了；
 * 404 是服务端确实没有这份字节，再试一百次也一样。
 */
internal fun HarnessViewModel.isRetryableAttachmentFailure(failure: Throwable): Boolean {
    val raw = messageOf(failure)
    return raw.contains("401") || raw.contains("403") || raw.contains("TOKEN") ||
        raw.contains("SCOPE") || raw.contains("FORBIDDEN")
}

/**
 * 把取图失败翻译成一句能照做的提示。
 *
 * 这几个分支对应四类完全不同的病因，用户该做的事也不同：
 * 401 是授权过期（去重新配对）、403 是缺 `files.read` 授权、404 是附件真不在了、
 * 其余多半是网络或解码。把它们混成一句「可能已被清理」，用户只会去清缓存 ——
 * 而缓存不是原因。
 */
internal fun HarnessViewModel.describeAttachmentFailure(failure: Throwable): String {
    val raw = messageOf(failure)
    return when {
        raw.contains("PATH_OUTSIDE_ROOTS") || raw.contains("PATH_NOT_FOUND") ||
            raw.contains("PATH_NOT_ABSOLUTE") -> appString(R.string.attachment_fail_outside_roots)

        raw.contains("401") || raw.contains("TOKEN") -> appString(R.string.attachment_fail_auth)
        raw.contains("403") || raw.contains("SCOPE") || raw.contains("FORBIDDEN") ->
            appString(R.string.attachment_fail_scope)

        raw.contains("404") || raw.contains("NOT_FOUND") -> appString(R.string.attachment_fail_missing)
        else -> appString(R.string.attachment_fail_other, raw)
    }
}
