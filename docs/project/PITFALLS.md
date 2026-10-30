# 坑位索引（PITFALLS）

> 名字 = 触发词 = 锚点。**动手改某块代码前，先按名字读对应条目**——里面是踩过的坑、
> 最硬铁律、实测数据与被否决方案。不看就改等于重踩。
>
> 索引在 `AGENTS.md` §7，两处必须同步：新增坑位 = 名字加进 `AGENTS.md` 清单 + 原文写进本文件并补 `### 同名` 锚点。
>
> 每条格式：**症状 → 根因 → 现在怎么办（铁律）**。

---

### README 双站漂移

**症状**：`docsCheck` 全绿，但 README 里英双站的版本号、测试数、APK 大小停在几个版本之前。

**根因**：`build.gradle.kts` 的 `docsCheck` 只校验 `docs/project/` 下 8 份必需文档与
ROADMAP/STATUS 的任务引用一致性，**完全不覆盖 README**。所以「改文档再 build」这道闸在 README 是空的。

**实发案例（2026-10-14 修复，commit `d61cad9`）**：v2.2.33 → v2.2.35 三版发布时 README 未同步，
中文停在 `v2.2.32`（10057），英文更老——测试数写 24 类/256 用例，而中文写 25 类/271，**两份自己就对不上**。
另有 bridge 要求自相矛盾：安装段 ≥2.3.3、项目信息段 ≥2.3.1。

**2026-10-17 补**：客户端现在会解析 `connection.ready` 的能力位，**缺关键能力时在设置页明说**
（不再只是「卡片不弹」）；但那条提示只在**服务端报了清单**时出现 ——
旧插件不给清单 = 未知，此时不提示（假警告会教用户忽略警告）。

**现在怎么办**：发版前除 `docsCheck` 外，另跑一次对账——

```sh
grep -n "versionCode\|versionName" app/build.gradle.kts | tail -2   # 真值来源
grep -n "当前版本\|Version:\|companion-v" README.md README.en.md   # 双站必须与真值一致
grep -n "2\.3\.[0-9]" README.md                                    # bridge 要求两处必须一致
find app/src/test -name "*Test.kt" | wc -l                         # 类数
grep -rho "@Test" app/src/test --include=*.kt | wc -l              # 用例数
stat -c '%s' artifacts/DeepSeek-Harness-companion-v*.apk           # 体积
sha256sum artifacts/DeepSeek-Harness-companion-v*.apk              # 摘要
```

**铁律**：英文版最容易漏——它是「没人同步的那一份」。中英一起改。

---

### Compose 状态与 SharedPreferences

**症状**：设置页开关「点了不动」，要再点别的才跳。

**根因**：开关直接读 `SharedPreferences`。那不是 Compose 状态，值变了不会触发重组。

**现在怎么办**：状态进 `HarnessState`，`SwitchRow` 统一形态、点一下立刻生效。
读 state 而不是读 viewModel（`ui/SettingsScreen.kt:192` 有注释）。

**铁律**：Compose 表现层只消费 `HarnessState` 并调 `HarnessViewModel` 动作，
不持有协议或权限决策，也不自己读偏好存储。

---

### WebView 与原生双实现

**症状**：同一功能在原生页与 WebView 页表现不一致；或网页版改了原生没跟上。

**根因**：v2.0.0 起会话详情页有 WebView 实现（`app/src/main/assets/chat.html`）与原生 Compose 并存，
由设置开关切换（**默认原生**）。2.2.15 起的方向是**把网页版那套呈现方式反向移植回原生**。

**现在怎么办**：
- 网页版（`chat.html`，2 476 行）是**兜底资产**，小修即可，不值得为它引入新体系。
- 新功能优先落原生；移植清单见 `docs/project/webview-port-todo.md`。
- 两处共用同一数据出口（如 `sessionFileList()`），避免「底部是交付物、弹层是嗅出来的路径」。

**铁律**：不许为了让网页版「也能用」而回退原生能力。
**已决**（`dsh-study/ADR-2026-10-15-*.md`）：我们的网页会话版**不能**基于 `dsh-web-mobile` 改——
它是寄生官方 WebUI 的补丁层，没有自己的会话页，与我们的自包含 `chat.html` 隔着整个官方 WebUI。

---

### 图片附件元数据与字节读取

**症状**：历史图片读不出来（404/401 或空白），或刚发的图也读不出。

**根因**：`data/ChatPresentation.kt` 的 `contentAttachments` **已经**从会话内容解析出
`mediaType` / `width` / `height` / `bytes` / `attachmentId`——**元数据不缺**。
问题在读取字节时没有把完整引用继续传下去。

**现在怎么办**：取字节走完整附件引用，不要只带 id 或只带路径。
401 授权过期 / 403 缺 `files.read` / 404 附件真没了要分开提示，且授权类失败**允许重试**
（此前失败一次就永久记住，重新配对也不能自愈）。

**铁律**：附件体积在**发送前**拦截（图片 8 MiB / 文件 24 MiB），给的是能照做的动作（「先压缩再试」）。

---

### 熄屏断流与亮屏接回

**症状**：App 在后台/分屏时仍保持实时流，纯耗电。

**根因**：用 `onStop` 判断「用户看不到内容」——但切 App、分屏、下拉通知栏都触发 `onStop`，屏幕还亮着。

**现在怎么办**（v2.2.30，`MainActivity.kt:28-39` + `HarnessViewModel.kt:1813`）：
听 `ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON` 广播，正好把「熄屏」与「切走但屏幕还亮」分开。
用户原话：「能让熄屏就不在线了，如果不是熄屏状态 实时优先」。

**铁律**：省电类改动必须真机确认，单测覆盖不到广播时序。

---

### ask_user_question 三态

**症状**：问答卡片不显示，或显示了点不了。

**根因**：内核问题有三种状态，语义不同：
- `open` —— 还没回答
- `continued` —— 已超时，agent 会**继续跑**（内核提示是 `Continue useful independent work. The user can still answer.`），
  但这一轮可能 5 秒收尾也可能几分钟，长度不可控
- 其他

**现在怎么办**：只渲染 `state=continued` 的问题，`open` 状态直接丢弃；
`refreshPendingQuestions` 只对当前 `selectedSessionId` 发 GET，返回后校验会话是否切走。
**全仓无遍历、无定时器、无轮询**，刷新只挂**四类**入口：切会话 / 顶栏刷新 / WS 结构性事件 / WS 重连。

**为什么不能遍历会话列表**（这条原因原先只活在源码注释里，2026-10-16 补进本条目）：
`GET /chat/sessions/{id}/questions` 在服务端内部走 `resolveAgent(sessionId, { resume: true })`，
而 `resume` 的语义是「resolve 不到就地启动」。所以对一个**冷会话**调这个**纯查询**端点，
副作用是把该会话的 Agent 在服务端**拉活** —— 一次「读一下有没有待答问题」变成一次会话唤醒。
拿 sessionId 遍历 `sessions` 批量拉，100 个会话就是 100 次误唤醒。
警告原文在 `data/DshClient.kt:292-297`，同一约束在 `HarnessInteractionsViewModel.kt:98-99` 与 `:120-129` 用散文重述过。
**铁律**：任何「看起来是只读」的端点，先确认服务端有没有 `resolveAgent(..., resume)` 这类隐式副作用。

**附带一条容易记错的**：`scheduleChatRefresh()`（400ms 去抖）**不负责审批与问答**，
它只拉 `history` 与 `sessions`。审批 5 个触发点、问答 9 个触发点，各自直接发 GET，
不走 400ms 窗口也不受单飞约束 —— 两条链路是**完全解耦**的，别指望调它能刷新问答。

**铁律**：别给 agent 抢答。内核 host 面有 `user-questions/request` 事件（`(request, next) => …`），
是给宿主插入界面的钩子；抢答会与官方 WebUI 冲突，应像 approvals 那样 `next()` 委托兜底。

---

### 交付物事件与路径嗅探

**症状**：agent 交付的报告/安装包在会话里看不到；或底部是交付物、标题栏弹层却是一堆嗅出来的路径。

**根因**：旧实现只认三种会话事件，认不出 `deliverables/presented`（`present` 工具触发，
带绝对路径与人写的说明）。

**现在怎么办**（v2.2.20 起）：会话底部与标题栏文件夹弹层**都直读内核事件**，
两处共用同一个 `sessionFileList()`。
**刻意不解析 `bash` 命令里的路径**——那里面大多是搜索范围、变量或 `echo` 文本，
**误报比少列更糟**（点一个打不开的路径，用户会以为功能坏了）。

**v2.2.48 起消息流里也有卡片**：用户对照桌面端发现电脑会话里交付物有**内联卡片**、
手机端只有弹层 —— 数据一直在历史里，是 `displayItems()` 的映射把 `deliverables/presented`
整类丢掉。修法：按出现顺序缓冲交付物，每轮 `turn/end` 处冲刷成 `DELIVERABLE` 卡片
（钉在**轮次收尾**，对齐桌面端 turnTail 的钉法；会话尾部没有 turn/end 时补在最后）。
同一轮里同一路径重复交付只留一张卡、说明取最后一次。回归用例在 `ChatDeliverableCardsTest.kt`。

**铁律**：文件预览按类型分流，`.md` 渲染、`.html` 结构化抽取（**丢弃 `script` 与 `style`**），
**刻意不用 WebView**——那等于让会话里出现的任意文件在手机上拿到执行权。

---

### bridge 版本基线

**症状**：某功能在用户的机器上不工作，日志却看不出问题。

**根因**：App 依赖 bridge 的具体版本能力，而 bridge 是另一个仓库、另一条发版线。
已知基线（`docs/project/STATUS.md`）：

| 能力 | 最低 bridge |
|---|---|
| 「加载更早消息」（`history()` 的 `beforeSeq`） | **2.3.1** |
| 插件页清单（profile 判定） | **2.3.2** |
| 插件页启停（`PATCH /settings/plugins`） | **2.3.3** |
| **审批 / 问答卡片在手机端弹出** | **2.3.7** |
| 斜杠命令可用（`commands.execute` 四参数） | **2.4.0** |
| 「回答被截断」提示（`turn/end` 透传 `reason.kind == 'max-tokens'`） | **2.4.1** |
| 会话权限档位 Pill / 待办与工具轨迹读投影（`history` 透传 `projections`、权限目录 BFF） | **2.4.2** |
| **后台任务面板（`/chat/sessions/{id}/jobs` 三端点 + `job-list-v1` 能力位）** | **2.4.3** |
| 模型页目录添加 / 恢复默认模型 / 删除供应商（`settingsNs` 与目录元数据透传、`models: null` unset、`SETTINGS_CONFLICT`） | **2.5.0** |

**DSH 内核版本基线**（2026-10-20 补，App 不直连内核，此栏仅用于服务端排错定位）：

| 组合 | 状态 |
|---|---|
| App v2.2.45 ↔ bridge 2.4.2 ↔ DSH `0.2.0-rc.2` | 已验证（bridge 兼容层真机实测） |
| App v2.2.45 ↔ bridge 2.4.2 ↔ DSH `0.2.1-rc.2` | 已做静态接口核对，**待真机回归** |

