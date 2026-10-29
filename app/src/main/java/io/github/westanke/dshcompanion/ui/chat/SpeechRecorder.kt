package io.github.westanke.dshcompanion.ui.chat

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.westanke.dshcompanion.data.WavHeader
import java.io.ByteArrayOutputStream

/**
 * 录音：`AudioRecord` 直出 16 kHz / 单声道 / PCM16，停止时封成 WAV。
 *
 * ## 为什么不是任务里写的 `MediaRecorder`
 *
 * 契约要求提交的是 **WAV**（16 kHz 单声道 PCM16）。`MediaRecorder` 能写的封装只有 AMR/AAC/3GP/MP4，
 * **没有裸 PCM**；用它就得再解码一遍（MediaCodec + 自己拼容器），代码量反而翻几倍，还多一次有损转码。
 * `AudioRecord` 拿到的就是 PCM16LE 原始字节，加 44 字节头即 WAV —— 少一层、也不失真。
 *
 * ## 线程模型
 *
 * `AudioRecord.read` 是阻塞调用，放主线程会卡 UI；这里单开一个搬运线程，把每 100ms 的数据
 * 追加进内存缓冲。录音是**短暂**行为（上限 2 分钟），所以内存里攒 PCM 是合理的：
 * 超过 [MAX_PCM_BYTES] 自动停，避免用户按住不放把机器拖垮。
 *
 * 用的人只有一个：`SpeechInput.kt`。VM 只接收「一段 WAV」，设备资源不越过 UI 层。
 */
internal class SpeechRecorder {

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var output: ByteArrayOutputStream? = null

    @Volatile
    private var running = false

    /** 最近一次采样的峰值（0..1），给界面画音量条；没有采样时是 0。 */
    @Volatile
    var level: Float = 0f
        private set

    val isRecording: Boolean
        get() = running

    /**
     * 开始录音。返回 false 表示设备不给麦克风（被别的 App 占用、或权限刚被撤）。
     *
     * 调用方**必须**先拿到 `RECORD_AUDIO` 权限：这里不弹权限，因为权限对话框需要一个
     * Activity 结果回调，那不是录音器该管的事。
     */
    @SuppressLint("MissingPermission") // 权限由 SpeechInput 的运行时请求把关，见上面的 KDoc
    fun start(): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            WavHeader.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val bufferSize = maxOf(minBuffer * 2, SAMPLE_BYTES_PER_TICK)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                WavHeader.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (error: Exception) {
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        val sink = ByteArrayOutputStream()
        output = sink
        record = recorder
        running = true
        level = 0f
        return try {
            recorder.startRecording()
            worker = Thread({ pump(recorder, sink) }, "dsh-speech-recorder").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            running = false
            recorder.release()
            record = null
            output = null
            false
        }
    }

    /** 停止并返回 WAV 字节；没录到东西返回 null（空音频送上去只会换来一个空的转写结果）。 */
    fun stop(): ByteArray? {
        if (!running) return null
        running = false
        worker?.let { thread -> runCatching { thread.join(THREAD_JOIN_TIMEOUT_MS) } }
        worker = null
        level = 0f
        val recorder = record
        record = null
        if (recorder != null) {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        val pcm = output?.toByteArray()
        output = null
        if (pcm == null || pcm.isEmpty()) return null
        return WavHeader.wrap(pcm, WavHeader.SAMPLE_RATE, WavHeader.CHANNELS, WavHeader.BITS_PER_SAMPLE)
    }

    /** 放弃这次录音（转屏、离开页面、用户点取消）：不返回数据，只把设备释放干净。 */
    fun cancel() {
        running = false
        worker?.let { thread -> runCatching { thread.join(THREAD_JOIN_TIMEOUT_MS) } }
        worker = null
        record?.let { recorder ->
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        record = null
        output = null
        level = 0f
    }

    private fun pump(recorder: AudioRecord, sink: ByteArrayOutputStream) {
        val buffer = ByteArray(SAMPLE_BYTES_PER_TICK)
        while (running) {
            val read = runCatching { recorder.read(buffer, 0, buffer.size) }.getOrDefault(-1)
            if (read <= 0) continue
            synchronized(sink) {
                sink.write(buffer, 0, read)
                level = peakOf(buffer, read)
                if (sink.size() >= MAX_PCM_BYTES) running = false
            }
        }
    }

    private fun peakOf(buffer: ByteArray, length: Int): Float {
        var peak = 0
        var index = 0
        while (index + 1 < length) {
            // PCM16 小端：两个字节拼一个样本，取绝对值比大小。
            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF)).toShort().toInt()
            val magnitude = if (sample == Short.MIN_VALUE.toInt()) -sample - 1 else kotlin.math.abs(sample)
            if (magnitude > peak) peak = magnitude
            index += 2
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }

    private companion object {
        /** 100ms 一读：太短会空转耗电，太长则停止时丢掉最后一段。 */
        const val SAMPLE_BYTES_PER_TICK = WavHeader.SAMPLE_RATE / 10 * 2
        const val THREAD_JOIN_TIMEOUT_MS = 1_000L

        /**
         * 上限 ≈ 120 秒。WAV 之后要整体 base64 进 JSON，而契约的解码后上限是 4 MiB；
         * 16 kHz×16bit 单声道下 4 MiB ≈ 131 秒，这里留一点余量，免得录到最后反而被 413。
         */
        const val MAX_PCM_BYTES = 120 * WavHeader.SAMPLE_RATE * 2
    }
}
