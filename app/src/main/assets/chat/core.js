/* core.js —— 全局状态与基础设施（自 chat.html 逐字搬出，2026-10-18 拆分）。
 * 贡献全局：App/API/TOKEN/SID/ws/running/canWrite/pend/CMD、$、log/ta、RENDER_INTO、showContext/lastHistory。
 * 必须第一个 <script> 加载：其余文件只消费这里的全局（普通脚本共享作用域，非 ES module —— file:// 下 import 被 CORS 拦）。
 */

/* ── 异常捕获必须在第一行 ──
   早先它排在第 26 行，于是第 26 行之前抛的任何异常都被静默吞掉：
   真机上一片空白，连 __dshErr 都是 null，看着像「没抛异常」，其实可能抛过。
   而且 typeof null === "object"，所以 typeof window.onerror 恒为 object，
   它压根不能证明 onerror 有没有被赋值 —— 我据此误判过好几版。 */
window.__err = null;
window.onerror = function (msg, src, line) {
  window.__err = (msg || '?') + ' @line' + line;
  return false;
};
"use strict";

/* 宿主 App 注入的桥接。有它就走 App 通道（复用其令牌、选路、重试）；
   没有它退回纯 fetch 直连 REST（浏览器预览与联调走这条）。 */
var App = window.DshBridge || null;

var API = "", TOKEN = "", SID = "";
var ws = null, wsRetry = 0, closed = false;
var running = false, canWrite = false;
var pend = {};
var CMD = [];
/* @ 补全要的会话工作目录信息；原生 init 多传这三个字段，旧版 chat.html 忽略未知字段不报错。 */
var ROOT = "", CWD = "", CWDABS = "";

/* ── 启动烙印 ──
   把「脚本是否执行到哪」直接写进标题栏。

   这一段是查了两轮才加的：页面曾经连续两版都停在「载入中…」与「正在读取会话…」，
   而标题里那个「载入中…」是 HTML 里写死的静态文案。于是有三种可能长得完全一样：
   ① 脚本一行都没跑；② 脚本跑了但在定义 window.dshChat 之前抛了；
   ③ 脚本全跑完了，只是原生没把 init 发过来。
   光看界面分不出来，所以把进度直接印在标题上：
   脚本跑完至少会变成「DSH」，走到 init 才会带上会话名。 */
/* 顶层任何异常都印在屏幕上。
   页面曾经连续两版静默卡住，而我只能看到「正在读取会话…」——
   异常被 WebView 吞进 logcat，真机上根本读不到。 */
window.onerror = function (msg, src, line, col, err) {
  var t = document.getElementById('title');
  if (t) t.textContent = 'JS出错';
  var e = document.getElementById('empty');
  if (e) e.textContent = '脚本出错：' + msg + ' @' + line;
  /* 留一份给原生探测读。
     原生那句 window.dshChat 在不在，只能告诉你「结果」，
     告诉不了你「为什么没有」—— 而没有的原因往往是脚本中途抛了。
     把原始错误钉在 window 上，原生一次 evaluateJavascript 就能读回来。 */
  window.__dshErr = msg + ' @' + src + ':' + line;
  return false;
};

try {
  document.title = 'DSH';
  var t = document.getElementById('title');
  if (t) t.textContent = 'DSH';
} catch (e) {}

var $ = function (id) { return document.getElementById(id); };
var log = $("log"), ta = $("ta");

/* 顶层绑定用：拿到元素，拿不到就给一个**游离节点**接住赋值。
 *
 * 为什么必须兜：顶层绑定在**脚本加载时**执行，一处 null 就会抛异常并中断
 * 整个脚本 —— window.dshChat 都来不及定义，真机表现是整页白屏；
 * 而 file:// 页面把错误详情屏蔽成「Script error. @0」，几乎无从排查。
 * 只读用途仍用 $()，保持「拿不到就是 null」的语义。 */
