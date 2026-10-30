# WS 优先、REST 兜底 —— App 侧双通道改造设计

> **状态**：设计定稿，实施中
> **起点**：用户指令「WS 优先、REST 兜底 APP不能走这条路吗？」→ 选定 **C 档：全量 WS 优先 + REST 兜底**
> **跨仓**：本文只定 App 侧；桥侧契约（asyncapi 通道 + Kotlin SDK + 合约测试）由 `dsh-remote-bridge` 侧同步推进

## 1. 为什么做

现状是**分工制**，不是 WS 优先：

| 干什么 | 走哪条 |
|---|---|
| 发消息、拉历史、审批、命令、模型目录… | REST `/api/v1/*` |
| 收推送（流式增量、turn 状态、审批弹出…） | WS `/api/v1/events`（单向广播） |

`/ws/mobile` 是**双向请求/响应**通道（`src/host/ws-handler.ts:508-560`：8 个基础帧 + 10 个域处理器），App 至今一个帧都没发过。全仓 `grep "ws/mobile"` 只命中两处注释（`data/SessionAnalytics.kt:12`、`data/CommandArgOptions.kt:8`）。

**收益**（不是「字吐得更快」，那条路已经通了）：

1. **单连接**：REST 每次请求都要一次 TCP + TLS + Bearer 鉴权；WS 一次握手复用。移动网络下这是实打实的耗电与延迟差。
2. **拿回 REST 没有的能力**：`API.md:918` 明列「尚未进入稳定外部契约」的那批 —— 会话搜索、消息队列、通用问题回答、工作区自定义排序、归档恢复、目标/子代理写操作。这批 App 现在**真拿不到**。
3. **请求-响应有序**：同一连接上发消息 → 收 `sent` → 收该轮事件，不存在「REST 写完、WS 事件先到」的乱序窗口。

**成本**：把单通道 App 改成双通道 App。每条能力两套路径 + 退化判据 + 双份测试。

## 2. 治理前提（先补票再上车）

`docs/project/API.md:920` 是硬约束：

> App 不得直接调用 DSH 私有 `/api` 或通用 RPC 透传。每项缺失能力必须在插件中定义稳定 REST/事件 schema、对应设备 scope/root/session 授权、脱敏规则、**OpenAPI/AsyncAPI、Kotlin SDK 和合约测试**后才能标记为对等。

当前缺口：

- `docs/api/asyncapi.yaml:38-39` 的 `channels` **只有** `events: /api/v1/events`，`/ws/mobile` 无 schema；
- `kotlin-sdk/.../DshWorkspaceClient.kt:652` 只有 `fun events(...)`（走 `/api/v1/events`），无 `/ws/mobile` 客户端；
- `scripts/check-project-docs.ts:386` 只断言 `asyncapiDoc.channels.events.address`，新增通道不会被门禁拦住，但也不会被校验。

**结论**：桥侧三件事（asyncapi 通道 + Kotlin SDK + 合约测试）必须先落地，否则 App 接 WS 就是自己绕过自己定的规矩。桥侧工作不在本文范围，但它是本改造的**前置依赖**。

## 3. 架构

### 3.1 通道抽象

```
                        ┌──────────────────────┐
                        │   HarnessViewModel   │
                        └──────────┬───────────┘
                                   │ 只认接口
                        ┌──────────▼───────────┐
                        │   DshTransport       │  ← 新增：能力面接口
                        └────┬───────────┬─────┘
                             │           │
                   ┌─────────▼──┐   ┌────▼─────────┐
                   │ MobileWs   │   │ RestFallback │
                   │ Transport  │   │  Transport   │
                   └─────────┬──┘   └────┬─────────┘
                             │           │
                   /ws/mobile│           │/api/v1/*
                             ▼           ▼
                        dsh-remote-bridge
```

**关键约定**：`DshTransport` 是**能力面**，不是「把 DshClient 每个方法抄一遍」。按域切：

