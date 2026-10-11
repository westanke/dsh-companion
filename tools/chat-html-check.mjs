#!/usr/bin/env node
/* chat.html 静态体检 —— 每次改完页面都跑一遍。
 *
 * 来历：v2.2.7 撤掉 #diag 元素时漏删了它的 onclick 绑定。那行是**顶层语句**，
 * 加载时 $("diag") 返回 null，null.onclick 抛 TypeError，整个脚本当场中断 ——
 * window.dshChat 都没定义，页面全白。而 file:// 页面把详情屏蔽成
 * 「Script error. @0」，真机上几乎无从排查。
 * 所以把「引用的 id 是否都存在」变成一条会自动跑的检查。
 */
import fs from 'fs';

const html = fs.readFileSync('app/src/main/assets/chat.html', 'utf8');
const js = html.slice(html.indexOf('<script>') + 8, html.indexOf('</script>'));
const markup = html.slice(0, html.indexOf('<script>'));

let bad = 0;
function ok(name, pass, detail) {
  if (!pass) bad++;
  console.log((pass ? '  PASS  ' : '  FAIL  ') + name + (detail ? '  → ' + detail : ''));
}

/* 1) 去掉注释，避免注释里出现的示例被当成真实引用 */
const code = js
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/^\s*\/\/.*$/gm, '');

/* 2) 收集 id：HTML 里定义的 + 运行时 createElement 后赋 .id 的 */
const defined = new Set();
for (const m of markup.matchAll(/\bid="([^"]+)"/g)) defined.add(m[1]);
for (const m of code.matchAll(/\.id\s*=\s*['\"]([A-Za-z0-9_-]+)['\"]/g)) defined.add(m[1]);

/* 3) 收集引用 */
const used = new Set();
for (const m of code.matchAll(/\$\(['\"]([A-Za-z0-9_-]+)['\"]\)/g)) used.add(m[1]);
for (const m of code.matchAll(/bind\(['\"]([A-Za-z0-9_-]+)['\"]\)/g)) used.add(m[1]);
for (const m of code.matchAll(/getElementById\(['\"]([A-Za-z0-9_-]+)['\"]\)/g)) used.add(m[1]);

const missing = [...used].filter(id => !defined.has(id));
ok('引用的 id 都存在', missing.length === 0, missing.join(', '));

/* 4) 顶层绑定必须走 bind()：拿不到元素时用游离节点接住，不让脚本被带死 */
const rawTopBind = [...code.matchAll(/^\$\(['\"][A-Za-z0-9_-]+['\"]\)\.(onclick|oninput|onchange|addEventListener)/gm)];
ok('顶层绑定都用 bind()', rawTopBind.length === 0, rawTopBind.length + ' 处仍是 $()');

/* 5) 表格不能用 display:block
 *
 * 这个坑踩过两次：2.2.4 在 .card table 上修掉，2.2.11 才发现 .md table 里同样写着。
 * table 一旦变成 block 就脱离表格布局算法，列宽算不出来，几列叠到一起、
 * 或压成一字一列（真机截图里表头「环节」竖着排）。
 * 正确做法是滚动容器套在**外面**（.tw），table 保持 display:table。
 */
const css = html.slice(0, html.indexOf('</style>'));
const badTableRules = [...css.matchAll(/([^{}]*table[^{}]*)\{([^}]*)\}/g)]
  .filter(m => /display\s*:\s*block/.test(m[2]))
  .map(m => m[1].trim());
ok('表格没有被写成 display:block', badTableRules.length === 0, badTableRules.join(' / '));

/* 6) 关键接口齐全 */
const need = ['init', 'setLive', 'setHistory', 'setApprovals', 'setFiles', 'setCards', 'setRunning'];

const miss2 = need.filter(n => js.indexOf(n + ': function') < 0 && js.indexOf(n + ': set') < 0 && js.indexOf(n + ': renderHistory') < 0);
ok('window.dshChat 接口齐全', miss2.length === 0, miss2.join(', '));

console.log(bad === 0 ? '\nchat.html 体检通过' : '\nchat.html 体检失败 ' + bad + ' 项');
process.exit(bad === 0 ? 0 : 1);
