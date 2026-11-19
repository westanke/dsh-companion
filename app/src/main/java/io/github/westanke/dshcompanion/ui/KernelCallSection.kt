package io.github.westanke.dshcompanion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.ws.KernelCallResultFrame
import io.github.westanke.dshcompanion.data.ws.KernelEndpoint
import io.github.westanke.dshcompanion.ui.theme.DshSpacing
import io.github.westanke.dshcompanion.ui.theme.DshTypography
import io.github.westanke.dshcompanion.ui.theme.LocalDshColors

/**
 * 设置页里的「内核调用」入口（桥侧 issue #26 阶段 5）。
 *
 * **入口的隐藏方式是「整段不组合」**：调用方 [io.github.westanke.dshcompanion.ui.SettingsScreen]
 * 用 `supportsKernelCall(state.bridgeCapabilities)` 包住这个 Composable —— 缺能力位时
 * 连标题和卡片都不会出现（不是禁用、不是置灰）。老桥不认识 `dshrb-v1` 子协议，
 * 永远不会有 `kernel-call-v1`，所以这里不会有「点进去看报错」的假入口。
 *
 * 目录来自桥鉴权后**自动推一次**的 `kernel-endpoints` 快照（白名单 ∩ 内核目录，
 * 不是内核全量端点）；空列表是合法的中间态（推送还没到），界面照实说「还没收到」。
 *
 * args 文本由用户自己填，键名以每条端点下的 `wires` 为准 —— 客户端**不硬编码**键名。
 */
@Composable
internal fun KernelCallSection(
    state: HarnessState,
    onCall: (endpoint: String, argsText: String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val dsh = LocalDshColors.current

    SettingsSection(stringResource(R.string.kernel_call_heading)) {
        SettingsInfoRow(
            icon = Icons.Outlined.Code,
            title = stringResource(R.string.kernel_call_title),
            subtitle = stringResource(R.string.kernel_call_desc),
            onClick = { open = true },
        )
        Text(
            stringResource(R.string.kernel_call_footnote),
            modifier = Modifier.padding(
                start = DshSpacing.settingsCardPadH,
                end = DshSpacing.settingsCardPadH,
                bottom = DshSpacing.settingsCardPadV,
            ),
            color = dsh.labelTertiary,
            style = DshTypography.caption,
        )
    }

    if (open) {
        KernelCallDialog(state = state, onCall = onCall, onDismiss = { open = false })
    }
}

/**
 * 目录 + 调用弹层。**不新造导航**：它从设置页本地弹出，关掉就回到设置页，
 * 与 `PluginsScreen` / `ChatForkDialog` 的弹层写法一致。
 */
@Composable
private fun KernelCallDialog(
    state: HarnessState,
    onCall: (endpoint: String, argsText: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var argsText by remember { mutableStateOf("{}") }
    val dsh = LocalDshColors.current
    val scroll = rememberScrollState()

    // 结果/错误落在列表上方，但用户点「调用」时通常已经滚到某个端点附近 ——
    // 不拉回顶部等于还是看不见（真机实测过）。结果一变就回到顶部。
    LaunchedEffect(state.kernelCallResult, state.kernelCallError) {
        if (state.kernelCallResult != null || state.kernelCallError != null) {
            scroll.animateScrollTo(0)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kernel_call_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(scroll),
            ) {
                Text(
                    stringResource(R.string.kernel_call_args_hint),
                    style = DshTypography.caption,
                    color = dsh.labelTertiary,
                )
                Spacer(Modifier.height(DshSpacing.settingsChipGap))
                OutlinedTextField(
                    value = argsText,
                    onValueChange = { argsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.kernel_call_args_label)) },
                    textStyle = DshTypography.inlineCode,
                    singleLine = false,
                )
                Spacer(Modifier.height(DshSpacing.blockGap))

                // 结果放在列表**上方**（真机实测：放列表末尾等于看不见 —— 白名单 23 条
                // 端点，用户点完「调用」以为没反应，其实结果在几千像素之外）。
                state.kernelCallInFlight?.let { endpoint ->
                    Text(
                        stringResource(R.string.kernel_call_calling, endpoint),
                        style = DshTypography.small,
                        color = dsh.labelSecondary,
                    )
                    Spacer(Modifier.height(DshSpacing.settingsChipGap))
                }
                state.kernelCallError?.let { message ->
                    Text(
                        message,
                        style = DshTypography.small,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(DshSpacing.settingsChipGap))
                }
                state.kernelCallResult?.let { result ->
                    KernelResultBlock(result)
                    Spacer(Modifier.height(DshSpacing.blockGap))
                }

                if (state.kernelEndpoints.isEmpty()) {
                    Text(
                        stringResource(R.string.kernel_call_empty),
                        style = DshTypography.small,
                        color = dsh.labelSecondary,
                    )
                } else {
                    state.kernelEndpoints.forEach { endpoint ->
                        EndpointCard(
                            endpoint = endpoint,
                            busy = state.kernelCallInFlight != null,
                            onCall = { onCall(endpoint.endpoint, argsText) },
                        )
                        Spacer(Modifier.height(DshSpacing.settingsSectionGap))
                    }
                }

            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.kernel_call_close))
            }
        },
    )
}