var BIND_SINK = document.createElement("div");
function bind(id) { return $(id) || BIND_SINK; }

/* 渲染落点：null 表示落到 #log（正常历史渲染）。
   流式渲染期间指向 #liveHost —— 这是修一个很显眼的真机 bug：
   早先 setLive 每帧都 host.innerHTML = "" 以为清空了，
   但 addAct/addMsg/addToolGroup 全是往 #log 追加，那个清理清的是空壳；
   于是每次 revision 变化就把当前内容**再追加一份**到聊天区，
   流式期间思考块越堆越多（重新进会话时 renderHistory 会清空 #log，所以看着正常）。 */
var RENDER_INTO = null;
function sink() { return RENDER_INTO || log; }

function toast(m) {
  var t = $("toast");
  t.textContent = m;
  t.classList.add("show");
  clearTimeout(t._t);
  t._t = setTimeout(function () { t.classList.remove("show"); }, 1900);
}

function esc(s) {
  return String(s == null ? "" : s)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

function atBottom() {
  return log.scrollHeight - log.scrollTop - log.clientHeight < 70;
}
function toBottom(force) {
  if (force || atBottom()) log.scrollTop = log.scrollHeight;
}

/* ── REST ── */
function api(path, method, body) {
  if (App && App.request) {
    /* 原生桥接是 request + poll 两段式：request 立刻给句柄（不阻塞 JS 线程，
       否则 WebView 滚动会跟着卡住），结果靠 poll 轮询取。
       这里把它包回 Promise，页面其余部分的 then/catch 写法不用动。 */
    var handle = App.request(path, method || "GET", body ? JSON.stringify(body) : null);
    return new Promise(function (resolve, reject) {
      var tries = 0;
      (function tick() {
        var raw = null;
        try { raw = App.poll(handle); } catch (e) { raw = null; }
        if (raw !== null && raw !== undefined) {
          var r;
          try { r = JSON.parse(raw); }
          catch (e) { reject(new Error("响应解析失败")); return; }
          if (r.status >= 200 && r.status < 300) {
            if (!r.text) { resolve(null); return; }
            try { resolve(JSON.parse(r.text)); }
            catch (e) { resolve(r.text); }
            return;
          }
          var msg = "HTTP " + (r.status || 0), code = r.code || "";
          try {
            if (r.body && r.body.error) {
              code = r.body.error.code || code;
              msg = r.body.error.message || msg;
            }
          } catch (e) {}
          var err = new Error(msg);
          err.code = code;
          reject(err);
          return;
        }
        if (++tries > 1200) { reject(new Error("请求超时")); return; }  /* ~2min */
        setTimeout(tick, 25);
      })();
    });
  }
  return fetch(API + path, {
    method: method || "GET",
    headers: (function () {
      var h = { Accept: "application/json", Authorization: "Bearer " + TOKEN };
      if (body) h["Content-Type"] = "application/json";
      return h;
    })(),
    body: body ? JSON.stringify(body) : undefined
  }).then(function (res) {
    if (!res.ok) {
      return res.text().then(function (raw) {
        var msg = "HTTP " + res.status, code = "";
        try {
          var b = JSON.parse(raw);
          if (b.error) { code = b.error.code || ""; msg = b.error.message || msg; }
        } catch (e) {}
        var err = new Error(msg);
        err.code = code;
        throw err;
      });
    }
    return res.status === 204 ? null : res.json();
  });
}

/* ── Markdown：自带实现，不依赖任何 CDN ──
   先把围栏代码块与行内代码抽出来占位，渲染完再放回去；
   否则代码里的 * _ # 会被当成强调与标题改写掉。 */
var FENCE = String.fromCharCode(96).repeat(3);
var TICK = String.fromCharCode(96);

function md(src) {
  var blocks = [], inl = [];
  var s = String(src == null ? "" : src);

  s = s.split(FENCE).join("@@FENCE@@");

  s = s.replace(/@@FENCE@@([A-Za-z0-9_+-]*)\n?([\s\S]*?)@@FENCE@@/g,
    function (m, lang, code) {
      blocks.push(code.replace(/\n$/, ""));
      return "@@CB" + (blocks.length - 1) + "@@";
    });

  s = s.split(TICK).join("@@TICK@@");
  s = s.replace(/@@TICK@@([^@@\n]+)@@TICK@@/g, function (m, c) {
    inl.push(c);
    return "@@IN" + (inl.length - 1) + "@@";
  });

  s = esc(s);

  s = s.replace(/!\[([^\]]*)\]\(([^)\s]+)[^)]*\)/g, function (m, a, u) {
    return /^https?:/i.test(u) ? '<img src="' + u + '" alt="' + a + '">' : a;
  });
  s = s.replace(/\[([^\]]+)\]\(([^)\s]+)[^)]*\)/g,
    '<a href="$2" target="_blank" rel="noopener">$1</a>');

  /* ── 表格 ──
   *
   * 真机才发现：早先 md() **压根没有表格**。而我回复里大量用表 ——
   * 手机端于是整段显示成原始竖线（截图里 | 项 | 之前 | 现在 | 那种），
   * 网页版却是正常表格，两边观感差得远。
   *
   * 处理方式与代码块同一套路：先把整块抽出来占位，最后统一生成 HTML。
   * 必须在这里抽：再往后就是 \n → <br> 那一步，表格行会被 <br> 拆散。
   */
  var tables = [];
  s = s.replace(
    /^[ \t]*\|[^\n]*\|[ \t]*\n[ \t]*\|[ \t]*:?-+:?[ \t]*(\|[ \t]*:?-+:?[ \t]*)*\|[ \t]*(?:\n[ \t]*\|[^\n]*\|[ \t]*)*/gm,
    function (block) {
      tables.push(block);
      return "\n@@TB" + (tables.length - 1) + "@@\n";
    }
  );

  s = s.replace(/^&gt;\s?(.*)$/gm, "<blockquote>$1</blockquote>");
  s = s.replace(/^\s{0,3}(#{1,6})\s+(.+)$/gm, function (m, h, t) {
    return "<h" + h.length + ">" + t.replace(/\*\*|__/g, "") + "</h" + h.length + ">";
  });
  s = s.replace(/^\s{0,3}([-*_])(\s*\1){2,}\s*$/gm, "<hr>");

  s = s.replace(/^\s{0,3}([*+-])\s+(.+)$/gm, "<li>$2</li>");
  s = s.replace(/^\s{0,3}(\d{1,9})[.)]\s+(.+)$/gm, "<li>$2</li>");
  s = s.replace(/(<li>[\s\S]*?<\/li>)(?!\s*<li>)/g, "<ul>$1</ul>");

  s = s.replace(/(\*\*|__)(?=\S)([\s\S]*?\S)\1/g, "<strong>$2</strong>");
  s = s.replace(/(^|[^\w*])(\*|_)(\S(?:[\s\S]*?\S)?)\2(?=[^\w*]|$)/g, "$1<em>$3</em>");
  s = s.replace(/~~(?=\S)([\s\S]*?\S)~~/g, "<del>$1</del>");

  s = s.replace(/\n{2,}/g, "</p><p>");
  s = s.replace(/\n/g, "<br>");

  var html = "<p>" + s + "</p>";
  html = html.replace(/<p>\s*(<h[1-4]>[\s\S]*?<\/h[1-4]>)\s*<\/p>/g, "$1");
  html = html.replace(/<p>\s*(<ul>[\s\S]*?<\/ul>)\s*<\/p>/g, "$1");
  html = html.replace(/<p>\s*(<hr>)\s*<\/p>/g, "$1");
  html = html.replace(/<p>\s*(<blockquote>[\s\S]*?<\/blockquote>)\s*<\/p>/g, "$1");

  /* 表格必须**最先**展开。
   *
   * 单元格里可能带着 @@IN/@@CB 占位（行内代码、代码块都是先抽走的）。
   * 早先把 @@TB 放在最后：等它把表格 HTML 插进去时，@@IN/@@CB 那两步早就跑完了，
   * 单元格里的占位符于是原样漏到屏幕上 —— 真机截图里那一片 @@IN0@@ @@IN1@@ 就是它。
   * 先展开表格，再让 @@CB/@@IN 扫过整段（包括刚插进来的单元格内容），才收得住。 */
  html = html.replace(/@@TB(\d+)@@/g, function (m, i) {
    return mdTable(tables[+i]);
  });
  html = html.replace(/@@CB(\d+)@@/g, function (m, i) {
    return '<pre><button class="copy" onclick="cpy(this,' +
      JSON.stringify(blocks[+i]) + ')">复制</button><code>' +
      esc(blocks[+i]) + "</code></pre>";
  });
  html = html.replace(/@@IN(\d+)@@/g, function (m, i) {
    return "<code>" + esc(inl[+i]) + "</code>";
  });
  /* 表格是块级元素，不能裹在 <p> 里（外面那圈 <p>…</p> 由浏览器补正，
     会在表格前后留下一对空行）。把紧邻的段落包裹与 <br> 去掉。 */
  html = html.replace(/<p>\s*(?:<br>\s*)*(<div class="tw">[\s\S]*?<\/table><\/div>)(?:\s*<br>)*(?:\s*<\/p>)?/g, "$1");
  return html;
}

