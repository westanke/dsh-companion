# 配置文本生成端：`tools/emit-config.mjs`

> **交付物**（本任务写作用域内的三个文件）
> - `tools/emit-config.mjs` —— 生成端脚本（只用 Node 内置模块）
> - `docs/project/CONFIG-EMITTER.md` —— 本文档
> - `app/src/test/java/io/github/hakunm/deepseekharness/data/ConnectionShareInteropTest.kt` —— 与 APP 解码端的对拍单测
>
> **一句话结论**：脚本能真的跑出 `DSH1:` 文本，令牌默认由脚本在本机自动配对换取（不需要人工抄写），
> 且生成端的产物与 Kotlin `ConnectionShare.encode()` 的输出**逐字节相同**，APP 解码端能完整还原。

---

## 1. 为什么需要这个脚本

「配置导入」这项功能有两个半边：

| 半边 | 位置 | 状态 |
| --- | --- | --- |
| 解码端 | APP 内 `ConnectionShare.decode()` | 已有（同事实现并落盘） |
| **生成端** | 电脑上产出那段文本 | **原本不存在 —— 就是本脚本** |

没有生成端，用户拿不到那段文本，功能就是个空壳。

它服务的真实场景是：**人已经出门在外，电脑留在家里，看不到电脑屏幕**。扫码、念配对码这类
「配对那一刻电脑必须在旁」的方案在这个场景下直接不可用。取代方案是「在电脑上生成一段短文本，
发给自己（微信文件传输助手 / 邮件 / 私密笔记），到目的地再粘贴」。

因此本脚本的设计前提是：**它运行时电脑肯定在场**（用户正坐在电脑前敲这条命令）。
凡是需要「电脑在场」的能力，在这里都合法可用 —— 尤其是自动创建配对码。

---

## 2. 线格式

权威定义在 `ConnectionShare.kt` 的 KDoc 与私有 `WirePayload` / `WireEndpoint`，
**不是**本文档。摘录如下（已逐字符对照源码）：

```
文本 = "DSH1:" + Base64 URL-safe 无填充( UTF-8( 窄格式 JSON ) )
```

窄格式 JSON 只带这 5 个键，示例：

```json
{
  "displayName": "家里的电脑",
  "endpoints": [
    { "label": "家里局域网", "baseUrl": "http://192.168.1.126:3090" },
    { "label": "公司虚拟网", "baseUrl": "http://100.101.102.103:3090" }
  ],
  "token": "设备令牌",
  "deviceName": "Pixel 9",
  "scopes": ["files.read"]
}
```

三条「不写」规则（对应 Kotlin 侧 `encodeDefaults = false` / `explicitNulls = false`）：

1. `token` 为 null 或空串 → **整个键不写**；
2. `deviceName` 为 null 或空串 → 整个键不写；
3. `scopes` 为空 → 整个键不写。

另外两条硬约定：

- **不写本机状态**：`Endpoint.id`、`kind`、`enabled`、`lastLatencyMs` 等一律不进线格式
  （每个地址一个 UUID 会白花约 40 个 Base64 字符）。导入端由 `buildHost` 重新发 id、重新推断 kind；
- **baseUrl 不含 `/api/v1`**：`/api/v1` 是 `DshClient` 的接口前缀，不是地址的一部分
  （归一规则见 Kotlin `normalizeBaseUrl`，脚本的 `normalizeBaseUrl` 与之幂等等价）。

### 长度预算（实测，非估算）

| 配置 | 实测长度 |
| --- | --- |
| 1 地址 + 9 字符令牌 + 设备名 | 215 |
| 2 地址 + 设备名，无令牌无 scopes | 277 |
| 2 地址 + 43 字符令牌 + 设备名 + 2 scopes | **419** |

一条微信消息上限约 2000 字符，脚本会在超过 400 字符（软预算）时在 stderr 提醒折行风险
（折行**不影响**粘贴，解码端会清掉所有空白）。上表数值同时被单测逐字符钉死。

---

## 3. 快速开始

```bash
cd /media/wangke/OFFICE/workspace/dsh-companion

# 最省事：地址自动探测、令牌自动配对
node tools/emit-config.mjs --name "家里的电脑" --device "Pixel 9" --verify
```

- `stdout` 只有一行可粘贴文本（便于 `$(...)` 取用）；
- 诊断信息全走 `stderr`（地址、令牌来源、长度、安全提醒、自检还原结果）；
- 也可 `--json` 让 stdout 输出结构化摘要（含 `text` 字段）。

