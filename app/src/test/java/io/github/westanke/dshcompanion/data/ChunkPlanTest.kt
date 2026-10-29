package io.github.westanke.dshcompanion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分块计划：断点续传、除不尽、非法块大小、越界下标。
 *
 * 这些是分块上传里**唯一能脱离网络与真机**验证的部分，所以测得细一点：
 * 真机上「块传了但拼起来是花的」这类问题，根因几乎都在 offset/count 的算术上。
 */
class ChunkPlanTest {

    @Test
    fun countsCeilingDivisionAndLastChunkMayBeShort() {
        val plan = planChunks(size = 2_500_000, chunkSize = 1_048_576)

        assertEquals(3, plan.count)
        assertEquals(listOf(0, 1, 2), plan.pending)
        assertEquals(0L, plan.offsetOf(0))
        assertEquals(1_048_576L, plan.offsetOf(1))
        assertEquals(2_097_152L, plan.offsetOf(2))
    }

    @Test
    fun exactMultipleDoesNotAddAnEmptyTailChunk() {
        val plan = planChunks(size = 2_097_152, chunkSize = 1_048_576)
        assertEquals(2, plan.count)
    }

    @Test
    fun emptyFileNeedsNoChunks() {
        val plan = planChunks(size = 0, chunkSize = 1_048_576)
        assertEquals(0, plan.count)
        assertTrue(plan.pending.isEmpty())
    }

    @Test
    fun zeroOrNegativeChunkSizeFallsBackToContractDefault() {
        // 服务端给 0 会让 offset = index * 0 全部重叠成 0：宁可退回契约默认值。
        assertEquals(DEFAULT_UPLOAD_CHUNK_SIZE, planChunks(3_000_000, 0).chunkSize)
        assertEquals(DEFAULT_UPLOAD_CHUNK_SIZE, planChunks(3_000_000, -5).chunkSize)
    }

    @Test
    fun alreadyReceivedChunksAreSkippedAndOutOfRangeOnesIgnored() {
        val plan = planChunks(size = 4_000_000, chunkSize = 1_048_576, alreadyReceived = listOf(0, 2, 99, -3))

        assertEquals(4, plan.count)
        // 越界/负数下标是服务端数据不干净，不能让客户端崩。
        assertEquals(listOf(1, 3), plan.pending)
    }

    @Test
    fun allChunksReceivedMeansNothingLeftToSend() {
        val plan = planChunks(size = 3_000_000, chunkSize = 1_048_576, alreadyReceived = listOf(0, 1, 2))
        assertTrue(plan.pending.isEmpty())
    }

    @Test
    fun sliceReturnsTailBytesAndGuardsOutOfRange() {
        val bytes = ByteArray(2_500_000) { (it % 251).toByte() }
        val plan = planChunks(bytes.size.toLong(), 1_048_576)

        assertEquals(1_048_576, chunkSlice(bytes, plan, 0).size)
        assertEquals(402_848, chunkSlice(bytes, plan, 2).size)
        assertTrue(chunkSlice(bytes, plan, 3).isEmpty())
        assertTrue(chunkSlice(bytes, plan, -1).isEmpty())
        assertTrue(chunkSlice(bytes, plan, 2).contentEquals(bytes.copyOfRange(2_097_152, 2_500_000)))
    }

    @Test
    fun progressFractionIsSafeWhenNothingToSend() {
        assertEquals(0f, ChunkProgress(0, 0).fraction, 0.0001f)
        assertEquals(0.5f, ChunkProgress(3, 6).fraction, 0.0001f)
    }
}
