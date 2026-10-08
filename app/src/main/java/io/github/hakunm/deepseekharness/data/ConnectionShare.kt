package io.github.hakunm.deepseekharness.data

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 「配置导入」的编解码：把一台电脑的连接信息压成一段能塞进微信的短文本，反过来也能还原。
 *
 * ## 为什么是文本，而不是二维码
 *
 * 真实场景是「人已经出门在外，电脑留在家里」。任何要求配对那一刻电脑必须在旁的方案
 * （扫码、念配对码）都会卡死在这个场景上：看不到屏幕就扫不了码。文本则可以由用户在电脑上
 * 复制、发给自己（微信文件传输助手 / 邮件 / 私密笔记），到目的地再粘贴 —— 全程不需要
 * 电脑在身边，也不需要摄像头。因此这条路径的容错要求高于普通解析：用户粘贴时
 * 已经离开电脑，此刻失败就等于整个方案失败。
 *
 * ## 线格式（wire format，桌面端生成器必须与此一致）
 *
 * ```
 * DSH1:<UTF-8 JSON 的 Base64 URL-safe 无填充编码>
 * ```
 *
 * JSON 结构（字段名即 [Payload] 的字段名）：
 *
 * ```json
 * {
 *   "displayName": "家里的电脑",
 *   "endpoints": [
 *     { "label": "家里局域网", "baseUrl": "http://192.168.1.126:3090" },
 *     { "label": "公司虚拟网", "baseUrl": "http://100.101.102.103:3090" }
 *   ],
 *   "token": "设备令牌",
 *   "deviceName": "Pixel 9",
 *   "scopes": ["files.read"]
 * }
 * ```
 *
 * 注意几点刻意的取舍：
 * - **不写 `Endpoint.id`、探活历史、`kind`、`enabled`**：这些是「本机状态」，换到另一台手机
 *   就没有意义，写进去每个地址要白花约 40 个 Base64 字符。导入端由 [buildHost] 重新分配
 *   并重新推断 [EndpointKind]。解码端反过来是宽容的：即使对面把 `id` / `kind` 一起塞进来
 *   （例如直接用 `Payload.serializer()` 输出的 JSON），也能正常读出。
 * - `token` / `deviceName` 为 null 时整个键都不写；`scopes` 为空时不写。
 * - 地址统一归一为**不含** `/api/v1` 后缀的基地址。
 *
 * 长度预算：定稿为 **≤ 600 字符**（微信单条上限约 2000 字符，留足余量）。实测：
 * 2 地址 + 40 字符令牌 ≈ 351；3 地址 + 16 字符令牌 ≈ 395；3 地址 + 40 字符令牌 ≈ 432；
 * 每个地址约 55 字符。预算有富余，所以遇到「再短一点」和「再多容错一点」的取舍一律选后者。
 *
 * ## 安全边界（重要，使用前必读）
 *
 * [encode] **刻意不做加密**：这段文本本身就是凭据载体（内含设备令牌），等价于一把钥匙。
 * Base64 只是编码，不是加密 —— 任何拿到它的人都能解出明文 JSON。安全边界由
 * 「用户把这段文本发给谁」决定，所以：
 * - **不要把这段文本发到群聊、公开论坛、截图、issue，或粘贴给第三方工具**；
 * - 只发给可信渠道（自己、私密笔记、密码管理器）；
 * - 一旦怀疑泄露，正确做法是立刻在电脑上**吊销该设备令牌**，而不是指望它「看不懂」。
 *
 * ## 解码容错
 *
 * [decode] 面向真实的粘贴环境，接受（等价地说：[encode] 的产物经过微信/邮件折腾后仍能还原）：
 * 首尾与**内部**的空白和换行、零宽字符与 BOM、带或不带前缀（`DSH1:` / `dsh1:` / `DsH1:`）、
 * 前缀出现在整段文本的任意位置（前面带着「Pixel 9 的配置：」这类标签也能定位）、
 * URL-safe 与标准 Base64 字母表、缺失的 `=` 填充、结尾被粘上的标点符号（`。`、`『』` 等）。
 * **任何不合法输入一律返回 null，绝不抛异常** —— 崩溃或误连都比明确说「这段文本无效」更糟。
 */
object ConnectionShare {
    const val PREFIX = "DSH1:"

    @Serializable
    data class Payload(
        val displayName: String,
        val endpoints: List<Endpoint>,
        val token: String? = null,
        val deviceName: String? = null,
        val scopes: List<String> = emptyList(),
    )

    /** `/api/v1` 是 [DshClient] 的接口前缀，不是地址的一部分，分享与存储都只保留基地址。 */
    private const val API_SUFFIX = "/api/v1"

    /** 极端情况下（displayName 为空）导入端也要有个能显示的名字。 */
    private const val FALLBACK_HOST_NAME = "电脑"