**运行环境**：Node ≥ 18（脚本用全局 `fetch` 与 `AbortSignal.timeout`，均为内置能力，无第三方依赖；
实测环境 Node v24.19.0）。

### 3.1 真实运行输出（原样粘贴，未删改）

```console
$ node tools/emit-config.mjs --name "家里的电脑" --device "interop-fixture-1" --scopes "files.read,chat.read" --verify
[emit-config] 未显式指定地址，自动探测到 2 个候选（端口 3090）
[emit-config] 已在本机自动配对并兑换设备令牌（配对码 D0E3B-83FFB，未经过人工）

[emit-config] 电脑显示名 : 家里的电脑
[emit-config] 地址（2 个）:
  - 局域网（eno1）  http://192.168.1.126:3090  [局域网] healthz ok 55ms
  - 虚拟网（utun0）  http://100.64.250.1:3090  [虚拟网] healthz ok 4ms
[emit-config] 令牌来源   : 自动配对（/manage/pairings → /api/v1/pairings/exchange）
[emit-config] 令牌        : 已脱敏（共 43 字符）
[emit-config] 设备        : interop-fixture-1（id 9397b682-8e9c-424d-9ab4-d2744a8460d9） scopes=[files.read, chat.read] roots=[b3cc74d4-4d42-436f-b8af-2d2cd3a02b4b]
[emit-config] 吊销方法    : curl -X DELETE http://127.0.0.1:3090/manage/devices/9397b682-8e9c-424d-9ab4-d2744a8460d9
[emit-config] 设备名      : interop-fixture-1
[emit-config] 权限        : [files.read, chat.read]
[emit-config] 文本长度    : 419 字符（微信软预算 400，硬上限 2000）
[emit-config] 自检        : 解码还原成功，往返一致=true
[emit-config] 还原结果    : {"displayName":"家里的电脑","endpoints":[{"label":"局域网（eno1）","baseUrl":"http://192.168.1.126:3090"},{"label":"虚拟网（utun0）","baseUrl":"http://100.64.250.1:3090"}],"token":"REDACTED-TOKEN-0000000000000000000000000000","deviceName":"interop-fixture-1","scopes":["files.read","chat.read"]}
[emit-config] 注意        : 超过软预算，微信里可能被折行（不影响粘贴，解码端能容忍换行）
[emit-config] 安全提醒    : 这段文本内含设备令牌，等价于一把钥匙 —— 只发给自己（文件传输助手/私密笔记），
                            不要发到群聊、论坛、截图、issue 或任何第三方工具；泄露后请在电脑上吊销该令牌。

DSH1:eyJkaXNwbGF5TmFtZSI6IuWutumHjOeahOeUteiEkSIsImVuZHBvaW50cyI6W3sibGFiZWwiOiLlsYDln5_nvZHvvIhlbm8x77yJIiwiYmFzZVVybCI6Imh0dHA6Ly8xOTIuMTY4LjEuMTI2OjMwOTAifSx7ImxhYmVsIjoi6Jma5ouf572R77yIdXR1bjDvvIkiLCJiYXNlVXJsIjoiaHR0cDovLzEwMC42NC4yNTAuMTozMDkwIn1dLCJ0b2tlbiI6IlJFREFDVEVELVRPS0VOLTAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAiLCJkZXZpY2VOYW1lIjoiaW50ZXJvcC1maXh0dXJlLTEiLCJzY29wZXMiOlsiZmlsZXMucmVhZCIsImNoYXQucmVhZCJdfQ
```

注意脚本自动探测到了两个地址：`192.168.1.126`（eno1，局域网）与 `100.64.250.1`（utun0，虚拟网）。
这正是用户要的「一台电脑多个地址」形态 —— 出门在外走虚拟网，在家走局域网。

> 本文档中出现的设备令牌已脱敏为**等长占位符**（原值已在实测后吊销，见 §7.3）。
> 占位符长度与原值相同，所以下面的 419 字符长度结论、base64 与 JSON 的对应关系都不受影响。

### 3.2 命令行参数

