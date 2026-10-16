# 工具审批 / ask_user_question 双链路端到端调研

调研对象：`/media/wangke/OFFICE/workspace/dsh-companion`
源码根：`app/src/main/java/io/github/hakunm/deepseekharness/`

行号均对应当前工作区文件内容。

---

## 0. 先纠正线索里的三处偏差

| 线索 | 实际 |
|---|---|
| `data/HarnessInteractionsViewModel.kt` | 在**包根**，不在 `data/`：`app/src/main/java/io/github/hakunm/deepseekharness/HarnessInteractionsViewModel.kt`（227 行） |
| `data/HarnessViewModel.kt` 约 470-488 | 实际路径 `HarnessViewModel.kt`（包根，663 行）；470-488 行内容一致 |
| `HarnessViewModel.kt` 里 `refreshPendingQuestions` | 定义**不在**这里，在 `HarnessInteractionsViewModel.kt:131`。`HarnessViewModel.kt:297` 的 KDoc 交叉引用指错了类（扩展函数的宿主类叫 `HarnessViewModel`，但注释读者会以为方法定义在该文件内） |

---

## 1. 协议层（客户端侧）

Base URL：`normalizeEndpoint`（`data/DshClient.kt:555-562`）强制以 `/api/v1` 结尾。所有路径经 `pathUrl`（`DshClient.kt:526-528`）逐段 `addPathSegment` 编码。

### 1.1 审批

| 操作 | 位置 | 方法 + 完整路径 | 请求体 |
|---|---|---|---|
| 拉待审批 | `DshClient.kt:281-282` `pendingApprovals` | `GET {endpoint}/chat/sessions/{sessionId}/approvals` | 无 |
| 提交决定 | `DshClient.kt:284-290` `decideApproval` | `POST {endpoint}/chat/sessions/{sessionId}/approvals/{approvalId}/decision` | `{"outcome":"allowed-once"}` 或 `{"outcome":"rejected"}` |

- 响应包封：`PendingApprovalList(items)`（`DshClient.kt:621`），`decideApproval` 走 `postUnit`（`DshClient.kt:479-482`）不读响应体，只 `ensureSuccess`。
- 请求体 DTO：`ApprovalDecision(val outcome: String)`（`DshClient.kt:623`）。`allowOnce=true → "allowed-once"`，`false → "rejected"`（`DshClient.kt:287`）。**没有「永久允许」这个取值**。
- `PendingApproval` 字段（`data/Models.kt:105-113`）：`id, sessionId, toolName, reason?, detail?, risk, requestedAt`。

### 1.2 提问

| 操作 | 位置 | 方法 + 完整路径 | 请求体 |
|---|---|---|---|
| 拉待答问题 | `DshClient.kt:299-300` `pendingQuestions` | `GET {endpoint}/chat/sessions/{sessionId}/questions` | 无 |
| 提交答案 | `DshClient.kt:308-314` `answerQuestions` | `POST {endpoint}/chat/sessions/{sessionId}/questions/{callId}/answer` | `{"answers":[{"id":…,"selected":[…],"custom":"…"}]}` |

- 响应包封：`QuestionSetList(items)`（`DshClient.kt:622`）；请求 DTO `QuestionAnswerRequest(val answers: List<QuestionAnswer>)`（`data/Models.kt:191`）。
- `QuestionAnswer(id, selected, custom="")`（`data/Models.kt:177-190`）：`custom` 默认空串，kotlinx 默认不编码等于默认值的字段 → 未填自由文本时请求体里**没有** `custom` 键。
- `PendingQuestionSet`（`data/Models.kt:152-172`）：`callId, state, questions, viaBridge=false`。

### 1.3 那段警告注释的完整解读

原文两处，`DshClient.kt:292-297`：

```
/**
 * 当前会话里待回答的问题（`ask_user_question`）。
 *
 * ⚠️ 服务端在这条路上会走 `resolveAgent(..., resume)` —— 对**冷**会话调它会把那边的
 * Agent 拉活。所以调用方**只能**传当前打开的那个会话，绝不能拿它去遍历会话列表
 * （见 [io.github.hakunm.deepseekharness.HarnessViewModel.refreshPendingQuestions]）。
 */
```

含义拆成三层：

1. **机制**：`GET /questions` 服务端内部不是查一张静态表，而是先 `resolveAgent(sessionId, { resume: true })` 拿一个活的 Agent 句柄，再从那个句柄读 pending questions。`resume=true` 的语义是「resolve 不到就地把它启动」。
2. **后果**：对**冷**会话（Agent 已停、上一轮已结束）调用这个端点，副作用是那个会话在服务端被重新拉活 —— 一次纯查询变成了「唤醒会话」。
3. **它防的写法**：拿 `sessionId` 去遍历 `sessions` 列表批量拉（比如想给会话列表每行显示「有待答问题」）。100 个会话 = 100 次误唤醒。同一条警告在 `HarnessInteractionsViewModel.kt:98-99` 和 `:120-129` 又用散文重述了一遍。

