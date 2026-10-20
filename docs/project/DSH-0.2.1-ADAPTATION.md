# dsh-companion 对 DSH 0.2.1-rc.2 / dsh-remote-bridge 2.4.2 的适配差距清单

- 调研日期：2026-10-20
- 调研对象：`/media/wangke/OFFICE/workspace/dsh-companion`（Android App，versionName **2.2.45** / versionCode **10070**，AGPL-3.0-only，fork 自 `Hakunm/dsh-android-app`）
- 参照服务端：`/media/wangke/OFFICE/workspace/dsh-remote-bridge`（`PLUGIN_VERSION = '2.4.2'`，`src/shared/version.ts:1`）
- 目标内核：DSH **0.2.1-rc.2**
- 本文只做调研与差距记录，**不改任何源码、不构建 APK、不做任何 git 操作**。

---

## 0. 结论先行

**dsh-companion 2.2.45 连 dsh-remote-bridge 2.4.2 没有已知的不匹配。**

App 侧 2.2.45 已经是「按 bridge 2.4.2 特性面写」的状态：v2.2.45 的三项核心改动（投影透传、权限档位 BFF、截断提示）依赖的都是 bridge 2.4.1 / 2.4.2 已发布的能力，而且 bridge 侧这三项都有对应提交：

| bridge 侧改动 | 提交 | App 侧对应 |
|---|---|---|
| `history` 透传 `projections` | `569e356` | `ChatPresentation.permissionSelect()` / `todoItems()` 读 `projections.values.*`（`data/ChatPresentation.kt:45,73`） |
| 新增 `GET /chat/permission-presets` BFF | `569e356` | `DshClient.permissionPresets()`（`data/DshClient.kt:174`） |
| `turn/end` 的 `reason` 透传 | `8eec95b` | `isTurnMaxTokens()` + `noteTurnBoundary()`（`data/ChatModels.kt:288`、`HarnessTruncationViewModel.kt:41`） |
| `commands.execute` 补全四参数（修所有斜杠命令失效） | `7bc82b0` | `POST /chat/sessions/{id}/commands`（`data/DshClient.kt:345`） |

**真正缺的是「DSH 内核 0.2.1 这一层」的适配**，而不是 bridge 2.4.2 这一层：

1. **两仓文档都还停在 0.2.0-rc.2**，没有任何一处提到 0.2.1-rc.2。bridge 的兼容层 `src/host/dsh-0.2-bridge.ts` 是基于 0.2.0-rc.2 写的；App 的兼容补丁（`AgentPreset.trust` 默认值）同样是基于 0.2.x 行为写的。
2. **DSH 0.2.1 新增的 `session/working-directory/{get,set}` 能力，两端都没有接**。App 现在对会话工作目录是**只读**的（能显示、能当 `@` 补全的基准），不能改。
3. **没有一份「App ↔ bridge ↔ DSH 内核」三方版本对照表**。现有版本门槛全部记在 README 与 `PITFALLS.md` §bridge 版本基线里，内核版本那一栏是空的。

> 一句话：**bridge 2.4.2 是够用的，内核 0.2.1 是没适配的，而「没适配」目前主要体现为「文档没写」和「新能力没用上」两个软性问题，不是硬故障。**

---

## 1. 调研范围与证据来源

| 项 | 证据 |
|---|---|
| App 版本 | `README.md:532`（`v2.2.45` / `versionCode 10070`）、`docs/project/STATUS.md:2` |
| bridge 版本 | `dsh-remote-bridge/src/shared/version.ts:1`（`2.4.2`） |
| bridge 能力位全量清单 | `dsh-remote-bridge/src/host/capabilities.ts:52-85`（26 项） |
| bridge 内核兼容层 | `dsh-remote-bridge/src/host/dsh-0.2-bridge.ts`（约 1993 行，含 `@patch history-projections-passthrough`，见 1271-1287 行） |
| App 能力位消费方 | `app/src/main/java/io/github/westanke/dshcompanion/data/BridgeCapabilities.kt`（全文 94 行） |
| App 唯一网络边界 | `app/src/main/java/io/github/westanke/dshcompanion/data/DshClient.kt`（685 行，50 个方法） |

---

## 2. P1（必须改）