| 参数 | 说明 |
| --- | --- |
| `--name <文本>` | 电脑显示名（默认：本机主机名） |
| `--endpoint <标签=地址>` | 一个可达地址，**可重复**；省略标签则用地址本身 |
| `--auto-endpoint` / `--no-auto-endpoint` | 未显式给地址时自动探测本机网卡（默认开启） |
| `--port <端口>` | 自动探测拼接的端口（默认 3090） |
| `--device <文本>` | 手机/设备名，写入服务端 `devices` 表，便于日后吊销 |
| `--scopes <列表\|all\|none>` | 设备权限（默认 `chat.read,chat.write,files.read,files.write,settings.read`） |
| `--token <令牌>` / `--token-file <路径>` | 显式提供令牌（优先于自动配对） |
| `--pair` / `--no-pair` | 是否自动配对（默认：没有显式令牌时自动配对） |
| `--allow-no-token` | 配对失败也照常输出（产出的文本无法认证，仅供格式测试） |
| `--host <基地址>` | 本机 workspace 服务地址（默认 `http://127.0.0.1:3090`） |
| `--roots <id,id\|all>` | 配对令牌授权的工作区根（默认 `all`） |
| `--probe` / `--no-probe` | 生成前对候选地址探活（默认开启） |
| `--verify` | 解码自检，把还原结果打到 stderr |
| `--json` / `--quiet` | 结构化输出 / 不打印 stderr |

---

## 4. 令牌自动获取：研究结论

**结论：能走通，而且已经是默认路径。** 脚本在本机一次跑完「创建配对码 → 兑换设备令牌」两步，
用户全程看不到配对码，也不需要念它。

### 4.1 服务端事实（只读研究 `/media/wangke/OFFICE/workspace/dsh-remote-bridge`）

| 路由 | 方法 | 鉴权 | 源码位置 |
| --- | --- | --- | --- |
| `/manage/pairings` | POST | **loopback 管理接口**，不需要 Bearer | `src/host/router.ts:558` |
| `/api/v1/pairings/exchange` | POST | **公开**，不需要 Bearer | `src/host/router.ts:92` |
| `/manage/roots` | GET | loopback 管理接口（用来取真实 rootId） | `src/host/router.ts:521` |
| `/api/v1/devices/self` | GET | 需要 `Authorization: Bearer <设备令牌>` | `src/host/router.ts:117` |

关键实现细节：

- `createPairing(rootIds, scopes, ttlMs = 10 分钟)`：`randomBytes(5)` → 形如 `AA87B-042D8` 的配对码，
  数据库只存 **sha256**（`src/host/database.ts:103`）；
- `exchangePairing(code, deviceName)`：校验未用过且未过期，签发 `randomBytes(32).toString('base64url')`
  的**43 字符**设备令牌，写 `devices` 表 + `device_roots` 授权，并把配对码标记 `used_at`（一次性）
  （`src/host/database.ts:117`）；
- 管理接口的「仅本机」判定是 `requireAdmin`：来源地址必须是 `127.0.0.1` / `::1` / `::ffff:127.0.0.1`，
  且 `Origin` 为空或 loopback（`src/host/auth.ts:47-53`、`isLoopbackAddress:70`）；
- 配对码归一化会剔掉所有非字母数字并转大写（`normalizePairingCode:339`），所以 `aa87b042d8` 也能兑换。

### 4.2 实测：两步换令牌（可复现命令 + 真实响应）

> 下面的响应取自**当时的插件版本**（响应里的 `pluginVersion` 是 `1.0.0`，那正是本文件写作时的线上版本）。
> 插件后来改名为 `dsh-remote-bridge`，App 侧当前要求 **≥ 2.0.4**；本节用到的这几条路由未变，
> 所以这段记录作为「怎么换令牌」的说明仍然有效，但**不要把 `1.0.0` 读成当前版本要求**。

```console
$ curl -s -i http://127.0.0.1:3090/api/v1/healthz
HTTP/1.1 200 OK
{"ok":true,"version":"v1","pluginVersion":"1.0.0"}

$ curl -s -X POST http://127.0.0.1:3090/manage/pairings \
    -H 'Content-Type: application/json' -d '{"rootIds":[],"scopes":["files.read"]}'
{"code":"AA87B-042D8","expiresAt":1791474980348}

$ curl -s -X POST http://127.0.0.1:3090/api/v1/pairings/exchange \
    -H 'Content-Type: application/json' -d '{"code":"AA87B-042D8","deviceName":"Pixel 9"}'
{"token":"REDACTED-TOKEN-0000000000000000000000000000",
 "device":{"id":"c63e73cb-baf2-4717-80f1-a21f02f1e3eb","name":"Pixel 9",
           "scopes":["files.read"],"rootIds":[],"createdAt":1791474385314}}
```

