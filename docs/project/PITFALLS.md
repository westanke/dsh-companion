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

**现在怎么办**：README 双站「安装要求」与「项目信息」两处写的必须一致（曾一处 2.3.3、一处 2.3.1）。
当前口径：**≥ 2.3.7**，并在括号里注明分项。

**卡片这一项为什么单列**：2026-10-16 才定位清楚——它曾连续坏在**两个**插件侧病根上，
App 侧代码自始至终是对的。① `sessionIdOfAgent` 漏了内核 `Agent.id`（bridge 2.3.6 修）；
② 两个钩子注册时没带 Cordis 的 `{ prepend: true }`，被排到 waterfall 链尾、永不被调用
（bridge **2.3.7** 修，含 Cordis 源码行号，见 `dsh-remote-bridge/docs/project/PITFALLS.md`
§waterfall 不 prepend 就等于没注册）。
**教训**：卡片不弹时**先查插件版本**再查 App —— 症状完全一样（手机零卡片、电脑端正常、
两侧日志都不说一句话），但修的地方在另一个仓库。

**铁律**：新增 bridge 依赖时，同步更新 README 双站 + `STATUS.md`，别只改一边。

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
