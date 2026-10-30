# 测试记录

> **本文件分两段**：前半段是**本 fork**（v1.1.0 → v1.9.0）的记录；后半段「上游 v1.0.0 时期」起
> 是上游 `Hakunm/dsh-android-app` 的历史，**原样保留**。后半段里出现的证书指纹 `A92938B3…` 与
> 「4096 位 RSA」指的是**上游 `CN=Hakunm` 证书**；本 fork 自 v1.1.0 起改用独立证书
> `CN=DSH Pocket Client`（RSA 2048，指纹 `4e7fa1db395c4a2436e7a96fcdf6c606e20a14bad55dfcd60c80892f4f92deb3`）。
> 两者签名不同，**后半段的签名结论不能套用到本 fork 的产物上**。

## 本 fork（v1.1.0 → v1.9.0）

- **2026-10-09 `DOC-003` 资料清理**：`docsCheck` 通过（8 份文档 / 35 个任务一致）；
  `:app:testDebugUnitTest --rerun-tasks` **169 用例 / 0 失败 / 0 跳过**（13 个测试类，其中新增
  GenUiBlocksTest 21、HtmlPreviewTest 7、PreviewKindTest 6）；中英字符串键 291/291 对齐。
  同时清理过期资料与历史 APK，并删除与实际不符的导航侧栏截图
  （`app-navigation.jpg`：v1.2.0 起抽屉多出「插件」入口、底部多出当前地址，旧图已失真）。
- **用例数演进**（以 `CHANGELOG-DEV.md` 各版本小节的记录为准）：

  | 版本 | 全量用例 / 失败 |
  | --- | --- |
  | v1.9.0 | 169 / 0 |
  | v1.7.0 | 156 / 0 |
  | v1.5.0 | 151 / 0 |
  | v1.4.0 | 135 / 0 |
  | v1.3.0 | 112 / 0 |

- **2026-10-16 `v2.2.41` 应用改名 + 包名迁移**（本表只到 v1.9.0，2.x 的逐版记录见 `CHANGELOG-DEV.md`）：
  `docsCheck` 通过；`:app:testDebugUnitTest` **27 类 / 290 用例 / 0 失败 / 0 跳过**
  （与 v2.2.40 逐项一致 —— 本版只改应用名、包名与一个 UI 测试断言，不动任何业务逻辑）；
  `compileDebugKotlin` 真执行（**非 UP-TO-DATE**），BUILD SUCCESSFUL，仅 3 条既有 deprecation warning；
  87 个 Kotlin 文件的 `package`/`import` 与源码目录整体迁移后**零残留**
  （`grep io.github.hakunm.deepseekharness` 命中 0）。
  - **未做 UI 测试**：`ConnectScreenTest` 属 `androidTest`，需真机/模拟器，本地无设备。
    该文件已把硬编码英文字面量改为按 `en` 配置开 Context 读 `values-en`，但**未实跑验证**。
  - **未做真机验收**：且因包名变更，验收前必须先卸载旧包（旧包无法覆盖安装）。

- **2026-10-17 质量收尾：测试数字一致性并入门禁（未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**（新增 `TestCountsTest`：三处声明一致 +
  用例数 == 源码 `@Test` 静态计数）。**首跑如期变红**（它自己让 409 → 410），同步后转绿。
  另查明一处口径：**测试文件 41 个 ≠ 测试类 43 个** —— `PreviewKindTest.kt` 里还有
  `HtmlPreviewTest`、`ArchitectureGuardTest.kt` 里还有 `ArchitectureGuardSelfCheckTest`，
  一个 `.kt` 可以声明多个测试类，所以文档里的「类数」不能拿文件数替代。
- **2026-10-17 质量收尾：中英 strings 一致性并入门禁（未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**（新增 `StringResourcesTest` 2 例：
  键集合双向一致、占位符一致；含下限断言）。**首跑两例都红，是我的路径拼错**（把仓库根当资源目录），
  测试对、实现错。
- **2026-10-17 质量收尾：坑位索引一致性并入门禁（未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**（`ArchitectureGuardTest` 新增
  「坑位索引与 PITFALLS 正文双向一致」，含下限断言）。
- **2026-10-17 B 批：Clock/Transport 接缝（未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**（用例数不变：改的是既有用例的**测量方式**）。
  `ConnectionCoordinator` 新增 `measureOverride` 接缝，三个依赖精确延迟比较的用例注入
  `fixedLatency`，「谁更快」不再受机器负载影响；并发性与挂死超时两个用例保留真实延迟。
  **DEBT-007 清账**。
- **2026-10-17 Whaleness 舒适度：轨迹页 UI（未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**（`TrajectoryTest` 增至 10 例：
  加「原始 data 原样带上」「短耗时两种形态与边界」）。
  **第三次被 `ArchitectureGuardTest` 抓到超预算**：`ChatDetail.kt` 810 行 →
  拆出 `ChatHeader.kt`（163 行顶栏）→ 636 / 214。**未做真机**。
