package io.github.hakunm.deepseekharness.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import io.github.hakunm.deepseekharness.HarnessState
import io.github.hakunm.deepseekharness.HarnessViewModel
import io.github.hakunm.deepseekharness.OpenDocument
import io.github.hakunm.deepseekharness.R
import io.github.hakunm.deepseekharness.data.FileEntry
import io.github.hakunm.deepseekharness.data.PreviewKind
import io.github.hakunm.deepseekharness.data.previewKind

@Composable
fun FilesScreen(state: HarnessState, viewModel: HarnessViewModel, wide: Boolean) {
    val context = LocalContext.current
    var createDirectory by remember { mutableStateOf<Boolean?>(null) }
    var moveDialog by remember { mutableStateOf(false) }
    var trashDialog by remember { mutableStateOf(false) }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val size = displaySize(context, it)
            viewModel.upload(displayName(context, it), size) {
                requireNotNull(context.contentResolver.openInputStream(it))
            }
        }
    }
    val replaceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val size = displaySize(context, it)
            viewModel.replaceSelected(size) { requireNotNull(context.contentResolver.openInputStream(it)) }
        }
    }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let { viewModel.downloadSelected { requireNotNull(context.contentResolver.openOutputStream(it)) } }
    }
    val download: () -> Unit = { state.document?.entry?.name?.let(downloadLauncher::launch) }

    if (state.roots.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Outlined.FolderOpen, stringResource(R.string.no_roots))
        }
        return
    }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            FileBrowser(
                state,
                viewModel,
                onNewFile = { createDirectory = false },
                onNewFolder = { createDirectory = true },
                onUpload = { uploadLauncher.launch(arrayOf("*/*")) },
                onTrash = { viewModel.loadTrash(); trashDialog = true },
                modifier = Modifier.width(360.dp).fillMaxHeight(),
            )
            VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
            EditorPane(
                state,
                viewModel,
                null,
                download,
                onReplace = { replaceLauncher.launch(arrayOf("*/*")) },
                onMove = { moveDialog = true },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    } else if (state.document == null) {
        FileBrowser(
            state,
            viewModel,
            onNewFile = { createDirectory = false },
            onNewFolder = { createDirectory = true },
            onUpload = { uploadLauncher.launch(arrayOf("*/*")) },
            onTrash = { viewModel.loadTrash(); trashDialog = true },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        EditorPane(
            state,
            viewModel,
            { viewModel.loadDirectory() },
            download,
            onReplace = { replaceLauncher.launch(arrayOf("*/*")) },
            onMove = { moveDialog = true },
            modifier = Modifier.fillMaxSize(),
        )
    }

    createDirectory?.let { directory ->
        NewEntryDialog(directory, onClose = { createDirectory = null }) { name ->
            viewModel.createEntry(name, directory)
            createDirectory = null
        }
    }
    if (moveDialog) MoveDialog(state.document, viewModel) { moveDialog = false }
    if (trashDialog) TrashDialog(state, viewModel) { trashDialog = false }
}