> P1 的判定标准：**不改会让用户或后来者做出错误判断**（不是「功能缺失」，而是「信息错」）。信息错的代价比功能少一个更高。

### P1-1 两仓 README 都没有「DSH 内核版本」这一栏，0.2.1-rc.2 无任何记载

**现状**：

- `dsh-remote-bridge/README.md:24-27` 只写了「DSH **0.2.x** 兼容性 …… 增加了对 DSH `0.2.0-rc.2` 的兼容层」，链接指向 `docs/project/DSH-0.2-COMPAT.md`。
- `dsh-remote-bridge/README.md:65` 的要求栏写的是「DSH 内核 `0.1.x` 或 `0.2.x`」——**没有 0.2.1**。
- `dsh-remote-bridge/README.md:328` 只记了 `master@47f9438…` 这个 commit，没有版本号语义。
- `dsh-companion/README.md:139-142` 与 `README.en.md:147-151` 的兼容性说明同样只写「DSH 0.2.x」，举的例子是 `AgentPreset.trust` 缺失（`CHANGELOG-DEV.md:3890` 记录为 2026-10-08 的事，对应 0.2.x）。

**风险**：

- 用户在 DSH 0.2.1-rc.2 上升级 bridge 到 2.4.2，**没有任何文档告诉他这个组合是否验证过**。bridge 的兼容层只经过 0.2.0-rc.2 的真机验证（`DSH-0.2-COMPAT.md:139-173` 的 §四 全部基于 0.2.0-rc.2）。
- 反过来，一个 0.2.1 用户遇到问题时，两仓的坑位索引、README、STATUS 全都指向 0.2.0-rc.2 的结论，**排错方向从第一步就是偏的**。
- `dsh-companion/AGENTS.md` §1 写「与谁配：服务端插件 `dsh-remote-bridge`（≥ 2.3.7）」，同样没有内核版本维度。

**建议改法**：

1. 在 `dsh-remote-bridge/README.md` 的「要求」栏（65 行附近）与「兼容性与已知限制」节（283 行起）各补一行：**已验证的内核版本**（如 `0.2.0-rc.2` 已验证 / `0.2.1-rc.2` 待验证或已验证）。
2. `dsh-remote-bridge/docs/project/DSH-0.2-COMPAT.md` 的标题与「适配后的 DSH 内核」表（第 15 行）从 `0.2.0-rc.2` 扩成覆盖 `0.2.1-rc.2` 的结论，或新开一节记录 0.2.1 的差异。
3. `dsh-companion/README.md` 中英双站的兼容性说明（139-142 / 147-151）与 `AGENTS.md` §1 同步补内核版本口径。

**涉及文件**：

- `dsh-remote-bridge/README.md` + `README.en.md`
- `dsh-remote-bridge/docs/project/DSH-0.2-COMPAT.md`
- `dsh-companion/README.md` + `README.en.md`
- `dsh-companion/AGENTS.md`（§1）

---

### P1-2 `dsh-remote-bridge` 2.4.2 未声明「基于 0.2.1 开发」，App 侧无据可依

**现状**：

- `dsh-remote-bridge/README.md:328`：「v2.4.2 基于 DSH `master@47f943859bef60e4160492346772ded9b24f765a` 开发」。这是一个 commit 哈希，**不是版本号**，读者无法判断它落在 0.2.0-rc.2 还是 0.2.1-rc.2。
- `dsh-remote-bridge/package.json:63-65` 的 `engines` 只有 `node`，**没有 `dsh` 字段**（这一点与既有决策一致：不引入 `engines.dsh`）。
- App 侧 `data/BridgeCapabilities.kt:23` 的 `bridgeVersion` 注释明确写「仅用于诊断展示，**不作为功能判据**」，`HarnessModels.kt:339-341` 同样。所以 App 拿不到内核版本，**也不打算拿版本号做判据**。

**风险**：

- bridge 升级到 0.2.1 后，`PLUGIN_VERSION` 仍是 `2.4.2`，App 的能力位清单（`capabilities.ts:52-85`）如果没有新增位，**App 完全感知不到内核换了**。这是设计使然（好事），但意味着**一旦 0.2.1 改了内核契约，只有真机能发现**。
- 这正是 `DSH-0.2-COMPAT.md:159-161` 记下的教训：「单测能证明拿到帧之后处理得对，证明不了帧会不会来」。

