# Android 项目恢复入口

每轮先读 `STATUS.md`、`ROADMAP.md` 和 `PARITY.md`，涉及界面时同时读取 `../../design-system/deepseek-harness-android/MASTER.md`。App 只使用插件公开版本化 API；发现能力不足时先在插件仓库更新 API、OpenAPI/AsyncAPI、SDK、scope 和合约测试，再接入 App。

完成任务、架构决定、测试结论或能力矩阵变化时同步更新本文档目录。签名文件、密码、设备令牌和服务器正文不得进入文档或 Git。

每次提交前运行 `scripts/build.sh docsCheck`（**不是 `./gradlew`**，理由见 `AGENTS.md` §2）；该任务校验恢复文档、路线图状态与 `STATUS.md` 任务引用，Windows 可使用 `gradlew.bat docsCheck`。

## 文档目录

| 文件 | 什么时候读 |
|---|---|
| `STATUS.md` | **每轮开工先读**：现在到哪了、最近验证数据、插件基线 |
| `ROADMAP.md` | 任务台账与状态口径（`done` = 已随版本发布） |
| `PARITY.md` | 某能力有没有、还差什么 |
| `STRUCTURE.md` | **代码地图**：目录树、分层与依赖方向（含禁止方向）、逐目录职责、关键文件速查、新增东西放哪里。文末锚点归 `docsCheck` 双向校验 |
| `INTERFACES.md` | **契约与签名**：消费的 bridge 端点总表、事件流、能力位、内部模块符号、改接口清单。文末锚点归 `docsCheck` 双向校验 |
| `ARCHITECTURE.md` | 为什么这么设计（分层、数据流、契约） |
| `decisions/` | **为什么选 A 不选 B**（架构决策记录，App 侧从 `ADR-0100` 起） |
| `CHANGELOG-DEV.md` | 这次改了什么、测了什么（基于什么/为什么/干了什么/测了什么） |
| `PITFALLS.md` | **改代码前**：按名字读对应坑位，不看就改 = 重踩 |
| `TECH-DEBT.md` | 债务台账（只许收紧）与行数预算 |
| `RELEASE.md` | 发版顺序与必改清单 |
| `TESTING.md` | 测试口径与用例数演进 |
| `SECURITY.md` | 签名、令牌、备份边界 |
| `CONFIG-EMITTER.md` | 配置导入线格式与生成端 |
| `WS-FIRST-DESIGN.md` | **WS 优先、REST 兜底**改造的设计定稿：通道抽象、降级判据、requestId 关联、各阶段范围与硬验收 |
| `webview-port-todo.md` / `webview-parity-audit.md` / `webview-report.md` | 网页版 ↔ 原生 移植线与复盘 |

## 工程宪法

`AGENTS.md`（仓库根）是每次会话必然读到的那份约定：命令口径、版本与文档同步铁律、
架构纪律（分层 / 债务台账 / `@patch` 四要素）、测试纪律、对外表述纪律、坑位索引。
**规矩写在那里，不靠记忆、不靠口头。**

## 新增文档的规矩

- 长效文档必须在上表挂一行，**目标没了删行防死链**。
- 新增/移动/删除 `app/src/main/java/` 下的 `.kt`，或改公开签名/REST 端点时，**同一提交**更新
  `STRUCTURE.md` / `INTERFACES.md` 的机检锚点（`docsCheck` 双向校验，见 `AGENTS.md` §4.4）。
- 「说不清、要摆证据」的内容进 `docs/project/`；`AGENTS.md` 只留指针（超长会被静默截尾）。
- 新坑位 = 名字加进 `AGENTS.md` §7 清单 + 原文写进 `PITFALLS.md` 并补 `### 同名` 锚点。
