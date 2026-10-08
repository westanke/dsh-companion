#!/usr/bin/env node
/**
 * emit-config.mjs —— 「配置导入」的生成端。
 *
 * ## 它解决的问题
 *
 * 真实场景是「人已经出门在外，电脑留在家里」。扫码/念配对码都要求配对那一刻电脑在旁，
 * 在这个场景下直接不可用。取而代之的做法是：**在电脑上生成一段短文本，发给自己**
 * （微信文件传输助手 / 邮件 / 私密笔记），到了目的地再粘贴进 APP。
 *
 * APP 侧的解码端已经实现（`ConnectionShare.kt`）。本脚本补上缺失的另一半：在没有 APP、
 * 没有摄像头、没有第二台设备的前提下，产出一段可粘贴的 `DSH1:` 文本。
 *
 * ## 线格式（权威定义是 `ConnectionShare.kt`，不是本文件）
 *
 * ```
 * DSH1:<UTF-8 JSON 的 Base64 URL-safe 无填充编码>
 * ```
 *
 * JSON 是**窄格式**，不带 `id` / `kind` / `enabled` / 探活历史等本机状态：
 *
 * ```json
 * {"displayName":"家里的电脑","endpoints":[{"label":"家里局域网","baseUrl":"http://192.168.1.126:3090"}],
 *  "token":"<设备令牌>","deviceName":"Pixel 9","scopes":["files.read"]}
 * ```
 *
 * - `token` / `deviceName` 为 null 或空串时**整个键都不写**；
 * - `scopes` 为空时不写；
 * - `baseUrl` 不含 `/api/v1` 后缀（`/api/v1` 是 DshClient 的接口前缀，不是地址的一部分）。
 *
 * 实测：本脚本对「家里电脑 / 家里局域网 / tok_abc123 / Pixel 9」这组输入产出的文本，
 * 与 Kotlin 端 `ConnectionShare.encode()` 的输出**逐字节相同**（见
 * `ConnectionShareInteropTest.kt` 的 golden 断言）。
 *
 * ## 令牌从哪来（优先自动，不让人手工抄）
 *
 * 脚本本身就运行在电脑上，所以「创建配对码时电脑在场」这个前提在本脚本里天然成立：
 *
 * 1. `POST /manage/pairings`（loopback 管理接口，免 Bearer）→ 拿到 10 分钟有效的配对码；
 * 2. `POST /api/v1/pairings/exchange` → 立刻把配对码换成设备令牌。
 *
 * 两步都在本机一次跑完，用户不需要看到配对码，更不需要念它。当然也可以用
 * `--token` / `--token-file` / `$DSH_DEVICE_TOKEN` 显式提供（服务端不方便直连时的退路）。
 *
 * ## 安全边界（重要）
 *
 * 产出的文本**等价于一把钥匙**：内含设备令牌，Base64 只是编码不是加密，任何拿到它的人都能
 * 解出明文。脚本刻意不做加密 —— 安全边界由「用户把这段文本发给谁」决定。因此：
 * **不要发到群聊、论坛、截图、issue，或粘贴给第三方工具**；只发给自己可信的渠道。
 * 怀疑泄露时正确做法是在电脑上**吊销该设备令牌**，而不是指望别人「看不懂」。
 *
 * ## 依赖
 *
 * 只用 Node 内置能力（`node:os` / `node:fs` / `node:process` + 全局 `fetch` / `AbortSignal.timeout`），
 * 无第三方依赖。**要求 Node ≥ 18**（全局 `fetch`），实测环境 Node v24.19.0。
 *
 * 用法见 `--help`，完整说明见 `docs/project/CONFIG-EMITTER.md`。
 */

import fs from 'node:fs'
import os from 'node:os'
import process from 'node:process'

// ---------------------------------------------------------------------------
// 常量：与 ConnectionShare.kt 对齐
// ---------------------------------------------------------------------------

/** 线格式字面量前缀。 */
const PREFIX = 'DSH1:'

/** 归一地址时要剥掉的接口前缀。 */
const API_SUFFIX = '/api/v1'

/** 本机 workspace 插件的监听地址（loopback，管理接口只认 loopback）。 */
const DEFAULT_HOST = 'http://127.0.0.1:3090'

/** 自动探测网卡时拼接的端口。 */
const DEFAULT_PORT = 3090

/** 设备记录的默认名字（会写进服务端 devices 表，便于日后吊销）。 */
const DEFAULT_DEVICE_NAME = 'Android 设备'

