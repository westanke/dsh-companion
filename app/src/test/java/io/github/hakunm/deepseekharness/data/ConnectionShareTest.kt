package io.github.hakunm.deepseekharness.data

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConnectionShare] 的容错与契约测试。
 *
 * 重点不是「正常输入能解析」，而是「用户从微信/邮件里粘贴出来的东西能解析」：
 * 换行、空格、零宽字符、丢了前缀、丢了 '=' 填充、被换成标准 Base64 字母表，
 * 以及「任何非法输入都不许抛异常」——用户粘贴时人已经在外面，崩溃比报错更糟。
 *
 * 若干硬编码的 Base64 字面量是**线格式的冻结样本**：桌面端生成器必须产出同样的 bytes，
 * 所以这里逐字节钉住，而不是只做 encode → decode 自循环（那样两边一起错也测不出来）。
 */
class ConnectionShareTest {

    private val home = Endpoint(id = "ep-home", label = "家里局域网", baseUrl = "http://192.168.1.126:3090")
    private val virtualNet = Endpoint(id = "ep-net", label = "公司虚拟网", baseUrl = "http://100.101.102.103:3090")

    private fun payload(
        token: String? = "tok_abc123",
        deviceName: String? = "Pixel 9",
    ) = ConnectionShare.Payload(
        displayName = "家里的电脑",
        endpoints = listOf(home, virtualNet),
        token = token,
        deviceName = deviceName,
    )