**建议改法**：

1. `README.md:328` 改为「内核 commit + **它对应的内核版本号**」双写，例如 `master@47f9438… (0.2.1-rc.2)`。
2. 若 bridge 后续要面向 0.2.1 做适配，按既有纪律在 `dsh-0.2-bridge.ts` 加 `@patch` 四要素，并在 `CHANGELOG-DEV.md` 记一条。

**涉及文件**：

- `dsh-remote-bridge/README.md` + `README.en.md`
- `dsh-remote-bridge/docs/project/CHANGELOG-DEV.md`（如新增适配）

---

### P1-3 App 侧「最低 bridge 版本」口径已到 2.4.2，但 `PITFALLS.md` §bridge 版本基线表格还停在 2.3.7

**现状**：

- `docs/project/PITFALLS.md:160-165` 的表格只列了 2.3.1 / 2.3.2 / 2.3.3 / 2.3.7 四档，**没有 2.3.8、2.4.1、2.4.2**。
- 但 README 双站已经写了 2.3.8（`README.md:295`）与 2.4.2（`README.md:543`、`README.en.md:597`）。
- `docs/project/STATUS.md:191` 的分项口径也只到 2.3.7（虽然 6 行提到了 2.4.2）。
- 表格下方的铁律（178 行）写「新增 bridge 依赖时，同步更新 README 双站 + `STATUS.md`」——**漏了 `PITFALLS.md` 自己**。

**风险**：这张表是排错时最先查的东西（`AGENTS.md` §0 路由表指向它）。表里没有 2.4.2，排错者会得出「2.3.7 就够了」的错误结论，而 v2.2.45 的权限 Pill、截断提示在 2.3.7 上分别是「降级只读」和「不显示」。

**建议改法**：把 §bridge 版本基线的表格补齐到当前口径，并把铁律改成「README 双站 + `STATUS.md` + **本表**」三处同步。建议补入：

| 能力 | 最低 bridge |
|---|---|
| 断档自动重同步、订阅基线、交付物实时卡片、`seq` 单一语义 | 2.3.8 |
| `turn/end` 的 `reason.kind === 'max-tokens'` 透传（「回答被截断」提示） | 2.4.1 |
| `GET /chat/permission-presets` BFF + `history` 透传 `projections` + 斜杠命令执行 | **2.4.2** |

**涉及文件**：

- `dsh-companion/docs/project/PITFALLS.md`（§bridge 版本基线）
- `dsh-companion/docs/project/STATUS.md`（191 行的分项口径）

---

## 3. P2（建议改，能提升）

### P2-1 App 对会话工作目录**只读**，用不上 DSH 0.2.1 的 `session/working-directory/{get,set}`

**现状（这是本次调研的核心差距）**：

App 侧对 `cwd` 的全部用法都是**读**：

| 位置 | 用途 |
|---|---|
| `data/Models.kt:83,91` | `ChatSession.cwd`（相对授权根）+ `cwdAbsolute`（bridge 2.3.5 起提供） |
| `data/MentionQuery.kt:74-78` | `@` 补全把 `entryPath` 拼成绝对路径 |
| `ui/chat/ChatLinkHandlers.kt:74-78` | 会话里的相对路径链接以 `cwdAbsolute` 为基准拼绝对路径 |
| `HarnessFilesViewModel.kt:160` | `@` 补全列举 `roots/{rootId}/entries?path=cwd` |
| `ui/ChatWebScreen.kt:348-349` | 给 WebView 传 `cwd` / `cwdAbsolute` |
| `ui/ChatScreen.kt:225`、`ui/chat/ChatSessionPane.kt:231` | 会话标题兜底显示 |

bridge 侧同样只透传，不提供 set：

- `src/host/chat-adapter.ts:405-416` 从内核 `session.cwd` 算出 `match.relative` 与 `absoluteCwd` 后**只写进响应**。
- `src/host/chat-adapter.ts:551` 创建会话时传 `cwd: resolved.absolutePath`（**创建时**能指定，**创建后**不能改）。
- `src/host/capabilities.ts:52-85` 的 26 个能力位里**没有任何一个与工作目录相关**。

**机会**：

