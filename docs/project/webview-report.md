# WebView 聊天页复盘：2.0.0 → 2.2.20

- 日期：2026-10-10（初版，写到 2.2.14）/ 2026-10-11（推进到 2.2.20）
- 终点：v2.2.20（versionCode 10045）
- 路线：会话详情页改用 WebView 承载 `assets/chat.html`，与原生版并存、设置里「聊天页实现」开关切换；
  2.2.15 起方向反过来 —— 把页面已经验证过的呈现方式一条条搬回原生 Compose
- 退路：`artifacts/` 里留着 v1.9.0（WebView 改造前的最后一版）
- 本文定位：WebView 这条线的**历史与教训**。「移植了什么 / 还差什么」在 `webview-port-todo.md`，
  「原生 vs 页面」的功能对照在 `webview-parity-audit.md` —— 三份不重复。
- 引用约定：**行号会漂，一律用函数名 / 组件名**，不按行号跳转。

> 2.2.9 之后又改了 **11 版**（2.2.10 → 2.2.20），其中最大一处是**取消双轨制**
> （见「七、2.2.10 → 2.2.14」）：此前七八版都在给双轨制打补丁，越补越乱。
> 2.2.15 之后是**反向移植轮**（见「八、2.2.15 → 2.2.20」）。
>
> 文件名：本文原名 `webview-2.2.9-report.md` —— 名字只反映起点，不反映覆盖范围，
> 2026-10-11 收口时改为 `webview-report.md`。

---

## 一、2.0.1 → 2.2.9 一共修了什么

| 版本 | 问题 | 根因 |
|---|---|---|
| 2.0.1 | 会话读不出来 | `LaunchedEffect` key 缺 `web`/`pageReady`，首帧早退后永不再跑 |
| 2.0.2 | 同上 | key 缺连接状态 + `if (activeConnection() == null) return` 静默早退 |
| 2.0.2 | 附件链四个缺陷（见下） | `as? Activity` 恒 null、轮询无互斥、清理谓词写反、靠 `indexOf` 判 JSON 字段 |
| 2.1.1 | 同上 | `payload.toRequestBody(null)` 给 GET 发了零长度 body |
| 2.1.2 | 全是工具调用、没有正文 | `pickText` 只认 `typeof d.message === 'string'`，真实是对象 |
| 2.2.4 | 卡片表格列重叠 | `.card table{display:block}` 破坏表格布局算法 |
| 2.2.5 | 进会话看见一堆自己没发的消息 | `user/message` 里混着系统注入，未判 `source.kind` |
| 2.2.6 | 流式期间思考块越堆越多 | `setLive` 清了空壳：渲染函数都往 `#log` 追加 |
| 2.2.7 | 表格列被压窄 | `width:100%` 是硬约束，与 `nowrap` 冲突 |
| 2.2.8 | 正文与工具块不停闪 | `setLive` 每帧全量重建，未复用节点 |
| 2.2.9 | **白屏** | 撤 `#diag` 元素漏删它的顶层 `onclick` 绑定 → 脚本中断 |

还有两项不是 bug 而是需求：消息左右分栏、流式思考改成一行。

> **2.0.2 那四个附件链缺陷**的取证来自一份独立静态审计（`docs/webview-audit.md`，2026-10-10，
> 只做静态核对、没跑真机）。2026-10-11 收口时那份审计被压缩成指针，四条结论并入本文保存：
> ① `pickFile` / `captureImage` 拿的是 `context.applicationContext as? Activity`，而它永远是
> Application 单例 → 转型恒为 null，选择器**连 Intent 都不发**（修法：另存 Composable 传入的
> `launchContext`）；② 每点一次 `+` 起一条独立轮询链，而原生 `pendingUri` 只有一个槽位 →
> 连点两次必有一条白等 50 秒（修法：模块级 `attWait` 哨兵，上限收到 8 秒）；③ 发送成功后
> 本意是「只留还没发出去的」（`busy === true`），代码写成 `!a.busy` → 刚发成功的图会再发一遍、
> 还在上传的附件反被清掉；④ 用 `raw.indexOf('"ok":false,"error"')` 判结果，依赖 JSON 字段顺序，
> 而 `JSONObject` 不保证顺序 → 读文件失败被当成「还没选」（修法：`JSON.parse` 后看 `obj.ok`）。

---

## 二、真正值钱的五条教训

### 1. 观测手段不能与被观测对象绑定

前面十版我把诊断写在**页面里**（黄条、标题烙印、`window.onerror`）。一旦页面真的坏了，
页面自己就没有任何东西能报告了 —— 最需要它说话的时候它恰好是瞎的。

换成 Compose 原生面板直接盖在 WebView 上之后，答案才开始自己冒出来。

**推论**：查一类东西之前，先确认诊断通道本身不依赖那个可能坏掉的东西。

