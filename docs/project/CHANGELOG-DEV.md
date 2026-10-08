# 开发日志

本文件记录本仓库的改动。每条都写清**基于什么、为什么、干了什么、测了什么**。
上半部分是本仓库新增的开发记录，下半部分是上游 `Hakunm/dsh-android-app` 的历史（原样保留）。

---

## 2026-10-08 · 插件清单页（配合服务端 `PLUGIN-001`）

### 为什么

用户反馈第 2 条：**「功能单调点，无法查看装的插件情况」**。

这条**无法纯靠客户端解决** —— 核实过 `dsh-workspace` 的对外 API 面：`/api/v1` 原有 10 个端点
（`healthz` / `pairings/exchange` / `devices/self` / `roots` / `trash` / `chat/sessions` /
`chat/workspaces` / `chat/agent-presets` / `settings/models` / `settings/providers`）外加 WS `events`，
**没有任何一个与插件相关**；loopback 管理面 `/manage/status` 也只回 `remote` / `roots` / `devices`。

因此先在插件侧加了 `GET /api/v1/settings/plugins`（见 `dsh-workspace` 的 `PLUGIN-001`），
本仓库负责把它显示出来。

### 干了什么

- `data/Models.kt` 新增 `PluginEntry` / `PluginInventory`。
- `data/DshClient.kt` 新增 `pluginInventory()`。
- `HarnessViewModel` 新增 `pluginInventory` 状态、`refreshPluginInventory()`，并在 `loadSnapshot()`
  里随顶栏刷新/重连一起带上。
- 新增 `ui/PluginsScreen.kt`：显示 profile 名、已加载数、问题数，逐条列出名称/实际版本/状态徽标；
  `problemCount > 0` 时给出提示条。
- `ui/App.kt` 新增「插件」页签，由 `settings.read` scope 门控。
- 中英字符串各 +23 条。

### 两处契约缺口（值得记录，因为都是「文档比实现少写了」的类型）

服务端返回的形态里有两点与最初的任务描述不一致，**若照描述写就会在真实设备上出问题**：

1. **`state` 有四种取值，不是三种。** 除 `loaded` / `installed-not-loaded` / `declared-missing`
   外还有 **`runtime-provided`** —— 官方 `@deepseek-ai/` 包随 DSH 运行时安装，不会出现在 profile 的
   `node_modules` 下，这是**正常状态**。按三种写不会崩溃，但会让一台健康机器冒出一排「状态未知」，
   而假警报比没有信息更糟。
2. **`profile` / `profilePath` 在服务端是 `string | null`，且判定不出 profile 时会显式发 `null`。**
   若把它们声明成非空的 `String = ""`，JSON 里的 `null` **不会退回默认值，而是让整个响应解码失败**
   —— 这比 [AgentPreset.trust] 那次的「缺键」更隐蔽，因为缺键会走默认值、显式 null 不会。

两条都已按**服务端实测 payload** 兜住，并补了专门的解码回归测试
（`DshClientTest.pluginInventoryDecodesRuntimeProvidedStateAndNullProfile` 与
`...AcceptsExplicitNullProfileAndUnavailableReason`），断言四种状态、显式 null、
以及「只有 name 的条目」都能解出。

> 教训沿用上一节：**契约要用真实响应核对，不能只对着文档写**。这次的来源是「任务书比实现落后了一版」
> —— 服务端加了第四种状态却没有同步给客户端。下游按旧契约写出的不是崩溃，而是更容易被忽略的假警报。

### 测了什么

| 测试类 | 用例 | 结果 |
|---|---|---|
| `DshClientTest`（新增 2 条解码回归） | 14 | 全绿 |
| 其余（含上一节的连接层测试） | 76 | 全绿 |
| **合计** | **90** | **全绿** |

---

## 2026-10-08 · 连接层重构：从「单地址」到「一台电脑多个地址」

### 基于什么

