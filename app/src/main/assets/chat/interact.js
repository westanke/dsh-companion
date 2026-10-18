/* interact.js —— 审批 / 问答 / 正文提取 / 历史整块重画 / 待办 / 连接 / 发送 / 弹层 / 斜杠命令 / 诊断（自 chat.html 逐字搬出）。
 * 依赖 core/render/tools；贡献：addApproval/decide/addQuestion/submitQuestion/pickText/renderHistory/connectWS/send/openSheet/refreshCommands。
 */



/* 审批卡 */
function addApproval(a) {
  dropEmpty();
  var w = document.createElement("div");
  w.className = "approve";
  w.setAttribute("data-approval", a.id);
  w.innerHTML =
    '<div class="hd">⚠ 需要审批</div>' +
    '<div>' + esc(a.toolName || "工具调用") +
    (a.reason ? " · " + esc(a.reason) : "") + "</div>" +
    (a.detail ? '<div class="cmd">' + esc(a.detail) + "</div>" : "") +
    '<div class="btns">' +
    '<button class="no">拒绝</button>' +
    '<button class="yes"' + (canWrite ? "" : " disabled") + ">允许一次</button></div>";
  w.querySelector(".no").onclick = function () { decide(a.id, false, w); };
  w.querySelector(".yes").onclick = function () { decide(a.id, true, w); };
  sink().appendChild(w);
  toBottom(true);
}
function decide(id, allow, node) {
  if (!canWrite) { toast("设备没有 chat.write 权限"); return; }
  api("/chat/sessions/" + encodeURIComponent(SID) + "/approvals/" +
      encodeURIComponent(id) + "/decision", "POST",
      { outcome: allow ? "allowed-once" : "rejected" })
    .then(function () {
      if (node) node.parentNode.removeChild(node);
      toast(allow ? "已允许一次" : "已拒绝");
    })
    .catch(function (e) { toast("审批失败：" + e.message); });
}

/* ── 问答卡（ask_user_question，方案 A：只认 continued）──
 *
 * 外形与审批卡共用 `.approve`（见上面的样式注释）。
 *
 * 数据**两条路**进来，与审批完全对称：
 *   ① 原生 setQuestions 推（原生那份只含当前会话）；
 *   ② 页面自己 GET（init 与几条 WS 事件之后，见 refreshQuestions）。
 * 两条路都按 callId 去重（页面节点上的 data-question），先到先画，不会画两张。
 *
 * 选项的 label 一律**不进 DOM 属性**，只放数字下标（data-i / data-j）——
 * 提交时再去 set.questions 里取原值。这样任何引号/尖括号都伤不到选择器，
 * 也不必给选择器写 CSS 转义。
 */
function addQuestion(set) {
  if (!set || !set.callId) return;
  if (log.querySelector('[data-question="' + set.callId + '"]')) return;
  dropEmpty();
  var w = document.createElement("div");
  w.className = "approve ask";
  w.setAttribute("data-question", set.callId);
  var qs = set.questions || [];
  var html = '<div class="hd">❓ 需要你的回答</div>';
  qs.forEach(function (q, i) {
    html += '<div class="q">';
    if (q.header) html += '<div class="qh">' + esc(q.header) + '</div>';
    html += '<div class="qt">' + esc(q.question || '') + '</div>';
    if (q.detail) html += '<div class="qd">' + esc(q.detail) + '</div>';
    var opts = q.options || [];
    if (opts.length) {
      opts.forEach(function (o, j) {
        html += '<label class="opt"><input type="' + (q.multiSelect ? 'checkbox' : 'radio') +
          '" data-i="' + i + '" data-j="' + j + '"><span>' + esc(o.label) +
          (o.description ? '<em>' + esc(o.description) + '</em>' : '') + '</span></label>';
      });
    } else {
      /* 没有选项 = 这题要自由文本，正好对应协议里的 custom。 */
      html += '<textarea class="custom" data-i="' + i + '" rows="2" placeholder="填写你的回答"></textarea>';
    }
    html += '</div>';
  });
  html += '<div class="btns"><button class="yes">提交回答</button></div>';
  w.innerHTML = html;
  if (set.answered) {
    markQuestionAnswered(w);
  } else {
    var btn = w.querySelector('.yes');
    btn.onclick = function () { submitQuestion(set, w, btn); };
  }
  sink().appendChild(w);
  toBottom(true);
  return w;
}

function markQuestionAnswered(node) {
  node.setAttribute("data-answered", "1");
  node.removeAttribute("data-busy");   /* 提交中flag不留：以后再推同一组也不该显示"提交中" */
  var btns = node.querySelector(".btns");
  if (btns && btns.parentNode) btns.parentNode.removeChild(btns);
  var inputs = node.querySelectorAll("input,textarea");
  for (var i = 0; i < inputs.length; i++) inputs[i].disabled = true;
  var hd = node.querySelector(".hd");
  if (hd) { hd.className = "hd done"; hd.textContent = "✓ 已回答"; }
}

