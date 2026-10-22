package io.github.westanke.dshcompanion.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

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
        val properties = propertiesOf(namespace.schema) ?: return fallbackFields(namespace)
        val values = namespace.value as? JsonObject
        return properties.keys.map { key ->
            field(namespace, key, properties[key] as? JsonObject, values?.get(key))
        }
    }

    /**
     * 取出这次要渲染的字段 schema。
     *
     * 两代格式都得认：
     * - **JSON Schema**：`{"type":"object","properties":{…}}`；
     * - **schemastery**：`{"uid":12427,"refs":{"12427":{"type":"object","dict":{…}}}}` ——
     *   内核 `settingsController.describe()` **实际发的就是后者**，`enum` 不是数组，
     *   而是一个 `union` 节点指向若干 `const` 节点。
     *
     * 只认前者的后果是真机上撞到的：所有字段退化成只读行，字号那栏孤零零显示「14」，
     * 点不动、改不了、保存键永远灰。所以这里先按 JSON Schema 取，取不到再现场把
     * schemastery 的引用图摊平成 JSON Schema 子集。
     */
    private fun propertiesOf(schema: JsonObject?): JsonObject? =
        (schema?.get("properties") as? JsonObject)?.takeIf { it.isNotEmpty() }
            ?: Schemastery.expand(schema)

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
        // schemastery 的密钥位长这样：`{"type":"string","meta":{"role":"secret"}}`。
        // 摊平后落成 `x-secret`，与 `secrets[]` 两条判据任一命中都按密钥处理。
        val declaredInSchema = schema.boolean("x-secret") == true
        val control = when {
            // 密钥优先：即使 schema 说它是 string，也不能给输入框（那会出现「填了但不落库」或回显风险）。
            secret != null || masked || declaredInSchema -> SettingsControl.Secret
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
            declaredSecret = secret != null || declaredInSchema,
            secretSet = secret?.set == true || masked,
        )
    }

    /** `secrets[].path` 的**第一段**与本字段同名即视为密钥（当前只渲染顶层属性）。 */
    private fun secretFor(namespace: SettingsNamespace, key: String): SettingsSecret? =
        namespace.secrets.firstOrNull { it.path.firstOrNull() == key }

    private fun isMasked(value: JsonElement?): Boolean =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content == SETTINGS_MASKED_VALUE
}

/**
 * 只影响**浏览器端 WebUI** 外观与交互的命名空间。
 *
 * 它们的消费者是 `@deepseek-ai/dsh-client-ui-*` 那几个前端包（字号、工作步骤展示方式、
 * 性能与用量明细、链接打开方式……），而本 App 的聊天界面是 Compose 自绘，
 * **一个字都不读这些键**。在 App 里给用户看它们，等于给了一排拨了没反应的开关 ——
 * 比不给更糟（用户会以为 App 坏了）。因此整段隐藏，等哪天真要被 App 消费了再从名单里摘。
 *
 * 新增插件自带 `ui-*` 命名空间时，若它确实是原生 App 不消费的纯前端键，加进这个 Set 即可。
 */
internal val WEBUI_ONLY_NAMESPACES: Set<String> = setOf(
    "ui-theme",
    "ui-chat",
    "ui-conversation",
    "ui-settings",
    "ui-settings-general",
)

/**
 * 这个命名空间有没有**用户能改**的字段。
 *
 * 全是 `ReadOnly`（对象、数组、以及摊平失败退化的行）时不算 —— 那种页面只有一行灰字，
 * 保存键永远点不亮，属于「看得到、改不了、也没必要看」。
 * `Secret` 算可改：密钥位允许重新输入覆盖。
 */
fun SettingsNamespace.hasEditableField(): Boolean =
    SettingsSchema.fields(this).any { it.control != SettingsControl.ReadOnly }

/**
 * 过滤出**在 App 里真起作用**的命名空间。
 *
 * 三条判据（用户原话：「对无法设置的内核没必要显示；不能在 App 上起到作用的都没必要显示」）：
 * 1. 不是 [WEBUI_ONLY_NAMESPACES] 里那批纯 WebUI 键；
 * 2. 至少有一个可编辑字段（见 [hasEditableField]）；
 * 3. **服务端说它可写**（`writable`）—— 桥的写策略默认只放行 `ui-*` 与 `locale` 一类，
 *    剩下 13 个内核命名空间是 `writable: false`：页面能打开、控件全是灰的、保存键永远点不亮。
 *    这种「只读展览」正是用户点名不要的，所以在入口处就筛掉，而不是让用户点进去才知道。
 *
 * 纯函数，JVM 单测可喂样本，不依赖 Android。
 */
fun List<SettingsNamespace>.forNativeApp(): List<SettingsNamespace> =
    filter { it.ns !in WEBUI_ONLY_NAMESPACES && it.writable && it.hasEditableField() }