    private fun base64Of(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

    // ---------------------------------------------------------------- 线格式

    @Test
    fun encodesTheDocumentedWireLiteralByteForByte() {
        // 冻结样本：这条字符串就是「桌面端要生成什么」的权威定义。
        val encoded = ConnectionShare.encode(
            ConnectionShare.Payload(
                displayName = "家里的电脑",
                endpoints = listOf(Endpoint(id = "ep-home", label = "家里局域网", baseUrl = "http://192.168.1.126:3090")),
                token = "tok_abc123",
                deviceName = "Pixel 9",
            ),
        )

        assertEquals("DSH1:$GOLDEN_BODY", encoded)
        assertEquals("DSH1:", ConnectionShare.PREFIX)
    }

    @Test
    fun decodesTheDocumentedWireLiteral() {
        val decoded = requireNotNull(ConnectionShare.decode("DSH1:$GOLDEN_BODY"))

        assertEquals("家里的电脑", decoded.displayName)
        assertEquals("家里局域网", decoded.endpoints.single().label)
        assertEquals("http://192.168.1.126:3090", decoded.endpoints.single().baseUrl)
        assertEquals("tok_abc123", decoded.token)
        assertEquals("Pixel 9", decoded.deviceName)
        assertTrue(decoded.scopes.isEmpty())
    }

    // ---------------------------------------------------------------- 往返

    @Test
    fun roundTripKeepsMeaningfulFieldsWhenTokenPresent() {
        val original = payload()
        val decoded = requireNotNull(ConnectionShare.decode(ConnectionShare.encode(original)))

        assertEquals(original.displayName, decoded.displayName)
        assertEquals(original.endpoints.map { it.label }, decoded.endpoints.map { it.label })
        assertEquals(original.endpoints.map { it.baseUrl }, decoded.endpoints.map { it.baseUrl })
        assertEquals("tok_abc123", decoded.token)
        assertEquals("Pixel 9", decoded.deviceName)
        assertTrue(decoded.scopes.isEmpty())
        // Endpoint.id / kind 属于「本机状态」，刻意不进线格式；导入端由 buildHost 重新分配（见下）。
    }

    @Test
    fun roundTripWhenTokenAndDeviceNameAreAbsent() {
        val decoded = requireNotNull(
            ConnectionShare.decode(ConnectionShare.encode(payload(token = null, deviceName = null))),
        )

        assertNull(decoded.token)
        assertNull(decoded.deviceName)
        assertEquals(2, decoded.endpoints.size)
    }

    @Test
    fun roundTripKeepsScopes() {
        val original = payload().copy(scopes = listOf("files.read", "chat.write"))

        val decoded = requireNotNull(ConnectionShare.decode(ConnectionShare.encode(original)))

        assertEquals(listOf("files.read", "chat.write"), decoded.scopes)
    }

    // ---------------------------------------------------------------- 粘贴容错

    @Test
    fun acceptsMissingPrefixAndAnyCasingOfThePrefix() {
        val encoded = ConnectionShare.encode(payload())
        val body = encoded.removePrefix(ConnectionShare.PREFIX)
        val expected = ConnectionShare.decode(encoded)

        assertEquals(expected, ConnectionShare.decode(body))
        assertEquals(expected, ConnectionShare.decode("DSH1:$body"))
        assertEquals(expected, ConnectionShare.decode("dsh1:$body"))
        assertEquals(expected, ConnectionShare.decode("DsH1:$body"))
    }

    @Test
    fun toleratesWeChatNewlinesSpacesAndInvisibleCharacters() {
        val encoded = ConnectionShare.encode(payload())
        val body = encoded.removePrefix(ConnectionShare.PREFIX)
        val mangled = buildString {
            append("\uFEFF") // BOM
            append("\r\n")
            body.chunked(17).forEach { chunk -> append(chunk).append('\n').append("\u3000").append(' ') }
            append("\u00A0\u200B") // nbsp + 零宽空格
        }
        val expected = ConnectionShare.decode(encoded)

        assertEquals(expected, ConnectionShare.decode(mangled))
        assertEquals(expected, ConnectionShare.decode("DSH1:$mangled"))
    }

    @Test
    fun toleratesWhitespaceInsideThePrefixItself() {
        val body = ConnectionShare.encode(payload()).removePrefix(ConnectionShare.PREFIX)
        val expected = ConnectionShare.decode(body)

        assertEquals(expected, ConnectionShare.decode("DS\nH1:$body"))
        assertEquals(expected, ConnectionShare.decode("DS H1: $body"))
    }

    @Test
    fun acceptsStandardBase64AlphabetAndMissingPadding() {
        val expected = requireNotNull(ConnectionShare.decode("DSH1:$GOLDEN_BODY"))

        // 两版样本确实不同（URL-safe 版含 '_'，标准版含 '/'），否则这条测试没测到东西。
        assertNotEquals(GOLDEN_BODY, GOLDEN_STANDARD_BODY)
        assertEquals(expected, ConnectionShare.decode(GOLDEN_STANDARD_BODY))
        assertEquals(expected, ConnectionShare.decode("DSH1:$GOLDEN_STANDARD_BODY"))
        assertEquals(expected, ConnectionShare.decode(GOLDEN_STANDARD_PADDED))
        assertEquals(expected, ConnectionShare.decode("DSH1:$GOLDEN_STANDARD_PADDED"))
    }

    @Test
    fun toleratesStrayCharactersGluedAroundTheBase64Body() {
        val expected = ConnectionShare.decode("DSH1:$GOLDEN_BODY")

        assertEquals(expected, ConnectionShare.decode("DSH1:『$GOLDEN_BODY』"))
        assertEquals(expected, ConnectionShare.decode("DSH1:$GOLDEN_BODY。"))
        assertEquals(expected, ConnectionShare.decode("配置：\n$GOLDEN_BODY\n（完）"))
        // 前缀不在行首、前面还带着标签（标签里含 Latin 字母/数字，靠过滤是救不回来的）——
        // 用 DSH1: 哨兵在整段文本里定位，这两条才是真正测到「定位」而非「过滤」。
        assertEquals(expected, ConnectionShare.decode("配置：DSH1:$GOLDEN_BODY"))
        assertEquals(expected, ConnectionShare.decode("Pixel 9 Pro 的配置：DSH1:$GOLDEN_BODY"))
    }

    @Test
    fun decodingToleratesExtraLocalOnlyFieldsFromOtherGenerators() {
        // 若桌面端直接用 Payload.serializer()（带 id / kind / 探活字段 / 未来新增字段），也要能读。
        val decoded = requireNotNull(ConnectionShare.decode("DSH1:$FULL_LOCAL_BODY"))

        assertEquals("完整", decoded.displayName)
        assertEquals("家里", decoded.endpoints.single().label)
        assertEquals("http://192.168.1.126:3090", decoded.endpoints.single().baseUrl)
        assertEquals("tok", decoded.token)
        assertEquals("Pixel", decoded.deviceName)
        assertEquals(listOf("files.read"), decoded.scopes)
    }

    @Test
    fun normalizesApiV1SuffixAndTrailingSlashWhenDecoding() {
        val decoded = requireNotNull(ConnectionShare.decode("DSH1:$API_SUFFIX_BODY"))

        assertEquals("http://192.168.1.126:3090", decoded.endpoints.single().baseUrl)
    }

    // ---------------------------------------------------------------- 拒绝非法输入

    @Test
    fun rejectsGarbageWithoutThrowing() {
        assertNull(ConnectionShare.decode(""))
        assertNull(ConnectionShare.decode("   \n\t "))
        assertNull(ConnectionShare.decode("hello world"))
        assertNull(ConnectionShare.decode("这不是配置文本"))
        assertNull(ConnectionShare.decode("DSH1:"))
        assertNull(ConnectionShare.decode("DSH1:!!!!"))
        assertNull(ConnectionShare.decode("DSH1:===="))
        assertNull(ConnectionShare.decode("DSH1:" + base64Of("this is not json")))
        assertNull(ConnectionShare.decode("DSH1:" + base64Of("{}")))
        assertNull(ConnectionShare.decode("DSH1:" + base64Of("null")))
        assertNull(ConnectionShare.decode("DSH1:" + base64Of("[1,2,3]")))
    }

    @Test
    fun rejectsPayloadWithoutEndpoints() {
        assertNull(ConnectionShare.decode("DSH1:$EMPTY_ENDPOINTS_BODY"))
        assertNull(ConnectionShare.decode("DSH1:" + base64Of("""{"displayName":"没有地址"}""")))
    }

    @Test
    fun rejectsEndpointWhoseBaseUrlIsNotHttp() {
        assertNull(ConnectionShare.decode("DSH1:$BAD_SCHEME_BODY"))
        assertNull(ConnectionShare.decode("DSH1:$SCHEMELESS_BODY"))
        assertNull(ConnectionShare.decode("DSH1:" + base64Of("""{"displayName":"x","endpoints":[{"label":"空","baseUrl":""}]}""")))
        assertNull(ConnectionShare.decode("DSH1:" + base64Of("""{"displayName":"x","endpoints":[{"label":"无主机","baseUrl":"http://"}]}""")))
    }

    @Test
    fun rejectsPayloadWhenAnySingleEndpointIsUnusable() {
        // 一个地址合法、一个不合法 —— 整体必须拒绝，否则用户会得到一个永不成功的地址。
        assertNull(ConnectionShare.decode("DSH1:$MIXED_BODY"))
    }

    @Test
    fun neverThrowsOnHostileInput() {
        val real = ConnectionShare.encode(payload())
        val hostile = listOf(
            "",
            " ",
            "\u0000",
            "\u0001\u0002\u0003",
            "\uFFFD",
            "DSH1:",
            "DSH1::",
            "DSH1:=",
            "=",
            "=".repeat(1000),
            "dsh1:",
            "DSH1:!!!!",
            "DSH1:AAAA",
            "DSH1:AAA", // 长度 ≡1 (mod 4)，任何 Base64 变体都不合法
            "DSH1:" + "A".repeat(4096),
            real.dropLast(1),
            real.dropLast(7),
            real.drop(3),
            real + real,
            real.replace(ConnectionShare.PREFIX, ""),
            base64Of("""{"displayName":"x","endpoints":"不是数组"}"""),
            base64Of("""{"displayName":"x","endpoints":[{}]}"""),
            base64Of("""{"displayName":"x","endpoints":[{"baseUrl":123}]}"""),
        )

        hostile.forEach { input ->
            val outcome = runCatching { ConnectionShare.decode(input) }
            assertTrue(
                "decode 不应抛异常：输入=${input.take(40)}，异常=${outcome.exceptionOrNull()}",
                outcome.isSuccess,
            )
        }
    }

    @Test
    fun encodeRejectsUnusableConfigurationInsteadOfProducingAShareText() {
        // 生成端此刻人还在电脑前，能立刻改；把坏配置生成出来、等用户到了外面才发现才是灾难。
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionShare.encode(ConnectionShare.Payload("家里的电脑", emptyList(), token = "t"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionShare.encode(
                ConnectionShare.Payload(
                    "家里的电脑",
                    listOf(Endpoint(id = "", label = "坏", baseUrl = "ftp://192.168.1.1:3090")),
                    token = "t",
                ),
            )
        }
    }

    // ---------------------------------------------------------------- buildHost

    @Test
    fun buildHostMapsFieldsAndInfersEndpointKind() {
        val host = ConnectionShare.buildHost(
            ConnectionShare.Payload(
                displayName = "家里的电脑",
                endpoints = listOf(
                    Endpoint(id = "", label = "家里局域网", baseUrl = "http://192.168.1.126:3090"),
                    Endpoint(id = "", label = "公司虚拟网", baseUrl = "http://100.101.102.103:3090"),
                    Endpoint(id = "", label = "公网", baseUrl = "https://dsh.example.com"),
                ),
                token = "tok_abc123",
                deviceName = "Pixel 9",
                scopes = listOf("files.read"),
            ),
        ) { "host-1" }

        assertEquals("host-1", host.id)
        assertEquals("家里的电脑", host.displayName)
        assertEquals(listOf("host-1-ep1", "host-1-ep2", "host-1-ep3"), host.endpoints.map { it.id })
        assertEquals(
            listOf(EndpointKind.LAN, EndpointKind.VIRTUAL_NET, EndpointKind.UNKNOWN),
            host.endpoints.map { it.kind },
        )
        assertTrue(host.endpoints.all { it.enabled })
        // 新导入的地址还没探过活，不该带着别人的历史数据。
        assertTrue(host.endpoints.all { it.lastLatencyMs == null && it.lastCheckedAt == null && it.lastError == null })
        assertNull(host.preferredEndpointId)
        assertTrue(host.createdAt > 0L)
        assertEquals(host.createdAt, host.updatedAt)

        val credential = requireNotNull(host.credential)
        assertEquals(CredentialKind.DEVICE_TOKEN, credential.kind)
        assertEquals("tok_abc123", credential.secret)
        assertEquals("Pixel 9", credential.deviceName)
        assertEquals(listOf("files.read"), credential.scopes)
        assertTrue(credential.issuedAt in 1..host.createdAt)
        assertTrue(!credential.isExpired())
    }

    @Test
    fun buildHostCallsIdFactoryOnceAndKeepsProvidedEndpointIds() {
        var calls = 0
        val host = ConnectionShare.buildHost(
            ConnectionShare.Payload(
                displayName = "家里的电脑",
                endpoints = listOf(Endpoint(id = "ep-keep", label = "家里", baseUrl = "http://192.168.1.126:3090")),
                token = "t",
            ),
        ) { calls++; "host-$calls" }

        assertEquals(1, calls)
        assertEquals("host-1", host.id)
        assertEquals("ep-keep", host.endpoints.single().id)
    }

    @Test
    fun buildHostWithoutTokenHasNoCredential() {
        val host = ConnectionShare.buildHost(
            ConnectionShare.Payload(
                displayName = "家里的电脑",
                endpoints = listOf(Endpoint(id = "", label = "家里", baseUrl = "http://192.168.1.126:3090")),
                token = null,
            ),
        ) { "host-1" }

        assertNull(host.credential)
        assertEquals("http://192.168.1.126:3090", host.endpoints.single().baseUrl)
        assertEquals(EndpointKind.LAN, host.endpoints.single().kind)
    }

    @Test
    fun buildHostFallsBackToBaseUrlAsLabelAndNormalizesTheAddress() {
        val host = ConnectionShare.buildHost(
            ConnectionShare.Payload(
                displayName = "  ",
                endpoints = listOf(
                    Endpoint(id = "", label = "  ", baseUrl = " http://192.168.1.126:3090/api/v1/ "),
                ),
            ),
        ) { "host-1" }

        assertEquals("电脑", host.displayName)
        assertEquals("http://192.168.1.126:3090", host.endpoints.single().baseUrl)
        assertEquals("http://192.168.1.126:3090", host.endpoints.single().label)
    }

    // ---------------------------------------------------------------- 长度预算

    @Test
    fun encodedTextFitsInOneWeChatMessage() {
        val threeEndpoints = ConnectionShare.Payload(
            displayName = "家里的电脑",
            endpoints = listOf(
                Endpoint(id = "ep-1", label = "家里局域网", baseUrl = "http://192.168.1.126:3090"),
                Endpoint(id = "ep-2", label = "公司虚拟网", baseUrl = "http://100.101.102.103:3090"),
                Endpoint(id = "ep-3", label = "公网域名", baseUrl = "https://dsh.example.com"),
            ),
            token = "dsh_dev_1234abcd",
            deviceName = "Pixel 9",
        )
        val encoded = ConnectionShare.encode(threeEndpoints)

        // 定稿预算 600 字符（微信单条约 2000，留足余量）；实测约 395。
        assertTrue("3 个地址的配置要在 600 字符内，实际 ${encoded.length}", encoded.length <= 600)
        // 紧凑度回归护栏：实测约 395，明显变胖说明线格式里混进了本机冗余字段。
        assertTrue("线格式变胖了，实际 ${encoded.length}", encoded.length <= 450)
        assertTrue("分享文本必须是单行", encoded.none { it.isWhitespace() })
        assertTrue("要能塞进一条微信消息", encoded.length <= 2000)
        assertTrue(encoded.startsWith(ConnectionShare.PREFIX))

        // 两个地址 + 较长的令牌同样要留在预算内。
        val twoEndpoints = threeEndpoints.copy(
            endpoints = threeEndpoints.endpoints.take(2),
            token = "dsh_dev_" + "a".repeat(25),
            deviceName = "Pixel 9 Pro",
        )
        assertTrue(ConnectionShare.encode(twoEndpoints).length <= 600)

        // 线格式里不该出现 Endpoint.id 这类本机冗余字段（它是 400 字符预算的主要杀手）。
        val body = encoded.removePrefix(ConnectionShare.PREFIX)
        val padded = body + "=".repeat((4 - body.length % 4) % 4)
        val jsonText = Base64.getUrlDecoder().decode(padded).toString(Charsets.UTF_8)
        assertTrue(jsonText.contains("\"baseUrl\""))
        assertTrue("冗余的本机 id 不该进分享文本：$jsonText", !jsonText.contains("\"id\""))
    }

    private companion object {
        /** 冻结样本：`{"displayName":"家里的电脑","endpoints":[{"label":"家里局域网","baseUrl":"http://192.168.1.126:3090"}],"token":"tok_abc123","deviceName":"Pixel 9"}` */
        const val GOLDEN_BODY =
            "eyJkaXNwbGF5TmFtZSI6IuWutumHjOeahOeUteiEkSIsImVuZHBvaW50cyI6W3sibGFiZWwiOiLlrrbph4zlsYDln5_nvZEiLCJiYXNlVXJsIjoiaHR0cDovLzE5Mi4xNjguMS4xMjY6MzA5MCJ9XSwidG9rZW4iOiJ0b2tfYWJjMTIzIiwiZGV2aWNlTmFtZSI6IlBpeGVsIDkifQ"

        /** 同一份 JSON 的标准 Base64 字母表版本（`_` → `/`），无填充。 */
        const val GOLDEN_STANDARD_BODY =
            "eyJkaXNwbGF5TmFtZSI6IuWutumHjOeahOeUteiEkSIsImVuZHBvaW50cyI6W3sibGFiZWwiOiLlrrbph4zlsYDln5/nvZEiLCJiYXNlVXJsIjoiaHR0cDovLzE5Mi4xNjguMS4xMjY6MzA5MCJ9XSwidG9rZW4iOiJ0b2tfYWJjMTIzIiwiZGV2aWNlTmFtZSI6IlBpeGVsIDkifQ"

        /** [GOLDEN_STANDARD_BODY] 补上 `=` 填充的版本。 */
        const val GOLDEN_STANDARD_PADDED =
            "eyJkaXNwbGF5TmFtZSI6IuWutumHjOeahOeUteiEkSIsImVuZHBvaW50cyI6W3sibGFiZWwiOiLlrrbph4zlsYDln5/nvZEiLCJiYXNlVXJsIjoiaHR0cDovLzE5Mi4xNjguMS4xMjY6MzA5MCJ9XSwidG9rZW4iOiJ0b2tfYWJjMTIzIiwiZGV2aWNlTmFtZSI6IlBpeGVsIDkifQ=="

        /** `{"displayName":"空配置","endpoints":[]}` */
        const val EMPTY_ENDPOINTS_BODY =
            "eyJkaXNwbGF5TmFtZSI6IuepuumFjee9riIsImVuZHBvaW50cyI6W119"

        /** `{"displayName":"坏地址","endpoints":[{"label":"ftp","baseUrl":"ftp://192.168.1.1:3090"}]}` */
        const val BAD_SCHEME_BODY =
            "eyJkaXNwbGF5TmFtZSI6IuWdj-WcsOWdgCIsImVuZHBvaW50cyI6W3sibGFiZWwiOiJmdHAiLCJiYXNlVXJsIjoiZnRwOi8vMTkyLjE2OC4xLjE6MzA5MCJ9XX0"

        /** `{"displayName":"混合","endpoints":[{"label":"ok","baseUrl":"http://192.168.1.1:3090"},{"label":"bad","baseUrl":"192.168.1.2:3090"}]}` */
        const val MIXED_BODY =
            "eyJkaXNwbGF5TmFtZSI6Iua3t-WQiCIsImVuZHBvaW50cyI6W3sibGFiZWwiOiJvayIsImJhc2VVcmwiOiJodHRwOi8vMTkyLjE2OC4xLjE6MzA5MCJ9LHsibGFiZWwiOiJiYWQiLCJiYXNlVXJsIjoiMTkyLjE2OC4xLjI6MzA5MCJ9XX0"

        /** `{"displayName":"带后缀","endpoints":[{"label":"家里","baseUrl":"http://192.168.1.126:3090/api/v1"}]}` */
        const val API_SUFFIX_BODY =
            "eyJkaXNwbGF5TmFtZSI6IuW4puWQjue8gCIsImVuZHBvaW50cyI6W3sibGFiZWwiOiLlrrbph4wiLCJiYXNlVXJsIjoiaHR0cDovLzE5Mi4xNjguMS4xMjY6MzA5MC9hcGkvdjEifV19"

        /** 带 id / kind / 探活字段 / scopes / 未来新增字段的「另一个生成器」样本。 */
        const val FULL_LOCAL_BODY =
            "eyJkaXNwbGF5TmFtZSI6IuWujOaVtCIsImVuZHBvaW50cyI6W3siaWQiOiJlcC0xIiwibGFiZWwiOiLlrrbph4wiLCJiYXNlVXJsIjoiaHR0cDovLzE5Mi4xNjguMS4xMjY6MzA5MCIsImtpbmQiOiJMQU4iLCJsYXN0TGF0ZW5jeU1zIjoxMiwibGFzdENoZWNrZWRBdCI6MSwibGFzdEVycm9yIjpudWxsLCJlbmFibGVkIjp0cnVlfV0sInRva2VuIjoidG9rIiwiZGV2aWNlTmFtZSI6IlBpeGVsIiwic2NvcGVzIjpbImZpbGVzLnJlYWQiXSwidW5rbm93bkZ1dHVyZUtleSI6NDJ9"

        /** `{"displayName":"无协议","endpoints":[{"label":"x","baseUrl":"192.168.1.2:3090"}]}` */
        const val SCHEMELESS_BODY =
            "eyJkaXNwbGF5TmFtZSI6IuaXoOWNj-iuriIsImVuZHBvaW50cyI6W3sibGFiZWwiOiJ4IiwiYmFzZVVybCI6IjE5Mi4xNjguMS4yOjMwOTAifV19"
    }
}
