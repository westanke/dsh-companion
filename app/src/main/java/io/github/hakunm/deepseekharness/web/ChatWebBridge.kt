package io.github.hakunm.deepseekharness.web

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.widget.Toast
import io.github.hakunm.deepseekharness.HarnessViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 注入 assets/chat.html 的 JS 桥。
 *
 * ## 为什么网络请求要回到原生
 *
 * 单页自己会发 `fetch` 直连 REST。但在 `file://` 里发跨源 `http://` 请求会被拦，
 * 更要紧的是**令牌会明文躺进页面**：页面是纯 HTML，任何一次注入或 XSS 都能读走它。
 * 所以这里把请求整个收回原生，页面只管调 [request]。
 *
 * ## 为什么不复用 DshClient
 *
 * `DshClient` 是按端点写死的强类型 API（`sendMessage`、`history`、`decideApproval`…），
 * 它的私有 `get/post/put` 也返回已反序列化的对象。通用转发需要「任意方法 + 任意路径 +
 * 任意 JSON」，两者对不上。所以这里自持一份 [okhttp3.OkHttpClient]，
 * **只从 [io.github.hakunm.deepseekharness.data.ActiveConnection] 取基址与令牌**，
 * 超时沿用同一档（15s 连接 / 45s 读写）。
 *
 * 换来的好处是页面要新增端点时**不必改 Kotlin**，只要 HTML 里加一行 `api(...)`。
 *
 * ## 线程
 *
 * `@JavascriptInterface` 跑在 WebView 的 JS 线程。这里不阻塞它：`.request()` 立刻返回
 * 一个句柄，真正的结果由页面轮询 [poll] 取。宁可多一次往返，也不要在 JS 线程上阻塞——
 * 阻塞会让 WebView 的滚动一起卡住。
 */
