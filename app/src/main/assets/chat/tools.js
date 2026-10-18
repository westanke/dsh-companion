/* tools.js —— 工具调用组渲染（自 chat.html 逐字搬出）：摘要行 / 单条详情（read·bash·edit·write 专用块）/ 组折叠。
 * 依赖 core/render；贡献：toolArgSummary/toolEntry/refillToolGroup/toolActions/addToolGroup。
 */



/* ── 工具调用：整组折叠 ──
 *
 * 早先是每次调用、每个结果各占一个折叠块 —— 一轮对话下来就是几百个块，
 * 翻历史像在翻日志，用户的原话是「太乱」。
 * 现在**连续的工具事件合成一组**：组头只报次数与工具名摘要，
 * 组内每条只占一行（工具名 + 参数预览 + 成败），要点开才看完整参数与结果。
 */

/* 参数预览：挑一个最有信息量的字段，压成一行。
   把整坨 JSON 塞进标题会把行撑爆，也读不出重点。 */
function toolArgSummary(args) {
  var raw = String(args == null ? '' : args);
  if (!raw.trim()) return '';
  var s = raw;
  try {
    var o = JSON.parse(raw);
    if (o && typeof o === 'object') {
      var keys = ['command', 'file_path', 'path', 'pattern', 'query', 'url', 'prompt', 'text', 'code'];
      for (var i = 0; i < keys.length; i++) {
        if (typeof o[keys[i]] === 'string' && o[keys[i]].trim()) { s = o[keys[i]]; break; }
      }
    }
  } catch (e) {}
  s = s.replace(/\s+/g, ' ').trim();
  return s.length > 72 ? s.slice(0, 72) + '…' : s;
}

/* 组内一条：默认只占一行，点开看参数与结果。 */
function toolEntry(e) {
  var d = document.createElement('details');
  d.className = 'ti';
  var sum = document.createElement('summary');
  var st = e.isError ? '<span class="tst err">失败</span>'
    : (e.hasResult ? '<span class="tst ok">✓</span>' : '');
  sum.innerHTML = '<span class="caret">▶</span>' +
    '<span class="tn">' + esc(e.name || '工具') + '</span>' +
    '<span class="ta1">' + esc(e.arg || '') + '</span>' + st;
  d.appendChild(sum);
  var body = document.createElement('div');
  body.className = 'tbody';
  /* 专用块（官方 Web 对标）：read / bash / edit / write 按工具名定制渲染，
     其余工具与解析失败一律走原样 —— 专用块是增强不是依赖。 */
  var parsed = null;
  try { parsed = e.argsFull ? JSON.parse(e.argsFull) : null; } catch (err) { parsed = null; }
  if (typeof parsed !== "object" || !parsed) parsed = null;
  var isRead = (e.name === 'read' || e.name === 'read_image') && e.result;
  var isBash = e.name === 'bash' && parsed && typeof parsed.command === 'string';
  var isEdit = e.name === 'edit' && parsed && typeof parsed.new_string === 'string';
  var isWrite = e.name === 'write' && parsed && typeof parsed.content === 'string';
  if (isBash) {
    var c = document.createElement('div');
    c.className = 'tcmd';
    c.textContent = '$ ' + parsed.command;
    body.appendChild(c);
    if (e.hasResult) {
      var o = document.createElement('div');
      o.className = 'tres ttres' + (e.isError ? ' err' : '');
      o.textContent = String(e.result || '').replace(/\u001B\[[0-9;]*[A-Za-z]/g, '').replace(/\u001B\][^\u0007]*(\u0007|\u001B\\)/g, '');
      body.appendChild(o);
    }
  } else if (isRead) {
    var p = document.createElement('div');
    p.className = 'tpath';
    p.textContent = (parsed && parsed.file_path) || '';
    var b = String(e.result);
    var ct = b.indexOf('<content>');
    if (ct >= 0) b = b.slice(ct + 9, b.lastIndexOf('</content>')).replace(/^\n+/, '');
    var r = document.createElement('div');
    r.className = 'tres tread';
    r.textContent = b;
    body.appendChild(p); body.appendChild(r);
  } else if (isEdit || isWrite) {
    var fp = document.createElement('div');
    fp.className = 'tpath';
    fp.textContent = (parsed.file_path || '') + (isWrite ? ' · 新建' : '');
    body.appendChild(fp);
    function dl(txt, cls) {
      var n = document.createElement('div');
      n.className = 'tres tread';
      n.innerHTML = String(txt).split('\n').map(function (l) {
        return '<div class="dl ' + cls + '">' + (cls === 'del' ? '- ' : '+ ') + esc(l) + '</div>';
      }).join('');
      return n;
    }
    if (isEdit && parsed.old_string) body.appendChild(dl(parsed.old_string, 'del'));
    var nt = isEdit ? parsed.new_string : parsed.content;
    body.appendChild(dl(nt, 'add'));
  } else {
    if (e.argsFull && e.argsFull !== e.arg) {
      var a = document.createElement('pre');
      a.className = 'targs';
      a.textContent = e.argsFull;
      body.appendChild(a);
    }
    if (e.hasResult) {
      var r2 = document.createElement('div');
      r2.className = 'tres' + (e.isError ? ' err' : '');
      r2.textContent = e.result || '';
      body.appendChild(r2);
    }
  }
  d.appendChild(body);
  return d;
}

