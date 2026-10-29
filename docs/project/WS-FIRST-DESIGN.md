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