class ChatWebBridge(
    context: Context,
    private val viewModel: HarnessViewModel,
    private val onClose: () -> Unit,
) {
    private val appContext = context.applicationContext

    /**
     * 用来发起 Activity 的**原始** context，不是 [appContext]。
     *
     * [appContext] 是 `applicationContext`，它**永远不是 Activity**（Android 保证返回
     * 单例 Application 实例），所以早先写成 `appContext as? Activity` 时那个转型恒为
     * null —— 点「+」选文件会静默 return false，页面上表现为「点了完全没反应」。
     *
     * Composable 传进来的 `LocalContext.current` 就是 Activity；uri 回调由
     * [ActiveChatBridge] 转给当前存活的桥接实例，不依赖这里缓存的引用。
     */
    private val launchContext: Context = context
    private val counter = AtomicLong(0)
    private val done = HashMap<String, String>()
    private val scope = CoroutineScope(Dispatchers.IO)

    /** 用户刚选中的 uri；由 [onPicked] 写入、[takeAttachment] 取走后清空。 */
    @Volatile private var pendingUri: android.net.Uri? = null

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** 发起一次请求，立刻返回句柄。页面随后用 [poll] 取结果。 */
    @JavascriptInterface
    fun request(path: String, method: String, body: String?): String {
        val id = "r" + counter.incrementAndGet()
        val conn = viewModel.activeConnection()

        if (conn == null) {
            finish(id, 0, errorBody("NO_CONNECTION", "尚未连接任何电脑，请先在会话列表选择一台电脑"))
            return id
        }
        val token = conn.host.credential?.secret
        if (token.isNullOrBlank()) {
            finish(id, 0, errorBody("NO_TOKEN", "这台电脑还没有设备令牌，请重新配对"))
            return id
        }

        scope.launch {
            try {
                val verb = method.uppercase()
                val url = conn.client.endpoint.trimEnd('/') + "/" + path.trimStart('/')
                val media = "application/json; charset=utf-8".toMediaType()

                val payload = if (body.isNullOrBlank()) {
                    ByteArray(0)
                } else {
                    body.toByteArray(Charsets.UTF_8)
                }
                /*
                 * 请求体怎么构造，这里有三个坑，每一个都真机炸过：
                 *
                 * 1. **GET/HEAD 绝不能有 body**，哪怕长度是 0。
                 *    早先写成 payload.toRequestBody(null) —— 空 body 但非 null 的
                 *    RequestBody，OkHttp 会照发，Node 端直接回
                 *    "method GET must not have a request body."。
                 *    正确写法是 method(verb, null)，body 参数整个不给。
                 *
                 * 2. 零长度体的 POST/PUT 有些服务端会当作非法请求，补一个 "{}"。
                 *
                 * 3. 有 body 时才设 Content-Type，否则会被当成 multipart 之外的怪东西。
                 */
                val carriesBody = payload.isNotEmpty() ||
                    verb == "POST" || verb == "PUT" || verb == "PATCH"
                val requestBody = if (!carriesBody) {
                    null
                } else if (payload.isEmpty()) {
                    "{}".toByteArray(Charsets.UTF_8).toRequestBody(media)
                } else {
                    payload.toRequestBody(media)
                }

                val builder = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token)
                // body 为 null 时就是裸的 GET/DELETE，服务端不会再抱怨。
                if (requestBody == null) builder.method(verb, null) else builder.method(verb, requestBody)
                val request = builder.build()

                http.newCall(request).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    val out = JSONObject()
                    out.put("status", res.code)
                    out.put("text", if (text.isBlank() && res.code == 204) "" else text)
                    finish(id, res.code, out.toString())
                }
            } catch (t: Throwable) {
                finish(id, 0, errorBody("CLIENT_ERROR", t.message ?: t.javaClass.simpleName))
            }
        }
        return id
    }

    /** 取回结果；取走即删除。返回 null 表示还没就绪。 */
    @JavascriptInterface
    fun poll(id: String): String? = synchronized(done) { done.remove(id) }

    /**
     * 页面回报「init 我收到了」。
     *
     * 为什么要这条回执：原生用 evaluateJavascript 发 init 是单向的 —— 没有返回值、
     * 没有异常、也没有回调。发得太早（WebView 还没执行完页面脚本）就静默落空，
     * 而界面表现与「压根没发」一模一样，只能靠猜。加了回执后，
     * 原生重发直到页面应答为止，两种情况第一次可区分。
     */
    @Volatile
    var initAcked: Boolean = false

    @JavascriptInterface
    fun pageReady() {
        initAcked = true
    }

    /** 复制到剪贴板（代码块的「复制」按钮与长按复制都走这里）。 */
    @JavascriptInterface
    fun copy(text: String) {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("dsh-chat", text))
    }

    /** 外链交给系统浏览器，避免在 WebView 里导航走。 */
    @JavascriptInterface
    fun openLink(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https" && scheme != "mailto") return
        runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 页面左上角返回键：交回会话列表。 */
    @JavascriptInterface
    fun close() = onClose()

    @JavascriptInterface
    fun toast(message: String) {
        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    }

    /** 发送成功/失败的震动反馈。 */
    @JavascriptInterface
    fun haptic() {
        runCatching {
            val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as? android.os.VibratorManager
            vm?.defaultVibrator?.vibrate(
                android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK)
            )
        }
    }

    /**
     * 让原生弹系统文件选择器。
     *
     * WebView 里没有可信的方式拿到用户选的文件：`input[type=file]` 在
     * `file://` 页面下拿到的 File 只能读到路径，读不到字节。所以走原生 picker，
     * 由 [ChatWebBridge] 记住 uri，页面再用 [takeAttachment] 取。
     */
    @JavascriptInterface
    fun pickFile(accept: String): Boolean {
        val ctx = launchContext as? Activity ?: run {
            // 不是 Activity 就没地方发 Intent。与其静默失败让用户以为点了没反应，
            // 不如立刻把原因说清楚。
            Toast.makeText(appContext, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
            return false
        }
        ctx.runOnUiThread {
            // 图片走相册（ACTION_PICK），其余走文件浏览器（ACTION_OPEN_DOCUMENT）。
            //
            // 为什么分开：ACTION_OPEN_DOCUMENT 是**文档**选择器，在华为等 ROM 上
            // 即便 type=image/* 也常常落到「文件/下载」界面，与按钮上写的「从相册选图片」
            // 对不上 —— 用户点的是相册，看到的却是下载目录。
            val wantsImage = accept.startsWith("image/")
            val intent = if (wantsImage) {
                Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (accept.isBlank() || accept == "*/*") "*/*" else accept
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            ctx.startActivityForResult(intent, REQUEST_PICK)
        }
        return true
    }

    /** 拍照。同样要把 uri 交给 [takeAttachment]。 */
    @JavascriptInterface
    fun captureImage() {
        val ctx = launchContext as? Activity ?: return
        ctx.runOnUiThread {
            ctx.startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE), REQUEST_CAPTURE)
        }
    }

    /**
     * 取回上一次选中的文件，读成 base64 交给页面。
     *
     * 刻意**不**在这里上传：图片要内联 base64、文件要先换 receiptId，
     * 而消息怎么组装属于页面自己的事。原生只负责取字节。
     *
     * @return JSON，含 `ok` / `name` / `size` / `type` / `data`；没有待取文件时 ok 为 false。
     */
    @JavascriptInterface
    fun takeAttachment(): String {
        val uri = pendingUri
        if (uri == null) return "{\"ok\":false}"
        pendingUri = null
        return runCatching {
            val ctx = appContext
            val name = queryName(ctx, uri) ?: "file"
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) return "{\"ok\":false,\"error\":\"读不到文件\"}"
            JSONObject()
                .put("ok", true)
                .put("name", name)
                .put("size", bytes.size)
                .put("type", ctx.contentResolver.getType(uri) ?: guessType(name))
                .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                .toString()
        }.getOrElse { e ->
            JSONObject().put("ok", false).put("error", e.message ?: "读取失败").toString()
        }
    }

    /** 供 Activity 的 onActivityResult 调用：记下用户选中的 uri。 */
    fun onPicked(uri: android.net.Uri?) {
        pendingUri = uri
    }

    private fun queryName(ctx: Context, uri: android.net.Uri): String? =
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()

    private fun guessType(name: String): String = when (name.substringAfterLast(".", "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }
    // ───────────────────────── 内部 ─────────────────────────

    private fun finish(id: String, status: Int, payload: String) {
        synchronized(done) { done[id] = payload }
    }

    private fun errorBody(code: String, message: String): String =
        JSONObject()
            .put("status", 0)
            .put("text", "")
            .put("body", JSONObject().put("error", JSONObject().put("code", code).put("message", message)))
            .toString()

    companion object {
        const val REQUEST_PICK = 4101
        const val REQUEST_CAPTURE = 4102
    }
}