「它指向的文档」：注释里唯一的指引是代码交叉引用 `[HarnessViewModel.refreshPendingQuestions]`，**不是** docs/ 目录里的文档。`docs/` 全仓 grep `resolveAgent` **零命中**——这条知识只存在于源码注释里。

⚠️ 一处内部不一致：该 KDoc 的链接指向 `HarnessViewModel.refreshPendingQuestions`，但函数实际在 `HarnessInteractionsViewModel.kt:131`（`HarnessViewModel` 的扩展函数）。链接能解析（扩展函数可作成员引用），但按链接跳转的读者会先在 `HarnessViewModel.kt` 里找不到它。

---

## 2. 事件流（WebSocket 帧）

连接：`DshClient.kt:425-428` `events()` → `eventsUrl()`（`:430`）= `{endpoint}/events`。断线指数退避重连 `ReconnectingEvents`（`:566-616`，`1L shl retries` 上限 30s）。

分发全部在 `HarnessViewModel.kt:356-491`（`connectEvents` 的 listener）。前置门槛：`event.type.startsWith("chat.")`（`:372`），`file.*` 走 `:490`。

### 2.1 审批帧

| 帧 | 位置 | 收到后调用 |
|---|---|---|
| `chat.approval.requested` | `HarnessViewModel.kt:455-459` | `chatSessionRefreshPending = true` + `scheduleChatRefresh()`；**仅当** `sessionId == selectedSessionId` 才 `refreshPendingApprovals()` |
| `chat.approval.resolved` | 同上（同行两个字符串合写） | 同上 |

另有这两个帧被 `HarnessEventRules.kt:165-172` 的 `QUESTION_REFRESH_EVENTS` 收录（也顺带触发一次问题刷新）：`chat.approval.requested`、`chat.approval.resolved` —— 见 `HarnessViewModel.kt:470-472`。

### 2.2 提问帧

**直接触发 `refreshPendingQuestions()` 的专属帧**（`HarnessViewModel.kt:477-488`）：

| 帧 | 行 | 处理 |
|---|---|---|
| `chat.userQuestions/requested` | `:478` | `refreshPendingQuestions()`（一次 GET） |
| `chat.userQuestions/answered` | `:479-487` | `dataString("callId")` → `removeQuestion(questions, callId)` 就地摘除，不发网络请求 |
| `chat.userQuestions/dismissed` | 同上（同分支） | 同上 |

`removeQuestion` 定义在 `HarnessEventRules.kt:184-189`；纯函数、callId 不匹配时原样返回。

**间接触发**（`HarnessViewModel.kt:470-472`）：
```kotlin
if (event.type in QUESTION_REFRESH_EVENTS || isToolResultFrame(event.type, event.data)) {
    refreshPendingQuestions()
}
```
- `QUESTION_REFRESH_EVENTS`（`HarnessEventRules.kt:165-172`）= `chat.message.committed`, `chat.turn.start`, `chat.turn.end`, `chat.session.status`, `chat.approval.requested`, `chat.approval.resolved`。KDoc `:159-164` 明说**刻意不含** `chat.message.delta`（跟着 token 流刷新等于轮询）。
- `isToolResultFrame`（`HarnessEventRules.kt:203-207`）：只认 `type == "chat.session.event"` 且 `data.eventType == "tool/result"`。理由见 `:191-202` —— 提问从 `open` 转 `continued` 的判定落在内核那条 tool/result 上，这一帧到达就是确切信号。
- 另有两处单独调 `refreshPendingQuestions()`：`:394`（`chat.turn.end` 且该会话已不在运行，兜底）、`:449`（`chat.turn.failed` 且是当前会话，撤卡片）。

### 2.3 重连 / 亮屏补拉

- `HarnessViewModel.kt:492-501`（`connection = { connected -> … }`）：连上且有 `selectedSessionId` 时依次 `scheduleChatRefresh()` → `refreshPendingApprovals()` → `refreshPendingQuestions()` → `refreshCommands()`。
- `resumeEvents()`（`:334-351`，亮屏）：`:349-350` 单独调这两个刷新，理由见 `:342-348` 注释（熄屏期间事件流关着，卡片不会自己出现）。

---

## 3. ViewModel 处理链（HarnessInteractionsViewModel.kt）

文件 KDoc `:85-100` 给了一张审批/问答对照表：谁在等（内核阻塞 vs Agent 已继续跑）、界面占位（顶掉输入区 vs 不顶）、防重手段（本地 `Deciding` 态 + 插件 vs `answeringCallId` + 已答集合 + 插件 409）。

### 3.1 `refreshPendingApprovals()` — `:101-118`