/**
 * 这个设备令牌能不能**存**设置。
 *
 * 桥对 `PATCH /settings/namespaces/{ns}` 要求 `settings.write`；配对默认那五档
 * （chat.read/chat.write/files.read/files.write/settings.read）里**没有**它，所以
 * 「能看不能存」是配对的默认状态，不是故障 —— 真机实测：点保存后 403 `SCOPE_REQUIRED`，
 * 表单还停在脏状态，用户只会觉得「这个 App 改不动」。
 *
 * 判据只能来自设备自己的 `scopes`（`GET devices/self`），不能按桥版本号猜。
 */
fun List<String>.canWriteSettingsNamespaces(): Boolean = "settings.write" in this

/**
 * `schemastery`（cordis 的设置 schema 格式）→ JSON Schema 子集的摊平器。
 *
 * 形状是一张**引用图**：`{uid: 12427, refs: {"12427": {type:"object", dict:{key: <refId>}}}}`，
 * `union` 节点的 `list` 里放的是 `const` 节点的 id —— 这正是「下拉框的选项」。
 *
 * 成败判据：**摊不出来就返回 null**，让上层走只读兜底；绝不猜类型造出半吊子控件
 * （把密钥猜成输入框、把对象猜成字符串，都是比只读更坏的结果）。
 */
private object Schemastery {

    /** 只展开三层：本项目只渲染顶层字段，嵌套再深也只是给人看个结构。 */
    private const val MAX_DEPTH = 3

    fun expand(schema: JsonObject?): JsonObject? {
        val refs = schema?.get("refs") as? JsonObject ?: return null
        val uid = (schema["uid"] as? JsonPrimitive)?.contentOrNull ?: return null
        val dict = (refs[uid] as? JsonObject)?.get("dict") as? JsonObject ?: return null
        if (dict.isEmpty()) return null
        return buildJsonObject {
            dict.forEach { (key, ref) -> resolve(refs, ref, 0)?.let { put(key, it) } }
        }.takeIf { it.isNotEmpty() }
    }

    private fun resolve(refs: JsonObject, ref: JsonElement?, depth: Int): JsonObject? {
        if (ref == null || depth > MAX_DEPTH) return null
        val id = (ref as? JsonPrimitive)?.contentOrNull ?: return null
        val node = refs[id] as? JsonObject ?: return null
        val meta = node["meta"] as? JsonObject
        val description = meta.string("description")
        return when (node.string("type")) {
            "object", "dict" -> {
                val dict = node["dict"] as? JsonObject ?: return null
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        dict.forEach { (key, child) -> resolve(refs, child, depth + 1)?.let { put(key, it) } }
                    })
                    description?.let { put("description", JsonPrimitive(it)) }
                }
            }
            // 全是 const 的 union = 枚举；混了别的类型就摊不成下拉框，交给上层只读。
            "union" -> {
                val options = (node["list"] as? JsonArray)
                    ?.mapNotNull { constValue(refs, it) }
                    ?: return null
                if (options.isEmpty()) return null
                buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("enum", buildJsonArray { options.forEach { add(JsonPrimitive(it)) } })
                    put("default", JsonPrimitive(meta.string("default") ?: options.first()))
                    description?.let { put("description", JsonPrimitive(it)) }
                }
            }
            "const" -> (node["value"] as? JsonPrimitive)?.contentOrNull?.let { value ->
                buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("enum", buildJsonArray { add(JsonPrimitive(value)) })
                }
            }
            "number", "integer" -> buildJsonObject {
                put("type", JsonPrimitive(if (node.string("type") == "integer") "integer" else "number"))
                meta.number("min")?.let { put("minimum", JsonPrimitive(it)) }
                meta.number("max")?.let { put("maximum", JsonPrimitive(it)) }
                meta.number("default")?.let { put("default", JsonPrimitive(it)) }
                description?.let { put("description", JsonPrimitive(it)) }
            }
            "boolean" -> buildJsonObject {
                put("type", JsonPrimitive("boolean"))
                meta.boolean("default")?.let { put("default", JsonPrimitive(it)) }
                description?.let { put("description", JsonPrimitive(it)) }
            }
            "string" -> buildJsonObject {
                put("type", JsonPrimitive("string"))
                if (meta.string("role") == "secret") put("x-secret", JsonPrimitive(true))
                meta.string("default")?.let { put("default", JsonPrimitive(it)) }
                description?.let { put("description", JsonPrimitive(it)) }
            }
            "array" -> buildJsonObject {
                put("type", JsonPrimitive("array"))
                description?.let { put("description", JsonPrimitive(it)) }
            }
            // any / 未知类型：不给控件，让上层按只读展示原值。
            else -> null
        }
    }

    private fun constValue(refs: JsonObject, ref: JsonElement?): String? {
        val id = (ref as? JsonPrimitive)?.contentOrNull ?: return null
        val node = refs[id] as? JsonObject ?: return null
        if (node.string("type") != "const") return null
        return (node["value"] as? JsonPrimitive)?.contentOrNull
    }
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

private fun JsonObject?.boolean(key: String): Boolean? =
    (this?.get(key) as? JsonPrimitive)?.booleanOrNull