/* 拆一行表格：去掉首尾竖线，按 | 切列。 */
function mdCells(line) {
  var t = String(line == null ? "" : line).trim();
  if (t.charAt(0) === "|") t = t.slice(1);
  if (t.charAt(t.length - 1) === "|") t = t.slice(0, -1);
  return t.split("|").map(function (c) { return c.trim(); });
}

/* 把一个表格块变成 HTML。
   单元格内容在这里才 esc —— 抽取发生在转义之后，所以进到 cell 的文本
   已经带好了 @@IN/@@CB 占位与实体转义，直接拼即可。
   外面套 .tw：窄屏放不下的宽表横向滚，与卡片里的表格同一套样式。 */
function mdTable(block) {
  var lines = String(block).replace(/\n+$/, "").split("\n").filter(function (l) {
    return l.trim() !== "";
  });
  if (lines.length < 2) return "<p>" + lines.join("<br>") + "</p>";
  var head = mdCells(lines[0]);
  var rows = lines.slice(2).map(mdCells);
  var out = '<div class="tw"><table><thead><tr>';
  out += head.map(function (c) { return "<th>" + c + "</th>"; }).join("");
  out += "</tr></thead><tbody>";
  rows.forEach(function (r) {
    out += "<tr>";
    for (var i = 0; i < head.length; i++) {
      out += "<td>" + (r[i] == null ? "" : r[i]) + "</td>";
    }
    out += "</tr>";
  });
  return out + "</tbody></table></div>";
}

