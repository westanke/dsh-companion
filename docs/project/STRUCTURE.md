# 目录结构（代码地图）

本文回答三个问题：**东西在哪、谁调谁、新东西该放哪**。
「为什么这么设计」看 [ARCHITECTURE.md](ARCHITECTURE.md)，「接口长什么样」看
[INTERFACES.md](INTERFACES.md)，「原先踩过什么坑」看 [PITFALLS.md](PITFALLS.md)。

> **本文是给 AI 与人共用的导航图，不是目录复述。** 改代码前先按这张图定位，
> 改完按文末「机检锚点」同步 —— 那份锚点归 `scripts/build.sh docsCheck` 双向校验，
> `app/src/main/java/` 下漏登记任何一个 `.kt` 都会让门禁变红（见 [AGENTS.md](../../AGENTS.md) §4.4）。

---

## 1. 顶层目录树

```
dsh-companion/
├── app/                        唯一模块
│   ├── src/main/java/…/dshcompanion/   源码（见 §3）
│   ├── src/main/assets/chat/           WebView 版会话页（chat.html 及资源）
│   ├── src/main/res/                   资源：drawable / values / values-en / xml / mipmap
│   ├── src/test/java/…/                单元测试（62 类，见 §5）
│   └── src/androidTest/java/…/         仪器测试
├── design-system/              设计规范（MASTER.md 等），涉及界面时必须读
├── docs/project/               开发事实来源（状态 / 决策 / 坑位 / 本文）
├── scripts/                    构建 / 发版 / 推送脚本（不是运行时代码）
├── tools/                      辅助工具（配置文本生成端等）
├── gradle/                     version catalog 与 wrapper 配置
├── artifacts/                  APK 产物与 SHA256SUMS（**勿手改**）
└── .github/workflows/          CI（docsCheck / 单测 / lint / assemble）
```

根级配置文件分工：

| 文件 | 用途 |
| --- | --- |
| `app/build.gradle.kts` | 模块配置、`versionCode`/`versionName`（**版本真值来源**）、逐版注释 |
| `build.gradle.kts` | 根构建脚本，含 `DocsCheckTask`（`docsCheck` 的实现就在这） |
| `settings.gradle.kts` | 仓库与模块声明（`FAIL_ON_PROJECT_REPOS` 在这里开） |
| `gradle/libs.versions.toml` | version catalog（依赖版本唯一出处） |
| `gradle.properties` | JVM / AndroidX 全局开关 |
| `init-mirrors.gradle` | 本机/CN 镜像配置（**必须用本项目这份**，理由见 AGENTS.md §2） |
| `local.properties` | 本机 SDK 路径，**不入库** |

---

## 2. 分层与依赖方向

```
        ┌──────────────────────────────────────────────┐
        │ app/src/main/assets/chat/  (WebView 版会话页) │  兜底路径，非主路径
        └───────────────────┬──────────────────────────┘
                            ↓ JS 桥
   ┌────────────────────────────────────────────────────────┐
   │ ui/  ui/chat/  ui/theme/                                │  只消费 HarnessState，调 ViewModel 动作
   │  （Compose 表现层：不自己发网络请求、不读 SharedPreferences 当状态）│
   └───────────────────┬────────────────────────────────────┘
                       ↓ 动作 / 状态
   ┌────────────────────────────────────────────────────────┐
   │ HarnessViewModel + Harness*ViewModel.kt（按域拆的扩展）  │  唯一 ViewModel
   │ （持有 HarnessState；跨域基础设施收敛在这里）             │
   └───────────────────┬────────────────────────────────────┘
                       ↓
   ┌────────────────────────────────────────────────────────┐
   │ data/                                                   │  协议、状态、持久化
   │   DshClient（**唯一网络边界**，阻塞 IO 只在 Dispatchers.IO）│
   │   Chat*（历史/展示/映射） · Connection*（连接与探活）      │
   │   HostStore/SecureConnectionStore/AppPreferences（存储）  │
   └───────────────────┬────────────────────────────────────┘
                       ↓ HTTPS / SSE
              dsh-remote-bridge 的版本化 REST
```

### 禁止的依赖方向