    /**
     * encode/decode 共用的 Json 配置。
     *
     * - `encodeDefaults = false`：默认值不写，直接省掉 `"scopes":[]`、`"enabled":true` 这些字符；
     * - `explicitNulls = false`：`token` / `deviceName` 为 null 时连键都不写；
     * - `ignoreUnknownKeys = true`：对面多写了字段（含 `id`、`kind`）也照读不误，向前兼容；
     * - `isLenient = true`：对轻微不规范的 JSON 也宽容一点，反正后面还有地址校验兜底。
     */
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 把配置编码成可粘贴文本：`DSH1:` + URL-safe 无填充 Base64。
     *
     * 编码前会「瘦身」：丢掉本机专属字段（[Endpoint.id]、探活历史、kind），清掉首尾空白，
     * 把 baseUrl 归一为不含 `/api/v1` 的基地址。
     *
     * 非法输入（地址为空、地址不是 http/https）会抛 [IllegalArgumentException] —— 这是唯一
     * 故意「抛异常」的地方：生成端此刻人还在电脑前，能立刻看到并修正；把坏配置生成出来、
     * 等用户到了外面粘贴才发现无效，才是真正的灾难。
     */
    fun encode(payload: Payload): String {
        val wire = payload.toWire()
        require(wire.endpoints.isNotEmpty()) { "至少需要一个地址才能生成配置文本" }
        wire.endpoints.forEach { endpoint ->
            require(isUsableBaseUrl(endpoint.baseUrl)) {
                "地址必须是 http:// 或 https:// 开头（去掉 /api/v1 之后）：${endpoint.baseUrl}"
            }
        }
        val body = json.encodeToString(WirePayload.serializer(), wire)
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(body.toByteArray(Charsets.UTF_8))
    }

    /**
     * 解析用户粘贴的文本。任何失败都返回 null，绝不抛异常。
     *
     * 见类文档「解码容错」一节的输入清单；解析成功后还会校验「至少一个地址、每个地址都是
     * http/https」，不通过同样返回 null（宁可拒绝，也不给用户一个连不上的 Host）。
     */
    fun decode(text: String): Payload? {
        val body = stripPrefix(clean(text))
        val bytes = decodeBase64(body) ?: return null
        val wire = parseWire(bytes.toString(Charsets.UTF_8)) ?: return null
        return wire.toPayload().takeIf(::isUsable)
    }

    /**
     * 把配置变成一个可入库的 [Host]。
     *
     * - `id` 由 [idFactory] 提供（只调用一次，用于 Host 本身；地址 id 优先沿用 payload 里
     *   已有的值，没有则派生成 `<hostId>-ep1`、`<hostId>-ep2` …，保证同一 Host 内唯一）；
     * - 每个地址都重新推断 [EndpointKind]，并清空探活历史（新导入的地址还没探过活）；
     * - `token` 非空时建一条 [CredentialKind.DEVICE_TOKEN] 凭据；为空则没有凭据；
     * - `preferredEndpointId` 保持 null —— 语义是「上次成功连接的地址」，刚导入时并不存在，
     *   选路交给 [EndpointSelection] 用真实探活结果决定；
     * - `createdAt` / `updatedAt` / `issuedAt` 都取当前时间。
     */
    fun buildHost(
        payload: Payload,
        idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    ): Host {
        val hostId = idFactory()
        val now = System.currentTimeMillis()
        val endpoints = payload.endpoints.mapIndexed { index, endpoint ->
            val baseUrl = normalizeBaseUrl(endpoint.baseUrl)
            Endpoint(
                id = endpoint.id.trim().ifEmpty { "$hostId-ep${index + 1}" },
                label = endpoint.label.trim().ifEmpty { baseUrl },
                baseUrl = baseUrl,
                kind = EndpointKind.infer(baseUrl),
            )
        }
        val token = payload.token?.trim()?.takeIf(String::isNotEmpty)
        return Host(
            id = hostId,
            displayName = payload.displayName.trim().ifEmpty { FALLBACK_HOST_NAME },
            endpoints = endpoints,
            credential = token?.let { secret ->
                Credential(
                    kind = CredentialKind.DEVICE_TOKEN,
                    secret = secret,
                    deviceName = payload.deviceName?.trim()?.takeIf(String::isNotEmpty),
                    scopes = payload.scopes.map(String::trim).filter(String::isNotEmpty),
                    issuedAt = now,
                )
            },
            createdAt = now,
            updatedAt = now,
        )
    }

    // ------------------------------------------------------------------
    // 线格式用的私有表示
    //
    // 为什么不直接用 Payload.serializer()：contract 里 Endpoint.id 是必填字段（无默认值），
    // 直接序列化会把一个本机 UUID 写进分享文本，每个地址白占约 40 个字符，两三个地址就顶破
    // 400 字符的预算。这里用一个更窄的线格式表示，同时让解码端对多余字段保持宽容。
    // ------------------------------------------------------------------

    @Serializable
    private data class WirePayload(
        val displayName: String = "",
        val endpoints: List<WireEndpoint> = emptyList(),
        val token: String? = null,
        val deviceName: String? = null,
        val scopes: List<String> = emptyList(),
    )

    @Serializable
    private data class WireEndpoint(
        val label: String = "",
        val baseUrl: String,
    )