function cpy(btn, code) {
  var done = function () {
    btn.textContent = "已复制";
    setTimeout(function () { btn.textContent = "复制"; }, 1300);
  };
  if (navigator.clipboard && App && App.copy) { App.copy(code); done(); return; }
  if (navigator.clipboard) { navigator.clipboard.writeText(code).then(done, function () { toast("复制失败"); }); }
  else toast("复制失败");
}

/* ── 消息渲染 ── */
function dropEmpty() {
  var e = $("empty");
  if (e) e.parentNode.removeChild(e);
}
function addMsg(role, html, key) {
  dropEmpty();
  var w = document.createElement("div");
  w.className = "msg m-" + role;
  if (key) w.setAttribute("data-key", key);
    /* 标签靠各自那一侧：我的在右、DSH 在左，和气泡对齐。 */
  var lbl = role === "user" ? "我" : "DSH";
  w.innerHTML = '<div class="role">' + lbl + '</div><div class="bubble">' + html + "</div>";
  sink().appendChild(w);
  toBottom(true);
  return w;
}
function addUser(text, rid) {
  return addMsg("user", esc(text).replace(/\n/g, "<br>"), rid);
}
/* ── dsh-ui 围栏：在页面里就地切开 ──
 *
 * 为什么改主意、由页面自己认围栏：
 * 早先是原生用 splitMessageBody 把卡片抽出来、再通过 setCards 集中推给页面。
 * 那个做法有个治不好的结构性问题 —— 卡片被塞进消息流末尾的一个 host，
 * 而原文里的围栏在页面看来仍是一段普通代码块。于是同一张卡片显示两次：
 * 一次在底部，一次是原位的一坨灰底 JSON。用户看到的就是「卡片显示有问题」。
 *
 * 围栏判据与原生 splitMessageBody 保持一字不差（每条都对应一个踩过的坑）：
 *   ① 标记里含 dsh-ui（```dsh-ui 与 ```json dsh-ui 都认）；
 *   ② 只看行首的围栏头，正文里提到的 dsh-ui 不算；
 *   ③ 必须闭合，未闭合整段按原文保留（流式输出时不能切出半截 JSON）；
 *   ④ 空卡片不切。
 */
