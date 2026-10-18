/* bridge.js —— 附件 / 消息组装 buildParts / 桥接导出（自 chat.html 逐字搬出）。
 * 被原生 addJavascriptInterface 调用的入口都在这里：window.dshChat 与 initChat/appendMessage/showApproval/showQuestion/setTodos/setRunning。
 */

/* ═══════════ 3. 附件：图片 / 文件 ═══════════
   两种走法，取决于服务端要什么：
   - 图片**内联 base64**（type:image），一步到位，不需要先上传；
   - 文件先 POST /attachments 换 receiptId，消息里引用它（type:file）。 */
var ATTS = [];   /* {id, kind, name, size, type, data, receiptId, busy} */

function renderAtts() {
  var box = $('atts');
  if (!ATTS.length) { box.hidden = true; return; }
  box.hidden = false;
  box.innerHTML = ATTS.map(function (a) {
    var thumb = (a.kind === 'image' && a.data)
      ? '<img src="data:' + esc(a.type || 'image/png') + ';base64,' + a.data + '">'
      : '';
    return '<span class="a' + (a.busy ? ' busy' : '') + '" data-id="' + esc(a.id) + '">' +
      thumb + '<span class="nm">' + esc(a.name || '文件') + '</span>' +
      (a.busy ? '<span>…</span>' : '') +
      '<button class="x" data-x="' + esc(a.id) + '">×</button></span>';
  }).join('');
  box.querySelectorAll('[data-x]').forEach(function (b) {
    b.onclick = function () {
      ATTS = ATTS.filter(function (a) { return a.id !== b.getAttribute('data-x'); });
      renderAtts();
    };
  });
}

/* 全局只允许一个「等文件」的任务。早先每点一次 + 就起一条 50 秒的轮询链，
   连点几次就是几条并行链同时问 takeAttachment —— 而原生那边只有一个
   pendingUri 槽位，先返回的那条会把它吃掉，后面的链永远等不到。
   表现为「选了文件，界面半天没反应」或「有时能选有时不能」。 */
var attWait = null;

function addAtt(kind, accept) {
  if (!App || !App.pickFile) { toast('这个版本不支持附件'); return; }
  if (attWait) { toast('还在等你选文件'); return; }

  var started = App.pickFile(kind === 'image' ? 'image/*' : (accept || '*/*'));
  if (started === false) { toast('无法打开文件选择器'); return; }

  /* 取字节的等待：原生不提供取消信号，只能轮询。
     上限 8 秒：选择器里慢慢挑文件可以，但无限轮询会在用户已经点了取消、
     甚至已经关页之后仍然在跑，白白浪费电量。 */
  var tries = 0;
  var MAX = 32;               /* 32 x 250ms = 8s */
  attWait = setInterval(function () {
    var raw = null;
    try { raw = App.takeAttachment(); } catch (e) { stopWait(true); return; }

    /* 一律 JSON.parse 后看字段，不再用 indexOf 匹配字符串。

       早先用 raw.indexOf('"ok":false,"error"') 判断失败，依赖了 **字段顺序**：
       Android 的 JSONObject 不保证插入顺序，一旦变成 {"error":…,"ok":false} 就误判为「还没选」，
       页面继续轮询到超时，用户等进一个错误提示。 */
    var obj = null;
    try { obj = raw ? JSON.parse(raw) : null; } catch (e) { obj = null; }

    if (obj && obj.ok === true) {
      stopWait(false);
      pushAtt(obj);
      return;
    }
    if (obj && obj.ok === false && obj.error) {
      stopWait(true);
      toast(obj.error);
      return;
    }
    if (++tries > MAX) { stopWait(true); toast('未选择文件'); }
  }, 250);

  function stopWait(silent) {
    if (attWait) { clearInterval(attWait); attWait = null; }
  }

  function pushAtt(a) {
    var isImg = /^image\//.test(a.type || '');
    var att = {
      id: 'a-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7),
      kind: isImg ? 'image' : 'file',
      name: a.name, size: a.size, type: a.type, data: a.data,
      busy: !isImg,
    };
    ATTS.push(att);
    renderAtts();
    if (!isImg) uploadAtt(att);
  }
}