1. `:102` `val api = client ?: return` — 未连接直接返回
2. `:103` `val id = selectedSessionId ?: return` — **只认当前选中会话**，这是警告落地处
3. `:104-105` `viewModelScope.launch` + `runCatching { withContext(IO) { api.pendingApprovals(id) } }`
4. `:106` **会话切换守卫**：`if (selectedSessionId != id) return@launch` —— 期间切走了就丢弃结果
5. `:108` 成功 → `update { copy(approvalState = approvals.toApprovalState()) }`；`toApprovalState`（`HarnessEventRules.kt:156-157`）空列表→`None`，否则→`Pending(list)`
6. `:109-116` 失败 → `approvalState = ApprovalUiState.None`（**清空**）+ `error = …`；但 `error.code == "RATE_LIMITED"` 时 error 置 `null`（`:113`）

### 3.2 `refreshPendingQuestions()` — `:131-142`（KDoc `:120-130`）

1. `:132-133` 同上两个 early return
2. `:134-135` launch + `runCatching { IO { api.pendingQuestions(id) } }`
3. `:136-137` **会话切换守卫**（注释明写「期间用户可能已经切走」）
4. `:138-140` 成功 → `update { copy(questions = sets.filter { it.state == QUESTION_STATE_CONTINUED }) }`
5. **失败路径整个不存在** —— `result.onSuccess` 之后没有 `.onFailure`。KDoc `:126-129` 给了两个理由：
   - **失败刻意静默**：这条路会挂在全轮 turn 事件上，插件版本旧（404）或一次网络抖都会失败，塞进 snackbar 等于每轮弹一次
   - **列表保留原样不清空**：已显示的卡片不因一次刷新失败消失；清空只发生在换会话时（`HarnessSessionsViewModel.kt:126`）

这就是 `refreshPendingQuestions` 的三道防护：① 只对 `selectedSessionId`；② 返回后比对会话 id；③ 失败不落状态（对比 `refreshPendingApprovals` 失败会清空 `approvalState`——两者的失败语义**故意不一致**）。

### 3.3 `answerQuestions(callId, answers)` — `:150-177`

KDoc `:144-149`：「防重复提交两道闸：本地 `answeringCallId` 非空时忽略后续点击，已答过的 callId 也不再发；插件的 409 是最后一道防线」。

1. `:151-152` early return（无 client / 无选中会话）
2. `:153` `if (answeringCallId != null) return` — **闸 1：全局单飞**（一次只提交一组）
3. `:154` `if (callId in answeredCallIds) return` — **闸 2：已答集合**
4. `:156` `update { copy(answeringCallId = callId, error = null) }` — 进入提交态
5. `:157-159` `api.answerQuestions(id, callId, answers)` on `Dispatchers.IO`
6. `:160` 会话守卫
7. `:161-172` 成功 → `answeringCallId = null` + `answeredCallIds + callId`，然后 `:171` 再 `refreshPendingQuestions()` 问一次服务端（注释 `:169-170`：答复被吃掉则卡片消失，还在则已转「已回答」）
8. `:173-175` 失败 → `answeringCallId = null` + `error = describeAnswerFailure(error)`

`describeAnswerFailure` — `:185-197`：

| 条件 | 文案资源 | strings.xml 行 |
|---|---|---|
| `code == "QUESTION_REPLY_QUEUED"` | `R.string.answer_already_queued`「这组问题你已经回答过了，答复正在排队」 | `:152` |
| `code == "QUESTION_NOT_PENDING"` | `R.string.answer_not_pending`「这组问题已经不需要回答了」 | `:153` |
| `status == 403` 或 code 含 `FORBIDDEN`/`SCOPE` | `R.string.answer_no_write`「这台设备没有 chat.write 权限，无法提交回答」 | `:154` |
| 其他 | `R.string.answer_failed`「提交失败：%1$s」，填 `messageOf(failure)` | `:155` |

KDoc `:179-184` 解释为什么要分这两类 409：「答复已经在队列里（你答过了）」与「问题已经不作答（这条作废了）」是完全不同的事。

### 3.4 `decideApproval(approval, allowOnce)` — `:199-227`

1. `:200-201` early return
2. `:202` `if (approval.sessionId != id) return` — **审批专属的第二道守卫**（答题没有这步）
3. `:203-207` 取回 items：`Pending`/`Deciding` 取其 items，否则 `listOf(approval)`
4. `:209` `update { copy(approvalState = Deciding(approval, items), error = null) }`
5. `:210-214` 一次 IO 块里连做两件事：`api.decideApproval(id, approval.id, allowOnce)` 然后 `api.pendingApprovals(id) to api.sessions()`（串行，返回 pair）
6. `:215-220` 成功 → `approvalState = approvals.toApprovalState()` + `sessions = sessions`，并置 `chatSessionRefreshPending = true` + `scheduleChatRefresh()`
7. `:221-225` 失败 → 回到 `Pending(items)` + `error = messageOf(error)`（**回滚**，不是清空）

---

## 4. UI 层

文件：`ui/chat/ChatApprovals.kt`（585 行）。文件 KDoc `:213-223`。

### 4.1 `ApprovalPanel` — `:257-380`