function isUiInfo(line) {
  if (line.indexOf(FENCE) !== 0) return false;
  var info = line.slice(FENCE.length).trim();
  if (!info) return false;
  return info.split(/[ \t]+/).some(function (w) { return w === 'dsh-ui'; });
}
function splitUi(src) {
  var body = String(src == null ? '' : src);
  var out = [];
  var cursor = 0;
  while (cursor < body.length) {
    var open = -1, pos = cursor;
    while (pos <= body.length) {
      var nl = body.indexOf('\n', pos);
      var end = nl < 0 ? body.length : nl;
      if (isUiInfo(body.slice(pos, end))) { open = pos; break; }
      if (nl < 0) break;
      pos = nl + 1;
    }
    if (open < 0) break;
    var headEnd = body.indexOf('\n', open);
    if (headEnd < 0) return [{ kind: 'prose', text: body }];
    var close = body.indexOf(FENCE, headEnd + 1);
    if (close < 0) return [{ kind: 'prose', text: body }];
    var before = body.slice(cursor, open).trim();
    if (before) out.push({ kind: 'prose', text: before });
    var json = body.slice(headEnd + 1, close);
    if (json.trim()) out.push({ kind: 'ui', json: json });
    var after = body.indexOf('\n', close + FENCE.length);
    cursor = after < 0 ? body.length : after + 1;
  }
  var rest = body.slice(cursor).trim();
  if (rest) out.push({ kind: 'prose', text: rest });
  return out.length ? out : [{ kind: 'prose', text: body }];
}

/* 流式期间把**尚未闭合的 dsh-ui 围栏**整段切掉。
 *
 * 不切的话：围栏闭合前它只是一段普通文本，会先以原始 JSON 代码块的形态显示一次，
 * 等闭合再变成卡片 —— 用户看到的就是「这里闪了一下」（真机截图里那个
 * {"title":"v2.2.9","gap":8,...）。节点复用治不了这个，因为内容本身换了形态。
 *
 * 与定稿渲染的取舍不同：那边未闭合整段保留（不能丢内容，宁可露出源码）；
 * 这边是流式中间态，藏起来比露出半截 JSON 好看。
 */
function stripOpenUiFence(text) {
  var body = String(text == null ? '' : text);
  var cursor = 0;
  while (cursor < body.length) {
    var open = -1, pos = cursor;
    while (pos <= body.length) {
      var nl = body.indexOf('\n', pos);
      var end = nl < 0 ? body.length : nl;
      if (isUiInfo(body.slice(pos, end))) { open = pos; break; }
      if (nl < 0) break;
      pos = nl + 1;
    }
    if (open < 0) return body;
    var headEnd = body.indexOf('\n', open);
    if (headEnd < 0) return body.slice(0, open).replace(/\s+$/, '');
    var close = body.indexOf(FENCE, headEnd + 1);
    if (close < 0) return body.slice(0, open).replace(/\s+$/, '');
    var after = body.indexOf('\n', close + FENCE.length);
    cursor = after < 0 ? body.length : after + 1;
  }
  return body;
}

