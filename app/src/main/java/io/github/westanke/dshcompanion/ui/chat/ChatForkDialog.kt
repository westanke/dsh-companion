package io.github.westanke.dshcompanion.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.westanke.dshcompanion.R

/**
 * 「从这条消息分叉」的确认框。
 *
 * 从 `ChatDetail.kt` 拆出（2026-10-17）：那个文件加上这一块后越过 800 行预算。
 * 拆出来的好处不只是行数 —— 分叉这件事的**文案**从此只有一处，将来要改
 * （比如真机验完发现内核的包含边界与我们写的不一样）不必在容器文件里翻找。
 *
 * 为什么要确认：分叉会新建会话并**切换过去**，属于「会改变你现在看什么」的动作，
 * 不该一次误触就发生。
 *
 * 文案刻意**不承诺内核的包含边界**：OpenAPI 只说 `at the supplied event sequence`，
 * 「保留到这条为止」是我们对常见语义的表述，真机验收前不该写得更死。
 */
@Composable
internal fun ChatForkDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fork_confirm_title)) },
        text = { Text(stringResource(R.string.fork_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.fork_confirm_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