签名 `:257-265`：`approval: PendingApproval, count: Int, deciding: Boolean, canWrite: Boolean, onReject: () -> Unit, onAllowOnce: () -> Unit`

- `:266` `fullAccess = approval.risk == "full-access"`
- `:267-268` `showRiskDialog` / `acknowledged`，都用 `rememberSaveable(approval.id)` 打 key
- 容器 `:269-275`：`Surface`，`shape=medium`，`color=surfaceContainerLow`，`border = BorderStroke(1.dp, fullAccess ? tertiary : outlineVariant)`，`shadowElevation=3.dp`，外边距 `padding(horizontal=8.dp, vertical=4.dp)`

显示字段：

| 字段 | 行 | 表现 |
|---|---|---|
| `approval.toolName` | `:287-288` | `count > 1` 时渲染 `approval_count`「%1$s · 共 %2$d 项待审批」，否则裸字符串 |
| `approval.reason` | `:295-297` | 非空才渲染，bodySmall / onSurfaceVariant |
| `approval.detail` | `:298-317` | 非空时先打标签 `approval_command_preview`「脱敏命令预览」，正文装在 `SelectionContainer`（可复制）里，`heightIn(max=144.dp)` + `verticalScroll` + `FontFamily.Monospace` |
| `approval.risk` | `:318-332` | `full-access` 才渲染一行 `WarningAmber` + `full_access`「请求完整系统访问权限」 |
| 图标 | `:279-283` | full-access → `WarningAmber`(tertiary)，否则 `Build`(secondary) |
| 抬头 | `:285` | `approval_required`「需要你的审批」 |

按钮（`:333-348`）：

- **拒绝** `OutlinedButton`，`:334` `enabled = canWrite && !deciding` → `onReject()`
- **允许一次** `Button`，`:337-347`，`:344` 同上 `enabled` 条件；`:338-343` 点击时若 `fullAccess` 则 `acknowledged = false; showRiskDialog = true`，否则直接 `onAllowOnce()`

二次确认弹窗 `:351-379`：`AlertDialog`，`icon=WarningAmber`，title=`confirm_full_access`，text=`full_access_warning` + `Checkbox`（`full_access_ack`「我已检查命令并了解完整访问风险」）；`dismissButton`=`close`；`confirmButton` 的 `Button` `:373` `enabled = acknowledged`，`:372` 点击才 `onAllowOnce()`。弹窗关闭一律 `showRiskDialog = false; acknowledged = false`（`:353`、`:366`）。

加载态：`deciding == true` 时抬头行右侧多一个 `CircularProgressIndicator(size 20.dp, strokeWidth 2.dp)`（`:293`），且两个按钮同时禁用。独立的加载态组件是 `ApprovalLoading()`（`:245-255`），由 `ChatDetail.kt:537` 在 `ApprovalUiState.Loading` 时渲染。

### 4.2 `QuestionCard` — `:434-585`

签名 `:434-441`：`set: PendingQuestionSet, answered: Boolean, submitting: Boolean, canWrite: Boolean, onSubmit: (List<QuestionAnswer>) -> Unit`

草稿状态 `:444-445`：
```kotlin
val selected = remember(set.callId) { mutableStateMapOf<String, Set<String>>() }
val custom = remember(set.callId) { mutableStateMapOf<String, String>() }
```
按 `callId` 打 key，KDoc `:442-443` 说明理由：换组从零开始，防上一组答案漏到下一组。

派生量 `:446-449`：
- `interactive = canWrite && !submitting && !answered`
- `complete = questions.isNotEmpty() && questions.all { isAnswered(it, selected[it.id], custom[it.id]) }`

容器 `:451-460`：与审批同款 `Surface`，但 `border` 用 `answered ? outlineVariant : primary`，KDoc `:431` 明说「抬头用主色而不是审批那个警告色：它要的是『等你回答』，不是『危险』」。

显示字段：

| 字段 | 行 | 表现 |
|---|---|---|
| — | `:465-471` | `answered ? Icons.Outlined.TaskAlt : Icons.Outlined.QuestionAnswer`，tint 对应 secondary / primary |
| 抬头 | `:473-483` | `question_waiting`「需要你的回答」/ `question_answered`「已回答」 |
| `set.questions.size` | `:484-490` | `size > 1` 才渲染 `question_count`「共 %1$d 道题」 |
| `question.header` | `:497-503` | 非空渲染，labelLarge / primary |
| `question.question` | `:504` | bodyMedium + `FontWeight.Medium` |
| `question.detail` | `:505-511` | 非空渲染，bodySmall / onSurfaceVariant |
| `question.options` | `:524-561` | 有选项：`multiSelect` → `Checkbox`(`:546`)，否则 `RadioButton`(`:548`)；整行 `Row` `:537-543` 包 `.clickable(enabled = interactive)`。每项渲染 `option.label`(`:551`) + `option.description`(`:552-558`) |
| 无选项分支 | `:512-523` | `OutlinedTextField`，label=`answer_your_own`「填写你的回答」，`minLines=1, maxLines=4`，`enabled = interactive` |
| `submitting` | `:492` + `:568-574` | 抬头行右侧 spinner；按钮行额外显示 `answer_submitting`「正在提交…」 |

