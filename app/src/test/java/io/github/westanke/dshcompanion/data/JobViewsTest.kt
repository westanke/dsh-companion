package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台任务 wire 数据的解码回归（bridge `GET /chat/sessions/{id}/jobs` 等）。
 *
 * 服务端已把内核 `JobView` 逐字段校验过，所以这里**不测**「缺 id/未知 status」
 * 那类脏数据（到不了客户端）；要钉住的是三条 wire 约定：
 * 可选字段缺省时不出现、`next` 是字节偏移、`lossy` 与 `status` 按原样携带。
 */
class JobViewsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes a full job row`() {
        val decoded = json.decodeFromString(
            JobView.serializer(),
            """{"id":"bash-1","kind":"bash","label":"npm run build","owner":"s1",
                "status":"running","progress":"3/10","startedAt":1760000000000}""",
        )
        assertEquals("bash-1", decoded.id)
        assertEquals("bash", decoded.kind)
        assertEquals(JobStatus.RUNNING, decoded.status)
        assertEquals("3/10", decoded.progress)
        assertEquals(1_760_000_000_000, decoded.startedAt)
        assertNull(decoded.finishedAt)
        assertTrue(decoded.status.isLive)
    }

    @Test
    fun `optional fields default to null when absent`() {
        val decoded = json.decodeFromString(
            JobView.serializer(),
            """{"id":"bash-2","kind":"bash","label":"cargo test","status":"completed","startedAt":1}""",
        )
        assertNull(decoded.owner)
        assertNull(decoded.progress)
        assertNull(decoded.detail)
        assertNull(decoded.finishedAt)
        assertFalse(decoded.status.isLive)
    }

    @Test
    fun `decodes every lifecycle status by its wire name`() {
        val wireNames = mapOf(
            "running" to JobStatus.RUNNING,
            "stopping" to JobStatus.STOPPING,
            "completed" to JobStatus.COMPLETED,
            "killed" to JobStatus.KILLED,
            "failed" to JobStatus.FAILED,
        )
        for ((wire, expected) in wireNames) {
            val decoded = json.decodeFromString(
                JobView.serializer(),
                """{"id":"j","kind":"bash","label":"l","status":"$wire","startedAt":0}""",
            )
            assertEquals(wire, expected, decoded.status)
        }
    }

    @Test
    fun `decodes the roster envelope`() {
        val decoded = json.decodeFromString(
            JobListEnvelope.serializer(),
            """{"jobs":[{"id":"a","kind":"bash","label":"x","status":"running","startedAt":0},
                        {"id":"b","kind":"bash","label":"y","status":"failed","startedAt":0}]}""",
        )
        assertEquals(2, decoded.jobs.size)
    }

    @Test
    fun `decodes job output with the resume offset and lossy marker`() {
        val decoded = json.decodeFromString(
            JobOutput.serializer(),
            """{"jobId":"bash-1","text":"building...","next":10,"lossy":true,"status":"completed"}""",
        )
        assertEquals(10, decoded.next)
        assertTrue(decoded.lossy)
        assertEquals(JobStatus.COMPLETED, decoded.status)
    }

    @Test
    fun `decodes the two kill outcomes`() {
        val requested = json.decodeFromString(
            JobKillResult.serializer(),
            """{"jobId":"bash-1","outcome":"requested"}""",
        )
        assertEquals("requested", requested.outcome)
        val finished = json.decodeFromString(
            JobKillResult.serializer(),
            """{"jobId":"bash-1","outcome":"already-finished"}""",
        )
        // 「它已经结束了」与「已终止」在界面上是两句话，解码层先保证这个区分不失真。
        assertEquals("already-finished", finished.outcome)
    }

    @Test
    fun `ignores unknown fields instead of failing on kernel evolution`() {
        // 内核给 JobView 加新字段时（比如将来的 cost），App 不该整条解码失败 ——
        // 那会把「新增字段」升级成「功能全挂」。
        val decoded = json.decodeFromString(
            JobView.serializer(),
            """{"id":"j","kind":"bash","label":"l","status":"running","startedAt":0,
                "someFutureField":{"nested":true}}""",
        )
        assertEquals("j", decoded.id)
    }
}