| 域 | 接口 | WS 帧 | REST 兜底 |
|---|---|---|---|
| 会话 | `SessionPort` | `sessions` / `history` / `session-create` / `session-rename` / `session-archive` / `session-cancel` / `fork` | 同名 REST |
| 消息 | `MessagePort` | `message` / `attachment` | `sendMessage` / `uploadAttachment` |
| HITL | `HitlPort` | `question-answer` / `question-cancel` / `approval-response` | 同名 REST |
| 命令 | `CommandPort` | `commands` / `command-execute` / `command-options` / `command-select` | 同名 REST |
| 模型 | `ModelPort` | `models` / `providers` / `select-model` / `default-model` / `permission-options` / `permission` | 同名 REST |
| 投影 | `ProjectionPort` | `tasks` / `goal` / `session-stats` / `context-usage` / `agent-team` / `subagents` | **REST history 的 `projections.values`**（已实现，见 §3.4） |
| 文件 | `FilePort` | `file-download-*` / `file-upload-*` / `file-resolve` | 既有 REST |
| 事件 | `EventPort` | `subscribe` + `event` 帧 | `/api/v1/events` |
| 语音/定时/分块 | 保持 REST | — | **不迁**（见 §3.5） |

### 3.2 降级判据（唯一真理源）

降级**只能有一个判据**，散落在调用点必然漂：

```kotlin
sealed interface ChannelState {
    data object WsReady : ChannelState                    // hello 已收、能力位齐
    data class WsDegraded(val reason: String) : ChannelState  // 连上但缺能力位
    data class WsDown(val reason: String) : ChannelState  // 连不上 / 断开重连中
    data object RestOnly : ChannelState                   // 老桥，无 /ws/mobile
}
```

判定顺序：

1. 握手时 `Sec-WebSocket-Protocol` 被拒 / upgrade 404 → **`RestOnly`**，不再重试 WS（老桥不会突然长出通道）；
2. `hello` 到达但 `capabilities` 缺所需位 → **`WsDegraded`**，该域走 REST，其余域仍走 WS；
3. 已建立的连接断开 → **`WsDown`**，全部走 REST，后台按 `1,2,4,8,16,30` 秒退避重连；
4. 重连成功 → 回到 `WsReady`，**不重放断连期间的请求**（请求都有 REST 幂等兜底，重放比丢更危险）。

### 3.3 请求-响应关联

**先说一个实测出来的坑：`/ws/mobile` 的 requestId 回显曾经是不统一的。**

原设计（以及桥仓 `asyncapi.yaml` 的初版描述）都写着「响应帧靠 `requestId` 回显关联」。2026-10-29 用真实 token 连运行中的桥逐帧实测后发现**这是错的**：

| 请求 `type` | 响应 `frame.requestId` | 响应 `frame.data.requestId` |
|---|---|---|
| `sessions` / `history` / `workspaces` / `models` / `commands` | 无 | 无 |
| `goal`（及 team / files / speech / schedule） | 无 | **有** |

根因在桥的 `src/host/ws-handler.ts`：`sendFrame(webSocket, frame: { kind, data? })` —— **外层信封没有 requestId 的位置**，只有各域处理器自己往 `data` 里塞的处理才会回显。而 `ws-sessions.ts` / `ws-models.ts` / `ws-commands.ts` / `ws-hitl.ts` 等文件里 `requestId` 出现 0 次。

后果：`MobileWsTransport.send()` 若发这些帧，会等满 15s → `abandon()` 断连重连 → 再超时。

**处置（两侧同时做）**：
1. 桥侧补统一回显（`ws-handler.ts` 注入 + async 上下文隔离，因为帧分发是 `void this.handleMessage(...)` 的**并发**模型，用实例字段暂存会串号）。
2. 客户端做**双兼容**：优先按 `requestId` 配对；没有则退化为**按响应 `kind` 配对**（兼容老桥）。退化路径必须**按 kind 串行化**——同 kind 同时只有一条在途，否则同 kind 并发会拿错响应。
3. `error` 帧不带 `requestId`，只带 `data.requestType`（是**请求帧的 type**，不是响应 kind），所以只能失败该 type 下**最老的那条**在途请求。

