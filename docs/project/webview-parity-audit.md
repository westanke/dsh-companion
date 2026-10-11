# WebView 聊天页 · 功能对照审计（截至 2.2.20）

- 日期：2026-10-10（初版）/ 2026-10-11 对齐到 v2.2.20（versionCode 10045）
- 对照对象：原生 `ui/ChatScreen.kt` vs `app/src/main/assets/chat.html`。
  **行数与行号都会漂，一律按函数名 / 组件名检索**：初版写的是「2656 行 / 40 个可组合函数」，
  2.2.20 时 `ChatScreen.kt` 已是 2907 行、md5 `4111fbf44fee4dfdfe5070da24bff9c9`。
- 触发：用户「我想 webview 模式先这样，你可以对照功能把以前的会话模式看看」
- 审计方式：**逐项读代码取证**，不靠印象。每条结论都附来源。
- 本文定位：两个模式的**能力边界对照**。历史复盘见 `webview-report.md`，
  移植清单见 `webview-port-todo.md`。

---

## 一、结论先说

| 维度 | 原生 | WebView 页 |
|---|---|---|
| **消息渲染** | Compose 手写 Markdown | 浏览器渲染，**更全**（表格、卡片、长文） |
| **工具活动显示** | 英文类别词（`Bash` / `Read` / `Code`） | **官方中文动作短语**（「运行了代码，修改了文件」） |
| **会话管理** | 重命名 / 归档 / 权限模式 / 新建 | **全无** |
| **文件** | 列表 + 详情（大小、时间、预览） | **只有路径** |
| **附件失败** | 分类成人话（401/403/404） | 只弹原始报错 |
| **生成 HTML** | 有结构预览 | 无 |

**一句话**：渲染与工具显示这一侧 WebView 曾反超原生；但**会话级操作**这一侧基本是空的，
因为页面只能调 11 个桥接方法，够不着的功能一个都做不了。

> **2026-10-11 补记（2.2.15 → 2.2.20 之后）**：前半句「页面反超」已经不成立了 ——
> 这一轮把页面的呈现方式搬回了原生（工具动作短语、输入区药丸、卡片表格列宽…），
> 逐条见 `webview-port-todo.md` 第二节。本文的用途因此从「要不要移植」变成
> **「长期保留 WebView 模式的话，两个模式还差什么」**。后半句仍然成立：
> 权限模式 / 重命名 / 归档依旧只有原生有。

---

## 二、页面能调用的原生桥（决定了能力边界）

`ChatWebBridge` 暴露 11 个 `@JavascriptInterface`：

```
request  poll  pageReady  copy  openLink  close  toast  haptic  pickFile  captureImage  takeAttachment
```

页面用到了 8 个（`haptic` / `captureImage` / `toast` 未用，其中 `captureImage` 有实现无入口）。
**没有**「打开原生弹层」这类方法 —— 所以原生那些 Sheet 在 WebView 模式下用户点不到。

---

## 三、页面已有的能力（逐项已核对）

| 能力 | 落点 |
|---|---|
| 消息渲染（Markdown / 代码块 / dsh-ui 卡片 / 表格） | `renderHistory` + `md()` + `renderCard()` |
| 工具调用**动作摘要** | `toolActions` / `toolGroupSummary`（照官方措辞实现） |
| 思考条（运行中一行） | `#thinkbar` + `setThinking` |
| 上下文注入（默认隐藏，可开关） | `showContext` + `setPrefs` |
| 斜杠命令补全（最多 5 条） | `refreshCommands` |
| 模型切换 / 智能体切换 | `openSheet` → `PUT /model`、`PUT /agent-preset` |
| 附件：图片 / 文件选择、上传、预览 chip | `App.pickFile` + `POST /messages` |
| 审批卡（允许一次 / 拒绝） | `showApproval` + `POST /approvals/{id}/decision` |
| 当前任务（todos） | `setTodos` |
| 本次产生的文件（路径列表） | `setFiles` |
| 停止 / 插话 | `POST /runs/{id}/cancel`、`send(true)` |
| 连接状态点、toast | `connected` / `toast` |

页面用到的 REST 端点：`/chat/sessions/...`（history / messages / model / agent-preset / approvals / decision / cancel）、
`/chat/runs/{id}/events`、`/chat/agent-presets`、`/commands`。

---

## 四、缺口（原生有、页面没有）

排序按「用户实际会不会碰到」。

### 高：会话级操作，WebView 模式下完全够不着

| 缺口 | 原生实现 | 页面取证 |
|---|---|---|
| **会话权限模式切换**（standard / full-access） | `PermissionControl`、`permissionLabel`、`ChatHistory.permissionSelect()` | `grep -c permission chat.html` = **0** |
| **会话重命名** | `RenameDialog` | `grep -c rename` = **0** |
| **会话归档** | `archive_session` 等字符串 | `grep -c archive` = **0** |

