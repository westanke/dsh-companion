# AGENTS.md — dsh-companion 工程宪法

> 本文件是每次会话**必然读到**的那份约定。规矩写在这里，不靠记忆、不靠口头。
> 按本项目（Android 原生客户端 + 服务端 bridge）的特色裁剪，见文末「制度来源」。
>
> **体积纪律**：本文件只写**命令、契约、触发词与一两行的铁律**；
> 「说不清、要摆证据」的内容一律进 `docs/project/`（坑位 → `PITFALLS.md`，设计 → `decisions/`），
> 这里只留指针。超长会被静默截尾，读者就丢了末尾的规矩。

---

## 0. 一分钟上手

| 我想… | 先读 |
|---|---|
| 知道现在到哪了 | `docs/project/STATUS.md` → `ROADMAP.md` |
| 知道某能力有没有、差什么 | `docs/project/PARITY.md` |
| 知道某文件/目录干什么、新文件该放哪 | `docs/project/STRUCTURE.md` |
| 知道某模块导出什么、接口签名长什么样 | `docs/project/INTERFACES.md` |
| 知道为什么这么设计 | `docs/project/ARCHITECTURE.md` → `docs/project/decisions/` |
| 知道这次改了什么、测了什么 | `docs/project/CHANGELOG-DEV.md` + `app/build.gradle.kts` 逐版注释 |
| 改代码前避坑 | `docs/project/PITFALLS.md`（名字 = 触发词 = 锚点） |
| 发版 | `docs/project/RELEASE.md` |

> **每轮开工先读 `STATUS.md`**；涉及界面时同时读 `design-system/…/MASTER.md`。
> 本 App **只使用 bridge 公开版本化 API**；发现能力不足时，先在 `dsh-remote-bridge` 仓库
> 更新 API / OpenAPI / AsyncAPI / SDK / scope / 合约测试，再接入 App。

---

## 1. 本项目是什么（一句话与边界）

- **定位**：DeepSeek Harness 的 Android **原生客户端**（Kotlin + Jetpack Compose + Material 3）。
- **上游**：`Hakunm/dsh-android-app`（AGPL-3.0）的 fork，保留完整提交历史。
- **与谁配**：服务端插件 `dsh-remote-bridge`（AGPL-3.0，**≥ 2.3.7**；与 README 双站的安装要求一致）。
  **App 不自带任何 DSH 能力。** 客户端会解析 `connection.ready` 的能力位做运行时探测
  （见 `data/BridgeCapabilities.kt`），因此「最低版本」只是**安装门槛**，不是功能判据。
- **不做什么**：不跑内核、不代理官方 WebUI、不打包 Ubuntu。把 DSH 搬进手机是另一条路线（DSHA 系），
  与我们**互斥不互补**——详见 `docs/project/decisions/` 与 `dsh-study/` 下的对比研究。
- **三端拓扑（2026-10-16 起）**：Gitee 主站 + AtomGit 备站（本地 `bash scripts/push-all.sh` 推这两个），
  GitHub 是镜像从站，**不手工推**——由 `.github/workflows/mirror-sync.yml` 每 3 小时从 Gitee 同步代码/tag/Release
  （Gitee 不通回退 AtomGit）。远端命名用 `scripts/setup-remotes.sh` 整理。链接规则见根 README。

---

## 2. 命令（本地唯一可信口径）

```sh
bash scripts/release.sh             # 发版·阶段 A：检查 → 门禁 → 打包 → 回填真实数据
bash scripts/release.sh --publish   # 发版·阶段 B：tag → 双推 → 两站 Release → 全渠道回读
scripts/build.sh                    # 默认 assembleDebug
scripts/build.sh assembleRelease    # 发布包
scripts/build.sh :app:testDebugUnitTest   # 单元测试
scripts/build.sh docsCheck          # 文档一致性（10 文档 / ROADMAP 任务状态 / STATUS 引用 / 目录与接口锚点双向）
```

> **发版走 `scripts/release.sh`，不要手敲清单**。它按 `RELEASE.md` 的顺序铁律**分成两阶段**：
> 回填真实数据发生在提交**之前**（⑤→⑥→⑦），一口气跑到底会让 tag 指向一份
> 「文档还是旧数字」的提交。回填由 `scripts/backfill-release.py` 做锚点式精确替换 ——
> **不许改回全局正则**：那会把 README 里 v2.2.41 / v2.2.40 这些历史版本记录一并改成当前版本。
> 本仓最高频的失分点是 README 双站漂移（`docsCheck` 不覆盖 README，v2.2.33–v2.2.35 连续三版漂过），
> 这个脚本就是为它兜底的。姊妹仓 `dsh-remote-bridge` 的同名脚本是**一次跑完**（没有回填环节），
> 两边的阶段数不同是**刻意**的，别互相对齐。