客户端侧规则：

- 每请求生成 `requestId = UUID`，`Map<String, CompletableDeferred<JsonObject>>` 挂起等待（主路径）；
- 回帧无 `requestId` 时按 `kind` 兜底配对（退化路径，按 kind 串行化）；
- 收到确实无法归属的帧（`event` / `question-requested` / `approval-requested` / `hello` / `pong` / `speech-state` 等推送类）走推送路径，不进 map；
- **超时**：默认 15s，文件传输类 120s；超时清理 pending 后由调用方决定是否降级（**不要**因为一次静默帧超时就 `close(1001)` 断连——那是把"这一帧不会回显"误判成"连接坏了"）。

### 3.4 投影域的特殊性

`tasks`/`goal`/`session-stats`/`context-usage`/`agent-team`/`subagents` 这六个，**REST 已经拿得到**——它们就在 `ChatHistory.projections.values` 里（`data/SessionAnalytics.kt` 已实现，`ChatPresentation.kt:77` 的 `todoItems()` 同源）。

所以这一域**不迁**：WS 帧和 REST 投影是同一份数据，多接一条路只是多一个可能不同步的副本。`API.md:918` 把它们列为「未进入稳定外部契约」指的是**写操作**（`goal-edit`/`goal-pause`/`goal-resume`/`goal-clear`），那才需要 WS。

### 3.5 不迁的三项

`speech-*` / `schedule-*` / `file-upload-*`（REST 版）保持现状：

1. 它们**已经有 REST 对等面**且已真机验收通过（v2.2.57）；
2. 桥侧对它们的定位就是「WS 里代理内核服务，REST 是同语义对等面」（`API.md:805-812`）；
3. 迁过去收益是「少一条通道」，成本是「重跑一遍真机验收 + 重写分块续传状态机」——性价比不成立。

**`file-upload-*` 的 WS 版也不迁**：REST 分块上传（1 MiB 块、`alreadyReceived` 续传、缺块 409）已跑通 5 MiB 端到端；WS 版是 256 KiB 块、需要 `sha256` 前置声明，是**另一套**状态机。两套并存只会让「为什么这个文件走那条」变成玄学。

### 3.6 行数预算

`AGENTS.md:127` 规定 **800 行/文件**。当前 `DshClient.kt` 已 794 行，顶在线上。

**所以 WS 客户端必须新开文件**，不能往 `DshClient.kt` 里塞：

```
data/ws/
  MobileWsTransport.kt   ← 连接/重连/帧编解码/requestId 关联
  WsFrames.kt            ← 帧类型常量 + 编解码（@Serializable）
  WsPorts.kt             ← 各域 Port 接口与 WS 实现
  ChannelState.kt        ← 降级状态机
```

`DshClient.kt` 只加一个入口方法（把 `http` 和 `token` 交给 WS 层），不再膨胀。

## 4. 实施顺序

| 阶段 | 内容 | 依赖 |
|---|---|---|
| P0 | 桥侧 asyncapi 通道 + Kotlin SDK + 合约测试 | — |
| P1 | `MobileWsTransport`（连接/重连/帧/requestId） + `ChannelState` | P0 |
| P2 | `SessionPort` / `MessagePort` / `EventPort` 三个高频域切换 | P1 |
| P3 | `HitlPort` / `CommandPort` / `ModelPort` | P1 |
| P4 | **目标写操作**（`goal-edit`/`goal-pause`/`goal-resume`/`goal-clear`）—— 见 §4.1，这是唯一真有价值的新能力 | P2 |
| P5 | 真机验收 + 文档 + 发版 | P4 |

每阶段独立可验证、独立可回滚：`ChannelState` 一旦判 `RestOnly`，行为与今天**逐字节一致**。

