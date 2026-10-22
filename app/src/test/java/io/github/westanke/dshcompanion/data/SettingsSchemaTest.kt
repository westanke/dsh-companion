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
        writable: Boolean = true,
    ) = SettingsNamespace(
        ns = ns,
        revision = revision,
        schema = schema?.let { Json.parseToJsonElement(it).jsonObject },
        value = value?.let { Json.parseToJsonElement(it) },
        writable = writable,
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

    // ———— schemastery（内核实测格式）————
    //
    // 2026-10-22 真机验收发现：桥直接透传内核的 schemastery（`{uid, refs}`），
    // 而这里只认 JSON Schema 的 `properties` —— 于是**每个字段都退化成只读行**：
    // 字号那栏孤零零一个「14」，点不动、保存键永远灰。下面四条钉住摊平结果。

    /** `ui-chat` 的真实形状：enum 是 union → const 引用，不是 JSON Schema 的数组。 */
    private val uiChatSchemastery = """
        {"uid":12427,"refs":{
          "12411":{"type":"const","value":"compact"},
          "12412":{"type":"const","value":"standard"},
          "12417":{"type":"union","meta":{"loose":true},"list":[12411,12412]},
          "12418":{"type":"const","value":"completion"},
          "12420":{"type":"union","meta":{"default":"completion"},"list":[12418]},
          "12427":{"type":"object","dict":{"transcriptView":12417,"collapseTiming":12420}}
        }}
    """.trimIndent()

    @Test
    fun `expands schemastery unions into an enum control`() {
        val fields = fieldsOf(
            schema = uiChatSchemastery,
            value = """{"transcriptView":"standard","collapseTiming":"completion"}""",
        )
        val view = fields.first { it.key == "transcriptView" }
        assertEquals(SettingsControl.Enum, view.control)
        assertEquals(listOf("compact", "standard"), view.enumValues)
        assertEquals(SettingsControl.Enum, fields.first { it.key == "collapseTiming" }.control)
    }

    @Test
    fun `expands schemastery numbers with min max and booleans`() {
        val schema = """
            {"uid":10,"refs":{
              "1":{"type":"number","meta":{"min":10,"max":22,"default":14}},
              "2":{"type":"boolean","meta":{"default":true}},
              "10":{"type":"object","dict":{"fontSize":1,"enabled":2}}
            }}
        """.trimIndent()
        val fields = fieldsOf(schema = schema, value = """{"fontSize":14,"enabled":true}""")
        val size = fields.first { it.key == "fontSize" }
        assertEquals(SettingsControl.Number, size.control)
        assertEquals(10.0, size.minimum!!, 0.0)
        assertEquals(22.0, size.maximum!!, 0.0)
        assertEquals(SettingsControl.Boolean, fields.first { it.key == "enabled" }.control)
    }

    @Test
    fun `treats schemastery role secret as a secret control`() {
        val schema = """
            {"uid":10,"refs":{
              "1":{"type":"string","meta":{"role":"secret"}},
              "10":{"type":"object","dict":{"apiKey":1}}
            }}
        """.trimIndent()
        val field = fieldsOf(schema = schema, value = """{"apiKey":"***"}""").single()
        assertEquals(SettingsControl.Secret, field.control)
        assertTrue(field.declaredSecret)
    }

    @Test
    fun `falls back to read only when the schema is neither format`() {
        val fields = fieldsOf(schema = """{"uid":1,"refs":{}}""", value = """{"whatever":1}""")
        assertEquals(SettingsControl.ReadOnly, fields.single().control)
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

    // ———— 只显示「在 App 里真起作用」的命名空间 ————

    @Test
    fun `drops namespaces whose keys are only consumed by the browser WebUI`() {
        val editable = """{"type":"object","properties":{"fontSize":{"type":"number"}}}"""
        val value = """{"fontSize":14}"""
        val list = listOf("ui-theme", "ui-chat", "ui-conversation", "ui-settings", "ui-settings-general")
            .map { namespace(ns = it, schema = editable, value = value) } +
            namespace(
                ns = "llm-deepseek",
                schema = """{"type":"object","properties":{"apiKey":{"type":"string"}}}""",
                value = """{"apiKey":"***"}""",
            )

        // 五个 ui-* 全被摘掉；llm-* 留着（改了真会影响内核调用）。
        assertEquals(listOf("llm-deepseek"), list.forNativeApp().map { it.ns })
    }

    @Test
    fun `drops namespaces that offer nothing the user can change`() {
        val readOnlyOnly = namespace(
            ns = "agent-preset-registry",
            schema = """{"type":"object","properties":{"presets":{"type":"object"}}}""",
            value = """{"presets":{}}""",
        )
        // 没有 schema：退化成只读行，用户改不了 —— 也就不该占一屏。
        val noSchema = namespace(ns = "web", value = """{"port":3090}""")
        val editable = namespace(
            ns = "permission",
            schema = """{"type":"object","properties":{"mode":{"type":"string","enum":["ask","allow"]}}}""",
            value = """{"mode":"ask"}""",
        )

        assertEquals(
            listOf("permission"),
            listOf(readOnlyOnly, noSchema, editable).forNativeApp().map { it.ns },
        )
        assertFalse(readOnlyOnly.hasEditableField())
        assertTrue(editable.hasEditableField())
    }

    @Test
    fun `drops namespaces the bridge refuses to write`() {
        // 桥的写策略默认只放行 ui-* 与 locale 一类；其余内核命名空间 writable=false。
        // 页面能打开但控件全灰、保存点不亮 —— 用户点名不要这种「只读展览」。
        val kernelReadOnly = namespace(
            ns = "permission",
            schema = """{"type":"object","properties":{"mode":{"type":"string","enum":["ask","allow"]}}}""",
            value = """{"mode":"ask"}""",
            writable = false,
        )
        val writableOne = namespace(
            ns = "locale",
            schema = """{"type":"object","properties":{"language":{"type":"string","enum":["zh-CN","en-US"]}}}""",
            value = """{"language":"zh-CN"}""",
        )

        assertEquals(
            listOf("locale"),
            listOf(kernelReadOnly, writableOne).forNativeApp().map { it.ns },
        )
    }

    @Test
    fun `a secret field counts as editable so the namespace stays visible`() {
        val onlySecret = namespace(
            ns = "llm-pi-ai",
            schema = """{"type":"object","properties":{"apiKey":{"type":"string"}}}""",
            value = """{"apiKey":"***"}""",
        )
        assertTrue(onlySecret.hasEditableField())
        assertEquals(listOf("llm-pi-ai"), listOf(onlySecret).forNativeApp().map { it.ns })
    }

    @Test
    fun `only a token with settings-write may offer the form`() {
        // 配对默认那五档里没有 settings.write —— 真机实测：点保存 403 SCOPE_REQUIRED，
        // 表单停在脏状态，用户只会以为「改不动」。
        val pairedByDefault = listOf("chat.read", "chat.write", "files.read", "files.write", "settings.read")
        assertFalse(pairedByDefault.canWriteSettingsNamespaces())
        assertTrue((pairedByDefault + "settings.write").canWriteSettingsNamespaces())
        assertFalse(emptyList<String>().canWriteSettingsNamespaces())
    }
}