@Composable
private fun FileBrowser(
    state: HarnessState,
    viewModel: HarnessViewModel,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onUpload: () -> Unit,
    onTrash: () -> Unit,
    modifier: Modifier,
) {
    var rootMenu by remember { mutableStateOf(false) }
    var createMenu by remember { mutableStateOf(false) }
    val canWrite = "files.write" in state.device?.scopes.orEmpty()
    Column(modifier.padding(horizontal = 12.dp)) {
        Text(
            stringResource(R.string.root_directory),
            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { rootMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Outlined.FolderOpen, null)
                    Text(
                        state.roots.find { it.id == state.selectedRootId }?.label.orEmpty(),
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Outlined.ExpandMore, null)
                }
                DropdownMenu(expanded = rootMenu, onDismissRequest = { rootMenu = false }) {
                    state.roots.forEach { root ->
                        DropdownMenuItem(
                            text = { Text(root.label) },
                            onClick = { viewModel.selectRoot(root.id); rootMenu = false },
                        )
                    }
                }
            }
            IconButton(onClick = { viewModel.loadDirectory() }) {
                Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh))
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { viewModel.loadDirectory(state.currentPath.substringBeforeLast('/', "")) },
                    enabled = state.currentPath.isNotEmpty(),
                ) {
                    Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.go_up))
                }
                Text(
                    "/${state.currentPath}",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                Box {
                    IconButton(onClick = { createMenu = true }, enabled = canWrite) {
                        Icon(Icons.Outlined.CreateNewFolder, stringResource(R.string.create))
                    }
                    DropdownMenu(expanded = createMenu, onDismissRequest = { createMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.new_file)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null) },
                            onClick = { createMenu = false; onNewFile() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.new_folder)) },
                            leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                            onClick = { createMenu = false; onNewFolder() },
                        )
                    }
                }
                IconButton(onClick = onUpload, enabled = canWrite) {
                    Icon(Icons.Outlined.UploadFile, stringResource(R.string.upload))
                }
                IconButton(onClick = onTrash) {
                    Icon(Icons.Outlined.RestoreFromTrash, stringResource(R.string.trash))
                }
            }
        }

        val entries = state.directory?.entries.orEmpty()
        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Outlined.FolderOpen, stringResource(R.string.empty_directory))
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(entries, key = { it.path }) { entry -> FileRow(entry, viewModel) }
                if (state.directory?.nextCursor != null) {
                    item {
                        TextButton(onClick = viewModel::loadNextDirectoryPage, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.load_more))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, viewModel: HarnessViewModel) {
    val kindLabel = if (entry.kind == "directory") {
        stringResource(R.string.file_kind_directory)
    } else {
        stringResource(R.string.file_kind_file)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { viewModel.openEntry(entry) }
            .padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (entry.kind == "directory") Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
                null,
                modifier = Modifier.size(21.dp),
                tint = if (entry.kind == "directory") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            // 文件名最多 3 行。
            //
            // 原来是 `maxLines = 1`：手机屏窄，稍长的名字（比如
            // `DeepSeek-Harness-companion-v2.2.34.apk`）就被省成 `DeepSeek-Harness-…`，
            // 而第二行给的是「类型 · 大小」—— 那对区分文件**毫无帮助**，于是用户
            // 「看不完名字，判断不出到底是哪个文件」（真机反馈）。放宽到 3 行足以覆盖
            // 绝大多数文件名；再长的仍留 `Ellipsis` 兜底，不至于把一行撑满整屏。
            Text(entry.name, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (entry.kind == "directory") kindLabel else "$kindLabel · ${formatBytes(entry.size)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { viewModel.selectEntryForAction(entry) }) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.file_actions))
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun EditorPane(
    state: HarnessState,
    viewModel: HarnessViewModel,
    onBack: (() -> Unit)?,
    onDownload: () -> Unit,
    onReplace: () -> Unit,
    onMove: () -> Unit,
    modifier: Modifier,
) {
    val document = state.document
    if (document == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            EmptyState(Icons.AutoMirrored.Outlined.InsertDriveFile, stringResource(R.string.select_file))
        }
        return
    }
    var actionsExpanded by remember(document.entry.path) { mutableStateOf(false) }
    val markdown = isMarkdown(document.entry.name)
    // 图片 / PDF 走独立的预览分支（原先一律落到「二进制文件」那行字上）。
    // previewKind 是纯函数，判据与「会话附件」面板完全相同 —— 两处必须同一套规则。
    val kind = previewKind(document.entry.name, document.contentType)
    val binaryPreview = kind == PreviewKind.IMAGE || kind == PreviewKind.PDF
    var markdownPreview by remember(document.entry.path) { mutableStateOf(markdown) }
    var textScale by remember(document.entry.path) { mutableStateOf(1f) }
    val applyZoom: (Float) -> Unit = { factor ->
        textScale = (textScale * factor).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                }
            }
            Column(Modifier.weight(1f).padding(start = if (onBack == null) 8.dp else 0.dp)) {
                // 与列表里同一个理由：标题栏单行放不下长文件名，用户就看不出打开的是哪个。
                // 这里是标题，给 2 行已经够；路径那行仍在下方兜底。
                Text(
                    document.entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    document.entry.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (markdown && document.text != null) {
                IconButton(onClick = { markdownPreview = false }) {
                    Icon(
                        Icons.Outlined.Code,
                        stringResource(R.string.markdown_source),
                        tint = if (!markdownPreview) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { markdownPreview = true }) {
                    Icon(
                        Icons.Outlined.Visibility,
                        stringResource(R.string.markdown_preview),
                        tint = if (markdownPreview) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                onClick = viewModel::saveDocument,
                enabled = document.text != null && document.entry.writable &&
                    "files.write" in state.device?.scopes.orEmpty(),
            ) {
                Icon(Icons.Outlined.Save, stringResource(R.string.save))
            }
            Box {
                IconButton(onClick = { actionsExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.file_actions))
                }
                DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.download)) },
                        leadingIcon = { Icon(Icons.Outlined.Download, null) },
                        enabled = document.entry.kind == "file",
                        onClick = { actionsExpanded = false; onDownload() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.replace_file)) },
                        leadingIcon = { Icon(Icons.Outlined.FileOpen, null) },
                        enabled = document.entry.kind == "file" && "files.write" in state.device?.scopes.orEmpty(),
                        onClick = { actionsExpanded = false; onReplace() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename_move)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) },
                        enabled = "files.write" in state.device?.scopes.orEmpty(),
                        onClick = { actionsExpanded = false; onMove() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_to_trash)) },
                        leadingIcon = {
                            Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                        },
                        enabled = "files.delete" in state.device?.scopes.orEmpty(),
                        onClick = { actionsExpanded = false; viewModel.trashSelected() },
                    )
                }
            }
        }
        if (document.text != null) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { textScale = (textScale - TEXT_SCALE_STEP).coerceAtLeast(MIN_TEXT_SCALE) },
                    enabled = textScale > MIN_TEXT_SCALE,
                ) {
                    Icon(Icons.Outlined.ZoomOut, stringResource(R.string.zoom_out))
                }
                Text(
                    "${(textScale * 100).roundToInt()}%",
                    modifier = Modifier.width(56.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(
                    onClick = { textScale = 1f },
                    enabled = textScale != 1f,
                ) {
                    Icon(Icons.Outlined.RestartAlt, stringResource(R.string.reset_zoom))
                }
                IconButton(
                    onClick = { textScale = (textScale + TEXT_SCALE_STEP).coerceAtMost(MAX_TEXT_SCALE) },
                    enabled = textScale < MAX_TEXT_SCALE,
                ) {
                    Icon(Icons.Outlined.ZoomIn, stringResource(R.string.zoom_in))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (document.entry.kind == "directory") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.AutoMirrored.Outlined.InsertDriveFile, document.entry.kind)
            }
        } else if (binaryPreview) {
            // 与「会话附件」面板共用同一个组件：解码 / 翻页 / 缩放 / 失败提示只有一份实现。
            BinaryFilePreview(
                kind = kind,
                bytes = document.bytes,
                sizeBytes = document.entry.size,
                skippedForSize = document.skippedForSize,
                fallbackMessage = stringResource(R.string.binary_file),
                skippedHint = stringResource(R.string.preview_skipped_hint_download),
                onDownload = onDownload,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (document.text == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.AutoMirrored.Outlined.InsertDriveFile, stringResource(R.string.binary_file))
            }
        } else if (markdown && markdownPreview) {
            Column(
                Modifier.fillMaxSize()
                    .pinchToZoom(document.entry.path, applyZoom)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Markdown(
                    content = document.text,
                    typography = fileMarkdownTypography(textScale),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            NumberedTextEditor(
                value = document.text,
                onValueChange = viewModel::updateDocument,
                scale = textScale,
                modifier = Modifier.fillMaxSize().pinchToZoom(document.entry.path, applyZoom),
            )
        }
    }
}