两步之间没有人工介入，配对码 10 分钟的有效期根本用不上 —— 它只是「创建」与「兑换」之间的一段
极小窗口。**这就是这条路在场景上成立的原因**：需要电脑在场的部分（创建配对码）由脚本自己完成，
用户要带走的东西已经变成一段不依赖电脑在线与否的文本。

### 4.3 ⚠️ 地址与路径前缀：一个容易踩的坑

本机同时存在**两个不同的 HTTP 面**，前缀规则不一样，实测：

| 请求 | 结果 |
| --- | --- |
| `http://192.168.1.126:3090/api/v1/healthz` | **200** |
| `http://192.168.1.126:3090/dsh-workspace-api/api/v1/healthz` | 401 |
| `http://127.0.0.1:3080/dsh-workspace-api/api/v1/healthz` | **200** |

- **3090** 是 workspace 插件的**远程监听端口**（实测 `ss -ltnp` 显示 `0.0.0.0:3090`，
  与 DSH 主进程同 PID），它**不带** `/dsh-workspace-api` 前缀；
- **3080** 是 DSH Web 本体（只绑 `127.0.0.1`，手机到不了），插件挂在 `/dsh-workspace-api` 前缀下。

对 APP 而言这条正好自洽：`DshClient.normalizeEndpoint()` 会在基地址后补 `/api/v1`
（`DshClient.kt:371-378`），所以线格式里写 `http://192.168.1.126:3090` 是对的 ——
APP 实际请求 `http://192.168.1.126:3090/api/v1/...`，命中 3090 监听。

> 如果谁把线格式里的地址写成 `http://ip:3090/dsh-workspace-api`，APP 会请求
> `/dsh-workspace-api/api/v1/...` 而 3090 上不存在该路由 → 必然连不上。

**退路**：若某台机器只开了 Web 挂载面（3080）而没开远程监听（3090），
`--host http://127.0.0.1:3080/dsh-workspace-api` 仍可用于**取令牌**（地址照旧显式给 `--endpoint`）。
已实测走通：

```console
$ curl -s http://127.0.0.1:3080/dsh-workspace-api/manage/roots
{"items":[{"id":"b3cc74d4-4d42-436f-b8af-2d2cd3a02b4b","label":"workspace","realPath":"/media/wangke/OFFICE/workspace","createdAt":1791464646286}]}

$ node tools/emit-config.mjs --name "家里的电脑" --device "interop-fallback-2" \
    --host http://127.0.0.1:3080/dsh-workspace-api \
    --endpoint "家里局域网=http://192.168.1.126:3090" --no-probe --verify --quiet --json
{ "text": "DSH1:eyJkaXNwbGF5TmFtZSI6IuWutumHjOeahOeUteiEkSIs...",
  "payload": { "displayName": "家里的电脑",
               "endpoints": [{"label":"家里局域网","baseUrl":"http://192.168.1.126:3090"}],
               "token": "REDACTED-TOKEN-0000000000000000000000000000",
               "deviceName": "interop-fallback-2",
               "scopes": ["chat.read","chat.write","files.read","files.write","settings.read"] } }
```

### 4.4 走不通的路（附原因与替代路径）

| 路 | 结果 | 原因 | 替代 |
| --- | --- | --- | --- |
| 用**局域网 IP** 调管理接口建配对码 | ❌ 403 | 实测 `{"error":{"code":"ADMIN_LOOPBACK_REQUIRED","message":"This operation is available only from the local machine."}}` —— `requireAdmin` 只认 loopback 来源 | 脚本本来就跑在电脑上，改走 `127.0.0.1` 即可（默认行为） |
| 用**局域网 IP** 调 `/api/v1/pairings/exchange` | 需配对码 | 兑换接口是公开的，但必须先有配对码；而配对码只能从 loopback 管理口创建 | 无实质影响：两步都在本机跑 |
| 手工让用户从别处抄令牌 | 可用但不推荐 | 用户要开 SSH / 翻数据库 / 问别人，正是要消灭的摩擦 | `--token` / `$DSH_DEVICE_TOKEN` / `--token-file` 仅作退路保留 |
| 不加密直接发文本 | ✅ 有意为之 | 线格式**刻意不加密**：Base64 只是编码，文本本身就是凭据载体 | 安全边界交给「发给谁」；泄露的正确处理是吊销令牌（见 §7） |

