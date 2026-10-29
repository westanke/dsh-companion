package io.github.westanke.dshcompanion.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.westanke.dshcompanion.HarnessState
import io.github.westanke.dshcompanion.HarnessViewModel
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.cancelSpeechTranscription
import io.github.westanke.dshcompanion.consumeSpeechText
import io.github.westanke.dshcompanion.data.supportsSpeech
import io.github.westanke.dshcompanion.loadSpeechCatalog
import io.github.westanke.dshcompanion.retrySpeechTranscription
import io.github.westanke.dshcompanion.transcribeSpeech
import kotlinx.coroutines.delay

/**
 * 官方语音输入的界面层：麦克风按钮 + 一条状态条（录音中 / 转写中 / 失败可重试）。
 *
 * ## 三种「用不了」必须分别处理（用户会立刻遇到的就是这三种）
 *
 * 1. **路由不存在**（老插件）：能力位没声明 → 按钮**根本不渲染**。这正是 `PARITY.md` 里
 *    「新 App 连老桥」那条：不显示比显示了点进去报错好。
 * 2. **路由在、服务没有**（内核没注入语音服务）：catalog 的 `available=false` → 按钮变灰，
 *    点它弹一句 [stringResource] 的 reason —— 界面不该只给一个"按不动"的按钮。
 * 3. **权限不给**：拒绝 → 提示并保留再次点按的机会；**永久拒绝** → 弹对话框引导去系统设置。
 *    这两种在 Android 上是不同状态（`shouldShowRequestPermissionRationale` 的差别），
 *    混成一句「权限被拒绝」会让用户找不到出路。
 *
 * ## 为什么录音状态放在 Compose 而不是 VM
 *
 * 麦克风是设备资源：谁申请、谁释放、转屏了要不要继续，答案只在界面生命周期里。
 * VM 只接收「一段 WAV」这个纯数据接口（见 `HarnessSpeechViewModel.transcribeSpeech`）。
 */

/** 录音需要的一点点界面状态；由 `Composer` 记住后分别交给按钮与状态条。 */
internal class SpeechUiState {
    val recorder = SpeechRecorder()

    /** 正在录（麦克风已打开）。 */
    var recording by mutableStateOf(false)

    /** 录音已进行的毫秒数，只用于显示计时与超时自停。 */
    var elapsedMs by mutableIntStateOf(0)

    /** 本次会话里权限的结果；`null` = 还没问过。 */
    var permission by mutableStateOf<PermissionState?>(null)

    /** 需要弹「去设置」的那种永久拒绝。 */
    var needsSettings by mutableStateOf(false)

    /** 用户点了「取消」时置位：`stopAndTranscribe` 看到它就不发请求。 */
    var discardNext by mutableStateOf(false)
}

internal enum class PermissionState { GRANTED, DENIED, PERMANENTLY_DENIED }

@Composable
internal fun rememberSpeechUiState(): SpeechUiState {
    val state = remember { SpeechUiState() }
    // 离开会话（转屏/退到别的页）一律释放麦克风：不放的话下次进来是「另一个 App 占着麦克风」。
    DisposableEffect(state) {
        onDispose { state.recorder.cancel() }
    }
    return state
}

/**
 * 麦克风按钮。[speech] 由 `Composer` 持有，按钮只负责「开始/停止/取消转写」。
 *
 * 交互：**点一下开始，再点一下结束并转写**（而不是按住说话）——
 * 按住说话在权限弹窗、来电、转屏这些打断下很难自洽，而这里也没有"微信式"的对讲语义要维护。
 */