function submitQuestion(set, node, btn) {
  if (!canWrite) { toast("设备没有 chat.write 权限"); return; }
  if (node.getAttribute("data-busy") === "1") return;   /* 防重复提交第一道闸（插件的 409 是第二道） */
  var qs = set.questions || [];
  var picked = {};
  var inputs = node.querySelectorAll("input");
  for (var k = 0; k < inputs.length; k++) {
    if (!inputs[k].checked) continue;
    var i0 = +inputs[k].getAttribute("data-i");
    if (!picked[i0]) picked[i0] = [];
    var opt = (qs[i0] && qs[i0].options && qs[i0].options[+inputs[k].getAttribute("data-j")]) || null;
    if (opt) picked[i0].push(opt.label);
  }
  var answers = [];
  var complete = true;
  qs.forEach(function (q, i) {
    var opts = q.options || [];
    if (opts.length) {
      var chosen = [];
      opts.forEach(function (o) { if ((picked[i] || []).indexOf(o.label) >= 0) chosen.push(o.label); });
      if (!chosen.length) complete = false;
      answers.push({ id: q.id, selected: chosen });
    } else {
      var ta = node.querySelector('textarea[data-i="' + i + '"]');
      var text = ta ? String(ta.value || "").replace(/^\s+|\s+$/g, "") : "";
      if (!text) complete = false;
      answers.push({ id: q.id, selected: [], custom: text });
    }
  });
  if (!complete) { toast("请先回答每一题"); return; }
  node.setAttribute("data-busy", "1");
  btn.disabled = true;
  btn.textContent = "提交中…";
  api("/chat/sessions/" + encodeURIComponent(SID) + "/questions/" + encodeURIComponent(set.callId) + "/answer",
      "POST", { answers: answers })
    .then(function () {
      markQuestionAnswered(node);
      toast("已回答");
    })
    .catch(function (e) {
      node.removeAttribute("data-busy");
      btn.disabled = false;
      btn.textContent = "提交回答";
      toast(questionError(e));
    });
}

/* 提交失败的文案：409 的两种 code 含义完全不同（答过了 / 这条作废了），
   原样甩 code 用户既看不懂，也不知道该不该重试。 */
function questionError(e) {
  var code = (e && e.code) || "";
  var msg = (e && e.message) || "";
  if (code === "QUESTION_REPLY_QUEUED") return "这组问题你已经回答过了，答复正在排队";
  if (code === "QUESTION_NOT_PENDING") return "这组问题已经不需要回答了";
  if (code === "FORBIDDEN" || code === "SCOPE_MISSING" || msg.indexOf("chat.write") >= 0) {
    return "这台设备没有 chat.write 权限";
  }
  return "提交失败：" + (msg || "未知错误");
}

/* 页面自己那一路：只对**当前会话** SID 发，绝不遍历会话列表 ——
   插件的 resolveAgent 是 resolve-or-resume，对冷会话会把那边的 agent 拉活。 */
function refreshQuestions() {
  if (!SID) return Promise.resolve();
  return api("/chat/sessions/" + encodeURIComponent(SID) + "/questions")
    .then(function (r) {
      ((r && r.items) || []).forEach(function (s) {
        if (!s || s.state !== "continued") return;
        addQuestion(s);
      });
    })
    .catch(function () {});
}

/* ── 历史归约 ──
   REST 历史是 DSH 原始事件流：user/message 与 assistant/message 已定稿，
   assistant/chunk 需按 (turn,step,index) 累积后再成段。 */
/* 按**段类型**取文字。
 *
 * 为什么必须分开取：早先这里把 text 与 reasoning 一起收了，结果是
 * 「思考过程」被当成正文灌进聊天气泡——用户看到我一大段英文内部推理直接铺在会话里。
 * 原生的做法是分开的：reasoning 进「思考过程」折叠块，只有 text 进正文。
 * 所以这里按 want 参数各取各的，调用方决定往哪儿放。
 */
function partsText(c, want) {
  if (c == null) return "";
  if (typeof c === "string") return want === "text" ? c : "";
  if (!Array.isArray(c)) return "";
  return c.map(function (p) {
    if (typeof p === "string") return want === "text" ? p : "";
    if (!p || typeof p !== "object") return "";
    if (p.type !== want) return "";
    return typeof p.text === "string" ? p.text : "";
  }).filter(function (s) { return s.trim() !== ""; }).join("\n\n");
}