DSH 0.2.1 的 `session/working-directory/{get,set}` 一旦通过 bridge 暴露出来，App 可以做三件现在做不到的事：

1. **会话内切换工作目录**：`@` 补全从此不必绑定在会话创建时那个 cwd 上。现在的实现是「`@` 永远指向本会话的 cwd」（`HarnessFilesViewModel.kt:129-138` 的注释明说这是刻意设计，因为共用字段会串），用户想在别处引用文件只能新建会话。
2. **新建会话时的默认目录可以建完再改**：`ChatComposer.kt:316-317` 现在把 `mentionSession.cwd` 当唯一基准。
3. **与官方 Web 对齐**：官方 0.2.1 前端已能在会话里改工作目录，App 这是纯缺口。

**建议改法**（按 bridge 侧纪律，**必须先改服务端再接 App**）：

1. bridge：新增 `GET/PUT /chat/sessions/{sessionId}/working-directory`，在 `capabilities.ts` 加能力位 `session-working-directory-v1`（命名遵守 `capabilities.ts:45` 的 `<能力>-v<主版本>`），并在 `docs/api/openapi.yaml` + `asyncapi.yaml` + `API.md` + `kotlin-sdk` + 合约测试五处同步改（`AGENTS.md` §3.1 铁律）。
2. App：`data/DshClient.kt` 加 `sessionWorkingDirectory()` / `setSessionWorkingDirectory()`；`data/BridgeCapabilities.kt` 加 `supportsWorkingDirectoryChange()`；`HarnessFilesViewModel.kt` 的 `@` 基准从「只读 session.cwd」扩成「可变状态」；**老 bridge 没有该能力位时保持现状**（只读），不能整块消失。

**风险**：如果不做，App 的 `@` 补全与会话文件面板会一直被锁在会话创建时的 cwd 上。这是个**体验天花板**，不是故障，所以列 P2 而不是 P1。

**涉及文件**：

- 服务端：`dsh-remote-bridge/src/host/router.ts`、`src/host/capabilities.ts`、`src/host/chat-adapter.ts`、`docs/api/*`、`kotlin-sdk/`
- App：`data/DshClient.kt`、`data/BridgeCapabilities.kt`、`HarnessFilesViewModel.kt`、`data/MentionQuery.kt`、`ui/chat/ChatComposer.kt`

---

### P2-2 能力位只消费了 3 个，26 个里 23 个是「对外声明」——App 可以更主动地做降级提示

**现状**：

- `data/BridgeCapabilities.kt:37`：`REQUIRED_CAPABILITIES = listOf("approvals-v1", "questions-v1")`，只此两项会触发警告。
- `data/BridgeCapabilities.kt:84-85`：`supportsArchiveListing()` 读 `session-archive-list-v1`，决定「已归档」入口显不显示。
- 其余 23 项（`capabilities.ts:31-32` 明说「对外声明，服务任何客户端」）App 一律不读。
- 后果：老 bridge 上 `permission-presets` 这条路是**靠 404 兜**的（`HarnessConnectionViewModel.kt:251-254` 自己吞异常），而不是靠能力位判断；`history-byte-budget-v1`、`interaction-replay-v1`、`session-snapshot-v1` 这三项 App 全都实际依赖（`ChatSeqMonitor`、连接即重放、超预算丢最旧），但**没有一项在能力位上做过判断**。

**机会**：把「实际依赖但靠异常兜底」的能力位改成显式判断，可以在连接时一次性给出一句「这台电脑的插件缺 X/Y/Z，对应功能将降级」，而不是等用户点到某个功能才发现它不动。

**建议改法**：在 `BridgeCapabilities.kt` 加一个 `DEGRADABLE_CAPABILITIES`（与 `REQUIRED_CAPABILITIES` 分开，只提示不阻断），消费点放在 `SettingsScreen.kt:150-160` 现有的 `missingCapabilities` 分支旁边。

**风险**：加多了会养成用户忽略警告（`BridgeCapabilities.kt:29-31` 自己警告过这件事），所以要克制，只列「用户能感知到的降级」。

**涉及文件**：`data/BridgeCapabilities.kt`、`ui/SettingsScreen.kt`、`res/values/strings.xml` + `res/values-en/strings.xml`

---