/** 服务端 `DEVICE_SCOPES` 的全集。 */
const ALL_SCOPES = [
  'chat.read',
  'chat.write',
  'files.read',
  'files.write',
  'files.delete',
  'settings.read',
  'settings.write',
]

/**
 * 默认权限：取「已经实测跑通的设备」所用的那一档（读写聊天与文件 + 读设置）。
 * 不用全集是因为 `files.delete` / `settings.write` 属于破坏性权限，应由用户显式 `--scopes all` 开启。
 */
const DEFAULT_SCOPES = ['chat.read', 'chat.write', 'files.read', 'files.write', 'settings.read']

/** 单条微信消息的软预算（正常配置的目标）与硬上限。 */
const SOFT_LENGTH_LIMIT = 400
const HARD_LENGTH_LIMIT = 2000

const HTTP_TIMEOUT_MS = 8000
const PROBE_TIMEOUT_MS = 1500

/** 粘贴链路里常见的「看不见但不是空白」的字符（BOM / 零宽）。 */
const INVISIBLE_CHARS = new Set(['\uFEFF', '\u200B', '\u200C', '\u200D', '\u2060'])

const KIND_LABEL = { LAN: '局域网', VIRTUAL_NET: '虚拟网', WAN: '公网', UNKNOWN: '未知网络' }

// ---------------------------------------------------------------------------
// 线格式编解码（镜像 ConnectionShare.kt 的行为；权威实现仍是 Kotlin 那份）
// ---------------------------------------------------------------------------

function trimOrNull(value) {
  if (value === undefined || value === null) return null
  const text = String(value).trim()
  return text.length === 0 ? null : text
}

/**
 * 归一基地址：去首尾空白与尾斜杠、去掉 `/api/v1` 后缀、协议名统一小写。
 * 与 Kotlin `normalizeBaseUrl` 幂等等价。
 */