/* 正文：只认 text 段。拼接规则与原生 ChatPresentation.contentText 一致
   （空段丢掉、段间空行）。 */
function contentText(c) {
  return partsText(c, "text");
}

/*
 * 取一条事件的正文。
 *
 * 旧版只有 typeof d.message === "string" 那一支，而真实历史里 assistant/message 的
 * 形状是 data.message = { role, content:[{type,text}] } —— 是**对象**不是字符串，
 * 于是全部落空，界面只剩工具调用/工具结果，正文一条都没有。
 * 用真实会话日志离线核过：745 条助手消息，旧实现提取出 0 条。
 * 现在递归下降一层，并处理 d.content 本身就是数组的情况（user/message 就是）。
 */
function pickText(d) {
  if (typeof d === "string") return d;
  if (!d || typeof d !== "object") return "";
  if (typeof d.text === "string" && d.text) return d.text;

  if (d.message && typeof d.message === "object") {
    var t = contentText(d.message.content);
    if (t) return t;
  }
  if (typeof d.message === "string") return d.message;

  if (Array.isArray(d.content)) {
    var t2 = contentText(d.content);
    if (t2) return t2;
  }
  if (typeof d.content === "string") return d.content;
  if (typeof d.body === "string") return d.body;
  if (Array.isArray(d.parts)) {
    return d.parts.map(function (p) {
      if (typeof p === "string") return p;
      return p && typeof p.text === "string" ? p.text : "";
    }).join("");
  }
  return "";
}
function renderHistory(hist) {
  RENDER_INTO = null;   /* 历史渲染一律落回 #log */
  clearThinking();      /* 思考条是流式状态，看历史时不该留着 */

  /* 整块重画。这是**唯一**的渲染路径 ——
   * 正文、工具、上下文、用户消息全在这里，不再有第二套「实时区」跟它抢着画。
   * 用户原话：「发出来的就当做历史对待，别自己替换了」。 */
  log.innerHTML = "";
  pend = {};
  var evs = (hist && hist.events) || [];
  var chunks = {}, order = [];

  /* 正文为空的事件一律跳过。
     assistant/message 里有一类是 content 纯 [tool-call]，本来就没有正文，
     不跳的话界面上会留下一串只有标题、没有内容的空「DSH」气泡。 */
  function addIfText(fn, d) {
    var t = pickText(d);
    if (t && t.trim()) fn(t);
  }

  /* 工具事件先攒着，遇到非工具事件再一次性落地成一组。
     这样连续的工具调用只占一个折叠块，而不是几十个。 */
  var toolRun = [];
  function flushTools() {
    flushThink();
    if (!toolRun.length) return;
    addToolGroup(toolRun.splice(0, toolRun.length));
  }

  /* 思考也攒着：一个回合里有多个 step，每个 step 的思考各成一块的话，
     流式期间就会看到一长串「思考过程」（真机截图里连着八个）。
     连续到达的思考合成一块，遇到别的内容再落地。 */
  var thinkRun = [];
  function flushThink() {
    if (!thinkRun.length) return;
    addAct("思考过程", thinkRun.join("\n\n"));
    thinkRun.length = 0;
  }

  evs.forEach(function (e) {
    var ev = e.event || e;
    var d = evData(ev);
    /* 只有**会渲染出可见内容**的事件才断开工具组。
     *
     * 早先是「任何非工具事件都断开」，结果 step/start、step/end 这类纯结构事件，
     * 以及那些只含 tool-call、没有正文的 assistant/message，全把组冲断了 ——
     * 分组形同虚设（实测 1771 个工具事件只降到 1739 组，白干）。
     * 现在改成：只有真的要往界面上写字时才收组。 */
    if (ev.type !== "tool/call" && ev.type !== "tool/result") {
      var mc0 = (d.message && typeof d.message === "object" && d.message.content) || d.content;
      /* 「可见」= 真的要往界面上写**给人读的内容**。三类都**不算**，各有理由：
       *   reasoning ——— 算了的话连续两次思考互相冲断，又成一长串「思考过程」；
       *   上下文注入 —— 系统每轮都塞。算了就会把一整串连续的工具调用切碎成
       *     「工具调用 2 次」「工具调用 12 次」… 真机上正是这一排排的组
       *     （用户截图）。它是环境噪音，不是内容。
       *   注入型 user/message —— 同上，只有 source.kind === "user" 才是人说的。
       * assistant/message 也不在这里判：它的思考与正文要按顺序一起处理。 */
      var visible = ev.type === "user/message" && isRealUser(d);
      if (visible) flushTools();
    }
    if (ev.type === "user/message") {
      /* 只有 source.kind === "user" 才是**用户真的发出去的**。
       *
       * user/message 这个事件类型里混着大量系统注入：运行上下文、时间上下文、
       * 后台任务通知、子代理完成、模型选择、技能清单、上下文压缩点…
       * 早先不判断一律渲染成蓝色用户气泡 —— 用户每次进会话都看见一大片
       * 「自己发过、其实没发」的东西（那份真实会话里 200 条 user/message，
       * 只有 57 条是用户发的，其余 143 条全是注入）。
       * 与原生 ChatPresentation.userItems 的判据保持一致：非 user 的收进折叠块。 */
      var srcKind = (d.source && d.source.kind) ? String(d.source.kind) : "";
      if (srcKind === "user") {
        addIfText(addUser, d);
      } else if (showContext) {
        addIfText(function (t) { addAct(contextLabel(d.source), t); }, d);
      }
    } else if (ev.type === "assistant/message") {
      /* reasoning 与 text 必须**分开**：前者进「思考过程」折叠块，后者才是正文。
         早先统一走 pickText，而 pickText 里把两种段一起取了，
         结果我的内部推理被当成正文整段灌进聊天气泡（真机截图里的那一大段英文）。 */
      var mc = (d.message && typeof d.message === "object" && d.message.content) || d.content;
      var body = partsText(mc, "text");
      /* 这里的 reasoning 段**刻意不取** —— 历史里不再显示思考过程（见上）。
         partsText(mc, "text") 只取 text 段，正是为了不让推理混进正文。 */
      if (body && body.trim()) {
        flushThink();
        flushTools();
        addAssistantText(body);
      }
    } else if (ev.type === "assistant/chunk") {
      var k = d.turn + "/" + d.step + "/" + (d.index || 0);
      if (!chunks[k]) { chunks[k] = { kind: d.kind, text: "" }; order.push(k); }
      chunks[k].text += d.text || "";
    } else if (ev.type === "tool/call") {
      flushThink();   /* 思考落在工具之前，顺序才对 */
      /* 只入队，不立刻渲染 —— 连续多次调用会合成一个组。 */
      toolRun.push({
        callId: d.callId || d.id || '',
        name: toolNameOf(d),
        arg: toolArgSummary(d.arguments),
        argsFull: toolArgsRaw(d),
      });
    } else if (ev.type === "tool/result") {
      flushThink();
      /* 按 callId 回填到对应的那一条；找不到就单独成条，不丢内容。 */
      var mid = (d.message && d.message.toolCallId) || d.toolCallId;
      var txt = pickText(d);
      var isErr = !!(d.message && d.message.isError);
      var hit = null;
      for (var x = toolRun.length - 1; x >= 0; x--) {
        if (mid && toolRun[x].callId === mid) { hit = toolRun[x]; break; }
      }
      if (hit) {
        hit.result = txt;
        hit.hasResult = true;
        hit.isError = isErr;
      } else {
        toolRun.push({ name: "工具结果", arg: "", result: txt, hasResult: true, isError: isErr });
      }
    } else if (ev.type === "reasoning") {
      /* 历史里的思考过程**不再渲染** —— 用户：
         「历史会话没必要显示思考过程了，没人看的」。
         实时那条思考行（#thinkbar）保留：那是运行状态，与这里不是一回事。 */
      return;
    } else if (ev.type === "context/injected" || ev.type === "context") {
      if (showContext) addIfText(function (t) { addAct("上下文注入", t); }, d);
    } else if (ev.type === "turn/end") {
      /* 终局失败：reason.kind === 'error'（或登出导致的 aborted）。
         判据与原生 ChatPresentation.turnEndItem、插件 turnFailureOf 三处一致 ——
         任何一处放宽，用户就会在没失败时看到红框；任何一处收紧，失败又被咽掉。
         max-tokens 不在这里：那是"回答被截断"，走下面 else-if 那条黄色警示。 */
      var f = turnFailureFrom(ev);
      if (f) { flushThink(); flushTools(); addTurnFailure(f); }
      else if (isMaxTokens(ev)) { flushThink(); flushTools(); addMaxTokensNotice(); }
    }
  });

  flushTools();   /* 收尾：末尾还攒着的那组也要落地，否则整段工具调用凭空消失 */

  /* assistant/chunk 也是同一个道理：连续几段 reasoning 合并成一块。 */
  order.forEach(function (k) {
    var c = chunks[k];
    if (!c.text) return;
    if (c.kind === "reasoning") return;   /* 历史不显示思考过程 */
    flushThink();
    addAssistantText(c.text);
  });
  flushThink();
  flushTools();


  if (!log.children.length) {
    var em = document.createElement("div");
    em.id = "empty";
    em.textContent = "这个会话还没有消息，发一条试试。";
    log.appendChild(em);
  }
  toBottom(true);
  renderTodosFrom(hist);
}

