# AGENTS.md — dsh-companion 工程宪法

> 本文件是每次会话**必然读到**的那份约定。规矩写在这里，不靠记忆、不靠口头。
> 来源：借鉴 [dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile) 的 `AGENTS.md` 制度设计，
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
scripts/build.sh                    # 默认 assembleDebug
scripts/build.sh assembleRelease    # 发布包
scripts/build.sh :app:testDebugUnitTest   # 单元测试
scripts/build.sh docsCheck          # 文档一致性（8 文档 / ROADMAP 任务状态 / STATUS 引用）
```

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

**铁律**：`docsCheck` **只覆盖 `docs/project/` 下 8 份文档**，不覆盖 README。
README 中英双站是最容易漂移的地方（v2.2.33–2.2.35 三版漂移过：中文停 2.2.32、英文停 24 类/256 用例）。
**发版前除 `docsCheck` 外，必须另跑一次对账**（命令见 `PITFALLS.md` §README 双站漂移）。

**顺序**（用户既定规矩，不可倒）：改代码 → 改文档与版本号 → `scripts/build.sh` 校验 → 同步实装目录 → 打包发布。
先同步产物再改文档/版本号，会让文档描述一个不存在的状态。

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
- 会话展示走 `ChatPresentation` 单一出口：只输出用户/助手正文、推理摘要、上下文注入来源、工具摘要；
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

---

## 5. 测试纪律

| 层 | 命令 | 说明 |
|---|---|---|
| 单元 | `scripts/build.sh :app:testDebugUnitTest` | 当前 44 类 / 410 用例（随代码变化，勿当常量） |
| 文档 | `scripts/build.sh docsCheck` | 8 文档 / ROADMAP 任务 / STATUS 引用一致性 |
| 真机 | 人工 | **单测绿 ≠ 行为对**。涉及手势、键盘、WebView、耗电、断流的一律要真机确认 |

**铁律**：
- 修 bug 必须带回归用例（`TESTING.md` 记录演进：112 → 135 → 151 → 156 → 169 → 273）。
- 测试文件命名与被测类对应（`XxxTest.kt`），夹具集中在 `app/src/test/resources/`。
- 不许为凑数加空断言测试。

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

**刻意没抄的**：CDP 探针体系（22 个）——本项目没有 WebUI 宿主，无处安放。
**本项目特有的三条**：Bridge 版本基线两处一致、熄屏断流与亮屏接回、WebView 与原生双实现。

> **共有三条的措辞与 `dsh-remote-bridge/AGENTS.md` 保持一致**：
> README 双站漂移、端口 3090 归属、对外表述三态。
> 2026-10-17 对账时发现：两边原本对「哪三条共有」的表述**两两不同**，
> 且本文件把 `README 双站漂移` 同时算进了「共有」与「特有」—— 那次已统一，改一处记得改另一处。