/* 就地重填一个已存在的工具组：只换内部列表与汇总行，不换 details 节点本身。
   流式期间工具条目会一条条增加，重建整个节点会让这一块闪。 */
function refillToolGroup(el, entries) {
  var names = toolGroupSummary(entries);
  var head = el.querySelector('summary');
  if (head) {
    var tn = head.querySelector('.tnames');
    if (tn) tn.textContent = names.slice(0, 90);
  }
  var list = el.querySelector('.tlist');
  if (!list) return;
  list.innerHTML = '';
  entries.forEach(function (e) { list.appendChild(toolEntry(e)); });
}

/* 一组工具调用。entries: {name, arg, argsFull, result, hasResult, isError} */
/* ── 工具动作的「人话」措辞 ──
 *
 * 网页版报的是「运行了代码，已写入文件，执行了命令」这种动作摘要，不是工具名。
 * 判据来自**真实数据**：run_code 的 arguments 里能看到它调了哪些内层工具。
 * 全量统计那份会话的 1129 次 run_code ——
 *   tools.bash 748、tools.edit 158、tools.read 116、tools.write 53；
 *   其中 bash+write 这一组合 42 例，对应的正是「运行了代码，已写入文件，执行了命令」。
 * 所以这不是猜的编法，是照着一字不差还原。
 */
/* 顺序按官方措辞对齐：edit 在 write 之前 —— 官方那句是
   「运行了代码，修改了文件，已写入文件等」。 */
var INNER_ACTIONS = [
  ['tools.edit',  '修改了文件'],
  ['tools.write', '已写入文件'],
  ['tools.read',  '读取了文件'],
  ['tools.bash',  '执行了命令'],
  ['tools.grep',  '搜索了内容'],
  ['tools.glob',  '搜索了文件'],
  ['tools.mcp',   '调用了 MCP 工具'],
];

/* 单个工具调用 → 动作短语数组。args 是原始 arguments 字符串（拿不到就只按工具名给）。 */
function toolActions(name, args) {
  var n = String(name == null ? '' : name);
  var raw = String(args == null ? '' : args);
  var out = [];
  if (n === 'run_code') {
    out.push('运行了代码');
    INNER_ACTIONS.forEach(function (pair) {
      if (raw.indexOf(pair[0]) >= 0 && out.indexOf(pair[1]) < 0) out.push(pair[1]);
    });
    return out;
  }
  var byName = {
    bash: '执行了命令', read: '读取了文件', write: '已写入文件', edit: '修改了文件',
    grep: '搜索了内容', glob: '搜索了文件', ls: '列了目录', task: '跑了子任务'
  };
  if (byName[n]) return [byName[n]];
  if (n.indexOf('mcp__') === 0) return ['调用了 ' + n.replace(/^mcp__/, '').replace(/__/g, ' · ')];
  return [n || '工具'];
}

/* 一整组工具 → 一句话动作摘要：按首次出现顺序去重，超过 4 个收尾加「等」。 */
function toolGroupSummary(entries) {
  var acts = [];
  (entries || []).forEach(function (e) {
    toolActions(e.name, e.argsFull).forEach(function (a) {
      if (acts.indexOf(a) < 0) acts.push(a);
    });
  });
  if (!acts.length) return '';
  if (acts.length > 4) return acts.slice(0, 4).join('，') + '等';
  return acts.join('，');
}

function addToolGroup(entries) {
  if (!entries || !entries.length) return null;
  dropEmpty();
  var names = toolGroupSummary(entries);
  var d = document.createElement('details');
  d.className = 'act tools';
  var head = document.createElement('summary');
  /* 表头只报动作，不再报「工具调用 N 次」—— 用户：「显示了运行了代码、修改了文件，
     就没必要显示工具调用 x 次了」。次数是给日志看的，上面那句才是给人看的。 */
  head.innerHTML = '<span class="caret">▶</span>' +
    '<span class="tnames">' + esc(names.slice(0, 90)) + '</span>';
  d.appendChild(head);
  var list = document.createElement('div');
  list.className = 'tlist';
  entries.forEach(function (e) { list.appendChild(toolEntry(e)); });
  d.appendChild(list);
  sink().appendChild(d);
  toBottom();
  return d;
}