这三项在原生里有完整实现，切到 WebView 后入口消失。
权限模式尤其要紧 —— 它是 `chat.write` 之外的另一道闸，用户可能在会话中途需要升到 full-access。

### 中：看得到但看不全

| 缺口 | 原生 | 页面 |
|---|---|---|
| **会话文件详情** | `SessionFileDetail` / `SessionFileMeta`（大小、时间、预览） | 只有一行路径，点开提示「已在电脑上」 |
| **附件失败分类** | `describeAttachmentFailure`：401→「授权已过期，去设置页重新配对」、403→「缺 files.read」、404→「附件真的不在服务器上」 | 只弹服务端原始报错 |
| **生成 HTML 的结构预览** | `HtmlStructurePreview`（`data/HtmlPreview.kt`，235 行） | 无 |

### 低：调试向

| 缺口 | 说明 |
|---|---|
| `GenUiDebugLine` | 原生 dsh-ui 诊断行；页面侧诊断条已撤（改走 console.log） |
| 消息反馈（feedback） | 页面 `grep -c feedback` = 0 |

---

## 五、页面曾经反超的地方（2.2.20 已部分追平）

| 项目 | 页面（基准） | 原生 2.2.20 现状 |
|---|---|---|
| **工具显示** | 官方中文动作短语，且扫 `run_code` 参数拼出内层动作 | **已追平**：`ChatPresentation.toolActionsFor()` 走同一判据，旧的英文类别词 `toolTitle()` 已删；连续调用还会合并成一行（`mergeToolRuns()`） |
| **Markdown 表格** | 浏览器渲染，外层 `.tw` 横向滚 | 由 `multiplatform-markdown-renderer` 库渲染（本仓库所有 `Markdown(` 调用都不传 `components`）；**真机窄屏观感未核实**。注意这与 dsh-ui **卡片**里的表格不是一条线 —— 后者 2.2.16 已对齐（`GenUiCard.kt` 的 `genUiTableColumnWidthDp()`） |
| **长内容渲染** | 浏览器，标准行为 | 手写 Markdown + 库，边界情况多，仍然落后 |

**2.2.15 之前**，原生那个 `toolTitle` 给的是英文类别词（`Code` / `Read`），
而这轮按官方 `dsh-client-ui-chat` 的措辞把页面做成了「运行了代码，修改了文件」——
两个模式观感不一致。2.2.15 已把原生对齐到同一措辞，这一条**不再是差异**。

---

## 六、建议的处理顺序（页面侧）

> **2.2.20 现状**：下面四条**页面侧一条都没做**（这两轮改的是原生）。原生侧按
> `webview-port-todo.md` 走完了移植，所以这份清单现在等于「**若要长期保留 WebView 模式，
> 页面还欠什么**」—— 前提是先确认这个模式留不留。

1. **权限模式切换** —— 会话中途可能要提权，缺了这个用户得切回原生。给页面加一个 REST 调用 + more 弹层一项即可。
2. **重命名 / 归档** —— 这两个本来是会话列表页的功能，不一定要塞进聊天页；确认一下在 WebView 模式下从哪里进。
3. **附件失败分类** —— 把 `describeAttachmentFailure` 的文案搬到页面，或让原生在返回里带上分类。
4. **会话文件详情** —— 页面加点击展开（大小/时间），路径已经拿到了。
5. 其余为可选项。

---

## 七、与既有 `PARITY.md` 的关系

`PARITY.md` 是**插件 API ↔ Android 原生**的对等矩阵（「插件有没有这个接口、原生有没有实现」），
和这份「原生 ↔ WebView 页」不是一回事，不重复。

但它的最后一句要留意：

> 「对等」要求 App 能读取同一状态并执行同一用户动作；**仅显示 WebView 或直接调用 DSH 私有 `/api` 不计为完成**。

这条规矩订立时 WebView 还只是个试验品。如果打算长期保留 WebView 模式，**这条判定标准需要
重新表态** —— 否则按字面读，聊天页永远算「未完成」。建议改成「WebView 与原生存量共用一个对等矩阵」。

> 2.2.20 补充：聊天页默认已经回到**原生**（`HarnessState.useWebChat = false`，见
> `HarnessViewModel.kt` 的 `withPreferences()`），WebView 模式退化成可选的试验开关，
> 这条判定的紧迫性随之下降 —— 但归类问题本身仍未解决。
> `PARITY.md` 本轮由并行任务刷新，其现行措辞以该文件为准（本文不复制其结论）。

---

## 八、方法论备注

这份审计的每一条都是 grep / 读文件得出的，附了命中次数或函数名（**早期版本记的行号已全部移除** —— 代码在动，行号必漂）。
需要留意的是：**「页面没有」不等于「应该加」** —— 有些原生功能（新建会话、工作区管理）
属于列表页和设置页，本来就不该出现在聊天页里。第四节的排序已经按这个思路筛过。
