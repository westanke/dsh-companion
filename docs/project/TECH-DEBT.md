# 技术债务台账（TECH-DEBT）

> 台账不是豁免，**是债务**：每条都注明在哪一阶段清掉。
> **只许收紧**：条目对应的债务消失，同一提交里删掉该条目（自证非空转）。
> **上调必须带理由**：超过行数预算只许带理由在同一提交里上调，且必须在下一阶段拆回。
>
> 制度来源见 `AGENTS.md` §4.2；本文件是**手工台账，暂不进 CI**（Kotlin 侧无等价解析器）。
> 它回答的问题是「欠了多少、在往哪个方向走」，不是「谁违规了」。

## 行数预算

**当前预算：800 行/文件。**

超预算文件必须登记在下面。基线测量日：2026-10-16。

### 当前状态：**零超预算**

截至 2026-10-16，仓库内**没有任何文件超过 800 行**。最大的三个是：

| 文件 | 行数 | 说明 |
|---|---|---|
| `ui/chat/ChatSheets.kt` | 797 | 模型 / 工作区 / 新建会话三个弹层（从 `ChatScreen.kt` 拆出） |
| `ui/FilesScreen.kt` | 764 | 「文件」页（本次未动，本就未超预算） |
| `ui/chat/ChatMessageItems.kt` | 730 | 消息气泡与助手正文（从 `ChatScreen.kt` 拆出；2026-10-17 把图片查看器搬去 `ChatImageViewer.kt` 后又瘦了 24 行） |

> 新增超预算文件必须**先入本表再写码**。这是硬门槛，不是建议。

2026-10-18 登记（本轮先记账后写码）：`app/src/main/assets/chat.html` 现 **2 575 行**
（WebView 兜底页，即 DEBT-001 的主体，历史遗留超预算）。本轮工具专用块同步净增 58 行
（2 517 → 2 575：4 条 CSS 规则 + `toolEntry` 内 read/bash/edit/write 四分支）。
约定：**chat.html 不再吸收新功能**，后续增量要么拆 `assets/chat/` 多文件，要么随 DEBT-001 退役。

### 2026-10-18 拆分兑现（部分清偿）

chat.html 已按「逐字搬迁、零行为变化」拆为壳 + 6 个子文件（`assets/chat/`）：

| 文件 | 行数 | 内容 |
|---|---|---|
| `chat.html` | 355 | 壳：HTML 骨架 + `<link>`/`<script>` 引用（file:// 相对路径，`allowFileAccess` 已开，无需改 Kotlin） |
| `chat/chat.css` | 302 | 全部样式（原 `<style>` 298 行逐字搬出） |
| `chat/core.js` | 582 | 全局状态 + `$`/`api`/`md` 等基础设施（必须最先加载） |
| `chat/render.js` | 175 | 思考条 / 消息与活动块 / 失败与截断行 |
| `chat/tools.js` | 203 | 工具调用组渲染（含 read/bash/edit/write 专用块） |
| `chat/interact.js` | 803 | 审批 / 问答 / renderHistory / 连接 / 发送 / 弹层 / 斜杠命令（**超预算 3 行**，下批拆 renderHistory 时收掉） |
| `chat/cards.js` | 223 | dsh-ui 卡片渲染 |
| `chat/bridge.js` | 269 | 附件 / buildParts / `window.dshChat` 桥接导出 |

搬迁等价性已机检：6 个 JS 的内容拼接与原 `<script>` 区块 **diff 为零**（含空行对齐）；
`node --check` 单文件与拼接整体语法均过。加载顺序 core → render → tools → interact → cards → bridge
（普通脚本共享全局作用域，非 ES module —— file:// 下 `import` 被 CORS 拦）。
**本表登记范围自此扩大到 `assets/chat/*.js`**：新功能直接落对应域文件，不再回填 chat.html。

## 本轮清账记录（2026-10-16）

原台账登记了两条债，本轮全部清掉。记在这里是为了让「为什么台账空着」有据可查：

| 原债务 | 原规模 | 清账方式 | 结果 |
|---|---|---|---|
| `ui/ChatScreen.kt` | 3 882 行 | 按 `webview-port-todo.md` 的 PORT-* 分组拆成 `ui/chat/` 下 12 个文件 | **329 行** |
| `HarnessViewModel.kt` | 2 277 行 | 按域拆成「状态 / 事件纯函数 / 六个域文件」共 9 个文件 | **663 行** |
| `data/ChatPresentation.kt` | 825 行 | 按职责拆成模型 / 映射 / 交接 / 工具 / JSON 五块 | **98 行** |

拆分的判据是**职责边界**，不是行数凑整：每个新文件的 KDoc 都写清楚「这里放什么、
为什么与相邻文件分开」，以便后来者知道往哪放新代码。

### 两处必须记录的代价