/* 待办：DSH 把 TODO 放在 projections 里，字段名各版本略有出入，都试一遍 */
function renderTodosFrom(hist) {
  var proj = (hist && hist.projections) || {};
  var t = proj.todos || proj.todoItems || proj.tasks || proj.todo;
  if (Array.isArray(t)) {
    setTodos(t.map(function (x) {
      return typeof x === "string" ? { content: x, status: "pending" } : x;
    }));
  }
}
function setTodos(list) {
  var box = $("todos");
  var arr = list || [];
  if (!arr.length) { box.hidden = true; return; }
  box.hidden = false;
  var done = 0;
  arr.forEach(function (t) { if (t.status === "completed") done++; });
  $("todoCnt").textContent = done + "/" + arr.length;
  $("todoList").innerHTML = arr.map(function (t) {
    var st = t.status || "pending";
    var ic = st === "completed" ? "✓" : st === "in_progress" ? "◐" : "○";
    var cls = st === "completed" ? "done" : st === "in_progress" ? "busy" : "";
    return '<div class="it"><span class="ic ' + cls + '">' + ic +
      '</span><span class="' + cls + '">' + esc(t.content || "") + "</span></div>";
  }).join("");
}

/* ── 运行状态 ── */
function setRunning(on) {
  running = !!on;
  $("stop").disabled = !on;
  $("steer").disabled = !on || !canWrite;
  /* 占位符必须短到一行放得下。
     早先写的是「输入消息…  Enter 发送 / Shift+Enter 换行」，在 346px 宽的窄屏上
     被撑成两行，而输入框只有一行多的高度 —— 第二行直接被裁掉，看着像残字。
     何况手机上有发送按钮，Enter/Shift+Enter 那套是桌面习惯，本来就多余。 */
  ta.placeholder = running ? "运行中… 发送即插话" : "输入消息…";
  $("send").disabled = !ta.value.trim() || !canWrite;
}