@Composable
private fun NumberedTextEditor(
    value: String,
    onValueChange: (String) -> Unit,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val lineCount = value.count { it == '\n' } + 1
    val digits = lineCount.toString().length
    val gutterWidth = (digits * 9 * scale + 22).dp
    val codeStyle = scaledTextStyle(
        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        scale,
    )
    Row(modifier.background(MaterialTheme.colorScheme.surface)) {
        Box(
            Modifier.width(gutterWidth).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clipToBounds(),
        ) {
            Text(
                text = (1..lineCount).joinToString("\n"),
                modifier = Modifier.fillMaxWidth().offset { IntOffset(0, -vertical.value) }
                    .padding(top = 12.dp, end = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                style = codeStyle,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).fillMaxHeight()
                .horizontalScroll(horizontal).verticalScroll(vertical)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            textStyle = codeStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun fileMarkdownTypography(scale: Float) = markdownTypography(
    h1 = scaledTextStyle(MaterialTheme.typography.headlineMedium, scale),
    h2 = scaledTextStyle(MaterialTheme.typography.headlineSmall, scale),
    h3 = scaledTextStyle(MaterialTheme.typography.titleLarge, scale),
    h4 = scaledTextStyle(MaterialTheme.typography.titleMedium, scale),
    h5 = scaledTextStyle(MaterialTheme.typography.titleSmall, scale),
    h6 = scaledTextStyle(MaterialTheme.typography.titleSmall, scale),
    text = scaledTextStyle(MaterialTheme.typography.bodyMedium, scale),
    code = scaledTextStyle(MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), scale),
    inlineCode = scaledTextStyle(MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), scale),
    quote = scaledTextStyle(MaterialTheme.typography.bodyMedium, scale),
    paragraph = scaledTextStyle(MaterialTheme.typography.bodyMedium, scale),
    ordered = scaledTextStyle(MaterialTheme.typography.bodyMedium, scale),
    bullet = scaledTextStyle(MaterialTheme.typography.bodyMedium, scale),
    list = scaledTextStyle(MaterialTheme.typography.bodyMedium, scale),
    table = scaledTextStyle(MaterialTheme.typography.bodySmall, scale),
)

private fun scaledTextStyle(style: TextStyle, scale: Float): TextStyle = style.copy(
    fontSize = style.fontSize * scale,
    lineHeight = style.lineHeight * scale,
)

private fun Modifier.pinchToZoom(key: Any, onZoom: (Float) -> Unit): Modifier = pointerInput(key) {
    awaitEachGesture {
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                val factor = event.calculateZoom()
                if (factor.isFinite() && factor != 1f) onZoom(factor)
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

private const val MIN_TEXT_SCALE = 0.75f
private const val MAX_TEXT_SCALE = 2.5f
private const val TEXT_SCALE_STEP = 0.25f

private fun isMarkdown(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in
    setOf("md", "markdown", "mdown", "mkd")

@Composable
private fun NewEntryDialog(directory: Boolean, onClose: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(if (directory) R.string.new_folder else R.string.new_file)) },
        text = {
            OutlinedTextField(
                name,
                { name = it },
                label = { Text(stringResource(R.string.file_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun MoveDialog(document: OpenDocument?, viewModel: HarnessViewModel, onClose: () -> Unit) {
    var destination by remember(document?.entry?.path) { mutableStateOf(document?.entry?.path.orEmpty()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.rename_move)) },
        text = {
            OutlinedTextField(
                destination,
                { destination = it },
                label = { Text(stringResource(R.string.destination_path)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { viewModel.moveSelected(destination); onClose() },
                enabled = destination.isNotBlank(),
                shape = MaterialTheme.shapes.medium,
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun TrashDialog(state: HarnessState, viewModel: HarnessViewModel, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.trash)) },
        text = {
            if (state.trash.isEmpty()) {
                Text(stringResource(R.string.no_trash))
            } else {
                LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                    items(state.trash, key = { it.id }) { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                item.path,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(
                                onClick = { viewModel.restoreTrash(item.id) },
                                enabled = "files.delete" in state.device?.scopes.orEmpty(),
                            ) {
                                Icon(Icons.Outlined.RestoreFromTrash, stringResource(R.string.restore))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}

private fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getString(0)
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
}

private fun displaySize(context: Context, uri: Uri): Long {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
    }
    return -1L
}

private fun formatBytes(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KiB".format(size / 1024.0)
    else -> "%.1f MiB".format(size / 1024.0 / 1024.0)
}
