package io.github.westanke.dshcompanion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 判据：哪些 WebSocket 帧意味着「该重新拉一次待答问题」。
 *
 * 背景：插件把所有非特殊会话事件统一降级成 `chat.session.event`，只留
 * `{sessionId, eventType, seq}`（chat-adapter.ts normalizeDshEvent 末段）。
 * 一轮里这类帧非常多 —— 若「来一帧拉一次」，等于自己造了个轮询。
 *
 * 而提问超时的判定完全落在内核那条 `tool/result` 上：折叠器读到
 * `{pending:true}` 才把问题从 open 改成 continued
 * （dsh-user-questions/lib/types/projection.js的 tool/result 分支）。
 * 所以这一帧到达就是确切信号 —— 时序上的事实，不是启发式。
 */
class ToolResultFrameTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun frame(eventType: String?, extra: String = ""): JsonObject? =
        json.parseToJsonElement(
            """{"sessionId":"s1","eventType":${if (eventType == null) "null" else "\"$eventType\""}$extra}"""
        ) as JsonObject

    @Test
    fun `tool result 帧被认出来`() {
        assertTrue(isToolResultFrame("chat.session.event", frame("tool/result")))
        assertTrue(isToolResultFrame("chat.session.event", frame("tool/result", ",\"seq\":42")))
    }

    @Test
    fun `同类型下的其它事件不认`() {
        // 这一条是「不做轮询」的关键：tool/call、assistant/chunk 等同样走
        // chat.session.event，全拉就等于每一步都去问一次。
        assertFalse(isToolResultFrame("chat.session.event", frame("tool/call")))
        assertFalse(isToolResultFrame("chat.session.event", frame("assistant/chunk")))
        assertFalse(isToolResultFrame("chat.session.event", frame("step/start")))
        assertFalse(isToolResultFrame("chat.session.event", frame("turn/end")))
    }

    @Test
    fun `别的事件类型一律不认`() {
        // chat.message.committed 等已由 QUESTION_REFRESH_EVENTS 覆盖，不该走这条。
        assertFalse(isToolResultFrame("chat.message.committed", frame("tool/result")))
        assertFalse(isToolResultFrame("chat.turn.end", frame("tool/result")))
        assertFalse(isToolResultFrame("chat.approval.requested", frame("tool/result")))
    }

    @Test
    fun `畸形输入不崩`() {
        assertFalse(isToolResultFrame(null, null))
        assertFalse(isToolResultFrame("chat.session.event", null))
        assertFalse(isToolResultFrame("chat.session.event", json.parseToJsonElement("[1,2]")))
        assertFalse(isToolResultFrame("chat.session.event", json.parseToJsonElement("\"str\"")))
        // eventType 缺失
        assertFalse(isToolResultFrame("chat.session.event", json.parseToJsonElement("{\"sessionId\":\"s1\"}") as JsonObject))
        // eventType 是 null
        assertFalse(isToolResultFrame("chat.session.event", frame(null)))
    }
}