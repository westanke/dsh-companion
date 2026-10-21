package io.github.westanke.dshcompanion.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 应用内更新检查（下载 → SHA-256 校验 → 调起系统安装器）。
 *
 * ## 为什么更新源按界面语言分流
 *
 * 用户指令（m00424 原文）：「按语言检查 中文 gitee。英文 github」。Gitee 是主站，
 * 发布先到；GitHub 是镜像从站，由 Actions 每 3 小时同步，**可能比 Gitee 晚若干分钟**。
 * 所以英文界面查不到「刚发布的中文版本」是设计权衡，不是 bug。
 *
 * ## 为什么复用 `DshClient.defaultHttpClient` 而不是自建客户端
 *
 * `AGENTS.md` §4.1 立了「`DshClient` 是唯一网络边界」，`ArchitectureGuardTest`
 * 把它变成会红的断言：`OkHttpClient` 构造点只允许出现在 `data/DshClient.kt`。
 * 这里若再建一个客户端，门禁直接红。复用同一个实例也让连接池与超时只有一处定义。
 *
 * ## 文件位置
 *
 * 按宪法放在 `data/` 包 —— 网络访问、版本比较、SHA 校验都是**纯逻辑**，
 * 不依赖 Compose，可单测（见 `data/UpdateCheckerTest.kt`）。
 */

/** 更新源渠道。中文界面走 GITEE，其余走 GITHUB。 */
enum class ReleaseChannel(
    val apiUrl: String,
    val headers: Map<String, String>,
) {
    GITEE(
        apiUrl = "https://gitee.com/api/v5/repos/westanke/dsh-companion/releases/latest",
        headers = emptyMap(),
    ),
    GITHUB(
        apiUrl = "https://api.github.com/repos/westanke/dsh-companion/releases/latest",
        // GitHub API 强制要求 `User-Agent`，缺失返回 403（实测，非猜测）。
        headers = mapOf(
            "User-Agent" to "dsh-companion",
            "Accept" to "application/vnd.github+json",
        ),
    ),
}

/**
 * 空 tag = 用户选了「跟随系统」，此时 `AppCompatDelegate` 的语义就是「未指定」。
 *
 * 判据与 `SettingsScreen.kt` 的语言三档**保持一致**：已指定取 tag 前缀，
 * 未指定回落到系统语言。首次安装时 LocaleList 同样是空 —— 此时按系统语言走，
 * 合理且不需要额外的标志位。
 */
fun effectiveLanguageTag(languageTag: String): String =
    if (languageTag.isBlank()) {
        Locale.getDefault().language
    } else {
        languageTag.substringBefore('-')
    }

/** 中文界面（`zh*`）→ Gitee；英文 / 其他 → GitHub。纯函数，可单测。 */
fun channelForLanguage(languageTag: String): ReleaseChannel =
    if (effectiveLanguageTag(languageTag).startsWith("zh")) {
        ReleaseChannel.GITEE
    } else {
        ReleaseChannel.GITHUB
    }

/**
 * 语义化版本比较：`remote` 是否严格大于 `current`。
 *
 * **必须逐段比数字**：字符串比较会让 `2.2.9` > `2.2.47`，那是真实的发布事故
 * （Gitee tag 从 v2.2.9 一路涨到 v2.2.47）。前导 `v` 与分隔符都由正则只取数字段消化。
 */
fun isNewerVersion(remote: String, current: String): Boolean {
    val remoteParts = splitVersionParts(remote)
    if (remoteParts.isEmpty()) return false
    val currentParts = splitVersionParts(current)
    for (index in 0 until maxOf(remoteParts.size, currentParts.size)) {
        val left = remoteParts.getOrElse(index) { 0 }
        val right = currentParts.getOrElse(index) { 0 }
        if (left != right) return left > right
    }
    return false
}

private fun splitVersionParts(raw: String): List<Int> =
    VERSION_TOKEN.findAll(raw).map { it.value.toInt() }.toList()

/** 发布资产。`size` 只在 GitHub 返回，Gitee **没有**这个字段 —— 故可空 + 默认值。 */
@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long? = null,
)

/**
 * 发布信息。字段名兼容两端差异：`tag_name` / `assets` / `browser_download_url`
 * 两端一致；其余未知字段由 `ignoreUnknownKeys = true` 吸收（Gitee 与 GitHub 的
 * 字段集合并不完全相同）。
 */
@Serializable
data class ReleaseInfo(
    @SerialName("tag_name") val tagName: String,
    val name: String = "",
    val body: String = "",
    val prerelease: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
)

/** 挑出 APK 产物。资产顺序不保证，按扩展名匹配，别按行号/下标。 */
fun pickApkAsset(assets: List<ReleaseAsset>): ReleaseAsset? =
    assets.firstOrNull { it.name.endsWith(".apk") }

/** 挑出校验清单（两端都叫 `SHA256SUMS.txt`）。 */
fun pickShaSumsAsset(assets: List<ReleaseAsset>): ReleaseAsset? =
    assets.firstOrNull { it.name.endsWith("SHA256SUMS.txt", ignoreCase = true) }

