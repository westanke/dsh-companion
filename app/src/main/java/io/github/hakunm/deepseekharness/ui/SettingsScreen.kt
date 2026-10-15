package io.github.hakunm.deepseekharness.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hakunm.deepseekharness.HarnessState
import io.github.hakunm.deepseekharness.HarnessViewModel
import io.github.hakunm.deepseekharness.BuildConfig
import io.github.hakunm.deepseekharness.R
import io.github.hakunm.deepseekharness.data.BusySendMode
import io.github.hakunm.deepseekharness.disconnect

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: HarnessState, viewModel: HarnessViewModel) {
    val scopes = state.device?.scopes.orEmpty()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.settings_subtitle),
                modifier = Modifier.padding(top = 4.dp, bottom = 26.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            SettingsHeading(stringResource(R.string.connection))
            val activeHost = state.hosts.firstOrNull { it.id == state.activeHostId }
            SettingRow(
                Icons.Outlined.CloudDone,
                activeHost?.displayName ?: stringResource(R.string.connection),
                state.endpoint,
            ) {
                StatusLabel(
                    if (state.eventsConnected) stringResource(R.string.online) else stringResource(R.string.offline),
                    state.eventsConnected,
                )
            }
            // 把当前这台电脑的全部地址列出来，并标出此刻实际走的是哪一个。
            // 用户换了网络（局域网 → 虚拟网）之后最想知道的就是这件事。
            activeHost?.let { host ->
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    host.endpoints.forEach { endpoint ->
                        val inUse = endpoint.id == state.activeEndpointId
                        Surface(
                            shape = CircleShape,
                            color = if (inUse) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                        ) {
                            Text(
                                buildString {
                                    append(endpointKindLabel(endpoint.kind))
                                    append(" · ")
                                    append(endpoint.baseUrl)
                                    if (inUse) {
                                        append(" · ")
                                        append(stringResource(R.string.active))
                                    }
                                },
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            if (state.endpoint.startsWith("http://")) {
                WarningBanner(stringResource(R.string.http_warning), Modifier.padding(top = 8.dp, bottom = 22.dp))
            } else Spacer(Modifier.height(22.dp))

            SettingsHeading(stringResource(R.string.device))
            SettingRow(Icons.Outlined.PhoneAndroid, state.device?.name.orEmpty(), state.device?.id.orEmpty())
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                scopes.forEach { scope ->
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Text(scopeLabel(scope), Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            SettingsHeading(stringResource(R.string.language))
            val english = AppCompatDelegate.getApplicationLocales().toLanguageTags().startsWith("en")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                SegmentedButton(
                    selected = !english,
                    onClick = { if (english) toggleLanguage() },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { Icon(Icons.Outlined.Language, null) },
                ) { Text(stringResource(R.string.chinese)) }
                SegmentedButton(
                    selected = english,
                    onClick = { if (!english) toggleLanguage() },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { Icon(Icons.Outlined.Language, null) },
                ) { Text(stringResource(R.string.english)) }
            }

            // 智能体运行时的发送行为。放在这里是因为它不是「聊天页的临时开关」，
            // 而是一条会影响每次输入的长期习惯，用户需要能一眼看到自己设成了什么。
            SettingsHeading(stringResource(R.string.busy_send_mode))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.busySendMode == BusySendMode.QUEUE,
                    onClick = { viewModel.setBusySendMode(BusySendMode.QUEUE) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null) },
                ) { Text(stringResource(R.string.busy_send_mode_queue)) }
                SegmentedButton(
                    selected = state.busySendMode == BusySendMode.STEER,
                    onClick = { viewModel.setBusySendMode(BusySendMode.STEER) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { Icon(Icons.Outlined.SubdirectoryArrowRight, null) },
                ) { Text(stringResource(R.string.busy_send_mode_steer)) }
            }
            Text(
                stringResource(
                    if (state.busySendMode == BusySendMode.QUEUE) {
                        R.string.busy_send_mode_queue_desc
                    } else {
                        R.string.busy_send_mode_steer_desc
                    },
                ),
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.busy_send_mode_subtitle),
                modifier = Modifier.padding(top = 4.dp, bottom = 28.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )

            // dsh-ui 诊断开关。放在「关于」正上方，因为它服务的正是「关于版本」那一类问题：
            // 用户报「卡片没出来」时，让他自己打开就能拿到证据，不必等我们出新包。
            // 默认关 —— 诊断行是灰字噪音，不该打扰正常使用。
            SettingsHeading(stringResource(R.string.web_chat_heading))
            SwitchRow(
                title = stringResource(R.string.web_chat_title),
                description = stringResource(R.string.web_chat_desc),
                // 读 state 而不是 viewModel：读 SharedPreferences 不是 Compose 状态，
                // 改了不会触发重组 —— 那正是「点了开关不动、得再点别的才跳」的根因。
                checked = state.useWebChat,
                onCheckedChange = viewModel::setUseWebChat,
            )
            // 上下文注入块：默认不显示。它们是系统每轮塞进来的（运行上下文、
            // 时间上下文、后台任务通知…），手机端没人看，却会把正文挤得七零八落。
            SwitchRow(
                title = stringResource(R.string.show_context_title),
                description = stringResource(R.string.show_context_desc),
                checked = state.showContext,
                onCheckedChange = viewModel::setShowContext,
            )
            Text(
                stringResource(R.string.web_chat_footnote),
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )

            SettingsHeading(stringResource(R.string.gen_ui_debug_heading))
            SwitchRow(
                title = stringResource(R.string.gen_ui_debug_title),
                description = stringResource(R.string.gen_ui_debug_desc),
                checked = state.genUiDebug,
                onCheckedChange = viewModel::setGenUiDebug,
            )
            Text(
                stringResource(R.string.gen_ui_debug_footnote),
                modifier = Modifier.padding(top = 4.dp, bottom = 28.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )

            SettingsHeading(stringResource(R.string.about))
            SettingRow(
                Icons.Outlined.Code,
                stringResource(R.string.author),
                stringResource(R.string.author_value),
            )
            SettingRow(
                Icons.Outlined.CheckCircle,
                stringResource(R.string.version),
                "v${BuildConfig.VERSION_NAME}",
                Modifier.padding(bottom = 18.dp),
            )

            OutlinedButton(
                onClick = viewModel::disconnect,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shape = CircleShape,
            ) {
                Icon(Icons.Outlined.DeleteForever, null)
                Text(stringResource(R.string.disconnect), modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsHeading(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
}

/**
 * 一行「标题 + 说明 + 开关」。
 *
 * 抽成组件是为了**间距统一**。此前三个开关各写各的 Row：
 * 一个 `padding(bottom = 4.dp)`、一个 `padding(vertical = 8.dp)` ——
 * 在页面上就是「中间那个开关上下各多出一截」，三个开关的呼吸感不一致，
 * 看着像没对齐的残次品。
 *
 * 其中一处还多带了一句没有作用的 `Arrangement.SpaceBetween`：
 * Column 已经 weight(1f)，开关本来就被推到最右，再加 SpaceBetween 只是噪音。
 *
 * 现在三处共用这一个，间距只有一处定义，不会再走偏。
 */
@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 设备授权 scope 的药丸标签：跟着界面语言走。
 *
 * scope 本身是协议级标识（`chat.read` 这类），服务端只认它，但**给人看的**
 * 不该是协议串 —— 中文界面露一串英文小点，用户得自己猜 `settings.read`
 * 是「能看设置」还是「能改设置」。
 *
 * 认不出的 scope 保留原文：服务端将来新增权限时，与其编一个名字把人带偏，
 * 不如让它原样露出来，至少能和文档对得上（与错误码表同一原则）。
 */
@Composable
private fun scopeLabel(scope: String): String = when (scope) {
    "chat.read" -> stringResource(R.string.scope_chat_read)
    "chat.write" -> stringResource(R.string.scope_chat_write)
    "files.read" -> stringResource(R.string.scope_files_read)
    "files.write" -> stringResource(R.string.scope_files_write)
    "files.delete" -> stringResource(R.string.scope_files_delete)
    "settings.read" -> stringResource(R.string.scope_settings_read)
    "settings.write" -> stringResource(R.string.scope_settings_write)
    else -> scope
}