## 4.1 P4 的真实范围（已按代码核实，比原先设想小很多）

原设计写「搜索、队列、归档恢复」，核实后**三条都不成立**：

| 候选 | 核实结果 | 证据 |
|---|---|---|
| 会话搜索 | **WS 自己也没有**。`search` 帧在 `KNOWN_UNSUPPORTED_FRAMES` 里，回结构化 `NOT_IMPLEMENTED`「session full-text search is not available over WS on this kernel build」 | `src/host/ws-handler.ts:122-124` |
| 队列编辑 | **WS 自己也没有**。`queue-update` 同样在 `KNOWN_UNSUPPORTED_FRAMES`，指引「send a new message with mode:"steer"」 | `src/host/ws-handler.ts:133` |
| 归档列表/恢复 | **WS 自己也没有**。`session-archives` 只在 `ServerFrameKind` 联合里声明，**全仓无生产者**（`grep "kind: 'session-archives'" src/` 零命中）。REST 的 `GET /chat/sessions?archived=true` 才是唯一入口 | `src/host/ws-protocol.ts:187`；`src/host/router.ts:656-659` |

**真正 REST 没有、只有 WS 能给的，只有目标写操作**：

- `goal-edit` / `goal-pause` / `goal-resume` / `goal-clear`（`src/host/ws-goals.ts:56-62`）；
- REST `router.ts` 里 `goal` 零命中（`grep -c "goal" src/host/router.ts` = 0）；
- 能力位是 `mobile-goal-read-v1`，注释明写「写操作还需内核 goals Remote」（`src/host/capabilities.ts:92`）；
- 实现是代理内核 Typert Remote 的 `goals` 服务（namespace `goals`，方法 edit/pause/resume/clear，参数 `{agentId:sessionId, ref, request?}`），**该服务只在较新内核提供**，缺失时回 `unsupported`；
- compare-and-set：必须带刚读到的 `ref:{id,revision}`（`src/host/ws-goals.ts:255-262`）。

所以 P4 的产出是：App 里「会话统计」面板的**目标区**从只读变成可暂停/恢复/编辑/清除。这是唯一一块「今天真做不到、接 WS 才做得到」的功能。**其余 P1-P3 都是把已有的 REST 路径换成更省连接的方式**，收益是连接数与延迟，不是新功能 —— 这个预期要摆正，否则做完会觉得「没什么变化」。

## 5. 验收判据（硬）

1. **退化等价**：老桥（v2.7.x，无 `/ws/mobile`）连上后，App 行为与 v2.2.57 完全一致 —— 现有 717 个用例全绿，不改一行断言。
2. **切换透明**：拔掉 WS（`iptables` 丢 `/ws/mobile` 的包）后，所有功能仍可用，只多一次重连日志。
3. **真机双跑**：同一台 V2080A，分别对 v2.7.2（无 WS）与 v2.8.0（有 WS）各跑一遍核心链路。
4. **不新增网络边界**：`ArchitectureGuardTest` 的 `NETWORK_BOUNDARY_ALLOWLIST` 仍只有 `data/DshClient.kt` 与 `web/ChatWebBridge.kt` —— WS 复用 `DshClient` 的 `http` 实例，不新建 `OkHttpClient`。

## 6. 已知风险

| 风险 | 应对 |
|---|---|
| 双通道状态不一致（WS 说已归档、REST 说还在） | 单一真理源：写操作只走一条，读一律以最近一次响应为准；投影域不迁（§3.4）正好避开这块 |
| 重连风暴 | 指数退避 + 上限 30s + 抖动；`RestOnly` 不重试 |
| 帧积压 | 桥侧已有背压（512 帧队列、溢出丢可重建帧）；客户端侧只保留 pending map，不缓存帧 |
| 测试翻倍 | Port 接口层做假实现，VM 层测试不区分通道；通道差异只在 `MobileWsTransportTest` 一处测 |

## 7. 实施进度与 P2 真机自检清单