选项切换逻辑 `:527-536`：单选时「再点一次已选中的就是取消」（`:532`，注释 `:530-531` 说明是为了让用户改主意，与网页版 radio 行为对齐不了）；多选走 `current - label` / `current + label`。

按钮区 `:566-582`：**`answered` 为 true 时整块不渲染**（`:566`）。否则右对齐 `Row`，只有一个提交按钮 `:575-580`：
- `onClick = { if (complete) onSubmit(buildAnswers(set.questions, selected, custom)) }`
- `enabled = interactive && complete`
- 文案 `submit_answer`「提交回答」

纯函数两个，KDoc 指向单测 `QuestionAnswerTest`：
- `isAnswered` `:389-390`：`options.isEmpty() ? custom.isNotBlank() : selected.isNotEmpty()`
- `buildAnswers` `:402-420`：无选项 → `selected = emptyList()`, `custom = trim()`；有选项 → `selected = question.options.map { it.label }.filter { it in picked }`（**按选项原顺序**，`:396-397`），`custom` 用默认值空串（不编码该键）

### 4.3 排版位置关系 —— 核实结果

「只有审批顶掉输入区」这句话在代码里**成立**，有两处互证。

`ChatApprovals.kt:213-223` 文件 KDoc：
> 两者都占「消息区下方、输入区上方」那块地方，但**只有审批顶掉输入区**：审批是内核阻塞着等决定，用户此刻没别的事可做；问题是 Agent 已经继续跑之后的补答，拿掉输入框只会让用户连话都发不出去。

`ChatApprovals.kt:422-432` QuestionCard KDoc 重复同一口径。

实际布局（`ui/chat/ChatDetail.kt`，自上而下）：

| 顺序 | 行 | 内容 |
|---|---|---|
| 1 | `:491` | （`}` 闭合浮层 Box） |
| 2 | `:497-516` | **QuestionCard 区**：`if (state.questions.isNotEmpty())`，`Column` 内 `heightIn(max = 340.dp)` + `verticalScroll` + `padding(8.dp/4.dp)`，`questions.forEach` 各一个 `QuestionCard`。注释 `:492-496` 明写「位置与审批面板同一块地方…但**不顶掉输入区**」 |
| 3 | `:520` | `ChatThinkBar(liveThinking)` |
| 4 | `:521-556` | **`when (val approvals = state.approvalState)` —— 这里同时决定输入区生不生** |

第 4 段的 `when` 五个分支（`:521-556`）：
- `ApprovalUiState.None` `:522-536` → 渲染 `Composer`（输入区）
- `ApprovalUiState.Loading` `:537` → `ApprovalLoading()`（**无输入区**）
- `is ApprovalUiState.Pending` `:538-547` → `approvals.items.firstOrNull()?.let { ApprovalPanel(…) }`（无输入区；空 items 时连面板都不渲染、也没有输入区）
- `is ApprovalUiState.Deciding` `:548-555` → `ApprovalPanel(deciding = true, onReject = {}, onAllowOnce = {})`（回调被传成空实现）

所以「顶掉输入区」的实现方式：**审批的 `when` 分支与 `Composer` 同层互斥**，问答是独立的一行 `if`，两者可同时存在（审批 + 问答同时在屏时，顺序是 问题卡 → 思考条 → 审批面板/输入区）。注释 `:494-496` 用了「顶掉」但代码实际是「互斥替换」，语义一致。

`Pending` 分支的 `canWrite` 判据 `:543`：`"chat.write" in state.device?.scopes.orEmpty()`，同样写在 `:552`。
`onReject = { viewModel.decideApproval(approval, false) }`（`:544`）、`onAllowOnce = { viewModel.decideApproval(approval, true) }`（`:545`）。
问答的 `onSubmit = { answers -> viewModel.answerQuestions(set.callId, answers) }`（`ChatDetail.kt:512`），`answered`/`submitting` 由 `:509-510` 从 `state.answeredCallIds` / `state.answeringCallId` 推导。

### 4.4 顺带：两处死 import

`ui/ChatScreen.kt:212-214` 还 import 着 `ApprovalPanel` / `ApprovalLoading` / `QuestionCard`，但全文件已无任何调用点（grep 全仓只剩这 3 行 import + 定义处）。拆分重构的遗留。

### 4.5 会话列表层的审批指示（不在 ChatApprovals.kt）

`ui/chat/ChatSessionPane.kt:475` 与 `ui/chat/ChatDetail.kt:603`：`if (session.pendingInteraction == "approval") ApprovalStatusLabel()`。
`ApprovalStatusLabel`（`ChatApprovals.kt:224-243`）= `WarningAmber` 图标 + `awaiting_approval`「等待审批」。
注意它依赖的是 `ChatSession.pendingInteraction`（服务端字段），与 `HarnessState.approvalState`（客户端拉取）是两套数据源。

