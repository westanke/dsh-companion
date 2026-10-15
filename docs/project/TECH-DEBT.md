# 技术债务台账（TECH-DEBT）

> 台账不是豁免，**是债务**：每条都注明在哪一阶段清掉。
> **只许收紧**：条目对应的债务消失，同一提交里删掉该条目（自证非空转）。
> **上调必须带理由**：超过行数预算只许带理由在同一提交里上调，且必须在下一阶段拆回。
>
> 制度来源见 `AGENTS.md` §4.2；本文件是**手工台账，暂不进 CI**（Kotlin 侧无等价解析器）。
> 它回答的问题是「欠了多少、在往哪个方向走」，不是「谁违规了」。

## 行数预算

**当前预算：800 行/文件。**

超预算文件必须登记在下面。基线测量日：2026-10-15。

| 文件 | 行数 | 为什么是债 | 清账阶段 |
|---|---|---|---|
| `app/src/main/java/io/github/hakunm/deepseekharness/ui/ChatScreen.kt` | 3 619 | 会话详情页单文件承载：消息流、输入区、思考条、工具行、文件弹层、`@` 面板、手势分发。改一处要读 3 600 行 | 按 `webview-port-todo.md` 的 PORT-* 分组，逐组拆出 `ui/chat/` 子模块 |
| `app/src/main/java/io/github/hakunm/deepseekharness/HarnessViewModel.kt` | 2 277 | 连接 + 会话 + 文件 + 插件 + 设置全在一个 ViewModel；`ChatScreen` 与它双向依赖 | 按域拆 `ConnectionViewModel` / `ChatViewModel` / `FilesViewModel` |

> 这两个是**已登记的债，不是许可**。新增超预算文件必须先入本表再写码。

## 已知结构性债务

| ID | 债务 | 位置 | 为什么现在不动 | 清账条件 |
|---|---|---|---|---|
| DEBT-001 | 网页版与原生双实现并存 | `app/src/main/assets/chat.html`（2 476 行）+ `ui/ChatScreen.kt` | 网页版是兜底资产，真机验证过；先完成原生反向移植 | 原生功能覆盖 `PARITY.md` 全项且网页版使用率归零 |
| DEBT-002 | README 双站无自动对账 | `README.md` / `README.en.md` | `docsCheck` 只覆盖 `docs/project/`；Kotlin/Gradle 侧无 README 解析器 | 扩展 `docsCheck` 增加 README 版本号/测试数一致性检查 |
| DEBT-003 | 无 lint baseline / 静态检查配置 | 仓库根 | 当前 `lintDebug` 在 CI 跑但无 baseline，warning 数不宜当门禁 | 建立 `lint-baseline.xml` 后把新增 warning 当门禁 |
| DEBT-004 | 无 `@patch` 补丁登记表自动化 | 全仓 | 四要素制度已立（`AGENTS.md` §4.3），尚无脚本生成登记表 | `docsCheck` 扩展：扫 `// @patch` 块校验四要素齐全 + `test` 指向的测试真实存在 |

## 账怎么记

- **新增**：写码前先入表，注明「为什么是债」与「清账阶段」。
- **清账**：同一提交里删条目 + 在 `CHANGELOG-DEV.md` 记一条（基于什么/为什么/干了什么/测了什么）。
- **收紧**：行数下降时更新数字，不留旧值（旧值会骗人）。

> 判据不是「有没有债」——**债可见且方向向下，比假装没有债更可信。**