function uploadAtt(a) {
  if (!SID) return;
  api('/chat/sessions/' + encodeURIComponent(SID) + '/attachments', 'POST', {
    name: a.name,
    data: a.data,
  }).then(function (r) {
    a.receiptId = (r && (r.receiptId || r.id)) || '';
    a.busy = false;
    renderAtts();
  }).catch(function (e) {
    a.busy = false;
    ATTS = ATTS.filter(function (x) { return x.id !== a.id; });
    renderAtts();
    toast('上传失败：' + e.message);
  });
}

/* 发送时按服务端期望的形状组装。原生 PromptPart 有三种：
   text / image（内联 base64）/ file（引用 receiptId）。 */
function buildParts(text) {
  var parts = [];
  if (text) parts.push({ type: 'text', text: text });
  ATTS.forEach(function (a) {
    if (a.kind === 'image') {
      parts.push({ type: 'image', mediaType: a.type || 'image/png', data: a.data, name: a.name });
    } else if (a.receiptId) {
      parts.push({ type: 'file', receiptId: a.receiptId });
    }
  });
  return parts;
}

/* ── 对外接口：App 通过 evaluateJavascript 调这些 ── */
window.dshChat = {
  init: function (cfg) {
    /* 先回报原生，再做任何别的事。
       原生靠这条回执判断 evaluateJavascript 到底有没有送达 ——
       它是单向的，没有回执时「发早了」和「没发」在界面上完全无法区分。 */
    try { if (App && App.pageReady) App.pageReady(); } catch (e) {}
    note("init 已收到", "sid=" + (cfg.sessionId || "空"));
    API = String(cfg.apiBase || "").replace(/\/+$/, "");
    TOKEN = cfg.token || "";
    SID = cfg.sessionId || "";
    if (!SID) note("init 里没有 sessionId —— 原生没选中会话");
    canWrite = !cfg.scopes || cfg.scopes.indexOf("chat.write") >= 0;
    $("title").textContent = cfg.title || SID || "会话";
    $("warn").textContent = cfg.warn || "";
    $("warn").style.display = cfg.warn ? "" : "none";
    if (cfg.todos) setTodos(cfg.todos);
    setRunning(!!cfg.running);
    window.__lastCfg = cfg;
    if (cfg.onLive !== false) {
      note("开始拉取");
      loadCommands()
        .then(function () { note("命令已载入"); })
        .then(function () { return refreshHistory(); })
        .then(function () { note("历史已载入"); })
        .then(function () { return refreshApprovals(); })
        .then(function () { return refreshQuestions(); })
        .then(connectWS)
        .catch(function (e) {
          /* 到这一步说明连 loadCommands 都挂了（多半是没连上电脑），
             显示成失败态而不是永远转圈。 */
          note("拉取失败", e.message);
          if (!$('log').children.length) showFatal("连不上电脑", e.message);
        });
    } else {
      note("onLive=false，页面不自行拉取，等原生推历史");
    }
    return true;
  },
  /* 原生已把 WS 事件归约好，整份推进来。页面不自己解析事件流，
     也不重复开 WS —— 那条退路只给浏览器预览用。 */
  setLive: function (items) {
    /* ── 实时区不再渲染任何内容 ──
     *
     * 用户的要求（原话）：「发出来的就当做历史对待，别自己替换了」。
     *
     * 这里原先维护一个独立的实时区：按 turn/step 重建、只装当前这一段，
     * 原生一换 step 就重置 —— 于是「这一段说完，上一段没了」。围绕它我改了
     * 七八版（渲染落点、就地更新、工具合并、思考合并…），每一版都是在给
     * 这套双轨制打补丁，越补越乱。
     *
     * 现在只留一件事：把思考喂给底部那条思考行（它是「运行状态」，不是内容）。
     * 正文、工具、上下文**全部**由 renderHistory 从历史渲染 —— 一条路，不替换。
     * 历史由原生在流式期间高频重推（每来一段增量就刷新一次，250ms 去抖）。
     */
    lastLiveItems = items || [];
    var think = lastLiveItems.filter(function (it) {
      return it && it.kind === "reasoning";
    }).map(function (it) { return it.text || ""; }).join(" ");
    if (think) setThinking(think); else clearThinking();
  },

  setApprovals: function (items) {
    (items || []).forEach(function (a) {
      if (!a.id) return;
      if (log.querySelector('[data-approval="' + a.id + '"]')) return;
      addApproval(a);
    });
  },

  /* 原生推过来的待答问题（只含当前会话）。与 setApprovals 同款：
     已经画过的 callId 不再动它 —— 用户可能正在这道题上勾选。 */
  setQuestions: function (items) {
    (items || []).forEach(function (s) {
      if (!s || !s.callId) return;
      if (s.state && s.state !== "continued") return;
      addQuestion(s);
    });
  },

  /* 兼容原生仍会调一次：现在没有独立的实时区了，只需收掉思考条。 */
  clearLive: function () {
    clearThinking();
    lastLiveItems = null;
  },

  setCards: setCards,
  addAtt: addAtt,
  clearAtts: function () { ATTS = []; renderAtts(); },

  setHistory: function (hist) {
    lastHistory = hist;   /* 留一份，供 setPrefs 就地重画 */
    renderHistory(hist);
  },

  /* 设置页的显示开关。改了立刻重画，不用等原生下一次推历史。 */
  setPrefs: function (o) {
    var next = !!(o && o.showContext);
    if (next === showContext) return;
    showContext = next;
    if (lastHistory) renderHistory(lastHistory);
  },
  setTodos: setTodos,
  setRunning: setRunning,
  showApproval: addApproval,
  clear: function () { log.innerHTML = ""; pend = {}; },
  appendUser: addUser,
  appendAssistant: addAssistantText,
  toast: toast,
  connected: function () { return !!(ws && ws.readyState === 1); }
};