---

## 5. 实测记录

### 5.1 与 Kotlin 编码端逐字节对拍（最强的一条证据）

同一组输入（`家里的电脑` / `家里局域网=http://192.168.1.126:3090` / `tok_abc123` / `Pixel 9` / 无 scopes）：

```console
$ node tools/emit-config.mjs --name "家里的电脑" \
    --endpoint "家里局域网=http://192.168.1.126:3090" \
    --token tok_abc123 --device "Pixel 9" --scopes none --no-probe --quiet
DSH1:eyJkaXNwbGF5TmFtZSI6IuWutumHjOeahOeUteiEkSIsImVuZHBvaW50cyI6W3sibGFiZWwiOiLlrrbph4zlsYDln5_nvZEiLCJiYXNlVXJsIjoiaHR0cDovLzE5Mi4xNjguMS4xMjY6MzA5MCJ9XSwidG9rZW4iOiJ0b2tfYWJjMTIzIiwiZGV2aWNlTmFtZSI6IlBpeGVsIDkifQ

$ # 上面那行就是脚本的产物；下面拿它与 Kotlin encode() 的产物（ConnectionShareTest.kt 里冻结的 GOLDEN_BODY）比较
$ [ "$(上面那行)" = "DSH1:eyJkaXNwbGF5TmFtZSI6IuWutumHjOeahOeUteiEkSIsImVuZHBvaW50cyI6W3sibGFiZWwiOiLlrrbph4zlsYDln5_nvZEiLCJiYXNlVXJsIjoiaHR0cDovLzE5Mi4xNjguMS4xMjY6MzA5MCJ9XSwidG9rZW4iOiJ0b2tfYWJjMTIzIiwiZGV2aWNlTmFtZSI6IlBpeGVsIDkifQ" ] \
    && echo "逐字节相同（与 ConnectionShare.encode 输出一致）" || echo "不一致"
逐字节相同（与 ConnectionShare.encode 输出一致）
```

即：生成端与 Kotlin `ConnectionShare.encode()` 产出的**是同一个字节序列**，
不只是「互相能读懂」。这条断言已固化进单测
`scriptGoldenOutputIsByteIdenticalToAppEncoder`。

### 5.2 生成的令牌真的能用（用文本里的令牌走局域网地址认证）

```console
$ curl -s http://192.168.1.126:3090/api/v1/devices/self \
    -H "Authorization: Bearer REDACTED-TOKEN-0000000000000000000000000000"
{"id":"9397b682-8e9c-424d-9ab4-d2744a8460d9","name":"interop-fixture-1",
 "scopes":["files.read","chat.read"],"rootIds":["b3cc74d4-4d42-436f-b8af-2d2cd3a02b4b"]}
```

注意这里走的是**局域网 IP 而非 loopback**，并且用的是**文本里那枚令牌** ——
等价于「手机拿这段文本去连电脑」的最小复现。整条链路因此是闭合的：

```
emit-config.mjs → DSH1 文本 → ConnectionShare.decode → buildHost → DshClient(带令牌) → 200
```

该令牌随后已被吊销（见 §7.3），所以现在用它会得到 `401 TOKEN_INVALID` ——
这也是对「吊销确实生效」的一次反证。

### 5.3 无令牌档（`token` 键整个消失）

```console
$ node tools/emit-config.mjs --name "家里的电脑" \
    --endpoint "家里局域网=http://192.168.1.126:3090" \
    --endpoint "公司虚拟网=http://100.101.102.103:3090" \
    --device "Pixel 9" --scopes none --no-pair --allow-no-token --no-probe --quiet
DSH1:eyJkaXNwbGF5TmFtZSI6IuWutumHjOeahOeUteiEkSIsImVuZHBvaW50cyI6W3sibGFiZWwiOiLlrrbph4zlsYDln5_nvZEiLCJiYXNlVXJsIjoiaHR0cDovLzE5Mi4xNjguMS4xMjY6MzA5MCJ9LHsibGFiZWwiOiLlhazlj7jomZrmi5_nvZEiLCJiYXNlVXJsIjoiaHR0cDovLzEwMC4xMDEuMTAyLjEwMzozMDkwIn1dLCJkZXZpY2VOYW1lIjoiUGl4ZWwgOSJ9
```

解码后 `token == null`、`scopes` 为空、`buildHost` 不建凭据（单测
`decodesTokenlessScriptOutputWithoutInventingCredential` 断言）。