function normalizeBaseUrl(raw) {
  let value = String(raw ?? '').trim().replace(/\/+$/, '')
  if (value.endsWith(API_SUFFIX)) value = value.slice(0, -API_SUFFIX.length).replace(/\/+$/, '')
  if (/^http:\/\//i.test(value)) return 'http://' + value.slice('http://'.length)
  if (/^https:\/\//i.test(value)) return 'https://' + value.slice('https://'.length)
  return value
}

/** 地址是否可用：http/https 且带主机名。等价于 Kotlin `isUsableBaseUrl`。 */
function isUsableBaseUrl(raw) {
  const normalized = normalizeBaseUrl(raw)
  const schemeLength = normalized.startsWith('http://')
    ? 7
    : normalized.startsWith('https://')
      ? 8
      : 0
  if (schemeLength === 0) return false
  const host = normalized.slice(schemeLength).split('/')[0].split(':')[0]
  return host.length > 0
}

/** 把地址按 Kotlin `EndpointKind.infer` 的规则粗分类（仅用于标签与排序）。 */
function classifyHost(host) {
  const value = String(host ?? '').toLowerCase()
  if (value === 'localhost' || value === '127.0.0.1' || value === '::1' || value.endsWith('.local')) return 'LAN'
  const octets = value.split('.').map(Number)
  if (octets.length !== 4 || octets.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return 'UNKNOWN'
  const [a, b] = octets
  if (a === 10) return 'LAN'
  if (a === 192 && b === 168) return 'LAN'
  if (a === 172 && b >= 16 && b <= 31) return 'LAN'
  // 100.64.0.0/10 是运营商级 NAT 网段，Tailscale / BeyondTunnel 都在用。
  if (a === 100 && b >= 64 && b <= 127) return 'VIRTUAL_NET'
  if (a === 169 && b === 254) return 'LAN'
  return 'WAN'
}

function hostOf(baseUrl) {
  return normalizeBaseUrl(baseUrl)
    .replace(/^https?:\/\//i, '')
    .split('/')[0]
    .split(':')[0]
}

/**
 * 编码成可粘贴文本：`DSH1:` + URL-safe 无填充 Base64。
 *
 * 键的书写顺序与 Kotlin `WirePayload` 一致，且**默认值不写**（`encodeDefaults = false` 的对应实现），
 * 因此同样的输入能产出逐字节相同的文本。
 */
function encodeWire(payload) {
  const endpoints = payload.endpoints.map((endpoint) => ({
    label: String(endpoint.label ?? '').trim(),
    baseUrl: normalizeBaseUrl(endpoint.baseUrl),
  }))
  if (endpoints.length === 0) throw new Error('至少需要一个地址才能生成配置文本')

  const wire = { displayName: String(payload.displayName ?? '').trim(), endpoints }
  const token = trimOrNull(payload.token)
  if (token !== null) wire.token = token
  const deviceName = trimOrNull(payload.deviceName)
  if (deviceName !== null) wire.deviceName = deviceName
  const scopes = (payload.scopes ?? []).map((scope) => String(scope).trim()).filter((scope) => scope.length > 0)
  if (scopes.length > 0) wire.scopes = scopes

  for (const endpoint of endpoints) {
    if (!isUsableBaseUrl(endpoint.baseUrl)) {
      throw new Error(`地址必须是 http:// 或 https:// 开头（去掉 /api/v1 之后）：${endpoint.baseUrl}`)
    }
  }

  const json = JSON.stringify(wire)
  return { text: PREFIX + Buffer.from(json, 'utf8').toString('base64url'), json, wire }
}

/** 去掉所有空白（含换行、全角空格、nbsp）与零宽字符。 */
function cleanText(text) {
  return [...String(text)].filter((char) => !/\s/u.test(char) && !INVISIBLE_CHARS.has(char)).join('')
}

function isBase64Char(char) {
  return /[A-Za-z0-9+/=_-]/.test(char)
}

function tryParseJson(raw) {
  try {
    const parsed = JSON.parse(raw)
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null
  } catch {
    return null
  }
}

/**
 * 解码用户粘贴的文本，**镜像** `ConnectionShare.decode` 的容错规则：
 * 首尾与内部空白、零宽字符、可选且大小写不敏感的前缀、URL-safe 与标准 Base64 字母表、
 * 缺失的 `=` 填充、结尾粘上的标点、以及解码结果里混了前后缀文字的情况。
 *
 * 任何失败返回 null，绝不抛异常。注意这只是自检用的镜像实现 —— 权威实现是
 * `ConnectionShare.decode`，`--verify` 通过不等于 APP 端一定通过，APP 端由 Kotlin 单测保证。
 */
function decodeWire(text) {
  const cleaned = cleanText(text)
  const body =
    cleaned.length >= PREFIX.length && cleaned.slice(0, PREFIX.length).toUpperCase() === PREFIX
      ? cleaned.slice(PREFIX.length)
      : cleaned
  const alphabetOnly = [...body].filter(isBase64Char).join('')
  if (alphabetOnly.length === 0) return null
  const remainder = alphabetOnly.length % 4
  if (remainder === 1) return null
  const padded = remainder === 0 ? alphabetOnly : alphabetOnly + '='.repeat(4 - remainder)

  const standard = padded.replace(/-/g, '+').replace(/_/g, '/')
  let raw
  try {
    raw = Buffer.from(standard, 'base64').toString('utf8')
  } catch {
    return null
  }

  let wire = tryParseJson(raw)
  if (wire === null) {
    const start = raw.indexOf('{')
    const end = raw.lastIndexOf('}')
    if (start >= 0 && end > start) wire = tryParseJson(raw.slice(start, end + 1))
  }
  if (wire === null) return null

  if (wire.displayName !== undefined && typeof wire.displayName !== 'string') return null
  if (wire.token !== undefined && wire.token !== null && typeof wire.token !== 'string') return null
  if (wire.deviceName !== undefined && wire.deviceName !== null && typeof wire.deviceName !== 'string') return null
  if (wire.scopes !== undefined && wire.scopes !== null && !Array.isArray(wire.scopes)) return null
  if (typeof wire.scopes?.some === 'function' && wire.scopes.some((scope) => typeof scope !== 'string')) return null
  if (!Array.isArray(wire.endpoints)) return null

  const endpoints = []
  for (const endpoint of wire.endpoints) {
    if (endpoint === null || typeof endpoint !== 'object') return null
    if (typeof endpoint.baseUrl !== 'string') return null
    if (endpoint.label !== undefined && typeof endpoint.label !== 'string') return null
    const baseUrl = normalizeBaseUrl(endpoint.baseUrl)
    if (!isUsableBaseUrl(baseUrl)) return null
    endpoints.push({ label: String(endpoint.label ?? '').trim(), baseUrl })
  }
  if (endpoints.length === 0) return null

  return {
    displayName: String(wire.displayName ?? '').trim(),
    endpoints,
    token: trimOrNull(wire.token),
    deviceName: trimOrNull(wire.deviceName),
    scopes: (wire.scopes ?? []).map((scope) => scope.trim()).filter((scope) => scope.length > 0),
  }
}

// ---------------------------------------------------------------------------
// HTTP（只用全局 fetch）
// ---------------------------------------------------------------------------

async function httpJson(url, options = {}) {
  const { method = 'GET', body, timeout = HTTP_TIMEOUT_MS } = options
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  let response
  try {
    response = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(timeout),
    })
  } catch (error) {
    const reason = error?.name === 'TimeoutError' ? `超时（${timeout}ms）` : (error?.cause?.message ?? error?.message ?? error)
    throw new Error(`请求 ${method} ${url} 失败：${reason}`)
  }
  const text = await response.text()
  let data = null
  try {
    data = text.length === 0 ? null : JSON.parse(text)
  } catch {
    data = null
  }
  if (!response.ok) {
    const code = data?.error?.code ?? `HTTP_${response.status}`
    const message = data?.error?.message ?? (text.slice(0, 200) || '（无响应正文）')
    throw new Error(`${method} ${url} → ${response.status} ${code}: ${message}`)
  }
  return data
}

// ---------------------------------------------------------------------------
// 令牌获取
// ---------------------------------------------------------------------------

/**
 * 自动配对：在本机创建配对码并立刻兑换成设备令牌。
 *
 * 全程 loopback，所以满足服务端 `requireAdmin` 的「仅本机」限制；两步之间没有人工介入，
 * 配对码的有效期（10 分钟）根本用不上。返回真实响应，供文档与排障引用。
 */
async function pairForToken(host, { deviceName, scopes, rootIds }) {
  const base = String(host).replace(/\/+$/, '')

  const created = await httpJson(`${base}/manage/pairings`, {
    method: 'POST',
    body: { rootIds, scopes },
  })
  if (typeof created?.code !== 'string') throw new Error('创建配对码成功但响应里没有 code 字段')

  const exchanged = await httpJson(`${base}/api/v1/pairings/exchange`, {
    method: 'POST',
    body: { code: created.code, deviceName },
  })
  if (typeof exchanged?.token !== 'string') throw new Error('兑换配对码成功但响应里没有 token 字段')

  return { token: exchanged.token, device: exchanged.device, code: created.code, expiresAt: created.expiresAt, rootIds }
}

async function probeEndpoint(baseUrl) {
  const startedAt = Date.now()
  try {
    const data = await httpJson(`${normalizeBaseUrl(baseUrl)}${API_SUFFIX}/healthz`, { timeout: PROBE_TIMEOUT_MS })
    if (data?.ok !== true) return { ok: false, latencyMs: null, error: 'healthz 未返回 ok:true' }
    return { ok: true, latencyMs: Date.now() - startedAt, error: null }
  } catch (error) {
    return { ok: false, latencyMs: null, error: error.message }
  }
}

// ---------------------------------------------------------------------------
// 地址自动发现
// ---------------------------------------------------------------------------

function autoEndpoints(port) {
  const rank = { LAN: 0, VIRTUAL_NET: 1, WAN: 2, UNKNOWN: 3 }
  const seen = new Set()
  const found = []
  for (const [iface, addresses] of Object.entries(os.networkInterfaces())) {
    for (const address of addresses ?? []) {
      if (address.family !== 'IPv4' || address.internal) continue
      // link-local 只在没有 DHCP 时出现，对外连接没有意义，别塞给用户。
      if (address.address.startsWith('169.254.')) continue
      if (seen.has(address.address)) continue
      seen.add(address.address)
      const kind = classifyHost(address.address)
      found.push({
        kind,
        iface,
        baseUrl: `http://${address.address}:${port}`,
        label: `${KIND_LABEL[kind]}（${iface}）`,
      })
    }
  }
  found.sort((a, b) => rank[a.kind] - rank[b.kind] || a.iface.localeCompare(b.iface))
  return found
}

// ---------------------------------------------------------------------------
// 命令行参数
// ---------------------------------------------------------------------------

const HELP = `用法：
  node tools/emit-config.mjs [选项]

产出（stdout 只有一行可粘贴文本，诊断信息一律走 stderr）：
  DSH1:<Base64 URL-safe 无填充>

选项：
  --name <文本>            电脑显示名（默认：本机主机名）
  --endpoint <标签=地址>   一个可达地址，可重复；省略标签则用地址本身
                           例：--endpoint "家里局域网=http://192.168.1.126:3090"
  --auto-endpoint          未显式给 --endpoint 时自动探测本机网卡（默认开启）
  --no-auto-endpoint       关闭自动探测（此时必须显式给 --endpoint）
  --port <端口>            自动探测拼接的端口（默认 ${DEFAULT_PORT}）
  --device <文本>          手机/设备名，写入服务端 devices 表，便于日后吊销（默认 ${DEFAULT_DEVICE_NAME}）
  --scopes <列表|all|none> 设备权限，逗号分隔（默认 ${DEFAULT_SCOPES.join(',')}；none 表示不写该键）
  --token <令牌>           显式提供设备令牌（优先于自动配对）
  --token-file <路径>      从文件读取设备令牌（首行，去空白）
  --pair / --no-pair       是否自动配对换令牌（默认：没有显式令牌时自动配对）
  --allow-no-token         配对失败也照常输出（产出的文本无法认证，仅供格式测试）
  --host <基地址>          本机 workspace 服务地址（默认 ${DEFAULT_HOST}）
                           管理接口只认 loopback，必须是 127.0.0.1 / ::1
  --roots <id,id|all>      配对令牌授权的根目录（默认 all，即全部已注册根）
  --probe / --no-probe     生成前对候选地址探活（默认开启）
  --verify                 解码自检，把还原结果打印到 stderr
  --json                   stdout 改为输出 JSON 摘要（含 text 字段）
  --quiet                  不打印 stderr 摘要
  --help                   显示本帮助

示例：
  node tools/emit-config.mjs --name "家里的电脑" --device "Pixel 9" --verify

  node tools/emit-config.mjs --name "家里的电脑" \\
    --endpoint "家里局域网=http://192.168.1.126:3090" \\
    --endpoint "公司虚拟网=http://100.101.102.103:3090" \\
    --device "Pixel 9" --scopes "files.read,chat.read" --verify

安全提醒：产出的文本内含设备令牌，等价于一把钥匙。只发给自己（文件传输助手 / 私密笔记 / 邮件），
不要发到群聊、论坛、截图或第三方工具。
`

function parseArgs(argv) {
  const opts = {
    name: null,
    endpoints: [],
    autoEndpoint: true,
    port: DEFAULT_PORT,
    device: DEFAULT_DEVICE_NAME,
    scopes: null,
    token: null,
    tokenFile: null,
    pair: null,
    allowNoToken: false,
    host: DEFAULT_HOST,
    roots: 'all',
    probe: true,
    verify: false,
    json: false,
    quiet: false,
    help: false,
  }

  const take = (index, flag) => {
    const value = argv[index + 1]
    if (value === undefined || value.startsWith('--')) throw new Error(`${flag} 需要一个值`)
    return value
  }

  for (let i = 0; i < argv.length; i += 1) {
    const raw = argv[i]
    const eq = raw.indexOf('=')
    const flag = raw.startsWith('--') && eq > 0 ? raw.slice(0, eq) : raw
    const inline = raw.startsWith('--') && eq > 0 ? raw.slice(eq + 1) : null
    const value = (needInline) => {
      if (inline !== null && inline !== '') return inline
      i += 1
      return take(i - 1, needInline)
    }

    switch (flag) {
      case '--name':
        opts.name = value('--name')
        break
      case '--endpoint':
      case '--address': {
        const spec = value(flag)
        const separator = spec.indexOf('=')
        // 只把「标签=地址」当成带标签写法；`https://x=1` 这种 URL 里的 '=' 不误判。
        if (separator > 0 && !/^https?$/i.test(spec.slice(0, separator))) {
          opts.endpoints.push({ label: spec.slice(0, separator).trim(), baseUrl: spec.slice(separator + 1).trim() })
        } else {
          opts.endpoints.push({ label: '', baseUrl: spec.trim() })
        }
        break
      }
      case '--auto-endpoint':
        opts.autoEndpoint = true
        break
      case '--no-auto-endpoint':
        opts.autoEndpoint = false
        break
      case '--port':
        opts.port = Number(value('--port'))
        if (!Number.isInteger(opts.port) || opts.port <= 0 || opts.port > 65535) throw new Error('--port 不是合法端口')
        break
      case '--device':
        opts.device = value('--device')
        break
      case '--scopes':
        opts.scopes = value('--scopes')
        break
      case '--token':
        opts.token = value('--token')
        break
      case '--token-file':
        opts.tokenFile = value('--token-file')
        break
      case '--pair':
        opts.pair = true
        break
      case '--no-pair':
        opts.pair = false
        break
      case '--allow-no-token':
        opts.allowNoToken = true
        break
      case '--host':
        opts.host = value('--host')
        break
      case '--roots':
        opts.roots = value('--roots')
        break
      case '--probe':
        opts.probe = true
        break
      case '--no-probe':
        opts.probe = false
        break
      case '--verify':
        opts.verify = true
        break
      case '--json':
        opts.json = true
        break
      case '--quiet':
        opts.quiet = true
        break
      case '--help':
      case '-h':
        opts.help = true
        break
      default:
        throw new Error(`未知选项：${flag}（用 --help 查看用法）`)
    }
  }
  return opts
}

function resolveScopes(raw) {
  if (raw === null) return [...DEFAULT_SCOPES]
  const text = String(raw).trim()
  // `none` 让 scopes 键整个不写 —— 与 Kotlin `encodeDefaults = false` 的行为一致，
  // 也是「逐字节对齐」对拍所需要的档位。
  if (text.toLowerCase() === 'none' || text === '-') return []
  if (text.toLowerCase() === 'all') return [...ALL_SCOPES]
  const scopes = text.split(',').map((scope) => scope.trim()).filter((scope) => scope.length > 0)
  if (scopes.length === 0) throw new Error('--scopes 不能为空（用 "all" 表示全部权限）')
  const unknown = scopes.filter((scope) => !ALL_SCOPES.includes(scope))
  if (unknown.length > 0) throw new Error(`未知权限：${unknown.join(', ')}（可用：${ALL_SCOPES.join(', ')}）`)
  return scopes
}

/**
 * 标记「用户参数/配置错误」：这类错误与「服务连不上」是两回事，
 * 排障提示不能混在一起，否则用户会去查网络而真正的问题在参数上。
 */
function configError(message) {
  const error = new Error(message)
  error.isConfigError = true
  return error
}

function resolveRoots(raw, available) {
  const text = String(raw ?? 'all').trim()
  if (text.toLowerCase() === 'all' || text === '') return 'all'
  const ids = text.split(',').map((id) => id.trim()).filter((id) => id.length > 0)
  if (ids.length === 0) return 'all'
  const unknown = ids.filter((id) => !available.includes(id))
  if (unknown.length > 0) {
    throw configError(
      `未知 rootId：${unknown.join(', ')}\n当前已注册的根：\n${available.map((id) => `  - ${id}`).join('\n')}`,
    )
  }
  return ids
}

function readTokenFile(path) {
  const content = fs.readFileSync(path, 'utf8')
  const token = content.split(/\r?\n/).map((line) => line.trim()).find((line) => line.length > 0)
  if (token === undefined) throw new Error(`令牌文件是空的：${path}`)
  return token
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------

async function main() {
  const opts = parseArgs(process.argv.slice(2))
  if (opts.help) {
    process.stdout.write(HELP)
    return 0
  }

  const log = opts.quiet ? () => {} : (message) => process.stderr.write(`${message}\n`)
  const warnings = []
  const scopes = resolveScopes(opts.scopes)

  // ---- 1. 地址：显式给的优先，没给就自动探测本机网卡 -----------------------
  let candidates = opts.endpoints.map((endpoint) => ({
    ...endpoint,
    kind: classifyHost(hostOf(endpoint.baseUrl)),
    explicit: true,
  }))
  if (candidates.length === 0) {
    if (!opts.autoEndpoint) throw new Error('没有可用地址：要么给 --endpoint，要么别关掉 --auto-endpoint')
    candidates = autoEndpoints(opts.port).map((endpoint) => ({ ...endpoint, explicit: false }))
    if (candidates.length === 0) throw new Error('自动探测没有找到任何非回环 IPv4 地址，请显式给 --endpoint')
    log(`[emit-config] 未显式指定地址，自动探测到 ${candidates.length} 个候选（端口 ${opts.port}）`)
  }

  // ---- 2. 探活：自动探测来的地址探不通就丢掉，显式给的一律保留（可能正等着起服务）----
  if (opts.probe) {
    const probed = []
    for (const endpoint of candidates) {
      const result = await probeEndpoint(endpoint.baseUrl)
      if (result.ok) {
        probed.push({ ...endpoint, probe: result })
      } else if (endpoint.explicit) {
        probed.push({ ...endpoint, probe: result })
        warnings.push(`地址探活失败但按要求保留：${endpoint.baseUrl} —— ${result.error}`)
      } else {
        warnings.push(`自动探测的地址探活失败，已丢弃：${endpoint.baseUrl} —— ${result.error}`)
      }
    }
    if (probed.length === 0) {
      throw new Error(
        `所有候选地址都无法访问 ${API_SUFFIX}/healthz，已放弃生成。\n` +
          `请确认本机 workspace 服务在运行，或用 --no-probe 跳过探活、显式 --endpoint 指定地址。`,
      )
    }
    candidates = probed
  }

  // ---- 3. 令牌：显式 > 环境变量 > 令牌文件 > 自动配对 ----------------------
  let token = trimOrNull(opts.token)
  let tokenSource = token === null ? null : '--token'
  if (token === null) {
    const fromEnv = trimOrNull(process.env.DSH_DEVICE_TOKEN)
    if (fromEnv !== null) {
      token = fromEnv
      tokenSource = '$DSH_DEVICE_TOKEN'
    }
  }
  if (token === null && opts.tokenFile !== null) {
    token = readTokenFile(opts.tokenFile)
    tokenSource = `--token-file ${opts.tokenFile}`
  }

  let pairing = null
  const shouldPair = opts.pair === true || (opts.pair === null && token === null)
  if (token === null && shouldPair) {
    try {
      const base = String(opts.host).replace(/\/+$/, '')
      const listed = await httpJson(`${base}/manage/roots`)
      const available = (listed?.items ?? []).map((item) => String(item.id))
      const resolvedRoots = resolveRoots(opts.roots, available)
      // `all` 是给用户的简写，服务端要的是真实 id 数组。
      const rootIds = resolvedRoots === 'all' ? available : resolvedRoots
      pairing = await pairForToken(opts.host, {
        deviceName: trimOrNull(opts.device) ?? DEFAULT_DEVICE_NAME,
        scopes,
        rootIds,
      })
      token = pairing.token
      tokenSource = '自动配对（/manage/pairings → /api/v1/pairings/exchange）'
      log(`[emit-config] 已在本机自动配对并兑换设备令牌（配对码 ${pairing.code}，未经过人工）`)
    } catch (error) {
      // 参数错误原样抛出，不要伪装成「服务连不上」。
      if (error?.isConfigError) throw error
      if (!opts.allowNoToken) {
        throw new Error(
          `自动获取设备令牌失败：${error.message}\n` +
            `本机 workspace 服务是否在 ${opts.host} 运行？（管理接口只认 loopback）\n` +
            `退路：先用 --token <设备令牌> 显式传入；确实只想验证文本格式可加 --allow-no-token。`,
        )
      }
      warnings.push(`自动获取令牌失败（--allow-no-token 放行）：${error.message}`)
      tokenSource = '无（--allow-no-token）'
    }
  } else if (token === null) {
    if (!opts.allowNoToken) {
      throw new Error(
        '没有拿到设备令牌：自动配对已关闭，且没有 --token / $DSH_DEVICE_TOKEN / --token-file。\n' +
          '产出的配置将无法认证，因此默认拒绝生成；确实只想验证格式可加 --allow-no-token。',
      )
    }
    tokenSource = '无（--allow-no-token）'
  }

  // 零权限的令牌能连上、但什么也做不了 —— 这几乎肯定是笔误，必须提醒。
  if (token !== null && scopes.length === 0) {
    warnings.push('令牌不含任何权限（scopes 为空）：APP 能连上，但读文件/聊天都会被 403 拒绝')
  }

  // ---- 4. 编码 ------------------------------------------------------------
  const payload = {
    displayName: trimOrNull(opts.name) ?? os.hostname(),
    endpoints: candidates.map((endpoint) => ({
      label: trimOrNull(endpoint.label) ?? normalizeBaseUrl(endpoint.baseUrl),
      baseUrl: endpoint.baseUrl,
    })),
    token,
    deviceName: trimOrNull(opts.device),
    scopes,
  }
  const { text, json, wire } = encodeWire(payload)

  // ---- 5. 自检 ------------------------------------------------------------
  let verification = null
  if (opts.verify) {
    const restored = decodeWire(text)
    if (restored === null) throw new Error('自检失败：本脚本生成的文本自己都解不回来（这是脚本的 bug）')
    const same =
      restored.displayName === payload.displayName &&
      restored.token === trimOrNull(payload.token) &&
      restored.deviceName === trimOrNull(payload.deviceName) &&
      restored.scopes.join(',') === payload.scopes.join(',') &&
      restored.endpoints.length === payload.endpoints.length &&
      restored.endpoints.every(
        (endpoint, index) =>
          endpoint.baseUrl === normalizeBaseUrl(payload.endpoints[index].baseUrl) &&
          endpoint.label === payload.endpoints[index].label,
      )
    verification = { restored, roundTripOk: same }
    if (!same) throw new Error(`自检失败：还原结果与输入不一致\n输入：${JSON.stringify(payload)}\n还原：${JSON.stringify(restored)}`)
  }

  // ---- 6. 输出 ------------------------------------------------------------
  const tokenShown = token === null ? null : `${token.slice(0, 4)}…（共 ${token.length} 字符）`

  if (!opts.quiet) {
    log('')
    log(`[emit-config] 电脑显示名 : ${payload.displayName}`)
    log(`[emit-config] 地址（${payload.endpoints.length} 个）:`)
    candidates.forEach((endpoint, index) => {
      const address = payload.endpoints[index]
      const probe = endpoint.probe
      const suffix = probe ? (probe.ok ? `healthz ok ${probe.latencyMs}ms` : `healthz 失败：${probe.error}`) : '未探活'
      log(`  - ${address.label}  ${address.baseUrl}  [${KIND_LABEL[endpoint.kind]}] ${suffix}`)
    })
    log(`[emit-config] 令牌来源   : ${tokenSource}`)
    log(`[emit-config] 令牌        : ${tokenShown ?? '（无）'}`)
    if (pairing !== null) {
      log(
        `[emit-config] 设备        : ${pairing.device?.name ?? '?'}（id ${pairing.device?.id ?? '?'}）` +
          ` scopes=[${(pairing.device?.scopes ?? []).join(', ')}] roots=[${(pairing.device?.rootIds ?? []).join(', ')}]`,
      )
      log(`[emit-config] 吊销方法    : curl -X DELETE ${opts.host.replace(/\/+$/, '')}/manage/devices/${pairing.device?.id ?? '<id>'}`)
    }
    log(`[emit-config] 设备名      : ${payload.deviceName ?? '（未写）'}`)
    log(`[emit-config] 权限        : [${payload.scopes.join(', ')}]`)
    log(`[emit-config] 文本长度    : ${text.length} 字符（微信软预算 ${SOFT_LENGTH_LIMIT}，硬上限 ${HARD_LENGTH_LIMIT}）`)
    if (verification !== null) {
      log(`[emit-config] 自检        : 解码还原成功，往返一致=${verification.roundTripOk}`)
      log(`[emit-config] 还原结果    : ${JSON.stringify(verification.restored)}`)
    }
    for (const warning of warnings) log(`[emit-config] 警告        : ${warning}`)
    if (text.length > SOFT_LENGTH_LIMIT) {
      log(`[emit-config] 注意        : 超过软预算，微信里可能被折行（不影响粘贴，解码端能容忍换行）`)
    }
    if (text.length > HARD_LENGTH_LIMIT) {
      log(`[emit-config] 严重        : 超过 ${HARD_LENGTH_LIMIT} 字符，一条消息可能发不出去，请减少地址或换短信/邮件`)
    }
    log('[emit-config] 安全提醒    : 这段文本内含设备令牌，等价于一把钥匙 —— 只发给自己（文件传输助手/私密笔记），')
    log('                            不要发到群聊、论坛、截图、issue 或任何第三方工具；泄露后请在电脑上吊销该令牌。')
    log('')
  }

  if (opts.json) {
    process.stdout.write(
      `${JSON.stringify(
        {
          text,
          json,
          payload: { ...payload, token: token === null ? null : token },
          tokenSource,
          deviceId: pairing?.device?.id ?? null,
          deviceName: pairing?.device?.name ?? null,
          scopes,
          rootIds: pairing?.device?.rootIds ?? null,
          length: text.length,
          verified: verification === null ? null : verification.roundTripOk,
          warnings,
        },
        null,
        2,
      )}\n`,
    )
  } else {
    process.stdout.write(`${text}\n`)
  }

  return 0
}

main()
  .then((code) => {
    process.exitCode = code
  })
  .catch((error) => {
    process.stderr.write(`[emit-config] 失败：${error?.message ?? error}\n`)
    process.exitCode = 1
  })
