# dsh-companion

<p align="center">
  <strong>把你自己的 DeepSeek Harness 带到 Android 手机上。</strong>
</p>

<p align="center">
  中文 · <a href="./README.en.md">English</a>
</p>

<p align="center">
  <img alt="Upstream" src="https://img.shields.io/badge/upstream-Hakunm%2Fdsh--android--app-555">
  <img alt="Android" src="https://img.shields.io/badge/Android-8.0%2B-3ddc84">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/UI-Jetpack_Compose_%2B_Material_3-6750a4">
  <img alt="License" src="https://img.shields.io/badge/license-AGPL--3.0-2da44e">
</p>

## 关于本仓库

本仓库是 [Hakunm/dsh-android-app](https://github.com/Hakunm/dsh-android-app) 的**衍生版本（fork）**，
在上游 `v1.0.0` 的基础上继续开发。它保留上游的完整提交历史，因此归属关系可逐条追溯。

- **基于什么**：上游 [Hakunm/dsh-android-app](https://github.com/Hakunm/dsh-android-app)（Android 原生客户端，Jetpack Compose + Material 3）。
- **为什么有它**：上游的连接模型只支持「一台电脑 = 一个地址」，在真实移动场景下不够用。具体见下一节。
- **许可证**：上游以 **AGPL-3.0** 发布，本仓库沿用同一许可证，见 [LICENSE](./LICENSE)。
  上游的版权声明、[NOTICE](./NOTICE) 与 [THIRD_PARTY_LICENSES](./THIRD_PARTY_LICENSES) 全部保留未改动。
- **我们改了什么、为什么、测了什么**：见 [开发日志](./docs/project/CHANGELOG-DEV.md)。

## 这个版本解决什么问题

手机端日常使用暴露出四个问题，它们看起来互不相干，实际上指向**同一个根因**：
旧实现把「一台电脑」和「一个地址」当成了同一个东西。它的数据模型只有：

```kotlin
data class StoredConnection(val endpoint: String, val token: String)
class DshClient(endpoint: String, token: String?)
```

`endpoint` 是**单个字符串**。于是：

| 实际遇到的问题 | 真正的根因 |
|---|---|
| 只能添加一条路径 | 模型里只有一个 `endpoint: String`，装不下第二个地址 |
| 出门在外没法配对 | 配对码必须在电脑上生成，而那时人已经不在电脑旁 |
| 局域网 / 虚拟网 / 公网不能同时保存 | 同上：只有一个地址位 |
| 会话里提到的文件看不了 | 会话事件中的绝对路径没有与授权根做匹配 |

### 关于「出门在外」这个场景

这一条值得单独说，因为它推翻了上游的一个前提。上游的连接方式是**一次性配对码**：
在电脑的 WebUI 上生成，拿到手机上输入。这个流程默认「人和电脑在一起」。

但真实场景恰恰相反：**人已经出门了，电脑留在家里，屏幕根本看不到**。此时
「去电脑上生成一个配对码」不是难用，而是**做不到**。扫码同理 —— 对着看不到的屏幕扫不了。

所以本版本把「配置导入」作为主路径：

```
电脑上生成一行文本 → 发给自己（微信 / 邮件 / 私密笔记）→ 到了外面粘贴进 App
```

全程不需要电脑在旁边，也不需要摄像头。配对码被保留为**次要入口**（你正坐在电脑前时它更快），
并在界面上明确标注了这个前提条件。

## 本仓库相对上游的改动

### 1. 一台电脑，多个地址

连接层重新建模（`app/src/main/java/io/github/hakunm/deepseekharness/data/ConnectionModels.kt`）：

```
Host        一台电脑：名字、凭据、若干地址、上次成功用的地址
 ├─ Endpoint   一个可达地址：标签、baseUrl、类型（局域网/虚拟网/公网）、探活历史
 └─ Credential 凭据：挂在电脑上，而不是挂在地址上
```

**关键点：这个改动不需要服务端做任何修改。** DSH 的 `devices` 表结构是
`id, name, token_hash, scopes_json, created_at, last_seen_at, revoked_at` ——
**根本没有地址列**。设备令牌天然是主机级的，同一个令牌在任何地址上都有效。
也就是说「多地址」一直是客户端本来就能做到、只是过去没做的事。

启动时的行为：并发探测这台电脑的**全部**地址（每个地址独立超时，实测 1.5 秒），
按「延迟优先、局域网近似即优先」选路，失败自动回退到下一个可达地址。

### 2. 配置导入取代扫码

`ConnectionShare.kt` 定义了线格式 `DSH1:<Base64URL(JSON)>`，解码端对真实粘贴环境做了容错：
微信插入的换行与零宽字符、前后带标签文字（`Pixel 9 的配置：DSH1:xxx`）、缺失的 `=` 填充、
标准与 URL-safe 两种 Base64 字母表、结尾被粘上的标点 —— 全部能正确还原。

生成端是 `tools/emit-config.mjs`。

### 3. 地址诊断

全部地址都连不上时，不再只说一句「连接失败」，而是逐条列出每个地址的结果：

```
局域网    192.168.1.126:3090   Unreachable — timeout after 1500ms
虚拟网    100.64.250.1:3090    Reachable · 38 ms
```

过去用户只能靠猜「我该填哪个地址」，现在他看得到。

### 4. 断开不再清除凭据

上游把「断开连接」和「删除令牌」绑成同一个按钮。于是网络抖一下、手点错一次，
用户就得重新配对 —— 而重新配对又要求他人在电脑旁。
现在断开只断连（`disconnect`），删除电脑是独立动作（`removeHost`）。

### 5. 旧数据自动迁移

从旧版升级时，已保存的地址与设备令牌会自动迁移成新的 `Host` 结构，无需重新配对。
迁移是幂等的，且**旧格式的令牌会用旧的 Keystore 别名解密**后搬过来。

### 6. 插件清单页

手机上多了一个「插件」页签，能看到这台机器装了哪些插件、**实际跑的是哪个版本**、以及有没有异常状态。

这一条**必须动服务端**：`dsh-workspace` 的 `/api/v1` 原有 10 个端点
（`healthz` / `pairings/exchange` / `devices/self` / `roots` / `trash` / `chat/sessions` /
`chat/workspaces` / `chat/agent-presets` / `settings/models` / `settings/providers`），
**没有任何一个与插件相关**，所以先在插件侧加了 `GET /api/v1/settings/plugins`。

它区分四种状态，其中两种只有对比两个数据源才能得出（`package.json` 的 `dependencies` =
装了哪些包，`dsh.profile.bundles` = 实际加载了哪些包）：

| 状态 | 含义 |
|---|---|
| `loaded` | 声明加载且已落盘 |
| `runtime-provided` | 官方包随 DSH 运行时自带（不在 profile 的 `node_modules` 里）—— **正常状态** |
| `installed-not-loaded` | 装了但没启用 |
| `declared-missing` | 非官方包声明加载却缺失，通常意味着启动会出问题 |

「装了哪些」与「跑着哪些」的差别正是它的价值所在 —— 例如本机实测发现
`dsh-hyperframes` 与 `dsh-remotion` 装了但未启用。

> **DSH 0.2.x 兼容性**：本分支同样包含上游修复的枚举契约问题 —— 0.2.x 的 `AgentPreset`
> 不再返回 `trust` 字段，而客户端严格解码要求该字段必填，导致 Agent 预设选择器报
> `Field 'trust' is required ... missing at path: $.items[0]` 并整体打不开。
> 修复方式是给可选字段补默认值，使**单个字段缺失不再拖垮整个列表**。

## 构建

```bash
scripts/build.sh                      # 默认 assembleDebug
scripts/build.sh assembleRelease      # 发布包
scripts/build.sh :app:testDebugUnitTest   # 单元测试
```

`scripts/build.sh` 的存在是有原因的，不是多余的包装：

1. **绕开 Gradle wrapper 的静默挂起。** `gradle-wrapper.properties` 里的
   `distributionUrl` 指向 `services.gradle.org`，该地址在国内网络下不可达，而 wrapper
   下载失败时**不报错退出，而是挂起**（实测：CPU 0.3%、零网络连接、无 daemon 日志，十几分钟无输出）。
   脚本直接调用本地已缓存的 Gradle 二进制。
2. **依赖镜像。** 本机无法访问 `maven.google.com`，需要经阿里云镜像重定向。
   注意用的是**本项目自带**的 `scripts/init-mirrors.gradle`：工具链里那份会往 project 级
   仓库追加，与本项目 `settings.gradle.kts` 的 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`
   冲突并直接构建失败。
3. **串行化。** Gradle 对同一项目目录是互斥的，脚本用 `flock` 保证并发调用不会互相抢锁。

## 下载与安装

从本仓库的 GitHub Releases 下载：

```text
DeepSeek-Harness-companion.apk
```

安装要求：

- Android 8.0（API 26）或更高版本
- 已运行的 DeepSeek Harness WebUI
- DSH WebUI 已安装 `dsh-workspace` v1.0.0 或兼容版本
- 插件中至少添加了一个授权根，并已启用远程访问
- 手机可以访问插件配置的 IP、域名和端口

正式 APK 使用与上游**相同**的发布证书签名，`applicationId` 也保持一致
（`io.github.hakunm.deepseekharness`）。因此可以从上游版本**直接覆盖安装**，
已保存的连接会在首次启动时自动迁移成新的多地址结构，无需重新配对。

## 界面预览

<p align="center">
  <img src="./assets/screenshots/app-navigation.jpg" width="47%" alt="App 导航侧栏">
  <img src="./assets/screenshots/chat-demo.jpg" width="47%" alt="聊天与实时回复">
</p>
<p align="center">
  <img src="./assets/screenshots/file-browser.jpg" width="47%" alt="授权根目录文件浏览">
  <img src="./assets/screenshots/file-editor.jpg" width="47%" alt="带行号和缩放的文本编辑器">
</p>
<p align="center">
  <img src="./assets/screenshots/workspace-create.jpg" width="47%" alt="创建工作区并开始会话">
  <img src="./assets/screenshots/model-providers.jpg" width="47%" alt="模型供应商设置">
</p>

## 下载与安装

从 [GitHub Releases](https://github.com/Hakunm/dsh-android-app/releases/latest) 下载：

```text
DeepSeek-Harness-v1.0.0.apk
```

安装要求：

- Android 8.0（API 26）或更高版本
- 已运行的 DeepSeek Harness WebUI
- DSH WebUI 已安装 `dsh-workspace` v1.0.0 或兼容版本
- 插件中至少添加了一个授权根，并已启用远程访问
- 手机可以访问插件配置的 IP、域名和端口

正式 APK 使用项目独立的发布证书签名，不使用 Android 测试签名。后续版本需要使用同一证书，才能直接覆盖更新。

## 连接你的 DSH

### 服务器端

1. 在 DSH WebUI 打开 `dsh-workspace` 的“工作区设置”。
2. 添加至少一个授权根目录。
3. 在“远程访问”页填写绑定 IP 和端口，并保存监听设置。
4. 点击“启用并创建配对”，取得十分钟内有效的一次性配对码。

### 手机端

1. 输入服务器地址，例如 `http://192.168.1.20:3090` 或 `https://dsh.example.com`。
2. 输入配对码和设备名称。
3. 点击“配对并连接”。

> **地址格式：千万不要带 `/dsh-workspace-api` 前缀。**
> 这一点很容易搞错，而且错了必然连不上。实测：
>
> | 请求 | 结果 |
> |---|---|
> | `http://192.168.1.126:3090/api/v1/healthz` | 200 ✅ |
> | `http://192.168.1.126:3090/dsh-workspace-api/api/v1/healthz` | 401 ❌ |
> | `http://127.0.0.1:3080/dsh-workspace-api/api/v1/healthz` | 200（但只绑 loopback，手机到不了） |
>
> 原因：`3090` 是**插件的远程监听端口**，路由直接挂在根上、**不带前缀**；而
> `/dsh-workspace-api` 是 DSH Web 在 `3080` 上的挂载面，只绑 `127.0.0.1`。
> 客户端 `DshClient.normalizeEndpoint()` 会自动补 `/api/v1`，所以如果你填了
> `http://ip:3090/dsh-workspace-api`，实际会请求 `/dsh-workspace-api/api/v1/...`，
> 而该路由在 3090 上并不存在。

设备令牌由 Android Keystore 支持的加密存储保存。App 重启后会恢复连接。

> **令牌不会自动过期。** 服务端的 `devices` 表没有过期列，所以一段配置文本一旦发出就长期有效，
> 只能通过吊销设备使其失效。因此：配置文本只发给自己，一旦怀疑泄露，请立刻在电脑上吊销对应设备
> （`curl -X DELETE http://127.0.0.1:3090/manage/devices/<id>`），而不是指望它“看不懂”。

## 聊天不只是收发消息

- 查看设备有权访问的 DSH 会话和实时运行状态。
- 以流式增量显示助手正文和思考，结束后与服务器历史校准。
- 正确排版 Markdown 标题、列表、引用、代码块和表格。
- 发送普通消息，在运行中追加引导，或取消当前任务。
- 查看 DSH TODO 模块、完成进度和正在处理的事项。
- 输入 `/` 浏览并执行服务器提供的 host 斜杠命令。
- 在输入区切换“只读”“工作区写入”“完整访问”。完整访问会再次提示风险。
- 查看待审批工具调用的脱敏信息，选择“允许一次”或“拒绝”。
- 切换模型和思考强度。空白会话还可以在首条消息前选择 Agent。

## 工作区与会话

新建会话时，可以选择 DSH WebUI 已登记的工作区，也可以从设备获准访问的根目录中挑选一个现有目录，将它登记为新的 DSH 工作区。

工作区管理支持：

- 查看设备可访问的 DSH 工作区。
- 重命名工作区。
- 移除工作区登记。

移除登记不会删除服务器目录、文件或会话日志。

每个会话的菜单支持：

- **重命名**：修改 DSH WebUI 中显示的标题。
- **分叉**：继承已有聊天历史、工作目录和模型配置，进入新的子会话。
- **归档**：从默认列表隐藏会话，同时保留服务器日志。

App 只显示工作目录位于设备授权根内的工作区和会话，接口不会向手机暴露服务器绝对路径。

## 文件管理与编辑

- 懒加载浏览授权根和子目录。
- 新建文件或文件夹，上传、下载和替换文件。
- 重命名、移动，以及将内容移入插件回收站。
- 查看回收站并恢复项目，发生路径冲突时不会覆盖现有文件。
- 使用带同步行号的 UTF-8 文本编辑器。
- 在 Markdown 源码与渲染预览之间切换。
- 使用按钮或双指手势将源码和预览缩放到 `75%-250%`。
- 使用 ETag 检测外部修改，拒绝静默覆盖较新的文件。
- 对二进制文件只提供元数据、下载和替换。

App 不能添加服务器授权根，也不能永久清空插件回收站。这些高权限操作只在服务器本机 DSH WebUI 中提供。

## 模型供应商

拥有 `settings.read` 权限时，App 可以查看 DSH 的有效供应商配置、模型列表以及凭据是否已配置。服务器不会回传 API 密钥正文。

拥有 `settings.write` 权限时，还可以：

- 编辑供应商名称、Base URL、协议、模型和思考设置。
- 写入或清除供应商凭据。
- 从兼容服务发现模型。
- 创建 DSH 支持的完全自定义供应商和模型路由。

## 权限如何生效

App 会根据服务器授予的 scope 自动显示或禁用功能：

| Scope | App 中允许的操作 |
| --- | --- |
| `chat.read` | 查看工作区、会话、消息、TODO、命令和审批 |
| `chat.write` | 管理工作区与会话、发消息、切换配置、执行命令和处理审批 |
| `files.read` | 浏览、预览、下载和查看回收站 |
| `files.write` | 新建、编辑、上传、移动和重命名 |
| `files.delete` | 将文件或目录移入插件回收站 |
| `settings.read` | 查看供应商和模型配置 |
| `settings.write` | 修改或创建供应商，写入凭据 |

每台设备还需要单独的根目录授权。服务器管理员可以在 `dsh-workspace` 的“设备”页随时修改权限或撤销设备。

## HTTP 安全提示

App 支持 `http://` 和 `https://`，不会强制 HTTPS。HTTP 适合可信局域网、VPN 或临时测试，但会明文传输设备令牌、聊天内容和文件内容。

跨公网或不可信网络使用时，建议在插件前配置 Caddy/Nginx HTTPS，或通过 Tailscale/WireGuard、其他 VPN 或可信隧道连接。不要把配对码、设备令牌或 APK 签名材料分享给其他人。

## 隐私

- App 不包含广告或第三方分析 SDK。
- 设备令牌保存在 Android Keystore 支持的加密存储中。
- API 密钥只会写入 DSH 服务器凭据库，不会由 App 读取回显。
- 聊天和文件数据只发送到你配置的 DSH 地址。

## 常见问题

**新建会话时没有可选工作区**

切换到“新建工作区”，从授权根中选择目录；也可以先在 DSH WebUI 中登记工作区。

**某些按钮不可用**

当前设备缺少对应 scope。请在插件的“设备”页调整权限。

**无法连接服务器**

确认远程访问已经启用，服务器防火墙和云安全组允许该端口，手机填写的地址也不是服务器自己的 `127.0.0.1`。

**会话长时间没有继续**

查看聊天页是否出现审批面板。需要授权的操作不会在后台自动批准。

**保存文件时提示冲突**

服务器文件已经被其他程序修改。重新载入并合并内容后再保存。

## 从源码构建

需要 JDK 17 和 Android SDK 36：

```sh
./gradlew testDebugUnitTest lintDebug assembleRelease
```

Release 构建必须提供独立签名配置：

```properties
storeFile=/absolute/path/to/release-signing.p12
storePassword=...
keyAlias=...
keyPassword=...
```

通过 `-PdshSigningProperties=/path/to/signing.properties` 指定配置。签名文件和密码不应提交到仓库。

## 项目信息

- 当前版本：`v1.0.0`
- 包名：`io.github.hakunm.deepseekharness`
- 作者：[Github@Hakunm](https://github.com/Hakunm)
- 许可证：[GNU Affero General Public License v3.0](./LICENSE)
- 服务端插件：[dsh-workspace](https://github.com/Hakunm/dsh-workspace)