- **2026-10-17 Whaleness 舒适度：轨迹页数据层（未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**（新增 `TrajectoryTest` 8 例：
  分组与排序、未知轮次不丢弃、Other 类型、五类映射、无名字工具、正常跨度、
  **乱序/零跨度返回 null**、空历史）。**首跑红一条：测试对、实现错**（min/max 算耗时在乱序时给假跨度），
  已改为「只在单调时给值」。另记 **DEBT-007**：`ConnectionCoordinatorTest` 的延迟容差用例 flaky。
- **2026-10-17 C 批：交付物实时到达（未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**（本次**无新增用例** ——
  改动是 ViewModel 的事件分支，该层在本项目没有测试基建）；
  `compileDebugKotlin` 真执行。**未做真机**。
- **2026-10-17 C 批：会话统计（执行维度，未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**（新增 `SessionStatsTest` 4 例：
  各类型计数、未知类型只进总数、新鲜度取最大序号、空历史）；
  `compileDebugKotlin` 真执行。**上下文用量环刻意不做**（内核 usage 不可证）。
- **2026-10-17 C 批：断档检测与 resync（未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**（新增 `ChatSeqMonitorTest` 8 例：
  连续 / 跳号 / 重复 / 倒退 / 无序号 / 无起点 / 基线三种比较 / cursor 未知）；
  `compileDebugKotlin` 真执行。**未做真机**：需人为构造「订阅窗口漏事件」才能看到 resync 生效。
- **2026-10-17 B 批：历史投影缓存（未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**（新增 `ChatVisibleItemsTest` 4 例：
  思考永不显示、上下文按开关、顺序保持、空历史）；
  `compileDebugKotlin` 真执行。**行数守卫第二次抓到超预算**（ChatDetail 797→越过 800），
  拆出 `ChatScrollFreeze.kt` → 781。
- **2026-10-17 B 批：事件去抖（未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**（新增 `EventDebouncerTest` 5 例，
  全部用 `runTest` 虚拟时间：风暴塌缩、窗口重置、分批各算、执行后再来、cancel。
  **首跑红两条，抓出实现写成了节流而非去抖** —— 改实现后全绿）；
  `compileDebugKotlin` 真执行。**未做真机**：批量改写文件时不再连发请求需上机验证。
- **2026-10-17 B 批：请求代际隔离（未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**（新增 `RequestGateTest` 4 例：
  初始号有效、invalidate 作废此前所有号、连点会话只有最后的号算数、8 线程 × 500 次并发领号互不重复）；
  `compileDebugKotlin` 真执行。**未做真机**：闸门语义有单测，端到端竞态仍需真机卡时序验证。
- **2026-10-17 A 批收官：`atSeq` 分叉入口（第六批，未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**（新增 `ChatForkPointTest` 4 例：
  用户消息是分叉点、助手/过程行不是、`seq = 0` 与负数被拒）；`compileDebugKotlin` 真执行。
  **行数守卫当场抓到超预算**（ChatMessageItems 806 / ChatDetail 805），按制度拆分为
  `ChatMessageFooter.kt`(84) + `ChatForkDialog.kt`(39) → 752 / 797。
  **未做真机**：入口观感与内核在 `atSeq` 处的包含边界（文案已避免承诺）。
- **2026-10-17 消息气泡时间戳（第五批，未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**（新增 `ChatTimeLabelTest` 6 例：
  三档粒度、跨零点边界、中英形态、时区）。**首跑红了两条，两条都是测试假设错、实现对**：
  换时区会连「哪一天」一起变（分档跟着掉档）；中文下 `MMM d` 实际是「10月 3」而非「10月3日」。
  `compileDebugKotlin` 真执行。**未做真机**。
- **2026-10-17 已归档会话可看（第四批，未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**（`BridgeCapabilitiesTest` 补 1 例：
  能力位存在 / 不存在 / 未知三种，未知必须为假 —— 否则入口会展示「一条归档都没有」的假结论）；
  `compileDebugKotlin` 真执行。**未做真机**：切换入口与归档行观感需上机。
- **2026-10-17 失败行「重试上一轮」（第三批，未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**（新增 `ChatRetryTest` 5 例：
  取最后一条用户消息、跳过空正文、无用户消息返回 null、越过中间行、逐字还原）；
  `compileDebugKotlin` 真执行。**未做真机**：按钮观感与重试后历史刷新时序需上机。
- **2026-10-17 客户端接入服务端能力位（第二批，未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败**（新增 `BridgeCapabilitiesTest` 9 例：
  正常解析、缺失/空清单算未知、脏项跳过（含 `42` 这类数字原语）、非对象载荷、
  缺口列表与顺序、依赖表守门）；`compileDebugKotlin` 真执行。
  **未做真机**：警示条观感与「旧服务端不弹假警告」的完整链路需上机。
