package io.github.westanke.dshcompanion.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Description
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.HarnessViewModel
import io.github.westanke.dshcompanion.BuildConfig
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.BusySendMode
import io.github.westanke.dshcompanion.data.InterfaceStyle
import io.github.westanke.dshcompanion.debug.DiagLog
import io.github.westanke.dshcompanion.ui.chat.copyText
import io.github.westanke.dshcompanion.ui.chat.toast
import io.github.westanke.dshcompanion.data.missingRequiredCapabilities
import io.github.westanke.dshcompanion.disconnect
import java.util.Locale

/**
 * 字号倍数的可选区间与档位数。
 *
 * 与 `normalizeFontScale` 的区间**必须一致**：那里是兜底（防非法值），
 * 这里是界面（防用户拖出界）。两处若各写一套，界面就会允许存下一个
 * 偏好层立刻改写的值 —— 表现是「拖到底再进来，滑块自己弹回去一点」。
 */
private val FontScaleRange = 0.8f..1.5f

/** 6 个中间档 → 步进 0.1（0.8/0.9/…/1.5 共 8 个落点）。 */
private const val FontScaleSteps = 6

/** 固定用 US locale 格式化：某些区域用逗号作小数点，「1,2×」会看着像笔误。 */
private fun formatFontScale(value: Float): String = String.format(Locale.US, "%.1f", value)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: HarnessState, viewModel: HarnessViewModel) {
    val scopes = state.device?.scopes.orEmpty()
    val context = LocalContext.current
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

            // 服务端能力位（新版插件才会带）。**读到了且缺关键能力**才提示：
            // 旧插件不给清单 = 未知，此时弹警告只会教用户忽略警告。
            //
            // 这条提示补的是一个真实痛点：审批/问答卡片不出现时，手机端看起来与
            // 「本来就没有待审批」一模一样，用户只能干等（见 PITFALLS §bridge 版本基线）。
            val missingCapabilities = if (state.bridgeCapabilities.isEmpty()) {
                emptyList()
            } else {
                missingRequiredCapabilities(state.bridgeCapabilities)
            }
            if (missingCapabilities.isNotEmpty()) {
                WarningBanner(
                    stringResource(R.string.bridge_outdated_warning, state.bridgeVersion ?: "?"),
                    Modifier.padding(top = 8.dp, bottom = 22.dp),
                )
            }

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
            // 语言三档：跟随系统 / 中文 / English。
            //
            // 「跟随系统」在这里必须存在：此前只有中英二选一，用户想跟着系统走
            // 就只能每次进设置手动切。判据用 LocaleListCompat 的 tag 前缀 ——
            // 空 tag 表示未指定（= 跟随系统），这正是 AppCompatDelegate 的语义。
            val languageTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            val languageIndex = when {
                languageTag.startsWith("en") -> 2
                languageTag.startsWith("zh") -> 1
                else -> 0
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                SegmentedButton(
                    selected = languageIndex == 0,
                    onClick = {
                        if (languageIndex != 0) {
                            viewModel.markLanguageChosen()
                            setAppLanguage(null)
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(0, 3),
                    icon = { Icon(Icons.Outlined.Language, null) },
                ) { Text(stringResource(R.string.language_follow_system)) }
                SegmentedButton(
                    selected = languageIndex == 1,
                    onClick = {
                        if (languageIndex != 1) {
                            viewModel.markLanguageChosen()
                            setAppLanguage("zh-CN")
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(1, 3),
                    icon = { Icon(Icons.Outlined.Language, null) },
                ) { Text(stringResource(R.string.chinese)) }
                SegmentedButton(
                    selected = languageIndex == 2,
                    onClick = {
                        if (languageIndex != 2) {
                            viewModel.markLanguageChosen()
                            setAppLanguage("en")
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(2, 3),
                    icon = { Icon(Icons.Outlined.Language, null) },
                ) { Text(stringResource(R.string.english)) }
            }

            // 外观：界面风格 + 字号。两项都会被 Theme 在最外层消费，
            // 所以这里改完立刻全 App 生效，不需要重启。
            SettingsHeading(stringResource(R.string.appearance))
            Text(
                stringResource(R.string.interface_style),
                modifier = Modifier.padding(bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                val styles = listOf(
                    InterfaceStyle.SYSTEM to R.string.interface_style_system,
                    InterfaceStyle.LIGHT to R.string.interface_style_light,
                    InterfaceStyle.DARK to R.string.interface_style_dark,
                )
                styles.forEachIndexed { index, (style, label) ->
                    SegmentedButton(
                        selected = state.interfaceStyle == style,
                        onClick = { viewModel.setInterfaceStyle(style) },
                        shape = SegmentedButtonDefaults.itemShape(index, styles.size),
                    ) { Text(stringResource(label)) }
                }
            }
            SwitchRow(
                title = stringResource(R.string.font_follows_system),
                description = stringResource(R.string.font_follows_system_desc),
                checked = state.fontFollowsSystem,
                onCheckedChange = viewModel::setFontFollowsSystem,
            )
            Text(
                stringResource(R.string.font_scale),
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                // coerceIn 是防御性的：Slider 的 value 超出 valueRange 会直接抛异常，
                // 而偏好里的旧值来自历史版本（那时区间可能是另一套）。
                value = state.fontScale.coerceIn(FontScaleRange.start, FontScaleRange.endInclusive),
                onValueChange = viewModel::setFontScale,
                valueRange = FontScaleRange,
                steps = FontScaleSteps,
                enabled = !state.fontFollowsSystem,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.font_scale_value, formatFontScale(state.fontScale)),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    stringResource(R.string.font_scale_desc),
                    modifier = Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(28.dp))

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

            // ── 诊断日志（窗口 / 会话操作 / 屏幕变化）──────────────────────
            //
            // 与上面那个 dsh-ui 诊断行是两件事：那个只管「卡片为什么没渲染」，
            // 这个记的是**操作过程本身** —— 点哪个会话、走原生页还是网页页、
            // 落底滚动推了多少拍、熄亮屏与窗口尺寸何时变化。
            //
            // 为什么值得单独一个开关：上一轮的教训是「探针只能在 debug 包里活着」，
            // 每次要重新出包、重装、再让用户复现一遍。做成设置项之后，
            // 装上 → 开开关 → 复现 → 读日志，四步完事，后期任何一次排查都不用再走一遍构建。
            //
            // 默认关：开着的时候右上角会有一块诊断浮层，正常使用不该看见它。
            SettingsHeading(stringResource(R.string.diag_log_heading))
            SwitchRow(
                title = stringResource(R.string.diag_log_title),
                description = stringResource(R.string.diag_log_desc),
                // 读 state 而不是 viewModel —— 与上面两个开关同一个理由：
                // SharedPreferences 不是 Compose 状态，直接读会「点了不动」。
                checked = state.diagLog,
                onCheckedChange = viewModel::setDiagLog,
            )
            Text(
                stringResource(R.string.diag_log_footnote),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            // 「读日志」入口：把已写入的日志整段复制到剪贴板。
            //
            // 为什么要这个按钮：日志的主通道是 `filesDir/diag.log`，而 vivo 真机上
            // logcat 抓不到自有 App 的输出（PITFALLS.md 实测）。有了这个按钮，
            // 用户不用连电脑、不用 adb，粘贴出来就是我们能读的完整现场。
            //
            // 用 `Modifier.clickable` 而不是给 SettingRow 加 onClick 参数：
            // 那个组件是全项目共用的，为一个诊断入口改它的签名不划算；
            // 包一层 clickable 就够了，而且语义上「这一行可点」也表达得清楚。
            // 文案在组合期预取：点击回调里再调 `context.getString` 会触发
            // `LocalContextGetResourceValueCall` lint error —— 它拿不到重组期的配置变化，
            // 切语言/换主题时不会跟着刷新。预取成局部 val 既过门禁也语义正确。
            val diagReadLabel = stringResource(R.string.diag_log_read)
            val diagEmptyText = stringResource(R.string.diag_log_empty)
            val diagCopiedText = stringResource(R.string.diag_log_copied)
            SettingRow(
                Icons.Outlined.Description,
                diagReadLabel,
                stringResource(R.string.diag_log_read_desc),
                modifier = Modifier
                    .clickable {
                        val text = DiagLog.exportText()
                        if (text.isBlank()) {
                            toast(context, diagEmptyText)
                        } else {
                            copyText(context, diagReadLabel, text)
                            toast(context, diagCopiedText)
                        }
                    }
                    .padding(bottom = 28.dp),
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

            // ── 应用内更新检查 ────────────────────────────────────────────
            // 入口紧贴版本行：它的语义就是「我装的这个版本够不够新」，用户想知道这件事时
            // 第一眼就该在版本旁边看到它。更新源按界面语言分流：中文→Gitee 主站，
            // 英文/其他→GitHub 镜像。
            UpdateCheckSection(context)

            // 构建水印：版本号在多次重打之间不变，所以「改了没生效」的争议一直说不清。
            // 把 git 短哈希与构建时刻摆在版本行下面，用户一眼就能确认「我装的是哪一次构建」。
            Text(
                // 只显示 git 短哈希：它在多次重打之间必然不同，时间戳在 defaultConfig 里取不到，不为它引依赖。
                "构建 ${BuildConfig.BUILD_STAMP}",
                modifier = Modifier.padding(start = 52.dp, bottom = 18.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
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
