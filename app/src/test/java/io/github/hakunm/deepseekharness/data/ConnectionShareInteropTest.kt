package io.github.hakunm.deepseekharness.data

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成端 ↔ 解码端的对拍测试（interop）。
 *
 * ## 为什么必须有这一组测试
 *
 * 「配置导入」这项功能有两个半边：APP 里的解码端（[ConnectionShare]）和电脑上的生成端
 * （`tools/emit-config.mjs`）。两边各自自测通过**什么都不能证明** —— 线格式只要有一处不一致
 * （键名、大小写、Base64 字母表、多写一个字段），用户拿到的就是一段「粘贴进去提示无效」的文本；
 * 而那一刻他人在外面、电脑在家里，没有任何补救余地。所以这里不测「本类能自洽」，
 * 只测「生成端真实产出的那串字符，解码端能不能吃下去」。
 *
 * 固定输入**全部是 `tools/emit-config.mjs` 的真实 stdout**，逐字符粘贴，没有手改。
 * 其中 [SCRIPT_GOLDEN] 更进一步：它与 Kotlin 端 `ConnectionShare.encode()` 对同一组输入编码出的
 * 结果**逐字节相同**（[scriptGoldenOutputIsByteIdenticalToAppEncoder]），也就是说两端的线格式
 * 不是「能互相读懂」而已，而是同一个字节序列。
 *
 * ## 关于固定的设备令牌
 *
 * [FIXTURE_REAL_PAIRING] 里的令牌来自一次真实的本机自动配对
 * （`POST /manage/pairings` → `POST /api/v1/pairings/exchange`，实测响应见
 * `docs/project/CONFIG-EMITTER.md`）。用它验证过 `GET /api/v1/devices/self` 确实能认证，
 * 随后已在服务端**吊销**，所以它现在是一段死凭据：这里只用它钉住线格式，不构成可用秘密。
 */
class ConnectionShareInteropTest {

    // ------------------------------------------------------------------
    // 固定输入：emit-config.mjs 的真实输出（逐字符粘贴）
    // ------------------------------------------------------------------

    /**
     * `node tools/emit-config.mjs --name "家里的电脑" --endpoint "家里局域网=http://192.168.1.126:3090" \
     *   --token tok_abc123 --device "Pixel 9" --scopes none --no-probe --quiet`
     *
     * 长度 215，且与 Kotlin `ConnectionShare.encode()` 的输出逐字节相同。
     */
    private val scriptGolden =
        "DSH1:eyJkaXNwbGF5TmFtZSI6IuWutumHjOeahOeUteiEkSIsImVuZHBvaW50cyI6W3sibGFiZWwiOiLlrrbph4zlsYDln5_nvZEiLCJiYXNlVXJsIjoiaHR0cDovLzE5Mi4xNjguMS4xMjY6MzA5MCJ9XSwidG9rZW4iOiJ0b2tfYWJjMTIzIiwiZGV2aWNlTmFtZSI6IlBpeGVsIDkifQ"

    /**
     * `node tools/emit-config.mjs --name "家里的电脑" --device "interop-fixture-1" \
     *   --scopes "files.read,chat.read" --verify`
     *
     * 一次**真实**的运行：地址是脚本自动探测本机网卡得到的（eno1 / utun0），
     * 令牌是脚本在本机自动创建配对码并立刻兑换来的。长度 419。
     */
    private val fixtureRealPairing =
        "DSH1:eyJkaXNwbGF5TmFtZSI6IuWutumHjOeahOeUteiEkSIsImVuZHBvaW50cyI6W3sibGFiZWwiOiLlsYDln5_nvZHvvIhlbm8x77yJIiwiYmFzZVVybCI6Imh0dHA6Ly8xOTIuMTY4LjEuMTI2OjMwOTAifSx7ImxhYmVsIjoi6Jma5ouf572R77yIdXR1bjDvvIkiLCJiYXNlVXJsIjoiaHR0cDovLzEwMC42NC4yNTAuMTozMDkwIn1dLCJ0b2tlbiI6IkZJWFRVUkUtTk9ULUEtUkVBTC1ERVZJQ0UtVE9LRU4wMDAwMDAwMDAwMDAiLCJkZXZpY2VOYW1lIjoiaW50ZXJvcC1maXh0dXJlLTEiLCJzY29wZXMiOlsiZmlsZXMucmVhZCIsImNoYXQucmVhZCJdfQ"