bridge 2.4.2 的 10 项宿主服务 `inject`（`cordis.patch.yml`）在 0.2.1-rc.2 上全部仍存在、被调方法签名未变，
故不需要改码；`permissionPresets` 的注入面在两版上都未确认（0.2.0 即注不进，已降级到内置目录）。
证据与完整清单见 `dsh-remote-bridge/docs/project/DSH-0.2.1-ADAPTATION.md`。

**现在怎么办**：README 双站「安装要求」与「项目信息」两处写的必须一致（曾一处 2.3.3、一处 2.3.1）。
当前口径：**≥ 2.3.7**，并在括号里注明分项。

**卡片这一项为什么单列**：2026-10-16 才定位清楚——它曾连续坏在**两个**插件侧病根上，
App 侧代码自始至终是对的。① `sessionIdOfAgent` 漏了内核 `Agent.id`（bridge 2.3.6 修）；
② 两个钩子注册时没带 Cordis 的 `{ prepend: true }`，被排到 waterfall 链尾、永不被调用
（bridge **2.3.7** 修，含 Cordis 源码行号，见 `dsh-remote-bridge/docs/project/PITFALLS.md`
§waterfall 不 prepend 就等于没注册）。
**教训**：卡片不弹时**先查插件版本**再查 App —— 症状完全一样（手机零卡片、电脑端正常、
两侧日志都不说一句话），但修的地方在另一个仓库。

**铁律**：新增 bridge 依赖时，同步更新 README 双站 + `STATUS.md` + **本表**，别只改一边。
（本表曾在 2.3.8 / 2.4.1 / 2.4.2 三版漏更，导致排错者得出「2.3.7 就够」的错误结论。）

---

### 端口 3090 归属

**症状**：在 DSHA 系手机上装本 App + bridge，或反向部署，出现端口占用。

**根因**：3090 在 DSHA 生态里是**双重身份**：
- DSHA App 侧：`Constants.SHELL_BRIDGE_PORT = 3090`（设备桥，给容器内插件调 `/app/*`）
- 我方：`dsh-remote-bridge` 默认监听 `DEFAULT_PORT = 3090`（`src/host/config-text.ts`）

更细的证据：DSHA 的内置插件把 3090 **硬编码**在插件里（`dsh-deliverable-mobile/lib/index.js:19`）。

**现在怎么办**：bridge 端口与绑定 IP 都可在设置里改；示例地址（`ConnectScreen.kt:631`、
`HarnessViewModel.kt:162`、`ConnectionShare.kt:30-31`）都用 3090 作默认值。
**若要与 DSHA 同机并存，把 bridge 改到 3091** 并写进说明。

**铁律**：文档/示例里的端口号改了，记得同步这几处（ConnectScreen / HarnessViewModel / ConnectionShare 注释 / README）。

---

### Gradle wrapper 挂起

**症状**：`./gradlew` 跑起来十几分钟没输出，CPU 0.3%、无 daemon 日志、零网络连接。

**根因**：`gradle-wrapper.properties` 的 `distributionUrl` 指向 `services.gradle.org`，国内不可达；
而 wrapper 下载失败时**不报错退出，而是挂起**。