---

## 5. 状态刷新时机

### 5.1 审批 `pendingApprovals`

**没有轮询，没有定时器。** 全部触发点：

| 触发 | 位置 |
|---|---|
| 切会话 | `HarnessSessionsViewModel.kt:139`（`selectSession` 内，先在 `:124` 置 `ApprovalUiState.Loading`） |
| WS 重连成功 | `HarnessViewModel.kt:496` |
| 亮屏恢复 | `HarnessViewModel.kt:349` |
| 审批事件帧 | `HarnessViewModel.kt:458` |
| 决定提交成功后（与 `api.sessions()` 同 IO 块） | `HarnessInteractionsViewModel.kt:213` |

注意：**`scheduleChatRefresh()` 里没有审批**（它只拉 `history` 和 `sessions`，`HarnessViewModel.kt:562`、`:581`）。审批是一条独立的 GET。

### 5.2 提问 `pendingQuestions`

同样**无轮询、无定时器**。全部触发点：

| 触发 | 位置 | 说明 |
|---|---|---|
| `refreshHistory()` 尾部 | `HarnessSessionsViewModel.kt:155` | KDoc `:152-154`：`refreshHistory` 只有两个调用点（切会话、顶栏刷新按钮），顶栏刷新是用户遇到「卡片该出现却没出现」时**唯一**的自救入口 |
| WS 重连成功 | `HarnessViewModel.kt:498` | |
| 亮屏恢复 | `HarnessViewModel.kt:350` | |
| `QUESTION_REFRESH_EVENTS` 命中 | `HarnessViewModel.kt:470-472` | 6 个结构性事件 |
| `tool/result` 帧 | `HarnessViewModel.kt:470-472` | |
| `chat.userQuestions/requested` | `HarnessViewModel.kt:478` | |
| `chat.turn.end` 且会话已停 | `HarnessViewModel.kt:393-395` | 工具结果帧那条路的兜底，注释 `:389-392` |
| `chat.turn.failed` 且当前会话 | `HarnessViewModel.kt:449` | |
| 答案提交成功后 | `HarnessInteractionsViewModel.kt:171` | |

`docs/project/PITFALLS.md:112` 概括为「刷新只挂四个入口：切会话 / 顶栏刷新 / WS 结构性事件 / WS 重连」——与代码一致（其余几个是同一类里的细分）。

### 5.3 与 400ms 去抖的关系

`scheduleChatRefresh()`（`HarnessViewModel.kt:533-598`）**不负责**审批和问答。它只做两件事：`api.history(id)`（`:562`）与 `api.sessions()`（`:581`）。

- 去抖窗口：`delay(400)` 起手（`:552`），循环收尾再 `delay(400)` 确认静默（`:594`），`:595-596` 无新标志则 `break`。KDoc `:537-551` 解释：旧实现是 1 秒轮询且每个事件帧都重新置位标志，流式期间等于每秒全量拉 history；现在是「安静 400ms 才拉一次，一轮只拉一次」。单飞靠 `if (chatRefreshJob?.isActive == true) return`（`:535`）。
- 事件分发末尾的 `else ->` 兜底分支（`:460`）只置 `chatRefreshPending`，**不**触发审批/问答 GET。
- 审批/问答的 GET 由各自的 `refresh*` 直接 `viewModelScope.launch`，**不走** 400ms 窗口、不受单飞限制。

唯一的耦合点：`decideApproval` 成功后自己置 `chatSessionRefreshPending = true` 并调 `scheduleChatRefresh()`（`HarnessInteractionsViewModel.kt:218-219`）——那是刷会话列表，历史/运行态由它顺带跟上。

结论：三条路（history 去抖流、审批 GET、问答 GET）互相独立。问答的刷新比 history 密（6 个结构性事件都能触发），是刻意的——KDoc `HarnessViewModel.kt:462` 写「待答问题的刷新靠事件驱动，**不引定时器、不轮询**：一条相关事件一次 GET」。

---

## 6. 失效处理（端不一致）

### 6.1 电脑端已答、手机卡片还在

| 通道 | 处理 | 位置 |
|---|---|---|
| WS `chat.userQuestions/answered` / `/dismissed` | 就地 `removeQuestion(questions, callId)`，不发网络请求 | `HarnessViewModel.kt:479-487` |
| 无 WS（断线） | 下次任意刷新入口的 GET 返回里已无该 callId → `copy(questions = …)` 自然覆盖 | `HarnessInteractionsViewModel.kt:138-140` |

**「反过来」的方向无处理**：问答**没有**任何一帧能凭空把一张卡推出来，只能靠 `requested` 帧或 9 个 GET 入口。

### 6.2 审批