- **2026-10-17 对标 Whaleness 的 A/B 两批客户端改动（未升版本号）**：
  `:app:testDebugUnitTest` **44 类 / 410 用例 / 0 失败 / 0 跳过**（口径：数
  `app/build/test-results/` XML）。本轮新增用例：过程分组 19（含 1 554 条序列的
  「摊平后与输入完全相等」属性断言）、代码高亮 12（含 20 万字符病态输入的性能钉子）、
  外观设置 10、架构门禁 8。其中 1 条是**修断言而不是改实现**：
  `[1, 2]` 里的两个数字本就都该着色，初版期望写成了只匹配 "1"。
  `compileDebugKotlin` 真执行（BUILD SUCCESSFUL）；`docsCheck` 8 文档 / 43 任务一致。
  - **未做真机验收**：滚动期冻结流式快照的手感、过程组折叠的观感、应用内字号在
    200% 系统字号下的布局、代码块长行横向滚动、图片保存到相册
    （API 28 及以下缺 `WRITE_EXTERNAL_STORAGE`，会给出明确失败提示而不是静默）——
    全部只能上机确认。**单测绿 ≠ 行为对**（AGENTS.md §5）。

- **v1.9.0 产物**：`artifacts/DeepSeek-Harness-companion-v1.9.0.apk`，2,714,772 字节，SHA-256
  `03007f1cbffb037a51e5e9b87ea0dce76c47dd8a700434fa1abb311544ec3942`；versionCode `10009`、
  versionName `1.9.0`；APK Signature Scheme v2，唯一签名者 `CN=DSH Pocket Client`（RSA 2048）。
  已从 GitHub Release 下载回验，字节数与哈希一致。
- **未做真机 UI 验证**：本环境无可用 Android 设备；长按选中/双击复制的手势边界、dsh-ui 卡片
  实际观感、会话文件预览分流都只过了逻辑测试与构建，需真机确认。

必须通过：

```bash
# 本机实测命令（离线依赖缓存 + 镜像脚本）
export JAVA_HOME=/media/wangke/OFFICE/workspace/android-toolchain/jdk17
export ANDROID_HOME=/media/wangke/OFFICE/workspace/android-toolchain/sdk
./gradlew :app:testDebugUnitTest -I init-mirrors.gradle
# 若显示 UP-TO-DATE（等于没跑），加 --rerun-tasks 强制真跑
```

```powershell
.\gradlew.bat docsCheck testDebugUnitTest lintDebug assembleRelease
apksigner verify --verbose app\build\outputs\apk\release\app-release.apk
```

- **2026-10-30 `v2.2.61` 智能团队只读面板 + 自然语言指挥条 + 权限档位分色**：**单测从 87 类 /
  872 用例增加到 88 类 / 874 用例 / 0 失败**（新增 `data/AgentTeamProjectionTest.kt` 5 例：
  全字段解析、bridge 极简夹具兜底、subagentCatalog 用 sessionId 键、无投影/空团队返回 null、
  脏数组元素跳过；新增 `ui/chat/PermissionToneTest.kt` 2 例：三档→INFO/WARN/DANGER、
  custom/未知/null/空串→NEUTRAL）。`scripts/build.sh` 门验四项全绿：`docsCheck`
  （10 文档 / 47 任务 / STRUCTURE 164 anchors 对 144 源文件 / INTERFACES 136）、
  `:app:testDebugUnitTest` 874 全绿、`:app:lintDebug` 0 error、`assembleDebug` 成功。
  **真机已验（V2080A，覆盖安装 lastUpdateTime 17:52:45）**：会话副行「已连接 + 红色完整访问
  + 品牌色智能团队」三胶囊；点团队胶囊直达面板，成员 lead/负责人/在岗、任务板空态、
  2 个可续用子会话卡（e083dffc…/d7a2b050…）、指挥 chips 对话框预填正确。
  单测绿 ≠ 行为对：只读/琥珀两档颜色用户叫停未验；真机菜单内切换权限档未完成闭环
  （DropdownMenu 在独立 window，uiautomator 抓不到菜单项，两次坐标点击未切换成功）。
- **2026-10-25 `v2.2.55` 对齐桥 v2.7.0（模型容量 + 空白会话复用）**：**单测从 68 类 / 627 用例
  增加到 69 类 / 634 用例**（新增 `data/ModelCapacityLabelTest.kt` 7 例，钉住 1024 进制与
  小数规则）；`docsCheck` 双向一致（10 文档 / 46 任务 / STRUCTURE 137 anchors /
  INTERFACES 54 anchors）；`lintDebug` **0 error**；行数 `DshClient.kt` 799 / `ChatSheets.kt` 781 /
  `ModelCapacityLabel.kt` 30，全部在 800 预算内。
  - **真机未验**：模型行那行容量小字要装包看一眼排版（模型名 + 两行描述 + 容量行可能偏挤）；
    `reuseBlank` 的实际效果也要等桥 v2.7.0 部署后连一次才知道。