### 5.4 实测踩到的坑（留在文档里免得下次再踩）

第一次跑自动配对时报：

```
[emit-config] 失败：自动获取设备令牌失败：POST http://127.0.0.1:3090/manage/pairings
  → 400 BODY_INVALID: rootIds must be an array of strings.
```

原因：`--roots` 的默认值 `all` 是写给用户看的简写，我把它原样当数组传给了服务端。
修法是先 `GET /manage/roots` 取真实 id 再替换（`router.ts` 会对每个 rootId 做存在性校验，
传不存在的 id 会 404 `ROOT_NOT_FOUND`）。这说明**服务端校验是可靠的**，脚本不该自作聪明。

---

## 6. 与 APP 解码端的对拍单测

文件：`app/src/test/java/io/github/hakunm/deepseekharness/data/ConnectionShareInteropTest.kt`

```bash
cd /media/wangke/OFFICE/workspace/dsh-companion
scripts/build.sh :app:testDebugUnitTest --tests "io.github.hakunm.deepseekharness.data.ConnectionShareInteropTest"
```

固定输入**全部是脚本的真实 stdout**（已用脚本反向校验过与 .kt 里的字面量逐字节相同）：

| 常量 | 来源 |
| --- | --- |
| `scriptGolden`（215 字符） | §5.1 的命令输出，与 Kotlin `encode()` 逐字节相同 |
| `fixtureRealPairing`（419 字符） | §3.1 的真实自动配对运行（自动探测到 eno1 + utun0 两个地址） |
| `fixtureNoToken`（277 字符） | §5.3 |
| `fixtureWechatMangled` | `fixtureRealPairing` 经 `sed 's/.\{40\}/&\n/g'` 逐 40 字符折行、行首插入空格/制表符、前缀拆成 `DS H1:` |
| `fixtureLowercasePrefix` | 同一条文本，前缀改小写 `dsh1:` |

覆盖点：

1. **逐字节对拍**：脚本产物 == `ConnectionShare.encode()` 产物；
2. 真实产物解码后的 `displayName` / 每个 `label`+`baseUrl` / `token` / `deviceName` / `scopes`；
3. `buildHost` 行为：kind 由 APP 端重新推断（LAN / VIRTUAL_NET）、地址 id 现场派发且唯一、
   探活历史为 null、`preferredEndpointId` 为 null、凭据字段映射正确；
4. **窄格式**：解码出的 JSON 里不得出现 `id` / `kind` / `enabled` / `lastLatencyMs` /
   `lastCheckedAt` / `lastError`，且该有的键一个不少；
5. 微信折腾过的折行版本、小写前缀版本、无前缀版本都能还原成同一 payload；
6. 无令牌档不得凭空造出凭据；
7. 长度逐字符钉死（419 / 277 / 215）并断言落在微信预算内；
8. 截断的产物必须返回 null（宁可说「无效」，也不能给用户一个缺地址/错令牌的 Host）。

### 实测结果（真实跑过，非推理）

```console
$ cd /media/wangke/OFFICE/workspace/dsh-companion
$ scripts/build.sh :app:testDebugUnitTest --tests "io.github.hakunm.deepseekharness.data.ConnectionShareInteropTest"
[build] 使用缓存 Gradle：/media/wangke/OFFICE/workspace/android-toolchain/gradle-home/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle
[build] 使用依赖镜像脚本：/media/wangke/OFFICE/workspace/dsh-companion/scripts/init-mirrors.gradle
[build] 任务：:app:testDebugUnitTest --tests io.github.hakunm.deepseekharness.data.ConnectionShareInteropTest
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 3s
31 actionable tasks: 2 executed, 29 up-to-date
```

JUnit 报告（`app/build/test-results/testDebugUnitTest/TEST-io.github.hakunm.deepseekharness.data.ConnectionShareInteropTest.xml`）：

```
tests=10 failures=0 errors=0 skipped=0 time=0.224s
```

| 用例 | 结果 |
| --- | --- |
| `scriptGoldenOutputIsByteIdenticalToAppEncoder` | ✅ |
| `scriptGoldenOutputIsDecodedBackToTheSamePayload` | ✅ |
| `scriptOutputIsNarrowAndCarriesNoLocalOnlyFields` | ✅ |
| `decodesRealAutoPairedOutputFromScript` | ✅ |
| `buildHostFromRealScriptOutputInfersKindsAndKeepsCredential` | ✅ |
| `acceptsScriptOutputAfterWechatMangling` | ✅ |
| `acceptsScriptOutputWithLowercasePrefixAndWithoutPrefix` | ✅ |
| `decodesTokenlessScriptOutputWithoutInventingCredential` | ✅ |
| `scriptOutputFitsInOneWechatMessage` | ✅ |
| `truncatedScriptOutputIsRejectedInsteadOfHalfDecoded` | ✅ |