`chat.approval.resolved` → `refreshPendingApprovals()`（`HarnessViewModel.kt:458`）→ 列表变空 → `toApprovalState()` 返回 `None`（`HarnessEventRules.kt:157`）→ 回到 `Composer`。无本地就地摘除逻辑。

### 6.3 409 的处理

**有，且成对。**

| code | 触发场景 | 处理 | 位置 |
|---|---|---|---|
| `QUESTION_REPLY_QUEUED` | 答复已在队列（用户答过了） | 文案 `answer_already_queued`；**不重试**，`answeringCallId` 已在 `:174` 清空 | `HarnessInteractionsViewModel.kt:186-187` |
| `QUESTION_NOT_PENDING` | 问题已不作答 | 文案 `answer_not_pending`；**不重试** | `HarnessInteractionsViewModel.kt:189-190` |

两处 409 都只翻成人话，**都不会把卡片从 `state.questions` 里摘掉**，也不会触发 `refreshPendingQuestions()` 去自愈。下一次任意刷新事件到达时 GET 才纠正。

对应的 403/scope 分支 `:192-194` → `answer_no_write`。

KDoc `DshClient.kt:302-307`（`answerQuestions` 上方）写明「插件在『答复已经在队列里』（`QUESTION_REPLY_QUEUED`）与『这个问题已经不作答』（`QUESTION_NOT_PENDING`）两种情况下回 409；调用方要把这两个 code 翻成人话」。

审批侧**没有** 409 专门处理：`decideApproval` 失败一律 `messageOf(error)`（`HarnessInteractionsViewModel.kt:223`），走 `localizedError`（`HarnessViewModel.kt:658-661`）按错误码查表，查不到保留英文原文。

### 6.4 换会话清场

`selectSession`（`HarnessSessionsViewModel.kt:116-146`）一次性清掉：`approvalState = Loading`（`:124`）、`questions = emptyList()`（`:126`）、`answeringCallId = null`（`:127`）、`answeredCallIds = emptySet()`（`:128`）。注释 `:125`「换会话就把问答状态清干净：上一组的草稿/已答标记留在界面上会张冠李戴」。

`answeredCallIds` 清空意味着跨会话后同一 callId 的「已回答」记忆会丢——但 callId 是单次工具调用 id，正常不会跨会话复用。

---

## 7. 测试

### 7.1 `data/PendingQuestionsTest.kt`（145 行，MockWebServer）

| 用例 | 行 | 断言 |
|---|---|---|
| `questionsEndpointDecodesEveryDocumentedField` | `:34-70` | `GET` + 路径 `/api/v1/chat/sessions/session%201/questions`（空格编码成 `%20`）+ `Authorization: Bearer device-token`；解出 `callId`/`state`/`id`/`header`/`question`/`detail`/`options`（`QuestionOption("A","甲")` 与 `QuestionOption("B", null)`）/`multiSelect=true`；JSON 里的 `intent` 与未知字段 `futureFieldFromANewerKernel` 被忽略 |
| `missingOptionalFieldsFallBackInsteadOfFailingTheWholeList` | `:72-91` | `state` 缺席 → `""`（因此不会被当 continued 渲染）；`header`/`detail` → null；`options` 空；`multiSelect` false |
| `answerEndpointPostsSelectedAndOmitsEmptyCustom` | `:93-123` | `POST` + 路径 `/api/v1/chat/sessions/s1/questions/call%201/answer`；body 含 `"selected":["A"]`、`"selected":[]`、`"custom":"自己写的"`，且**不含** `"custom":""` |
| `answerFailureSurfacesThePluginErrorCode` | `:125-144` | 409 + `{"error":{"code":"QUESTION_REPLY_QUEUED"}}` → `DshApiException`，`code == "QUESTION_REPLY_QUEUED"`（没退化成 `HTTP_ERROR`）、`status == 409` |

### 7.2 `ui/QuestionAnswerTest.kt`（117 行，纯函数）

`isAnswered`：
- `choiceQuestionNeedsAtLeastOneSelection` `:31-39` — 有选项时选中 ≥1 为 true；空选 false；**有选项的题不看 custom**（`:38`）
- `freeTextQuestionOnlyLooksAtCustom` `:41-49` — 无选项只看 custom；纯空白 `"   \n  "` 算未答

`buildAnswers`：
- `keepsEveryQuestionInOrderAndEchoesTheId` `:53-64` — 顺序与 `questions` 一致、`id` 原样回带、`selected`/`custom` 各自落位
- `selectedFollowsTheOptionOrderNotTheClickOrder` `:66-77` — `setOf("C","A")` → `listOf("A","C")`
- `unknownSelectionIsDropped` `:79-89` — 幽灵选项被丢弃
- `freeTextQuestionSendsEmptySelectedAndTrimmedCustom` `:91-102` — `selected` 空、custom trim
- `blankFreeTextBecomesEmptyStringSoItIsNotSerialized` `:104-116` — 纯空白 → `""`（序列化时不带该键）

