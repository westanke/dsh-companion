package io.github.westanke.dshcompanion.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAV 头：字节序（LE）、块对齐、data 长度。
 *
 * 写反字节序在真机上的表现是「转写超时/返回乱码」，从录音侧根本看不出来，
 * 所以这条只能靠单测钉。
 */
class WavHeaderTest {

    @Test
    fun headerIsFortyFourBytesWithRiffWaveFmtAndData() {
        val header = WavHeader.build(pcmBytes = 32_000)
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
    }

    @Test
    fun lengthsAndPcmFormatAreLittleEndian() {
        val header = WavHeader.build(pcmBytes = 32_000)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(36 + 32_000, buffer.getInt(4)) // RIFF 总长
        assertEquals(16, buffer.getInt(16)) // fmt 子块
        assertEquals(1.toShort(), buffer.getShort(20)) // PCM
        assertEquals(1.toShort(), buffer.getShort(22)) // 单声道
        assertEquals(16_000, buffer.getInt(24)) // 采样率
        assertEquals(32_000, buffer.getInt(28)) // byteRate = 16000*1*2
        assertEquals(2.toShort(), buffer.getShort(32)) // blockAlign
        assertEquals(16.toShort(), buffer.getShort(34)) // 位深
        assertEquals(32_000, buffer.getInt(40)) // data 长度
    }

    @Test
    fun wrapPrependsHeaderAndKeepsPcmUntouched() {
        val pcm = ByteArray(6) { it.toByte() }
        val wav = WavHeader.wrap(pcm)

        assertEquals(44 + 6, wav.size)
        assertTrue(wav.copyOfRange(44, wav.size).contentEquals(pcm))
        assertEquals(44 + 6 - 8, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
    }

    @Test
    fun emptyRecordingStillProducesAValidHeader() {
        val wav = WavHeader.wrap(ByteArray(0))
        assertEquals(44, wav.size)
        assertEquals(0, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
    }
}
