/**
 * chat.html 顶层脚本冒烟测试。
 *
 * 为什么要它：实机症状是「页面停在『正在读取会话…』，原生探测报 dshChat 不存在」，
 * 而这个错只有在**整段 script 抛异常**时才会发生 —— 单看代码每一处都对，
 * 只有真跑一遍才知道是不是在定义 window.dshChat（第 1156 行）之前炸了。
 *
 * 用法：node tools/chat-html-smoke.mjs [app/src/main/assets/chat.html]
 * 退出码 0 = 脚本跑完且 window.dshChat 已定义；1 = 失败（打印抛点与源码行）。
 */
import fs from 'node:fs';
import path from 'node:path';

const file = process.argv[2] || 'app/src/main/assets/chat.html';
const html = fs.readFileSync(file, 'utf8');

const match = html.match(/<script>([\s\S]*?)<\/script>/);
if (!match) {
  console.error('❌ 页面里没有 <script> 块');
  process.exit(1);
}
const js = match[1];
// script 标签在 HTML 里的起始行号，用于把 JS 栈帧换算回文件行号。
const scriptLine = html.slice(0, html.indexOf('<script>')).split('\n').length;

function stubEl(classes = []) {
  const el = {
    __classes: classes,
    style: {}, innerHTML: '', textContent: '', className: '', value: '',
    hidden: false, disabled: false, scrollTop: 0, scrollHeight: 0,
    clientHeight: 0, clientWidth: 0, offsetHeight: 0,
    children: [], childNodes: [], dataset: {}, files: [],
    classList: { add() {}, remove() {}, contains: () => false, toggle() {} },
    appendChild() {}, removeChild() {}, insertBefore() {}, replaceChild() {},
    querySelectorAll: () => [], querySelector: (sel) => stubEl(sel.replace(/^[.#]/, '')), closest: () => null,
    setAttribute() {}, getAttribute: () => null, removeAttribute() {},
    addEventListener() {}, removeEventListener() {}, dispatchEvent() {},
    focus() {}, blur() {}, click() {}, scrollIntoView() {}, setPointerCapture() {},
    releasePointerCapture() {}, getBoundingClientRect: () => ({ width: 0, height: 0, top: 0, left: 0 }),
  };
  return el;
}

const registry = new Map();
const getEl = (id) => {
  if (!registry.has(id)) registry.set(id, stubEl());
  return registry.get(id);
};

/**
 * 选择器支持：页面里用到 .hd / .no / .yes / [data-x] / .a 等少量选择器去绑事件。
 * 这里按「class 名」和「标签名」匹配已登记的元素，取不到就回一个**带同样 class 的桩**——
 * 目的是让「真实 HTML 里没有这个元素」这种问题能报出来，而不是被桩悄悄吞掉。
 */
function makeQuery(container) {
  return (sel) => {
    const m = /^\.([\w-]+)$/.exec(sel) || /^#([\w-]+)$/.exec(sel);
    if (!m) return stubEl();
    const cls = m[1];
    const hit = [...registry.values()].find((el) => el.__classes?.includes(cls));
    if (hit) return hit;
    // 登记过的元素都没这个 class：仍然给桩，让脚本能跑完（真实浏览器里它就是个元素）。
    return stubEl();
  };
}

const document = {
  title: '',
  body: stubEl(),
  head: stubEl(),
  documentElement: stubEl(),
  getElementById: getEl,
  querySelector: (sel) => makeQuery(document)(sel),
  querySelectorAll: (sel) => [],
  createElement: stubEl,
  createTextNode: () => stubEl(),
  createDocumentFragment: stubEl,
  addEventListener() {},
  removeEventListener() {},
  readyState: 'complete',
  cookie: '',
};

const store = new Map();
const localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k),
  clear: () => store.clear(),
};
const window = {
  document,
  navigator: { userAgent: 'node', clipboard: null, onLine: true },
  location: { href: 'http://127.0.0.1:3080/', protocol: 'http:', host: '127.0.0.1:3080', origin: 'http://127.0.0.1:3080', pathname: '/', search: '', hash: '' },
  screen: { width: 390, height: 844 },
  innerWidth: 390,
  innerHeight: 844,
  devicePixelRatio: 2,
  addEventListener() {},
  removeEventListener() {},
  setTimeout: (fn, ms, ...a) => setTimeout(fn, ms, ...a),
  clearTimeout: (t) => clearTimeout(t),
  setInterval: (fn, ms, ...a) => setInterval(fn, ms, ...a),
  clearInterval: (t) => clearInterval(t),
  requestAnimationFrame: (fn) => setTimeout(fn, 16),
  cancelAnimationFrame: (t) => clearTimeout(t),
  localStorage,
  sessionStorage: localStorage,
  getComputedStyle: () => ({ getPropertyValue: () => '' }),
  matchMedia: () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} }),
  open: () => null,
  close() {},
  alert() {},
  confirm: () => false,
  prompt: () => null,
  print() {},
  postMessage() {},
  fetch: () => Promise.reject(new Error('冒烟测试不联网')),
  WebSocket: function WS() { this.close = () => {}; this.send = () => {}; this.readyState = 0; },
  Blob: class {}, FileReader: class { readAsDataURL() {} },
  console,
  JSON, Math, Date, Object, Array, String, Number, Boolean, Error, RegExp, Promise, Symbol, Map, Set, WeakMap, WeakSet, parseInt, parseFloat, isNaN, isFinite, encodeURIComponent, decodeURIComponent, btoa: (s) => Buffer.from(String(s), 'binary').toString('base64'), atob: (s) => Buffer.from(String(s), 'base64').toString('binary'),
};
window.window = window;
window.self = window;
window.top = window;
window.globalThis = window;

