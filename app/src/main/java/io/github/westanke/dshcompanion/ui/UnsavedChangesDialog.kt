package io.github.westanke.dshcompanion.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.westanke.dshcompanion.R

/**
 * 「有未保存的改动」的三个出口：留下、丢弃、保存。
 *
 * ## 为什么要有它
 *
 * v2.2.44 真机验收（缺陷 W3）：在文本编辑器里改了字，顶栏「保存」按钮**没有任何脏状态指示**，
 * 直接点返回也**没有任何提示**就退回目录 —— 改动静默丢失。
 *
 * 一个不报错的丢数据，比一次报错严重得多：用户以为存上了，下次打开还是旧内容，
 * 而他从头到尾没收到过任何信号。
 *
 * 判定放在数据层（`OpenDocument.hasUnsavedChanges`，与打开时的原文逐字比），
 * 这里只负责呈现与出口 —— 三条路径各自的**后果**必须写清楚，界面才不会替用户做决定。
 */
@Composable
internal fun UnsavedChangesDialog(
    onStay: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
    canSave: Boolean,
) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text(stringResource(R.string.unsaved_changes_title)) },
        text = { Text(stringResource(R.string.unsaved_changes_message)) },
        confirmButton = {
            // 存不了的时候（文件不可写 / 没有 files.write scope）不亮这个键：
            // 亮着点了必然失败，而用户还以为已经存上了 —— 那是把「没存成」说成「存了」。
            Button(onClick = onSave, enabled = canSave, shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onStay) { Text(stringResource(R.string.close)) }
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.discard_changes), color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}