| 禁止 | 为什么 |
| --- | --- |
| `ui/` 直接发网络请求 | 会绕过 ViewModel 的状态收敛，表现成「点了开关不动」（PITFALLS §Compose 状态与 SharedPreferences） |
| `ui/` 直接读 `SharedPreferences` 当状态 | 同上：那不是 Compose 状态，值变了不触发重组 |
| `data/` 依赖 `ui/` | 反向依赖会让数据层拖进 Compose，单测也没法纯 JVM 跑 |
| 除 `DshClient` / `ChatWebBridge` 外新建 `OkHttpClient` | `ArchitectureGuardTest` 只放行两个白名单文件，新建必红（见 `TECH-DEBT.md` DEBT-006） |
| `ui/` 或 `data/` 里做权限/协议决策 | 权限与协议归属不变式由 ViewModel 与 `DshClient` 守 |

**已知例外（已登记、由测试盯着）**：`web/ChatWebBridge.kt` 为 WebView 桥自持一份 OkHttp
客户端 —— 它要转发「任意方法 + 任意路径 + 任意 JSON」，与 `DshClient` 的强类型 API 对不上。
清账路径写在 `TECH-DEBT.md` DEBT-006。

---

## 3. 逐目录说明

### 3.1 根目录（15 个文件）

**入口**

| 文件 | 职责 |
| --- | --- |
| `MainActivity.kt` | 应用入口 Activity（Compose 宿主、生命周期、Intent 处理） |

**唯一 ViewModel 与其按域拆分**

原 `HarnessViewModel.kt` 曾 2 277 行，按职责拆成下列文件；成员函数改为
**`HarnessViewModel` 的扩展函数**，因此调用方写法不变。

| 文件 | 域 |
| --- | --- |
| `HarnessViewModel.kt` | 唯一 ViewModel：持有 `HarnessState`，收敛跨域共用基础设施 |
| `HarnessModels.kt` | `HarnessState` 与跨层数据载体（只放「长什么样」） |
| `HarnessOperations.kt` | 拿活跃连接跑请求；**没有连接时不再静默返回** |
| `HarnessEventRules.kt` | 事件帧与输入行的纯判定（不碰 Android、不碰网络） |
| `HarnessErrorText.kt` | 失败调用 → 用户可读句子的三档降级 |
| `HarnessConnectionViewModel.kt` | 连接域：多主机/多地址配对、导入、探活选路、断开 |
| `HarnessSessionsViewModel.kt` | 会话域：增删改查、工作区、发消息、会话文件、模型选择 |
| `HarnessFilesViewModel.kt` | 文件域：授权根、浏览分页、读写增删移、上传下载、回收站、编解码 |
| `HarnessInteractionsViewModel.kt` | 交互域：工具审批与内核提问的拉取、提交、决定 |
| `HarnessAttachmentsViewModel.kt` | 附件域：待发附件与历史图片按需取回 |
| `HarnessJobsViewModel.kt` | 后台任务面板的动作集 |
| `HarnessPluginsViewModel.kt` | 设置域：插件清单/启停、提供商增改查与模型发现 |
| `HarnessDiagViewModel.kt` | 把偏好开关同步给 `DiagLog` |
| `HarnessTruncationViewModel.kt` | 一轮边界事件：落「回答被截断」提示或清上一轮的 |

### 3.2 `data/`（40 个文件）