/* ── WebSocket：只认当前会话的事件 ──
   delta 是 token 级，落定由 chat.message.committed 收束；
   服务端不给回放，所以重连后一律重新拉 REST 历史。 */
function connectWS() {
  if (!API) return;
  if (ws) { try { ws.close(); } catch (e) {} }
  var sock;
  try {
    sock = new WebSocket(API.replace(/^http/, "ws") + "/events");
  } catch (e) { scheduleReconnect(); return; }
  ws = sock;

  sock.onopen = function () { wsRetry = 0; $("dot").className = "on"; };
  sock.onclose = function () { $("dot").className = "off"; scheduleReconnect(); };
  sock.onerror = function () { try { sock.close(); } catch (e) {} };
  sock.onmessage = function (m) {
    var ev;
    try { ev = JSON.parse(m.data); } catch (e) { return; }
    if (!ev) return;

    if (ev.type === "connection.ready") { $("dot").className = "on"; return; }
    if (ev.sessionId && SID && ev.sessionId !== SID) return;

    var d = ev.data || {};
    switch (ev.type) {
      case "chat.message.delta":
        liveDelta(d);
        setRunning(true);
        break;
      case "chat.message.committed":
        clearPend();
        break;
      case "chat.session.status":
        setRunning(d.running === true || d.status === "running");
        refreshQuestions();
        break;
      /* 问题多半出现在一轮中间，这几类事件就是「这一轮结构变了」的信号 ——
         与原生 refreshPendingQuestions 挂在同一批事件上，两处口径保持一致。 */
      case "chat.turn.start":
      case "chat.turn.end":
        refreshQuestions();
        break;
      /* 终局失败：限流 429 / 配额 / 密钥 / 登出停止。
         插件为失败发的**就是**这一帧（chat.turn.end 那一帧在失败时不发），
         所以必须先于一切收尾逻辑处理它。

         三件事，缺一件就还是"傻等"：
           ① 立刻把运行标志关掉 —— 停止按钮亮着、占位符写「运行中…」，
             用户看着就以为还在跑；
           ② 当场把红框画进对话流，不用等一次网络往返才看见；
           ③ 再拉一次历史兜底。这一趟看着与②重复，但它有两个②替不了的作用：
             把流式半截文字清掉（renderHistory 整块重画），以及万一有的服务端
             不把失败帧落盘，红框仍能靠实时帧留住。代价是重绘时闪一帧，可接受。 */
      case "chat.turn.failed": {
        setRunning(false);
        clearPend();
        if (d && d.failure) addTurnFailure(d.failure);
        refreshQuestions();
        refreshHistory();
        break;
      }
      case "chat.approval.requested":
      case "chat.approval.resolved":
        refreshApprovals();
        refreshQuestions();
        break;
      default:
        break;
    }
  };
}
function scheduleReconnect() {
  if (closed) return;
  var wait = Math.min(30000, 500 * Math.pow(2, wsRetry++));
  setTimeout(connectWS, wait);
}
window.addEventListener("pagehide", function () { closed = true; });

