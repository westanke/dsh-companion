package io.github.westanke.dshcompanion.data.ws

import io.github.westanke.dshcompanion.data.DshApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `GoalPort` 的单元测试：**ref 怎么传、什么时候不发帧、失败怎么分类**。
 *
 * 这一组用例护住 P4 的三条硬约束（设计见 `GoalPort.kt` 的类注释）：
 *
 * 1. 四帧的 `ref:{id,revision}` 必须逐字传对 —— 传错 revision 会被内核的 compare-and-set
 *    静默拒绝（表现为"点了没反应"），传错 id 会改到别的目标上；
 * 2. 明知写不了的帧**不发**（通道没就绪、内核不支持、ref 不完整），因为"发了再失败"和
 *    "没发就失败"在用户眼里没区别，但前者会白占一次串行化的同 kind 通道；
 * 3. revision 过期必须**抛可识别的码**，让界面能走"重新读 → 用户确认 → 再发一次"，
 *    而不是静默重试或吞掉。
 *
 * 夹具沿用 `WsRequestRouterTest` 的写法：假 sender 记下所有出站帧，手动往 router 里
 * 灌应答帧 —— 不起真实 WS，也不碰 OkHttp。
 */
class GoalPortTest {

    private fun case(body: suspend CoroutineScope.() -> Unit) =
        runBlocking<Unit> { supervisorScope { body() } }

    // ---------------------------------------------------------------- 夹具

    private class Link {
        val sent = mutableListOf<JsonObject>()

        fun sender(): (JsonObject) -> Boolean = { frame ->
            synchronized(sent) { sent.add(frame) }
            true
        }

        fun count(): Int = synchronized(sent) { sent.size }

        fun types(): List<String> = synchronized(sent) { sent.mapNotNull { WsFrames.typeOf(it) } }

        /**
         * 第 [index] 个出站帧本体。
         *
         * 注意：客户端请求帧的字段是**平铺在顶层**的（`WsFrames.request` 把 payload 直接
         * `put` 进 frame，不套 `data`），应答帧才是 `{kind, data}` 的包法 —— 所以这里读顶层，
         * 不能像读应答那样走 `WsFrames.dataOf`。
         */
        fun payload(index: Int = 0): JsonObject = synchronized(sent) { sent[index] }

        /** 帧里属于业务载荷的键（排掉协议自带的 `type` / `requestId`）。 */
        fun bodyKeys(index: Int = 0): Set<String> =
            synchronized(sent) { sent[index].keys - setOf(WsFrames.TYPE, WsFrames.REQUEST_ID) }
    }

    private suspend fun awaitSent(link: Link, count: Int) {
        repeat(200) {
            if (link.count() >= count) return
            delay(10)
        }
        assertEquals("等不到第 $count 个出站帧", count, link.count())
    }

    private fun okReply(kind: String, sessionId: String, refRevision: Int? = null): JsonObject =
        buildJsonObject {
            put("kind", kind)
            put(
                "data",
                buildJsonObject {
                    put("sessionId", sessionId)
                    if (refRevision != null) {
                        put(
                            "ref",
                            buildJsonObject {
                                put("id", "goal-1")
                                put("revision", refRevision)
                            },
                        )
                    }
                },
            )
        }

    private fun clearReply(sessionId: String): JsonObject = buildJsonObject {
        put("kind", GoalFrames.CLEAR)
        put(
            "data",
            buildJsonObject {
                put("sessionId", sessionId)
                put("cleared", true)
            },
        )
    }

