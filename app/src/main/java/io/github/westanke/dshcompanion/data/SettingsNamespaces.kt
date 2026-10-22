package io.github.westanke.dshcompanion.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * ============================================================================
 * 按插件划分的设置命名空间：wire 模型 + schema → 控件的纯函数映射
 * ============================================================================
 *
 * 这一层刻意**不强类型化**。官方 Web 的设置文档是按插件分的命名空间
 * （`ui-chat`、`ui-theme`…），每个命名空间自带一份 JSON Schema 子集；装一个新插件
 * 就可能多一个命名空间、多几个字段。若给每个插件写一个 Kotlin 数据类，
 * 那就退回了「27 个插件 27 个页面」的老路 —— 本任务的全部意义就是**不要**那样。
 *
 * 于是：schema / value / user 一律用 `JsonElement` 承接，字段长什么样由
 * [SettingsSchema.fields] 在运行时读出来。控件类型与标题的人类化也在那里，
 * 都是纯函数（不碰 Android、不发请求），可以直接被 JVM 单测喂样本。
 *
 * 三个「缺了就退化」的判据（桥与 App 分批发版，字段一定会缺）：
 * - `properties` 缺失或空 → 用 `value` 的键兜底生成**只读**行，而不是装作没有设置；
 * - `type` 缺失/是 object/array → 只读折叠 JSON，不提供半吊子写入；
 * - `secrets` 里的路径 / 值是 `***` → 只读展示「已设置/未设置」，**绝不提交**。
 */

/** 服务端脱敏占位：密钥位在 `value` 里长这样。 */
internal const val SETTINGS_MASKED_VALUE = "***"

/** `GET settings/namespaces` 的响应。 */
@Serializable
data class SettingsNamespaceList(
    val writable: Boolean = false,
    /**
     * 本机是否已经生成过设置文档。
     *
     * 为 false 时 `namespaces` 可能为空 —— 那是**内核还没建文档**，不是错误，
     * 界面要给空态而不是弹红条（这条降级写在 `ui/SettingsNamespacesScreen.kt`）。
     */
    val hasDocument: Boolean = true,
    val namespaces: List<SettingsNamespace> = emptyList(),
)

/**
 * 一个命名空间的完整快照。
 *
 * 字段全部带默认值/可空：桥老一版可能只有 `ns/revision/schema/value/writable`，
 * 这里缺 `secrets`、`hasDocument` 之类也必须能解码 —— 否则老桥一整页设置都读不出来。
 */
@Serializable
data class SettingsNamespace(
    val ns: String = "",
    val revision: Int = 0,
    val schema: JsonObject? = null,
    val value: JsonElement? = null,
    val user: JsonElement? = null,
    val writable: Boolean = false,
    /** `HIGH_RISK_NAMESPACE` / `HOST_READONLY`；文案映射见 `ui/SettingsNamespacesScreen.kt`。 */
    val reason: String? = null,
    /** 这个命名空间作用于什么（副标题用，**不作逻辑依据**）。 */
    val applies: String? = null,
    val autoGenerate: Boolean = false,
    /**
     * 密钥位清单 —— 判断「这一项是密钥」的**权威信号**。
     *
     * 以前只看值是不是 `***`，但桥可能压根不回显值（字段缺失），那时就漏判、
     * 把一个密钥位渲染成可编辑输入框。`secrets[].path` 是服务端给的显式声明，
     * 两个判据都认，任一命中即只读。
     */
    val secrets: List<SettingsSecret> = emptyList(),
)

@Serializable
data class SettingsSecret(val path: List<String> = emptyList(), val set: Boolean = false)

/** `PATCH settings/namespaces/{ns}` 里的一条操作。当前只发 `set`。 */
@Serializable
data class SettingsOp(val op: String = "set", val path: List<String>, val value: JsonElement)

/** `PATCH` 请求体：ops + 乐观并发用的 expectedRevision。 */
@Serializable
data class SettingsNamespaceUpdate(val ops: List<SettingsOp>, val expectedRevision: Int? = null)

/** 自动渲染时能产出的控件种类（`Text` 是普通字符串输入，不属于原任务四类但必须可编辑）。 */
enum class SettingsControl { Enum, Boolean, Number, Text, Secret, ReadOnly }

/** 一个字段的渲染描述：纯数据，UI 只照着画，不再回头读 schema。 */
data class SettingsFieldSpec(
    val key: String,
    val control: SettingsControl,
    val title: String? = null,
    val description: String? = null,
    val enumValues: List<String> = emptyList(),
    val currentValue: JsonElement? = null,
    val defaultValue: JsonElement? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val integer: Boolean = false,
    val declaredSecret: Boolean = false,
    val secretSet: Boolean = false,
)

