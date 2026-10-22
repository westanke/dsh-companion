package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * schema → 控件的映射。
 *
 * 这层唯一的产出是「该画什么控件」，错了不会崩，只会把设置渲染成一堆不可编辑的
 * 行（或者更糟：把密钥位渲染成输入框）。所以每条降级路径都钉一条断言：
 * 缺 schema、缺 type、object/array、`secrets` 命中、值被脱敏。
 */
class SettingsSchemaTest {

    private fun namespace(
        schema: String? = null,
        value: String? = null,
        secrets: List<SettingsSecret> = emptyList(),
        ns: String = "ui-chat",
        revision: Int = 3,
    ) = SettingsNamespace(
        ns = ns,
        revision = revision,
        schema = schema?.let { Json.parseToJsonElement(it).jsonObject },
        value = value?.let { Json.parseToJsonElement(it) },
        writable = true,
        secrets = secrets,
    )

    private fun fieldsOf(schema: String?, value: String? = null, secrets: List<SettingsSecret> = emptyList()) =
        SettingsSchema.fields(namespace(schema = schema, value = value, secrets = secrets))

    // ———— 四类控件 ————

    @Test
    fun `enum renders a picker and keeps the declared option order`() {
        val fields = fieldsOf(
            """{"type":"object","properties":{"transcriptView":{
                 "type":"string","enum":["compact","standard","detailed","verbose"],"default":"detailed"}}}""",
            """{"transcriptView":"standard"}""",
        )
        assertEquals(1, fields.size)
        val field = fields.single()
        assertEquals(SettingsControl.Enum, field.control)
        assertEquals(listOf("compact", "standard", "detailed", "verbose"), field.enumValues)
        assertEquals("standard", (field.currentValue as JsonPrimitive).content)
    }

    @Test
    fun `boolean renders a switch`() {
        val field = fieldsOf(
            """{"type":"object","properties":{"enabled":{"type":"boolean","default":true}}}""",
            """{"enabled":false}""",
        ).single()
        assertEquals(SettingsControl.Boolean, field.control)
        assertEquals(false, (field.currentValue as JsonPrimitive).content.toBoolean())
    }

    @Test
    fun `integer carries bounds and the integer flag`() {
        val field = fieldsOf(
            """{"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":50}}}""",
        ).single()
        assertEquals(SettingsControl.Number, field.control)
        assertTrue(field.integer)
        assertEquals(1.0, field.minimum)
        assertEquals(50.0, field.maximum)
    }

    @Test
    fun `number accepts the min max shorthand`() {
        val field = fieldsOf(
            """{"type":"object","properties":{"ratio":{"type":"number","min":0.5,"max":2}}}""",
        ).single()
        assertEquals(SettingsControl.Number, field.control)
        assertFalse(field.integer)
        assertEquals(0.5, field.minimum)
        assertEquals(2.0, field.maximum)
    }

    @Test
    fun `plain string renders a text field`() {
        val field = fieldsOf(
            """{"type":"object","properties":{"label":{"type":"string","title":"Label"}}}""",
        ).single()
        assertEquals(SettingsControl.Text, field.control)
        assertEquals("Label", field.title)
    }

    // ———— 降级路径 ————

    @Test
    fun `missing schema falls back to read-only rows driven by value`() {
        val fields = fieldsOf(
            null,
            """{"transcriptView":"standard","collapseTiming":"completion"}""",
        )
        assertEquals(
            listOf("transcriptView", "collapseTiming"),
            fields.map { it.key },
        )
        assertTrue(fields.all { it.control == SettingsControl.ReadOnly })
    }

    @Test
    fun `empty properties object also falls back to values`() {
        val fields = fieldsOf("""{"type":"object","properties":{}}""", """{"a":1}""")
        assertEquals(SettingsControl.ReadOnly, fields.single().control)
    }