### 2. `typeof null === 'object'`，别拿 typeof 判存在

我用 `typeof window.onerror === 'object'` 当「onerror 已赋值」的证据，连用好幾版。
浏览器里 `window.onerror` 默认就是 `null`，这一栏永远是 `object`，**从头到尾没证明过任何事**。

### 3. `file://` 页面里 `window.onerror` 是瞎的

Chromium 把每个本地文件当**独立不透明源**，onerror 收到的是脱敏后的
`Script error. @0` —— 行号 0、无详情。要拿真实错误必须用 `try/catch`。

2.2.9 的白屏就是因此极难定位；最后靠**静态引用审计**抓到（见第 5 条）。

### 4. 增量渲染不要「清空容器再重建」

`setLive` 每帧 `host.innerHTML = ''` 再全量重建 → 正文与工具块持续闪烁。
改成按稳定 key 就地更新后消失。

**判据**：同一份数据的两次连续渲染，DOM 节点身份必须相同。

### 5. 删 DOM 元素必须全局搜它的 id

撤 `#diag` 时漏删 `$("diag").onclick = ...`。那是**顶层语句**，加载时 `null.onclick` 抛错，
整个脚本当场中断，`window.dshChat` 都没定义 → 整页白屏。

现在顶层绑定一律走 `bind(id)`：拿不到元素代入游离节点接住赋值，
且 `tools/chat-html-check.mjs` 会检查「引用的 id 是否都存在」。

---

## 三、方法上的两次顺序错误

这轮我反复犯同一个错：**先猜根因，再写代码，最后才去核数据**。

| 我的判断 | 量化之后 |
|---|---|
| 思考块没合并 → 写合并逻辑 | 历史里 177 个思考事件本来就是分开的，合并无效 |
| 表格 `display:block` 是根因 | 对，但改完仍有列压窄，真正的硬约束是 `width:100%` |

**正确顺序**：解压真实会话日志 → 跑解析函数 → 拿到「745 条全空」这样的数字 → 再动手。
「真机来回十一版，抵不过一次离线数据核验。」

---

## 四、可复用的检查工具

| 工具 | 作用 |
|---|---|
| `tools/chat-html-check.mjs` | 页面体检：引用的 id 是否都存在、顶层绑定是否都用 `bind()`、接口是否齐全 |
| `tools/chat-html-smoke.mjs` | 在 DOM 桩里执行页面脚本，确认 `window.dshChat` 能定义出来 |
| `tools/emit-config.mjs` | 配置导入线格式 |

改完 `chat.html` **先跑体检再出包**。2.2.9 之前没有这道闸，白屏才流到真机。

---

## 五、仍需真机确认 / 已知取舍

- 流式期间的节点复用（2.2.8）与一行思考条（2.2.7）已随 2.2.9 一起验证通过（历史结论）。
- 卡片内的 `chart` **在页面侧**只把数据列成「项 / 值」表，不引图表库 —— 手机端加图表库不划算。
  原生侧是另一条线：`GenUiCard.kt` 的 `GenUiChart()` 手绘 bars / line / donut。
- 未识别组件类型按**可读键值对**列出，不甩原始 JSON（页面 `renderCard()` 的兜底分支）。
- 「会话文件列表只显示路径，点开提示『已在电脑上』」说的是**页面**；原生侧有完整详情，
  2.2.20 起底部区块与标题栏弹层还统一到同一个来源 `sessionFileList()`（`ui/ChatScreen.kt`）。
- 页面始终**没有**权限模式 / 重命名 / 归档入口（`grep -c -i permission assets/chat.html` = 0），
  这三项只在原生有 —— 切到 WebView 模式就够不着，详见 `webview-parity-audit.md`。
- WebView 模式到 2.2.20 仍是**试验品**：由 `HarnessState.useWebChat` 控制、默认 `false`
  （`ChatScreen.kt` 按它分流到 `ChatWebScreen`），真机逐项验证未做。

---

## 六、发布状态

- 产物：`artifacts/DeepSeek-Harness-companion-v2.2.20.apk`，sha256 见 `artifacts/SHA256SUMS.txt`；
  `artifacts/DeepSeek-Harness-companion-v1.9.0.apk` 留作 WebView 改造前的退路。
- 签名：debug keystore（`CN=DSH Debug`）—— 发布证书已丢失，只能自用/内部分发。
  **未核实**：本轮不跑 gradle，证书与签名门禁的实际状态以构建日志为准。
- `git push` / `tag` / `release` **尚未执行**，等确认。
- 2.2.14 之后又出了 6 个包（2.2.15 → 2.2.20）。逐版改动的**事实源**是
  `app/build.gradle.kts` `defaultConfig` 里的中文注释，本文只摘与 WebView 这条线相关的部分。

---

## 七、2.2.10 → 2.2.14：取消双轨制

### 先修的单点