1. **可见性放宽**：原先 `private` 的部分成员提升为 `internal` ——
   `HarnessViewModel` 的 `mutableState` / `update` / `withClient` / `client` / `hostStore` 等，
   `ChatScreen.kt` 里的组件（`ChatItem` / `Composer` / `SessionPane` …），
   `data/ChatPresentation.kt` 里的 JSON 取值助手。
   `internal` 只在本 module 内可见，等于「同包可见」，比 `public` 窄得多，
   但确实比原来的 `private` 宽。**这是拆文件的必然代价**，不是设计选择。
2. **ViewModel 域文件用扩展函数而不是子类**：`HarnessViewModel` 的成员函数被搬到
   `Harness*ViewModel.kt` 里写成 `fun HarnessViewModel.xxx()`。调用点写法完全不变
   （`viewModel.loadDirectory()` 照样成立），所以 `ui/` 下所有界面的**行为**一行都没改；
   代价是 IDE 里「这个方法在哪」需要跨文件跳转。

## 已知结构性债务

| ID | 债务 | 位置 | 为什么现在不动 | 清账条件 |
|---|---|---|---|---|
| DEBT-001 | 网页版与原生双实现并存 | `app/src/main/assets/chat.html`（355 行壳）+ `assets/chat/`（6 JS + 1 CSS，2026-10-18 拆分）+ `ui/chat/` | 网页版是兜底资产，真机验证过；先完成原生反向移植 | 原生功能覆盖 `PARITY.md` 全项且网页版使用率归零 |
| DEBT-002 | README 双站无自动对账 | `README.md` / `README.en.md` | `docsCheck` 只覆盖 `docs/project/`；Kotlin/Gradle 侧无 README 解析器 | 扩展 `docsCheck` 增加 README 版本号/测试数一致性检查 |
| DEBT-003 | 无 lint baseline / 静态检查配置 | 仓库根 | 当前 `lintDebug` 在 CI 跑但无 baseline，warning 数不宜当门禁 | 建立 `lint-baseline.xml` 后把新增 warning 当门禁 |
| DEBT-004 | ~~无 `@patch` 补丁登记表自动化~~ **已由单测兑现** | 全仓 | **2026-10-17 起由 `ArchitectureGuardTest` 的 `@patch` 扫描顶住**：五字段齐全 + `test` 指向的测试类真实存在 + id kebab-case 且唯一，随 `testDebugUnitTest` 进 CI | 把同一套校验搬进 `docsCheck`，与文档一致性门禁合流 |
| DEBT-005 | ~~行数预算不进 CI~~ **已由单测兑现** | 仓库根 | **2026-10-17 起由 `ArchitectureGuardTest` 第 1 条顶住**：扫 `app/src/main/java` 全部 `.kt`，超 800 行即失败，豁免须在本表登记且记录行数 ≥ 实际 | 同上：搬进 `docsCheck` |
| DEBT-006 | WebView 桥自持第二个网络边界 | `web/ChatWebBridge.kt:75` | WebView 桥要转发「任意方法 + 任意路径 + 任意 JSON」，与 `DshClient` 的强类型 API 对不上，故自持一份 `OkHttpClient`（凭据仍从 `ActiveConnection` 取）。**这条以前只活在代码注释里，2026-10-17 由 `ArchitectureGuardTest` 第 4 条抓出来并在此立账** | 给 `DshClient` 加通用转发入口（任意方法/路径/JSON，含令牌注入与超时），`ChatWebBridge` 改用它，然后删掉 `ArchitectureGuardTest` 里的白名单项与本条目 |


> **DEBT-006 是「登记的债」，不是「允许」**：`ArchitectureGuardTest` 的白名单里写着它，
> 意为「这条偏离已知、有主、有清账条件」；再出现第三个自建客户端的文件会直接判红。
> 白名单是**显式**的，新增一项必须同时补齐本表条目 —— 只改白名单不改台账，等于把违规洗白。
> **DEBT-007 已于 2026-10-17 清账**（当天立、当天清）：`ConnectionCoordinator` 加了
> `measureOverride` 接缝，三个依赖精确延迟比较的用例改为注入受控测量 ——
> 「谁更快」不再取决于跑测试时机器多忙。它留下的教训仍然成立：
> **flaky 测试比没有测试更坏**，它教会人「红了先重跑一次」，于是真的回归也会被重跑掩盖。
> 立账时它没有白名单，也没有长期挂着 —— 这是台账制度想要的样子。

## 账怎么记

- **新增**：写码前先入表，注明「为什么是债」与「清账阶段」。
- **清账**：同一提交里删条目 + 在 `CHANGELOG-DEV.md` 记一条（基于什么/为什么/干了什么/测了什么）。
- **收紧**：行数下降时更新数字，不留旧值（旧值会骗人）。

> 判据不是「有没有债」——**债可见且方向向下，比假装没有债更可信。**