**为什么必须用 `scripts/build.sh` 而不是 `./gradlew`**（三条理由都在 `scripts/build.sh` 头注释里）：
1. `gradle-wrapper.properties` 指向 `services.gradle.org`，国内不可达，且 wrapper 失败**不报错、只挂起**；
2. `maven.google.com` 不可达，需经镜像（用**本项目自带**的 `scripts/init-mirrors.gradle`，
   工具链那份会与 `settings.gradle.kts` 的 `FAIL_ON_PROJECT_REPOS` 冲突直接构建失败）；
3. Gradle 对同一项目目录互斥，脚本用 `flock` 串行化。

> **CI 口径**：`.github/workflows/android.yml` 跑
> `docsCheck testDebugUnitTest lintDebug assembleDebug`，四项都是硬门。
> 2026-10-16 起历史 16 个 lint error 已清零（0 error / 52 warning / 4 hint，无 baseline）；
> AGP 默认只对 **error** 判失败，warning（依赖升级、plural、unused resources 等）不拦截。
> 新增代码若触发 lint error 必须修；确属误报才在**调用点** `@SuppressLint` 并写理由。
> hosted runner 可直连 services.gradle.org 与 maven.google.com，无需本项目的
> `init-mirrors.gradle`（那是本机/CN 用的）。本地可用 `scripts/build.sh :app:lintDebug` 复跑。

---

## 3. 版本与文档同步（本项目最高频的失分点）

| 改动 | 必须同步 |
|---|---|
| `versionCode` / `versionName` | `app/build.gradle.kts` 逐版注释、`docs/project/CHANGELOG-DEV.md`、`STATUS.md`、**README 中英双站** |
| APK 体积 / SHA-256 | `README` 中英双站 + `artifacts/SHA256SUMS.txt` |
| 测试类数 / 用例数 | `README` 中英双站 + `TESTING.md`（口径：数 `app/build/test-results/` XML） |
| bridge 最低版本 | `README` 双站「安装要求」与「项目信息」两处必须一致 |

**铁律**：`docsCheck` 覆盖 `docs/project/` 下 **10 份必需文档**，但**不覆盖根 README**。
README 中英双站是最容易漂移的地方（v2.2.33–2.2.35 三版漂移过：中文停 2.2.32、英文停 24 类/256 用例）。
**发版前除 `docsCheck` 外，必须另跑一次对账**（命令见 `PITFALLS.md` §README 双站漂移）。

**顺序**（用户既定规矩，不可倒）：改代码 → 改文档与版本号 → `scripts/build.sh` 校验 → 同步实装目录 → 打包发布。
先同步产物再改文档/版本号，会让文档描述一个不存在的状态。

---

## 3.5 开工口径：先 issue，再动手（2026-10-31 用户定）

**改 bug、加新功能之前，先在仓库开一条 issue，然后再写代码。**

- issue 里写清：现象或需求、影响面、打算怎么做、怎么验。**不给方案的 issue 不算数** ——
  它是给自己看的工单，不是写给别人的祈使句。
- 单行注释级别的修复、纯文档订正、纯格式调整**不需要 issue**。判据是：**这次改动会不会
  改变用户可见的行为或对外契约**（含 bridge 端点 / WS 事件 / scope / 错误码的消费侧变化），会才需要。
- 提交信息里带 issue 编号（`fix(...#12)` / `feat(...#13)`），让「为什么改这行」能从
  `git blame` 一路走到动机。
- 关闭 issue 时把验证结果贴进去（跑了什么、看到什么）。**「已修」不是证据** ——
  对本仓尤其如此：单测绿 ≠ 行为对，真机那一趟才是证据（见 §5）。

**为什么是这条规矩**：本仓是**消费侧**。桥改一个端点、一个事件字段、一个错误码，
这边就得跟着动，而 `git blame` 到一个只写着「适配新字段」的提交时，下一个人无从判断
这个字段现在还在不在。先写 issue，把「桥那侧变了什么、这边受影响的是谁」记下来。

> 用户 2026-10-31 原话：「给仓库宪法加个要求，改bug增加新功能前先发个 issue。」
>
> 与姊妹仓 `dsh-remote-bridge/AGENTS.md` §2.5 同一条规矩（措辞按本仓消费侧特色调整）。
> 两边**共有**的条目又多一条，改一处记得改另一处。

---


## 4. 架构纪律