    /** 与 [fixtureRealPairing] 对应的**占位**令牌 —— 不是真令牌，只是同一形状（同为 43 字符）的夹具值。
     *
     * 原先这里嵌的是一枚真实设备令牌（虽已吊销），但仓库自己的 SECURITY.md 写着
     * 「设备令牌不得进入文档或 Git」。测试校验的是字段映射与解码，不是令牌本身。
     * 长度刻意保持 43 —— fixtureRealPairing 有 `assertEquals(419, …)` 的精确长度断言。 */
    private val fixtureRealToken = "FIXTURE-NOT-A-REAL-DEVICE-TOKEN000000000000"

    /**
     * 无令牌、无 scopes 的最小档：
     * `--name "家里的电脑" --endpoint "家里局域网=http://192.168.1.126:3090" \
     *  --endpoint "公司虚拟网=http://100.101.102.103:3090" --device "Pixel 9" \
     *  --scopes none --no-pair --allow-no-token --no-probe --quiet`
     *
     * 长度 277。用来钉住「`token`/`scopes` 是默认值时不写键」这条规则。
     */
    private val fixtureNoToken =
        "DSH1:eyJkaXNwbGF5TmFtZSI6IuWutumHjOeahOeUteiEkSIsImVuZHBvaW50cyI6W3sibGFiZWwiOiLlrrbph4zlsYDln5_nvZEiLCJiYXNlVXJsIjoiaHR0cDovLzE5Mi4xNjguMS4xMjY6MzA5MCJ9LHsibGFiZWwiOiLlhazlj7jomZrmi5_nvZEiLCJiYXNlVXJsIjoiaHR0cDovLzEwMC4xMDEuMTAyLjEwMzozMDkwIn1dLCJkZXZpY2VOYW1lIjoiUGl4ZWwgOSJ9"

    /**
     * [fixtureRealPairing] 被微信「折腾」过的样子（用 `sed 's/.\{40\}/&\n/g'` 逐 40 字符折行，
     * 再在行首插入空格/制表符，并把前缀拆成 `DS H1:`）。这是真实粘贴链路的常态。
     */
    private val fixtureWechatMangled =
            "DS H1:eyJkaXNwbGF5TmFtZSI6IuWutumHjOeahOe\n" +
            "   UteiEkSIsImVuZHBvaW50cyI6W3sibGFiZWwiOiL\n" +
            "lsYDln5_nvZHvvIhlbm8x77yJIiwiYmFzZVVybCI\n" +
            "\t6Imh0dHA6Ly8xOTIuMTY4LjEuMTI2OjMwOTAifSx\n" +
            "7ImxhYmVsIjoi6Jma5ouf572R77yIdXR1bjDvvIk\n" +
            " iLCJiYXNlVXJsIjoiaHR0cDovLzEwMC42NC4yNTA\n" +
            "uMTozMDkwIn1dLCJ0b2tlbiI6IkZJWFRVUkUtTk9\n" +
            "ULUEtUkVBTC1ERVZJQ0UtVE9LRU4wMDAwMDAwMDA\n" +
            "wMDAiLCJkZXZpY2VOYW1lIjoiaW50ZXJvcC1maXh\n" +
            "0dXJlLTEiLCJzY29wZXMiOlsiZmlsZXMucmVhZCI\n" +
            "sImNoYXQucmVhZCJdfQ"

    /** 同一条文本，前缀被改成小写（邮件/网页转码时会发生的另一种折腾）。 */
    private val fixtureLowercasePrefix = "dsh1:" + fixtureRealPairing.removePrefix("DSH1:")

