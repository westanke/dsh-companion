# 技术债务台账（TECH-DEBT）

> 台账不是豁免，**是债务**：每条都注明在哪一阶段清掉。
> **只许收紧**：条目对应的债务消失，同一提交里删掉该条目（自证非空转）。
> **上调必须带理由**：超过行数预算只许带理由在同一提交里上调，且必须在下一阶段拆回。
>
> 制度来源见 `AGENTS.md` §4.2；本文件是**手工台账，暂不进 CI**（Kotlin 侧无等价解析器）。
> 它回答的问题是「欠了多少、在往哪个方向走」，不是「谁违规了」。

## 行数预算

**当前预算：800 行/文件。**

超预算文件必须登记在下面。基线测量日：2026-10-16。

### 当前状态：**零超预算**

截至 2026-10-16，仓库内**没有任何文件超过 800 行**。最大的三个是：

| 文件 | 行数 | 说明 |
|---|---|---|
| `ui/chat/ChatSheets.kt` | 797 | 模型 / 工作区 / 新建会话三个弹层（从 `ChatScreen.kt` 拆出） |
| `ui/FilesScreen.kt` | 764 | 「文件」页（本次未动，本就未超预算） |
| `ui/chat/ChatMessageItems.kt` | 748 | 消息气泡与助手正文（从 `ChatScreen.kt` 拆出） |

> 新增超预算文件必须**先入本表再写码**。这是硬门槛，不是建议。

## 本轮清账记录（2026-10-16）

原台账登记了两条债，本轮全部清掉。记在这里是为了让「为什么台账空着」有据可查：

| 原债务 | 原规模 | 清账方式 | 结果 |
|---|---|---|---|
| `ui/ChatScreen.kt` | 3 882 行 | 按 `webview-port-todo.md` 的 PORT-* 分组拆成 `ui/chat/` 下 12 个文件 | **329 行** |
| `HarnessViewModel.kt` | 2 277 行 | 按域拆成「状态 / 事件纯函数 / 六个域文件」共 9 个文件 | **663 行** |
| `data/ChatPresentation.kt` | 825 行 | 按职责拆成模型 / 映射 / 交接 / 工具 / JSON 五块 | **98 行** |

拆分的判据是**职责边界**，不是行数凑整：每个新文件的 KDoc 都写清楚「这里放什么、
为什么与相邻文件分开」，以便后来者知道往哪放新代码。

### 两处必须记录的代价

1. **可见性放宽**：原先 `private` 的部分成员提升为 `internal` ——
   `HarnessViewModel` 的 `mutableState` / `update` / `withClient` / `client` / `hostStore` 等，
   `ChatScreen.kt` 里的组件（`ChatItem` / `Composer` / `SessionPane` …），
   `data/ChatPresentation.kt` 里的 JSON 取值助手。
   `internal` 只在本 module 内可见，等于「同包可见」，比 `public` 窄得多，
   但确实比原来的 `private` 宽。**这是拆文件的必然代价**，不是设计选择。
2. **ViewModel 域文件用扩展函数而不是子类**：`HarnessViewModel` 的成员函数被搬到
   `Harness*ViewModel.kt` 里写成 `fun HarnessViewModel.xxx()`。调用点写法完全不变
   （`viewModel.loadDirectory()` 照样成立），所以 `ui/` 下所有界面的**行为**一行都没改；
   代价是 IDE 里「这个方法在哪」需要跨文件跳转。

## 已知结构性债务

| ID | 债务 | 位置 | 为什么现在不动 | 清账条件 |
|---|---|---|---|---|
| DEBT-001 | 网页版与原生双实现并存 | `app/src/main/assets/chat.html`（2 476 行）+ `ui/chat/` | 网页版是兜底资产，真机验证过；先完成原生反向移植 | 原生功能覆盖 `PARITY.md` 全项且网页版使用率归零 |
| DEBT-002 | README 双站无自动对账 | `README.md` / `README.en.md` | `docsCheck` 只覆盖 `docs/project/`；Kotlin/Gradle 侧无 README 解析器 | 扩展 `docsCheck` 增加 README 版本号/测试数一致性检查 |
| DEBT-003 | 无 lint baseline / 静态检查配置 | 仓库根 | 当前 `lintDebug` 在 CI 跑但无 baseline，warning 数不宜当门禁 | 建立 `lint-baseline.xml` 后把新增 warning 当门禁 |
| DEBT-004 | 无 `@patch` 补丁登记表自动化 | 全仓 | 四要素制度已立（`AGENTS.md` §4.3），尚无脚本生成登记表 | `docsCheck` 扩展：扫 `// @patch` 块校验四要素齐全 + `test` 指向的测试真实存在 |
| DEBT-005 | 行数预算不进 CI | 仓库根 | Kotlin 侧没有等价于上游 `architecture.test.ts` 的行数检查器；靠本文件手工台账 | 写一个 Gradle 任务扫 `app/src/**/*.kt` 的行数，超 800 视为构建失败 |

## 账怎么记

- **新增**：写码前先入表，注明「为什么是债」与「清账阶段」。
- **清账**：同一提交里删条目 + 在 `CHANGELOG-DEV.md` 记一条（基于什么/为什么/干了什么/测了什么）。
- **收紧**：行数下降时更新数字，不留旧值（旧值会骗人）。

> 判据不是「有没有债」——**债可见且方向向下，比假装没有债更可信。**