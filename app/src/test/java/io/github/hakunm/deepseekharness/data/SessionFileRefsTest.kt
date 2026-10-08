package io.github.hakunm.deepseekharness.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFileRefsTest {

    @Test
    fun extractsAbsolutePathsFromStructuredDiffs() {
        val events = listOf(
            entry(
                "tool/call",
                1,
                """{"turn":1,"step":1,"meta":{"diffs":[{"path":"/media/work/app/src/Main.kt","additions":3}]}}""",
            ),
        )

        assertEquals(listOf("/media/work/app/src/Main.kt"), SessionFileRefs.refs(events).map(SessionFileRef::path))
    }

    @Test
    fun extractsFilePathsFromReadWriteAndEditArguments() {
        val events = listOf(
            toolCall(1, "read", """{"file_path":"/media/work/a.txt"}"""),
            toolCall(2, "write", """{"file_path":"/media/work/b.txt","content":"x"}"""),
            toolCall(3, "edit", """{"file_path":"/media/work/b.txt","old_string":"a","new_string":"b"}"""),
        )

        val refs = SessionFileRefs.refs(events)

        assertEquals(listOf("/media/work/a.txt", "/media/work/b.txt"), refs.map(SessionFileRef::path))
        assertEquals(listOf(1, 2), refs.map(SessionFileRef::count))
        assertEquals(listOf(1, 3), refs.map(SessionFileRef::lastSeq))
    }

    @Test
    fun neverParsesShellCommands() {
        val events = listOf(
            // command 里的路径多半是 grep 模式、变量或文本内容，故意一律不解析。
            toolCall(1, "bash", """{"command":"grep -rn foo /media/work/app","description":"search"}"""),
            toolCall(2, "bash", """{"command":"cat /etc/passwd"}"""),
            toolCall(3, "pwsh", """{"command":"Get-Content C:\\logs\\a.txt"}"""),
            toolCall(4, "run_code", """{"code":"open('/media/work/c.txt').read()"}"""),
            // 即便 shell 工具的参数里出现了 file_path 这个键，也照样不看。
            toolCall(5, "bash", """{"file_path":"/media/work/sneaky.txt"}"""),
        )

        assertTrue(SessionFileRefs.refs(events).isEmpty())
    }

    @Test
    fun ignoresRelativePaths() {
        val events = listOf(
            toolCall(1, "read", """{"file_path":"src/main.kt"}"""),
            toolCall(2, "read", """{"file_path":"./README.md"}"""),
            toolCall(3, "read", """{"file_path":"../sibling/x.txt"}"""),
            entry("tool/call", 4, """{"meta":{"diffs":[{"path":"docs/readme.md"}]}}"""),
        )

        assertTrue(SessionFileRefs.refs(events).isEmpty())
    }

    @Test
    fun acceptsWindowsDrivePathsButNotDriveRelativeOnes() {
        assertTrue(SessionFileRefs.isAbsolutePath("/media/work/a.txt"))
        assertTrue(SessionFileRefs.isAbsolutePath("""C:\Users\me\a.txt"""))
        assertTrue(SessionFileRefs.isAbsolutePath("D:/work/a.txt"))
        assertFalse(SessionFileRefs.isAbsolutePath("C:relative.txt"))
        assertFalse(SessionFileRefs.isAbsolutePath("src/a.txt"))
        assertFalse(SessionFileRefs.isAbsolutePath(""))
        assertTrue(SessionFileRefs.isAbsolutePath("/"))
    }

    @Test
    fun extractsWindowsPathsFromDiffs() {
        val events = listOf(
            entry("tool/call", 1, """{"meta":{"diffs":[{"path":"C:\\Users\\me\\a.txt"}]}}"""),
        )

        assertEquals(listOf("""C:\Users\me\a.txt"""), SessionFileRefs.refs(events).map(SessionFileRef::path))
    }

    @Test
    fun deduplicatesAndKeepsFirstAppearanceOrder() {
        val events = listOf(
            toolCall(1, "write", """{"file_path":"/media/work/b.txt"}"""),
            toolCall(2, "read", """{"file_path":"/media/work/a.txt"}"""),
            toolCall(3, "read", """{"file_path":"/media/work/b.txt"}"""),
            entry(
                "tool/call",
                4,
                """{"meta":{"diffs":[{"path":"/media/work/a.txt"},{"path":"/media/work/b.txt"}]}}""",
            ),
        )

        val refs = SessionFileRefs.refs(events)

        assertEquals(listOf("/media/work/b.txt", "/media/work/a.txt"), refs.map(SessionFileRef::path))
        assertEquals(listOf(3, 2), refs.map(SessionFileRef::count))
        assertEquals(listOf(4, 4), refs.map(SessionFileRef::lastSeq))
    }

    @Test
    fun emptyHistoryYieldsNoRefs() {
        assertTrue(SessionFileRefs.refs(emptyList()).isEmpty())
    }

    @Test
    fun unrelatedEventsAndMissingArgumentsYieldNoRefs() {
        val events = listOf(
            entry("user/message", 1, """{"source":{"kind":"user"},"content":[{"type":"text","text":"hi"}]}"""),
            entry("turn/start", 2, """{"turn":1}"""),
            toolCall(3, "read", """{"path":"/media/work/ok.txt"}"""),
            // 工具名已知但没有路径参数
            toolCall(4, "write", """{"content":"no path here"}"""),
        )

        // read 的兜底 path 键是合法的，其余事件什么也提不出来。
        assertEquals(listOf("/media/work/ok.txt"), SessionFileRefs.refs(events).map(SessionFileRef::path))
    }

    @Test
    fun malformedArgumentsNeverCrash() {
        val events = listOf(
            toolCall(1, "read", "not json at all"),
            toolCall(2, "read", "{"),
            toolCall(3, "read", """["/media/work/a.txt"]"""),
            toolCall(4, "read", """{"file_path":123}"""),
            toolCall(5, "read", """{"file_path":null}"""),
            toolCall(6, "read", ""),
            toolCall(7, "read", """{"file_path":{"nested":"/media/work/a.txt"}}"""),
            entry("tool/call", 8, """{"name":"read","arguments":[]}"""),
            HistoryEntry(event = SessionEvent("tool/call", 9, 9L, JsonPrimitive("nonsense"))),
            HistoryEntry(event = SessionEvent("tool/call", 10, 10L, JsonNull)),
        )

        assertTrue(SessionFileRefs.refs(events).isEmpty())
    }

    @Test
    fun extractsArgumentsNestedInAssistantMessageContent() {
        val data = buildJsonObject {
            put("turn", 1)
            put("step", 1)
            putJsonObject("message") {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "tool-call")
                        put("id", "call-1")
                        put("name", "edit")
                        put("arguments", """{"file_path":"/media/work/nested.txt","old_string":"a"}""")
                    }
                }
            }
        }
        val events = listOf(HistoryEntry(SessionEvent("assistant/message", 1, 1L, data)))

        assertEquals(listOf("/media/work/nested.txt"), SessionFileRefs.refs(events).map(SessionFileRef::path))
    }

    @Test
    fun extractsArgumentsNestedInStreamChunks() {
        val data = buildJsonObject {
            put("turn", 1)
            putJsonArray("stream") {
                addJsonObject {
                    putJsonObject("chunk") {
                        putJsonObject("block") {
                            put("name", "read")
                            put("arguments", """{"file_path":"/media/work/streamed.txt"}""")
                        }
                    }
                }
            }
        }
        val events = listOf(HistoryEntry(SessionEvent("assistant/message", 1, 1L, data)))

        assertEquals(listOf("/media/work/streamed.txt"), SessionFileRefs.refs(events).map(SessionFileRef::path))
    }

    @Test
    fun acceptsAlreadyDecodedArgumentObjects() {
        val data = buildJsonObject {
            put("name", "write")
            putJsonObject("arguments") { put("file_path", "/media/work/object.txt") }
        }
        val events = listOf(HistoryEntry(SessionEvent("tool/call", 1, 1L, data)))

        assertEquals(listOf("/media/work/object.txt"), SessionFileRefs.refs(events).map(SessionFileRef::path))
    }

    @Test
    fun acceptsUnknownToolsWithFilePathButNotBarePath() {
        val events = listOf(
            // file_path 这个键名本身足够明确，未知工具也采信。
            toolCall(1, "str_replace", """{"file_path":"/media/work/unknown.txt"}"""),
            // 只有已知文件工具才采信兜底的 path 键：grep/glob 的 path 是搜索目录。
            toolCall(2, "grep", """{"pattern":"foo","path":"/media/work/app"}"""),
            toolCall(3, "glob", """{"pattern":"*.kt","path":"/media/work/app"}"""),
        )

        assertEquals(listOf("/media/work/unknown.txt"), SessionFileRefs.refs(events).map(SessionFileRef::path))
    }

    private fun entry(type: String, seq: Int, data: String) =
        HistoryEntry(event = SessionEvent(type, seq, seq.toLong(), json.parseToJsonElement(data)))

    private fun toolCall(seq: Int, name: String, arguments: String) = HistoryEntry(
        event = SessionEvent(
            "tool/call",
            seq,
            seq.toLong(),
            buildJsonObject {
                put("turn", 1)
                put("callId", "call-$seq")
                put("name", name)
                put("arguments", arguments)
            },
        ),
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