/**
 * schema → 字段描述的纯函数层。
 *
 * **顺序稳定**：走 `JsonObject.keys`（底层是 LinkedHashMap），与桥返回的 properties
 * 顺序一致。每次重新组合都从同一份 schema 读，用户不会看到字段跳来跳去。
 */
object SettingsSchema {

    /**
     * 读出这个命名空间要渲染的字段。
     *
     * `properties` 缺失/空时走 [fallbackFields]：用 `value` 的键生成只读行 ——
     * 桥的 schema 可能是 `{}`（老内核），但用户至少该看到「这个命名空间里现在有什么」。
     */
    fun fields(namespace: SettingsNamespace): List<SettingsFieldSpec> {
        val properties = (namespace.schema?.get("properties") as? JsonObject)
            ?.takeIf { it.isNotEmpty() } ?: return fallbackFields(namespace)
        val values = namespace.value as? JsonObject
        return properties.keys.map { key ->
            field(namespace, key, properties[key] as? JsonObject, values?.get(key))
        }
    }

    /** 没有 schema 时按 `value` 的键兜底，全部只读（不猜类型、不提供写入）。 */
    fun fallbackFields(namespace: SettingsNamespace): List<SettingsFieldSpec> {
        val values = namespace.value as? JsonObject ?: return emptyList()
        return values.keys.map { key ->
            val secret = secretFor(namespace, key)
            val masked = isMasked(values[key])
            SettingsFieldSpec(
                key = key,
                control = if (secret != null || masked) SettingsControl.Secret else SettingsControl.ReadOnly,
                currentValue = values[key],
                declaredSecret = secret != null,
                secretSet = secret?.set == true || masked,
            )
        }
    }

    private fun field(
        namespace: SettingsNamespace,
        key: String,
        schema: JsonObject?,
        current: JsonElement?,
    ): SettingsFieldSpec {
        val secret = secretFor(namespace, key)
        val masked = isMasked(current)
        val enumValues = (schema?.get("enum") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val type = schema.string("type")
        val control = when {
            // 密钥优先：即使 schema 说它是 string，也不能给输入框（那会出现「填了但不落库」或回显风险）。
            secret != null || masked -> SettingsControl.Secret
            enumValues.isNotEmpty() -> SettingsControl.Enum
            type == "boolean" -> SettingsControl.Boolean
            type == "number" || type == "integer" -> SettingsControl.Number
            type == "string" -> SettingsControl.Text
            else -> SettingsControl.ReadOnly
        }
        return SettingsFieldSpec(
            key = key,
            control = control,
            title = schema.string("title"),
            description = schema.string("description"),
            enumValues = enumValues,
            currentValue = current,
            defaultValue = schema?.get("default"),
            // 正规 JSON Schema 是 minimum/maximum；桥也可能用简写 min/max，两种都认。
            minimum = schema.number("minimum") ?: schema.number("min"),
            maximum = schema.number("maximum") ?: schema.number("max"),
            integer = type == "integer",
            declaredSecret = secret != null,
            secretSet = secret?.set == true || masked,
        )
    }

    /** `secrets[].path` 的**第一段**与本字段同名即视为密钥（当前只渲染顶层属性）。 */
    private fun secretFor(namespace: SettingsNamespace, key: String): SettingsSecret? =
        namespace.secrets.firstOrNull { it.path.firstOrNull() == key }

    private fun isMasked(value: JsonElement?): Boolean =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content == SETTINGS_MASKED_VALUE
}

/** 数字输入/步进的夹取；min > max 这种脏 schema 原样返回，不抛异常。 */
fun clampSettingsNumber(value: Double, minimum: Double?, maximum: Double?): Double {
    val lo = minimum ?: Double.NEGATIVE_INFINITY
    val hi = maximum ?: Double.POSITIVE_INFINITY
    return if (lo > hi) value else value.coerceIn(lo, hi)
}

/** `transcriptView` → `Transcript view`：schema 没给 title 时的兜底标题。 */
fun humanizeFieldName(key: String): String {
    if (key.isBlank()) return key
    val spaced = key
        .replace(CAMEL_BOUNDARY, "$1 $2")
        .replace('_', ' ')
        .replace('-', ' ')
    val words = spaced.split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return key
    return words.joinToString(" ") { it.lowercase() }.replaceFirstChar { it.uppercase() }
}

/** 只读字段的折叠内容。用独立的 pretty 实例，避免影响 wire 的紧凑编码。 */
fun prettySettingsJson(element: JsonElement): String =
    PRETTY.encodeToString(JsonElement.serializer(), element)

private val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")
private val PRETTY = Json { prettyPrint = true }

// 小工具：把 JsonObject 的常见读取收在一处，省得每个字段都写一遍类型转换。
private fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject?.number(key: String): Double? =
    (this?.get(key) as? JsonPrimitive)?.doubleOrNull
