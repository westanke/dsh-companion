/* render.js —— 思考条 / 消息与活动块渲染 / 失败与截断行 / 上下文标签（自 chat.html 逐字搬出）。
 * 依赖 core.js 全局；贡献：setThinking/clearThinking/addAct/turnFailureFrom/addTurnFailure/contextLabel 等。
 */



/* ── 流式思考条 ──
 *
 * 位置：消息区**下方**、输入区**上方**那条（原来挂诊断黄条的地方）。
 * 它是「运行状态」而不是「会话内容」，所以不进消息流 ——
 * 插在消息流里会随消息滚动、还会把内容挤开，也不好一眼看到。
 *
 * 行为：有思考就填一行（右侧省略号截断、只留最新的一段），没有就整条隐藏。
 * 完整的思考内容等这一轮结束后由历史里的「思考过程」折叠块承载。
 */
function setThinking(text) {
  var el = document.getElementById("thinkbar");
  if (!el) return;
  var t = String(text == null ? "" : text).replace(/\s+/g, " ").trim();
  if (!t) { el.hidden = true; return; }
  /* 只留尾部：思考是往前推进的，最新的一段才有信息量。 */
  if (t.length > 140) t = "…" + t.slice(-140);
  var tt = el.querySelector(".tt");
  if (tt) tt.textContent = t;
  el.hidden = false;
}
function clearThinking() {
  var el = document.getElementById("thinkbar");
  if (!el) return;
  el.hidden = true;
  var tt = el.querySelector(".tt");
  if (tt) tt.textContent = "";
}

/* 折叠块：工具调用 / 工具结果 / 上下文注入 */
function addAct(label, text) {
  dropEmpty();
  var d = document.createElement("details");
  d.className = "act";
  d.innerHTML = '<summary><span class="caret">▶</span><span>' + esc(label) +
    '</span></summary><div class="body">' + esc(text || "") + "</div>";
  sink().appendChild(d);
  return d;
}

/* 从一条历史 `turn/end` 事件里解出失败；不是失败就返回 null。
 *
 * 与原生 ChatHistoryMapping.turnEndItem 同一判据：只认 reason.kind === 'error'
 * 与「登出导致的 aborted」两种。max-tokens 不在这里 —— 它不是失败，
 * 由 isMaxTokens + addMaxTokensNotice 单独渲染成**黄色**警示。
 * 报成红框会让用户以为白跑了一轮。 */
function turnFailureFrom(ev) {
  var d = evData(ev);
  var r = (d && typeof d === "object" && d.reason) || null;
  if (!r || typeof r !== "object") return null;
  if (r.kind === "error") {
    var e = r.error;
    if (!e || typeof e !== "object") return { message: String(e || "本轮运行失败"), code: null, stopped: false };
    return {
      message: typeof e.message === "string" ? e.message : JSON.stringify(e),
      /* code 可能是空串 —— 保留空串，不当成缺失 */
      code: typeof e.code === "string" ? e.code : null,
      stopped: false,
    };
  }
  var inner = r.reason;
  if (r.kind === "aborted" && inner && inner.kind === "hook" && inner.reason === "deepseek-account/signed-out") {
    return { message: "任务已因退出 DeepSeek 登录而停止。", code: "ACCOUNT_SIGNED_OUT", stopped: true };
  }
  return null;
}

/* ── 一轮运行的终局失败（限流 429 / 配额 / 密钥 / 登出停止） ──
 *
 * 为什么它值得一个专属渲染函数而不是塞进 addAct：用户被"傻等"坑过，
 * 失败行必须是这条会话里**最显眼**的东西。红色边框、加粗标题、不折叠。
 *
 * 三行信息各有分工（与原生 TurnFailureRow 同一口径）：
 *   .hd  = 标题（「本轮运行失败」/「任务已停止」）+ 错误码；
 *   .msg = 服务端原话。429 限流那句话就躺在这儿，request id 也在这儿，
 *          用户截图发回来就是唯一能排查现场的凭据。
 */