- 上游 [Hakunm/dsh-android-app](https://github.com/Hakunm/dsh-android-app) `v1.0.0`（commit `b0bf711`）。
- 许可证 **AGPL-3.0**。上游版权声明、`NOTICE`、`THIRD_PARTY_LICENSES` 全部原样保留。
- 起点已包含上游修复的 DSH 0.2.x 枚举契约问题（见下方条目）。

### 为什么

来自真实使用反馈的四条问题，追查后发现**根因是同一个**：旧模型把「一台电脑」和「一个地址」
焊成了同一个东西。

```kotlin
// 改动前
data class StoredConnection(val endpoint: String, val token: String)
//                                    ^^^^^^^^ 单个字符串，装不下第二个地址
class DshClient(endpoint: String, private val token: String? = null)
```

| 反馈原文 | 追查到的根因 |
|---|---|
| 「只能添加一条路径」 | `endpoint` 只有一个位置 |
| 「我出去了，配对码谁给我？」 | 配对码必须在电脑上生成，而人已经不在电脑旁 |
| 「我出去有可能是局域网，也有可能虚拟网，或者 Tailscale/ZeroTier」 | 同上：只有一个地址位 |
| 「会话中的文件无法查看」 | 会话事件里的绝对路径没有与授权根做匹配（**本项尚未实现**） |

第 2 条推翻了上游的一个隐含前提：上游流程默认「人和电脑在一起」，而真实场景相反 ——
**人已出门，电脑在家，屏幕看不到**。此时「去电脑上生成配对码」不是难用，是做不到。

### 干了什么

**新增连接层模型**（`data/ConnectionModels.kt`）：

```
Host          一台电脑：id / 名字 / 地址列表 / 首选地址 / 凭据 / 时间戳
 ├─ Endpoint     一个地址：id / 标签 / baseUrl / 类型 / 启用 / 探活延迟 / 最后错误
 └─ Credential   凭据：挂在电脑上而非地址上
```

- `EndpointKind` 按地址推断类型；`100.64.0.0/10` 归为虚拟网（Tailscale 与 BeyondTunnel 都用这个 CGNAT 网段）。
- `EndpointSelection.rank` 定死选路规则：延迟优先，但**局域网近似即优先**
  （局域网地址延迟不超过最快地址的 1.5 倍则排前）—— 同网段直连更稳、不烧虚拟网流量。
- **不需要服务端任何修改**：依据是 DSH 的 `devices` 表结构
  `id, name, token_hash, scopes_json, created_at, last_seen_at, revoked_at` **没有地址列**，
  设备令牌天然是主机级的，同一令牌在任何地址上都有效。

**新增多主机持久化**（`data/HostStore.kt` + `data/AndroidConnectionStorage.kt`）：

- 整个 `List<Host>` 序列化后**整体加密**（Android Keystore AES-GCM，`IV || 密文`单串）再落盘，明文绝不落盘。
- 解密/解析失败返回空列表而**不抛异常**（否则一条坏数据把用户锁进启动崩溃循环），原值保留 + 另存副本。
- 旧数据迁移：用**旧的 Keystore 别名**解密旧令牌（旧格式 IV 与密文分开存，需手工重组）转成新 `Host`，幂等。

**新增并发探活选路**（`data/ConnectionCoordinator.kt`）：

- 并发探测全部地址，每个地址**独立计时、独立超时**（默认 1500ms），总耗时接近单个超时而非累加。
- 全部失败抛 `DshConnectionException(probes)`，携带逐地址失败原因供诊断面板展示。
- **必须记录的坑**：`DshClient.health()` 是同步阻塞的 OkHttp 调用，**超时无法靠
  `withTimeoutOrNull` 实现**（超时到点后仍要等这次调用返回，最坏等到 OkHttp 自带的 45 秒读超时）——
  看着有超时，实际没用。现实现把调用交给自有 scope、只对 `Deferred.await()` 设 deadline，
  于是 timeout 由协调层自己拥有，不依赖外部注入的 HTTP 超时。
- **`close()` 的完整语义**：拒绝新的探活、并立即取消在途探活的**等待层**
  （注意：只 `probeScope.cancel()` 不够，详见下方「一处值得记录的错误判断」）。
  仍做不到的是让已经发出的阻塞 HTTP 调用提前结束、释放 IO 线程 —— 根治需要 OkHttp `callTimeout`。

**新增配置导入**（`data/ConnectionShare.kt` + `tools/emit-config.mjs`）：

- 线格式 `DSH1:` + UTF-8 JSON 的 Base64 **URL-safe 无填充**；窄格式只写
  `displayName` / `endpoints[]{label,baseUrl}` / `token` / `deviceName` / `scopes`，
  不写本机状态字段（每个地址省约 40 字符）。
- 解码容错覆盖：内部空白换行、零宽字符、BOM、带/不带前缀、大小写前缀、
  URL-safe 与标准两种字母表、缺失 `=` 填充、结尾粘上的标点。
- **一个反直觉的失败模式**：早期实现只认行首 `DSH1:`，而真实粘贴常带标签
  （`Pixel 9 的配置：DSH1:xxx`）。标签里的 Latin 字母与数字**本身就在 Base64 字母表内**，
  所以「过滤非字母表字符」这条兜底完全无效，会被当正文吞进去冲掉整段。
  正解是用 `DSH1:` 哨兵在整段里定位。
- encode 对非法配置抛异常（生成端在电脑上，能立刻改）；decode 对任何非法输入返回 null 绝不抛
  （此刻用户人在外面，崩溃比明确报错更糟）。
- **安全边界**：文本不加密，它本身就是凭据载体（内含设备令牌），等价于一把钥匙。
  安全性由「发给谁」决定，界面与 KDoc 都明确提示只发给自己。

**界面**：`ui/ConnectScreen.kt` 重写（新增「已保存的电脑」列表含每地址类型与探活结果、
「从电脑导入配置」主路径、地址诊断面板；手动配对降为可折叠次要入口并标注前提）；
`ui/SettingsScreen.kt` 显示当前电脑名与全部地址并标出**此刻实际使用**的那个；
`ui/App.kt` 增加 `CONFIG_INVALID` 本地化；字符串资源中英各新增 27 条。

**行为语义**：`disconnect()` **不再删除凭据**（上游把断开与删令牌绑成一个动作，
导致网络抖动或误触就要重新配对，而重新配对又要求人在电脑旁）；
启动恢复失败**不再清除已保存的主机**（上游 `store.clear()` 把「此刻网络不通」误判成「凭据失效」）；
启动时自动连接**最近活动过**的那台电脑。

### 测了什么

| 测试类 | 用例数 | 结果 |
|---|---|---|
| `HostStoreTest` | 26 | 全绿 |
| `ConnectionShareTest` | 23 | 全绿 |
| `ConnectionCoordinatorTest` | 14 | 全绿 |
| `DshClientTest`（上游原有） | 12 | 全绿 |
| `ChatPresentationTest`（上游原有） | 3 | 全绿 |

覆盖：密文落盘（断言磁盘不含明文令牌）、两类损坏数据容错、迁移幂等性、
并发探活全部成功/部分失败/全部失败、局域网近似即优先、单地址超时不拖累整批、取消异常透传、
粘贴容错 23 种敌意输入、编码长度预算回归护栏。

**一处值得记录的错误判断（我错了，同事用实测纠正）**：
`ConnectionCoordinatorTest.closeCancelsInFlightProbeAndRejectsNewOnes` 最初失败（5.0s，断言「在途探活应随
close() 被取消，实际 null」）。我当时的判断是「这在协程取消模型下物理上做不到」，并要求放宽断言。
**这个判断是错的。** 打不断的只是那次 `Call.execute()`，而**调用方的等待是一个普通 suspend 点，可取消**。
正确修法是监听 close 信号后**显式 cancel 掉正在等待的那一层**。修好后该用例 5.021s → 0.016s，断言原样保留。

过程中暴露的两个协程语义坑（会反复被踩，记录在此）：

1. **只 `probeScope.cancel()` 不够**：在途 `probeAll` 不会提前返回（Job 停在 Cancelling），
   而且最后会产出一条**假失败探活**（`error = timeout after Nms`）喂给诊断与落盘 ——
   地址其实没失败，是协调器被关了。这个副作用比「慢」更严重。
2. **子协程抛出的 `CancellationException` 不会上传父协程**：它会被 `JobSupport.childCancelled`
   当作「该子协程正常取消」处理；而父协程正挂在 `call.await()` 上，没有人叫醒它。
   所以「监听关闭信号后就 throw CE」这种写法仍然会等满超时 —— 必须显式 cancel 等待层。

保留下来、如实写进 KDoc 的限制：已发出的阻塞 HTTP 调用仍会占用 IO 线程直到 OkHttp 自身读超时；
根治需要给 `DshClient` 加 OkHttp `callTimeout`（见「已知未做」）。

### 构建链路修复（这三个问题会让任何人在本机构建失败）

1. **上游 `gradlew` 在 git 中被记录为 `100644`（没有执行位）** —— `git ls-files -s gradlew`
   可复现。clone 之后直接运行 `./gradlew` 会得到 `Permission denied`；而它又被管道里的
   `| tail` 掩盖成 `exit 0`，非常有欺骗性（看起来「构建成功」，其实 Gradle 从未启动）。
   本仓库修正为 `100755`。
2. **Gradle wrapper 静默挂起**：`distributionUrl` 指向不可达的 `services.gradle.org`；
   本地缓存的 Gradle 属于另一个 URL（腾讯云镜像），哈希不匹配 → wrapper 认为未下载 →
   尝试下载 → 网络不通 → **不报错、不退出、十几分钟无任何输出**。
   实测该目录下有两个哈希目录，其中一个只有 20MB 的 `.part` 残骸。
3. 依赖镜像脚本与 `RepositoriesMode.FAIL_ON_PROJECT_REPOS` 冲突 → 构建直接失败。
   改为项目自带的 `scripts/init-mirrors.gradle`（只重写 settings 级仓库）。

### 已知未做

- **会话中文件查看**（第 4 条）未实现。方向：会话事件的绝对路径
  （`events[N].event.data.meta.diffs[0].path` 与 `meta.path`）与授权根做最长前缀匹配，
  转相对路径后经 `GET /roots/:id/content?path=...` 读取。
- **查看已安装插件**（第 2 条）需服务端加接口：`dsh-workspace` 目前只有 10 个接口
  （`healthz` / `devices/self` / `roots` / `trash` / `chat/sessions` / `chat/workspaces` /
  `chat/agent-presets` / `settings/models` / `settings/providers` / `pairings/exchange`），
  **没有任何插件相关接口**，因此无法纯靠客户端解决。
- **`DshClient` 的 OkHttp `callTimeout`**：加上后阻塞调用才真正可被超时中断，
  `ConnectionCoordinator` 也能退回更简单的实现。
- 文件区重构（按根分组 / 最近 / 收藏 / 搜索）。

---

## 2026-10-08 · 兼容 DSH 0.2.x 的字符串枚举契约（下游分支）

- **背景**：上游 `Hakunm/dsh-android-app` v1.0.0 使用 kotlinx.serialization 严格解码。
  `AgentPreset.trust` 为无默认值的必填字段；而 DSH 0.2.x 的 `AgentPreset` / `AgentPresetRow`
  已移除 system/user 二分，**服务端不再返回该字段**。客户端因此对整个列表报错：
  `Field 'trust' is required for type with serial name '...AgentPreset', but it was missing at path: $.items[0]`，
  表现为 Agent 预设选择器完全打不开。
- **做法**：给 `AgentPreset.trust` 加默认值 `"system"`，并同时给 `isDefault` / `available`
  补上安全默认值。这样**服务端缺少任一可选字段时，一个字段的缺失不会再拖垮整个列表**。
  同批在服务端（`dsh-workspace` 的兼容层）也补了该字段，两端互为保险。
- **为什么改客户端而不是只改服务端**：严格解码在遇到未知/缺失字段时是「全有或全无」的。
  客户端不应假定某个具体内核版本一定返回某个可选字段；给可选字段默认值是更稳的防线。
- **构建环境说明**：本次构建在无外网代理的环境下完成，构建时临时使用了国内镜像
  （`settings.gradle.kts` 指向阿里云与 `dl.google.com`，Gradle wrapper 指向腾讯镜像）。
  **这些改动未包含在本分支中**——本分支相对上游只保留与修复直接相关的文件，
  以便差异最小、便于审阅。
- **验证**：
  - `./gradlew assembleRelease` 构建成功，产物 `app-release.apk` 2,561,512 字节，
    与上游 v1.0.0 产物同尺寸（R8 优化后恰好一致）。
  - 逐 dex 比对确认改动**确实编译入包**：`classes.dex` 由 3,709,572 字节变为 3,709,696 字节
    （+124，即新增默认值产生的字节码），`AgentPreset` 符号在包内存在。
  - APK 已用自签密钥签名并验签通过（`CN=DSH Pocket Client`，
    SHA-256 `4e7fa1db395c4a2436e7a96fcdf6c606e20a14bad55dfcd60c80892f4f92deb3`）。
  - 装机实测：连接 DSH 0.2.x + `dsh-workspace` 兼容层后，Agent 预设列表可正常打开，
    会话模型、历史记录、流式输出、命令菜单均正常。
- 产物 `DeepSeek-Harness-compat02-release.apk`（SHA-256 `4df87f47…`）随分支提供；
  签名密钥 `dsh-release.jks` **不进入仓库**。

## 2026-08-15 · v1.0.0

- 完成 `DOC-002` 与 `REL-004`：README 扩展为 6 张用户提供的真实手机截图，新增文件浏览与新建工作区界面，删除中英文截图来源说明；GitHub topics 加入 `dsh-plugin`，公开分支和标签继续保持单一根提交。
- 完成 `BRAND-002` 与 `LICENSE-001`：App 用户可见简称统一为 `DSH`，项目自身许可证改为 `AGPL-3.0-only`；上游鲸鱼图标与 Markdown 渲染器继续按各自许可证保留告知。
- 完成 `DOC-001`：README 改用用户在真实手机上提供的导航、聊天、文本编辑和模型供应商截图；连接地址已打码，模拟器截图不进入正式仓库。
- Oracle 演示服务器已清除发布前的会话历史和截图过程产生的临时会话，只保留一个本次发布创建的“你好”演示会话；DSH WebUI 与远程 listener 均恢复正常。
- 完成 `REL-003`：v1.0.0 正式签名 APK、README、截图和许可证统一后，以单一根提交重写公开分支与标签，并替换 Release 产物。
- 完成 `WORKSPACE-001`：会话列表页新增 DSH 工作区管理入口，可重命名和移除登记，删除确认明确保留服务器目录、文件和会话日志。
- 完成 `SESSION-001`：每个会话子项增加重命名、分叉和归档；分叉后自动进入子会话，归档后刷新默认列表并保留日志。
- 正式项目名调整为 `dsh-android-app`，版本升级为 `1.0.0` / versionCode `10000`，设置页展示运行版本；新增 GitHub Android CI 和中英双语用户 README。
- `docsCheck testDebugUnitTest lintDebug assembleRelease` 通过；正式 APK 为 2,561,512 字节，SHA-256 `823344D6CEAFF9FE030F625335191D52ABE97FB6D02AFD2B9555B12680E4A266`，v2 签名和既有独立 4096 位 RSA 证书验证通过。
- 完成 `REL-002`：在保留用户手动删改的基础上使用 Humanizer-zh 重写中文 README，拆出完整 `README.en.md`，把安装、连接和核心操作前置，并加入会话、会话菜单与工作区管理三张真实 App 截图。
- 截图不包含设备令牌、API 密钥或服务器连接地址；包含连接 IP 的抽屉采样未进入仓库。
- 创建 `Hakunm/dsh-android-app` 公开仓库并推送 `main`，设置 `android`、`deepseek-harness`、`jetpack-compose`、`material3`、`vibe-coding` topics。
- 发布 GitHub `v1.0.0` Release，上传正式签名 `DeepSeek-Harness-v1.0.0.apk` 与 `SHA256SUMS.txt`。
- 完成 `CI-001`：Release 签名校验从 Gradle 配置阶段移到 Release 打包任务前。公开 CI 可在不持有正式私钥的情况下验证 Debug；`packageRelease` 与 `bundleRelease` 仍依赖专用签名检查，修复后的 hosted run 已成功。

## 2026-08-15

- 完成 `TODO-001`、`COMMAND-001` 和 `PERMISSION-001`：聊天页展示 DSH TODO 完成进度与任务状态，输入 `/` 时提供当前会话 host 命令补全并走受控 BFF 执行，输入区增加只读/工作区写入/完整访问权限选择器；完整访问必须二次确认。
- 完成 `BRAND-001`：设置页的“关于”分区以中英文展示 `Github@Hakunm`。
- 正式签名 Release 已覆盖安装到 Android 15 尺寸设备，并连接更新后的 Oracle listener 完成权限选择器和 `/` 命令候选视觉验收；APK v2 与既有独立证书保持一致。
- Oracle 服务器已载入审批版插件并重启 DSH；App 下一次收到审批请求即可通过稳定 REST/WS 展示并决定，不再依赖旧插件。旧运行随用户授权的重启终止。

## 2026-08-14

- 完成 `APPROVAL-001`：App 通过插件稳定 BFF 恢复待审批状态，会话列表改为“等待审批”，审批面板接管输入区并显示脱敏原因/命令；提供“拒绝/允许一次”，`danger-full-access` 必须勾选风险确认，不提供永久允许。WebSocket 审批变化会刷新 REST 状态，决定过程使用显式 UI 状态而非通用 busy。
- 记录 `APPROVAL-001`：通过 App 发起的真实 Skill 安装停在 DSH `approval/asked`，证明当前移动端缺少审批交互会将等待授权误呈现为持续运行；服务器与命令均未异常，恢复需在 WebUI 允许一次或拒绝。
- 完成 `STREAM-001`、`AGENT-001` 与 `IME-001`：App 直接呈现 token 级文本/思考增量并在完成后以历史正文收口；会话配置加入仅空白会话可用的 Agent 切换；聊天输入区移除与窗口 `adjustResize` 重复的 IME padding，修复键盘上方整块空白。
- 用户实机确认完全移除聊天 IME inset 会让键盘覆盖输入框，`IME-001` 因此重新打开：父级 `Scaffold` 现在消费已应用的系统 inset，聊天列只应用剩余 IME inset，避免“完全不抬升”和“重复抬升”两个极端；最终状态等待用户实际键盘复验。
- 使用 Android 15 发布版连接真实 Oracle listener 完成流式视觉采样：连续帧中的助手正文从 `1...34` 增长到 `1...120`，最终 REST 历史正常接管且无 429/崩溃；已开始会话的 Agent 面板同步完成锁定状态验收。
- 扩展 `APP-004`：文字源码与 Markdown 预览新增共用的 75%–250% 缩放状态，支持缩小、百分比显示、重置、放大和双指捏合；字体与行高同步缩放，行号沟槽随字号调整，单指滚动、编辑和 ETag 保存路径保持不变。

- 扩展 `APP-005`：新建会话增加“已有工作区/新建工作区”模式；新建模式从授权根浏览工作目录，经插件登记 DSH 工作区后直接创建会话，仍不会把文件根误当作 WebUI 工作区。
- 扩展 `MODEL-001`：现有供应商编辑显示 DSH 有效配置、凭据引用和继承模型；新增完全自定义供应商表单，协议选项由服务器 schema 提供。
- 扩展 `MD-001`：文字文件编辑加入同步行号，Markdown 文件支持源码/渲染预览切换。
- 完成 `UI-004`：聊天 Markdown 正文与列表统一到紧凑正文层级，缩小消息留白、用户气泡、宽屏会话栏和输入正文区，保留关键按钮的触控热区。
- Android 15 最终复验发现新建工作区长表单会把创建按钮挤出屏幕；现已收紧段间距与目录列表高度，使根目录、目录选择、Agent 和底部创建按钮在同一手机视口内完整可操作，并重新完成 lint、Release、签名和覆盖安装。

- 创建独立 Android 工程并锁定应用名、包名、SDK、Compose/Material 3 和双语基线。
- 从 DSH WebUI 上游 favicon 提取黑色鲸鱼路径作为 adaptive/monochrome launcher icon，并保留 MIT 许可。
- 在仓库外生成独立 4096 位 RSA PKCS12 签名，未使用 Android debug 测试签名。
- 建立持久化项目追踪与 DSH WebUI 功能对等矩阵。
- 完成配对/恢复、Android Keystore 令牌保护、scope 感知和 HTTP/HTTPS 客户端；WebSocket 使用封顶 30 秒的指数退避重连。
- 完成自适应会话与文件界面：创建/历史/发送/steer/取消，以及 ETag 编辑、新建、移动、上传下载、替换、软删除和恢复。
- 添加 OkHttp 合约单测并通过 lint、R8 Release 构建、APK 包名与独立证书签名核验；真机端到端仍待执行。
- 对本机真实插件 listener 完成临时设备/根回归，验证一次性配对、scope、ETag 文件更新、移动、软删除/恢复和会话授权过滤，并在结束后撤销测试授权。
- 文件上传下载改为 Okio 流式传输，补充目录分页、操作菜单替换文件的 ETag 获取、纯 CR 换行保留和 NUL 二进制识别；Compose 默认中文首屏测试 APK 编译通过。
- 在 Android 15 上运行发布版并通过真实插件完成健康检查、一次性配对、设备/scope/根加载、应用重启恢复和主导航验收；临时设备与根已撤销清理。
- 真机配对发现 WebSocket 请求提前改写 `ws://` 导致 OkHttp 拒绝，现改为传入 HTTP(S) 握手 URL 让 OkHttp 执行 Upgrade，并新增第 6 项回归单测。
- Compose 仪器测试首次因真实配对状态残留而无法进入连接首屏；清理测试前置状态后重跑通过，测试文档明确要求干净安装。
- 完成 `APP-005`：聊天历史增加强类型事件 presentation 层，隐藏 `step/end`、`turn/end`、token 数组等内部原始 JSON，只保留正文、思考、上下文注入与工具摘要；新建会话改为读取并选择 DSH 工作区和 Agent Preset，不再把文件授权根当作工作区，也无需手填路径或模式 ID。
- Android 15 发布版完成 `APP-005` 视觉验收：空工作区、临时非空 DSH 工作区、默认 Agent 和四种模式列表均按真实 listener 数据渲染；测试工作区与设备随后清理，重新生成并核验正式签名 APK。
- 完成 `UI-001`：安装并采用 UI/UX Pro Max 的 Compose 设计建议，重构浅色/深色主题、应用栏、自适应导航、连接表单、会话列表与消息、文件浏览与编辑操作、设置分区及弹窗；所有业务 ViewModel、网络协议、scope 判断和文件回调保持不变。Android 15 的 1080×1920 实际截图复核通过。
- 完成 `UI-002`：分析用户提供的参考 APKS 及实际运行层级，将内容优先留白、移动端抽屉、宽屏固定侧栏、行式列表、底部面板与克制过渡应用到 DSH 自有品牌界面；没有复制第三方商标、资源或字体，并持久化设计系统。
- 完成 `MODEL-001`：设置页新增供应商配置、凭据状态、只写密钥和模型发现；聊天页新增真实可路由模型与思考强度底部面板，均由 `settings.read/settings.write` 和 session/root 授权保护。
- 完成 `MD-001`：集成 `multiplatform-markdown-renderer-m3` 0.41.0，助手正文及展开思考支持标题、列表、引用、代码和表格；补充 NOTICE 与 Apache-2.0 全文。
- 完成 `RATE-001`：聊天增量事件改为合并刷新，会话列表只在生命周期或本地主动动作后刷新，429 静默退避；所有错误从阻塞对话框改为 Snackbar。真实发送与 24 秒观察无 429 或崩溃。
- ARM64 Oracle 上的插件 tarball 已更新并重载；真实 API、Android 15 模型/供应商/Markdown/消息发送、10 项单测、lint、R8 Release、正式签名和最终覆盖安装均通过。
- 完成 `UI-003`：将全局标题、正文、会话行、消息留白、用户气泡和聊天输入容器收紧；为 Markdown 增加独立紧凑 H1-H6 映射，避免聊天正文标题使用库的超大默认 display 样式。Android 15 实际会话截图确认内容密度提升，44/48dp 关键触控区域不变。
