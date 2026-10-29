# dsh-remote-bridge v2.7.x 新功能 → dsh-companion 对齐需求

**生效时间**：2026-10-28（对标完成后才能写码；见 AGENTS.md §3/§4）
**对标来源**：`dsh-remote-bridge` v2.7.0（3f38fbd）/ v2.7.1（1991147）/ v2.7.2（48c559a）
**对齐对象**：`dsh-companion`（Android，当前 v2.2.55，versionCode 10080）
**状态**：需求块完成；实施按任务板推进。

---

## 0. 背景

`dsh-remote-bridge` 是把 DSH 内核私有 remote 协议翻译成版本化 REST 的桌面插件。
`dsh-companion` 是它的安卓客户端，**只消费公共 `/api/v1` REST**（AGENTS.md 强制，不碰 `/ws/mobile` 网关实现）。
因此「桥的新功能」到「伴侣的工作」的映射，永远是『桥端靠谱，客户端验证/补齐』。

桥这几版的客户端视角只有两件事：

1. **v2.7.0 4 项能力提升**（直接影响伴侣）：
   - `model.contextWindow / maxTokens` — 模型行显示容量；
   - `POST /chat/sessions` 的 `reuseBlank` — 空白会话复用；
   - 插件清单侧栏 / 设置页插件卡片 — 服务端 UI，伴侣不会感知。
2. **v2.7.1 纯修复**：`sessionController` 返回 `{agent}/{error}` 包装，消费侧必须解包（文件上传、agent preset 选择两条路径挂了）——但该修法在**桥端**完成，伴侣再无消费变化。

真正还没在伴侣里落地的，是桥 **v2.6.0 系列引入的移动端网关能力**（`f7b5c44` 等）：
事件总线、任务/目标/统计、智能体团队、官方语音、定时任务、分块文件通道、命令选项/选择。
其中「事件总线主动推送（WS 优先）」伴侣已有 `connectEvents` WebSocket 消费，**已对齐**。

---

## 1. 对标表（bridge → companion）

| # | 桥（新功能 / commit） | 伴侣影响 | 当前状态 | 需求单 |
|---|---|---|---|---|
| 1 | 模型上下文容量 `contextWindow/maxTokens`（v2.7.0） | 已完成 v2.2.55（`ModelCapacityLabel.kt`、`ModelRow`） | **待发版** | — |
| 2 | `reuseBlank`（v2.7.0） | 已完成 v2.2.55（`createSession(..., reuseBlank)`） | **待发版** | — |
| 3 | 插件清单侧栏（v2.7.0） | 服务端侧栏，伴侣不感知 | N/A | — |
| 4 | 设置页插件卡片（v2.7.0） | 服务端 UI | N/A | — |
| 5 | 事件总线主动推送（847de6a） | 伴侣已有 `events` WebSocket 消费 | **对齐** | — |
| 6 | 任务/目标/统计投影（d854c05 + `ws-goals.ts`） | **缺失**：无目标/任务/统计入口 | 待做 | `T-01` |
| 7 | 智能体团队（f1dd82b + `ws-team.ts`） | **缺失**：无团队入口 | 待做 | `T-02` |
| 8 | 官方语音（ws-speech） | 已完成（未发版 v2.2.57）：`SpeechInput.kt` / `SpeechRecorder.kt` + `DshClientBff` 三个语音端点 | **待发版** | `T-03` |
| 9 | 定时任务（ws-schedule） | 已完成（未发版 v2.2.57）：`ScheduleSheet.kt` + `HarnessSchedulesViewModel.kt` + `/chat/schedules` CRUD | **待发版** | `T-04` |
| 10 | 命令选项 / 命令选择（ae4b6ab） | **缺失**：命令无「选项/选择」形态 | 待做 | `T-05` |
| 11 | 文件分块下载/上传（d8d8a8b + `ws-file-transfer.ts`） | 已有 `downloadFile`；分块上传已完成（未发版 v2.2.57）：>4 MiB 走 `attachments/uploads`，进度/续传/409 缺块补传 | **待发版** | `T-06` |
| 12 | 连接握手细节 v0.11.1（9a02772） | 服务端握手，伴侣不感知 | N/A | — |
| 13 | 问题取消 / 文件 resolve（34afa0e） | 已有 `pendingQuestions` + 附件 resolve | **对齐** | — |
| 14 | 文档回填（e32c088） | 服务端 | N/A | — |
| 15 | host/permission 帧（6aaf8f3） | 伴侣已有权限处理 | **对齐** | — |
| 16 | `directory-create`（d920ca3） | 已有 `createEntry(directory=true)` | **对齐** | — |
| 17 | fork + workspace-create（a7ab580） | 已有 `forkSession` / `createChatWorkspace` | **对齐** | — |
| 18 | subscribe 会话白名单过滤（730ceab） | 已有会话白名单 | **对齐** | — |
| 19 | 版本同步（v2.7.2） | 关于页/设置页版本号 + 建桥前置版本提示 | 待做 | `T-07` |

