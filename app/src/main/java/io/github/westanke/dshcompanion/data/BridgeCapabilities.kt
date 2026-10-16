package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 服务端在 `connection.ready` 里自报的版本与能力位。
 *
 * 为什么客户端要读它：服务端"能做哪些事"是**运行时事实**，而安装文档里那句
 * 「要求 bridge ≥ 2.3.7」只是给用户看的安装门槛。二者不能混为一谈 ——
 * 用户完全可能连着一台旧插件，然后遇到「功能静默不工作」：
 *
 * - 审批卡片不弹，手机端看起来与「本来就没有待审批」**一模一样**；
 * - 问答卡片同理。
 *
 * 这个坑我们真踩过（卡片不弹连续坏在两个插件侧病根上，App 侧代码自始至终是对的，
 * 见 `PITFALLS.md` §bridge 版本基线）。读到能力位之后，客户端至少能**明说**
 * 「这台电脑的插件不支持这个功能」，而不是让用户干等。
 */
internal data class BridgeCapabilities(
    /** 服务端自报的插件版本；仅用于诊断展示，**不作为功能判据**。 */
    val bridgeVersion: String?,
    val capabilities: Set<String>,
)

/**
 * 本 App **依赖**的能力位：缺了就会出现「功能静默不工作」的那些。
 *
 * 只列这类，不列全部：能力位是给特性探测用的，把「锦上添花」也算进必需项
 * 会让每个稍旧的服务端都弹出警告，警告一多就没人看了。
 *
 * **这两个名字是跨仓契约**：服务端 `capabilities.spec.ts` 里有一条对应的钉子
 * （`keeps the two capabilities the Android app treats as required`），
 * 防止那边把它们当死代码删掉 —— 那样会让这里对**所有用户**误报「插件过旧」。
 */
internal val REQUIRED_CAPABILITIES: List<String> = listOf("approvals-v1", "questions-v1")

/**
 * 从 `connection.ready` 的 `data` 里读出能力清单。
 *
 * 读不到就返回 **null（未知）**，而不是「空清单」。两种情况一律算未知：
 *
 * - 字段缺失（旧服务端根本不报）；
 * - 清单为空数组。
 *
 * 为什么空数组也算未知：服务端在现实里不会声明「我一条能力都没有」，
 * 真实的空数组只可能来自字段被误删/降级；而把它当成「什么都缺」会让调用方
 * 弹出假警告 —— **假警告比没有警告更坏**，它教会用户忽略警告。
 * 代价是「服务端明确说自己没能力」这一（不存在的）情形被读成未知，
 * 结果是不提示，方向上是安全的。
 *
 * 调用方一律按「null → 不提示、不降级」处理。
 */
internal fun parseBridgeCapabilities(data: JsonElement): BridgeCapabilities? {
    val payload = data as? JsonObject ?: return null
    val version = (payload["bridgeVersion"] as? JsonPrimitive)?.content
    val declared = payload["capabilities"]
    val capabilities = declared?.let { element -> runCatching { capabilitySetOf(element) }.getOrNull() }
    if (capabilities.isNullOrEmpty()) return null
    return BridgeCapabilities(bridgeVersion = version, capabilities = capabilities)
}

/**
 * 能力位数组的容错读取：非**字符串**项直接跳过，不因为一项脏数据整条作废。
 *
 * 注意这里的 `isString` 不是多余的：`42` 同样是一个 `JsonPrimitive`，
 * 只看类型会把它读成能力名 `"42"`，于是一个数字错位就能让清单里多出一条
 * 永远不会匹配的假能力位（实测被单测抓过一次）。
 */
private fun capabilitySetOf(element: JsonElement): Set<String> {
    val array = element as? kotlinx.serialization.json.JsonArray ?: return emptySet()
    return array.mapNotNullTo(mutableSetOf()) { item ->
        (item as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/**
 * 服务端是否支持「列出已归档会话」（能力位 `session-archive-list-v1`）。
 *
 * 读不到能力清单时返回 **false**（不显示入口）——这是安全方向：老 bridge 会忽略
 * `archived` 参数并返回未归档列表，若此时展示入口，用户只会看到「一条归档都没有」这个**假结论**。
 */
internal fun supportsArchiveListing(capabilities: Set<String>): Boolean =
    "session-archive-list-v1" in capabilities

/**
 * 本 App 依赖、但服务端**没有声明**的能力位；空列表 = 没缺。
 *
 * 注意：调用方必须先确认「拿到了能力清单」（[parseBridgeCapabilities] 返回非 null），
 * 否则旧服务端会被判成「什么都缺」。
 */
internal fun missingRequiredCapabilities(capabilities: Set<String>): List<String> =
    REQUIRED_CAPABILITIES.filterNot { it in capabilities }
