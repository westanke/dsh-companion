package io.github.hakunm.deepseekharness.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ask_user_question 的两个端点：路由、请求体形状、以及解码的宽容度。
 *
 * 为什么要钉死：这两条路由的**路径**与**请求体**是插件契约里最容易写错的地方
 * （callId 要参与路径编码、custom 该省则省），而错了之后服务端只会回一个 4xx，
 * 界面上表现为「点了没反应」。这里用 MockWebServer 把契约钉成可执行的断言。
 */
class PendingQuestionsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun questionsEndpointDecodesEveryDocumentedField() {
        server.enqueue(
            MockResponse()
                .setBody(
                    """
                    {"items":[{"callId":"call 1","state":"continued","questions":[{
                      "id":"q1","header":"方案","question":"选哪个？","detail":"细一点",
                      "options":[{"label":"A","description":"甲"},{"label":"B"}],
                      "multiSelect":true,
                      "intent":{"kind":"plan-review","approve":"OK"},
                      "futureFieldFromANewerKernel":123
                    }]}]}
                    """.trimIndent(),
                )
                .setHeader("Content-Type", "application/json"),
        )
        val client = DshClient(server.url("/").toString(), "device-token")

        val set = client.pendingQuestions("session 1").single()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/chat/sessions/session%201/questions", request.path)
        assertEquals("Bearer device-token", request.getHeader("Authorization"))

        assertEquals("call 1", set.callId)
        assertEquals("continued", set.state)
        val question = set.questions.single()
        assertEquals("q1", question.id)
        assertEquals("方案", question.header)
        assertEquals("选哪个？", question.question)
        assertEquals("细一点", question.detail)
        // 选项两个字段都要在（description 缺席的那个保持 null）。
        assertEquals(listOf(QuestionOption("A", "甲"), QuestionOption("B", null)), question.options)
        assertTrue(question.multiSelect)
    }

    @Test
    fun missingOptionalFieldsFallBackInsteadOfFailingTheWholeList() {
        // 插件对 questions 各项是原样透传，字段可能缺席；缺了不该让**整条**列表解不出来。
        // state 缺席回落成空串 —— 于是它不会被当成 continued 去渲染卡片。
        server.enqueue(
            MockResponse()
                .setBody("""{"items":[{"callId":"c1","questions":[{"id":"q1","question":"只有必填项"}]}]}""")
                .setHeader("Content-Type", "application/json"),
        )
        val client = DshClient(server.url("/").toString(), "device-token")

        val set = client.pendingQuestions("s").single()

        assertEquals("", set.state)
        val question = set.questions.single()
        assertEquals(null, question.header)
        assertEquals(null, question.detail)
        assertTrue(question.options.isEmpty())
        assertFalse(question.multiSelect)
    }

    @Test
    fun answerEndpointPostsSelectedAndOmitsEmptyCustom() {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setBody("""{"accepted":true}""")
                .setHeader("Content-Type", "application/json"),
        )
        val client = DshClient(server.url("/").toString(), "device-token")

        client.answerQuestions(
            sessionId = "s1",
            callId = "call 1",
            answers = listOf(
                QuestionAnswer(id = "q1", selected = listOf("A")),
                QuestionAnswer(id = "q2", selected = emptyList(), custom = "自己写的"),
            ),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        // callId 参与路径，空格必须编码。
        assertEquals("/api/v1/chat/sessions/s1/questions/call%201/answer", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"selected\":[\"A\"]"))
        assertTrue(body, body.contains("\"selected\":[]"))
        assertTrue(body, body.contains("\"custom\":\"自己写的\""))
        // custom 等于默认值（空串）时不编码 —— 服务端看到的是「没有自定义答案」，
        // 而不是一个空字符串（那是两件不同的事）。
        assertFalse(body, body.contains("\"custom\":\"\""))
    }

    @Test
    fun answerFailureSurfacesThePluginErrorCode() {
        // 409 的 code 决定用户看到哪句话（见 HarnessViewModel.describeAnswerFailure），
        // 所以这里必须确认 code 真的被解出来、没有退化成 HTTP_ERROR。
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("""{"error":{"code":"QUESTION_REPLY_QUEUED","message":"already queued"}}""")
                .setHeader("Content-Type", "application/json"),
        )
        val client = DshClient(server.url("/").toString(), "device-token")

        val failure = runCatching {
            client.answerQuestions("s1", "c1", listOf(QuestionAnswer("q1", listOf("A"))))
        }.exceptionOrNull()

        assertTrue(failure is DshApiException)
        assertEquals("QUESTION_REPLY_QUEUED", (failure as DshApiException).code)
        assertEquals(409, failure.status)
    }
}