/* 输出 token 上限的判据：内核 `turn/end` 的 reason.kind === 'max-tokens'。
 *
 * 与 turnFailureFrom 分开是**有意的**：截断不是失败。内核自己也是这么分的
 * —— 它给的是独立的 `turn-max-tokens` 节点，而不是 `turn-error`
 * （见内核 TURN_PROCESS_INDEPENDENT_KINDS）。 */
function isMaxTokens(ev) {
  var d = evData(ev);
  var r = (d && typeof d === "object" && d.reason) || null;
  return !!r && typeof r === "object" && r.kind === "max-tokens";
}

/* 「已达到输出 token 上限」那条黄色提示。
 *
 * 以前这里**刻意排除**它，注释写的理由是「内核把它渲染成黄色警示」。
 * 那个理由不成立：本页是客户端自渲染，内核不会替客户端画任何东西 ——
 * 于是这条提示在手机上**整条消失**（真机实测 2026-10-18）。
 * 现在两边都画：原生 `TruncatedNoticeRow` 与本函数。 */
function addMaxTokensNotice() {
  dropEmpty();
  var d = document.createElement("div");
  d.className = "maxtok";
  var hd = document.createElement("div");
  hd.className = "hd";
  hd.textContent = "已达到输出 token 上限";
  d.appendChild(hd);
  var m = document.createElement("div");
  m.className = "msg";
  m.textContent = "回答被截断，已有输出保留在对话中。发送“继续”可让模型接着输出。";
  d.appendChild(m);
  sink().appendChild(d);
  toBottom(true);
  return d;
}

function addTurnFailure(f) {
  if (!f) return null;
  dropEmpty();
  var d = document.createElement("div");
  d.className = "fail";
  var hd = document.createElement("div");
  hd.className = "hd";
  hd.textContent = f.stopped ? "任务已停止" : "本轮运行失败";
  d.appendChild(hd);
  /* code 可能是空串（免费额度限流就是）—— 空也要占位，
     否则行高忽高忽低，看起来像渲染出错。 */
  if (f.code != null) {
    var c = document.createElement("span");
    c.className = "code";
    c.textContent = f.code;
    hd.appendChild(c);
  }
  if (f.message) {
    var m = document.createElement("div");
    m.className = "msg";
    m.textContent = f.message;
    d.appendChild(m);
  }
  sink().appendChild(d);
  toBottom(true);
  return d;
}

/* 系统注入的上下文该挂什么名头。
 *
 * 这些事件的 source.kind 是分类依据：runtime-context 是每轮都塞进来的运行快照，
 * time-context 是时间戳，tool-jobs / subagent-settled 是后台与子代理的通知…
 * 给它们一个人能读的名字，折叠块才不至于全叫「上下文注入」。
 * 与原生 ChatPresentation.contextLabel 覆盖同一批 kind。 */
function contextLabel(source) {
  if (!source || typeof source !== 'object') return '上下文注入';
  var k = String(source.kind || '');
  if (!k) return '上下文注入';
  var map = {
    'runtime-context': '运行上下文',
    'time-context': '时间上下文',
    'tool-jobs': '后台任务',
    'subagent-settled': '子代理完成',
    'model-selection': '模型选择',
    'skill-catalog': '技能清单',
    'compact-checkpoint': '上下文压缩点',
    'agent-instructions': '智能体指令',
    'session-reference': '会话引用'
  };
  if (map[k]) return map[k];
  if (k === 'skill-invocation') return '技能' + (source.name ? ' · ' + source.name : '');
  /* 插件注入的 kind 形如 plugin:dsh-mneme / plugin:@scope/name */
  if (k.indexOf('plugin') === 0) {
    var p = k.replace(/^plugin:?/, '');
    return '插件上下文' + (p ? ' · ' + p : '');
  }
  return k;
}