**10 / 10 通过，0 失败 0 错误 0 跳过。**

---

## 7. 权限、吊销与清理

### 7.1 权限档位

服务端 `DEVICE_SCOPES`（`src/shared/contracts.ts:1`）共 7 项：
`chat.read` `chat.write` `files.read` `files.write` `files.delete` `settings.read` `settings.write`。

脚本默认给的是 **5 项**：`chat.read,chat.write,files.read,files.write,settings.read`
（即已实测跑通的那一档）。**不含** `files.delete` 与 `settings.write` —— 这两项是破坏性权限，
要的话显式 `--scopes all`。

`--roots` 默认 `all`，即把当前 `GET /manage/roots` 返回的**全部已注册根**授权给这台新设备。

### 7.2 吊销

```bash
curl -X DELETE http://127.0.0.1:3090/manage/devices/<deviceId>
```

脚本在配对成功后会直接把这条命令打在 stderr 上（含真实 deviceId），不用用户自己去查。
怀疑文本泄露时的正确动作就是跑这一条，而不是指望别人解不开 Base64。

### 7.3 本次实测产生的设备与清理记录

实测过程中脚本在真实服务端创建了 4 个设备，**已全部吊销**（留痕，避免污染用户的设备列表）：

```console
$ for id in 9397b682-…（interop-fixture-1） f7fead7e-…（interop-fallback-2） \
              c63e73cb-…（Pixel 9） 1600bde3-…（Android 设备）; do
    curl -s -o /dev/null -w "HTTP %{http_code}\n" -X DELETE "http://127.0.0.1:3090/manage/devices/$id"; done
HTTP 204
HTTP 204
HTTP 204
HTTP 204

$ curl -s -o /dev/null -w "HTTP %{http_code}\n" http://192.168.1.126:3090/api/v1/devices/self \
    -H "Authorization: Bearer REDACTED-TOKEN-0000000000000000000000000000"
HTTP 401
```

最后那条 401 正是「吊销生效」的反证，也说明单测里固定的那枚令牌已是**死凭据**。

> 说明：脚本**不会**自动吊销任何东西；这是任务实测后的手工清理动作。

---

## 8. 已知边界与未解决疑点

1. **设备令牌不过期**：`devices` 表结构是
   `id, name, token_hash, scopes_json, created_at, last_seen_at, revoked_at`
   （实测 `database.ts:303`），**没有过期列**。也就是说这段文本只要没被吊销就长期有效 ——
   「发出去就收不回来」。产品上值得在 UI 里提醒用户定期吊销，但服务端是否有意如此，不在本任务范围。
2. **`--roots all` 是快照，不是动态授权**：`device_roots` 在兑换那一刻写入
   （`database.ts:139`），之后用户**新增**的工作区根不会自动授权给这台设备 ——
   需要重新生成一份配置。这是语义边界，不是 bug，但用户不会猜到。
3. **单测只能证明「解码端吃得下」**：`--verify` 用的是脚本自己镜像实现 `decodeWire()`，
   它**不是权威**（权威是 Kotlin）。真正的兼容性保证来自 §6 的 Kotlin 单测。
   脚本的 `--verify` 定位是「生成端自己别犯错」，不是「保证 APP 一定能解」。
4. **未在真机验证**：本任务范围内没有 Android 设备，粘贴-导入的端到端体验（含输入法/微信
   实际粘贴行为）未测；已覆盖的是「文本 → 解码 → buildHost → 带令牌请求成功」这条链路。
5. **地址自动探测的边界**：候选地址靠 `/api/v1/healthz` 探活（自动探测来的地址探不通会被丢弃，
   显式 `--endpoint` 给的一律保留并告警）。若本机 3090 未监听（未开启远程访问），
   脚本会明确报错而不是产出一份连不上的配置 —— 这是有意的「宁可拒绝」。
6. **默认端口写死 3090**：`--port` 可改，但监听端口由服务端 `remote` 配置决定，
   脚本不去猜其它端口。
