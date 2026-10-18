/* cards.js —— dsh-ui 卡片渲染（自 chat.html 逐字搬出）：规格由原生 parseGenUi 推来，这里只负责画。
 * 依赖 core/render；贡献：fstr/preBlock/kvRow/badgeHtml/cellHtml/renderCard/setCards。
 */

/* 待办现在是 <details>，展开收起由浏览器原生处理，不需要 JS 绑定。


/* ═══════════ 2. dsh-ui 卡片 ═══════════
   规格由原生 parseGenUi 解析后推来；这里只负责画。
   刻意不自己在页面里认围栏 —— 原生那套判据（```dsh-ui 与 ```json dsh-ui 都认、
   必须闭合、只看行首）每条都对应一个真实踩过的坑，两边写两遍迟早对不上。 */
/* 取字符串字段，容忍多种写法。 */
function fstr(v, keys) {
  for (var i = 0; i < keys.length; i++) {
    var x = v[keys[i]];
    if (typeof x === 'string' && x !== '') return x;
    if (typeof x === 'number' || typeof x === 'boolean') return String(x);
  }
  return '';
}
function preBlock(text) {
  return '<pre><button class="copy" onclick="cpy(this,' + JSON.stringify(String(text)) +
    ')">复制</button><code>' + esc(String(text)) + '</code></pre>';
}
function kvRow(k, val) {
  return '<div class="kv"><span class="k">' + esc(k) + '</span><span class="v">' +
    esc(val) + '</span></div>';
}

/*
 * 渲染一份 dsh-ui spec。
 *
 * 字段名一律以**原生 GenUiCard 的实现**为准，不再按猜测写：
 *   keyvalue → pairs:[{key,value}]      table → columns:[str] + rows:[[...]]
 *   callout  → tone/title/content       steps → steps:[{title,desc}]
 *   stat     → label/value/delta/unit   progress → value(0-100)/label/target
 *   diff     → diffs:[{path,oldText,newText}]
 * 早先 table 按对象数组读、callout 读 text/message（真实字段是 content），
 * 两个都取不到内容；keyvalue 压根没处理，整坨 JSON 甩在脸上。
 */
/* 状态徽标。真实 spec 用的字段是 **variant**（不是 tone），
   取值 success / error / warning 之类；带个对错号，和电脑端观感一致。 */
function badgeHtml(v) {
  var variant = v.variant || v.tone || '';
  var ok = variant === 'success' || variant === 'ok';
  var bad = variant === 'error' || variant === 'danger' || variant === 'warning';
  return '<span class="badge' + (ok ? ' ok' : (bad ? ' warn' : '')) + '">' +
    (ok ? '✓ ' : (bad ? '✗ ' : '')) +
    esc(fstr(v, ['text', 'label', 'value'])) + '</span>';
}

/* 表格单元格：里面可以是纯字符串，也可以嵌一个组件对象（真实卡片的状态列就是 badge）。
   直接 esc(object) 会得到 "[object Object]"，所以按组件渲染。 */
function cellHtml(c) {
  if (c === null || c === undefined) return '';
  if (typeof c !== 'object') return esc(c);
  if (c.type === 'badge') return badgeHtml(c);
  var text = fstr(c, ['text', 'content', 'label']);
  return esc(text || JSON.stringify(c));
}