- **2026-10-24 `v2.2.54` 连接页顶栏归位**：纯布局改动，**单测数量不变**（`68 类 / 627 用例 / 0 失败`，
  没有新增用例 —— 逻辑零改动，且 `androidTest/ConnectScreenTest.kt` 只断言标题文本与语言切换、
  不依赖图标位置）；`docsCheck` 双向一致（10 文档 / 46 任务 / STRUCTURE 136 anchors /
  INTERFACES 54 anchors）；`lintDebug` **0 error**；`assembleDebug` BUILD SUCCESSFUL。
  - **真机验了**（V2080A / Android，1080×2400 截图）：PIL 算包围盒，brand 方块 120×120px、
    诊断图标、语言图标**水平中线完全重合于 y=179.5**；`uiautomator dump` 层级
    brand `[60,120][180,240]` / 诊断 `[768,108][912,252]` / 语言 `[912,108][1056,252]`
    （144px = 48dp 触控区下限），滚动区起点 y=264。中/英 × 浅/深四组截图无裁切。
  - 遗留：诊断浮层打开时压住 BrandMark 左下角（`DiagProbe` 固定 TopStart，可拖动；
    按 PITFALLS「浮层不调死坐标」不改）。

- **2026-10-23 `v2.2.53` 设备身份上报**（配套桥 v2.6.1）：
  `:app:testDebugUnitTest` **68 类 / 627 用例 / 0 失败**；`docsCheck` 双向一致
  （10 文档 / 46 任务 / STRUCTURE 136 anchors / INTERFACES 54 anchors）；
  `lintDebug` **0 error**；`assembleDebug` BUILD SUCCESSFUL。
  新增 2 例：`authenticatedRequestsReportDeviceIdentity`（断言 `X-Dsh-Device` 头存在且值形如
  `android/<机型>`，并验证 `/api/v1/devices/self` 的三个新字段能读到）、
  `deviceIdentityFieldsAreOptionalForOlderBridges`（老桥不返回 `platform`/`model`/`nameSource`
  时三者均为 null、不抛异常）。
  - **未真机验**：需桥 v2.6.1 部署后，设备表里出现「Android · <机型>」才算闭环。

## 上游 v1.0.0 时期（2026-08-14/15）

