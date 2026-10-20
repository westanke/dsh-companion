package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端能力位的解析与缺口判断。
 *
 * 这些断言护的是一类**没有崩溃、只有错误提示**的行为：假警告（把旧服务端读成
 * 「什么都缺」）会训练用户忽略警告，而漏警告则让用户继续干等 —— 两者都不会让
 * 构建变红，只能靠这里钉住。
 */
class BridgeCapabilitiesTest {

    private fun payload(json: String) = Json.parseToJsonElement(json)

    @Test
    fun `reads version and capability list`() {
        val parsed = parseBridgeCapabilities(
            payload("""{"bridgeVersion":"2.4.0","capabilities":["approvals-v1","questions-v1","history-v1"]}"""),
        )
        assertEquals("2.4.0", parsed?.bridgeVersion)
        assertEquals(setOf("approvals-v1", "questions-v1", "history-v1"), parsed?.capabilities)
    }

    /**
     * 旧服务端根本不报这两项 —— 必须是「未知」，不是「什么都缺」。
     * 这条一旦写成空集，每台旧插件都会看到一条假警告。
     */
    @Test
    fun `treats a missing capability list as unknown`() {
        assertNull(parseBridgeCapabilities(payload("{}")))
        assertNull(parseBridgeCapabilities(payload("""{"bridgeVersion":"2.3.0"}""")))
    }

    /** 空数组同样按未知处理：现实里不存在「服务端声明自己没能力」。 */
    @Test
    fun `treats an empty capability list as unknown`() {
        assertNull(parseBridgeCapabilities(payload("""{"bridgeVersion":"2.4.0","capabilities":[]}""")))
    }

    /** 一项脏数据不能让整条清单一并作废（否则会从「有」误判成「无」）。 */
    @Test
    fun `skips non-string entries instead of failing the whole list`() {
        val parsed = parseBridgeCapabilities(
            payload("""{"capabilities":["approvals-v1",42,null,{"x":1},"questions-v1"]}"""),
        )
        assertEquals(setOf("approvals-v1", "questions-v1"), parsed?.capabilities)
    }

    @Test
    fun `returns unknown for a payload that is not an object`() {
        assertNull(parseBridgeCapabilities(payload("[1,2,3]")))
        assertNull(parseBridgeCapabilities(payload("\"just a string\"")))
    }

    @Test
    fun `reports nothing missing when everything required is declared`() {
        assertTrue(missingRequiredCapabilities(setOf("approvals-v1", "questions-v1", "history-v1")).isEmpty())
    }

    @Test
    fun `reports the missing required capability`() {
        assertEquals(listOf("questions-v1"), missingRequiredCapabilities(setOf("approvals-v1")))
        assertEquals(listOf("approvals-v1"), missingRequiredCapabilities(setOf("questions-v1")))
    }

    /** 顺序按依赖表固定：提示文案与测试都不该随 Set 的迭代顺序变化。 */
    @Test
    fun `keeps a stable order for the missing list`() {
        assertEquals(REQUIRED_CAPABILITIES, missingRequiredCapabilities(emptySet()))
    }

    /**
     * 归档列表入口的守卫。
     *
     * 能力清单**未知**（老 bridge 不给）时必须为假：老 bridge 会忽略 `archived` 参数、
     * 照样返回未归档列表 —— 若此时展示入口，用户看到的「一条归档都没有」是**假结论**。
     */
    @Test
    fun `archive listing requires the declared capability`() {
        assertTrue(supportsArchiveListing(setOf("session-archive-list-v1")))
        assertFalse(supportsArchiveListing(setOf("session-archive-v1")))
        assertFalse(supportsArchiveListing(emptySet()))
    }

    /** 依赖表本身要守住：这些是本 App 真会「静默不工作」的能力。 */
    @Test
    fun `required capabilities stay limited to the silent-failure ones`() {
        assertEquals(listOf("approvals-v1", "questions-v1"), REQUIRED_CAPABILITIES)
    }

    /**
     * 后台任务入口的守卫（bridge ≥ 2.4.3）。
     *
     * 三个能力位（job-list / job-output / job-kill）在服务端**同批声明**，
     * 所以判据只查 `job-list-v1` 一个 —— 查全三个只是把同一句话写三遍。
     * 这条用例同时钉住「判据名与能力位名的对应关系」，服务端若拆开发布
     * （理论上不应发生），这里会先红。
     */
    @Test
    fun `session jobs require the job capability`() {
        assertTrue(supportsSessionJobs(setOf("job-list-v1", "job-output-v1", "job-kill-v1")))
        assertFalse(supportsSessionJobs(setOf("job-output-v1", "job-kill-v1")))
        assertFalse(supportsSessionJobs(emptySet()))
    }
}
