package io.github.westanke.dshcompanion.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把裸 PCM 封成 WAV（RIFF/LEI16）。
 *
 * ## 为什么客户端要自己封装
 *
 * `MediaRecorder` 在 Android 上能直接出 `.wav` 吗？不能 —— 它的输出格式里没有 WAV，
 * 最接近的是 `ENCODING_PCM_16BIT` 的裸流（`.pcm`）。而契约要的是一段**能解码的 WAV**
 * （服务端按容器头判时长/采样率）。于是客户端录音时写裸 PCM、停止后补 44 字节头，
 * 这一段是所有平台都会走、也最容易写错（字节序/块对齐）的地方，所以抽成纯函数 + 单测。
 *
 * 规格固定按契约：**16 kHz / 单声道 / 16 bit**（`ws-speech.ts` 的采集口径）。
 */
internal object WavHeader {

    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16

    /** 标准 PCM 头长度。 */
    const val SIZE = 44

    /**
     * 生成 44 字节 WAV 头。[pcmBytes] 是**后续要跟上的裸 PCM 字节数**。
     *
     * 字段顺序照 RIFF 规范：`RIFF` + 总长 + `WAVE` + `fmt ` 子块 + `data` 子块。
     * `ByteOrder.LITTLE_ENDIAN` 不是可选项 —— WAV 是 little-endian，写反了服务端读出来
     * 采样率会是天文数字（1 600 000 → 乱码/超时），这种错在真机上极难看出来。
     */
    fun build(
        pcmBytes: Int,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        bitsPerSample: Int = BITS_PER_SAMPLE,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + pcmBytes) // 总长 = 头剩余 36 字节 + data
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // fmt 子块长度
        buffer.putShort(1) // 1 = PCM（未压缩）
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(pcmBytes)
        return buffer.array()
    }

    /** 头 + 数据，得到可直接 base64 的完整 WAV。 */
    fun wrap(
        pcm: ByteArray,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        bitsPerSample: Int = BITS_PER_SAMPLE,
    ): ByteArray {
        val header = build(pcm.size, sampleRate, channels, bitsPerSample)
        return header + pcm
    }
}