### 4.1 分层与依赖方向

```
ui/            ← 只消费 HarnessState，调 HarnessViewModel 动作；不持有协议或权限决策
data/          ← DshClient 是唯一网络边界；SecureConnectionStore 管凭据
web/           ← WebView 容器（chat.html 兜底路径）
```

- **`DshClient` 是唯一网络边界**，阻塞 IO 只在 `Dispatchers.IO`。
  已知例外（**登记在 `TECH-DEBT.md` DEBT-006，并由 `ArchitectureGuardTest` 盯着**）：
  `web/ChatWebBridge.kt` 为 WebView 桥自持一份 OkHttp 客户端 —— 它要转发
  「任意方法 + 任意路径 + 任意 JSON」，与 `DshClient` 的强类型 API 对不上。
  清账路径：给 `DshClient` 加通用转发入口后，删掉该例外、`TECH-DEBT.md` 条目与测试白名单项。
- Compose 表现层**不得**自己发网络请求或读 `SharedPreferences` 当状态（那会导致「点了开关不动」，
  见 `PITFALLS.md` §Compose 状态与 SharedPreferences）。
- 会话展示走 `ChatPresentation` 单一出口：只输出用户/助手正文、推理摘要、上下文注入来源、工具摘要、
  显式交付物卡片（`deliverables/presented`，钉在轮次收尾）；
  `step/end`、`turn/end`、token 序列与未知内部事件**不渲染为聊天卡片**。

### 4.2 债务台账（只许收紧）

`ARCHITECTURE.md` 是散文，**不构成门禁**。本项目引入「债务台账」制度：

- 台账放 `docs/project/TECH-DEBT.md`，每条含：位置、为什么是债、清账阶段。
- **只许收紧**：条目对应的债务消失，同一提交里删掉该条目（自证非空转）。
- **上调必须带理由**：单文件超过行数预算时，只许带理由在同一提交里上调，且必须在下一阶段拆回。
- 当前行数预算：**800 行/文件**。
  > 2026-10-16 起台账已清空：`app/src` 下**零个文件超预算**（最大 `ui/chat/ChatSheets.kt` 797）。
  > 曾经的三个债（`ChatScreen.kt` 3 882 / `HarnessViewModel.kt` 2 277 / `ChatPresentation.kt` 825）
  > 已按职责拆成 `ui/chat/` 与 `Harness*ViewModel.kt` 两组文件，清账记录见 `TECH-DEBT.md`。
  > **新增超预算文件必须先入台账再写码** —— 台账空了不等于门禁松了。

### 4.3 兼容补丁四要素（`@patch`）

凡因上游/内核/bridge 的行为而写的适配代码，必须在源码里自报四要素：

```kotlin
// @patch
// id: short-kebab-id
// for: 适配什么（上游/内核/bridge 的哪个结构、哪个版本）
// why: 为什么不能用正规写法（没理由 = 债务）
// until: 什么条件达成就能删
// test: 哪个测试盯着它（必须真实存在）
```

- `test` 指向的测试**必须存在**——这条可机检，将来应进 `docsCheck`。
- 没有 `@patch` 的适配代码 = 没人认领的补丁，后来者不敢删，只会在上面继续堆。

### 4.4 目录结构与接口说明的同步纪律

用户要求（2026-10-21）：「加目录结构与接口说明，方便 AI 阅读改代码，**后期改代码要同步改**。」
「请记得同步」没有门禁等于没写（README 双站漂移就是这么漂了三版），所以它是**机检**的。

| 改动 | **同一提交**内必须同步 |
|---|---|
| 新增 / 移动 / 删除 `app/src/main/java/` 下的 `.kt` | `docs/project/STRUCTURE.md` 的 `<!-- structure-check -->` 锚点 |
| 改公开方法/类签名、搬模块 | `docs/project/INTERFACES.md` 的 `<!-- interfaces-check -->` 锚点与正文 |
| 改消费的 bridge 端点或事件字段 | `INTERFACES.md` §3 + **先改 bridge 仓库**的 API/OpenAPI/AsyncAPI/SDK/合约测试 |
| 新增一类文件（测试 / 脚本 / 文档 / 资源） | `STRUCTURE.md` 的「新增东西放哪里」表与对应小节 |

门禁是**双向**的，`scripts/build.sh docsCheck`：

1. **文档 → 源码**：锚点里的路径必须存在且类型对得上（`file` / `dir`）；
   `INTERFACES.md` 锚点声明的标识符必须真能在指出的文件里 grep 到。