@Composable
internal fun SpeechInputButton(
    state: HarnessState,
    viewModel: HarnessViewModel,
    speech: SpeechUiState,
) {
    if (!supportsSpeech(state.bridgeCapabilities)) return
    val context = LocalContext.current
    val speechState = state.speech
    val catalog = speechState.catalog
    val available = catalog?.available == true
    val transcribing = speechState.transcribing
    val recording = speech.recording
    // 文案在组合期取出，不要在事件回调里 `context.getString(...)`：
    // lint 的 LocalContextGetResourceValueCall 会判 error（配置变化后取到的可能是旧值），
    // 而且这里本来就能用 `stringResource` —— 事件里只做动作，不做资源查询。
    val permissionDeniedText = stringResource(R.string.speech_permission_denied)
    val permissionPermanentlyDeniedText = stringResource(R.string.speech_permission_permanently_denied)
    val unavailablePlainText = stringResource(R.string.speech_unavailable_plain)
    val unavailableReason = catalog?.reason?.takeIf { it.isNotBlank() }
    val unavailableText = if (unavailableReason != null) {
        stringResource(R.string.speech_unavailable, unavailableReason)
    } else {
        unavailablePlainText
    }

    // 目录只取一次：它决定「按钮能不能按」，取不到（老桥 404）就当没有。
    LaunchedEffect(Unit) { viewModel.loadSpeechCatalog() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            speech.permission = PermissionState.GRANTED
            startRecording(speech, viewModel)
        } else {
            val permanent = context.findActivity()
                ?.let { activity -> !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) }
                ?: false
            speech.permission = if (permanent) PermissionState.PERMANENTLY_DENIED else PermissionState.DENIED
            if (permanent) speech.needsSettings = true
            viewModel.notify(
                if (permanent) permissionPermanentlyDeniedText else permissionDeniedText,
            )
        }
    }

    // 计时 + 到达上限自动收尾：录音器自己到顶也会停，这里负责把结果送去转写。
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        val startedAt = android.os.SystemClock.elapsedRealtime()
        while (speech.recording) {
            delay(TICK_MS)
            speech.elapsedMs = (android.os.SystemClock.elapsedRealtime() - startedAt).toInt()
            if (!speech.recorder.isRecording || speech.elapsedMs >= MAX_RECORD_MS) {
                finishRecording(speech, viewModel)
                break
            }
        }
    }

    if (speech.needsSettings) {
        AlertDialog(
            onDismissRequest = { speech.needsSettings = false },
            title = { Text(stringResource(R.string.speech_permission_title)) },
            text = { Text(stringResource(R.string.speech_permission_permanently_denied)) },
            confirmButton = {
                TextButton(onClick = {
                    speech.needsSettings = false
                    openAppSettings(context)
                }) { Text(stringResource(R.string.speech_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { speech.needsSettings = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // 「不可用」也必须能点：下面 `onClick` 专门有一条 `!available -> notify(unavailableText)`，
    // 文案还分了「有原因」和「没原因」两种 —— 只要 `enabled` 不含它，这条分支就是死代码。
    // 真机实测（v2.2.57，内核未注入语音服务，目录回 available=false）正是如此：
    // 图标灰着、点下去毫无反应，用户只能猜是自己没给权限还是功能坏了。
    // 灰是**视觉**信号，不该同时掐掉唯一的解释入口。
    // `catalog == null`（目录请求还在路上）仍然不可点：那一刻还不知道能不能录，
    // 点下去只会给出「不可用」这种误导性提示。
    val enabled = available || transcribing || recording || catalog != null
    IconButton(
        onClick = {
            when {
                transcribing -> viewModel.cancelSpeechTranscription()
                recording -> finishRecording(speech, viewModel)
                !available -> viewModel.notify(unavailableText)
                else -> {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        speech.permission = PermissionState.GRANTED
                        startRecording(speech, viewModel)
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        },
        modifier = Modifier.size(34.dp),
        enabled = enabled,
    ) {
        when {
            transcribing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            recording -> Icon(
                Icons.Outlined.Stop,
                contentDescription = stringResource(R.string.speech_stop),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            else -> Icon(
                Icons.Outlined.Mic,
                contentDescription = stringResource(R.string.speech_input),
                modifier = Modifier.size(20.dp),
                tint = if (available) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
            )
        }
    }
}

/**
 * 状态条：录音中（计时 + 取消）/ 转写中（可取消）/ 失败（可重试）。
 *
 * 空闲时**完全不占高度**（直接 return），所以它在输入区上方常驻也不浪费像素。
 * 转写结果在这里消费：`pendingText` 一旦出现就交给 [onText] 插进输入框，随即清空，
 * 避免重组时重复插入同一段话。
 */
@Composable
internal fun SpeechStatusBar(
    state: HarnessState,
    viewModel: HarnessViewModel,
    speech: SpeechUiState,
    onText: (String) -> Unit,
) {
    if (!supportsSpeech(state.bridgeCapabilities)) return
    val speechState = state.speech
    LaunchedEffect(speechState.pendingText) {
        val text = speechState.pendingText ?: return@LaunchedEffect
        onText(text)
        viewModel.consumeSpeechText()
    }

    val recording = speech.recording
    val transcribing = speechState.transcribing
    val failure = speechState.error
    if (!recording && !transcribing && failure.isNullOrBlank()) return

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                recording -> {
                    LevelBars(speech)
                    Text(
                        stringResource(R.string.speech_listening, formatDuration(speech.elapsedMs)),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        speech.discardNext = true
                        speech.recorder.cancel()
                        speech.recording = false
                        speech.elapsedMs = 0
                    }) { Text(stringResource(R.string.speech_cancel)) }
                }
                transcribing -> {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.speech_transcribing),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.cancelSpeechTranscription() }) {
                        Text(stringResource(R.string.speech_cancel))
                    }
                }
                else -> {
                    Text(
                        failure.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { viewModel.retrySpeechTranscription() },
                        enabled = speechState.retryable,
                    ) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
}

/** 录音时的音量条：给用户一个「麦克风确实在收声」的反馈（不看计时也知道没卡住）。 */
@Composable
private fun LevelBars(speech: SpeechUiState) {
    val level = speech.recorder.level
    val bars = 4
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(bars) { index ->
            val active = level >= (index + 1).toFloat() / bars * 0.6f
            Box(
                Modifier
                    .width(3.dp)
                    .height((5 + index * 2).dp)
                    .background(
                        color = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

private fun startRecording(speech: SpeechUiState, viewModel: HarnessViewModel) {
    if (speech.recorder.start()) {
        speech.recording = true
        speech.discardNext = false
        speech.elapsedMs = 0
    } else {
        viewModel.notify(viewModel.appString(R.string.speech_record_failed))
    }
}

private fun finishRecording(speech: SpeechUiState, viewModel: HarnessViewModel) {
    val discard = speech.discardNext
    speech.discardNext = false
    speech.recording = false
    speech.elapsedMs = 0
    val wav = speech.recorder.stop() ?: return
    if (discard) return
    viewModel.transcribeSpeech(wav)
}

private fun formatDuration(millis: Int): String {
    val totalSeconds = millis / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

private const val TICK_MS = 200L

/** 界面层的硬上限（录音器自身也有同样的上限，这里是第二道）：到点自动收尾去转写。 */
private const val MAX_RECORD_MS = 120_000