| 分组 | 文件 | 职责 |
| --- | --- | --- |
| 网络边界 | `DshClient.kt` | **唯一网络边界**：bridge REST 强类型客户端 + SSE 订阅 |
| 数据模型 | `Models.kt` / `ChatModels.kt` / `ConnectionModels.kt` / `JobViews.kt` / `SessionFileRefs.kt` / `SessionStats.kt` | 响应/请求模型、展示项、连接地基契约、任务与文件引用 |
| 协议映射 | `ChatPresentation.kt` / `ChatHistoryMapping.kt` / `ChatDisplayGroups.kt` / `ChatVisibleItems.kt` / `ChatContentParts.kt` / `ChatToolBlocks.kt` / `ChatToolPresentation.kt` / `ChatJson.kt` / `ChatLinkTarget.kt` / `ChatPathLinks.kt` / `ChatForkPoint.kt` / `ChatRetry.kt` / `ChatSeqMonitor.kt` / `ChatTimeLabel.kt` / `ChatHandoff.kt` / `ChatDeliverableCards.kt` | 历史事件 → 展示项、工具块、链接、分叉、重试、断档、时间、交接、交付物 |
| 连接 | `ConnectionCoordinator.kt` / `ConnectionLiveness.kt` / `ConnectionShare.kt` / `BridgeCapabilities.kt` | 多地址探活选路、此刻通不通、配置导入编解码、能力位解析 |
| 存储 | `SecureConnectionStore.kt` / `AndroidConnectionStorage.kt` / `HostStore.kt` / `AppPreferences.kt` | 凭据与主机（密文）、偏好（明文）、键值抽象 |
| 展示支撑 | `HtmlPreview.kt` / `PreviewKind.kt` / `GenUiBlocks.kt` / `MentionQuery.kt` / `ErrorMessages.kt` / `Trajectory.kt` | HTML 结构抽取、预览路径、dsh-ui 卡片、@ 提及、错误文案、轨迹 |
| 流程与闸门 | `EventDebouncer.kt` / `RequestGate.kt` / `ComposerSendPolicy.kt` | 副作用去抖、请求代际、queue/steer 策略 |
| 更新 | `UpdateChecker.kt` | 按界面语言分流（中文 Gitee / 英文 GitHub）、下载、SHA-256 校验、调起安装器 |

### 3.3 `ui/`（17 个文件）与 `ui/chat/`（33 个文件）

`ui/` 是页面级容器与跨页面组件；`ui/chat/` 是会话详情页的全部拆分部件。
拆分理由是行数预算（800/文件），**不是**新架构 —— 语义与行为在拆之前就等价。

`ui/chat/` 的组织方式：`ChatDetail.kt` 是骨架（什么挂在哪儿、按什么顺序），
真正的部件都在兄弟文件里；列表层 `ChatSessionPane.kt`，消息项 `ChatMessageItems.kt`，
交互卡片 `ChatApprovals.kt`，输入区 `ChatComposer.kt`。

### 3.4 `ui/theme/`（2）· `web/`（2）· `debug/`（2）

| 文件 | 职责 |
| --- | --- |
| `ui/theme/Theme.kt` | 应用主题（跟随系统 / 浅色 / 深色三档由偏好驱动） |
| `ui/theme/AppFontScale.kt` | 应用内字号缩放（替换 `LocalDensity`） |
| `web/ChatWebBridge.kt` | 注入 `assets/chat.html` 的 JS 桥（**已登记的网络例外**） |
| `web/ActiveChatBridge.kt` | 当前存活的桥实例（Activity 回调落点） |
| `debug/DiagLog.kt` | 诊断日志单一出口（关了就什么都不做） |
| `debug/DiagProbe.kt` | 诊断探针浮层（可拖动，收起成小条） |

### 3.5 测试与资源

| 目录 | 说明 |
| --- | --- |
| `app/src/test/java/…/` | 单元测试，62 类。`ArchitectureGuardTest.kt` 守着「唯一网络边界」「`@patch` 四要素」「800 行预算」三条 |
| `app/src/androidTest/java/…/` | 仪器测试 |
| `app/src/main/assets/chat/` | WebView 版会话页（兜底路径，与原生 `ChatDetail` 并存） |
| `app/src/main/res/` | `drawable` / `values` / `values-en` / `xml` / `mipmap`；文案双语在 `values*` |
| `design-system/` | 设计规范；涉及界面改动时必须读 `MASTER.md` |

---

## 4. 关键文件速查（改它会影响什么）

| 文件 | 量级 | 改它会影响 |
| --- | --- | --- |
| `data/DshClient.kt` | ≈760 行 | **全部网络行为**；改它要同步 INTERFACES 与回归用例 |
| `ui/chat/ChatDetail.kt` | ≈790 行 | 会话页骨架与挂载顺序（已贴 800 预算） |
| `ui/ChatScreen.kt` | ≈360 行 | 窄/宽屏布局选择与会话页容器 |
| `HarnessViewModel.kt` | ≈770 行 | 全 App 状态与跨域基础设施 |
| `HarnessSessionsViewModel.kt` | ≈620 行 | 会话域全部动作 |
| `data/Models.kt` | ≈580 行 | 大量响应模型；改字段会牵动映射与测试 |
| `web/ChatWebBridge.kt` | ≈360 行 | WebView 兜底路径的全部桥行为 |
| `data/UpdateChecker.kt` | ≈290 行 | 更新检查与安装引导 |
| `app/build.gradle.kts` | — | `versionCode`/`versionName`（**版本真值**）与逐版注释 |

