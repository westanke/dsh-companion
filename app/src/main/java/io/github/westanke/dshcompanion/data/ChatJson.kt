package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 会话数据层共用的 **JSON 取值助手**与两个解析实例。
 *
 * 为什么单独一个文件：这些助手原先散在 `ChatPresentation.kt` 末尾，拆分后**四个**文件
 * （历史映射、交接附件、工具行、门面）都要用它们。放在这里，谁都不必 import 别人的私有实现。
 *
 * 命名带 `Chat` 前缀而不是裸 `objectValue`：`data` 包下已有 `GenUiBlocks.kt` 等同名
 * 私有助手，共用裸名会让两边的 `json` 实例互相遮蔽 —— 那是「改一边、另一边悄悄换了
 * 解析配置」的典型入口，宁可名字长一点。
 */

/** 宽松解析：认不出的字段忽略。会话事件的字段随插件版本增减，严格解码已经踩过三次坑。 */
internal val chatJson: Json = Json { ignoreUnknownKeys = true }

/** 只用于把工具 arguments 缩进显示；解析仍走 [chatJson]。 */
internal val chatPrettyJson: Json = Json { prettyPrint = true }

internal fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.intValue(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.booleanValue(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

internal fun JsonObject.arrayLabels(member: String, field: String): String {
    val values = (this[member] as? JsonArray).orEmpty().mapNotNull { entry ->
        (entry as? JsonObject)?.stringValue(field)?.takeIf(String::isNotBlank)
    }.distinct()
    return values.joinToString(", ")
}