### 7.1 进度

| 阶段 | 状态 | 产物 |
|---|---|---|
| P0 桥侧契约 | 已完成 | `asyncapi.yaml` 的 `/ws/mobile` 通道、Kotlin SDK、`mobile-frame-contract.spec.ts` 静态对账 |
| P1 传输层基座 | 已完成 | `data/ws/` 的 `ChannelState.kt` / `WsFrames.kt` / `MobileWsTransport.kt` / `WsPorts.kt` |
| **P2 三域接入** | **已完成（本阶段）** | `WsRequestRouter.kt`（配对层）、`HarnessWsPortsViewModel.kt`（装配层 + 端口适配）、`SessionPort` / `MessagePort` / `EventPort` 的 WS 实现与 REST 兜底 |
| P3 HITL/命令/模型 | 已完成（模型域的**读+写**已真机验证） | `data/ws/WsDomainPorts.kt`、`HarnessDomainPortsViewModel.kt`；`sent` kind 三生产者双向互斥 |
| P4 目标写操作 | 已完成（四项写操作全部真机验证） | `data/ws/GoalPort.kt`、`HarnessGoalPortViewModel.kt`、`ChatStatsSheet` 目标区可操作 |
| P5 真机验收 + 发版 | **已完成**（2026-10-29/30，桥 v2.8.0 → v2.8.2） | V2080A 对真桥跑通 7.2 各步；P2/P3/P4 见 §7.1.2；v2.8.2 补齐 HITL 与 `command-execute`（§7.1.3）并修掉 sessions 裸数组解码 bug（§7.1.4）。两仓均已发版：bridge v2.8.2（npm + 双站 Release）、companion v2.2.58（双站 Release） |

**2026-10-29 晚补的两条接收侧缺口**（不是新阶段，是 P1/P2 留给后续阶段的账）：

| 缺口 | 现象 | 补法 |
|---|---|---|
| 桥的背压告知没人听 | 桥在队列溢出后补发 `connection-resync-required`，而 `onWsUnmatchedFrame` 只认 `event`/`error`，这帧落到末尾被静默忽略 —— 带 seq 的事件丢了自己能发现（`seqVerdict` 跳号），**不带 seq 的流式 delta 丢了看不出来** | `WsFrames.RESYNC_REQUIRED` + `resyncDroppedOf`/`resyncReasonOf`；`onResyncHint` 清 `lastEventSeq` 基线并重取当前会话历史 |
| 429 被当成普通故障 | `isDefinitiveUpgradeRejection` 不含 429 是对的（不能判死），但它会落到默认退避，**忽略 `Retry-After`**；桥以前没这个上限所以只是理论问题，2026-10-29 桥真会回 429 了 | 新增 `data/ws/RetryAfter.kt`：`retryAfterMillis`（只认 delta-seconds，夹 1 s..60 s）+ `reconnectWaitMillis`（与退避取较大者）；`onFailure` 里单列 429 分支 |

两条都不改 `ChannelState` 的四态语义：429 仍进 `WsDown`（重连中），`RestOnly` 依然只留给「这条通道不存在」。

**2026-10-29 晚补齐的三条「最后一米」**（此前都以「桥侧没有对等面」为由留在 REST，
复核代码后发现其中三条其实是桥侧只差一点点）：