2. **源码 → 文档**：`app/src/main/java/` 下每个 `.kt` 都必须登记在 `STRUCTURE.md` 锚点里。
   这条才是「新增文件必须同步文档」的兑现 —— 只查第一向的话，新文件根本不会被发现。
3. **下限断言**：锚点解析出的条目数低于阈值（STRUCTURE < 100 / INTERFACES < 25）时**直接红**，
   报「表格格式可能变了」。防的是「解析失败 → 两个集合双双为空 → 被当成一致」的假绿。
   > 这个阈值不是拍脑袋：2026-10-21 落地时**第一条就抓到了自己** ——
   > Kotlin 的 `Regex("^…")` 默认不带 MULTILINE，多行表格只解析出 0 行。
   > 少了这条断言，那次的 130 条锚点会「一条没查」却全绿。

**铁律**：
- **先改文档再提交，不允许「下个提交补」**。门禁红着提交等于把红转给下一个人。
- 明细只写在 `STRUCTURE.md` / `INTERFACES.md` 一处；本文件**不抄文件清单和签名**
  （抄了必然漂，见 §4.2 行数那段的教训）。
- 锚点区的标题与标记是**机器契约**，改格式要同批改 `build.gradle.kts` 的 `DocsCheckTask`。

---

## 5. 测试纪律

| 层 | 命令 | 说明 |
|---|---|---|
| 单元 | `scripts/build.sh :app:testDebugUnitTest` | 当前 88 类 / 876 用例（随代码变化，勿当常量） |
| 文档 | `scripts/build.sh docsCheck` | 10 文档 / ROADMAP 任务 / STATUS 引用 / 根 README 双站 / 目录与接口锚点双向 |
| 真机 | 人工 | **单测绿 ≠ 行为对**。涉及手势、键盘、WebView、耗电、断流的一律要真机确认 |

**铁律**：
- 修 bug 必须带回归用例（`TESTING.md` 记录演进：112 → 135 → 151 → 156 → 169 → 273）。
- 测试文件命名与被测类对应（`XxxTest.kt`），夹具集中在 `app/src/test/resources/`。
- 不许为凑数加空断言测试。
- **桥的安全模型变了，客户端必须跟着过一遍**（用户 2026-10-21 定，与姊妹仓
  `dsh-remote-bridge` 的「改桥必测安全」成对）。本 App 只消费 `/api/v1`，**不碰 `/manage`**
  （已核实全仓无管理面调用），所以**管理面收紧不会影响本 App**；但以下几种改动仍会连带：

  | 桥侧改动 | 本 App 必须确认 |
  |---|---|
  | 认证/令牌格式、`Authorization` 头形态、WS 子协议 | `data/DshClient` 与 WS 建连仍能过鉴权；撤销后重连行为正确 |
  | 新增/删除 scope、`connection.ready` 能力位 | `data/BridgeCapabilities` 判据与实际服务端一致，不得按版本号猜 |
  | 新增错误码（如 `TUNNEL_MODE_ADMIN_DISABLED`） | `data/ErrorMessages` 有对应文案，不得落到「未知错误」 |
  | 隧道/反代相关行为 | 手机从公网经隧道连回来时仍可用（隧道只会禁管理面，不应禁数据面） |

  桥侧发版后，本仓至少复跑 `:app:testDebugUnitTest` 并真机连一次；**不许只看桥的测试绿就放行** ——
  2026-10-21 的提权漏洞正是「桥自己有 349 个测试全绿」却没测到管理面来源判定。

---

## 6. 对外表述纪律

- **「已合并」≠「已发布」≠「用户吃到了」**。三态必须分清，未发版前明说「等下个 release」。
- 公开文本（PR / issue / Release notes）只写事实：改了什么、为什么、门验结果。
  私聊验收口语、内部原话、自封头衔一律不进公开文本。
- 链接三类处理：上游引用保持 GitHub 不改；本仓库自身链接以 **Gitee 为主、GitHub 为从**，
  AtomGit 仅在指向代码镜像时列出；姊妹仓库沿用它们各自的拓扑；仓库内相对链接各端自动解析。
- **禁止**：推送（push / release / tag / 上传产物）未经用户明确同意。
  本地 commit、构建、测试、清理、写文档可直接做。

---

## 7. 坑位索引（名字 = 触发词 = 锚点）

正文在 `docs/project/PITFALLS.md`，动手前按名字读对应条目。**不看就改 = 重踩。**