    @Test
    fun `object and array fields stay read-only with their json preserved`() {
        val fields = fieldsOf(
            """{"type":"object","properties":{
                 "extra":{"type":"object"},"list":{"type":"array","items":{"type":"string"}}}}""",
            """{"extra":{"k":1},"list":[1,2]}""",
        )
        assertEquals(listOf(SettingsControl.ReadOnly, SettingsControl.ReadOnly), fields.map { it.control })
        assertTrue(fields[0].currentValue.toString().contains("\"k\""))
    }

    @Test
    fun `field without type stays read-only`() {
        val field = fieldsOf("""{"type":"object","properties":{"mystery":{}}}""").single()
        assertEquals(SettingsControl.ReadOnly, field.control)
    }

    // ———— 密钥位 ————

    @Test
    fun `declared secret is read-only even when the value is not masked`() {
        val field = fieldsOf(
            """{"type":"object","properties":{"apiKey":{"type":"string"}}}""",
            """{"apiKey":"sk-live-123"}""",
            secrets = listOf(SettingsSecret(path = listOf("apiKey"), set = true)),
        ).single()
        assertEquals(SettingsControl.Secret, field.control)
        assertTrue(field.declaredSecret)
        assertTrue(field.secretSet)
    }

    @Test
    fun `masked value alone is enough to treat a field as a secret`() {
        val field = fieldsOf(
            """{"type":"object","properties":{"apiKey":{"type":"string"}}}""",
            """{"apiKey":"***"}""",
        ).single()
        assertEquals(SettingsControl.Secret, field.control)
        assertFalse(field.declaredSecret)
        assertTrue(field.secretSet)
    }

    @Test
    fun `unset secret reports itself as unset`() {
        val field = fieldsOf(
            """{"type":"object","properties":{"apiKey":{"type":"string"}}}""",
            """{}""",
            secrets = listOf(SettingsSecret(path = listOf("apiKey"), set = false)),
        ).single()
        assertEquals(SettingsControl.Secret, field.control)
        assertFalse(field.secretSet)
    }

    // ———— 顺序与标题 ————

    @Test
    fun `properties keep the schema declaration order`() {
        val fields = fieldsOf(
            """{"type":"object","properties":{
                 "zeta":{"type":"boolean"},"alpha":{"type":"boolean"},"mid":{"type":"boolean"}}}""",
        )
        assertEquals(listOf("zeta", "alpha", "mid"), fields.map { it.key })
    }

    @Test
    fun `title beats the humanized fallback and description survives`() {
        val field = fieldsOf(
            """{"type":"object","properties":{"fontSize":{
                 "type":"string","title":"字号","description":"界面文字大小"}}}""",
        ).single()
        assertEquals("字号", field.title)
        assertEquals("界面文字大小", field.description)
    }

    @Test
    fun `humanizes camel case and snake case names`() {
        assertEquals("Transcript view", humanizeFieldName("transcriptView"))
        assertEquals("Busy enter", humanizeFieldName("busy_enter"))
        assertEquals("New tab", humanizeFieldName("new-tab"))
        assertEquals("X", humanizeFieldName("x"))
        assertNull(fieldsOf("""{"properties":{"unknownField":{"type":"string"}}}""").single().title)
        assertEquals("Unknown field", humanizeFieldName("unknownField"))
    }

    // ———— 小工具 ————

    @Test
    fun `clamps numbers into the declared range and tolerates broken ranges`() {
        assertEquals(1.0, clampSettingsNumber(0.2, 1.0, 50.0), 0.0)
        assertEquals(50.0, clampSettingsNumber(99.0, 1.0, 50.0), 0.0)
        assertEquals(7.0, clampSettingsNumber(7.0, null, null), 0.0)
        // min > max 是脏 schema：原样返回比抛异常好（抛了就整页设置打不开）。
        assertEquals(7.0, clampSettingsNumber(7.0, 10.0, 1.0), 0.0)
    }

    @Test
    fun `pretty json is multi-line and stable`() {
        val element = Json.parseToJsonElement("""{"a":1,"b":[2]}""")
        val pretty = prettySettingsJson(element)
        assertTrue(pretty.contains("\n"))
        assertTrue(pretty.contains("\"a\""))
    }
}