| 原结论 | 复核结果 | 落地 |
|---|---|---|
| 首屏历史必须走 REST（WS 的 `history` 帧丢 `projections`） | 桥 `handleHistory` 只解构 `{events, hasMore}`，而 `chat-adapter` 明明返回了 `projections` —— 属「最后一米」 | 桥：首屏（无 `beforeSeq`）的应答附 `projections`，翻页不带（那帧有 1 MiB 上限）；App：`WsSessionPort.history` 读 `data["projections"]`，`AdaptiveSessionPort` 不再按 `beforeSeq == null` 分叉 —— **「打开会话」这条最热的路现在真走 WS 了** |
| 新建会话必须走 REST（WS 帧只认 `rootId`+`cwd` 且写死 `reuseBlank:false`） | 属实，但可以补齐而不是放弃 | 桥：`session-create` 新增 `workspaceId` 寻址（内部 `createSessionInWorkspace`）与 `agentPreset`/`reuseBlank`；App：`WsSessionPort.createSession` 发该帧并按写操作回落（帧没发出去才回落，避免建出两个会话） |
| 切 agent 预设必须走 REST（「桥无对应帧」） | **结论错误** —— `ws-presets.ts:35` 有 `select-agent-preset`，应答 `sent` + `data.type` | App：`SessionPort.selectAgentPreset` + WS 实现；`sent` 判别收敛成 `NON_MESSAGE_SENT_TYPES` 一张表（生产者从 3 个变 5 个，继续堆 `!=` 必漏） |

继续留 REST 的三条及理由（**不是漏做**）：归档会话列表（`session-archives` 全仓无生产者）、
读附件字节（WS 的 `attachment` 帧是上传语义，下载要走 `file-download-*` 分片状态机）、
待审批/待提问清单（App 只对当前会话发一次、不轮询，换通道省不了请求）。

### 7.1.1 P2 真机验收记录（2026-10-29，V2080A + 桥 v2.8.0）

探活 1.5 s → 快照 3.5 s → `hello` 到达 → WS 接管事件流，界面顶栏绿点「已连接」，
会话列表 104 条，进会话正文实时更新。关键日志（`files/diag.log`）：

```
#8  WS 建立 WS 传输：endpoint=http://192.168.1.126:3090 先 REST 后 WS，等 hello 判决
#9  WS 事件通道留在 REST：state=WsDown(reason=尚未建立连接)
#10 WS 忽略 REST 流的断开回调：WS 正接管事件通道
#12 WS 事件通道切到 WS：已关闭 REST /api/v1/events 流
```

**这一步抓到一个 P2 自带的回归**：交接瞬间 `EventSubscription.close()` 无条件回调
`connection(false)`，把刚置上的 `eventsConnected = true` 立刻改回 false，顶栏一直是「已断开」。
修复见 `handleEventsConnection` 的守卫与 `PITFALLS.md` §两个事件通道交接时…。

### 7.1.2 P3/P4 真机验收记录（2026-10-29，V2080A + 桥 v2.8.0）

**判据一律取「桥侧回读」**，不看界面自己说成功 —— 界面上写成功而内核没动，正是本项目反复踩的坑。

P4（目标写操作），全部在真机会话上点按钮、再用裸 WS 发 `goal` 帧回读：

| 动作 | 点击后界面 | WS 回读 |
|---|---|---|
| 暂停 | `阶段 paused · rev 2 · 轮次上限 10`，按钮变「恢复」 | `phase: "paused"`，`revision: 2` |
| 恢复 | `阶段 active · rev 3 · 轮次上限 10`，按钮变「暂停」 | `phase: "active"`，`revision: 3` |
| 编辑（轮次上限 10 → 12） | `rev 4 · 轮次上限 12` | `maxGoalRounds: 12`，`revision: 4` |
| 清除 | 目标区整块消失，无残留控件 | `goal: null` |

`revision` 每次 +1 说明 `ref` 的 compare-and-set 真的带过去了（桥侧拿旧 revision 会拒）。
编辑对话框的「正文留空 = 不改」也验了：只改轮次上限时正文原样保留。

P3（模型域）：

| 动作 | 结果 |
|---|---|
| 读（打开「会话配置 → 模型」） | 分组、provider、上下文容量、思考强度齐全 —— 数据来自 `models` 帧 |
| 写（`deepseek-flash` → `deepseek-v4-pro`） | WS 回读 `current.model` 从 `deepseek-flash` 变为 `deepseek-v4-pro`，思考强度 `high` 保持 |

