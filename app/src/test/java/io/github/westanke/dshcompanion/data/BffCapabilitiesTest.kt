package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三项新能力的**能力位判据**。
 *
 * 2026-10-29 桥侧定稿后，客户端从「别名容忍表」收紧成**单值**判定：
 * 名字对不上就必须判为不支持、界面据此藏入口 —— 这比「猜中一个近义名」更安全，
 * 因为「桥发了位、客户端不认识」的表现是入口消失（可诊断），而不是点进去 404（看着像故障）。
 *
 * 这条测试同时也是「老 bridge 上没有这些位 → 入口不出现」的机检。
 */
class BffCapabilitiesTest {

    @Test
    fun chunkedUploadUsesTheContractedNameOnly() {
        assertTrue(supportsChunkedUpload(setOf("attachment-chunked-upload-v1")))
        // 定稿前用过的近义名不再接受：宁可藏入口，也不要点进去 404。
        assertFalse(supportsChunkedUpload(setOf("chunked-upload-v1")))
        assertFalse(supportsChunkedUpload(setOf("attachment-upload-v1")))
    }

    @Test
    fun schedulesUseTheContractedNameOnly() {
        assertTrue(supportsSchedules(setOf("chat-schedules-v1")))
        assertFalse(supportsSchedules(setOf("schedule-crud-v1")))
        assertFalse(supportsSchedules(setOf("schedules-v1")))
    }

    @Test
    fun speechUsesTheContractedNameOnly() {
        assertTrue(supportsSpeech(setOf("speech-transcription-v1")))
        assertFalse(supportsSpeech(setOf("speech-v1")))
        assertFalse(supportsSpeech(setOf("chat-speech-v1")))
    }

    @Test
    fun theThreeContractedBitsAreIndependent() {
        // 只声明分块上传的桥：定时任务与语音的入口必须不出现。
        val uploadOnly = setOf("attachment-chunked-upload-v1")

        assertTrue(supportsChunkedUpload(uploadOnly))
        assertFalse(supportsSchedules(uploadOnly))
        assertFalse(supportsSpeech(uploadOnly))
    }

    @Test
    fun oldBridgeWithoutTheseBitsHidesAllThreeEntries() {
        // 老 bridge 的真实形态：只有 approvals / questions 这些老位。
        val legacy = setOf("approvals-v1", "questions-v1", "session-jobs-v1")

        assertFalse(supportsChunkedUpload(legacy))
        assertFalse(supportsSchedules(legacy))
        assertFalse(supportsSpeech(legacy))
    }

    @Test
    fun emptyCapabilitiesMeanNothingIsAvailable() {
        assertFalse(supportsChunkedUpload(emptySet()))
        assertFalse(supportsSchedules(emptySet()))
        assertFalse(supportsSpeech(emptySet()))
    }
}