function renderCard(spec) {
  var box = document.createElement('div');
  box.className = 'card';
  var html = '';
  if (spec.title) html += '<div class="t">' + esc(spec.title) + '</div>';

  /* 单个节点的渲染逻辑单独成函数：表格里也能嵌组件（状态列的 badge、
     甚至整行就是一个 callout 对象），那些地方要走同一套分派。 */
  function nodeInner(node) {
    /* 节点有两种形状，必须都认：
       ① 原生包装过的 {type, value:{...}}（历史路径）；
       ② **原始 spec** {type, ...字段} —— 字段与 type 同级，真实卡片就是这个形状。
       早先只认第 ① 种，遇到第 ② 种时 v 恒为空对象，
       于是 callout / table 全渲染成空框（用户截图里那三个空框就是这么来的）。 */
    var v = (node.value && typeof node.value === 'object') ? node.value : node;
    var t = node.type || '';
    var inner = '';

    if (t === 'text' || t === 'markdown') {
      inner = '<div class="md">' + md(fstr(v, ['content', 'text'])) + '</div>';

    } else if (t === 'keyvalue') {
      inner = (v.pairs || []).map(function (p) {
        return kvRow(p && p.key !== undefined ? p.key : '',
                     p && p.value !== undefined ? p.value : '');
      }).join('');

    } else if (t === 'table') {
      /* rows 是**数组的数组**，与 columns 一一对应 —— 不是对象数组。 */
      var cols = v.columns || [];
      var rws = v.rows || [];
      /* 表格外面套滚动容器：窄屏放不下的宽表由 .tw 横向滚，table 保持正常布局。 */
      inner = '<div class="tw"><table><thead><tr>' + cols.map(function (c) {
        return '<th>' + esc(c) + '</th>';
      }).join('') + '</tr></thead><tbody>' + rws.map(function (r) {
        var span = Math.max(1, cols.length);
        /* rows 里可能混着一个非数组的组件对象 —— 真实卡片就把结尾的 callout
           塞在 rows 尾部。这种按**整行一个组件**渲染，硬塞进单元格会很难看。 */
        if (!Array.isArray(r)) {
          return '<tr><td colspan="' + span + '">' +
            (r && typeof r === 'object' && r.type ? nodeInner(r) : cellHtml(r)) +
            '</td></tr>';
        }
        return '<tr>' + r.map(function (c) {
          return '<td>' + cellHtml(c) + '</td>';
        }).join('') + '</tr>';
      }).join('') + '</tbody></table></div>';

    } else if (t === 'list') {
      inner = '<ul class="gs">' + (v.items || []).map(function (r) {
        if (typeof r === 'string') return '<li>' + esc(r) + '</li>';
        return '<li><b>' + esc(r.title || r.label || '') + '</b>' +
          (r.desc || r.description || r.text
            ? ' ' + esc(r.desc || r.description || r.text) : '') + '</li>';
      }).join('') + '</ul>';

    } else if (t === 'stat') {
      inner = '<div class="kv"><span class="k">' + esc(fstr(v, ['label', 'name'])) +
        '</span><span class="v"><b>' + esc(fstr(v, ['value', 'delta'])) +
        '</b> ' + esc(fstr(v, ['unit'])) + '</span></div>';

    } else if (t === 'badge') {
      inner = badgeHtml(v);

    } else if (t === 'progress') {
      var pv = Number(v.value);
      if (!isFinite(pv)) pv = 0;
      var tgt = fstr(v, ['target']);
      inner = '<div class="pg"><div class="pgbar"><i style="width:' +
        Math.max(0, Math.min(100, pv)) + '%"></i></div><span>' +
        esc(fstr(v, ['label'])) + ' ' + pv + '%' +
        (tgt ? ' / ' + esc(tgt) : '') + '</span></div>';

    } else if (t === 'steps') {
      inner = '<ol class="gs steps">' + (v.steps || []).map(function (s) {
        return '<li><b>' + esc(s.title || '') + '</b>' +
          (s.desc ? ' ' + esc(s.desc) : '') + '</li>';
      }).join('') + '</ol>';

    } else if (t === 'timeline') {
      inner = '<ul class="gs tl">' + (v.items || []).map(function (s) {
        return '<li>' + (s.time ? '<span class="tm">' + esc(s.time) + '</span> ' : '') +
          '<b>' + esc(s.title || '') + '</b>' +
          (s.desc ? ' ' + esc(s.desc) : '') + '</li>';
      }).join('') + '</ul>';

    } else if (t === 'code' || t === 'json') {
      var body = (t === 'json' && v.value !== undefined)
        ? JSON.stringify(v.value, null, 2)
        : fstr(v, ['code', 'text', 'content']);
      inner = preBlock(body);

    } else if (t === 'diff') {
      inner = (v.diffs || []).map(function (d) {
        var s = d.path ? '<div class="tf">' + esc(d.path) + '</div>' : '';
        if (d.oldText) s += '<div class="dl del">- ' + esc(d.oldText) + '</div>';
        if (d.newText) s += '<div class="dl add">+ ' + esc(d.newText) + '</div>';
        return s;
      }).join('');

    } else if (t === 'callout' || t === 'alert') {
      /* 正文是 **content** —— 早先读 text/message/title，全是空。 */
      var tone = v.tone === 'warning' ? ' warn'
        : (v.tone === 'error' ? ' err' : (v.tone === 'success' ? ' ok' : ''));
      inner = '<div class="co' + tone + '">' +
        (v.title ? '<div class="cot">' + esc(v.title) + '</div>' : '') +
        '<div>' + esc(fstr(v, ['content', 'text'])) + '</div></div>';

    } else if (t === 'divider') {
      inner = '<hr class="gd">';

    } else if (t === 'spacer') {
      inner = '<div style="height:8px"></div>';

    } else if (t === 'chart') {
      /* 页面不引图表库；把数据列成表，信息不丢，也不假装画得出来。 */
      var data = v.data || [];
      inner = data.length
        ? '<div class="tw"><table><thead><tr><th>项</th><th>值</th></tr></thead><tbody>' +
          data.map(function (d) {
            return '<tr><td>' + esc(d.label) + '</td><td>' + esc(d.value) + '</td></tr>';
          }).join('') + '</tbody></table></div>'
        : '<div style="color:var(--muted)">（图表数据为空）</div>';

    } else {
      /* 没认出来的类型：按**可读的键值对**列出，不再甩一坨原始 JSON。
         兜底原则是「难看但看得懂」，原始 JSON 连看懂都做不到。 */
      var lines = [];
      Object.keys(v).forEach(function (k) {
        var x = v[k];
        if (x === null || x === undefined || x === '') return;
        lines.push(kvRow(k, typeof x === 'object' ? JSON.stringify(x) : String(x)));
      });
      inner = '<div class="unk">' + esc(t) + '</div>' +
        (lines.length ? lines.join('') : '<div class="kv">（无内容）</div>');
    }
    return inner;
  }

  (spec.items || []).forEach(function (node) {
    html += '<div class="c">' + nodeInner(node) + '</div>';
  });

  if (!html) html = '<div style="color:var(--muted)">空卡片</div>';
  box.innerHTML = html;
  return box;
}
function setCards(specs) {
  var host = $('cardHost');
  if (!host) {
    host = document.createElement('div');
    host.id = 'cardHost';
    log.appendChild(host);
  }
  host.innerHTML = '';
  (specs || []).forEach(function (spec) {
    host.appendChild(renderCard(spec));
  });
  toBottom();
}