这一条是 P3 里风险最高的（`sent` 是 `message`/`unsubscribe`/`select-model` 三个生产者共用的 kind），
真机通过说明双向互斥的 matcher 写对了：既没把在途发消息确认掉，也没让模型切换被吞。

**2026-10-29 当晚标注的两项「未真机验证」，在 2026-10-30 桥 v2.8.2 上补齐**（见 §7.1.3）。

> 本次真机对的是**已发布的**桥 v2.8.0 —— 它还不回显 requestId，所以配对走的是
> `WsRequestRouter` 的按 kind 兜底路径（清单第 3 步）。桥侧统一回显已在
> `dsh-remote-bridge` 实现（未发版），发版后第 5 步的 `requestId 快路` 会自然生效，
> 客户端无需改代码 —— 这是 §3.3「不靠版本号猜，靠回帧里那个字段在不在」的直接兑现。

### 7.1.3 v2.8.2 补齐 HITL / command-execute 真机验收（2026-10-30，V2080A + 桥 v2.8.2）

升级宿主到 `dsh-remote-bridge@2.8.2`（该版本修了审批决策恒 409，见桥仓
`PITFALLS.md`「把事件信封的外层 rpcId 当成结算句柄」），diag 里所有配对从
「按 kind 兜底」变成「**requestId 快路**」，证明统一回显在真桥生效、客户端零改动。

- **`command-execute`（App UI + 桥回读双重通过）**：App 发 `/permission read-only`，
  diag `requestId 快路 command-execute ← command-executed`；独立 WS 探针回读
  `{commandId, result:{kind:"success",text:"preset read-only"}}`，read-only/workspace-write/danger
  三档全部 success。**注意帧字段是 `line` 不是 `command`**（用错桥回 `INTERNAL_ERROR`）。
- **`approval-response`（协议闭环通过，文件真落地）**：在只读会话触发内核提权 →
  真实审批卡（full-access）→ 取**真 approvalId**（薄事件信封里的 id 是事件 id 不是审批 id，
  从 REST 清单取）→ WS 应答 `approval-response {accepted:true}` + `approval-resolved`，
  随后放行的 `touch /tmp/dsh-appr5.txt` 真被创建。v2.8.1 同路径恒回
  `409 APPROVAL_NOT_PENDING`（桥 bug，与 App 无关，App 端帧编解码与 matcher 由单测钉住）。
  纯「手指点 UI 确认按钮」这一下在 v2.8.2 没重走：当时这台 vivo 的自动化注入唤不起搜狗 IME
  （浏览器地址栏可弹、DSH 框不弹，`mShowRequested` 恒 false，重启后仅首次 tap 聚焦），
  属测试环境输入法问题，非 App 回归 —— 待真人手指或换设备点一次即可闭环最后这一下。

### 7.1.4 v2.8.2 真机抓到并修掉的一个解码 bug：sessions 帧是裸数组（2026-10-30）

App UI「新建会话」成功后立刻报
`JsonDecodingException: Expected JsonObject, but had JsonArray … serialized body of …SessionList`。
根因：**同一份会话列表，REST 包 `{items:[…]}`，WS `sessions` 帧的 `data.sessions` 直接是数组**，
而 `WsSessionPort.sessions()` 复用了 REST 的 `SessionList(items)` 模型去解 JsonArray。
修法：WS 侧改用 `ListSerializer(ChatSession.serializer())` 直接解数组（见
`PITFALLS.md`「同一份列表，REST 包 items、WS 直接给数组」），补了一条真形状回归用例。
单测此前漏掉是因为只手造了「自己以为的」回帧形状 —— 真桥才拆穿假设。

### 7.2 P2 真机自检清单

P2 的单元测试跑在 JVM + MockWebServer 上，**验证不了两件真机才有的事**：真实桥的 `hello` 时机，以及「老桥不回显 requestId」这一不对称。所以必须真机对真桥跑一遍。前置：`DiagLog` 需开启（`BuildConfig.DIAG_LOG` 或设置里打开），否则下面所有日志都不会出现。所有日志 tag 为 `DSH_DIAG`，类别字段统一是 `WS`，用 `adb logcat -s DSH_DIAG | grep " WS "` 过滤。