/**
 * 解析 `SHA256SUMS.txt`，按 APK 文件名匹配返回 64 位小写 hex。
 *
 * 格式（两端内容一致）：首行 `#` 注释可跳过；数据行 = `<64位hex>` + 两个空格 +
 * `<APK文件名>`。**按文件名匹配而不是按行号** —— 资产顺序不保证。
 */
fun parseSha256Sums(text: String, apkName: String): String? {
    val wanted = apkName.trim()
    if (wanted.isEmpty()) return null
    return text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split(WHITESPACE)
            if (parts.size >= 2 && parts.last() == wanted && parts.first().matches(SHA256_HEX)) {
                parts.first().lowercase()
            } else {
                null
            }
        }
        .firstOrNull()
}

/** 计算文件的 SHA-256（64 位小写 hex）。分块读，不把整个 APK 读进内存。 */
fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> String.format(Locale.US, "%02x", byte) }
}

/** 校验文件是否匹配期望哈希。失败不抛异常，返回 `false` 让调用方决定是否删文件。 */
fun verifySha256(file: File, expected: String): Boolean =
    sha256Hex(file).equals(expected.trim().lowercase(), ignoreCase = true)

/** 网络/解析失败。带 `httpCode` 以便 UI 区分「限流 403」这类可读提示。 */
class UpdateException(message: String, val httpCode: Int = -1) : Exception(message)

/** 查询指定渠道的最新发布。成功返回 [ReleaseInfo]；失败抛 [UpdateException]。 */
suspend fun checkLatest(channel: ReleaseChannel): ReleaseInfo {
    val request = Request.Builder()
        .url(channel.apiUrl)
        .get()
        .apply { channel.headers.forEach { (name, value) -> header(name, value) } }
        .build()
    return withContext(Dispatchers.IO) {
        DshClient.defaultHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UpdateException("HTTP ${response.code}", response.code)
            }
            val body = response.body?.string().orEmpty()
            runCatching { UPDATE_JSON.decodeFromString(ReleaseInfo.serializer(), body) }
                .getOrElse { throw UpdateException(it.message ?: "发布信息解析失败", -1) }
        }
    }
}

/**
 * 下载 APK 到 [output]，进度通过 [progress] 回调（`(已下载字节, 总字节)`）。
 *
 * `total` 为 `-1` 表示服务器没给 `Content-Length`（Gitee 资产没有 `size` 字段，
 * 下载前无法预估，只能下载后从响应头取）。
 */
suspend fun downloadApk(url: String, output: File, progress: (Long, Long) -> Unit) {
    val request = Request.Builder().url(url).get().build()
    withContext(Dispatchers.IO) {
        output.parentFile?.mkdirs()
        DshClient.defaultHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UpdateException("HTTP ${response.code}", response.code)
            }
            val total = response.body?.contentLength() ?: -1L
            val source = response.body?.byteStream()
                ?: throw UpdateException("响应没有内容", -1)
            source.use { input ->
                output.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        out.write(buffer, 0, count)
                        written += count
                        progress(written, total)
                    }
                }
            }
        }
    }
}

/** 下载文本（校验清单）。 */
suspend fun downloadText(url: String): String {
    val request = Request.Builder().url(url).get().build()
    return withContext(Dispatchers.IO) {
        DshClient.defaultHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UpdateException("HTTP ${response.code}", response.code)
            }
            response.body?.string().orEmpty()
        }
    }
}

/**
 * 构造「打开 APK」的 Intent：经 FileProvider 转成 `content://` URI。
 *
 * `file_paths.xml` 里 `cache-path path="."` 覆盖整个 cache 根，所以放在
 * `cacheDir/updates/` 天然被覆盖，不需要动 provider 配置。
 */
fun buildInstallIntent(context: Context, apkFile: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apkFile)
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** 未授权安装时的系统设置页引导（带 `package:` URI，Android 11 上跳到本 App 的授权项）。 */
fun buildInstallSourcesIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
        data = Uri.parse("package:${context.packageName}")
    }

/**
 * 把发布说明压成一行摘要。
 *
 * 两端的 `body` 都是 Markdown（含 `##` 小标题、加粗、`SHA-256：…` 一行）。界面只给
 * 一小段，所以统一压平、去掉强调符号；超出 [maxLength] 截断并补省略号。
 *
 * 提上来当纯函数是为了单测：截断边界（正好等于、超过一个字符）是最容易写歪的地方。
 */
fun summarizeReleaseNotes(body: String, maxLength: Int = 280): String {
    val flat = body
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\n', ' ')
        .replace("**", "")
        .trim()
    if (flat.length <= maxLength) return flat
    return flat.take(maxLength) + "…"
}

/** 下载进度百分比。`total <= 0`（Gitee 资产没有 `size`，且下载前拿不到）时返回 0，
 * 由界面显示「正在下载…」而不是一个假的 0%。 */
fun downloadProgressPercent(downloaded: Long, total: Long): Int =
    if (total > 0) ((downloaded * 100) / total).coerceIn(0, 100).toInt() else 0


private const val BUFFER_SIZE = 64 * 1024

private val UPDATE_JSON = Json { ignoreUnknownKeys = true }

private val VERSION_TOKEN = Regex("""\d+""")

private val SHA256_HEX = Regex("""[0-9a-fA-F]{64}""")

private val WHITESPACE = Regex("""\s+""")