### P2-3 App 没有读取 bridge 的 `HISTORY_FORMAT_VERSION`

**现状**：

- `dsh-remote-bridge/src/host/capabilities.ts:88-103` 定义了 `HISTORY_FORMAT_VERSION = 1`，注释明确写「客户端可以在 `GET /messages` 的响应里读到它；将来若出现不兼容变更，服务端会连同游标一起拒绝（返回 `resetRequired`）」。
- App 侧 `data/ChatHistoryMapping.kt` 与 `data/ChatModels.kt` **没有任何一处读这个字段**（本次 grep 未命中 `historyFormatVersion`）。
- bridge 侧 101-102 行明说「**本轮只做声明，不做强制校验** …… 等客户端开始读它之后再打开校验」。

**机会**：这是 bridge 留给 App 的一个明确的「下一步」。App 读了它，bridge 才能打开强制校验；不读，将来内核/插件改事件形状时，客户端会把新形状按老规则解析，**拼出一段看起来正常但内容是错的历史**——`capabilities.ts:97-98` 描述的最坏情况。

**建议改法**：`data/Models.kt` 的 `ChatHistory` 加 `historyFormatVersion: Int? = null`；`ChatHistoryMapping` 或 `HarnessSessionsViewModel` 在收到版本不符时触发一次全量重取（而不是增量），并在诊断日志里记一行。

**涉及文件**：`data/Models.kt`、`data/ChatHistoryMapping.kt`、`HarnessSessionsViewModel.kt`

---

### P2-4 两仓都没有「三方版本对照表」，排错要跨三个仓库翻

**现状**：版本门槛分散在五处，没有任何一处能把「DSH 内核 ↔ bridge ↔ App」三列对齐：

| 位置 | 覆盖 |
|---|---|
| `dsh-companion/README.md:293-295` + `README.en.md` | bridge 门槛（安装要求） |
| `dsh-companion/README.md:549` | bridge 分项门槛（项目信息） |
| `dsh-companion/docs/project/PITFALLS.md:160-165` | bridge 分项（表格） |
| `dsh-companion/docs/project/STATUS.md:191` | bridge 分项（散文） |
| `dsh-remote-bridge/README.md:65,328` | 内核版本（无 App 列） |

**机会**：做一张三列对照表，放在 `dsh-companion/docs/project/PARITY.md` 顶部（它是「能力 ↔ 两端」的既有矩阵，加一列内核版本最自然），bridge 侧 README 反向链接。

**涉及文件**：`docs/project/PARITY.md`、`dsh-remote-bridge/README.md`

---

## 4. P3（暂不做，说明为什么）

### P3-1 不给 App 加「最低 bridge 版本」硬校验

**为什么不做**：`data/BridgeCapabilities.kt:7-19` 的 KDoc 已经把这套决策写死了，而且是对的——能力位是**运行时事实**，版本号只是历史编号。`capabilities.ts:14-18` 从服务端侧给出了同意的两条理由（粒度太粗、方向错了）。加一个 `if (bridgeVersion < "2.4.2") 拒绝连接` 会同时踩两个坑：一是用户自建/魔改的 bridge 版本号可能不准；二是同一个版本可能因配置关掉某个能力。**保持现状。**

### P3-2 不把 `bridgeVersion` 当功能判据

同 P3-1。`HarnessModels.kt:339-341` 的注释「仅用于诊断展示，不作为功能判据」是正确口径，不改。

### P3-3 不接入 DSH 0.2.1 的 on-demand bundle / Office 环境变量 / import SDK 类型

**为什么不做**：这三项在 bridge 侧的计划书里已被明确排除（属服务端侧决策），且与 App 的接入面无关——App 只走 bridge 的版本化 REST，不直接碰内核运行时。App 侧没有任何对应代码，也不需要加。

### P3-4 不为「内核换了但能力位没变」加报警

**为什么不做**：这正是能力位机制的设计目标（内核升级不应惊动客户端）。真要防 0.2.1 破坏内核契约，靠的是 `DSH-0.2-COMPAT.md` 那套「单测 → 打包装入 → 真实端到端」三段验证（159-161 行），而不是在 App 里加一个版本比较器。

---

## 5. 对第 5 个问题的直接回答