### 7.3 `RemoveQuestionTest.kt`（5 个用例，中文函数名）

`摘掉指定的那一题` `:20`、`callId 对不上时原样返回` `:27`、`空列表不崩` `:34`、`重复 callId 一次摘净` `:41`、`viaBridge 默认是 false 且能解析` `:49`。
KDoc `:12` 引用的理由与 `HarnessEventRules.kt:174-183` 一致。

### 7.4 `ToolResultFrameTest.kt`（4 个用例）

`tool result 帧被认出来` `:31`、`同类型下的其它事件不认` `:37`、`别的事件类型一律不认` `:47`（显式断言 `isToolResultFrame("chat.approval.requested", frame("tool/result"))` 为 false `:51`）、`畸形输入不崩` `:55`。

### 7.5 `data/DshClientTest.kt` —— MockWebServer 覆盖的审批/问答端点

**只有审批，没有问答**：

`readsAndDecidesPendingApprovalWithoutPrivateRpcFields` — `:188-205`
- enqueue `APPROVALS_RESPONSE`，断言 `approval.id == "approval-1"`、`risk == "full-access"`、`detail == "echo [REDACTED]"`（`:195-197`）——测试名里的 "WithoutPrivateRpcFields" 指的是断言只读公开字段
- enqueue 202 空体调 `decideApproval("session/1", "approval-1", allowOnce = true)`
- 断言第 1 个请求路径 `/api/v1/chat/sessions/session%2F1/approvals`（`:200`，斜杠编码成 `%2F`）
- 断言第 2 个请求 `POST` + 路径 `…/approvals/approval-1/decision`（`:202-203`）
- 断言 body **逐字节等于** `{"outcome":"allowed-once"}`（`:204`）

问答端点在 `DshClientTest` 里**零覆盖**——单独由 `PendingQuestionsTest` 承担。

### 7.6 无测试覆盖的部分（无法确定是否有意）

- `HarnessInteractionsViewModel.kt` 的 4 个函数全部**无单测**（`refreshPendingApprovals` / `refreshPendingQuestions` / `answerQuestions` / `describeAnswerFailure` / `decideApproval`）。需要 Android `viewModelScope`，项目里没有对应测试夹具。
- `ApprovalPanel` / `QuestionCard` 的 Composable 本身无测试。
- `toApprovalState`（`HarnessEventRules.kt:156-157`）无独立测试。

---

## 8. 关于那条警告与 docs/

**完整原文**（`data/DshClient.kt:292-297`，逐字）：

```
    /**
     * 当前会话里待回答的问题（`ask_user_question`）。
     *
     * ⚠️ 服务端在这条路上会走 `resolveAgent(..., resume)` —— 对**冷**会话调它会把那边的
     * Agent 拉活。所以调用方**只能**传当前打开的那个会话，绝不能拿它去遍历会话列表
     * （见 [io.github.hakunm.deepseekharness.HarnessViewModel.refreshPendingQuestions]）。
     */
```

它指引的**不是** docs/ 里的文档，而是一个 KDoc 交叉引用。

`docs/` 目录下的相关文档：

- **`docs/project/PITFALLS.md:101-116`「ask_user_question 三态」** —— 讲的是内核三态（`open` / `continued` / 其他），「现在怎么办」段（`:110-113`）复述了同一条约束：「`refreshPendingQuestions` 只对当前 `selectedSessionId` 发 GET，返回后校验会话是否切走。**全仓无遍历、无定时器、无轮询**，刷新只挂四个入口」。**未提 `resolveAgent` / resume 副作用**，也没解释「为什么不能遍历」的技术原因。
- `PITFALLS.md:116` 的铁律另说：「别给 agent 抢答」，与本警告无关。

`grep -rn "resolveAgent" docs/` → **零命中**。
`grep -rn "resolveAgent"` 全仓只在源码注释里出现 3 处：`DshClient.kt:295`、`HarnessInteractionsViewModel.kt:99`、`:123`，加 `ui/ChatWebScreen.kt:513` 的一句转述。

结论：`resolveAgent(..., resume)` 会拉活冷会话这条约束**只存在于源码注释**，docs/ 里没有对应条目（`PITFALLS.md` 记的是「不能遍历」这个结论，不是「因为它会 resume」这个原因）。

---

## 9. 文档核对的其他出入

| 位置 | 说法 | 实际 |
|---|---|---|
| `DshClient.kt:297` | 链接指向 `HarnessViewModel.refreshPendingQuestions` | 函数在 `HarnessInteractionsViewModel.kt:131`（是 `HarnessViewModel` 的扩展函数，链接可解析但跳转有误导） |
| `ui/ChatScreen.kt:212-214` | import 三个 Composable | 三个都已迁到 `ui/chat/ChatApprovals.kt`，本文件无调用点 |
| `PITFALLS.md:110-113`「刷新只挂四个入口」 | 4 个 | 代码里 `refreshPendingQuestions` 有 9 个调用点，「四个入口」指类别而非个数 |