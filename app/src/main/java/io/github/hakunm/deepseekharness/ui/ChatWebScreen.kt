package io.github.hakunm.deepseekharness.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import io.github.hakunm.deepseekharness.R
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.github.hakunm.deepseekharness.ApprovalUiState
import io.github.hakunm.deepseekharness.HarnessState
import io.github.hakunm.deepseekharness.HarnessViewModel
import io.github.hakunm.deepseekharness.data.ChatHistory
import io.github.hakunm.deepseekharness.data.ChatItemKind
import io.github.hakunm.deepseekharness.data.MessageSegment
import io.github.hakunm.deepseekharness.data.PendingQuestionSet
import io.github.hakunm.deepseekharness.data.displayItems
import io.github.hakunm.deepseekharness.data.parseGenUi
import io.github.hakunm.deepseekharness.data.splitMessageBody
import io.github.hakunm.deepseekharness.data.todoItems
import io.github.hakunm.deepseekharness.web.ActiveChatBridge
import io.github.hakunm.deepseekharness.web.ChatWebBridge
import io.github.hakunm.deepseekharness.selectSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.json.JSONObject

/**
 * 用 WebView 承载 assets/chat.html 的会话详情页。
 *
 * ## 为什么留一个开关
 *
 * 原生的 `ChatDetail` 是走过真机验证的资产；这个 WebView 版是**并存的第二条路**，
 * 由 `AppPreferences.useWebChat` 控制。好处是随时能对比、随时能退回——
 * 前端那一大坨 Markdown、代码块与卡片渲染正是原生版最费劲的地方。
 *
 * ## 数据从哪来
 *
 * 页面**不自己连**：REST 由 [ChatWebBridge] 转给 `DshClient`，流式事件由原生
 * `DshClient.events()` 转发。页面里的 `API` 恒为空，所以它那条 `fetch` +
 * `WebSocket` 的退路在 App 里永远不会触发（那条路是给浏览器预览用的）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChatWebScreen(
    state: HarnessState,
    viewModel: HarnessViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val session = state.sessions.firstOrNull { it.id == state.selectedSessionId }

    val bridge = remember {
        ChatWebBridge(context, viewModel) {
            viewModel.selectSession(null)
        }
    }
    var web by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }

    // ── 原生侧自证面板 ──
    //
    // 为什么不用页面自证：页面里那套诊断连着四版都没能报出有效信息，
    // 而这条路径**完全不经过 JavaScript**。哪怕页面一行脚本都没跑、
    // 甚至连 assets 都没加载成，这块原生 Compose 文本照样会显示真实状态。
    // 排查 WebView 的问题时，这是唯一不会跟着页面一起失明的眼睛。
    val diag = remember { mutableStateListOf<String>() }
    // 页面探测回来的完整报告。点面板一次性复制走，不用截图。
    var report by remember { mutableStateOf("") }
    val lastSnap = remember { mutableStateOf("") }
    fun mark(line: String) {
        if (diag.size > 10) diag.removeAt(0)
        diag.add(line)
    }
    // 状态行要**只在变化时**记一行。早先放在 SideEffect 里每帧都追加，
    // 变成 mark -> 重组 -> mark 的死循环，把 onPageStarted 这类真正的事件行
    // 几秒内就顶出窗口，反而看不见要查的东西。
    val snap = listOf(
        "web=" + (web != null) + " pageReady=" + pageReady +
            " initAck=" + bridge.initAcked +
            " conn=" + (viewModel.activeConnection() != null) +
            " sid=" + (state.selectedSessionId?.take(6) ?: "无") +
            " 开关=" + state.useWebChat,
    ).joinToString("  ")
    if (snap != lastSnap.value) {
        lastSnap.value = snap
        mark(snap)
    }

    // 登记给 MainActivity：图片/文件选择器是 Activity 发起的，回调得有人接。
    DisposableEffect(bridge) {
        ActiveChatBridge.attach(bridge)
        onDispose { ActiveChatBridge.detach(bridge) }
    }

    // `.imePadding()` 是这个键盘弹起问题的关键。
    //
    // MainActivity 调了 enableEdgeToEdge()，而 targetSdk 是 36 —— 从 Android 15 起，
    // 边到边模式下清单里的 `windowSoftInputMode="adjustResize"` 会被系统**忽略**，
    // 窗口不再随键盘缩小，WebView 就整个铺到键盘底下：输入框被挡住，
    // 用户打字时看不见自己写了什么。
    //
    // 现在由 Compose 自己把 IME 高度从容器里减掉，WebView 跟着变小，
    // 页面那边是 flex 列布局（#log 自适应、#comp 固定高），输入区自然就留在可见范围内。
    Box(modifier = modifier.fillMaxSize().imePadding()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(if (dark) Color.parseColor("#151517") else Color.WHITE)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // 页面走原生桥接发请求，这里并不需要它自己跨源取数；
                        // 但保持打开可以让浏览器预览与真机行为一致，避免两套 bug。
                        allowFileAccess = true
                        allowContentAccess = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        mediaPlaybackRequiresUserGesture = false
                    }
                    // 探测用的第二个桥，名字刻意与业务桥不同：
                    // 这样「Android.__probe 能不能被调到」本身就成了
                    // addJavascriptInterface 是否成功注入的证据。
                    addJavascriptInterface(
                        object {
                            @android.webkit.JavascriptInterface
                            fun __probe(msg: String) {
                                // 整份存进 report，不再逐行刷屏：
                                // 刷屏会把关键行挤出窗口，而且截图一定裁掉大半。
                                report = msg
                                mark("收到完整探测报告（" + msg.lines().size + " 行，点面板复制）")
                            }
                        },
                        "Android",
                    )
                    // 关掉横向滚动条与边缘辉光：内容横向溢出时，用户继续拖动会被
                    // 系统当成侧滑返回手势，左边滑出侧边栏。真正的修复在页面 CSS
                    // （overflow-x:hidden + 容器 overscroll-behavior），这里是第二道保险。
                    isVerticalScrollBarEnabled = true
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    addJavascriptInterface(bridge, "DshBridge")
                    // 没有它的话，页面里的 JS 报错与 console.log 全都静默 ——
                    // 页面白屏时既看不到原因，也没法从 logcat 里捞线索。
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                            val text = "[${m.sourceId()}:${m.lineNumber()}] ${m.message()}"
                            when (m.messageLevel()) {
                                android.webkit.ConsoleMessage.MessageLevel.ERROR ->
                                    android.util.Log.e("DshChatWebView", text)
                                android.webkit.ConsoleMessage.MessageLevel.WARNING ->
                                    android.util.Log.w("DshChatWebView", text)
                                else -> android.util.Log.d("DshChatWebView", text)
                            }
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            mark("onPageStarted: " + url)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            mark("onPageFinished: 加载结束")
                            // 只翻标记：真正的 init 由 LaunchedEffect 在 key 变化后发出，
                            // 在这里直接发会和那一条撞车（页面会收到两次 init）。
                            pageReady = true
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: android.webkit.WebResourceError,
                        ) {
                            super.onReceivedError(view, request, error)
                            // 只报主文档的失败；子资源（favicon 之类）失败与用户看到的东西无关。
                            if (!request.isForMainFrame) return
                            // 只写 logcat 等于没写：真机上没人读得到，
                            // 而这一条恰恰是「assets 到底加载没加载」的直接答案。
                            mark("❌ 加载失败 ${error.errorCode}: ${error.description}")
                            android.util.Log.e(
                                "DshChatWebView",
                                "main frame failed: ${error.errorCode} ${error.description}",
                            )
                        }

                        // 外链一律交给系统，别在 WebView 里导航走（否则页面没了）。
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val url = request.url?.toString().orEmpty()
                            return if (url.startsWith("http://") || url.startsWith("https://")) {
                                bridge.openLink(url)
                                true
                            } else {
                                false
                            }
                        }
                    }
                    mark("factory：开始 loadUrl")
                    loadUrl("file:///android_asset/chat.html")
                    mark("factory：loadUrl 已调用")
                    web = this
                }
            },
            onRelease = { it ->
                it.removeJavascriptInterface("DshBridge")
                it.removeJavascriptInterface("Android")
                it.stopLoading()
                it.destroy()
                web = null
                pageReady = false
                // **必须清 initAcked**。bridge 是 remember 出来的，整个 Composable 生命周期内
                // 都是同一个实例；而 AndroidView 每次重建都换一个新的 WebView 上下文。
                // 不清的话，新页面的 initAck=false 会被上一个实例留下的 true 盖住，
                // 重发逻辑在第一个 tick 就认为「已确认」直接返回 —— 新页面永远收不到 init，
                // 症状是页面停在「正在读取会话…」而诊断里 initAck 却显示 true。
                bridge.initAcked = false
                mark("onRelease：已销毁")
            },
        )

    }

    // 系统返回手势：页面没有原生导航栈，退回即退出会话。
    BackHandler(enabled = true, onBack = onBack)

    // 会话或连接变化时把配置推进页面。onLive 保持 true：
    // 页面要自己按顺序拉命令 → 历史 → 审批，每步都过原生桥接；
    // App 若抢在前面推，会与页面的拉取撞出重复请求。
    //  key 里**必须**含 web 与 pageReady。
    //
    //  首帧重组时 AndroidView 的 factory 还没跑，web 是 null、pageReady 是 false，
    //  于是这里直接 return；等 factory 跑完、页面 onPageFinished，这两项变成真值，
    //  但 key 没变 → effect 不再重跑 → init 永远发不出去，页面卡在「正在读取会话…」。
    //  这就是当初那个 pendingConfig 兜底想救的场景，但它救不了：
    //  onPageFinished 回调里改的是 Compose state，只有 key 变了 effect 才会重跑。
    //  key 里**不能**有 session?.running。
    //
    //  它曾经在这里，结果是：发一条消息 → 会话进入运行态 → running 由 false 变 true
    //  → key 变化 → 整个 effect 重跑 → 再发一次 init → 页面把命令、历史、审批
    //  重新拉一遍。症状就是「发完消息，诊断栏里 init 已收到/开始拉取/历史已载入
    //  连着出现三次」。running 只是要交给页面的一个初值，用另一条 effect 推即可。
    LaunchedEffect(
        state.selectedSessionId,
        state.activeEndpointId,
        state.activeHostId,
        web,
        pageReady,
    ) {
        val id = state.selectedSessionId ?: return@LaunchedEffect
        // 没连上时**照样发 init**，只是 tell 页 why。
        //
        // 原来这里直接 return@LaunchedEffect —— 静默早退，页面什么都不显示，
        // 用户永远停在「正在读取会话…」。而且连接状态既不在 key 里也不产生重跑，
        // 后续连上电脑也不会补发 init。和当初 web/pageReady 那次是同一个坑：
        // 「早退」不等于「无事发生」，它等于「用户永远看不到原因」。
        // 桥接自己会在 request() 里回 NO_CONNECTION，页面能显示成一句人话。
        val conn = viewModel.activeConnection()
        val scopes = state.device?.scopes ?: emptyList()

        // 用局部变量而不是把 when 写进 .put(...) 的实参里：
        // when 作为函数实参时解析器会把紧随其后的 "}," 吞进 when 体，直接编译不过。
        //
        // 取值走 `context.getString` 而不是 `stringResource`：这一段在 LaunchedEffect 里，
        // **不是 Composable 上下文**，用后者会直接编译失败（已经踩过一次）。
        val warn = when {
            conn == null -> context.getString(R.string.web_no_connection)
            "chat.write" !in scopes -> context.getString(R.string.web_read_only_scope)
            else -> ""
        }

        val cfg = JSONObject()
            .put("sessionId", id)
            .put("token", "")                       // 令牌不传给页面：请求全在原生
            .put("apiBase", "")                     // 留空 → 页面不会自建 fetch / WebSocket
            .put("scopes", org.json.JSONArray(scopes))
            .put("title", session?.title ?: session?.workspaceTitle ?: id.take(12))
            .put("running", viewModel.selectedSessionRunning())
            .put("onLive", true)
            // 待办随首帧一起给，省掉一次等待；页面随后仍会按 projections 自行刷新。
            .put(
                "todos",
                org.json.JSONArray(
                    state.history?.todoItems().orEmpty().map {
                        JSONObject()
                            .put("content", it.content)
                            .put("status", it.status)
                    }
                ),
            )
            .put("warn", warn)

        // pageReady 已在 key 里，这里不必再判断；evaluateJavascript 要在主线程，
        // 而 LaunchedEffect 本来就跑在主线程。
        val view = web ?: return@LaunchedEffect

        // **确认通道**：evaluateJavascript 是单向的，没有回调、没有异常、没有返回值。
        // 发得太早（页面脚本还没跑完）就静默落空，界面表现和「压根没发」完全一样。
        // 所以改成握手：页面 init 里第一件事回调 [ChatWebBridge.pageReady]；
        // 原生每 250ms 重发一次，最多 40 次，收到回报才停。
        // 这样「发早了」与「没发」第一次变得可区分。
        val payload = cfg.toString()
        var attempts = 0
        val tick = object : Runnable {
            override fun run() {
                // 判据读的是 bridge.initAcked —— 真正由页面回调置位的那个字段。
                // 早先这里读一个局部 acked，而置位发生在别处，两套状态永远对不上，
                // 结果是每条会话都把 init 重发满 40 次。
                if (bridge.initAcked || attempts > 6 || view != web) {
                    mark("init 重试结束 · 本轮共" + attempts + "次 · 未收到应答=" + !bridge.initAcked)
                    return
                }
                attempts++
                // 先问一句「你到底在不在」，把结果读回来再决定要不要发 init。
                // 这一条是决定性的：它能区分「页面里压根没有 dshChat」（脚本没跑）
                // 与「有 dshChat 但 init 里抛了」（脚本跑了但炸了）。
                //
                // 报不出来的原因必须一起报。原版只回 'dshChat 不存在' 一句，
                // 而这句话在四种情况下都会出现：
                //   ① 页面还没解析完（正常，等下一轮）；
                //   ② 主文档加载失败（assets 没进包 / 路径写错）；
                //   ③ 脚本中途抛异常（dshChat 定义在文件末尾，前面炸了就永远不存在）；
                //   ④ 注入的 JS 接口没建起来（addJavascriptInterface 失败）。
                // 把 location.readyState / 页面内元素数 / 接口是否存在一并读回来，
                // 排障期那段「页面上报 60 项状态」的探测代码已撤掉：
                // 它的使命是确认脚本有没有执行 —— 那个问题在 2.1.0 就解决了。
                // 留着只会让这个函数变成一堵墙，以后谁读都要愣一下。
                view.evaluateJavascript("window.dshChat.init(" + payload + ")", null)
                view.postDelayed(this, 250)
            }
        }
        bridge.initAcked = false
        view.post(tick)
    }

    // 运行态单独推，不走 init。
    //
    // running 需要随会话状态变化送到页面（它决定「发送」是发送还是插话），
    // 但它**不能**出现在上面那条 effect 的 key 里 —— 早先放在那儿，
    // 结果每发一条消息、会话一进入运行态就重发一次 init，
    // 页面把命令、历史、审批重新拉一遍，诊断栏里连着刷出三行相同记录。
    LaunchedEffect(viewModel.selectedSessionRunning(), pageReady) {
        val view = web ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        view.evaluateJavascript(
            "window.dshChat.setRunning(" + viewModel.selectedSessionRunning() + ")",
            null,
        )
    }


    // ── dsh-ui 卡片：改由页面就地渲染，原生不再集中推送 ──
    //
    // 早先这里是：原生用 splitMessageBody 把卡片抽出来，通过 setCards 推给页面，
    // 页面塞进消息流**末尾**的一个 host。这个结构治不好两个问题 ——
    //   ① 卡片脱离了它在正文里的位置，全堆在底部；
    //   ② 原文里的围栏在页面看来仍是普通代码块，于是同一张卡片显示两次：
    //      一次在底部，一次是原位的一坨灰底 JSON。
    // 用户看到的就是「dsh-ui 显示有问题」。
    //
    // 现在页面自己按同一套判据切围栏并就地渲染（见 chat.html 的 splitUi），
    // 卡片出现在它本来该出现的位置，也不再残留 JSON 代码块。
    // 判据两边都存一份是有意的：页面要能在「自己拉 REST」的预览路径下单独工作，
    // 而那条路径上原生插不上手。两处注释互相指向，改动时一起改。

    // ── 把原生已归约好的流式状态推进页面 ──
    //
    // 不在这里另开一条 WS 订阅：[HarnessViewModel.connectEvents] 已经订阅过一次，
    // 并把事件归约进了 [HarnessState.liveChat]。再订阅一份只会得到同样的数据、
    // 多一条重连与一条 token 泄露面，还得自己再实现一遍 turn/step 归并。
    //
    // 这里只做一件事：把 Compose 已经算好的显示项，原样搬到页面上。
    val liveRevision = state.liveChat?.revision
    LaunchedEffect(liveRevision, state.selectedSessionId) {
        val live = state.liveChat ?: return@LaunchedEffect
        if (live.sessionId != state.selectedSessionId) return@LaunchedEffect
        val view = web ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect

        // revision 变了就意味着内容变了，重置游标让页面整体重画。
        // 逐条 diff 在这里没有意义：页面侧本来就会按 turn/step/index 自行归并。
        val items = live.displayItems()
        if (items.isEmpty()) return@LaunchedEffect

        val payload = org.json.JSONArray()
        items.forEach { item ->
            val o = JSONObject()
                .put("id", item.id)
                .put("seq", item.seq)
                .put("kind", item.kind.name.lowercase())
                .put("body", item.body)
                .put("title", item.title ?: "")
                .put("streaming", item.streaming)
            payload.put(o)
        }
        view.evaluateJavascript("window.dshChat.setLive(" + payload.toString() + ")", null)
    }

    // ── 工具活动不再单独推 ──
    //
    // 上一版（2.2.11）这里把「本轮已发生的工具」抽出来推给页面，因为当时运行期间
    // 历史根本不推过去，页面上一条工具活动都看不到。
    //
    // 2.2.12 起历史在运行中也照推（历史=已提交、实时区=在飞那段），而历史里本来就
    // 带着成组的工具活动 —— 两条路同时画，同一批调用就出现两次，真机上是
    // 「一排排的工具行」。工具活动统一由历史那条路径负责，这里整段撤掉。

    // 设置页的显示开关：是否显示「上下文注入」块。改了立刻推给页面。
    // 首屏就不会先闪一下上下文块再消失。
    LaunchedEffect(state.showContext, pageReady) {
        val view = web ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        view.evaluateJavascript(
            "window.dshChat.setPrefs(" + JSONObject().put("showContext", state.showContext) + ")",
            null,
        )
    }

    // 不在运行时，把（可能刚重载过的）历史推给页面。
    //
    // 这里是两个 bug 的交汇点，都出在我写的判据上：
    //
    // ① 早先写的是 `val liveSession = state.liveChat?.sessionId` 并拿它当 key，
    //    然后再 `if (liveSession == null) return`。可 liveChat 被置空的那一刻
    //    liveSession **正好就是 null** —— 于是在最需要推送历史的那一瞬提前返回，
    //    历史永远推不出去。
    //
    // ② 页面在 liveChat 非空时只渲染实时区，而每一次 step 提交后 liveChat 都会被
    //    置空再因下一段增量重新建一个「只含当前 step」的状态。历史推不过去，
    //    实时区又只有当前这一段 —— 用户看到的就是「一直在同一个位置说这一段，
    //    就把上一段没了」。
    //
    // 改法：key 用「是否在运行」这个布尔值（由 true→false 的变化正好触发一次），
    // 条件只看「有选中会话且不在运行」，不再拿 liveSession 做早退判据。
    // 运行中也照推，不再跳过。
    //
    // 历史与实时区是分工的：**历史 = 已提交的部分，实时区 = 在飞的那一段**。
    // 早先这里写着 `if (state.liveChat != null) return`，等于运行期间完全不推历史 ——
    // 而 LiveChatState 的设计又是「换 step 就重置、旧内容交给 REST 历史补回来」。
    // 补回来的那一步被这个早退挡掉，于是整轮只看得见当前这一段：
    // 用户原话「一直在同一个位置说这一段就把上一段没了」。
    //
    // 页面侧配套改了 renderHistory：重画时把 #liveHost 摘下来、画完再挂回末尾，
    // 所以这里尽管推，正在流式输出的那一段不会被抹掉、也不会重复。
    LaunchedEffect(state.history, state.selectedSessionId) {
        val view = web ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        if (state.selectedSessionId == null) return@LaunchedEffect
        view.evaluateJavascript(
            "window.dshChat.setHistory(" + (state.history?.let { historyJson(it) } ?: "null") + ")",
            null,
        )
    }

    // 待审批：状态一变就整份推过去，页面按 id 增量插入。
    val approvals = state.approvalState
    LaunchedEffect(approvals) {
        val view = web ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        val list = when (val a = approvals) {
            is ApprovalUiState.Pending -> a.items
            is ApprovalUiState.Deciding -> a.items
            else -> emptyList()
        }
        if (list.isEmpty()) return@LaunchedEffect
        val payload = org.json.JSONArray()
        list.forEach { ap ->
            payload.put(
                JSONObject()
                    .put("id", ap.id)
                    .put("toolName", ap.toolName)
                    .put("reason", ap.reason ?: "")
                    .put("detail", ap.detail ?: "")
                    .put("risk", ap.risk)
            )
        }
        view.evaluateJavascript("window.dshChat.setApprovals(" + payload.toString() + ")", null)
    }

    // 待回答问题：与审批同款，状态一变就整份推过去，页面按 callId 增量插入。
    //
    // 只推**当前会话**的问题：[HarnessState.questions] 本身就是按 selectedSessionId 取的
    // （见 HarnessViewModel.refreshPendingQuestions —— 插件的 resolveAgent 会把冷会话拉活，
    // 所以那条路永远只看当前会话）。页面也自己有一路 GET（chat.html 的 refreshQuestions），
    // 与审批的双路完全对称：原生推一份、页面自己补一份，哪条先到都不会重复。
    val questionSets = state.questions
    LaunchedEffect(questionSets, state.answeredCallIds) {
        val view = web ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        if (questionSets.isEmpty()) return@LaunchedEffect
        val payload = org.json.JSONArray()
        questionSets.forEach { set ->
            payload.put(questionJson(set, set.callId in state.answeredCallIds))
        }
        view.evaluateJavascript("window.dshChat.setQuestions(" + payload.toString() + ")", null)
    }
}

/**
 * 一组待答问题 → 页面能直接吃的 JSON。
 *
 * 与 [historyJson] 同样的理由手写：页面只关心这几个字段，给它补 @Serializable 反而
 * 会把「协议里还有哪些字段」这件事绑死在 Kotlin 的模型上。
 *
 * `answered` 是**原生这边的记忆**（用户可能是切过来之前在原生化答的），
 * 带上它页面就能直接画成「已回答」，不必等下一次 GET 才纠正。
 */