/* ── 刷新 ── */
/*
 * 静默早退诊断。
 *
 * 背景：初版 WebView 版会在四种情况下长得一模一样 —— 都停在 #empty 的
 * 「正在读取会话…」：init 压根没发到（原生 LaunchedEffect 早退）／init 发了但
 * sessionId 为空／没连上任何电脑／请求发出但没回。四条路径在界面上不可区分，
 * 于是只能靠猜 —— 猜了三轮全错。
 *
 * 现在每条早退都往控制台喊一句并记进 __diag，页面底部留一行可点开的诊断条。
 * 真机复现一次，把这行字念给我，根因一次到位。
 */
var __diag = [];
function note(where, detail) {
  var line = where + (detail ? ' · ' + detail : '');
  __diag.push(line);
  /* 只写控制台（logcat 里按 DshChatWebView 捞）。
     页面上的诊断条已撤，paintDiag 随之删除 —— 它引用的 #diag 元素早已不存在，
     留着就是走不到的死代码，还会让「引用的 id 是否都存在」这项体检报红。 */
  try { console.log('[dsh] ' + line); } catch (e) {}
}
function showFatal(text, detail) {
  var log = $('log');
  log.innerHTML = '';
  var box = document.createElement('div');
  box.style.cssText = 'color:var(--muted);text-align:center;padding:38px 20px;font-size:14px';
  box.innerHTML = '<div style="font-size:15px;color:var(--fg);margin-bottom:8px"></div>' +
    '<div style="font-size:12.5px;margin-bottom:14px"></div>' +
    '<button class="approve no" style="padding:10px 22px">重试</button>';
  box.firstChild.textContent = text;
  box.childNodes[1].textContent = detail || '';
  box.querySelector('button').onclick = function () { init(window.__lastCfg || {}); };
  log.appendChild(box);
}

function refreshHistory() {
  return api("/chat/sessions/" + encodeURIComponent(SID) + "/messages")
    .then(renderHistory)
    .catch(function (e) {
      /* 失败必须**在页面上留下痕迹**：原来只弹个 toast，#empty 的
         「正在读取会话…」会一直挂着，用户看到的就是卡住不动却不知道出了什么事。 */
      showFatal("读不出这个会话", e.message);
      throw e;
    });
}
function refreshApprovals() {
  return api("/chat/sessions/" + encodeURIComponent(SID) + "/approvals")
    .then(function (r) {
      var items = (r && r.items) || [];
      var seen = {};
      log.querySelectorAll("[data-approval]").forEach(function (n) {
        seen[n.getAttribute("data-approval")] = 1;
      });
      items.forEach(function (a) { if (!seen[a.id]) addApproval(a); });
    })
    .catch(function () {});
}
function loadCommands() {
  return api("/chat/sessions/" + encodeURIComponent(SID) + "/commands")
    .then(function (r) {
      CMD = ((r && r.items) || []).map(function (c) {
        return { name: c.name, hint: c.description || (c.input && c.input.hint) || "" };
      });
    })
    .catch(function () { CMD = []; });
}

/* ── 发送 ── */
function rid() {
  return "c-" + Date.now() + "-" + Math.random().toString(36).slice(2, 8);
}
function send(steer) {
  var text = ta.value.trim();
  if (!text) return;
  if (!canWrite) { toast("设备没有 chat.write 权限"); return; }

  ta.value = "";
  ta.style.height = "auto";
  var r = rid();
  addUser(text, r);

  /* 附件随这条一起发：图片内联 base64，文件引用已换到的 receiptId。
     还在上传中的文件不带（它们还没 receiptId，发出去只会是空引用）。 */
  var parts = buildParts(text);
  var body = {
    mode: (steer || running) ? "steer" : "queue",
    clientTimeZone: (Intl.DateTimeFormat().resolvedOptions().timeZone) || "UTC",
    clientRequestId: r
  };
  /* text 与 content **二选一**，不能都发 —— 原生 messageBody() 就是这么分的：
     只有一个 text part 时走 text 字段，多于一个（或含图片/文件）才走 content。
     两个都发会被服务端当成两条内容，附件就重了。 */
  if (parts.length === 1 && parts[0].type === "text") {
    body.text = text;
  } else {
    body.content = parts;
  }

  api("/chat/sessions/" + encodeURIComponent(SID) + "/messages", "POST", body)
    .then(function () {
      /* 发送成功后只保留**仍在上传**的那些（busy=true）。

         早先写成了 filter(!busy) —— 方向是反的：那时刚发完的图片正好被留下来，
         而仍在上传的文件被清掉（它下次就永远发不出去）。
         表现：连发两条消息会把第一条的图片再发一遍。 */
      ATTS = ATTS.filter(function (a) { return !!a.busy; });
      renderAtts();
    })
    .catch(function (e) {
      toast("发送失败：" + e.message);
      ta.value = text;                    /* 失败把原文还回去，别让用户白打一遍 */
      var n = log.querySelector('[data-key="' + r + '"]');
      if (n) n.parentNode.removeChild(n);
    });
}