/* 流式期间把**尚未成形的表格**切掉。
 *
 * 表格是逐行到达的：先是表头那行竖线、之后才来分隔行。
 * 分隔行没到之前它只是一行普通文本，会以原始竖线的样子先显示一次，
 * 分隔行一到就整块变成表格 —— 中间那一下就是用户说的「闪」。
 * 与未闭合围栏同理：成形前先不显示，成形后一次给出。
 *
 * 只认「行首就是竖线」的行，正文里随便出现的 a | b 不会被误伤。
 */
function stripOpenTable(text) {
  var lines = String(text == null ? '' : text).split('\n');
  var i = lines.length - 1;
  while (i >= 0 && /^\s*\|/.test(lines[i])) i--;
  if (i === lines.length - 1) return text;    /* 末尾不是表格行，不动 */
  var block = lines.slice(i + 1);
  /* 第二行必须是分隔行（|---|:--:| 这类）才算成形。 */
  var sep = block.length >= 2 ? block[1] : '';
  var formed = /^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/.test(sep) && sep.indexOf('-') >= 0;
  if (formed) return text;
  return lines.slice(0, i + 1).join('\n').replace(/\s+$/, '');
}

/* 把一段助手正文填进**已有的** .bubble 里。
 *
 * 新建与就地更新共用同一个实现，是为了让流式渲染只换内容、不换节点 ——
 * 节点一换就要重新布局，屏幕上看就是「在同一位置不停地闪」。
 *
 * streaming=true（流式中间态）时，未闭合的围栏与未成形的表格都先藏起来，
 * 见 stripOpenUiFence / stripOpenTable。
 */
function fillAssistantBubble(bubble, text, streaming) {
  if (streaming) {
    text = stripOpenUiFence(text);
    text = stripOpenTable(text);
  }
  var segs = splitUi(text);
  var hasUi = segs.some(function (s) { return s.kind === 'ui'; });
  /* 没有卡片的常见情况：只有一个 .md 子节点时直接换它的 innerHTML，最省。 */
  if (!hasUi) {
    var only = bubble.querySelector('.md');
    if (only && bubble.children.length === 1) { only.innerHTML = md(text); return; }
    bubble.innerHTML = '<div class="md">' + md(text) + '</div>';
    return;
  }
  bubble.innerHTML = "";
  segs.forEach(function (s) {
    if (s.kind === 'ui') {
      var spec = null;
      try { spec = JSON.parse(s.json); } catch (e) { spec = null; }
      if (spec && typeof spec === 'object') {
        bubble.appendChild(renderCard(spec));
      } else {
        /* 解析失败：把原文放出来，不静默吞掉 —— 与原生「坏卡片回落」一致。 */
        var pre = document.createElement('pre');
        var code = document.createElement('code');
        code.textContent = s.json;
        pre.appendChild(code);
        bubble.appendChild(pre);
      }
    } else if (s.text) {
      var d = document.createElement('div');
      d.className = 'md';
      d.innerHTML = md(s.text);
      bubble.appendChild(d);
    }
  });
}