> 行数只是**量级提示**；预算与债务状态以 [TECH-DEBT.md](TECH-DEBT.md) 为准（800/文件）。
> **不要把这里的近似值当成门禁依据。**

---

## 5. 新增东西放哪里（决策表）

| 我要加… | 放哪 | 还要同步什么 |
| --- | --- | --- |
| 一个新页面 | `ui/XxxScreen.kt`，并在 `ui/App.kt` 挂导航 | **本文机检锚点**、`INTERFACES.md` 锚点、`PITFALLS.md`（若有坑）、`design-system` |
| 一个新会话页部件 | `ui/chat/Xxx.kt` | 同上；注意 `ChatDetail.kt` 已贴 800 行预算，新东西别再往里塞 |
| 一个新 ViewModel 动作 | 对应域的 `Harness*ViewModel.kt`（扩展函数） | `HarnessModels.kt` 的 state 字段、`INTERFACES.md` 锚点 |
| 一个新的 bridge 响应字段/端点 | 先改 **bridge 仓库**（五处同步），再改 `data/Models.kt` + `DshClient.kt` | `INTERFACES.md` 锚点、回归用例、必要时能力位判据 |
| 一个新协议映射规则 | `data/Chat*.kt` | 单测（`data/Chat*Test.kt`）、`ChatPresentation` 单一出口不被绕过 |
| 一个新存储键 | `data/AppPreferences.kt`（纯偏好）或 `HostStore`（凭据） | 别在 Compose 里直接读它当状态 |
| 一个新单元测试 | `app/src/test/java/…/`，命名 `XxxTest.kt` | `TESTING.md` 记演进；修 bug 必须带回归用例 |
| 一份新文档 | `docs/project/`，并在 `docs/project/README.md` 表格挂一行 | 若属必需文档，加到 `build.gradle.kts` 的 `required` 清单 |
| 一个新坑位 | `docs/project/PITFALLS.md` 补 `### 同名` | `AGENTS.md` §7 索引同名条目（两处必须同步） |
| 一个新 `@patch` | 源码注释四要素 | `test` 指向的用例必须真实存在（`ArchitectureGuardTest` 机检） |

---

## 6. 机检锚点（docsCheck 读取，勿改格式）

> 本区由 `build.gradle.kts` 的 `DocsCheckTask` 解析。**双向**校验：表里路径必须真实存在，
> `app/src/main/java/` 下每个 `.kt` 也必须出现在表里。新增/移动/删除源文件时同步这里，
> 否则 `scripts/build.sh docsCheck` 变红。