- 2026-08-15 README 六图复验：中英文 README 均引用 6 张存在的 JPG；新增图片与用户原始真机截图 SHA-256 一致，截图来源说明已删除。
- 2026-08-15 最终发布刷新：Temurin 17 下 `docsCheck testDebugUnitTest lintDebug assembleRelease` 通过，8 份文档/31 个任务一致，lint、R8 Release 与专用签名门禁全部成功。正式 APK 为 2,561,512 字节，SHA-256 `80940602D79ACB68D5805122FEC4E6678F39ADEF02DEDAEE4DD7B4856CD7DBBE`。
- 2026-08-15 README 图片复验：使用用户提供的 4 张真实手机截图展示导航、聊天、带行号/缩放的文本编辑器和模型供应商；连接地址已打码，未采用模拟器截图。Oracle 会话存储与工作区注册表均确认只保留 1 个本次发布演示会话。
- 2026-08-15 v1.0.0：`docsCheck testDebugUnitTest lintDebug assembleRelease` 全部通过；MockWebServer 新增工作区重命名/移除和会话重命名/分叉/归档请求契约。APK 包名 `io.github.hakunm.deepseekharness`、versionCode `10000`、versionName `1.0.0`、min SDK 26、target SDK 36；2,561,512 字节，SHA-256 `823344D6CEAFF9FE030F625335191D52ABE97FB6D02AFD2B9555B12680E4A266`。APK Signature Scheme v2、单一 Hakunm 4096 位 RSA 正式证书，证书 SHA-256 `A92938B33F59D90993ADA96437F8A8DEF987A27353E46A018661DDC1587046B1`。
- 2026-08-15 README 发布复验：Temurin 17 下 `docsCheck testDebugUnitTest` 通过，8 份恢复文档/25 个任务一致；三张已入库 App 截图逐张检查，无设备令牌、API 密钥或服务器连接地址。
- 2026-08-15 `CI-001`：首个 GitHub hosted run 暴露 Release 签名校验发生在 Gradle 配置阶段，导致无私钥的 `assembleDebug` 也失败。本地以不存在的 `dshSigningProperties` 复现后修复：`docsCheck testDebugUnitTest lintDebug assembleDebug` 通过；`assembleRelease --dry-run` 包含 `verifyDedicatedReleaseSigning -> packageRelease`，验证任务在缺少签名时以 `Dedicated signing config is required` 失败，且配置缓存可正常保存。修复提交的 [GitHub Actions run 31834143207](https://github.com/Hakunm/dsh-android-app/actions/runs/31834143207) 在 Ubuntu hosted runner 成功。

### 2026-08-15

> **关于本节及下方 `2026-08-14` 一节的截图引用**：文中提到的 `.tmp/*` 截图与 UI 层级文件
> 都是当时的临时产物，早已随临时目录清理，不在仓库里。已移除这些死引用，
> 但保留验收结论本身 —— 记录的是「当年验过什么」，不是「文件还在」。


- 完整门禁 `docsCheck testDebugUnitTest lintDebug assembleRelease` 通过，8 份文档/22 个任务一致；Release APK 为 2,538,256 字节，SHA-256 `E9FD6DEBD8B5DAD094273E9EDEBB49BF551D73103941B28C77FDC0A153DD5C65`。`apksigner` 验证 APK v2、单一签名者、4096 位 RSA 正式证书，证书 SHA-256 仍为 `A92938B33F59D90993ADA96437F8A8DEF987A27353E46A018661DDC1587046B1`。
- Android 15 尺寸设备覆盖安装后连接真实 Oracle 新版 listener：空白会话显示当前“工作区写入”权限选择器；输入 `/` 后列出 `compact/export/feedback/goal/permission` 等当前 host 命令，已知内置命令说明按 App 语言显示中文或英文，截图已随临时目录清理，不再保留。当前服务器没有含 TODO 的运行，任务模块由 projection 单测覆盖，真实实时状态留待下一次产生 TODO 的任务。
- `TODO-001`、`COMMAND-001`、`PERMISSION-001` 与 `BRAND-001` JVM/Lint 门禁通过：`testDebugUnitTest lintDebug` 成功；投影单测覆盖 TODO 状态与权限选项，MockWebServer 验证 `/chat/sessions/{id}/commands` 列举/执行路径和命令正文。Compose 编译与中英双语资源检查通过。
- Oracle root `web` profile 已安装审批版插件并按用户明确要求重启；`3080` WebUI、`3090` listener、健康检查、审批 OpenAPI 和 Host bundle 哈希通过。旧待审批会话因重启结束，真实 App 允许一次/拒绝验收将使用新会话。

### 2026-08-14

- `APPROVAL-001` 门禁通过：DshClient 新增待审批读取和单次决定合约测试；`docsCheck testDebugUnitTest lintDebug assembleRelease` 全部成功，Release 已覆盖安装到 Android 15 测试设备。APK 为 2,510,044 字节，SHA-256 `C59D70F965A65EDAFDC847839E4E954111A736F6C77F1F85E63EA219F622F96B`。真实 Oracle 插件包因活动会话仍在等待审批而只暂存未重载，真实决定验收延后且没有替用户批准/拒绝。
- `APPROVAL-001` 真实故障取证：Oracle DSH、`3080/3090` 和 Node 事件循环均健康；最新会话尾事件为 Bash `approval/asked`，请求把 Skill 安装到工作区外的 `/root/.dsh/skills` 并申请 `danger-full-access`。App 尚无审批 BFF/UI，因此只显示“运行中”且无后续正文。确认安装命令尚未执行、目标 Skill 目录不存在、DSH 下无安装子进程；服务器状态未修改。
- `IME-001` 实机反馈后的第二轮修复：Connected `Scaffold` 在应用 `PaddingValues` 后调用 `consumeWindowInsets`，聊天列再使用 `imePadding`，因此只补父级尚未处理的 IME 高度；Composer 自身 3dp 外边距保留，使输入区位于键盘上沿少许而不贴死。`docsCheck testDebugUnitTest lintDebug assembleRelease` 全部通过并覆盖安装。测试镜像的 Sogou IME 虽报告 `mInputShown=true`，其窗口 frame 仍为 `[0,1920][1080,1920]` 零高，最终键盘视觉间距等待用户实际输入法确认。
- 本轮 Release APK 为 2,471,396 字节，SHA-256 `1369EF2CDC6E37225C258A18EB1CAA6AF98FEF140428B4EC440ADAE6A30D5425`；APK v2、单一 4096 位 RSA 正式证书和覆盖安装验证通过。
- `STREAM-001` Android 15 真实 listener 验收：通过 App 发送 120 个数字的长回复并以约 350 ms 间隔采集 28 帧；正文从空白增长至 `1...34`，随后增长至 `1...120`，证明 Compose 直接消费 `chat.message.delta`，而不是等待 REST 返回完整消息。最终持久正文正常收口，logcat 无 `FATAL EXCEPTION`、`RATE_LIMITED` 或 App 崩溃。
- `AGENT-001` Android 15 界面验收：已开始会话展示当前 `PTC` Agent，其他 preset 保持可查看但不可点击，并显示“会话开始后 Agent 配置由 DSH 锁定”；服务端真实探针确认空白会话可切换，首条消息后返回 `409 AGENT_PRESET_LOCKED`。
- `IME-001` 第一轮方案曾完全移除聊天 `imePadding`，但用户实机确认该方案会使输入框被键盘覆盖，已由本节顶部记录的“父级消费、聊天补剩余 inset”方案取代；测试镜像的 Sogou IME 窗口为零高，无法替代用户实际输入法的最终视觉复验。
- 最终门禁 `docsCheck testDebugUnitTest lintDebug assembleRelease` 通过：8 份恢复文档、17 个任务一致，JVM 单测、lint、R8 Release 全部成功。APK 为 2,471,396 字节，SHA-256 `764FA914AF1B43C5A83B8FA16A27C835A0A8788114E85F699B0FAA32D006A0F6`；APK v2 签名、独立 4096 位 RSA 证书和覆盖安装复验通过。
- `testDebugUnitTest`：通过；新增插件稳定 `chat.message.delta` 的中文分片累积测试，以及现有会话 Agent Preset `PUT` 客户端合约。
- `testDebugUnitTest`：通过，10 项测试覆盖地址规范化、健康检查、配对、Bearer、Unicode wire path、ETag 写入、流式上传下载、历史分页、WebSocket 握手、DSH 工作区/Agent Preset、聊天事件投影、会话模型/思考强度以及供应商读取/更新/模型发现合约。
- `docsCheck`：通过，8 份恢复文档和 12 个稳定任务 ID 一致。
- `lintDebug`：通过，无 error。保留的 `InsecureBaseConfiguration` 是允许 HTTP 直连的产品决策；界面持续显示明文风险，HTTPS/VPN 仍为推荐配置。
- `assembleRelease`：通过 R8、资源压缩和签名配置验证。
- `apksigner verify --verbose --print-certs`：v2 签名通过，唯一签名者为独立 4096 位 RSA 证书，证书 SHA-256 为 `A92938B33F59D90993ADA96437F8A8DEF987A27353E46A018661DDC1587046B1`。
- `aapt dump badging`：确认包名 `io.github.hakunm.deepseekharness`、version `0.1.0`、min SDK 26、target SDK 36。
- `UI-002` 重构后的最终 Release APK：2,442,576 字节，文件 SHA-256 为 `37EC1424D1381AE7E0C7276D17304F024080605BC8A254B304900A3961CEB3BC`。
- `connectedDebugAndroidTest`：Android 15 设备通过 1 项 Compose 测试，验证首次启动默认中文、连接表单和中英文双向切换。一次运行因设备保留了真实配对状态而无法看到连接首屏，撤销临时设备并在干净安装后重跑通过。
- 本机真实插件 listener `http://127.0.0.1:3090/api/v1`：临时根和一次性设备完成配对、5 scope、根过滤、文件创建/读取、ETag 更新、移动、软删除、回收站列表、恢复及会话过滤；随后撤销 2 个测试设备并移除临时根。
- Android 15 发布版通过 `adb reverse tcp:3090 tcp:3090` 连接真实插件：健康检查、一次性配对、令牌恢复、WebSocket 握手、会话空状态、授权根列表及设置页均通过；发现 `unexpected scheme: ws` 后改为由 OkHttp 从 HTTP(S) URL 执行 Upgrade，并增加回归单测。临时设备已撤销、临时根已移除。
- 更新后的真实插件返回 4 个 DSH 系统 Agent 模式；本地 DSH 未登记会话工作区时 `/chat/workspaces` 返回空列表，确认 App 不再把插件文件授权根标签显示为新会话工作区。临时合约设备随后撤销。
- Android 15 发布版实际打开新建会话对话框：空列表显示“没有可用的 DSH 工作区”且禁用创建；临时在 DSH WebUI 登记插件源码工作区后，App 显示标题 `dsh-android-app-workspace` 并启用创建，Agent 下拉完整显示服务器默认、标准、PTC、极简和创造模式及说明。临时工作区、设备授权和 App 数据在验收后清理。
- 真实 DSH 空白会话只含 `permission/preset`、`sandbox/mode`、`approval/policy` 和 `session/end-seed` 初始化事件；Android 15 会话页显示“暂无可显示的正文”，未再回显这些内部事件 JSON。该会话没有可用于视觉验收的用户/助手正文，非空正文与工具投影由单元回归覆盖。
- 已人工检查连接页、会话页、文件页和设置页的 1080×1920 截图，无文字遮挡或横向溢出。
- `UI-001` 历史基线曾在 Android 15 的 1080×1920 视口检查连接、会话、文件、设置和新建会话；该导航结构已由 `UI-002` 的移动端抽屉和宽屏固定侧栏取代。测试只创建临时设备授权，未更改 DSH 原始工作区或插件根。
- `UI-001` 最终验证命令 `docsCheck testDebugUnitTest lintDebug assembleRelease connectedDebugAndroidTest` 通过；文档校验为 8 份文档/8 个任务，Android 15 Compose 测试 1/1 通过。APK v2 证书 SHA-256 仍为 `A92938B33F59D90993ADA96437F8A8DEF987A27353E46A018661DDC1587046B1`。
- `MODEL-001` 真实 Oracle 合约探针通过：返回 37 个供应商、6 个授权会话、当前 `opencode-go/deepseek-v4-pro/max` 和 2 个可路由模型组；Android 15 模型面板显示模型及 Off/High/Max，并成功切换到 High。供应商页显示凭据状态和配置入口，不回显密钥。
- `MD-001` 使用真实 DSH 回复验收：标题和表格按排版/表格渲染，界面未显示原始 `##`、`**` 与管道表格标记。`multiplatform-markdown-renderer-m3` 固定为 `0.41.0`；`0.43.0` 要求 compile SDK 37，与当前 SDK 36 基线不兼容，因此未采用。
- `RATE-001` 使用真实会话发送 `Reply with: streaming refresh OK`，正文正常增量完成；连续观察 24 秒无 429 UI、无 `RATE_LIMITED` 日志、无崩溃。最终又移除发送/取消后的同步会话列表请求，改为合并队列刷新；重新构建并覆盖安装后启动日志仍无 `FATAL EXCEPTION` 或 `RATE_LIMITED`。
- `UI-002` 已在 1080×1920 Android 15 设备检查会话列表、聊天、模型面板、供应商设置、文件页和 Markdown；最终安装截图与 UI 层级文件已随临时目录清理；未发现横向溢出、底部控件裁切或文字遮挡。
- 最终命令 `docsCheck testDebugUnitTest lintDebug assembleRelease` 通过；APK v2 唯一签名者为 4096 位 RSA 独立证书，证书 SHA-256 仍为 `A92938B33F59D90993ADA96437F8A8DEF987A27353E46A018661DDC1587046B1`。
- `UI-003` 在 1080×1920 Android 15 设备验收：全局标题从 30sp 级收紧至 24sp 级，主要聊天正文为 15sp/22sp，消息间距降至 8dp，输入正文区从 74dp 降至 44dp；空输入容器屏幕高度从约 366px 降至约 303px。Markdown 使用独立紧凑排版表，H1-H6 映射至 App 的 24/20/18/15/13sp 层级，“系统环境”等标题不再异常放大。
- `UI-003` 最终截图已随临时目录清理；发送按钮保持 44dp，导航/新增按钮保持 48dp。最终 APK 为 2,442,576 字节，SHA-256 `7ABD069C7653686E5413B74E3A1862BEF18C0568E06BE3ECCFF0AAFD4B755515`，正式证书与签名方案不变。
- 工作区/供应商/文件编辑扩展通过 `docsCheck testDebugUnitTest lintDebug assembleRelease`：新建会话可从授权根浏览目录并调用插件登记 DSH 工作区；供应商合约覆盖自定义能力/创建；文字编辑器显示同步行号，Markdown 可切换源码与渲染预览。
- `UI-004` 在 1080×1920 Android 15 复验：聊天输入容器由约 101dp 进一步降至 88dp，用户气泡正文与 Markdown 段落统一为 14sp/20sp，消息区间距为 5dp；新建工作区弹窗压缩标题间距和目录列表后，Agent 下拉与“创建工作区并开始会话”按钮完整显示。截图已随临时目录清理。
- 最终 Release APK 为 2,467,484 字节，SHA-256 `22882C77C519B70FDC65F9FC4CCC54E90A5BA999344C88D014DAA2A217770179`；APK v2 签名通过，唯一 4096 位 RSA 证书 SHA-256 仍为 `A92938B33F59D90993ADA96437F8A8DEF987A27353E46A018661DDC1587046B1`，覆盖安装成功。
- `APP-004` 文字缩放扩展通过 `docsCheck testDebugUnitTest lintDebug assembleRelease`。Android 15 在 `.bashrc` 源码验证 `100%→150%→100%`，行号沟槽、字体与行高同步变化；Markdown 验收文件验证预览 `100%→150%`，切换回源码仍保持 `150%`。双指处理仅消费两个及以上指针，按钮作为无手势替代。截图已随临时目录清理（服务器临时文件随后删除）。
- 当前 Release APK 为 2,468,808 字节，SHA-256 `EE2676CF422FBDE74730258AA0CF4866CDCF65268DB54716960C9F826A36BD51`；APK v2 签名及唯一 4096 位 RSA 正式证书 SHA-256 `A92938B33F59D90993ADA96437F8A8DEF987A27353E46A018661DDC1587046B1` 复验通过并已覆盖安装。
- 未完成：局域网 HTTPS、steer/取消、文件编辑与陈旧 ETag 冲突、上传下载、WebSocket 网络切换、旋转/进程恢复及平板实机布局仍待端到端执行。
- `v2.2.43`（**纯仓库卫生，无代码改动**）通过 `docsCheck`（8 文档 / 46 任务一致）与 `:app:testDebugUnitTest`（**436 用例 / 0 失败**，48 个测试文件 / 50 个测试类）。忽略规则做了正负向实测：真建 `.wm9` / `.apkcheck3` / `.m3` / `.mdlib` 四个目录，`git check-ignore` 全部命中对应行；负向确认 `app/`、`docs/`、`scripts/` 未命中；漏洞回归复现 `.secrets/__probe__/secret.txt`，现已命中 `/.secrets/` 且 `git status` 条数为 0；`.secrets` 四个文件字节数复核未变。发布包 **2,965,986 字节**，SHA-256 `5bad043b0d75c96ceb57572245bc25e1f7585cc4f64de867a4ce1d7c27380f9a`，包内 `versionCode 10068` / `versionName 2.2.43`，v2 签名证书 `CN=DSH Pocket Client`（证书 SHA-256 `8b2ba797…`），构建水印 `afbea7d`。**未做真机验收**（无界面改动）。
- `v2.2.45` 通过 `docsCheck`（8 文档 / 46 任务一致）、`:app:testDebugUnitTest`（**57 个测试文件 / 57 个测试类 / 505 用例 / 0 失败**）、`:app:lintDebug`（0 error）与 `assembleRelease`。发布包 **3,034,832 字节**，SHA-256 `6bd2634d08648f71ec5d0d32875a0228cf93f3ddaed7e013543e08c8bf9e713f`，包内 `versionCode 10070` / `versionName 2.2.45`。
  本轮新增用例：`ComposerSendButtonTest`(3，发送键三态优先级)、`ChatPathLinksTest`(7，正文文件名链接化的三条安全约束)、`ChatLinkTargetTest`(+2，Markdown autolink 尖括号剥除)。
  **真机（vivo V2080A / Android 11 / 1080×2400 / serial 9540718532001JR）逐条核对**：权限 Pill 已移到状态行「● 已连接 🔒 完整访问」；状态点绿/红生效；顶栏只剩汉堡/文件夹/⋯（刷新与停止已入菜单）；输入区 + 号与发送键垂直居中、发送箭头朝上；运行中且输入框空显示**红色方形停止键**；权限菜单描述全中文（英文残留 0 处）；点会话正文里的文件链接→文件详情面板打开并渲染 Markdown（`text/markdown · 12.2 KiB`）。
  **未验**：「用其他应用打开」（需 docx/zip 类素材，尚未构造）。
- `v2.2.47` 通过 `docsCheck`（8 文档 / 46 任务一致 + README 双站 14 项）、`:app:testDebugUnitTest`（**60 个测试文件 / 542 用例 / 0 失败**）、`:app:lintDebug`（0 error）与 `assembleRelease`。
  本轮新增用例：`ChatLinkTargetTest` +5（22 → 27）——绝对路径六种位置写法（`:1` / `:12-34` / `:12:5` / `#L101` / `#L101-L120` / `#l7`）归到同一文件、
  相对路径带后缀仍归 `RELATIVE_PATH` 且 `raw` 保留原文、网页 `#L1` 片段与 `:8080` 端口一字不动（含尖括号包裹写法）、
  `stripLocationSuffix` 边界（日期不误伤 / 行范围要剥 / `:12` 不剥成空 / 三段只剥两段 / 空串原样返回）、
  `joinUnderCwd` 拼接（含前导与尾随斜杠）。
  **未做真机验收**：按用户既定口径由用户自行完成。单测绿 ≠ 行为对。
- `v2.2.48`（三笔未发版合并发版：Markdown 表格贴行 + 应用内更新检查 + 会话内联交付物卡片）
  通过 `docsCheck`（8 文档 / 46 任务一致 + README 双站）、`:app:testDebugUnitTest`
  （**64 个测试文件 / 62 个测试类 / 575 用例 / 0 失败**）、`:app:lintDebug`（0 error）
  与 `assembleRelease`（发布包真实体积 / SHA 见 README 双站与 `artifacts/SHA256SUMS.txt`）。
  本轮新增用例：`ChatDeliverableCardsTest`(6) —— 轮次收尾钉卡（卡片在收尾消息后、下一轮提问前）、
  无 `turn/end` 补尾、同轮同路径单卡取最后说明、失败轮保卡（交付在失败前）、
  空 `files` / 缺说明容忍、DELIVERABLE 不折进过程组。
  真机状态：表格贴行已复验（2026-10-20）、更新检查已复验（2026-10-21，检查/解析链路）；
  交付物卡片与更新下载/安装链路未上机 —— 按用户既定口径由用户自行完成。单测绿 ≠ 行为对。