---

## 2. 开放需求单（实施顺序）

### T-01 目标/任务/统计（源于 `ws-goals.ts` / `d854c05`）
- 数据面：`GET /tasks` / `GET /goals` 若桥提供；**当前桥只在 `/ws/mobile` 投影读**，REST 暂无，故先做「会话统计」展示 + 投影头（`context-usage`）。
- 验收：会话页有目标/任务卡片；统计波形（上下文占用/成本）可见；目标写操作路线在文档 `docs/post/GOALS.md` 定义（REST 由桥扩展后接上）。

### T-02 智能体团队（源于 `ws-team.ts`）
- 数据面：`GET /chat/agent-presets` 已有；**团队粒度（子代理、角色分工）仍缺**。
- 验收：设置/会话页可创建「团队」并选定子代理；团队模型与单代理一视同仁。

### T-03 官方语音（源于 `ws-speech.ts`）
- 数据面：`GET /settings/speech`（官方音色池）若桥提供；否则给「语音合成」入口。
- 验收：会话内「转语音」可选官方/自定义音色。

### T-04 定时任务（源于 `ws-schedule.ts`）
- 数据面：`POST /schedules` 若桥提供；否则走「定时执行」主流程（任务模板 + 触发器）。
- 验收：设置页有定时任务全局表，可创建/启用/删除。

### T-05 命令选项 / 命令选择（源于 `ae4b6ab`）
- 伴侣 `executeCommand` 是『行输入』，桥新增「选项/选择」帧 → 需要「命令卡片」或「输入补全」形态。
- 验收：在 ChatSheets 能发起「选项选择」类命令，并获得结果。

### T-06 文件分块上传（源于 `ws-file-transfer.ts`）
- 伴侣 `uploadAttachment` 是整包一次 POST；桥新增分块上传（256 KiB 块 + 序号）→ 补 `sendFile` 入口（大文件分块、多段合并）。
- 验收：`FilesScreen` 可选「分块上传」，大文件断点续传不丢。

### T-07 版本同步
- 关于页显示 bridge version `2.7.2`；连接页提示「需桥 ≥ 2.7.0」。
- 验收：`settings` 页面版本文本正确。

---

## 3. 发版（v2.2.56）要点

继 v2.2.55 后，一次把「容量显示 + 空白会话复用」推到用户手里的小版：

- `docs/project/CHANGELOG-DEV.md` 追加「v2.2.56 · 发布」：
  - 内容：模型行容量 + `reuseBlank` 正式发版。
  - 证据：`ModelCapacityLabelTest` 7 例、docsCheck 双向一致、lintDebug 0 error、单测 69 类/634 用例/0 失败。
  - 门禁：真机 V2080A 一条：模型行容量字符无溢出，可手敲 `reuseBlank` 复用空白会话。
- 桥对照：`dsh-remote-bridge` v2.7.0 的 `POST /chat/sessions` 至少一台真机连通后才算 100%。
