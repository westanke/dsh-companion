package io.github.westanke.dshcompanion

import android.util.Base64
import androidx.lifecycle.viewModelScope
import io.github.westanke.dshcompanion.data.configureSpeech
import io.github.westanke.dshcompanion.data.speechCatalog
import io.github.westanke.dshcompanion.data.transcribe
import io.github.westanke.dshcompanion.data.DshApiException
import io.github.westanke.dshcompanion.data.MAX_AUDIO_BYTES
import io.github.westanke.dshcompanion.data.SpeechCatalog
import io.github.westanke.dshcompanion.data.isRouteMissing
import io.github.westanke.dshcompanion.debug.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 官方语音输入的动作集（目录读取 / 整段转写 / 取消 / 重试 / 记住选择）。
 *
 * ## 为什么转写**不走** `launchOperation`
 *
 * `launchOperation` 会把全局 `busy` 置位；转写的服务端超时是 180s，那样等于用户一按麦克风、
 * 整个 App 的按钮灰三分钟。这里自管 `SpeechState.transcribing`，只影响语音按钮自己。
 * 仍然遵守另外两条公共纪律：无连接丢弃留痕（`client == null` 直接 return）、
 * 代际闸门（[SpeechState.generation]，换会话/取消后回来的结果一律丢）。
 *
 * ## 录音为什么不在 ViewModel 里
 *
 * `MediaRecorder` 是**设备资源**（麦克风独占、要权限、跟音频焦点打架），而且「横竖屏切换
 * 要不要继续录」这个问题的答案只存在于 Compose 的生命周期里。所以录音与权限在
 * `ui/chat/SpeechInput.kt`，VM 只接「一段 WAV 字节」这个纯数据接口。
 */

/** 读语音服务目录。已读过就不重复请求（[force] 用于用户手动刷新）。 */
internal fun HarnessViewModel.loadSpeechCatalog(force: Boolean = false) {
    val api = client ?: return
    if (!force && mutableState.value.speech.catalog != null) return
    update { copy(speech = speech.copy(loadingCatalog = true, error = null)) }
    viewModelScope.launch {
        try {
            val catalog = withContext(Dispatchers.IO) { api.speechCatalog() }
            update { copy(speech = speech.copy(catalog = catalog, loadingCatalog = false)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 老 bridge 404 = 没这条路由：保持 catalog=null（入口本来就被能力位藏掉），
            // 不写错误 —— 用户没做错任何事，别让他看见红条。
            val missingRoute = error is DshApiException && error.isRouteMissing()
            update {
                copy(
                    speech = speech.copy(
                        loadingCatalog = false,
                        catalog = null,
                        error = if (missingRoute) null else messageOf(error),
                    ),
                )
            }
            DiagLog.w("SPEECH", "loadSpeechCatalog 失败（routeMissing=$missingRoute）: ${error.message}")
        }
    }
}

/**
 * 转写一段 WAV。[wav] 由录音侧用 `WavHeader.wrap` 封装好（16 kHz / 单声道 / PCM16）。
 *
 * 结果不自动发送，只落到 [SpeechState.pendingText]，由输入区把它插进输入框 —— `ask_user_question`
 * 时代留下的教训：任何「自动发送」都会让用户失去最后一次检查错字的机会。
 */
internal fun HarnessViewModel.transcribeSpeech(wav: ByteArray) {
    val api = client ?: return
    if (wav.isEmpty()) return
    if (wav.size > MAX_AUDIO_BYTES) {
        notify(appString(R.string.speech_audio_too_large))
        return
    }
    val catalog = mutableState.value.speech.catalog
    val generation = mutableState.value.speech.generation + 1
    update {
        copy(
            speech = speech.copy(
                transcribing = true,
                error = null,
                pendingText = null,
                retryable = false,
                audio = wav,
                generation = generation,
            ),
        )
    }
    viewModelScope.launch {
        try {
            val encoded = withContext(Dispatchers.IO) { Base64.encodeToString(wav, Base64.NO_WRAP) }
            val result = withContext(Dispatchers.IO) {
                api.transcribe(
                    audioBase64 = encoded,
                    language = catalog?.selected?.language?.takeIf { it.isNotBlank() },
                    providerId = catalog?.selected?.providerId?.takeIf { it.isNotBlank() },
                )
            }
            if (mutableState.value.speech.generation != generation) return@launch // 用户已取消/重开
            val text = result.text.trim()
            update { copy(speech = speech.copy(transcribing = false, pendingText = text.ifBlank { null })) }
            // 空结果不是错误（契约原话）：用户可能就是没说话。给一句轻提示，别弹红条。
            if (text.isEmpty()) notify(appString(R.string.speech_empty_result))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (mutableState.value.speech.generation != generation) return@launch
            update {
                copy(
                    speech = speech.copy(
                        transcribing = false,
                        error = messageOf(error),
                        retryable = true,
                    ),
                )
            }
            DiagLog.w("SPEECH", "transcribe 失败: ${error.message}")
        }
    }
}

/** 取消转写：只丢结果（代际 +1），不掐 HTTP 连接（见 [SpeechState.generation] 的注释）。 */
internal fun HarnessViewModel.cancelSpeechTranscription() = update {
    val current = speech
    copy(
        speech = current.copy(
            transcribing = false,
            generation = current.generation + 1,
            retryable = current.audio != null,
        ),
    )
}

/** 用上一次的音频重试，不然用户得重录一遍（最招人烦的交互之一）。 */
internal fun HarnessViewModel.retrySpeechTranscription() {
    val audio = mutableState.value.speech.audio ?: return
    transcribeSpeech(audio)
}

/** UI 已把 [SpeechState.pendingText] 插进输入框，消费掉它（避免重组时重复插入）。 */
internal fun HarnessViewModel.consumeSpeechText() = update {
    copy(speech = speech.copy(pendingText = null))
}

/** 记住用户的语音服务/语言选择，返回的目录视图直接覆盖本地。 */
internal fun HarnessViewModel.selectSpeech(providerId: String, language: String) {
    val api = client ?: return
    update { copy(speech = speech.copy(loadingCatalog = true, error = null)) }
    viewModelScope.launch {
        try {
            val catalog: SpeechCatalog = withContext(Dispatchers.IO) { api.configureSpeech(providerId, language) }
            update { copy(speech = speech.copy(catalog = catalog, loadingCatalog = false)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            update { copy(speech = speech.copy(loadingCatalog = false, error = messageOf(error))) }
            DiagLog.w("SPEECH", "configureSpeech 失败: ${error.message}")
        }
    }
}