| # | 步骤 | 期望的 logcat 特征 | 判据 |
|---|---|---|---|
| 1 | 冷启动连桥（v2.8.0） | `WS 建立 WS 传输：endpoint=https://… 先 REST 后 WS，等 hello 判决` | 出现即说明 `connectWsTransport` 跑了；**没有**这行且出现 `WS 无设备令牌，不建 WS 通道，全量走 REST` 则是没令牌，属预期的 REST-only |
| 2 | 等 `hello` 到达 | `WS 事件通道切到 WS：已关闭 REST /api/v1/events 流` | 这行是 `ChannelState` 变 `usesWs` 的**唯一可信标志**。它出现之前的所有请求都走 REST，属预期（`_state` 初值是 `WsDown`） |
| 3 | 切换会话 / 下拉刷新 | `WS 配对：按 kind 兜底 sessions ← sessions` 或 `… history ← history（该桥未回显 requestId）` | **这是「WS 真接管」的铁证**。v2.8.0 不回显 requestId，所以走 kind 兜底是正确行为 |
| 4 | 发一条消息 | `WS 配对：按 kind 兜底 message ← sent（该桥未回显 requestId）` | 消息送达即说明 `content` 数组 + `locale` 的载荷被桥接受 |
| 5 | （桥修好回显后）同 3、4 | `WS 配对：requestId 快路 sessions ← sessions` | 快路日志出现即说明自适应切到了正确路径，**无需改代码、无需配置** |
| 6 | 断开桥（或关掉桥进程） | `WS 事件通道留在 REST：state=WsDown(reason=…)` | 事件流自动退回老 `/api/v1/events`，聊天仍能收推送 |
| 7 | 桥恢复 | 再次出现 `WS 事件通道切到 WS` | 退避重连生效，回到 `WsReady` |
| 8 | 用 v2.7.2（无 `/ws/mobile`）连 | `WS 事件通道留在 REST：state=RestOnly(…)` | 退化等价：此后行为与改造前逐字节一致，且**不再重试** WS |

**关于「第一个走 WS 的请求是哪条」**：冷启动时 `loadSnapshot` 里的 `sessions()` 是最先发出的会话请求，但它发出时 `hello` 还没到、`ChannelState` 仍是 `WsDown`，`usesWs == false`，所以那一次**实际走 REST**。真正第一条走 WS 的是 `hello` 之后的下一次 `sessions`/`history`（分页），或者事件订阅帧 `subscribe`。这不是缺陷 —— 设计上「连上 ≠ 能用」，`MobileWsTransport.onOpen` 刻意不置 `WsReady`，必须等桥推 `hello` 并核对能力位。

### 7.3 并发正确性：REST 与 WS 混跑不会互相覆盖

`WsDown → WsReady` 那一刻 `reconcileEventChannel()` 会关掉 REST 事件流改订阅 WS，而此刻**可能正有一条 REST `history` 在途**。三道保护叠加，不存在「旧数据覆盖新数据」：

1. `launchOperation`（`HarnessOperations.kt:51`）在调用点领 `requestGate.current` 的代际，回来时 `isCurrent` 不成立就**结果与错误一并丢弃**；`selectSession` 首行 `requestGate.invalidate()`，换会话即让所有在途请求作废。
2. `scheduleChatRefresh` 的回填本来就比对 `selectedSessionId == id`。
3. `refreshHistory` 在 P2 补了同样的守卫（`if (mutableState.value.selectedSessionId != id) return@withClient`）—— 原来它只靠代际，换通道后请求可能更早回来，窗口更明显，故显式补上。

WS 与 REST 的响应顺序不保证（桥侧 `handleMessage` 是 `void` 调用、并发处理），所以**任何回填都必须带 sessionId 或代际判据**，不能假设「谁先发谁先回」。