> **如果 dsh-remote-bridge 后续新增能力（例如基于 DSH 0.2.1 的 session working-directory），App 侧要怎么受益？现在缺什么？**

**现在缺的（按依赖顺序）**：

1. **服务端缺接口**：bridge 2.4.2 的 26 个能力位里没有工作目录相关位，`router.ts` 没有对应路由，`chat-adapter.ts` 只在创建会话时接受 `cwd`（551 行）、在响应里回填 `cwd`/`cwdAbsolute`（405-416 行）。**没有 set。**
2. **App 缺一个「可变」的工作目录状态**：现在 `ChatSession.cwd` 是不可变的投影数据，`HarnessFilesViewModel.kt:129-138` 的注释把「`@` 永远指向本会话 cwd」写成了设计约束（理由是防止与文件页浏览位置串字段）。
3. **App 缺能力位判断**：`BridgeCapabilities.kt` 没有 `session-working-directory-v1` 的判定函数，接进来时老 bridge 会 404。

**受益路径（三步，顺序不能倒）**：

1. bridge 加 `GET/PUT /chat/sessions/{sessionId}/working-directory` + 能力位 `session-working-directory-v1`，同步改 OpenAPI / AsyncAPI / `API.md` / `kotlin-sdk` / 合约测试五处（`dsh-remote-bridge/AGENTS.md` §3.1 铁律）。
2. App 在 `BridgeCapabilities.kt` 加 `supportsWorkingDirectoryChange()`，在 `DshClient.kt` 加两个方法，**没有能力位时完全走老路**（现状）。
3. App 把 `@` 补全的基准从「会话不可变 cwd」升级为「会话 cwd + 用户可覆盖的当前目录」，覆盖点：`HarnessFilesViewModel.kt:129-160`、`MentionQuery.kt:74-78`、`ChatComposer.kt:309-317`、`ChatLinkHandlers.kt:73-86`（相对路径链接的基准也可以跟着变）。

**注意一个边界**：改了工作目录之后，`ChatLinkHandlers.kt:86` 那条「老 bridge / 会话未同步时才说无法定位」的兜底文案要同步改，否则用户会看到一句与事实不符的提示。

---

## 6. README 双语文档需要改哪些行（清单）

> 这是 `AGENTS.md` §3 表格里「bridge 最低版本：README 双站『安装要求』与『项目信息』两处必须一致」的执行清单。
> 本次调研**只列不改**。改的时候按 `RELEASE.md` 的顺序铁律走：先改代码/服务端 → 再改文档与版本号 → `scripts/build.sh` 校验 → 打包。

### 6.1 `README.md`（中文站）

| 行号 | 现在的内容 | 建议改成 | 归属 |
|---|---|---|---|
| **139-142** | 「**DSH 0.2.x 兼容性**：……0.2.x 的 `AgentPreset` 不再返回 `trust` 字段……」 | 段首补一句内核版本口径：已验证 `0.2.0-rc.2`；`0.2.1-rc.2` 的状态（已验证/待验证）单独一行 | P1-1 |
| **293-295** | 「已安装 `dsh-remote-bridge` **≥ 2.3.7** …… **断档自动重同步、订阅基线、交付物实时卡片、`seq` 单一语义需要 2.3.8+**」 | 末尾追加：「**权限档位候选清单 / 历史投影透传 / 斜杠命令执行需要 2.4.2+**（缺了权限 Pill 退化为只读标签、部分斜杠命令失效）」 | P1-3 |
| **532** | 「当前版本：`v2.2.45`（`versionCode` 10070）」 | 若发新版才动；本次不动 | — |
| **543** | 「**「回答被截断」提示**（需 bridge **≥ 2.4.2**）」 | 应改为 **≥ 2.4.1**（`turn/end` 的 `reason` 透传是 `8eec95b`，对应 v2.4.1；`CHANGELOG-DEV.md:40` 明写「配套 bridge **v2.4.1** 透传该字段」） | P1-3 |
| **549** | 「服务端插件：dsh-remote-bridge …… **≥ 2.3.7**（……**审批 / 问答卡片需要 2.3.7+**；**断档重同步 / 订阅基线 / 交付物实时卡片需要 2.3.8+**）」 | 括号内追加：「**权限档位 BFF 与投影透传需要 2.4.2+**」 | P1-3 |
| **549（同处）** | 同上 | 追加 DSH 内核版本口径（与 bridge README 65 行对齐） | P1-1 |