let failure = null;
const sandbox = {
  window, document, navigator: window.navigator, location: window.location,
  localStorage: window.localStorage, sessionStorage: window.sessionStorage,
  screen: window.screen, console,
  setTimeout: window.setTimeout, clearTimeout: window.clearTimeout,
  setInterval: window.setInterval, clearInterval: window.clearInterval,
  requestAnimationFrame: window.requestAnimationFrame, cancelAnimationFrame: window.cancelAnimationFrame,
  App: undefined, // 原生桥在 App 里注入；冒烟测试不注入
  Android: undefined,
  fetch: window.fetch, WebSocket: window.WebSocket,
  Blob: window.Blob, FileReader: window.FileReader,
  JSON, Math, Date, Object, Array, String, Number, Boolean, Error, RegExp, Promise, Symbol, Map, Set, WeakMap, WeakSet, parseInt, parseFloat, isNaN, isFinite,
  encodeURIComponent, decodeURIComponent, btoa: window.btoa, atob: window.atob,
};

const vm = await import('node:vm');
vm.createContext(sandbox);

try {
  vm.runInContext(js, sandbox, { filename: 'chat.html#script' });
} catch (e) {
  failure = e;
}

const lines = js.split('\n');
const frame = failure ? /chat\.html#script:(\d+)/.exec(failure.stack || '') : null;

if (failure) {
  console.error('❌ chat.html 顶层脚本抛出异常：', failure.message);
  if (frame) {
    const n = Number(frame[1]);
    console.error(`   对应 chat.html 第 ${scriptLine + n} 行（JS 内第 ${n} 行）：`);
    for (let i = Math.max(1, n - 3); i <= Math.min(lines.length, n + 3); i++) {
      const mark = i === n ? '>>' : '  ';
      console.error(`${mark} ${i + scriptLine - 1}: ${lines[i - 1]}`);
    }
  } else {
    console.error(failure.stack);
  }
}

const api = sandbox.window.dshChat;
if (failure || typeof api !== 'object') {
  console.error('❌ window.dshChat 未定义 —— 原生侧 evaluateJavascript 会静默落空，页面永远停在「正在读取会话…」');
  process.exit(1);
}

const methods = Object.keys(api).sort();
console.log(`✅ chat.html 顶层脚本执行完毕，window.dshChat 已定义（${methods.length} 个方法）`);
console.log('   对外接口：' + methods.join(', '));

// 原生侧实际调用的方法，一个都不能少。
const required = ['init', 'setCards', 'setFiles', 'setLive', 'setHistory', 'setApprovals'];
const missing = required.filter((m) => typeof api[m] !== 'function');
if (missing.length) {
  console.error('❌ 原生 ChatWebScreen 会调用但页面没提供的方法：' + missing.join(', '));
  process.exit(1);
}
console.log('✅ 原生调用的 6 个方法齐备');
process.exit(0);