- `README 双站漂移`
- `Compose 状态与 SharedPreferences`
- `WebView 与原生双实现`
- `图片附件元数据与字节读取`
- `熄屏断流与亮屏接回`
- `ask_user_question 三态`
- `交付物事件与路径嗅探`
- `bridge 版本基线`
- `端口 3090 归属`
- `Gradle wrapper 挂起`
- `并发写者与未提交改动`
- `GitHub 443 阻断与镜像从站`
- `AtomGit 账号名与 API 版本`
- `长文本正则与重复分组`
- `过期响应覆盖新上下文`
- `SelectionContainer 把子节点当兄弟排布`
- `delta 帧缺 turn/step 被静默丢弃`
- `vivo 输入法拒绝外部文本注入`
- `键盘弹起让对话框上移：点旧坐标会点空，表现为「点了没反应」`
- `诊断日志：开关只该管「记不记」，不该管「通道建不建」`
- `诊断日志的噪音：重复行必须按指数抑制`
- `数字口径不可混用（诊断日志版）`
- `history 重建冲掉滚动位置，贴底跟随被自己的守卫锁死`
- `诊断浮层贴在 TopStart：连撞四个导航入口（已改为可拖动）`
- `Markdown autolink 的尖括号目标被当成相对路径`
- `客户端自渲染 ≠ 内核会替你画：两处注释都以为对方在画`
- `表格贴上一行：intellij-markdown 不认无空行 GFM 表格`
- `连接状态位是「曾经连上过」，不是「此刻还连着」`
- `busy 只在 launchOperation 里收尾：绕过它的路径会把标志永久卡住`
- `文档链接带位置后缀（:行号 / #L行号）被当成路径的一部分`
- `目录结构与接口说明漂移`
- `内核 schema 是 schemastery，不是 JSON Schema`
- `设置页照抄 WebUI 命名空间：一排拨不动的开关`
- `设置页「能看不能存」：配对默认没有 settings.write`
- `空助手消息把过程组劈成几十个组头`
- `刷新历史把「加载更早」的成果抹掉，屏幕上只剩一片空白`
- `建议面板硬截 N 条又不可滚：第 N+1 条之后的命令既看不见也够不着`
- `WebSocket 断连只挂 onClosed：桥重启后连接永远「还在」`
- `契约文档写着「requestId 回显」，实测一半的帧不回显`
- `两个事件通道交接时，旧通道的「告别」会把新通道的状态改回断开`
- `整份状态重建只填服务端字段，其余五十多个字段被打回默认值`
- `共用 kind 的应答：靠 data.type 判别，漏一个就会互相把请求「确认」掉`
- `同一份列表，REST 包 items、WS 直接给数组：套同一个反序列化模型必炸`

> 新坑位：名字加进上面清单 + 原文写进 `PITFALLS.md` 并补 `### 同名` 锚点。

---

## 8. 制度来源与差异说明

本文件借鉴 `dsh-web-mobile/AGENTS.md` 的四项制度，按本项目特色裁剪：

| 借鉴项 | 原样 | 本项目的裁剪 |
|---|---|---|
| 补丁四要素 | `@patch` for/why/until/test，脚本生成登记表 | 保留四要素；**2026-10-17 起由 `ArchitectureGuardTest` 机检**（五字段齐全 + `test` 指向的测试真实存在），随 `testDebugUnitTest` 进 CI；尚未并入 `docsCheck` |
| 债务台账只许收紧 | `architecture.test.ts` 强制（环/行数/模块状态） | 改 `TECH-DEBT.md` + 行数预算 800；**2026-10-17 起由 `ArchitectureGuardTest` 扫行数兑现**（超预算须在台账登记）—— 原以为「Kotlin 侧无等价解析器」，实测**文本匹配就够** |
| 文档路由表 | 13 条场景路由 | 收敛为第 0 节一张表 |
| 坑位索引 | 67 条 | 11 条起步，按需增长 |

**2026-10-21 新增**：`docs/project/STRUCTURE.md`（代码地图）与 `INTERFACES.md`（契约与签名），
连同 `docsCheck` 的**双向锚点校验**一起落地 —— 与姊妹仓同制度、同格式、不同阈值。

**刻意没抄的**：CDP 探针体系（22 个）——本项目没有 WebUI 宿主，无处安放。
**本项目特有的三条**：Bridge 版本基线两处一致、熄屏断流与亮屏接回、WebView 与原生双实现。

> **共有三条的措辞与 `dsh-remote-bridge/AGENTS.md` 保持一致**：
> README 双站漂移、端口 3090 归属、对外表述三态。
> 2026-10-17 对账时发现：两边原本对「哪三条共有」的表述**两两不同**，
> 且本文件把 `README 双站漂移` 同时算进了「共有」与「特有」—— 那次已统一，改一处记得改另一处。
