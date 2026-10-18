package io.github.westanke.dshcompanion.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.ui.chat.toast

/*
 * ============================================================================
 * 连接页的「导入配置」面板与「从文件读取」选择器
 * ============================================================================
 *
 * 从 `ConnectScreen.kt` 拆出。拆的理由只有一条：行数预算
 * （`AGENTS.md` §4.2 的 800 行，由 `ArchitectureGuardTest` 第 1 条守着）。
 * `ConnectScreen.kt` 本来就贴着线，「从文件读取」加进来必然越界，
 * 而为一个功能去 `TECH-DEBT.md` 立债务账是把诊断码和业务债混为一谈。
 *
 * 搬过来的两块**逐字未改**，只是换了所在文件：
 * 1. [ImportConfigPanel] —— 导入配置的折叠面板（输入框 + 安全提示 + 两个按钮）；
 * 2. [rememberConfigFilePicker] —— 「从文件读取」的 SAF 选择器。
 */


/**
 * 配置导入面板 —— 取代扫码的主路径。
 *
 * 与手动配对同款：平时只显示一行入口，点开才铺表单（默认收起，用户要那个才点）。
 *
 * 展开区里的文案刻意把「为什么要有这个」说清楚：用户看到「粘贴一段文本」时第一反应是
 * 「为什么不扫码更简单」，必须当场回答这个疑问，否则他还是会觉得别扭。
 */
@Composable
internal fun ImportConfigPanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    text: String,
    onTextChange: (String) -> Unit,
    enabled: Boolean,
    onImport: () -> Unit,
    /** 从文件读一段配置文本进来（SAF，零权限）。读到的内容直接填进上面的输入框。 */
    onPickFile: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ConnectionEntryRow(
            expanded = expanded,
            icon = Icons.Outlined.CloudDownload,
            title = stringResource(R.string.import_from_computer),
            subtitle = stringResource(R.string.import_entry_hint),
            onToggle = onToggle,
        )
        AnimatedVisibility(visible = expanded, enter = fadeIn() + slideInVertically { it / 3 }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.import_explanation),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.import_hint)) },
                        minLines = 2,
                        maxLines = 5,
                        shape = MaterialTheme.shapes.medium,
                    )
                    WarningBanner(stringResource(R.string.import_security_note))
                    // 「从文件读取」：绕开软键盘粘贴长文本这一步。
                    //
                    // 存在的直接理由是一次真机卡壳：464 字符的 `DSH1:` 配置文本
                    // 在 vivo 输入法上**粘不进去** —— `input text` 被截断、
                    // 长按弹出的「粘贴」不响应、`KEYCODE_PASTE` 无效
                    // （见 `PITFALLS.md` §vivo 输入法拒绝外部文本注入）。
                    // 而这条路径本该是「人已出门、电脑不在旁」时才用的主路径，
                    // 却要求用户先有一块能粘贴的输入环境，本身就是矛盾的。
                    //
                    // 走 SAF（`OpenDocument`）而不是直接读绝对路径：**零权限**，
                    // 与「文件」页上传、会话里带附件用的是同一套机制。
                    OutlinedButton(
                        onClick = onPickFile,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(18.dp))
                        Text(
                            stringResource(R.string.import_from_file),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Button(
                        onClick = onImport,
                        enabled = enabled && text.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Outlined.Link, null)
                        Text(stringResource(R.string.import_and_connect), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/**
 * 「从文件读取」配置文本的 SAF 选择器。
 *
 * ## 为什么需要它
 *
 * 一次真机卡壳：464 字符的 `DSH1:` 配置文本在 vivo 输入法上**粘不进去** ——
 * `input text` 被截断、长按弹出的「粘贴」菜单不响应外部点击、
 * `KEYCODE_PASTE` 无效、`KEYCODE_DEL` 被吞（见 `PITFALLS.md`
 * §vivo 输入法拒绝外部文本注入）。而「导入配置」本该是「人已出门、
 * 电脑不在旁」时才用的主路径，却要求用户先有一块能粘贴的输入环境，
 * 本身就是矛盾的。
 *
 * ## 两个设计取舍
 *
 * 1. **先填进输入框，不直接连接**：用户能亲眼看到「读进来的是这一串」再点导入。
 *    一段 Base64 读错了（选成了别的文件），看得见才拦得住。
 * 2. **走 SAF（`OpenDocument`）而不是绝对路径**：零权限，与「文件」页上传、
 *    会话里带附件用的是同一套机制。申请 `READ_EXTERNAL_STORAGE` 只为这一个
 *    按钮不值当，而且 Android 13 起它本来也拿不到文件。
 *
 * 读失败只提示、不崩：选到非文本文件、或读权限被收，都走同一句文案。
 */
@Composable
internal fun rememberConfigFilePicker(
    context: android.content.Context,
    onPicked: (String) -> Unit,
) = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    val text = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.decodeToString()
    }.getOrNull()?.trim()
    if (text.isNullOrBlank()) {
        toast(context, context.getString(R.string.import_file_unreadable))
    } else {
        onPicked(text)
    }
}