**现在怎么办**：一律用 `scripts/build.sh`（直接调本地缓存的 Gradle 9.5.0，并注入镜像）。
**另一个更隐蔽的坑**：必须用**本项目自带**的 `scripts/init-mirrors.gradle`——工具链里那份会往
project 级仓库追加，与 `settings.gradle.kts` 的 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`
冲突并**直接构建失败**。

**铁律**：不要在文档里写 `./gradlew` 当唯一口径；一律 `scripts/build.sh`。

---

### 并发写者与未提交改动

**症状**：`git status` 突然变空，或别人的提交把你的未提交改动一起带走。

**根因**：本项目是单人主维护，但**本地有多个工作树/Agent 并行**是常态
（本次会话就出现过：宪法编写期间另一路在读同一仓库）。

**现在怎么办**：
- 提交只 `git add` 自己点名的路径，**绝不 `git add -A`**。
- 发现改动「凭空消失」时，先 `git show HEAD:<file>` 确认内容是否已在，别重做。
- 判「某修复在不在 main 上」以 `origin/main` 为基准，先 `git fetch origin --prune`；
  本地 `main` 可能落后（自认没有的修复其实早落地），也可能**领先**（夹着本地合并、远端仍 OPEN 的 PR）。

**铁律**：本地 commit 可自主；push / release / tag / 上传产物一律等用户点头。

---

### GitHub 443 阻断与镜像从站

**症状**：本机 `git push origin` 报 `gnutls_handshake() failed` 或连接超时；
但 `api.github.com`、`uploads.github.com` 正常，`gh` 也已登录。

**根因**：本机到 `github.com:443` 长期被阻断（DNS 正常），REST API 走的主机名不在封锁面。
历史上用过工作区 `gh-mirror-rest.py` 经 REST 重建提交，但那条路**保留不了原始 commit hash**
（哈希覆盖父提交，重建链就是新 hash），与主站永远对不齐。

**现在怎么办（2026-10-16 起的定论）**：GitHub 降级为**镜像从站**，本地不再手工推：
- 主备两站用 `scripts/push-all.sh` 推 Gitee + AtomGit；
- GitHub 由 `.github/workflows/mirror-sync.yml` 在 **GitHub 自己的 runner** 上
  `git clone --mirror` Gitee（不通回退 AtomGit）后 `git push --mirror` —— runner 不受本机网络限制，
  且普通 clone/push 保留真实 hash；
- 发版后想立刻同步，Actions 页手动 `workflow_dispatch`，否则等每 3 小时定时。

**重建仓库的捷径**：若 GitHub 历史已与主站分叉（本项目 v2.2.35 后发生过 hash 重写分叉，
merge 爆 47 个冲突），直接删仓再用 **GitHub Importer 从 Gitee 导入**，hash 原样保留，
比线性化/`-s ours` 收编干净得多。注意删仓需要 token 带 `delete_repo` scope。

**铁律**：不要在本地对 GitHub 反复试推或用 REST 脚本补哈希；从站的唯一事实来源是 Gitee。

**重建/同步实施时踩过的三个坑（2026-10-16 实测）**：

1. **空仓引导的鸡生蛋**：`push --mirror` 的工作流要靠 `scripts/ci/*.sh`，但新空仓第一次
   只有引导 yml，没有脚本 → `No such file or directory`（exit 127）。解法：引导版 yml 的
   mirror job **内嵌**同步逻辑（与脚本等价）；mirror 覆盖后真实历史里的文件才出现。
   GitHub Import REST API 已废弃（404 指向网页 Importer），网页 Importer 在被墙网络下卡
   "Couldn't check availability"——所以别走 Importer，走「Contents API 引导 + workflow 自举」。
2. **GITHUB_TOKEN 不能写 workflow 文件**：job 级 `permissions` 没有 `workflows` 键（写了
   解析报 `Unexpected value 'workflows'`）；`push --mirror` 每个含历史 `.github/workflows/*.yml`
   的 ref 都被拒。必须用仓库 secret **`MIRROR_PAT`**（带 `repo` + `workflow` scope）。
3. **GitHub uploads 拒绝非 ASCII 资产名**：百分号编码的中文被整段丢弃（`probe-中文.txt`
   → `probe-.txt`）、raw UTF-8 报 400、不认 `Content-Disposition`。Release **标题/正文**可中文，
   但**资产文件名**必须 ASCII；同步脚本 `ascii_asset_name()` 做映射并自愈残缺名。
   Gitee 侧资产名保留中文，两站不同名但内容哈希一致。

---

### AtomGit 账号名与 API 版本

**症状**：照 Gitee/GitHub 的账号 `westanke` 拼 AtomGit 地址，403 `project could not be found`；
访问 `https://atomgit.com/api/v1/...` 一律 404 `NOT_PATH`。

**根因**：AtomGit 账号是 **`westanke163`**（与另外两端不同）；其 Web 主机上的旧版 API 路径
已废弃，可用的是 **`https://api.atomgit.com/api/v5/`**（与 Gitee v5 同源同形），
认证头 `PRIVATE-TOKEN: <token>`（v3 路径也认，但不是正路）。

**现在怎么办**：
- 仓库地址 `https://atomgit.com/westanke163/dsh-companion.git`；
- 建仓 `POST https://api.atomgit.com/api/v5/user/repos`（`auto_init=false`，随后 push 全量 refs）；
- 已观察到的服务端差异：两个最老的**轻量 tag**（v1.1.0/v1.2.0）在 Gitee 的 `ls-remote`
  输出带 `^{}` 解引用行、AtomGit 不带，指向 commit 相同，属展示差异，不用修。

**铁律**：三端账号名不要想当然；新接远端先 `git ls-remote` 探活再配 remote。

---

### 长文本正则与重复分组

**症状**：代码高亮 / 分词这类「对整段文本跑正则」的代码，在长输入上抛 `StackOverflowError`
—— 不是超时、不是变慢，而是**直接崩**，且崩在渲染路径上。实测三种输入必崩：
`"` + 20 万个字母、20 万字符的完整字符串字面量、`"\"` 重复 20 万次。

**根因**：`java.util.regex`（Kotlin `Regex` 的实现）对**重复分组**用递归处理，
递归深度 = 重复次数。于是 `Regex("\"(?:\\\\.|[^\"\\\\])*\"")` 这种看着人畜无害的
「字符串字面量」正则在长文本上一路压栈到爆。**它不挑环境**：JVM 崩，Kotlin/Native 同样崩。

**现在怎么办（铁律）**：
- 对**长度由用户/模型决定**的文本（消息正文、代码块、工具输出）**不要**用含 `(…)*`、`(…)+`
  的分组正则；一律改**单遍字符扫描**（`indexOf` 找起点 + 手写状态机找终点），O(n) 且无递归。
- 只允许留下「锚定行内、无重复组」的正则，例如 `//[^\n]*`、`\b\d+(\.\d+)?\b`。
- **后向断言（lookbehind）一并禁止**：KMP 侧支持不全，且与重复组同属性能雷区。
- 每个扫描器都要配一条**病态输入回归**（长串字面量 / 未闭合引号 / 20 万字符），
  以「能跑完且不抛异常」为断言；样板见 `ChatCodeHighlightTest`。

**实发案例（2026-10-17）**：`ui/chat/ChatCodeHighlight.kt` 初版按字符串字面量正则实现，
20 万字符输入下 `StackOverflowError`；改为与 JSON 共用的单遍扫描后通过，
留下 `pathologicalQuoteRunsStayFast` 钉子。**同一次改动还踩到第二个坑**：
块注释正文里写出「星号紧跟斜杠」两个字符会**提前闭合 Kotlin 块注释**（Kotlin 注释支持嵌套），
症状是编译器报一串 `Expecting a top level declaration`。注释里要描述正则时用文字，别抄字面量。

**附**：未闭合引号若一路染色到串尾，会把后面几十行代码全吞掉（整段失去高亮）；
扫描器应**停在行尾** —— 这也正贴合流式输出里的半截字符串。

---

### 过期响应覆盖新上下文

**症状**：切会话后看到「切过去又跳回来」；更糟的是**标题写着 B、内容却是 A**。
不崩、不报错，只在特定时序下出现，所以极难复现。

**根因**：异步加载把「发请求」与「写状态」当成一段连续逻辑，而中间隔着一次网络往返；
上下文（会话 / 主机 / 连接）完全可能在往返期间被换掉，回来那份就是**上一个上下文**的数据。

**现在怎么办**：`data/RequestGate.kt` 的代际闸门 —— 加载任务在**调用点**领号，
回来先 `isCurrent(token)`，换代就整块丢弃（`busy = false` 一并跳过，它属于新请求）。
三个切换点已接：`selectSession`、`connectHostInternal`、`disconnect`。
回归钉子：`RequestGateTest`。

**铁律**：
- 任何「异步取数据 → 写进共享状态」的路径，都要问一句「这段时间里上下文会不会变」。
- **领号必须在发出请求的那一层**，不能挪进协程：协程启动有延迟，挪进去就漏掉了
  「发出后立刻切换」这个最典型的窗口（这条反直觉，但正是它的价值所在）。

---

### SelectionContainer 把子节点当兄弟排布

**症状**：消息正文与时间戳**并排成同一行**，时间戳被挤到屏幕左边、与气泡重叠。
用户原话连着改了八轮：「显示时间与消息重叠」（第 1–8 轮反馈，措辞每次略有不同）。

**根因**：`ChatItem` 的结构是

```kotlin
SelectionContainer {
    when (item.kind) { ... }          // 正文
    ChatMessageFooter(item, onForkAt)  // 时间戳 + 分叉入口
}
```

`SelectionContainer` 是 **inline-like** 布局：它把**直接子节点当兄弟排布**，不是叠成上下。
所以正文与 footer 一直是同一行 —— 之前看不出来，是因为 footer 那时是 `fillMaxWidth()` 的
全宽 Text，撑满后看着像在下一行（其实是被挤到了一边）。一旦把它改成自适应宽度，
并排关系立刻暴露。

**现在怎么办**：`SelectionContainer` 内部加一层 `Column(Modifier.fillMaxWidth())`，
正文在上、footer 在下；footer 内部再用 `Row` 按消息类型对齐
（用户消息 `Arrangement.End`、助手 `Arrangement.Start`），内部 `Text` 用
`weight(1f, fill = false)` 兼顾「宽度自适应」与「左右对齐」。

**为什么值得单独立一条**：这个坑连续八轮没找到，前七轮全在**给一个排错了位的元素调 padding**
（3 → 6 → 8 → 10dp → 归零 → 加回来 → 分叉按钮也加 → 消息流 spacedBy 也加），
每一版都「有理由」却全无效。`SelectionContainer` 的这个特性代码里**没有任何注释提示**，
只有把布局结构打出来才看得见。

**铁律**：
- **改样式前先确认元素在布局树里的位置**。同一个组件多轮调间距仍无效时，
  停手，把父容器的布局结构打出来看 —— 元素可能压根不在你以为的那一行。
- `SelectionContainer` / `FlowRow` 这类 inline-like 容器**只包一个需要上下排列的子节点**；
  要多个，请自己加 `Column`。

### delta 帧缺 turn/step 被静默丢弃

**症状**：正文正在流式渲染，底部状态条却一直显示「内容由 AI 生成，请甄别后使用」，
思考内容该出现的地方一片空白。真机上看起来像「实时坏了」，但正文其实在正常刷新，
只有**思考条**永远不出现。

**根因（两侧都有责任）**：

- 服务端 `chat-adapter.ts` 转发 `assistant/chunk` 时，`turn` / `step` 是**条件性**的：
  `...(typeof assistantData.turn === 'number' ? { turn: assistantData.turn } : {})`。
  内核这一版的 `assistant/chunk` 不带这两个字段，于是转出来的 delta 帧也没有。
- 客户端 `chatDelta()` 原来写的是 `turn = value.intValue("turn") ?: return null`，
  **缺一个字段就整帧丢弃**，且是静默的（没有任何日志）。

两端一叠加：所有 delta 帧被扔掉 → `liveChat` 恒 null → `liveThinkingText()` 恒空串 →
`ChatStatusBar` 只能走「显示 AI 标识」那个分支。**症状落在思考条，根因在事件解析。**

**修法**：`turn` / `step` 只用于「按 turn/step 切段」，不参与正文渲染，缺失兜底 `0`；
真必填的只有 `sessionId` / `index` / `text`。两侧同时兜底，回归见 `ChatDeltaToleranceTest`。

**取证教训（同一天踩了两次）**：这两次排查里我两次把**自己写的计数器**当成事实：

1. `frames` 计数器只在解析失败时 +1，于是 `frames=0 dropped=0` 被我读成「一帧没收到」，
   实际含义只是「零解析失败」。改成统计**所有**收到的帧并按 type 分布后，
   真相是 `frames=1 dropped=0 types=connection.ready×1`。
2. 落底那次的「跳到底部按钮消失」当判据（那个条件只是「距底 ≤3 项」）。

**计数器口径必须先核对再解读** —— 数字不会自己解释自己。


### vivo 输入法拒绝外部文本注入

**症状**：真机上要往 App 的输入框里填一段 464 字符的 `DSH1:` 配置文本（无人值守连接），
`adb shell input text` 一路试下来全部失败，且**失败方式各不相同**，很容易误判成「代码坏了」：

| 手段 | 真机表现 |
|---|---|
| `input text '<464 字符>'` | 只进前 ~80 字符，其余静默丢弃 |
| 分 12 段逐段 `input text` | 仍只进前几十字符（焦点在段间丢失） |
| `input keyevent 279`（`KEYCODE_PASTE`） | 完全无效 |
| 长按弹「粘贴」菜单 → `input tap` 点它 | 菜单不响应外部注入的点击（输入法浮层通常忽略注入事件） |
| `input keyevent 67`（`KEYCODE_DEL`）×60 | 删不动，退格被吞 |
| `service call clipboard 2 s16 '<text>'` | ✅ **写剪贴板成功**（唯一能用的注入手段） |

**根因**：厂商输入法（vivo）对非真实触摸的注入事件做了过滤，`input text` 的长串
被截断、粘贴类按键与浮层点击被忽略。这不是 App 的问题，也不该靠「多试几次」解决。

**修法（本项目的取舍）**：**绕开输入法**，给连接页加两条不经过软键盘的输入路径：

1. **外部专属目录自动读取**（主路径）：`getExternalFilesDir()/dsh-connection.txt`。
   这个目录**不需要任何权限**（App 自己的外部专属目录），`adb push` 直接可写；
   App 启动时若输入框还空着就把文件内容填进去。
2. **SAF「从文件读取」按钮**：`ActivityResultContracts.OpenDocument()`，零权限。
   但这条**在 vivo 上不可靠** —— `documentsui` 的「文档」视图只索引 MediaStore
   认得的类型，`adb push` 进 `/sdcard/Download/` 的 `.txt` / `.json` **都不出现**
   （实测 2026-10-18）。留着是给「用户自己从微信/邮箱存下来的文件」用，
   不是给 adb 部署用的。

**一句话**：无人值守场景一律走 ①，别指望输入框。

### 键盘弹起让对话框上移：点旧坐标会点空，表现为「点了没反应」

**症状**：真机验收报告 v2.2.44 的 F2 ——「文件页新建文件/文件夹**静默失败**：
对话框关闭、文件没创建、列表不刷新、**没有任何错误提示**」，连制造同名冲突后的
`409` 也不显示。报告据此写了「请求没有到达 bridge」。

**根因不是 App，是点击落空**：往 `AlertDialog` 的输入框打完文件名后**软键盘弹起**，
对话框整体上移；此时用**弹起之前** dump 到的坐标去点「创建」，坐标落在对话框之外 →
触发 `AlertDialog` 的 `onDismissRequest` → 对话框关闭。**整个过程一个请求都没发**，
所以「文件没建、没有提示、审计无记录」三件事同时成立 —— 完美伪装成「功能坏了」。

实测位移：`(834,1442)` → **`(834,1016)`**，**上移 426px**（vivo V2080A / 1080×2400 / 密度 480）。

**怎么一锤定音的**：给 `createEntry` 补了一条诊断日志（`FILE 创建条目 …`）+
给 `launchOperation` 的失败分支补了（`SEND 请求失败：…`），然后用**重新 dump 后的坐标**点「创建」：

```
#5 FILE 创建条目 root=f54c3523-… path=f2diag2 directory=false
#6 FILE 创建条目返回：f2diag2
```

同时 bridge 审计出现 `actor_type=device` 的 `file.create`，文件真的生成。
再用同名文件制造冲突 → 界面显示「**同名文件已经存在了**」，日志给出
`#8 SEND 请求失败：DshApiException: The destination already exists.` ——
说明错误通道本来就是通的，**报告里那条「静默失败」从来没发生过**。

**修法/规避**：

1. **输入之后必须重新 dump 再点**（`android-ui-drive` skill 的铁律：
   「每次点击前必须重新 dump，不要复用上一次的坐标」—— 键盘弹起正是这条规矩要防的事之一）。
2. 或者先 `input keyevent KEYCODE_BACK` 收起键盘（注意：在对话框里按返回会**直接关掉对话框**，
   所以这条要配合「收完再重新打开」）。
3. App 侧加固（本轮已做，独立价值）：早退点不再静默 `return` 而是抛错走全局 snackbar；
   `DiagLog` 在「无活动连接被丢弃」「代际失效丢弃」「请求失败」三处都留痕。

**教训**：

- **坐标类失败与功能类失败在屏幕上完全同形** —— 都是「点了，界面没变化」。
  没有日志/审计这种**旁证**，就只能靠猜，而猜出来的根因会写进验收报告误导后来人。
  报告里那条「请求没到 bridge」就是这么来的。
- **补日志的价值在这次被量化了**：一条 `FILE 创建条目` 就让悬案当场结案。
  排查真机问题的第一件事应该是「这条路径有没有记录」，而不是「先解释现象」。

### 诊断日志：开关只该管「记不记」，不该管「通道建不建」

**症状**：诊断日志的屏上浮层有字、`adb` 打开文件却是空的 ——
**看起来一半正常，实际文件通道整条没建立**。

**根因**：`DiagLog.bind(context)` 初版写成 `if (!enabled) return`，理由是「开关关着就不必绑路径」。
而设置页那个开关**默认就是关的**，于是：启动时 `bind` 被跳过 → `logFile` 恒 null →
用户随后拨开开关只改了 `enabled`，**没有任何人再调 `bind`** →
文件通道永远死着，而屏上浮层（走内存环形缓冲）照常工作。

**这是唯一坏路径恰好是默认路径的典型**：只有「先关后开」会中招，而那就是默认流程。
冷启动时偏好已是「开」的人反而一切正常，所以问题不会在自测里出现。

**修法**：`bind` 与开关**彻底解耦** —— 它只记住文件在哪，不判断要不要写；
判断留给每条日志的 `if (!enabled) return`。回归见 `DiagLogTest` §先关后开时文件通道仍然建立。

**同类教训**：「开关」类变量一旦被两个不同职责共用（是否记录 / 是否初始化），
就会出现「先 X 后 Y」这种唯一坏序，而它往往正是默认序。

### 诊断日志的噪音：重复行必须按指数抑制

**症状**：插桩第一版在真机上跑一轮会话操作，`diag.log` 刷出 **344 条**，其中 300+ 条是
同一句「ChatDetail 组合：会话 …」，真正要看的 `ROUTE` / `SCROLL` 被淹得找不着。
改掉组合体里的直接打点后仍有第二波：流式期间「贴底跟随未触发」逐帧重复，
一屏 6 条**正文完全相同**（只有时间戳不同）。

**修法（两处，缺一不可）**：

1. **打点位置**：会重组的地方一律用 `LaunchedEffect(key)` 而不是写在组合体里。
   路由日志的键取 `(useWebChat, selectedSessionId)`，渲染日志取 `selectedId` ——
   只有**语义上真的变了**才记一条。
2. **`DiagLog` 内的连续重复抑制**：与紧邻上一条正文相同时只累加计数，
   且只在计数为 2、4、8、16…（2 的幂）时落一条「（同前，已重复 N 次）」。
   n 次重复从 n 行降到 ⌊log₂n⌋+1 行。

**为什么抑制要放在 `DiagLog` 而不是各调用点**：噪音来自**多个**调用点，
逐个加判断必然漏掉下一个新加的。放到底层，新调用点自动受益。

**效果**：同一轮操作从 344 条降到 17 条。

### 数字口径不可混用（诊断日志版）

**症状**：日志里出现 `displayItems=60 total=57` —— 前者比后者大，看着像个 bug，
于是去查一个不存在的 bug。

**根因**：`displayItems` 是**分组前**的消息项数，`total` 是 LazyColumn 自报的
**分组后**条目数（`groupChatDisplay` 会把若干条消息折进一个过程组）。
两个数都没错，错的是并排显示 —— 分组后条目数**本来就该少于**消息项数。

**修法**：记 `entries`（= `displayEntries.size`，与 `total` 同口径）而不是 `displayItems.size`，
并在函数 KDoc 里写明两个数各自是什么。

**与 `delta 帧` 那条同源**：**计数器口径必须先核对再解读**。

### history 重建冲掉滚动位置，贴底跟随被自己的守卫锁死

**症状**：进会话时自动落底是好的（推到物理底部），但过一会儿**视图自己回到顶部**；
且流式输出期间正文一直往下长，视图**完全不跟**，用户得手动再滚到底。
日志上表现为 `isAtBottom=false` 持续几百条、「贴底跟随触发」一次都不出现。

**证据链**（2026-10-18 真机，同一会话 8 秒内）：

```
#17 落底循环结束：scrollBy=0（物理底部），共 26 拍
#18 贴底跟随触发：isAtBottom=true  可见=39-47/48   ← 正常
#19 snapshotFlow：totalItemsCount=1                ← 列表被清空
#20 snapshotFlow：totalItemsCount=48
#21 snapshotFlow：totalItemsCount=1                ← 又清空
#22 snapshotFlow：totalItemsCount=48
#23 贴底跟随未触发：isAtBottom=false 可见=0-8/48 first=0/0  ← 位置归零，再不回来
```

**根因（三层叠加）**：

1. `state.history` 被周期性替换（流式刷新期间反复「空 → 满」），
   LazyColumn 的条目数掉到 1（只剩「加载更早消息」那一项）；
2. **列表清空的那一瞬，Compose 把滚动位置重置为 0** —— 这是 LazyColumn 的正常行为，
   条目没了，先前的位置无处附着；
3. 内容回来后位置不恢复，而「落底循环」只在 `LaunchedEffect(selected.id)` 里
   **跑一次**（进会话那一刻），对后续重建无能为力。

于是 `isAtBottom` 永久 false → 贴底跟随的守卫 `if (atBottom)` 永远不成立 → 自我锁死。

**这里还有一处「注释承诺了但没实现」**：`ChatDetail.kt` 的注释写着
「持续贴底的守卫是 `!userScrolled`」，但全仓 grep `userScrolled`
**只在注释里命中** —— 实际守卫是瞬时的 `isAtBottom`（几何位置），
而注释描述的是**粘性意图**。两者差别是本质的：

| | 粘性意图（注释承诺） | 瞬时几何（实际实现） |
|---|---|---|
| 内容增长 | 不受影响，继续贴底 | 立刻 false → 拒绝跟随 |
| 位置被冲走 | 会自动追回 | 永久失效 |

**修法（未实施）**：把守卫换成注释里承诺的粘性标志 ——
`wantsBottom` 由「用户滚到底」置真、「用户主动上滑」置假，
贴底跟随只看它、不看瞬时 `isAtBottom`；落底循环也改成「只要 `wantsBottom` 就持续贴」。
**治的是根因**：位置被冲走后能自动追回。

**另外值得单独查**：`state.history` 为什么会被周期性置空。
位置被冲走只是它的后果；即使守卫改好，「列表闪一下」的抖动本身也该消除。

**判据教训**：查这类问题必须把 `visible.first().index / last().index / totalItemsCount`
**三个数一起打**。只打 `entries` 与 `total` 判不出是谁的锅 ——
`可见=39-47/48` 与 `可见=0-8/48` 在「都是 48 条」这一点上完全一样。

### Markdown autolink 的尖括号目标被当成相对路径

**症状**：会话里点了文件链接**完全没反应**（用户 2026-10-19 原话「我手动点都没反应」）。
同一文件从「本会话的文件」面板点得开 —— 这条对照说明链路本身没坏，坏在链接。

**模型写的是合法 Markdown，目标用尖括号包着**（CommonMark 的 autolink 写法，
路径含中文/空格时常用）：

```markdown
📄 [dsh-public-deployments-项目适配评估_2026-10-19.md](</media/wangke/OFFICE/workspace/report/dsh-public-…md>)
```

**三层根因，缺一层就仍然打不开**：

| # | 根因 | 表现 |
|---|---|---|
| 1 | `ChatLinkTarget.classify` **不剥尖括号** → `<...>` 不以 `/` 开头、也没 scheme → 判成**相对路径** | 绝对路径又被拼一次 cwd，得到 `/workspace/</media/…/x.md>` 这种翻倍路径 → `PATH_NOT_FOUND` |
| 2 | `openSessionFile` **只把文件加载进 state，不开面板**（面板是 ChatDetail 的局部 `filesSheet`） | 即使 resolve 成功，屏幕也一动不动 —— 用户看到的仍是「点了没反应」 |
| 3 | `UNRESOLVABLE_CODES` 把 `PATH_NOT_FOUND` / `PATH_NOT_FILE` 也算「打不开的正常结果」 | 「文件没找到」被显示成「**不在授权根内**」，用户按那句话去查授权根，方向全错 |

**怎么二分出来的**（这套判据值得复用）：在 handler 入口记 `LINK openUri=…`、
在 resolve 失败处记 `PATH resolve 失败 code=… path=…`。于是：

- 只有 `LINK` 没有 `PATH` 失败 → **链接通了、也 resolve 成功了**，问题在「没开面板」（根因 2）；
- 两条都有、path 还是翻倍的 → 根因 1；
- 提示文案与 code 不符 → 根因 3。

**修法**：
1. `classify` 先剥包裹的 `<...>`（前后空白也容忍）；
2. 把「打开什么」交给调用方（`onOpenSessionFile` 回调），由 ChatDetail 一并
   `filesSheet = true`；
3. `UNRESOLVABLE_CODES` 收紧到只剩 `PATH_OUTSIDE_ROOTS`，其余走错误码表给准确文案。

**教训**：
- **「点了没反应」先分两层问**：事件到 handler 了吗？数据加载后有人负责显示吗？
  这次两层各中一半，只修哪一层都不用。
- **提示文案必须由真实 code 决定**：把「找不到」说成「不在授权根内」，
  比不说还坏 —— 它把用户引向一个根本不存在的问题。

### 诊断浮层贴在 TopStart：连撞四个导航入口（已改为可拖动）

**症状**：屏上诊断浮层（`DiagProbe`）在真机上「点不动」「盖住整页」「抽屉打不开」「返回不了」。

**四次实测，四个不同原因 —— 而前三次的修法都是「调死坐标」，所以第四次必然还会来**：

| 现象 | 根因 | 修法 |
|---|---|---|
| 点一下收不起来 | 浮层从 y=0 起画，标题行落在**状态栏**（0~84px）里 —— 状态栏归 SystemUI，点击被系统截走 | `Modifier.statusBarsPadding()` |
| 盖住整个会话页 | 默认 `expanded = true`，展开时连正文与输入框都看不见 | 默认**收起** |
| 抽屉打不开 | 避开状态栏后浮层落在 y≈135，而 App 的**汉堡按钮也在 TopStart**（同一 y）—— 点击全被浮层吃掉 | 再下移 56 dp（一个 TopAppBar 高） |
| **会话回不去**（2026-10-19 真机第四次） | 下移 56 dp 后浮层落在 y≈300，而**会话详情页的返回箭头也在那一行**（顶栏下方是工作区条，返回箭头在其中）—— 返回键点不到，会话列表回不去 | **改成可拖动**（`dragX/dragY` + `detectDragGestures`，边界取自父容器实测尺寸） |

**教训（这次是真教训）**：给 Compose 悬浮层定位时，「避开状态栏」只是第一步 ——
**App 自己的导航键不止一个，而且不同页面同一位置放着不同东西**
（主页顶栏 y≈180 是汉堡、会话详情页 y≈300 是返回箭头、平板宽屏又是另一套布局）。
**没有一个固定坐标能对所有页面都对**，每加一个页面就多一种撞法。

所以别再调坐标了 —— 前三次都栽在同一个思路里。**要么换锚点，要么做成可拖动**；
本仓 2026-10-19 选了可拖动（只允许往右下拖：初始位置已在状态栏+顶栏之下，
往左上拖只会把它送进状态栏，收益为负）。

**为什么这四条只能真机发现**：单测跑 JVM 没有 SystemUI 与窗口尺寸；
预览/模拟器没有厂商状态栏高度差异。**浮层的可点性必须在真机上验**。

**配套约定（用户 2026-10-19 定）**：诊断浮层挡路时**不要顺手把诊断关掉** ——
那会把取证通道一起关掉，下次排查又没记录。正确做法是**把它拖开**。
每次装新包进 App 后，去「设置 → 操作日志」确认**「记录窗口与会话操作」是开着的**，
那是本仓唯一在真机上还能拿到证据的通道（vivo 封了 `Log.i`，见上文）。

### 客户端自渲染 ≠ 内核会替你画：两处注释都以为对方在画

**症状**：模型撞到输出 token 上限后，电脑端 WebUI 明明显示了一行黄色提示
（「已达到输出 token 上限 / 回答被截断，已有输出保留在对话中。发送"继续"可让模型接着输出。」），
**手机 App 里整条消失** —— 用户只看到回答戛然而止，没有任何解释。

**根因：两条客户端路径的注释都写着「内核会渲染」**

| 位置 | 原注释 | 后果 |
|---|---|---|
| `data/ChatHistoryMapping.kt` 的 `turnEndItem` | 只认 `error` / `aborted`，`else -> null` | `max-tokens` 落进 else，**整条丢掉** |
| `app/src/main/assets/chat.html` 的 `turnFailureFrom` | 「max-tokens 刻意排除 —— **内核把它渲染成黄色警示**」 | 排除之后**没有任何人再画它** |

那句「内核把它渲染成黄色警示」是**对架构的误判**：本项目走的是「客户端自渲染」——
内核只发事件（`turn/end` 的 `reason.kind`），**不会替客户端画任何东西**。
于是两边都以为对方在画，实际没人画。

**内核的真实契约**（`dsh-client-ui-chat/lib/client.js`）：

```
match: (event) => event.type === "turn/end" && event.data.reason.kind === "max-tokens"
```

它注册的是一个**独立的** `turn-max-tokens` 会话节点，并且在
`TURN_PROCESS_INDEPENDENT_KINDS` 里与 `turn-error` **并列** —— 两者是不同的东西：

| | `turn-error` | `turn-max-tokens` |
|---|---|---|
| 语义 | 失败 | **截断，不是失败**（已有输出有效） |
| 颜色 | error（红） | warn（黄） |
| 文案 | `message.turnError*` | `message.maxTokens` / `message.maxTokens.hint` |
| 折叠 | 独立 | **独立**（折进过程组用户就只看到「AI 忽然不说话了」） |

**修法**（两边都补）：
1. 新增 `ChatItemKind.TRUNCATED`（与 `FAILURE` 分开，理由同上表）；
2. `turnEndItem` 先判 `max-tokens` 再走失败分支；
3. 原生渲染 `TruncatedNoticeRow()`（`tertiaryContainer` 黄底 + `WarningAmber`，与 `WarningBanner` 同族）；
4. `chat.html` 补 `isMaxTokens()` + `addMaxTokensNotice()`（`.maxtok` 用 `--warn` / `--warn-bg`）；
5. 文案**逐字**对齐内核 `message.maxTokens(.hint)`，中英各一份资源。

**副产品**：`ChatDisplayGroupsTest` 的穷举断言（所有长度 1..4 的 kind 序列）会因新增 kind
从 1554 条变成 2800 条 —— 那条测试**故意**写死数字，就是要逼人确认「新 kind 该算过程还是消息」。

**教训**：凡是注释里出现「XX 会渲染 / XX 会处理」的跨组件假设，
**必须能指出那一行代码**。指不出来，就是两边都没做。
本项目已因为同类误判踩过一次（`SelectionContainer` 那条空头支票），这次是第二次。

### 表格贴上一行：intellij-markdown 不认无空行 GFM 表格

**症状**（2026-10-20 真机截图）：模型输出了一张合法的 GFM 表格，但 App 里
**没有表格结构**，竖线 `|` 原样显示成一长串文字，换行被折叠成空格：

```
三层结构 | 层 | 安装形态 | 升级方式 | |---|----|---||---
内核 ... | npm 全局 ... | npm i -g ... |
```

**根因：不是换行丢了，是解析器压根没把它当表格。**

先怀疑过「delta 传输把 `\n` 炖没了」，查了 bridge（`chat-adapter.ts` 原样透传
`rawChunk.text`）与 App 拼接（`LiveChatState.append` 纯字符串相加，不 trim），
都干净。最后从内核会话存储 `session.v4.jsonl.zstd` 取出**模型原文**，换行一个不缺，
但标题行与表头之间**没有空行**：

```
**三层结构**
| 层 | 安装形态 | 升级方式 |
|---|---|---|
```

App 用的 `org.intellij.markdown`（markdown-jvm 0.7.3，`com.mikepenz.markdown` 的底层）
在这种「表格头紧贴上一行、中间无空行」的写法下**不产出 TABLE 节点**——
表格行被并进上一个 PARAGRAPH，于是竖线变纯文本。GitHub 网页端能渲染，是因为
GFM 允许这种紧凑写法，**这颗解析器比 GitHub 更严格**。

**实测对照**（同款 0.7.3 jar，证据脚本在工作区 `研究类/table-debug/Probe.java`、
`Probe2.java`）：

| 输入形态 | AST 结果 |
|---|---|
| 普通行紧接表格头（无空行） | 只有 PARAGRAPH，**无 TABLE** |
| 表头前补一个空行 | 正常 TABLE |
| 文档开头直接是表格 | TABLE（无需补） |
| 表格**后**面紧贴文字 | TABLE（后边界不影响） |
| 围栏代码块里的伪表格 | 不受影响（也不该动） |

**修法**：渲染前规范化，只补「前边界」这一个空行 —— 新增
`ui/chat/ChatMarkdownNormalize.kt` 的 `normalizeMarkdownTables()`：
- 识别「本行像表格行 + 下一行是 `---|---` 分隔行」且上一行非空 → 在前面插一个空行；
- **跳过围栏代码块内部**（```` ` / `~~~` 状态机，只认同种符号闭合），否则会破坏代码内容；
- 流式安全：分隔行未到达时不补，避免半截表格期间空行抖动；
- 幂等：已有空行不重复插。
- 接在 `AssistantBody` 最前面（`ChatMessageItems.kt`），**plain 分支也要用规范化后的文本**——
  第一版只改了 segments，而现场这条消息没有 dsh-ui 围栏、走的正是 plain 分支，差点白修。

这是因第三方解析器行为写的适配，按 AGENTS.md §4.3 带 `@patch` 五要素
（id `glued-gfm-table-needs-blank-line`），`ArchitectureGuardTest` 机检。

**教训**：
1. 「显示成一坨竖线」先别赖传输，**取模型原文**核对换行，再用**同款解析器**跑 AST 对照——
   渲染问题的证据在 AST，不在猜测。
2. 改渲染前处理，必须确认覆盖**所有渲染分支**（plain 整段 / 切段后 prose），漏一个分支就是
   「修了但现场没好」。
3. 客户端自渲染路线上，第三方 markdown 解析器与 GitHub 的方言差异要靠客户端兜，
   不能指望模型永远输出最保守的写法。

### 连接状态位是「曾经连上过」，不是「此刻还连着」

**症状**（2026-10-20 真机，用户原话「当网络无法连接时候 都显示的『连接』」）：
连着的时候断网 / 换网、电脑休眠、服务端重启、或锁屏几小时后回来，
连接页仍显示「已连接」+「进入应用」。更难受的是点那个按钮**什么都没发生**：
既不重连、也不进去、没有任何提示。

**根因（三处代码把同一个历史值当成了现状）**：

`HarnessState.connected` 只在**连接成功那一刻**置 true（`connectHostInternal` 与
`loadSnapshot` 两处），而全仓**没有任何代码把它置回 false**。它的真实语义是
「曾经连上过」，但下面三处都当成了「此刻还连着」：

| 位置 | 错误的假设 | 真机表现 |
|---|---|---|
| 连接卡片的 `isActive` | 据此点亮「已连接」 | 断网了还说连着 |
| 按钮点击的短路条件 | `(connected && hostId == activeHostId)` 成立就不重连 | 点了没反应 |
| 回前台要不要复核 | 沿用它做早退判据就永远不跑 | 状态永远不自我纠正 |

那个「点了没反应」还叠了第二层：短路把点击跳过之后，`App.kt` 的门闩
（`!connectGatePassed || !state.connected`）因为 `connected` 仍是 true 而**放行**，
用户被送进一个已经断了的会话页…然后任何一次请求失败又把他弹回来。
整件事在屏幕上看起来就是「按钮坏了」。

**修法（三处判据抽成带名字的纯函数，都有单测）**：
新增 `data/ConnectionLiveness.kt`：
- `shouldRecheckActiveConnection(...)` —— 回前台要不要探活；
- `shouldReconnectOnTap(...)` —— 按钮点击要不要真发连接（判据是**客户端还在不在**，
  不是 `connected`）；
- `hostCardState(...)` —— 卡片三态 `CONNECTED / DISCONNECTED / IDLE`，
  让「连过但断了」与「从来没连过」在屏幕上分得开。

配合 `MainActivity.onResume` → `recheckConnectionIfOnConnectScreen()`：
连接页可见时对当前链路发一次 `healthz`，失败就把 `connected` 翻回 false。
**不走 `launchOperation`** —— 那个出口会弹 snackbar 并置 `busy`，每次回前台失败都吵一句红字，
还把按钮压灰（见下一条）。

**为什么不干脆上心跳轮询**：连接页是必经入口，用户回来看的就是「现在通不通」，
一次探活足够；进了 App 之后由事件流（`eventsConnected`）盯着，再轮询是重复的。

**教训**：
1. 一个状态位被多处读取时，**先把每处的语义假设写成带名字的纯函数**，再谈修界面。
   只改显示、不改判据，另外两处会继续错。
2. `busy` 这种「有操作在跑」的标志，**只能由发起那个操作的公共出口（`launchOperation`）负责置位与复位**。
   有第二条直达路径时，它会漏。

### busy 只在 launchOperation 里收尾：绕过它的路径会把标志永久卡住

**症状**（2026-10-20 真机，修上一条时被顺手抓到）：连接页按钮显示「连接」但是**灰的、点不动**，
而实际上没有任何请求在跑。

**根因**：`connectHostInternal` 里有一行 `busy = true`，而这条函数有**两个调用方**：

| 调用方 | 谁负责清 busy |
|---|---|
| `connectHost` / `pair` / `importConfiguration` | `launchOperation`，它结束时 `copy(busy = false)` |
| `restoreConnection`（冷启动自动重连） | **没有人** —— 它直接 `runCatching { connectHostInternal(target) }` |

于是冷启动自动重连成功后 `busy` 永远是 true，连接页所有按钮的 `enabled` 判据
（`!state.busy && !state.connecting`）一律落 false。转天再看还是灰的，因为这一位只在进程重建时复位。

**修法**：`connectHostInternal` 里改为 `busy = false`，并在注释里写明
「后台请求的转圈由 App 顶栏自己表达，不该借用这个字段」。

**铁律**：`busy` 的所有权归 `launchOperation`。被它调用的函数**不许**自己置 `busy = true`
—— 那条路径可能是别的入口（冷启动、事件回调）进来的，没有收尾者。

---

### 文档链接带位置后缀（:行号 / #L行号）被当成路径的一部分

**症状**（2026-10-20 真机截图）：会话正文里的
`docs/project/STATUS.md:1`、`/…/STATUS.md#L101` 点了之后，
文件详情面板报「**找不到这个路径，可能已经被删了**」——
而那个文件就躺在磁盘上，从「本会话的文件」面板点得开。

**根因两层，同源**：

| # | 根因 | 表现 |
|---|---|---|
| 1 | 位置后缀被原样发给 `roots/resolve`：那不是一个普通文件，文件名里真的多了个 `#L101` / `:1` | 服务端回「不是普通文件 / 找不到」 |
| 2 | 分类用 `indexOf(':')` **一刀切**认 scheme，于是 `docs/x.md:1` 的「scheme」是 `docs/x.md` | 判成**未知协议**，连拼 cwd 的机会都没有 |

`#` 这条更隐蔽：它在 URI 里是**片段标识符**，模型照着 GitHub 的写法写，
而我们此前一直把它当文件名的一部分。

**四种常见写法**（都要剥）：`:1`（第 1 行）、`:12-34`（行范围）、
`:12:5`（行:列，编译器风格）、`#L101`（GitHub 风格，行范围写作 `#L12-L34`）。

**修法**：`ChatLinkTarget.kt` 新增 `stripLocationSuffix()`

- 只认**确定形态**：`:数字` / `#L数字`（含 `:12-34` / `#L12-L34` / `:12:5`），`#L` 大小写不敏感；
- **最多剥两段**（覆盖「行:列」）——`/a/b.ts:1:2:3` 剥成 `/a/b.ts:1` 就收手，不继续吃；
- **剥成空串则不剥**（目标本身是 `:12` 时），否则只是换个错误再报一次；
- 接入点两处：**绝对路径**在 `classify()` 里剥；**相对路径**在拼 cwd 之前剥
  （`ChatLinkHandlers` 改走纯函数 `joinUnderCwd()`，这样拼接逻辑有 JVM 单测盯着）。

**刻意不动的两处**（都有单测钉住，别「顺手统一」）：

- `https://e.com/a#L1` 的 `#` 是**页内跳转**，剥掉会把用户送回页面顶部；
- `http://host:8080` 的端口也不是行号。

保住的办法是「**网页分支返回原文**」——`Web(target)` 传的是未经剥壳的串。
所以谁要是把 `stripLocationSuffix` 提到分支之前统一调用，这两条会立刻变红。

**教训**：

- 链路是「分类 → 拼基准 → 服务端解析」三段，**同一个后缀在每一段都可能是毒**
  —— 只修服务端那一层不够（相对路径压根走不到那里）。
- **剥壳要做成纯函数**。真正的调用点在 Compose 的 `UriHandler` 里，那里测不了；
  抽出来之后「拼错基准」这种最贵的错（拼出翻倍路径 / 打开另一个文件）就能被单测钉住。
- 正则形态受 §长文本正则与重复分组 约束：锚定行尾、无重复分组（`(?:…)?` 是可选组，
  不是 `(…)` 后面跟星号），与仓里已放行的 `\b\d+(\.\d+)?\b` 同型。

---

### 目录结构与接口说明漂移

**症状**：两种表现，都很像「没问题」：

1. 新加了 `ui/XxxScreen.kt`，`docs/project/STRUCTURE.md` 里压根没它。后来的人（或 AI）
   照目录图找实现，翻不到，于是**重新写一份**——同一件事出现两套实现。
2. 某个类/方法改名或搬了文件（`pickApkAsset` 挪走、`RequestGate` 改签名），
   `INTERFACES.md` 还写着旧签名。照着旧签名调，编译报错还算好；更糟的是
   签名「看着还在、语义变了」，代码能编过、行为是错的。

两种都不会让别的检查变红：`docsCheck` 原来的三段只管必需文档存在性、ROADMAP/STATUS
引用、根 README 双站与版本 —— **它压根不读 `app/src/main/java` 的文件清单**。

**根因**：用户的原始要求是「加文档，**后期改代码要同步改**」，而「请记得同步」是**靠自觉**。
本仓对这件事有实锤先例：README 双站漂移连续三版（v2.2.33→v2.2.35），根因正是
「那份文档不在任何机检范围内」。同样的敞口不能在新文档上再开一次。

**实发案例（2026-10-21，落地当天就撞了两次）**：

- **第一次：下限断言抓到自己。** 首跑 `docsCheck` 直接红 ——
  `STRUCTURE.md 锚点只解析出 0 行（预期 ≥ 100）`。根因不是文档格式，
  是 Kotlin 的 `Regex("""^\\|…""")` **默认不带 MULTILINE**（Java/Kotlin 的 `^` 只匹配输入开头，
  与 JS 的 `/gm` 不同），130 行多行表格一行都没匹配上。
  **如果当时没写下限断言，这次会「一条没查」却全绿** —— 这正是它要防的假绿，
  只是第一次抓的是写门禁的人自己。
- **第二次：反向断言兑现。** 负向实测删掉 `RequestGate.kt` 那一行锚点，
  立刻报「未登记进 STRUCTURE.md 机检锚点」。这验证了「源码 → 文档」那一向真的在工作 ——
  只查「文档 → 源码」的话，**新增文件永远发现不了**，而那才是高频的那一半。

**现在怎么办**：

- `docs/project/STRUCTURE.md`：目录树、分层与依赖方向（含**禁止的依赖方向**）、
  逐目录职责、关键文件速查、**「新增东西放哪里」决策表**，文末 `<!-- structure-check -->` 锚点。
- `docs/project/INTERFACES.md`：消费的 bridge 端点总表、事件流、能力位、内部模块符号与签名、
  **改接口检查清单**，文末 `<!-- interfaces-check -->` 锚点。
- `build.gradle.kts` 的 `DocsCheckTask` 新增第四段，**双向 + 下限断言**：
  1. 文档 → 源码：锚点路径必须存在且类型对（`file`/`dir`）；锚点声明的标识符必须能在
     指出的文件里 grep 到；
  2. 源码 → 文档：`app/src/main/java/` 下 111 个 `.kt` 每个都必须登记在锚点里；
  3. 下限：`structure` < 100 条或 `interfaces` < 25 条直接红，报「表格格式可能变了」。

  负向实测（三条全红，恢复后逐字节复核）：删锚点行 → 「未登记」；
  `pickApkAsset` 改 `…TYPO` →「源码里找不到」；删 `structure-check:end` →「缺少机检锚点」。

**铁律**：

- **新增/移动/删除 `app/src/main/java` 下文件、改公开签名或消费的 bridge 端点 → 同一提交改锚点**，
  不允许「下个提交补」。门禁红着提交只是把红转给下一个人。
- 目录图与签名**只在两份文档里写一次**；`AGENTS.md` 只留指针、不抄清单
  （抄了必漂，见 §README 双站漂移的教训）。
- 锚点的 `<!-- …-check:begin/end -->` 与表头是**机器契约**，改格式必须同批改
  `build.gradle.kts` 的 `DocsCheckTask`。
- 判据要**双向**且**带下限**。只查一向、或只查「非空」的写法，
  在「新增文件」与「格式变更」这两个真实场景下都会静默放行 —— 本仓这两类都真发生过。
- **写门禁时的语言差异要记牢**：Kotlin/Java 的 `^` 默认不 MULTILINE，JS 的 `/gm` 是。
  这类「解析器自己的 bug」与「文档格式变了」表现相同，所以下限断言的报错文案要能区分二者。

### 空助手消息把过程组劈成几十个组头

**症状**：会话页「不展开折叠」时，整屏只有一排「耗时 8 分 33 秒 · 42 次工具调用」，
正文像丢了；用户原话「工具加载过多，当前页面只显示耗时 xxx x次工具调用」。

**根因**：真实会话里 `assistant/message` 绝大多数**只有 reasoning + tool-call、没有 text**
（某会话实测 46 条里 45 条）。这类消息仍映射成 `ASSISTANT` 展示项、`body` 为空串，
而 `isProcessItem()` 曾经把 `ASSISTANT` 一律当消息 → 每两条过程项之间插一条零高度空消息 →
`flushRun()` 反复触发 → 一整轮工具调用被劈成几十个单条过程组。

**判据**：`chat/sessions/{id}/messages` 里数 `assistant/message` 的 `content` 类型；
若 `text` 占比极低而 `tool-call` 很高，就是本坑。

**修法**：`ASSISTANT` 只在**有正文或带图片**时算消息（`ChatDisplayGroups.hasVisibleBody`），
空的折进过程组。回归用例见 `ChatDisplayGroupsTest.emptyAssistantMessageFoldsIntoTheGroup`。

### 内核 schema 是 schemastery，不是 JSON Schema

**现象**：通用设置页能列出 22 个命名空间、能点进去，但**每个字段都是一行灰字**：
字号那栏孤零零显示「14」，点不动、没有输入框、没有下拉，「保存」永远是灰的。
看起来像「后端不让写」，实际是**前端一个字段都没认出来**，于是全走了只读兜底。

**根因**：`settingsController.describe()` 的 `schema` 不是 JSON Schema，而是 cordis 的
**schemastery 引用图**：

```json
{"uid":12427,"refs":{
  "12411":{"type":"const","value":"compact"},
  "12412":{"type":"const","value":"standard"},
  "12417":{"type":"union","meta":{"loose":true},"list":[12411,12412]},
  "12427":{"type":"object","dict":{"transcriptView":12417}}
}}
```

于是 `schema.properties` 是 `undefined` —— 只认 JSON Schema 的解析器**必然**全字段只读。
枚举在 schemastery 里是 `union` 指向若干 `const`，不是 `enum` 数组；数字的边界在
`meta.min/meta.max`，不是 `minimum/maximum`；密钥位是 `meta.role == "secret"`。

**修法**：`SettingsSchema.propertiesOf()` 先按 JSON Schema 取，取不到再调
`Schemastery.expand()` 把引用图摊平成 JSON Schema 子集（`type/enum/minimum/maximum/default/x-secret`），
两者任一命中都能渲染出正确控件。摊不出来时**必须**回退只读，不许猜类型。

**教训**：
- 「能列出东西」不等于「能操作」。验收时要**真的去改一个值并看到落库**，
  只看列表渲染成功会把只读页面判成通过。
- 桥把内核对象**原样透传**时，等于把内核的私有格式变成了 wire 契约。
  要么在桥上归一化，要么消费端做兼容层 —— 两边都不做，就是这次的「看起来有、其实没有」。

### 设置页照抄 WebUI 命名空间：一排拨不动的开关

**现象**：通用设置页把内核的 22 个命名空间全列出来，其中 `ui-theme`、`ui-chat` 这些
点进去改完保存也「成功」了 —— 但**手机 App 的界面一点没变**（聊天正文是 Compose 自绘，
字体的缩放走 App 自己的字号设置）。用户的原话是「对无法设置的内核没必要显示；
不能在 App 上起到作用的都没必要显示」。

**根因**：内核的设置命名空间是**按消费者**划分的。`ui-*` 那批的消费者是浏览器端 WebUI
（`@deepseek-ai/dsh-client-ui-*`），原生 App 一个字都不读。把它们照抄进 App 的设置页，
等于给用户一排「拨了没反应」的开关 —— 比不给更糟，因为用户会先怀疑 App 坏了。

**修法**：`SettingsNamespaces.kt` 的 `List<SettingsNamespace>.forNativeApp()` 两道过滤：
1. 命中 `WEBUI_ONLY_NAMESPACES`（`ui-theme` / `ui-chat` / `ui-conversation` /
   `ui-settings` / `ui-settings-general`）的整段隐藏；
2. `hasEditableField()` 为假的隐藏（全是只读：对象、数组、摊平失败退化的行）——
   「看得到、改不了」的页面同样没有存在价值。
入口的计数、列表、详情查找三处**都走同一份过滤结果**，否则会出现「计数 22、
列表 17、点进去 404」的错位。

**教训**：
- 设置项的价值判据不是「服务端有」，而是「**改了会在这台设备上产生可见效果**」。
  搬渠道时照抄列表最容易犯这个错（WebUI 的清单天然包含只给 WebUI 用的键）。
- 过滤规则写成**纯函数**（`forNativeApp()`）而不是散在 Composable 里，
  才能被 JVM 单测覆盖 —— 否则这类「少显示一行」的 bug 没人会写测试。

### 设置页「能看不能存」：配对默认没有 settings.write

**症状**：设置页能列出命名空间，点进去控件都能拨、保存键也是黑的，点保存后什么都没发生
（真机 2026-10-22 实测：表单停在脏状态，用户判定「这个 App 改不动」）。

**根因**：桥对 `PATCH /api/v1/settings/namespaces/{ns}` 要求 `settings.write` scope，
而配对默认给的五档是 `chat.read / chat.write / files.read / files.write / settings.read`
—— **没有** `settings.write`。所以 curl 直接返回：

```
403 {"error":{"code":"SCOPE_REQUIRED","message":"The settings.write scope is required."}}
```

**判据**：`GET /api/v1/devices/self` 的 `scopes` 里有没有 `settings.write`。
**不许按桥版本号猜** —— 这是授权问题，不是版本问题。

**修法**：入口按 scope 二段门控（`data/SettingsNamespaces.kt` 的 `canWriteSettingsNamespaces()`）：
没有 `settings.read` 说「需要 settings.read」；有读没写说「需要 settings.write 权限才能在这里修改
（在电脑端「已配对设备」里勾选）」；两者都有才画表单。

### 刷新历史把「加载更早」的成果抹掉，屏幕上只剩一片空白

真机实测（2026-10-22，用户报「还是不显示」）：点「加载更早消息」→ 内容出现 →
一秒后整屏变空白，只剩一个「耗时 … · N 次工具调用」组头。

`DiagLog` 的 SCROLL 行是决定性证据：`totalItemsCount` 在 5 与 2 之间反复回退 ——
**加载出来的旧页被下一次刷新替换掉了**，而视口还停在原来的像素偏移上，
于是落在没有内容的区域里，看起来就是"什么都没显示"。

根因：`refreshHistory()` 拿回的是**最后一页**，赋值时用新页整体替换；
用户此前翻出来的更早几页随之消失。

修法：`mergeRefreshedHistory(previous, fresh)` 保留「新页第一条 seq 之前」的历史，
再拼上新页；`hasMore` 延续上一页的结论（更早的确实还没读完）。
4 个单测在 `HistoryMergeTest`。

判据：以后遇到「加载更早之后空白」，先看 `grep SCROLL files/diag.log` 里的
`totalItemsCount` 有没有回退，再看 `history.events` 的条数，别先怀疑渲染。

### 建议面板硬截 N 条又不可滚：第 N+1 条之后的命令既看不见也够不着

真机实测（2026-10-29，用户报「输入 `/` 命令不能上滑，其他的命令无法看见」）：
打一个 `/` 只列出 `/acp` `/acp-cache` `/export` `/feedback` `/goal` 五条，
而桥上 `GET /api/v1/chat/sessions/{id}/commands` 实际返回 **9 条** ——
`/mcp` `/permission` `/plan` `/tunnel` 四条凭空消失，面板又不能滑，
用户唯一的补救是「猜出命令名再手敲」，等于把命令的发现性彻底废掉。

根因是两处叠加，任一处单独存在都不会这么糟：
`ChatSuggestions.kt` 的 `commandSuggestions()` 末尾写着 `.take(5)`（**数据层截断**），
而 `CommandSuggestions` 的选项列表是普通 `Column` + `forEach`（**没有滚动容器**）。
截断本身不算错——`MentionSuggestions` 当年也截 8 条——但它早就改成了
`LazyColumn(Modifier.heightIn(max = 264.dp))` 并在注释里写明「列表已经可滚动，就不该再硬截」，
命令面板**漏做了同一件事**，于是「截断」从优化变成了唯一的出口。

修法：`commandSuggestions()` 删掉 `.take(5)`；`CommandSuggestions` 的 `forEach`
换成 `LazyColumn(Modifier.heightIn(max = 264.dp)) { items(suggestions) { ... } }`，
与 `MentionSuggestions` 同一套做法。6 个单测在 `CommandSuggestionsTest`
（第一条就叫 `doesNotTruncateWhenTheHostSendsMoreThanFiveCommands`，钉住「9 条全出」）。

判据：以后凡是「列表条数由宿主决定」的面板，**先看它能不能滚，再看它截不截**。
两者同时为「不能 / 截」，后面的条目就是永久不可达；只截不滚比只滚不截更危险，
因为屏幕上完全看不出少了东西——用户只会以为自己记错了命令名。

### WebSocket 断连只挂 onClosed：桥重启后连接永远「还在」

**症状**：桥进程重启（或反代断开）之后，App 侧 WS 连接看上去一直「还连着」——
状态不降级、不重连、也不报错，直到 OkHttp 的 `pingInterval`（本仓 20 秒）
连续失败才被发现。如果对端是被 `kill -9` 走的、连 close 帧都没发，那就是
`onFailure` 能兜住；但**只要对端发得出 close 帧**（正常退出、反代主动断），
客户端就只收到 `onClosing`，`onClosed` 可能永远不来。

**根因**：OkHttp 的 `WebSocketListener` 把「对端请求关闭」和「关闭握手完成」
拆成两个回调。收到对端的 close 帧时先回调 `onClosing`，此时**需要应用把 close 帧回过去**
（`webSocket.close(1000, null)`）才算握手完成，然后才轮到 `onClosed`。
对端发完 close 就销毁 socket 的情况下，我们回过去的 close 帧没人收，
`onClosed` 就悬着不来了。只重写 `onClosed` 的监听器 = 漏掉这条最常见的断线路径。

本项目是 **MockWebServer 单测先撞出来的**（`MobileWsTransportTest` 的重连与
`RestOnly` 三条同时卡在 `WsDown(reason=尚未建立连接)`），用一段裸 OkHttp 探针
把回调序列打出来才定位到：`client onOpen` → `client msg` → `client closing 1000 bye`
→ 之后就没了。真机上表现更隐蔽，因为 20 秒 ping 超时最终也会触发 `onFailure`，
看起来「只是慢」而不是「断了」。

**现在怎么办**：`WebSocketListener` **必须同时重写 `onClosing` 和 `onClosed`**，
两者走同一段断线处理（本项目抽成 `onDisconnected(code, reason)`）。
`onClosing` 里额外做两件事：

1. `runCatching { webSocket.close(1000, null) }` 把 close 帧回过去，完成握手；
2. **立刻让本连接的后续回调失效**（`MobileWsTransport` 用 `generation` 自增，
   回调开头 `if (gen != generation.get()) return`）。不退休的话，随后迟到的
   `onClosed` 会再排一次重连 —— 一次断线排两次重连，指数退避会被打乱，
   严重时形成重连风暴。

判据：以后凡是写 `WebSocketListener`，**先问「对端正常 close 时我怎么知道」**。
只挂 `onClosed` 就是没答案。测试上也要用「服务端 `onOpen` 里发完帧就 `close`」
这种最朴素的场景压一遍——这正是 MockWebServer 的默认行为，反而最容易暴露。

### 契约文档写着「requestId 回显」，实测一半的帧不回显

**症状**：按桥的 `asyncapi.yaml` 和本项目设计文档实现 WS 请求-响应关联（发 `requestId`、
等 id 匹配的回帧），结果 `sessions` / `history` / `workspaces` / `models` / `commands`
这几类**全部等满超时**；而 `goal` / `agent-team` / 文件类 / 语音类却正常。
客户端表现为「一进会话列表就卡 15 秒然后连接断开重连」，看起来像网络问题。

**根因**：桥 `src/host/ws-handler.ts` 的 `sendFrame(webSocket, frame: { kind: string; data?: unknown })`
—— **外层信封结构里没有 `requestId` 的位置**。只有各域处理器自己往 `data` 里塞的处理才回显。
实测统计：`ws-sessions.ts` / `ws-models.ts` / `ws-commands.ts` / `ws-hitl.ts` / `ws-presets.ts` /
`ws-host.ts` / `ws-settings.ts` 里 `requestId` 出现 **0 次**；只有 `ws-goals.ts` / `ws-team.ts` /
`ws-files.ts` / `ws-file-transfer.ts` / `ws-speech.ts` / `ws-schedule.ts` 有。
**而 `asyncapi.yaml` 初版与设计文档都写着「统一回显」** —— 文档描述的是“本应有的样子”，
不是“现在的样子”。

**怎么发现的**：不是读代码发现的，是**用真实 token 连运行中的桥逐帧发请求、打印回帧的
`requestId` 位置**发现的。裸 WS 探针（`ws` 包 + 子协议 `dsh-mobile-v1, dsh-auth.<token>`）
跑一遍十分钟，比读 13 个域处理器一百行注释快得多。
**MockWebServer 测不出来**：它老老实实回显你伪造的回帧，永远「通过」。

**现在怎么办**：

1. **凡是"另一个进程发的"协议，先拿探针实测再写代码**，不要从契约文档反推行为。
   验收判据是「回帧里那个字段真的在」，不是「文档说它会在」。
2. 客户端做**双兼容**：优先按 `requestId` 配对，没有则按响应 `kind` 兜底；
   兜底路径**必须按 kind 串行化**（同 kind 并发会拿错响应）。
3. `error` 帧不带 `requestId`，只带 `data.requestType`（是**请求帧的 type**，不是响应 kind），
   所以只能失败该 type 下最老的那条在途请求。
4. **不要因为一次静默帧超时就 `close(1001)` 断连** —— 那是把「这一帧本来就不回显」
   误判成「连接坏了」，会引发无谓的重连风暴。

判据：以后凡是写「等某个 id 匹配的响应」，先问一句**「对端真的会回这个 id 吗，
我怎么验证的」**。答不上来就先去实测。

### 两个事件通道交接时，旧通道的「告别」会把新通道的状态改回断开

**症状**（2026-10-29 真机，WS 优先改造 P2 落地后）：会话正文正常渲染、内容实时更新，
说明推送好端端地在跑；但顶栏一直是红点 +「已断开」。
进任何一个会话都一样，重启 App 也一样。

**根因**：`DshClient.EventSubscription.close()` **无条件**回调 `connection(false)`
（`data/DshClient.kt:742`）。而 `reconcileEventChannel()` 在 WS 抢到事件流时，
做的第一件事就是**关掉那条老的 REST 流**：

```
handleEventsConnection(true)     // 刚置上「连上了」
  → eventSubscription?.close()    // 关 REST 流
    → connection(false)           // 立刻被改回「断开」
```

于是 `eventsConnected` 在这两个通道交接的几毫秒里被写成 true 又写成 false，
最终停在 false。**两个通道各自都是对的，错的是「谁说了算」没有定义。**

**怎么发现的**：一开始怀疑渲染判据、怀疑 `snapshot.copy()` 冲掉字段，都不对。
最后是在 `handleEventsConnection` 里加一行 `DiagLog.i`，真机跑一遍，
diag.log 末尾赫然是 `事件连状态 → false` —— 一个**没人该发的** false。
判据：**状态位被改成意外值时，先查"谁还有权改它"，而不是查"谁没改它"**。

**现在怎么办（铁律）**：

1. **同一时刻只有一个事件通道在服役，状态位听服役者的。**
   `handleEventsConnection(false)` 在 WS 可用时必须丢弃：
   ```kotlin
   if (!connected && wsEventsUsable()) return
   ```
2. 凡是 `close()` 会回调状态的订阅对象，**接手方必须显式声明"我接管了"**，
   不能指望旧对象安静地退场 —— 它的 clenup 回调分不清「我该走了」和「大家都断了」。
3. 排查这类问题**不要靠读代码猜**：加一条日志、真机跑一遍，比在三个文件里
   推断状态流转快一个数量级（同 `### 契约文档写着「requestId 回显」` 那条的教训）。

判据：以后凡是出现「A 上线同时 B 下线」，先问一句**「B 下线时会不会顺手把 A 的状态位写坏」**。

### 整份状态重建只填服务端字段，其余五十多个字段被打回默认值

**症状**：点一下顶栏刷新（或导航面板里的刷新），顶栏立刻显示「已断开」，可推送其实好好的；
或者「关于」页的桥版本变成「版本未知」、权限档位切换没了、定时任务入口凭空消失、
正在看的对话正文变空。重启 App 又全好了 —— 于是第一反应永远是「网络不稳」。

**根因**：`loadSnapshot()` 原来 `return HarnessState(...)` —— **全新构造**。而 `HarnessState`
有 72 个字段，快照只填 16 个，剩下 56 个（`eventsConnected`、`bridgeVersion`、
`bridgeCapabilities`、`permissionCatalog`、`history`、`sessionModels`、`commands`、
`jobsPanel`、`schedulePanel`、`goalAction`……）全部落回构造参数的默认值。
`refreshDashboard` 又把这份新状态整个盖到共享状态上，于是运行态被一次刷新清空。

**为什么此前只补了两个字段**：2026-10-29 先发现 `eventsConnected`（刷新即"掉线"）与
`goalAction`，就在 `refreshDashboard` 的 `snapshot.copy(...)` 里手工加了
`eventsConnected = eventsConnected` / `goalAction = goalAction`。这两行**看着像在保留字段，
实际只保住了被点到的那两个** —— 剩下 54 个还在漏。这就是「加一个补一个」的典型结局：
逐字段点名的写法，漏掉的那些不会报错，只会安静地回默认值。

**现在怎么办（铁律）**：

1. **重建方向要反过来：默认全带，只覆盖服务端那份。**
   `loadSnapshot(api, device, base: HarnessState = HarnessState())` 里 `return base.copy(...)`，
   只写那 16 个服务端字段。新字段加进 `HarnessState` 时**不需要改这里**，它自动被带过。
2. **两种调用点语义不同，都要显式写出来**：
   - 刷新（`refreshDashboard`）传 `base = mutableState.value`；
   - 新连接（`connectHostInternal`）**不传**，用默认空状态 —— 换一台电脑不该继承上一台的
     会话正文、选中会话、问答状态。
3. **不要再用「自我赋值」假装保留字段**：`eventsConnected = eventsConnected` 这类写法
   会被 `SnapshotCarryOverTest` 判红，它同时盯着「`loadSnapshot` 不许再 `HarnessState(`」
   「刷新必须传 base」「连接路径不许传 base」三条。

**判据**：看到一个 `data class` 有几十个字段、而「重建」处只列了十几个，
就要问一句**「没列的那些去哪了」**。凡是靠逐字段点名来"保留"的写法，早晚会漏。

**同类**：`### busy 只在 launchOperation 里收尾`（同一个模式的另一面）、
`### 两个事件通道交接时，旧通道的「告别」会把新通道的状态改回断开`（同一次排查中发现）。

### 共用 kind 的应答：靠 data.type 判别，漏一个就会互相把请求「确认」掉

**症状**：调用方报成功，但服务端实际做了另一件事。具体见过的形状：
发消息返回成功、可消息没发出去 —— 其实是一条迟到的「切模型」确认把它认领了。
症状出现在**用户层**（"我发的话没了"），而代码层每一层都自认为成功了，
所以排查通常从网络、从后端、从数据库一路查到底也查不到这里。

**根因**：`/ws/mobile` 的应答通道复用了几个"通用 kind"，同一个 `kind` 下
不同请求的应答字段长得几乎一样，只能靠 `data.type`（请求帧名字的回显）区分。
`sent` 这个 kind 目前有 **五个** 生产者（`src/host` 侧逐个核对过）：

| 请求帧 | 应答 | `data` 形状 |
|---|---|---|
| `message` | `sent` | `{type:'message', sessionId, accepted:true}` |
| `unsubscribe` | `sent` | `{type:'unsubscribe', acknowledged:true}`（**无 sessionId**） |
| `select-model` | `sent` | `{type:'select-model', sessionId, accepted:true, selected}` |
| `session-agent-preset` | `sent` | `{type:'session-agent-preset', sessionId, accepted:true}` |
| `select-agent-preset` | `sent` | `{type:'select-agent-preset', sessionId, accepted:true, agentPresetId}` |

后四者的 `sessionId + accepted` 与 `message` **完全同形**。所以只要判别式是
「sessionId 对上 + 有 accepted」，就必然互相抢 —— 谁先到谁赢，而"赢"的一方
拿到的是别人的成功回执。

**怎么发现的**：P3 接 `select-model` 时只数出三个生产者，用
`data.type != "select-model"` 挡了一下；后来接 agent 预设时才发现是五个 ——
也就是说**每接一个新帧，所有旧判别式都要回头改一遍**。这个结构注定漏。

**现在怎么办（铁律）**：

1. **判别式写成"排除表"，不写成条件链**。`data/ws/WsPorts.kt` 有
   `NON_MESSAGE_SENT_TYPES` 一处集中列出"不是 message 确认"的那些 `type`，
   matcher 写成 `data.sentTypeOf() !in NON_MESSAGE_SENT_TYPES`。
   新增一个 `sent` 生产者只需往表里加一行，不用去翻每个 matcher。
2. **每个新帧都要双向钉**：不只测"我的应答能完成我的请求"，还要测
   「别人的应答**不许**完成我的请求」—— 后者才是真正会静默出错的那一半
   （`WsSessionPortTest` 里两两各一条）。
3. **判别字段优先选"只有我有"的**：有 `approvalId`/`callId` 就别靠 `sessionId`；
   实在没有（如 `session-create` 的应答只有新 id）就承认它没有判别字段，
   靠 `WsRequestRouter` 的同 kind 串行兜住，并在注释里写明这个取舍。

**判据**：写 `matcher` 时先问「这个 kind 还有谁能发」，把答案列完再写。
回答不出来就去 `src/host/` 里 `grep "kind: '"` 数一遍 —— 别凭印象数。

**同类**：`### 契约文档写着「requestId 回显」，实测一半的帧不回显`
（同一条通道的另一半问题：应答根本拿不到稳定的关联 id）。

### 同一份列表，REST 包 items、WS 直接给数组：套同一个反序列化模型必炸

**症状**（真机 v2.8.2，2026-10-30）：新建会话后会话列表立刻报错
`SEND 请求失败：JsonDecodingException: Expected JsonObject, but had JsonArray as the
serialized body of ...data.ws.SessionList`。会话其实**创建成功了**（diag 里
`session-create ← session-created` 正常），死在紧接着刷列表那一步。桥一切正常，
REST `GET /chat/sessions` 也正常 —— 只有走 WS 的列表炸。

**根因**：两条通道对「列表」的包裹不一样：

- REST：`GET /chat/sessions` → `{ "items": [ {...}, {...} ] }`（对象包一层 `items`）。
- WS：`sessions` 帧 → `{ kind:'sessions', data:{ sessions: [ {...} ] } }`，
  `data.sessions` **直接就是数组**（桥 `ws-handler.ts` 的 `handleSessions`：
  `data: { sessions }`，`sessions = await chat.listSessions(...)` 本就是数组）。

`WsSessionPort.sessions()` 却复用了 REST 的 `SessionList(val items: List<ChatSession>)`
去解 `data.sessions` —— 拿 JsonArray 喂一个期望 JsonObject 的 data class，必抛
JsonDecodingException。修复：WS 侧直接
`decodeFromJsonElement(ListSerializer(ChatSession.serializer()), items)`，
不再套 `SessionList`。

**为什么单测漏了**：`WsSessionPortTest` 之前**根本没有 sessions 帧的用例**
（只测了 history / createSession / select-agent-preset），读码时 `data:{sessions}`
看着和 REST 的 `{sessions:{items:[]}}`「差不多」，就顺手复用了模型。补了
`sessions 回帧里 sessions 直接是数组（不是 REST 的 items 包裹）` 一条钉住。

**怎么发现的**：真机 diag.log 里 `#570/#573` 连续两条同样的解码异常，且都紧跟在
`session-create ← session-created`、`sessions ← sessions` 之后 —— 创建成功、列表失败，
把锅精准定位到解码。再用裸 WS 探针打印真帧：`data keys=[sessions,requestId]`、
`Array.isArray(sessions)=true`，一分钟定案。**又是「手造回帧测不出来」那一类**：
你喂什么形状测试就过什么形状，只有真桥给的帧才会拆穿假设。

**现在怎么办（铁律）**：

1. **WS 帧的每个数组字段都按桥源码的真实形状解**，不要因为「语义都是列表」就复用
   REST 的包裹模型。接入前 `grep "kind: 'xxx'" src/host/ws-*.ts` 看它 data 里放的
   是数组还是 `{items}`。
2. **每接一个 WS 帧就配一条「真帧形状」用例**：数组就喂数组，别喂对象。
   词汇表（帧名对不对）测不出形状错，形状必须单独钉。
3. 已核实同模块的 `workspaces` 帧也是 `data:{workspaces:[...]}` 裸数组，但 App 的
   工作区列表目前仍走 REST（没接 WS 帧），暂不受影响；将来接入时照此办理，别再踩。

**判据**：凡是「换了条通道复用旧反序列化模型」，先问一句「这条通道对同一个概念
包不包裹、用什么键名」，并用真帧（或读桥源码）确认，别让模型替你假设形状。

**同类**：`### 契约文档写着「requestId 回显」，实测一半的帧不回显`
（都是「想当然以为两条通道行为一致」，真帧才拆穿）。