| 版本 | 问题 | 根因 |
|---|---|---|
| 2.2.10 | 流式期间卡片位置先闪出原始 JSON | 围栏未闭合时被当普通文本渲染，闭合后变卡片。流式期间改为切掉未闭合围栏 |
| 2.2.11 | 表格显示成原始竖线 | **`md()` 压根没有表格**（一直在用，一直没实现） |
| 2.2.12 | 表格被压成一字一列 + 漏出 `@@IN0@@` | ① `@@TB` 占位符展开顺序排在 `@@IN` 之后；② `.md table` 里写着 `display:block`——**与 2.2.4 在 `.card table` 上修的是同一个错误** |
| 2.2.12 | 运行期间看不到工具活动 | 实时通道只承载正文与思考；工具事件被插件归一化成 `chat.session.event`，连工具名都没带 |
| 2.2.12 | 「这一段说完，上一段没了」 | 推历史的 effect 拿 `liveChat?.sessionId` 当 key，又判 `liveSession == null` 早退——**恰好在最需要推历史的一瞬返回** |

### 根因：双轨制

页面里一直有**两套渲染路径**：一个独立「实时区」按 turn/step 重建、只装当前这一段；另一个是历史区。
原生 `LiveChatState` 换 step 就重置，两边叠加就成了「这一段说完，上一段没了」。

围绕它我改了七八版：渲染落点、按块就地更新、工具合并、思考合并、历史运行中也推……**每版都在打补丁，越补越乱**。

用户最后给了明确指令：

> 「发出来的就当做历史对待，别自己替换了」

### 最终做法

- 页面 `setLive` 由 118 行缩到 **20 行**，只保留「把思考喂给底部那条思考行」（思考是运行状态，不是内容）
- 正文 / 工具 / 上下文 / 用户消息**全部**由 `renderHistory` 渲染——**唯一**的渲染路径
- `#liveHost` 及相关代码整个删除
- 原生在收到 `chat.message.delta` 时也 `scheduleChatRefresh()`（250ms 去抖 + 单飞），流式文字不必等消息落盘即可显示

### 官方实现对照

读了 `@deepseek-ai/dsh-client-ui-chat/lib/client.js`，官方那套是：
内核给一个 `summary.counts[{kind}]`（read / write / edit / commands / code / webSearch …），
页面按 i18n 拼标题 —— 一句 `joinTwo`（`{first}并{second}`）、多句用 `，` 连接、超过三项加 `等`。
**不是扫参数猜的**，是结构化数据。我们的会话事件里没有这个字段，所以只能按工具名与参数近似还原。

### 教训

**同一类问题改到第三版还不好，就该怀疑架构，而不是继续打补丁。**
双轨制（一份数据两条渲染路径）本身就是缺陷源。

---

## 八、2.2.15 → 2.2.20：反向移植轮

这一轮主题不再是「修页面」，而是**把页面已经验证过的呈现方式搬回原生**（用户要求：
除「本次产生的文件」外全部移植）。逐条清单与取证在 `webview-port-todo.md`，这里只记
这条线形状上的变化：

- **2.2.15**：工具显示改官方口径的中文动作短语（`ChatPresentation.toolActionsFor()`）、
  连续工具调用合并成一行（`mergeToolRuns()`）、历史不显示思考、上下文注入默认隐藏。
- **2.2.16**：思考条挪到消息区下方 / 输入区上方（`ChatThinkBar()`）；卡片内表格列宽对齐
  页面判据 `max(96dp, 可用宽度/列数)`（`GenUiCard.kt` 的 `genUiTableColumnWidthDp()`）。
- **2.2.17 / 2.2.18**：输入区照页面摆法重做成单行药丸（`Composer()`），模型 / 权限 / 插话
  搬进顶栏 `⋯`；输入框高度改随内容自适应（44~120dp，对齐页面 `#ta` 的
  `min-height:36px / max-height:120px`）。
- **2.2.19**：设置页三个开关统一 `SwitchRow`｜开关进 `HarnessState`（`withPreferences()`）
  修「点了不变、得再点别的才跳」｜模型拆成独立 `ModelsScreen()`｜交付物改读内核
  `deliverables/presented`（`ChatHistory.deliverables()`）｜启动永远先落连接页（`connectGate`）。
- **2.2.20**：「进入应用」按钮启用条件按 `isActive` 分开（纯本地跳转不该被后台 busy 卡住）；
  会话文件清单两处统一到 `sessionFileList()`。

**这条线留下的教训**：移植不是「把页面的写法抄一遍」。页面那套是浏览器给的
（`min-width:100%`、`table-layout:auto`、flex 布局），Compose 里没有对应物 ——
必须先找出页面的**判据**（「装得下铺满、装不下横向滚」），再在 Compose 里重新实现，
而不是照抄某一版 CSS 的值。