### 6.2 `README.en.md`（英文站）

| 行号 | 现在的内容 | 建议改成 | 归属 |
|---|---|---|---|
| **147-151** | "**DSH 0.2.x compatibility**: this fork also carries the upstream fix …… DSH 0.2.x no longer returns `trust` on `AgentPreset`" | 同 6.1 第 139-142 条，补内核版本口径 | P1-1 |
| **597** | "**"response truncated" notice** (needs bridge **≥ 2.4.2**)" | 改为 **≥ 2.4.1**（与 6.1 第 543 条同步，**双站必须同值**） | P1-3 |
| **603** | "Server plugin: dsh-remote-bridge **≥ 2.3.7** (loading older messages needs 2.3.1+; …… gap resync / subscription baseline / live deliverable cards need 2.3.8+)" | 括号内追加 "permission-preset BFF and history projections need 2.4.2+" | P1-3 |
| **（安装要求节）** | 需定位到与 `README.md:293-295` 对应的英文行（本次未逐行定位到行号，改前请 grep `2.3.7` 对齐） | 同步追加 2.4.2 口径 | P1-3 |

### 6.3 双站必须一起改的（`AGENTS.md` §3 铁律）

`README.md` 与 `README.en.md` 的 **bridge 最低版本**口径出现在两处（安装要求 + 项目信息），**四处必须同值**。历史上 v2.2.33–2.2.35 连续三版在这里漂移过（中文停 2.2.32、英文停 24 类/256 用例），`scripts/backfill-release.py` 就是为兜底它而存在。本次若只改一站，`docsCheck` **不会**发现（它只覆盖 `docs/project/` 下 8 份文档），必须另跑一次对账。

### 6.4 顺带要改的非 README 文件（同一批提交里）

| 文件 | 行 | 改什么 | 归属 |
|---|---|---|---|
| `docs/project/PITFALLS.md` | 160-165 表格 + 178 铁律 | 补齐 2.3.8 / 2.4.1 / 2.4.2 三档；铁律加上「本表」 | P1-3 |
| `docs/project/STATUS.md` | 191 | 分项口径补 2.4.1 / 2.4.2 | P1-3 |
| `docs/project/PARITY.md` | 3-6 表头区 | 加一列「内核版本」 | P2-4 |
| `AGENTS.md` | §1 | 「与谁配」补内核版本口径 | P1-1 |

---

## 7. 附：本次调研的核对记录

为避免「文档说支持、代码里没有」这类旧错，以下逐条核对过：

- [x] App 的网络出口只有 `DshClient`（`AGENTS.md` §4.1 记的例外是 `web/ChatWebBridge.kt` 为 WebView 自持 OkHttp，与 REST 前缀无关）。
- [x] App 的 REST 前缀是 `/api/v1`，由 `DshClient.normalizeEndpoint()` 自动补（`data/DshClient.kt:584-591`）；bridge 3090 监听面上路由**不带** `/dsh-workspace-api`（`README.md:349-365` 与 `router.ts:101` 的 stripPrefix 机制双向印证）。**不存在前缀不匹配。**
- [x] App 侧没有任何版本号比较逻辑（grep `semver` / `compareVersion` / `isAtLeast` 零命中）——与 `BridgeCapabilities.kt` 的设计声明一致。
- [x] `REQUIRED_CAPABILITIES` 的两个名字（`approvals-v1` / `questions-v1`）在 bridge 侧 `capabilities.ts:78-79` 确实存在，未跨仓漂移。
- [x] `session-archive-list-v1` 在 bridge 侧 `capabilities.ts:64` 存在。
- [x] `permission-presets` BFF 在 bridge 侧 `router.ts:269` 存在（`GET /api/v1/chat/permission-presets`）。
- [x] `turn/end` 的 `reason` 透传在 bridge 侧 `chat-adapter.ts`（`8eec95b`）已合入，App 侧 `ChatModels.kt:288-291` 消费。
- [x] `HISTORY_FORMAT_VERSION` 在 bridge 侧存在（`capabilities.ts:103`），App 侧零命中——即 P2-3 的差距成立。