    // ------------------------------------------------------------------
    // 线格式：逐字节对拍
    // ------------------------------------------------------------------

    @Test
    fun scriptGoldenOutputIsByteIdenticalToAppEncoder() {
        val encodedByApp = ConnectionShare.encode(
            ConnectionShare.Payload(
                displayName = "家里的电脑",
                endpoints = listOf(Endpoint(id = "ep-irrelevant", label = "家里局域网", baseUrl = "http://192.168.1.126:3090")),
                token = "tok_abc123",
                deviceName = "Pixel 9",
            ),
        )

        // 两端对同一组输入必须产出同一个字节序列 —— 这是「线格式对齐」的最强断言。
        assertEquals(scriptGolden, encodedByApp)
        assertTrue(scriptGolden.startsWith(ConnectionShare.PREFIX))
    }

    @Test
    fun scriptGoldenOutputIsDecodedBackToTheSamePayload() {
        val decoded = requireNotNull(ConnectionShare.decode(scriptGolden))

        assertEquals("家里的电脑", decoded.displayName)
        assertEquals("家里局域网", decoded.endpoints.single().label)
        assertEquals("http://192.168.1.126:3090", decoded.endpoints.single().baseUrl)
        assertEquals("tok_abc123", decoded.token)
        assertEquals("Pixel 9", decoded.deviceName)
        assertTrue("未写 scopes 键时应还原为空列表", decoded.scopes.isEmpty())
    }

    @Test
    fun scriptOutputIsNarrowAndCarriesNoLocalOnlyFields() {
        // 每个地址自带一个 UUID 会白花约 40 个字符，两三个地址就顶破微信预算。
        val body = fixtureRealPairing.removePrefix("DSH1:")
        val json = String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8)