    private fun errorReply(code: String, requestType: String, message: String = "服务端说：$code"): JsonObject =
        buildJsonObject {
            put("kind", "error")
            put(
                "data",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                    put("requestType", requestType)
                },
            )
        }

    private class Fixture(
        val link: Link,
        val router: WsRequestRouter,
        val port: WsGoalPort,
    )

    private fun fixtureWith(
        channel: () -> ChannelState,
        unsupported: () -> Boolean = { false },
        onUnsupported: () -> Unit = {},
    ): Fixture {
        val link = Link()
        val router = WsRequestRouter(sender = link.sender())
        val port = WsGoalPort(
            router = router,
            channel = channel,
            kernelUnsupported = unsupported,
            onKernelUnsupported = onUnsupported,
        )
        return Fixture(link, router, port)
    }

    private fun readyFixture(
        unsupported: () -> Boolean = { false },
        onUnsupported: () -> Unit = {},
    ): Fixture = fixtureWith(
        channel = { ChannelState.WsReady },
        unsupported = unsupported,
        onUnsupported = onUnsupported,
    )

    private suspend fun assertThrowsCode(block: suspend () -> Unit): DshApiException {
        var thrown: DshApiException? = null
        try {
            withTimeout(2_000) { block() }
        } catch (error: DshApiException) {
            thrown = error
        }
        assertNotNull("必须抛 DshApiException，而不是静默返回", thrown)
        return thrown!!
    }

    private fun JsonObject.ref(): JsonObject = (this["ref"] as? JsonObject) ?: error("payload 里没有 ref：$this")

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.num(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { it.isString.not() }?.int

    // ------------------------------------------------------- ref 正确传递

    @Test
    fun `goal-edit 同时带 objective 与 maxGoalRounds 时 四要素逐字传对`() = case {
        val fixture = readyFixture()
        val job = async {
            fixture.port.edit("s-1", GoalRef("goal-1", 7), objective = "  重写后的目标  ", maxGoalRounds = 12)
        }
        awaitSent(fixture.link, 1)
        fixture.router.onFrame(okReply(GoalFrames.EDIT, "s-1", refRevision = 8))
        val ack = withTimeout(2_000) { job.await() }

        assertEquals(listOf(GoalFrames.EDIT), fixture.link.types())
        val data = fixture.link.payload()
        assertEquals("s-1", data.str("sessionId"))
        assertEquals("goal-1", data.ref().str("id"))
        assertEquals(7, data.ref().num("revision"))
        // 正文要去掉首尾空白：桥侧的 trimmedString 会把纯空白当"没给"，两边口径必须一致。
        assertEquals("重写后的目标", data.str("objective"))
        assertEquals(12, data.num("maxGoalRounds"))
        // 应答只带 ref：回执只暴露 revision，不伪造目标正文。
        assertEquals(8, ack.revision)
        assertFalse(ack.cleared)
        assertEquals(0, fixture.router.pendingCount())
    }

    @Test
    fun `goal-edit 只带 objective 时不发 maxGoalRounds 键`() = case {
        val fixture = readyFixture()
        val job = async { fixture.port.edit("s-1", GoalRef("goal-1", 3), objective = "只改正文") }
        awaitSent(fixture.link, 1)
        fixture.router.onFrame(okReply(GoalFrames.EDIT, "s-1", refRevision = 4))
        withTimeout(2_000) { job.await() }

        val data = fixture.link.payload()
        assertEquals("只改正文", data.str("objective"))
        assertNull("没给的字段不能凭空出现（桥会把它当一次修改）", data["maxGoalRounds"])
        assertEquals(setOf("sessionId", "ref", "objective"), fixture.link.bodyKeys())
    }

    @Test
    fun `goal-edit 只带 maxGoalRounds 时不发 objective 键`() = case {
        val fixture = readyFixture()
        val job = async { fixture.port.edit("s-1", GoalRef("goal-1", 3), objective = null, maxGoalRounds = 5) }
        awaitSent(fixture.link, 1)
        fixture.router.onFrame(okReply(GoalFrames.EDIT, "s-1", refRevision = 4))
        withTimeout(2_000) { job.await() }

        val data = fixture.link.payload()
        assertEquals(5, data.num("maxGoalRounds"))
        assertNull(data["objective"])
    }

    @Test
    fun `goal-pause 与 goal-resume 只带 sessionId 与 ref`() = case {
        val fixture = readyFixture()
        val pause = async { fixture.port.pause("s-1", GoalRef("goal-1", 7)) }
        awaitSent(fixture.link, 1)
        fixture.router.onFrame(okReply(GoalFrames.PAUSE, "s-1", refRevision = 8))
        withTimeout(2_000) { pause.await() }

        val resume = async { fixture.port.resume("s-1", GoalRef("goal-1", 8)) }
        awaitSent(fixture.link, 2)
        fixture.router.onFrame(okReply(GoalFrames.RESUME, "s-1", refRevision = 9))
        withTimeout(2_000) { resume.await() }

        assertEquals(listOf(GoalFrames.PAUSE, GoalFrames.RESUME), fixture.link.types())
        val pauseData = fixture.link.payload(0)
        assertEquals(setOf("sessionId", "ref"), fixture.link.bodyKeys(0))
        assertEquals(7, pauseData.ref().num("revision"))
        val resumeData = fixture.link.payload(1)
        assertEquals(setOf("sessionId", "ref"), fixture.link.bodyKeys(1))
        assertEquals(8, resumeData.ref().num("revision"))
        assertEquals(0, fixture.router.pendingCount())
    }

    @Test
    fun `goal-clear 应答 cleared 为真时回执承认清除`() = case {
        val fixture = readyFixture()
        val job = async { fixture.port.clear("s-1", GoalRef("goal-1", 7)) }
        awaitSent(fixture.link, 1)
        fixture.router.onFrame(clearReply("s-1"))
        val ack = withTimeout(2_000) { job.await() }

        assertEquals(listOf(GoalFrames.CLEAR), fixture.link.types())
        assertTrue(ack.cleared)
        assertNull("goal-clear 的应答不带 ref", ack.revision)
    }

    // -------------------------------------------------- 不发帧的三种情形

    @Test
    fun `通道不是 usesWs 时一个帧都不发`() = case {
        val fixture = fixtureWith(channel = { ChannelState.RestOnly })

        val thrown = assertThrowsCode { fixture.port.pause("s-1", GoalRef("goal-1", 7)) }

        assertEquals(GoalError.CHANNEL_NOT_READY, thrown.code)
        assertEquals("RestOnly 不该有任何出站帧", 0, fixture.link.count())
        assertEquals(0, fixture.router.pendingCount())
    }

    @Test
    fun `通道断开与降级同样不发帧`() = case {
        val down = fixtureWith(channel = { ChannelState.WsDown("close 1006") })
        assertEquals(
            GoalError.CHANNEL_NOT_READY,
            assertThrowsCode { down.port.clear("s-1", GoalRef("goal-1", 7)) }.code,
        )
        assertEquals(0, down.link.count())

        // WsDegraded 是 usesWs == true：降级**不是**禁用，帧照发（能不能成由桥决定）。
        val degraded = fixtureWith(channel = { ChannelState.WsDegraded("hello 缺能力位") })
        val job = async { degraded.port.pause("s-1", GoalRef("goal-1", 7)) }
        awaitSent(degraded.link, 1)
        degraded.router.onFrame(okReply(GoalFrames.PAUSE, "s-1", refRevision = 8))
        withTimeout(2_000) { job.await() }
        assertEquals(1, degraded.link.count())
    }

    @Test
    fun `ref 缺 id 或缺正整数 revision 时不发帧`() = case {
        val fixture = readyFixture()

        val blankId = assertThrowsCode { fixture.port.pause("s-1", GoalRef("  ", 7)) }
        assertEquals(GoalError.REF_MISSING, blankId.code)

        val zeroRevision = assertThrowsCode { fixture.port.pause("s-1", GoalRef("goal-1", 0)) }
        assertEquals(GoalError.REF_MISSING, zeroRevision.code)

        val negativeRevision = assertThrowsCode { fixture.port.resume("s-1", GoalRef("goal-1", -3)) }
        assertEquals(GoalError.REF_MISSING, negativeRevision.code)

        assertEquals("ref 不完整时一帧都不该出去", 0, fixture.link.count())
    }

    @Test
    fun `编辑两样都不给时本地就拦下 不发帧`() = case {
        val fixture = readyFixture()

        val nothing = assertThrowsCode { fixture.port.edit("s-1", GoalRef("goal-1", 7)) }
        assertEquals(GoalError.EDIT_EMPTY, nothing.code)

        // 纯空白按"没给"处理，与桥侧 trimmedString 一致。
        val blank = assertThrowsCode { fixture.port.edit("s-1", GoalRef("goal-1", 7), objective = "   ") }
        assertEquals(GoalError.EDIT_EMPTY, blank.code)

        val badRounds = assertThrowsCode { fixture.port.edit("s-1", GoalRef("goal-1", 7), maxGoalRounds = 0) }
        assertEquals(GoalError.EDIT_EMPTY, badRounds.code)

        assertEquals(0, fixture.link.count())
    }

    // ------------------------------------------------- revision 过期与降级

    @Test
    fun `revision 过期时抛可识别异常 且不静默重试`() = case {
        val fixture = readyFixture()
        val job = async { fixture.port.edit("s-1", GoalRef("goal-1", 7), objective = "改一下") }
        awaitSent(fixture.link, 1)
        fixture.router.onFrame(
            errorReply("CONFLICT", GoalFrames.EDIT, "goal revision mismatch: expected 8, got 7"),
        )

        val thrown = assertThrowsCode { job.await() }

        assertEquals(GoalError.REVISION_STALE, thrown.code)
        assertEquals("静默重试会用到用户没确认的改动，绝不能自己再发", 1, fixture.link.count())
        assertEquals(0, fixture.router.pendingCount())
    }

    @Test
    fun `revision 过期的识别覆盖码名与 message 关键字 但不误伤无关报错`() {
        assertTrue(isStaleRevisionError("CONFLICT", null))
        assertTrue(isStaleRevisionError("goal/STALE_REVISION", null))
        assertTrue(isStaleRevisionError(null, "goal revision is stale, reload and retry"))
        assertTrue(isStaleRevisionError("WS_ERROR", "revision mismatch: expected 8"))
        // 无关的 revision 字样不该被当成乐观锁冲突：否则用户会被引导去重试一次没意义的写入。
        assertFalse(isStaleRevisionError("BAD_REQUEST", "tool revision must be a number"))
        assertFalse(isStaleRevisionError("SESSION_FORBIDDEN", "Session is outside authorized roots."))
        assertFalse(isStaleRevisionError(null, null))
    }

    @Test
    fun `桥回 unsupported 后记住该内核不支持 后续不再发帧`() = case {
        val link = Link()
        val router = WsRequestRouter(sender = link.sender())
        var unsupported = false
        val port = WsGoalPort(
            router = router,
            channel = { ChannelState.WsReady },
            kernelUnsupported = { unsupported },
            onKernelUnsupported = { unsupported = true },
        )

        val first = async { port.pause("s-1", GoalRef("goal-1", 7)) }
        awaitSent(link, 1)
        router.onFrame(
            errorReply("unsupported", GoalFrames.PAUSE, "goal management is unavailable in this DSH host"),
        )
        val thrown = assertThrowsCode { first.await() }
        assertEquals(GoalError.UNSUPPORTED, thrown.code)
        assertTrue("撞到 unsupported 必须回调装配层记住这次判定", unsupported)
        assertEquals(1, link.count())

        // 第二次：能力缺失已记住 → 立刻失败，**一帧都不发**（否则每次点都白跑一趟）。
        val second = assertThrowsCode { port.clear("s-1", GoalRef("goal-1", 7)) }
        assertEquals(GoalError.UNSUPPORTED, second.code)
        assertEquals("记住不支持之后不能再发出站帧", 1, link.count())
        assertEquals(0, router.pendingCount())
    }

    @Test
    fun `会话越权错误原样透传 不被翻译成降级`() = case {
        val fixture = readyFixture()
        val job = async { fixture.port.clear("s-1", GoalRef("goal-1", 7)) }
        awaitSent(fixture.link, 1)
        fixture.router.onFrame(
            errorReply("SESSION_FORBIDDEN", GoalFrames.CLEAR, "Session is outside authorized roots."),
        )

        val thrown = assertThrowsCode { job.await() }

        assertEquals("授权失败是权限问题，不是能力缺失也不是版本冲突", "SESSION_FORBIDDEN", thrown.code)
        assertFalse("不能把它算成 unsupported", thrown.code == GoalError.UNSUPPORTED)
    }

    @Test
    fun `UnsupportedGoalPort 四个动作都抛不可用码`() = case {
        val port = UnsupportedGoalPort()
        val ref = GoalRef("goal-1", 7)
        assertEquals(GoalError.UNSUPPORTED, assertThrowsCode { port.edit("s-1", ref, "x", 1) }.code)
        assertEquals(GoalError.UNSUPPORTED, assertThrowsCode { port.pause("s-1", ref) }.code)
        assertEquals(GoalError.UNSUPPORTED, assertThrowsCode { port.resume("s-1", ref) }.code)
        assertEquals(GoalError.UNSUPPORTED, assertThrowsCode { port.clear("s-1", ref) }.code)
    }

    @Test
    fun `空 sessionId 是本端口的调用方 bug 直接拒绝`() = case {
        val fixture = readyFixture()
        var thrown: Throwable? = null
        try {
            fixture.port.pause(" ", GoalRef("goal-1", 7))
        } catch (error: IllegalArgumentException) {
            thrown = error
        }
        assertTrue("空 sessionId 应当直接被 require 拦下", thrown is IllegalArgumentException)
        assertEquals(0, fixture.link.count())
    }
}