/* ── 模型 / 智能体面板 ── */
function sheet(html, onOk) {
  var ov = document.createElement("div");
  ov.style.cssText = "position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:60;" +
    "display:flex;align-items:flex-end";
  var bx = document.createElement("div");
  bx.style.cssText = "background:var(--bg);width:100%;border-radius:16px 16px 0 0;" +
    "padding:8px 0 calc(env(safe-area-inset-bottom) + 12px);max-height:82vh;overflow:auto;" +
    "border-top:1px solid var(--line)";
  bx.innerHTML =
    '<div style="width:36px;height:4px;border-radius:2px;background:var(--line);margin:0 auto 10px"></div>' +
    html +
    '<div style="display:flex;gap:8px;padding:8px 16px 0">' +
    '<button class="approve no" data-close style="flex:1;padding:11px">关闭</button>' +
    /* 「应用」只在真的有「待确认后统一提交」的语义时才出现。
       添加附件的弹层里没有这种东西：那里每个选项自己就是动作，
       多一个「应用」只会让人以为要先选再确认（而且它永远取第一个选项）。 */
    (onOk ? '<button class="approve yes" data-ok style="flex:1;padding:11px">应用</button>' : '') +
    '</div>';
  ov.appendChild(bx);
  document.body.appendChild(ov);

  function close() { if (ov.parentNode) ov.parentNode.removeChild(ov); }
  var okBtn = bx.querySelector("[data-ok]");
  if (okBtn) okBtn.onclick = function () { Promise.resolve(onOk(bx)).then(close); };
  bx.querySelector("[data-close]").onclick = close;
  ov.onclick = function (e) { if (e.target === ov) close(); };
  /* 返回句柄而不是裸节点：调用方需要自己关闭弹层（选项即动作的弹层要立刻关）。 */
  return { box: bx, close: close };
}
function options(list, name, checked) {
  if (!list.length) return '<div style="color:var(--muted)">暂无可选项</div>';
  return list.map(function (o) {
    return '<label style="display:flex;gap:9px;align-items:center;padding:7px 0">' +
      '<input type="radio" name="' + name + '" value="' + esc(o.v) + '"' +
      (o.v === checked ? " checked" : "") + ">" +
      '<span>' + esc(o.n) + "</span>" +
      (o.h ? '<span style="margin-left:auto;color:var(--muted);font-size:12px">' +
        esc(o.h) + "</span>" : "") + "</label>";
  }).join("");
}
function openSheet() {
  Promise.all([
    api("/chat/sessions/" + encodeURIComponent(SID) + "/models").catch(function () { return null; }),
    api("/chat/agent-presets").catch(function () { return null; })
  ]).then(function (res) {
    var models = res[0], presets = (res[1] && res[1].items) || [];
    var cur = (models && models.current) || {};
    var groups = (models && models.groups) || [];

    var mopts = [];
    groups.forEach(function (g) {
      (g.models || []).forEach(function (m) {
        mopts.push({ v: (g.id || g.name) + "|" + m.id, n: m.name || m.id, h: g.name });
      });
    });
    var popts = presets.map(function (p) {
      return { v: p.id, n: p.name };
    });
    var curPre = "";
    if (models && models.current) curPre = cur.model;

    var html =
      '<div style="padding:12px 16px 4px;font-weight:700">模型</div>' +
      '<div style="padding:0 16px 8px;color:var(--muted);font-size:13px">当前：' +
        esc((cur.provider || "?") + " · " + (cur.model || "?")) + "</div>" +
      '<div style="padding:0 16px 8px">' + options(mopts, "mdl", curPre) + "</div>" +
      '<div style="border-top:1px solid var(--line);margin:6px 0"></div>' +
      '<div style="padding:12px 16px 4px;font-weight:700">智能体</div>' +
      '<div style="padding:0 16px 8px">' + options(popts, "pre", "") + "</div>";

    sheet(html, function (box) {
      var m = box.querySelector('input[name=mdl]:checked');
      var jobs = [];
      if (m && m.value.split("|")[1] !== cur.model) {
        var parts = m.value.split("|");
        jobs.push(api("/chat/sessions/" + encodeURIComponent(SID) + "/model", "PUT",
          { provider: parts[0], model: parts[1] })
          .then(function () { toast("模型已切换"); })
          .catch(function (e) { toast("切换失败：" + e.message); }));
      }
      var p = box.querySelector('input[name=pre]:checked');
      if (p) {
        jobs.push(api("/chat/sessions/" + encodeURIComponent(SID) + "/agent-preset", "PUT",
          { agentPreset: p.value })
          .then(function () { toast("智能体已切换"); })
          .catch(function (e) { toast("切换失败：" + e.message); }));
      }
      return Promise.all(jobs);
    });
  }).catch(function (e) { toast("读取失败：" + e.message); });
}