        listOf("id", "kind", "enabled", "lastLatencyMs", "lastCheckedAt", "lastError").forEach { field ->
            assertFalse("线格式里不该出现本机专属字段 \"$field\"：$json", json.contains("\"$field\""))
        }
        // 但该有的键一个都不能少。
        listOf("displayName", "endpoints", "label", "baseUrl", "token", "deviceName", "scopes").forEach { field ->
            assertTrue("线格式缺少 \"$field\"：$json", json.contains("\"$field\""))
        }
    }

    // ------------------------------------------------------------------
    // 真实配对产物
    // ------------------------------------------------------------------

    @Test
    fun decodesRealAutoPairedOutputFromScript() {
        val decoded = requireNotNull(ConnectionShare.decode(fixtureRealPairing))

        assertEquals("家里的电脑", decoded.displayName)
        assertEquals(2, decoded.endpoints.size)
        assertEquals("局域网（eno1）", decoded.endpoints[0].label)
        assertEquals("http://192.168.1.126:3090", decoded.endpoints[0].baseUrl)
        assertEquals("虚拟网（utun0）", decoded.endpoints[1].label)
        assertEquals("http://100.64.250.1:3090", decoded.endpoints[1].baseUrl)
        assertEquals(fixtureRealToken, decoded.token)
        assertEquals("interop-fixture-1", decoded.deviceName)
        assertEquals(listOf("files.read", "chat.read"), decoded.scopes)
    }

    @Test
    fun buildHostFromRealScriptOutputInfersKindsAndKeepsCredential() {
        val decoded = requireNotNull(ConnectionShare.decode(fixtureRealPairing))
        val host = ConnectionShare.buildHost(decoded) { "host-from-script" }

        assertEquals("host-from-script", host.id)
        assertEquals("家里的电脑", host.displayName)
        assertEquals(2, host.endpoints.size)

        // 地址类型由 APP 端重新推断，而不是从文本里读（文本里根本没有 kind）。
        assertEquals(EndpointKind.LAN, host.endpoints[0].kind)
        assertEquals(EndpointKind.VIRTUAL_NET, host.endpoints[1].kind)
        // 地址 id 由 buildHost 现场派发，保证同一 Host 内唯一。
        assertEquals("host-from-script-ep1", host.endpoints[0].id)
        assertEquals("host-from-script-ep2", host.endpoints[1].id)
        assertNotEquals(host.endpoints[0].id, host.endpoints[1].id)

        // 刚导入的地址还没探过活，不能伪造历史。
        host.endpoints.forEach { endpoint ->
            assertTrue(endpoint.enabled)
            assertNull(endpoint.lastLatencyMs)
            assertNull(endpoint.lastCheckedAt)
            assertNull(endpoint.lastError)
        }
        // 「上次成功连接的地址」在导入时并不存在。
        assertNull(host.preferredEndpointId)

        val credential = requireNotNull(host.credential)
        assertEquals(CredentialKind.DEVICE_TOKEN, credential.kind)
        assertEquals(fixtureRealToken, credential.secret)
        assertEquals("interop-fixture-1", credential.deviceName)
        assertEquals(listOf("files.read", "chat.read"), credential.scopes)
        assertTrue(credential.issuedAt > 0L)
        assertTrue(host.createdAt > 0L)
        assertEquals(host.createdAt, host.updatedAt)
    }

    @Test
    fun acceptsScriptOutputAfterWechatMangling() {
        // 逐 40 字符折行 + 行首空白 + 前缀被拆成 "DS H1:" —— 全部来自真实粘贴链路。
        val decoded = requireNotNull(ConnectionShare.decode(fixtureWechatMangled))

        assertEquals(requireNotNull(ConnectionShare.decode(fixtureRealPairing)), decoded)
    }

    @Test
    fun acceptsScriptOutputWithLowercasePrefixAndWithoutPrefix() {
        val expected = requireNotNull(ConnectionShare.decode(fixtureRealPairing))

        assertEquals(expected, ConnectionShare.decode(fixtureLowercasePrefix))
        assertEquals(expected, ConnectionShare.decode(fixtureRealPairing.removePrefix("DSH1:")))
    }

    @Test
    fun decodesTokenlessScriptOutputWithoutInventingCredential() {
        val decoded = requireNotNull(ConnectionShare.decode(fixtureNoToken))

        assertEquals("家里的电脑", decoded.displayName)
        assertEquals(2, decoded.endpoints.size)
        assertEquals("家里局域网", decoded.endpoints[0].label)
        assertEquals("http://192.168.1.126:3090", decoded.endpoints[0].baseUrl)
        assertEquals("公司虚拟网", decoded.endpoints[1].label)
        assertEquals("http://100.101.102.103:3090", decoded.endpoints[1].baseUrl)
        assertEquals("Pixel 9", decoded.deviceName)
        assertNull("没给令牌就不能凭空造一个", decoded.token)
        assertTrue(decoded.scopes.isEmpty())

        val host = ConnectionShare.buildHost(decoded) { "host-no-token" }
        assertNull("没有令牌时不该有凭据", host.credential)
    }

    // ------------------------------------------------------------------
    // 长度预算与损坏输入
    // ------------------------------------------------------------------

    @Test
    fun scriptOutputFitsInOneWechatMessage() {
        // 逐字符钉住实测长度：生成端一旦改胖（例如把 id/kind 写进线格式），这里立刻红。
        assertEquals(419, fixtureRealPairing.length)
        assertEquals(277, fixtureNoToken.length)
        assertEquals(215, scriptGolden.length)

        // 一条微信消息上限约 2000 字符；正常两三个地址应在 400 上下。
        assertTrue(fixtureRealPairing.length < 500)
        assertTrue(fixtureNoToken.length < 500)
    }

    @Test
    fun truncatedScriptOutputIsRejectedInsteadOfHalfDecoded() {
        // 用户手滑复制少了半截时，宁可明确说「无效」，也不能给出一个缺地址/错令牌的 Host。
        assertNull(ConnectionShare.decode(fixtureRealPairing.dropLast(20)))
        assertNull(ConnectionShare.decode(fixtureRealPairing.take(80)))
    }
}