/** 一条端点：描述符 + 摘要 + scope/stream + wires + 调用按钮。 */
@Composable
private fun EndpointCard(
    endpoint: KernelEndpoint,
    busy: Boolean,
    onCall: () -> Unit,
) {
    val dsh = LocalDshColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(endpoint.endpoint, style = DshTypography.inlineCode)
        if (endpoint.summary.isNotBlank()) {
            Text(
                endpoint.summary,
                style = DshTypography.small,
                color = dsh.labelSecondary,
            )
        }
        val meta = buildList {
            if (endpoint.scope.isNotBlank()) add(endpoint.scope)
            if (endpoint.stream) add("stream")
        }.joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(meta, style = DshTypography.caption, color = dsh.labelTertiary)
        }
        if (endpoint.wires.isNotEmpty()) {
            Text(
                stringResource(R.string.kernel_call_wires, endpoint.wires.joinToString(", ")),
                style = DshTypography.caption,
                color = dsh.labelTertiary,
            )
        }
        Spacer(Modifier.height(DshSpacing.settingsChipGap))
        OutlinedButton(onClick = onCall, enabled = !busy) {
            Text(stringResource(R.string.kernel_call_invoke))
        }
    }
}

/** 最近一次结果。`ok:false` 是**结果**不是传输失败；错误码原样展示。 */
@Composable
private fun KernelResultBlock(result: KernelCallResultFrame) {
    val dsh = LocalDshColors.current
    Spacer(Modifier.height(DshSpacing.blockGap))
    if (result.ok) {
        Text(
            stringResource(R.string.kernel_call_result_ok, result.callId),
            style = DshTypography.smallStrong,
            color = dsh.labelSecondary,
        )
    } else {
        val detail = result.error?.let { error ->
            if (error.message.isBlank()) error.code else "${error.code}: ${error.message}"
        } ?: stringResource(R.string.kernel_call_result_failed_unknown)
        Text(
            stringResource(R.string.kernel_call_result_failed, detail),
            style = DshTypography.smallStrong,
            color = MaterialTheme.colorScheme.error,
        )
    }
    val valueText = result.value?.toString().orEmpty()
    if (valueText.isNotEmpty()) {
        Spacer(Modifier.height(DshSpacing.settingsChipGap))
        Text(
            // 流式端点默认最多 64 帧；一次全渲染会把弹层撑爆，诊断界面截断即可。
            if (valueText.length > 4000) valueText.take(4000) + "\n…（已截断）" else valueText,
            style = DshTypography.codeBlock,
            color = dsh.labelSecondary,
        )
    }
}