function addAssistantText(text, streaming) {
  dropEmpty();
  var w = document.createElement("div");
  w.className = "msg m-assistant";
  var head = document.createElement("div");
  head.className = "role";
  head.textContent = "DSH";
  w.appendChild(head);
  var bubble = document.createElement("div");
  bubble.className = "bubble";
  fillAssistantBubble(bubble, text, streaming);
  w.appendChild(bubble);
  sink().appendChild(w);
  toBottom(true);
  return w;
}
function newLiveBody(key) {
  dropEmpty();
  var w = document.createElement("div");
  w.className = "msg m-assistant";
  w.setAttribute("data-key", key);
  w.innerHTML = '<div class="role">DSH</div><div class="bubble md"></div>';
  sink().appendChild(w);
  toBottom(true);
  return w.querySelector(".bubble");
}
function newReasonBody() {
  dropEmpty();
  var d = document.createElement("details");
  d.className = "act";
  d.innerHTML = '<summary><span class="caret">▶</span><span>思考过程</span></summary><div class="body"></div>';
  sink().appendChild(d);
  toBottom(true);
  return d.querySelector(".body");
}

/* 流式气泡：服务端按 token 推 delta（带 turn/step/index），
   这里自己按四元组归并；turn 或 step 一变就另起一段，
   否则推理与正文会被揉进同一个气泡。 */
function liveDelta(d) {
  var key = d.sessionId + "/" + d.turn + "/" + d.step + "/" + (d.index || 0);
  var slot = pend[key];
  if (!slot) {
    slot = {
      buf: "",
      body: d.kind === "reasoning" ? newReasonBody() : newLiveBody(key)
    };
    pend[key] = slot;
  }
  slot.buf += d.text || "";
  if (d.kind === "reasoning") {
    slot.body.textContent = slot.buf;
  } else {
    slot.body.innerHTML = md(slot.buf);
  }
  toBottom();
}
function clearPend() { pend = {}; }

/* ── 事件数据读取：吃两种形状 ──
 *
 * 服务端那条路是 Kotlin → org.json。kotlinx 的 JsonElement 有时被当普通对象、
 * 有时被 quote 成**字符串**（org.json 遇到不认识的类型会写它的 toString()）。
 * 只认对象的话工具名会整片变空，动作摘要退化成「工具，工具结果」——
 * 真机上那一排排的组正是这个。两种都吃下。
 */
function evData(ev) {
  var d = ev && ev.data;
  if (typeof d === "string") {
    try { d = JSON.parse(d); } catch (e) { return {}; }
  }
  if (!d || typeof d !== "object") return {};
  /* 有的形状会再套一层 data。 */
  if (d.data && typeof d.data === "object" && !d.name && !d.content && !d.message) return d.data;
  return d;
}

/* 是不是「人发的」那条。历史与实时两条路共用同一判据。 */
function isRealUser(d) {
  return !!(d && d.source && d.source.kind === "user");
}

/* 工具名兜底：不同形状下它可能叫 name / tool / toolName / tool_name。 */
function toolNameOf(d) {
  if (!d || typeof d !== "object") return "";
  return d.name || d.tool || d.toolName || d.tool_name
    || (d.message && (d.message.name || d.message.toolName))
    || (d.call && d.call.name) || "";
}

/* 原始参数：动作摘要要扫它里面的内层工具名（tools.bash / tools.write …）。 */
function toolArgsRaw(d) {
  if (!d || typeof d !== "object") return "";
  var a = d.arguments != null ? d.arguments : (d.args != null ? d.args : d.input);
  if (typeof a === "string") return a;
  if (a && typeof a === "object") { try { return JSON.stringify(a); } catch (e) { return ""; } }
  return "";
}

/* 上一份 setLive 载荷。整份重画时要用，也留着给调试。 */
var lastLiveItems = null;

/* 是否显示「上下文注入」块（运行上下文 / 时间上下文 / 后台任务通知…）。
 * 由设置页控制，默认关 —— 用户：「没必要给我显示 上下文注入，手机端不看的」。
 * 这些块是系统每轮塞进来的，会在每条消息之间插一排折叠块，把正文挤得七零八落。 */
var showContext = false;
/* 记住最后一份历史，供开关切换时就地重画（不用等原生下一次推送）。 */
var lastHistory = null;
var lastLiveItems = null;