<!-- structure-check:begin -->
| 路径 | 类型 | 职责 |
| --- | --- | --- |
| `app/` | dir | 唯一应用模块 |
| `app/src/main/java/` | dir | 全部 Kotlin 源码根 |
| `app/src/main/java/io/github/westanke/dshcompanion/` | dir | 源码包根（根目录放入口与 ViewModel 拆分） |
| `app/src/main/java/io/github/westanke/dshcompanion/data/` | dir | 协议、状态、存储与网络边界 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/` | dir | Compose 表现层（页面与跨页面组件） |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/` | dir | 会话详情页拆分部件 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/theme/` | dir | 主题与字号缩放 |
| `app/src/main/java/io/github/westanke/dshcompanion/web/` | dir | WebView 容器与 JS 桥 |
| `app/src/main/java/io/github/westanke/dshcompanion/debug/` | dir | 诊断日志与探针 |
| `app/src/test/java/` | dir | 单元测试（62 类） |
| `app/src/androidTest/java/` | dir | 仪器测试 |
| `app/src/main/assets/chat/` | dir | WebView 版会话页资源 |
| `app/src/main/res/` | dir | Android 资源（含双语 values） |
| `docs/project/` | dir | 开发事实来源 |
| `design-system/` | dir | 设计规范 |
| `scripts/` | dir | 构建/发版/推送脚本 |
| `tools/` | dir | 辅助工具 |
| `gradle/` | dir | version catalog 与 wrapper |
| `artifacts/` | dir | APK 产物与校验和（勿手改） |
| `app/src/main/java/io/github/westanke/dshcompanion/HarnessAttachmentsViewModel.kt` | file | 附件域：待发附件与历史图片按需取回 |
| `app/src/main/java/io/github/westanke/dshcompanion/HarnessConnectionViewModel.kt` | file | 连接域：多主机/多地址配对、导入、探活选路与断开 |
| `app/src/main/java/io/github/westanke/dshcompanion/HarnessDiagViewModel.kt` | file | 把偏好里的开关同步给 DiagLog |
| `app/src/main/java/io/github/westanke/dshcompanion/HarnessErrorText.kt` | file | 失败调用 → 用户可读句子的三档降级 |
| `app/src/main/java/io/github/westanke/dshcompanion/HarnessEventRules.kt` | file | 事件帧与输入行的纯判定（不碰 Android / 网络） |
| `app/src/main/java/io/github/westanke/dshcompanion/HarnessFilesViewModel.kt` | file | 文件域：授权根、浏览分页、读写增删移、上传下载、回收站与编解码 |
| `app/src/main/java/io/github/westanke/dshcompanion/HarnessInteractionsViewModel.kt` | file | 交互域：工具审批与内核提问的拉取、提交与决定 |
| `app/src/main/java/io/github/westanke/dshcompanion/HarnessJobsViewModel.kt` | file | 后台任务面板的动作集 |
| `app/src/main/java/io/github/westanke/dshcompanion/HarnessModels.kt` | file | UI 状态（HarnessState）与跨层数据载体 |
| `app/src/main/java/io/github/westanke/dshcompanion/HarnessOperations.kt` | file | 拿活跃连接跑请求；无连接时给明确提示而不是静默返回 |
| `app/src/main/java/io/github/westanke/dshcompanion/HarnessPluginsViewModel.kt` | file | 设置域：插件清单/启停与模型提供商增改查 |
| `app/src/main/java/io/github/westanke/dshcompanion/HarnessSessionsViewModel.kt` | file | 会话域：会话增删改查、工作区、发消息、会话文件与模型选择 |
| `app/src/main/java/io/github/westanke/dshcompanion/HarnessTruncationViewModel.kt` | file | 一轮边界事件：落「回答被截断」提示或清掉上一轮的 |
| `app/src/main/java/io/github/westanke/dshcompanion/HarnessViewModel.kt` | file | 唯一 ViewModel：持有 HarnessState 与跨域共用基础设施 |
| `app/src/main/java/io/github/westanke/dshcompanion/MainActivity.kt` | file | 应用入口 Activity（Compose 宿主、生命周期、Intent 处理） |
| `app/src/main/java/io/github/westanke/dshcompanion/data/AndroidConnectionStorage.kt` | file | KeyValueStore 的 SharedPreferences 落地（存密文） |
| `app/src/main/java/io/github/westanke/dshcompanion/data/AppPreferences.kt` | file | 轻量应用偏好（明文，丢只影响一次点击） |
| `app/src/main/java/io/github/westanke/dshcompanion/data/BridgeCapabilities.kt` | file | connection.ready 能力位解析与必需项判据 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ChatContentParts.kt` | file | 文本 + 待发附件 → 请求体，并守住资源上限 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ChatDisplayGroups.kt` | file | 会话列表显示分组：过程/结论二分与折叠组 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ChatForkPoint.kt` | file | 这条展示项能否作为分叉点，以及给 forkSession 的 atSeq |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ChatHandoff.kt` | file | 纯文本模型发图的交接附件识别与处理 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ChatHistoryMapping.kt` | file | 一条会话历史 → 一串展示项：逐事件映射 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ChatJson.kt` | file | 会话数据层共用的 JSON 取值助手与解析实例 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ChatLinkTarget.kt` | file | 链接目标归一（含末尾行号片段处理） |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ChatModels.kt` | file | 会话展示层数据模型与事件帧解析 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ChatPathLinks.kt` | file | 正文里已知的会话文件变可点链接 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ChatPresentation.kt` | file | 会话展示的单一出口（门面） |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ChatRetry.kt` | file | 「重试」要重发的文本：最后一条有正文的用户消息 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ChatSeqMonitor.kt` | file | 带序号事件能否接上上一条（断档判定） |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ChatTimeLabel.kt` | file | 消息时间戳展示文案（今天/今年/更早三档） |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ChatToolBlocks.kt` | file | 工具调用专用渲染视图模型与分发 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ChatToolPresentation.kt` | file | 工具调用展示口径：动作短语、合并、详情与耗时 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ChatVisibleItems.kt` | file | 历史里该显示的那部分：滤思考、按开关决定是否显示上下文注入 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ComposerSendPolicy.kt` | file | 智能体运行中提交消息走 queue 还是 steer |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ConnectionCoordinator.kt` | file | 多地址探活选路与全失败时的逐条原因 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ConnectionLiveness.kt` | file | 连接页「此刻通不通」的三个纯函数判据 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ConnectionModels.kt` | file | 连接层数据模型（地基契约） |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ConnectionShare.kt` | file | 配置导入编解码：连接信息 ↔ 可粘贴短文本 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/DshClient.kt` | file | 唯一网络边界：bridge REST 强类型客户端 + SSE 订阅 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/ErrorMessages.kt` | file | 按错误码查的本地化出错文案 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/EventDebouncer.kt` | file | 事件驱动副作用闸门（安静一段时间再干一次） |
| `app/src/main/java/io/github/westanke/dshcompanion/data/GenUiBlocks.kt` | file | 正文切分为普通文字或 dsh-ui 卡片 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/HostStore.kt` | file | 极小键值存储抽象（为可测试而抽象） |
| `app/src/main/java/io/github/westanke/dshcompanion/data/HtmlPreview.kt` | file | 从 HTML 抽结构供 Compose 画原生控件 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/JobViews.kt` | file | 后台任务视图模型（bridge ≥ 2.4.3） |
| `app/src/main/java/io/github/westanke/dshcompanion/data/MentionQuery.kt` | file | 输入框 @ 提及的识别与替换 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/Models.kt` | file | bridge 响应/请求的序列化模型合集 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/PreviewKind.kt` | file | 会话文件预览该走哪条显示路径 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/RequestGate.kt` | file | 请求代际闸门：过期结果写不回状态 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/SecureConnectionStore.kt` | file | 凭据与主机存储的门面 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/SessionFileRefs.kt` | file | 会话里被 agent 提到过的文件路径提取 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/SessionStats.kt` | file | 会话执行统计（全来自历史事件） |
| `app/src/main/java/io/github/westanke/dshcompanion/data/Trajectory.kt` | file | 轨迹页数据：历史按轮次分组的节点 |
| `app/src/main/java/io/github/westanke/dshcompanion/data/UpdateChecker.kt` | file | 应用内更新检查：渠道分流、下载、SHA256 校验、调起安装器 |
| `app/src/main/java/io/github/westanke/dshcompanion/debug/DiagLog.kt` | file | 诊断日志单一出口（关了就什么都不做） |
| `app/src/main/java/io/github/westanke/dshcompanion/debug/DiagProbe.kt` | file | 诊断探针浮层（可拖动，收起成小条） |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/App.kt` | file | 应用外壳：主导航与页面切换 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/ChatScreen.kt` | file | 会话页容器与窄/宽屏布局选择 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/ChatWebScreen.kt` | file | WebView 承载 assets/chat.html 的会话详情页 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/Components.kt` | file | 跨页面复用的基础组件与语义色 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/ConnectImportPanel.kt` | file | 配置导入面板（粘贴一段文本） |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/ConnectScreen.kt` | file | 连接页：配对、手动填写、探活状态 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/FileEntryDialogs.kt` | file | 文件页三个输入型对话框：新建、移动/改名、回收站 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/FilePreviewToggle.kt` | file | 「源码 / 预览」切换键 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/FileTypeIcon.kt` | file | 文件类型图标（取系统已有关联） |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/FilesScreen.kt` | file | 文件页：授权根切换、浏览、编辑与上传下载 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/GenUiCard.kt` | file | 渲染一份 dsh-ui spec |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/ModelsEditorSheet.kt` | file | 编辑既有供应商的底部弹层 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/ModelsScreen.kt` | file | 模型与提供商设置页 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/PluginsScreen.kt` | file | 插件清单与启停页 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/SettingsScreen.kt` | file | 设置页（外观/诊断/关于/检查更新） |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/UnsavedChangesDialog.kt` | file | 未保存改动的三个出口：留下、丢弃、保存 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/UpdateCheckSection.kt` | file | 检查更新的一次性流程状态与 UI |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatActivityRows.kt` | file | 消息流里的非气泡两行：终局失败与折叠活动行 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatApprovals.kt` | file | 两类交互卡片：工具审批与待答问题 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatAttachments.kt` | file | 待发附件条与当前任务面板 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatCodeBlock.kt` | file | Markdown 代码块与表格组件替换 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatCodeHighlight.kt` | file | 轻量零依赖代码高亮 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatComposer.kt` | file | 输入区药丸（附件 · 输入 · 发送） |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatDeliverableCard.kt` | file | 显式交付物卡片（钉在轮次收尾） |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatDetail.kt` | file | 会话详情页骨架：顶栏、消息流与各区间挂载点 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatForkDialog.kt` | file | 「从这条消息分叉」确认框 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatHeader.kt` | file | 会话顶栏：标题、状态、文件、刷新、停止、⋯ 菜单 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatImageViewer.kt` | file | 消息图片：缩略图 + 点开全屏 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatJobsSheet.kt` | file | 后台任务面板 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatLinkHandlers.kt` | file | Markdown 链接的统一分发器 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatMarkdownNormalize.kt` | file | 渲染前规范化：给紧贴上一行的 GFM 表格补空行 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatMarkdownTable.kt` | file | Markdown 真表格渲染 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatMessageFooter.kt` | file | 气泡下方元信息与「从这条分叉」 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatMessageItems.kt` | file | 消息气泡：用户消息、助手正文与 dsh-ui 诊断行 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatPermissionMenu.kt` | file | ⋯ 菜单里的权限段与二次确认 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatPermissionPill.kt` | file | 发送区常驻的权限档位 Pill |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatPermissionPresetPicker.kt` | file | 创建会话时的权限档位选择器 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatPickers.kt` | file | 通用选择小部件（分组标题、选择行） |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatProcessGroup.kt` | file | 过程组折叠与 LazyColumn 稳定 key |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatScrollDiag.kt` | file | 滚动诊断探针与「等内容」拍数 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatScrollFreeze.kt` | file | 滚动期间冻结流式快照 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatSessionFiles.kt` | file | 「本会话的文件」面板 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatSessionPane.kt` | file | 会话列表层（窄屏整页 / 宽屏左栏 / 未选时概览） |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatSheets.kt` | file | 三个底部弹层：模型与智能体、工作区管理、新建会话 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatStatsSheet.kt` | file | 会话统计面板（执行维度） |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatSuggestions.kt` | file | 输入框上方 @ 文件与 / 命令两个补全面板 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatToast.kt` | file | 一次性反馈与剪贴板助手 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatToolBlocks.kt` | file | 工具调用专用块渲染（Read/Terminal/Diff） |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatToolDetail.kt` | file | 一条工具调用的详情视图 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/chat/ChatTrajectorySheet.kt` | file | 轨迹页：按轮次折时间轴并展开原始载荷 |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/theme/AppFontScale.kt` | file | 应用内字号缩放（替换 LocalDensity） |
| `app/src/main/java/io/github/westanke/dshcompanion/ui/theme/Theme.kt` | file | 应用主题（跟随系统/浅色/深色三档） |
| `app/src/main/java/io/github/westanke/dshcompanion/web/ActiveChatBridge.kt` | file | 当前存活的 ChatWebBridge（Activity 回调落点） |
| `app/src/main/java/io/github/westanke/dshcompanion/web/ChatWebBridge.kt` | file | 注入 assets/chat.html 的 JS 桥（唯一登记的网络例外） |
<!-- structure-check:end -->

---

## 7. 与其他文档的关系

| 想知道 | 去哪 |
| --- | --- |
| 为什么这样分层、状态怎么流动 | [ARCHITECTURE.md](ARCHITECTURE.md) |
| 某个类/方法签名、消费了哪些 bridge 端点 | [INTERFACES.md](INTERFACES.md) |
| 某能力有没有、还差什么 | [PARITY.md](PARITY.md) |
| 哪块代码是债、预算多少 | [TECH-DEBT.md](TECH-DEBT.md) |
| 改之前该避什么坑 | [PITFALLS.md](PITFALLS.md) |
| 界面规范 | `design-system/…/MASTER.md` |