    private fun Payload.toWire() = WirePayload(
        displayName = displayName.trim(),
        endpoints = endpoints.map { endpoint ->
            WireEndpoint(label = endpoint.label.trim(), baseUrl = normalizeBaseUrl(endpoint.baseUrl))
        },
        token = token?.trim()?.takeIf(String::isNotEmpty),
        deviceName = deviceName?.trim()?.takeIf(String::isNotEmpty),
        scopes = scopes.map(String::trim).filter(String::isNotEmpty),
    )

    private fun WirePayload.toPayload() = Payload(
        displayName = displayName.trim(),
        endpoints = endpoints.map { Endpoint(id = "", label = it.label.trim(), baseUrl = normalizeBaseUrl(it.baseUrl)) },
        token = token?.trim()?.takeIf(String::isNotEmpty),
        deviceName = deviceName?.trim()?.takeIf(String::isNotEmpty),
        scopes = scopes.map(String::trim).filter(String::isNotEmpty),
    )

    /** 基础校验：至少一个地址，且每个地址去掉 `/api/v1` 后都是 http/https 且有主机名。 */
    private fun isUsable(payload: Payload): Boolean =
        payload.endpoints.isNotEmpty() && payload.endpoints.all { isUsableBaseUrl(it.baseUrl) }

    private fun isUsableBaseUrl(raw: String): Boolean {
        val normalized = normalizeBaseUrl(raw)
        val schemeLength = when {
            normalized.startsWith("http://") -> 7
            normalized.startsWith("https://") -> 8
            else -> return false
        }
        return normalized.substring(schemeLength)
            .substringBefore('/')
            .substringBefore(':')
            .isNotBlank()
    }

    /**
     * 归一基地址：去首尾空白与尾斜杠、去掉 `/api/v1` 后缀、协议名统一小写。
     * 幂等 —— [encode] 与 [buildHost] 都会调用它。
     */
    private fun normalizeBaseUrl(raw: String): String {
        var value = raw.trim().trimEnd('/')
        if (value.endsWith(API_SUFFIX)) value = value.dropLast(API_SUFFIX.length).trimEnd('/')
        return when {
            value.regionMatches(0, "http://", 0, 7, ignoreCase = true) -> "http://" + value.substring(7)
            value.regionMatches(0, "https://", 0, 8, ignoreCase = true) -> "https://" + value.substring(8)
            else -> value
        }
    }

    // ------------------------------------------------------------------
    // 解码容错
    // ------------------------------------------------------------------

    /** 零宽字符、BOM 之类「看不见但不是空白」的字符，粘贴链路里很常见。 */
    private val INVISIBLE_CHARS = charArrayOf('\uFEFF', '\u200B', '\u200C', '\u200D', '\u2060')

    /** 去掉所有空白（含微信插入的换行、邮件折行、中文全角空格、nbsp）与零宽字符。 */
    private fun clean(text: String): String =
        text.filterNot { char -> char.isWhitespace() || char in INVISIBLE_CHARS }

    /**
     * 前缀可选、大小写不敏感，并且允许它出现在整段文本的任意位置。
     *
     * 因为空白已被清掉，「DS\nH1:」这种被拆开的写法也能命中。`DSH1:` 是刻意设计的哨兵，
     * 所以可以放心地在全文中找它 —— 于是「Pixel 9 Pro 的配置：DSH1:xxxx」这种带标签的粘贴
     * 也能救回来（标签里的 Latin 字母/数字本身就在 Base64 字母表内，靠过滤字符救不回来）。
     * 找不到哨兵就把整段当作纯 Base64 处理。
     */
    private fun stripPrefix(text: String): String {
        val at = text.indexOf(PREFIX, startIndex = 0, ignoreCase = true)
        return if (at >= 0) text.substring(at + PREFIX.length) else text
    }

    /**
     * 解 Base64：先剔掉字母表之外的字符（结尾粘上的句号、引号、标签文字），再补 `=` 填充，
     * 然后先按 URL-safe、再按标准字母表解 —— [encode] 用 URL-safe，但邮件/网页转码可能把它
     * 换成标准字母表，两种都得认。
     */
    private fun decodeBase64(body: String): ByteArray? {
        val alphabetOnly = body.filter(::isBase64Char)
        if (alphabetOnly.isEmpty()) return null
        val remainder = alphabetOnly.length % 4
        // 长度 ≡ 1 (mod 4) 在任何 Base64 变体下都不可能，直接判死，省得后面空转。
        if (remainder == 1) return null
        val padded = if (remainder == 0) alphabetOnly else alphabetOnly + "=".repeat(4 - remainder)
        runCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull()?.let { return it }
        return runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
    }

    private fun isBase64Char(char: Char): Boolean =
        char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
            char == '+' || char == '/' || char == '-' || char == '_' || char == '='

    /** 先按原样解析；失败则截出最外层 `{ ... }` 再试一次（解码结果里混了前后缀文字的情况）。 */
    private fun parseWire(raw: String): WirePayload? {
        runCatching { json.decodeFromString(WirePayload.serializer(), raw) }
            .getOrNull()?.let { return it }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            json.decodeFromString(WirePayload.serializer(), raw.substring(start, end + 1))
        }.getOrNull()
    }
}