/* ── 兜底自检 ──
   标题停在启动烙印「DSH」说明脚本整个跑完了，只是 init 没来；
   标题还停在「载入中...」说明脚本在中途就断了（连烙印都没写上）。
   这两种在 2.0.2 里长得一模一样，所以必须靠标题来区分。 */
setTimeout(function () {
  if (!__diag.length) {
    var got = ($("title") || {}).textContent || "";
    if (got === "DSH") {
      note("脚本跑完了，但 init 没到");
      var e1 = $("empty");
      if (e1) e1.textContent = "页面脚本已执行，但 App 没把会话配置发过来";
    } else {
      note("脚本没跑到底，标题还是：" + got);
      var e2 = $("empty");
      if (e2) e2.textContent = "页面脚本中途停止（标题=" + got + "）";
    }
  }
}, 3000);

/* 兼容早期约定的别名 */
window.initChat = function (j) { window.dshChat.init(typeof j === "string" ? JSON.parse(j) : j); };
window.appendMessage = function (j) {
  var d = typeof j === "string" ? JSON.parse(j) : j;
  if (d.kind === "user") addUser(d.body, d.clientRequestId);
  else if (d.kind === "reasoning") addAct("思考过程", d.body);
  else addAssistantText(d.body || "");
};
window.appendAssistant = window.dshChat.appendAssistant;
window.showApproval = function (j) { addApproval(typeof j === "string" ? JSON.parse(j) : j); };
window.showQuestion = function (j) { addQuestion(typeof j === "string" ? JSON.parse(j) : j); };
window.setTodos = setTodos;
window.setRunning = setRunning;
window.setConnectionStatus = function (on) { $("dot").className = on ? "on" : "off"; };