private fun questionJson(set: PendingQuestionSet, answered: Boolean): JSONObject {
    val questions = org.json.JSONArray()
    set.questions.forEach { question ->
        val options = org.json.JSONArray()
        question.options.forEach { option ->
            options.put(
                JSONObject()
                    .put("label", option.label)
                    .put("description", option.description ?: ""),
            )
        }
        questions.put(
            JSONObject()
                .put("id", question.id)
                .put("question", question.question)
                .put("header", question.header ?: "")
                .put("detail", question.detail ?: "")
                .put("options", options)
                .put("multiSelect", question.multiSelect),
        )
    }
    return JSONObject()
        .put("callId", set.callId)
        .put("state", set.state)
        .put("answered", answered)
        .put("questions", questions)
}


/**
 * 把 [io.github.hakunm.deepseekharness.data.ChatHistory] 摊成页面能直接用的形状。
 *
 * 刻意不碰 kotlinx.serialization：这个类型没有 @Serializable（它是 DSH 事件流的归约结果），
 * 而且页面只需要 `events` 与 `projections` 两个字段，手写比给整个类型补注解省事，
 * 也不会给别处引入新的解码约束。
 */
private fun historyJson(history: ChatHistory): String {
    val events = org.json.JSONArray()
    history.events.forEach { entry ->
        events.put(
            org.json.JSONObject()
                .put("event", org.json.JSONObject().put("type", entry.event.type).put("data", entry.event.data))
                .put("view", entry.view)
        )
    }
    return org.json.JSONObject()
        .put("events", events)
        .put("hasMore", history.hasMore)
        .put("projections", history.projections)
        .toString()
}