/* ── 斜杠命令 ── */
function refreshCommands() {
  var box = $("cmd"), v = ta.value;
  if (!v.startsWith("/") || /\s/.test(v.slice(1))) { box.hidden = true; return; }
  var q = v.slice(1).toLowerCase();
  var hit = CMD.filter(function (c) {
    return c.name.toLowerCase().indexOf(q) === 0;
  }).slice(0, 5);
  if (!hit.length) { box.hidden = true; return; }
  box.hidden = false;
  box.innerHTML = hit.map(function (c) {
    return '<button data-c="' + esc(c.name) + '">/' + esc(c.name) +
      (c.hint ? ' <span style="opacity:.65">' + esc(c.hint) + "</span>" : "") + "</button>";
  }).join("");
  box.querySelectorAll("[data-c]").forEach(function (b) {
    b.onclick = function () {
      ta.value = "/" + b.getAttribute("data-c") + " ";
      box.hidden = true;
      ta.focus();
    };
  });
}

/* ── 交互绑定 ── */
ta.addEventListener("input", function () {
  ta.style.height = "auto";
  ta.style.height = Math.min(ta.scrollHeight, 120) + "px";   /* 与 #ta 的 max-height 保持一致 */
  $("send").disabled = !ta.value.trim() || !canWrite;
  refreshCommands();
});
ta.addEventListener("keydown", function (e) {
  if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(false); }
});
bind("send").onclick = function () { send(false); };
bind("steer").onclick = function () { send(true); };
bind("back").onclick = function () {
  if (App && App.close) App.close(); else history.back();
};
bind("more").onclick = openSheet;
bind("attachBtn").onclick = function () {
  if (!canWrite) { toast("设备没有 chat.write 权限"); return; }
  var s = sheet(
    '<div style="padding:10px 16px 4px;font-weight:700">添加</div>' +
    '<div style="padding:4px 16px 12px">' +
    '<button class="approve no" data-a="image" style="width:100%;padding:12px;margin-bottom:8px">从相册选图片</button>' +
    '<button class="approve no" data-a="file" style="width:100%;padding:12px">选择文件</button></div>',
    null   /* 没有「待确认后统一提交」的语义，不要「应用」按钮 */
  );
  /* 每个选项**自己就是动作**。
     早先这两个按钮没绑任何事件，只有底部「应用」能触发，而它永远取第一个
     （data-a="image"）—— 所以点「选择文件」毫无反应，点「应用」也只是去开图片选择器。 */
  s.box.querySelectorAll("[data-a]").forEach(function (b) {
    b.onclick = function () {
      var kind = b.getAttribute("data-a") === "image" ? "image" : "file";
      s.close();
      addAtt(kind);
    };
  });
};
bind("stop").onclick = function () {
  api("/chat/runs/" + encodeURIComponent(SID) + "/cancel", "POST")
    .then(function () { toast("已请求停止"); })
    .catch(function (e) { toast("停止失败：" + e.message); });
};
document.addEventListener("click", function (e) {
  var a = e.target.closest ? e.target.closest("a") : null;
  if (a && a.href && /^https?:/i.test(a.href)) {
    e.preventDefault();
    if (App && App.openLink) App.openLink(a.href);
    else window.open(a.href, "_blank");
  }
});
/* 诊断条的 onclick 绑定已随 #diag 元素一起删除。
 *
 * 真机教训（v2.2.7 白屏）：先撤了 #diag 元素，却漏删这行绑定。
 * 它是**顶层语句**，脚本加载时就执行，$("diag") 返回 null，
 * `null.onclick = ...` 抛 TypeError → 整个脚本当场中断 →
 * window.dshChat 根本没定义 → 页面全白，只剩一句被 file:// 屏蔽掉细节的
 * 「Script error. @0」。撤元素时一定要连它的绑定一起删。 */
