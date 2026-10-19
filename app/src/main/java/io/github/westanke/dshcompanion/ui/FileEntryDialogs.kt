package io.github.westanke.dshcompanion.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.HarnessViewModel
import io.github.westanke.dshcompanion.OpenDocument
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.moveSelected
import io.github.westanke.dshcompanion.restoreTrash

/**
 * 文件页的三个输入型对话框：新建、移动/改名、回收站。
 *
 * **只是搬走，行为一个字没改**（`FilesScreen.kt` 原样那三段）。
 * 搬的理由纯粹是行数预算：`FilesScreen` 已经贴着 800 行线，
 * 而这三个对话框与浏览/编辑区不是同一块职责。
 */
@Composable
internal fun NewEntryDialog(directory: Boolean, onClose: () -> Unit, onCreate: (String) -> Unit) {
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
internal fun MoveDialog(document: OpenDocument?, viewModel: HarnessViewModel, onClose: () -> Unit) {
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
internal fun TrashDialog(state: HarnessState, viewModel: HarnessViewModel, onClose: () -> Unit) {
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

