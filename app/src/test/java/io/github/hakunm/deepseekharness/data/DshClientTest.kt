package io.github.hakunm.deepseekharness.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class DshClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun normalizesServerAddressToVersionedApi() {
        assertEquals("http://example.test:3090/api/v1", DshClient.normalizeEndpoint(" http://example.test:3090/ "))
        assertEquals("https://example.test/api/v1", DshClient.normalizeEndpoint("https://example.test/api/v1/"))
    }

    @Test
    fun healthAndPairingUsePublicUnauthenticatedRoutes() {
        server.enqueue(MockResponse().setBody("""{"ok":true,"version":"v1"}""").setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setResponseCode(201).setBody(PAIRING_RESPONSE).setHeader("Content-Type", "application/json"))
        val client = DshClient(server.url("/").toString())

        assertEquals("v1", client.health().version)
        assertEquals("secret-token", client.pair("ABCDEFGH", "Pixel").token)

        assertEquals("/api/v1/healthz", server.takeRequest().path)
        val pairing = server.takeRequest()
        assertEquals("/api/v1/pairings/exchange", pairing.path)
        assertTrue(pairing.body.readUtf8().contains("\"deviceName\":\"Pixel\""))
    }

    @Test
    fun authenticatedFileCallsEncodePathsAndSendOptimisticLockHeaders() {
        server.enqueue(MockResponse().setBody(ENTRIES_RESPONSE).setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setHeader("ETag", "\"etag-2\""))
        val client = DshClient(server.url("/").toString(), "device-token")

        val page = client.entries("root id", "目录/file name.txt")
        assertEquals("file name.txt", page.entries.single().name)
        assertEquals("\"etag-2\"", client.writeFile("root id", "目录/file name.txt", "hello".toByteArray(), "\"etag-1\""))

        val listing = server.takeRequest()
        assertEquals("Bearer device-token", listing.getHeader("Authorization"))
        assertTrue(listing.path.orEmpty().contains("/api/v1/roots/root%20id/entries"))
        assertTrue(listing.requestUrl?.queryParameter("path").orEmpty().contains("目录/file name.txt"))
        val write = server.takeRequest()
        assertEquals("PUT", write.method)
        assertEquals("\"etag-1\"", write.getHeader("If-Match"))
        assertEquals("hello", write.body.readUtf8())
    }

    @Test
    fun streamsUploadsAndDownloadsWithoutBufferingWholeFiles() {
        server.enqueue(MockResponse().setHeader("ETag", "\"stream-etag\""))
        server.enqueue(MockResponse().setBody("streamed response"))
        val client = DshClient(server.url("/").toString(), "device-token")
        val upload = "streamed request".toByteArray()

        assertEquals(
            "\"stream-etag\"",
            client.writeFile("root-1", "large.bin", upload.size.toLong(), { ByteArrayInputStream(upload) }),
        )
        val output = ByteArrayOutputStream()
        client.downloadFile("root-1", "large.bin", output)

        val write = server.takeRequest()
        assertEquals(upload.size.toLong(), write.getHeader("Content-Length")?.toLong())
        assertEquals("streamed request", write.body.readUtf8())
        assertEquals("streamed response", output.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun requestsEarlierChatHistoryBySequence() {
        server.enqueue(MockResponse().setBody("""{"events":[],"hasMore":false}""").setHeader("Content-Type", "application/json"))
        val client = DshClient(server.url("/").toString(), "device-token")

        client.history("session/1", beforeSeq = 42, maxMessages = 25)

        val request = server.takeRequest()
        assertEquals("/api/v1/chat/sessions/session%2F1/messages?beforeSeq=42&maxMessages=25", request.path)
    }

    @Test
    fun websocketHandshakeKeepsAnOkHttpCompatibleUrlScheme() {
        val httpClient = DshClient("http://example.test:3090")
        val httpsClient = DshClient("https://example.test")

        assertEquals("http://example.test:3090/api/v1/events", httpClient.eventsUrl().toString())
        assertEquals("https://example.test/api/v1/events", httpsClient.eventsUrl().toString())
    }

    @Test
    fun readsDhsWorkspaceAndAgentChoicesAndCreatesByWorkspaceIdentity() {
        server.enqueue(MockResponse().setBody(WORKSPACES_RESPONSE).setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setResponseCode(201).setBody(CREATED_WORKSPACE_RESPONSE).setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody(PRESETS_RESPONSE).setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setResponseCode(201).setBody(CREATED_SESSION_RESPONSE).setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody(SELECTED_PRESET_RESPONSE).setHeader("Content-Type", "application/json"))
        val client = DshClient(server.url("/").toString(), "device-token")

        assertEquals("WebUI Workspace", client.chatWorkspaces().single().title)
        assertEquals("New Workspace", client.createChatWorkspace("root-1", "projects/new").title)
        assertEquals("Coding", client.agentPresets().single().name)
        assertEquals("session-created", client.createSession("workspace-1", "standard").id)
        assertEquals("minimal", client.selectAgentPreset("session-created", "minimal"))

        assertEquals("/api/v1/chat/workspaces", server.takeRequest().path)
        val workspaceCreate = server.takeRequest()
        assertEquals("POST", workspaceCreate.method)
        assertTrue(workspaceCreate.body.readUtf8().contains("\"path\":\"projects/new\""))
        assertEquals("/api/v1/chat/agent-presets", server.takeRequest().path)
        val create = server.takeRequest()
        assertTrue(create.body.readUtf8().let { body ->
            body.contains("\"workspaceId\":\"workspace-1\"") &&
                body.contains("\"agentPreset\":\"standard\"") &&
                !body.contains("rootId") && !body.contains("\"path\"")
        })
        val selection = server.takeRequest()
        assertEquals("PUT", selection.method)
        assertEquals("/api/v1/chat/sessions/session-created/agent-preset", selection.path)
        assertTrue(selection.body.readUtf8().contains("\"agentPreset\":\"minimal\""))
    }

    @Test
    fun managesWorkspacesAndSessionChildrenThroughVersionedRoutes() {
        server.enqueue(MockResponse().setBody(RENAMED_WORKSPACE_RESPONSE).setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody("{\"deleted\":true}").setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody("{\"title\":\"Renamed session\",\"seq\":8}").setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setResponseCode(201).setBody("{\"sessionId\":\"session-forked\"}").setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody("{\"archived\":true}").setHeader("Content-Type", "application/json"))
        val client = DshClient(server.url("/").toString(), "device-token")

        assertEquals("Renamed Workspace", client.renameChatWorkspace("workspace/1", "Renamed Workspace").title)
        client.deleteChatWorkspace("workspace/1")
        assertEquals("Renamed session", client.renameSession("session/1", "Renamed session").title)
        assertEquals("session-forked", client.forkSession("session/1"))
        client.archiveSession("session/1")

        val workspaceRename = server.takeRequest()
        assertEquals("PATCH", workspaceRename.method)
        assertEquals("/api/v1/chat/workspaces/workspace%2F1", workspaceRename.path)
        assertEquals("{\"title\":\"Renamed Workspace\"}", workspaceRename.body.readUtf8())
        assertEquals("DELETE", server.takeRequest().method)
        val sessionRename = server.takeRequest()
        assertEquals("PATCH", sessionRename.method)
        assertEquals("/api/v1/chat/sessions/session%2F1", sessionRename.path)
        assertEquals("/api/v1/chat/sessions/session%2F1/fork", server.takeRequest().path)
        assertEquals("/api/v1/chat/sessions/session%2F1/archive", server.takeRequest().path)
    }

    @Test
    fun readsAndChangesSessionModelWithReasoningEffort() {
        server.enqueue(MockResponse().setBody(SESSION_MODELS_RESPONSE).setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody(SELECTED_MODEL_RESPONSE).setHeader("Content-Type", "application/json"))
        val client = DshClient(server.url("/").toString(), "device-token")

        val models = client.sessionModels("session-1")
        assertEquals("deepseek-v4-pro", models.current.model)
        assertEquals("max", models.current.reasoningEffort)
        assertEquals("High", models.groups.single().models.single().reasoning?.efforts?.get(1)?.name)
        assertEquals(
            "high",
            client.selectModel("session-1", ModelSelection("opencode-go", "deepseek-v4-pro", "high")).reasoningEffort,
        )

        assertEquals("/api/v1/chat/sessions/session-1/models", server.takeRequest().path)
        val selection = server.takeRequest()
        assertEquals("PUT", selection.method)
        assertTrue(selection.body.readUtf8().contains("\"reasoningEffort\":\"high\""))
    }

    @Test
    fun readsAndDecidesPendingApprovalWithoutPrivateRpcFields() {
        server.enqueue(MockResponse().setBody(APPROVALS_RESPONSE).setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setResponseCode(202).setBody(""))
        val client = DshClient(server.url("/").toString(), "device-token")

        val approval = client.pendingApprovals("session/1").single()
        assertEquals("approval-1", approval.id)
        assertEquals("full-access", approval.risk)
        assertEquals("echo [REDACTED]", approval.detail)
        client.decideApproval("session/1", approval.id, allowOnce = true)

        assertEquals("/api/v1/chat/sessions/session%2F1/approvals", server.takeRequest().path)
        val decision = server.takeRequest()
        assertEquals("POST", decision.method)
        assertEquals("/api/v1/chat/sessions/session%2F1/approvals/approval-1/decision", decision.path)
        assertEquals("{\"outcome\":\"allowed-once\"}", decision.body.readUtf8())
    }

    @Test
    fun listsAndExecutesHostSlashCommandsThroughTheVersionedApi() {
        server.enqueue(MockResponse().setBody(COMMANDS_RESPONSE).setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody(COMMAND_EXECUTION_RESPONSE).setHeader("Content-Type", "application/json"))
        val client = DshClient(server.url("/").toString(), "device-token")

        val command = client.sessionCommands("session/1").single()
        assertEquals("permission", command.name)
        assertEquals("preset", command.input?.hint)
        assertEquals("workspace-write", client.executeCommand("session/1", "/permission workspace-write").result.text)

        assertEquals("/api/v1/chat/sessions/session%2F1/commands", server.takeRequest().path)
        val execution = server.takeRequest()
        assertEquals("POST", execution.method)
        assertEquals("/api/v1/chat/sessions/session%2F1/commands", execution.path)
        assertEquals("{\"line\":\"/permission workspace-write\"}", execution.body.readUtf8())
    }

    @Test
    fun readsAndUpdatesProviderSettingsWithoutExpectingSecretValues() {
        server.enqueue(MockResponse().setBody(PROVIDER_SETTINGS_RESPONSE).setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody(PROVIDER_SETTINGS_RESPONSE).setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setResponseCode(201).setBody(PROVIDER_SETTINGS_RESPONSE).setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody(DISCOVERED_MODELS_RESPONSE).setHeader("Content-Type", "application/json"))
        val client = DshClient(server.url("/").toString(), "device-token")

        val settings = client.providerSettings()
        assertTrue(settings.providers.single().credential.configured)
        assertEquals("openai-completions", settings.customProvider.protocols.first())
        client.updateProvider(
            "custom-openai",
            ProviderPatch(baseURL = "https://api.example/v1", apiKey = "write-only", models = listOf(ProviderModel("model-b"))),
        )
        client.createCustomProvider(
            CustomProviderCreate(
                id = "acme-gateway",
                baseURL = "https://acme.example/v1",
                api = "openai-responses",
                models = listOf(ProviderModel("acme-code")),
                expectedRevision = 3,
            ),
        )
        assertEquals("model-b", client.discoverModels("custom-openai", null, null, null).single().id)

        assertEquals("/api/v1/settings/providers", server.takeRequest().path)
        val update = server.takeRequest()
        assertEquals("PATCH", update.method)
        val updateBody = update.body.readUtf8()
        assertTrue(updateBody.contains("\"apiKey\":\"write-only\""))
        assertTrue(updateBody.contains("\"baseURL\":\"https://api.example/v1\""))
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/api/v1/settings/providers", create.path)
        assertTrue(create.body.readUtf8().contains("\"id\":\"acme-gateway\""))
        assertEquals("/api/v1/settings/providers/custom-openai/discover", server.takeRequest().path)
    }

    /**
     * `settings/plugins` 的解码回归。
     *
     * 这条测试是对着**服务端实测 payload** 写的，不是照文档臆造的，因为这里有两个已在真实
     * 环境里踩到过的坑：
     *
     * 1. `state` 有**四种**取值，除 loaded / installed-not-loaded / declared-missing 外还有
     *    `runtime-provided` —— 官方 `@deepseek-ai/` 包随 DSH 运行时安装，不会出现在 profile 的
     *    `node_modules` 下，这是**正常状态**。按三种写不会崩，但会让健康机器冒出一排「状态未知」。
     * 2. `profile` / `profilePath` 在服务端是 `string | null`，判定不出 profile 时会**显式发
     *    `null`**。若声明成非空的 `String = ""`，JSON 里的 null **不会退回默认值，而是让整个
     *    响应解码失败** —— 比 [AgentPreset.trust] 那次的「缺键」更隐蔽。
     *
     * 同时断言「只有 name 的条目」也能解出（字段全带默认值），避免将来某个可选字段再次
     * 拖垮整个列表。
     */
    @Test
    fun pluginInventoryDecodesRuntimeProvidedStateAndNullProfile() {
        server.enqueue(
            MockResponse().setBody(PLUGIN_INVENTORY_RESPONSE).setHeader("Content-Type", "application/json"),
        )
        val client = DshClient(server.url("/").toString(), "device-token")

        val inventory = client.pluginInventory()

        assertEquals("/api/v1/settings/plugins", server.takeRequest().path)

        // 官方包：没有 installed、没有 declared，但 state 是正常的 runtime-provided。
        val official = inventory.items.first { it.name == "@deepseek-ai/dsh-base" }
        assertEquals("runtime-provided", official.state)
        assertTrue(official.official)
        assertEquals(null, official.installed)
        assertEquals(null, official.declared)

        // 普通插件：实际版本与声明都解得出，且两者不同（这正是要显示实际版本的原因）。
        val ffmpeg = inventory.items.first { it.name == "dsh-ffmpeg" }
        assertEquals("loaded", ffmpeg.state)
        assertEquals("^0.4.5", ffmpeg.declared)
        assertEquals("0.4.7", ffmpeg.installed)

        // 只有 name 的条目也必须能解 —— 缺字段走默认值，不能整包失败。
        val bare = inventory.items.first { it.name == "dsh-bare" }
        assertEquals("", bare.state)
        assertEquals(null, bare.installed)

        assertEquals("web", inventory.profile)
        assertEquals(3, inventory.loadedCount)
        assertEquals(1, inventory.problemCount)
    }

    @Test
    fun pluginInventoryAcceptsExplicitNullProfileAndUnavailableReason() {
        // 服务端在 PROFILE_UNKNOWN 时返回的是显式 null，而不是省略键。
        server.enqueue(
            MockResponse().setBody(PLUGIN_INVENTORY_UNAVAILABLE).setHeader("Content-Type", "application/json"),
        )
        val client = DshClient(server.url("/").toString(), "device-token")

        val inventory = client.pluginInventory()

        assertEquals(null, inventory.profile)
        assertEquals(null, inventory.profilePath)
        assertEquals(false, inventory.available)
        assertEquals("PROFILE_UNKNOWN", inventory.reason)
        assertTrue(inventory.items.isEmpty())
    }

    /**
     * `roots/resolve` 的错误分流：**「路径打不开」与「端点不存在」必须分开**。
     *
     * 为什么单独测这个：服务端插件版本过旧时该端点不存在，返回 `404 ROUTE_NOT_FOUND`。
     * 若按状态码笼统地把所有 404 都当成「不在授权根内」，用户在手机上会看到
     * 「这个路径不在授权根内」—— 于是他去反复检查自己的路径，而真正该做的是升级插件。
     * 这个分流是「提示是否可操作」的分界线，值得钉死。
     */
    @Test
    fun resolvePathTreatsOutsideRootsAsNormalButSurfacesAMissingRoute() {
        // 路径确实不在授权根内：这是会话里的常态，返回 null 而不是抛异常。
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"code":"PATH_OUTSIDE_ROOTS","message":"not inside any root"}}""")
                .setHeader("Content-Type", "application/json"),
        )
        val client = DshClient(server.url("/").toString(), "device-token")
        assertEquals(null, client.resolvePath("/etc/hostname"))
        assertEquals("/api/v1/roots/resolve?path=%2Fetc%2Fhostname", server.takeRequest().path)

        // 端点不存在（插件太旧）：必须抛出去，让界面提示可操作的「升级插件」。
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"code":"ROUTE_NOT_FOUND","message":"no such route"}}""")
                .setHeader("Content-Type", "application/json"),
        )
        val failure = runCatching { client.resolvePath("/etc/hostname") }.exceptionOrNull()
        assertTrue(failure is DshApiException)
        assertEquals("ROUTE_NOT_FOUND", (failure as DshApiException).code)
    }

    @Test
    fun resolvePathReturnsTheRelativePathForAnAbsolutePath() {
        server.enqueue(
            MockResponse()
                .setBody("""{"rootId":"root-1","path":"project/README.md","kind":"file","size":12,"modifiedAt":1,"contentType":"text/markdown"}""")
                .setHeader("Content-Type", "application/json"),
        )
        val client = DshClient(server.url("/").toString(), "device-token")

        val resolved = client.resolvePath("/srv/project/README.md")

        assertEquals("root-1", resolved?.rootId)
        assertEquals("project/README.md", resolved?.path)
        assertEquals("file", resolved?.kind)
    }

    private companion object {
        const val PAIRING_RESPONSE = """{"token":"secret-token","device":{"id":"device-1","name":"Pixel","scopes":["files.read"],"rootIds":["root-1"]}}"""
        const val ENTRIES_RESPONSE = """{"path":"目录","entries":[{"name":"file name.txt","path":"目录/file name.txt","kind":"file","size":5,"modifiedAt":1,"writable":true}]}"""
        const val WORKSPACES_RESPONSE = """{"items":[{"id":"workspace-1","title":"WebUI Workspace","rootId":"root-1","path":"project","createdAt":"2026-08-14T00:00:00.000Z","updatedAt":"2026-08-14T00:00:00.000Z"}]}"""
        const val CREATED_WORKSPACE_RESPONSE = """{"workspace":{"id":"workspace-2","title":"New Workspace","rootId":"root-1","path":"projects/new","createdAt":"2026-08-14T00:00:00.000Z","updatedAt":"2026-08-14T00:00:00.000Z"}}"""
        const val RENAMED_WORKSPACE_RESPONSE = """{"workspace":{"id":"workspace-1","title":"Renamed Workspace","rootId":"root-1","path":"project","createdAt":"2026-08-14T00:00:00.000Z","updatedAt":"2026-08-15T00:00:00.000Z"}}"""
        const val PRESETS_RESPONSE = """{"items":[{"id":"standard","name":"Coding","description":"Full coding agent","trust":"system","isDefault":true,"available":true}]}"""
        const val CREATED_SESSION_RESPONSE = """{"session":{"id":"session-created","agentPreset":"standard"}}"""
        const val SELECTED_PRESET_RESPONSE = """{"agentPreset":"minimal"}"""
        const val SESSION_MODELS_RESPONSE = """{"current":{"provider":"opencode-go","model":"deepseek-v4-pro","reasoningEffort":"max"},"routable":true,"groups":[{"id":"opencode-go","name":"OpenCode Go","models":[{"id":"deepseek-v4-pro","name":"DeepSeek V4 Pro","reasoning":{"efforts":[{"id":"off","name":"Off"},{"id":"high","name":"High"},{"id":"max","name":"Max"}],"defaultEffort":"max"}}]}],"failures":[]}"""
        const val SELECTED_MODEL_RESPONSE = """{"selected":{"provider":"opencode-go","model":"deepseek-v4-pro","reasoningEffort":"high"}}"""
        const val APPROVALS_RESPONSE = """{"items":[{"id":"approval-1","sessionId":"session/1","toolName":"bash","reason":"danger-full-access","detail":"echo [REDACTED]","risk":"full-access","requestedAt":1}]}"""
        const val COMMANDS_RESPONSE = """{"items":[{"name":"permission","description":"Change the permission preset","input":{"hint":"preset"}}]}"""
        const val COMMAND_EXECUTION_RESPONSE = """{"execution":{"commandId":"permission","result":{"kind":"text","text":"workspace-write"}}}"""
        const val PROVIDER_SETTINGS_RESPONSE = """{"writable":true,"revisionByNamespace":{"llm-pi-ai":3},"customProvider":{"available":true,"protocols":["openai-completions","openai-responses","anthropic-messages"],"revision":3},"providers":[{"id":"custom-openai","displayName":"Custom OpenAI","active":true,"configurable":true,"configured":true,"removable":true,"credential":{"ref":"CUSTOM_OPENAI_API_KEY","configured":true,"writable":true},"config":{"baseURL":"https://api.example/v1","api":"openai-completions","models":[{"id":"model-a"}]}}]}"""
        const val DISCOVERED_MODELS_RESPONSE = """{"models":[{"id":"model-b","name":"Model B"}]}"""
        const val PLUGIN_INVENTORY_RESPONSE = """{"profile":"web","profilePath":"/home/u/.dsh/profiles/web","available":true,"reason":null,"loadedCount":3,"problemCount":1,"items":[{"name":"@deepseek-ai/dsh-base","declared":null,"installed":null,"loaded":true,"official":true,"state":"runtime-provided"},{"name":"dsh-ffmpeg","declared":"^0.4.5","installed":"0.4.7","loaded":true,"official":false,"state":"loaded"},{"name":"dsh-bare"}]}"""
        const val PLUGIN_INVENTORY_UNAVAILABLE = """{"profile":null,"profilePath":null,"available":false,"reason":"PROFILE_UNKNOWN","loadedCount":0,"problemCount":0,"items":[]}"""
    }
}
