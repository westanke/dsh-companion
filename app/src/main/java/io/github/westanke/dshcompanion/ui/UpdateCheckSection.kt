package io.github.westanke.dshcompanion.ui

import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.westanke.dshcompanion.BuildConfig
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.data.UpdateException
import io.github.westanke.dshcompanion.data.buildInstallIntent
import io.github.westanke.dshcompanion.data.buildInstallSourcesIntent
import io.github.westanke.dshcompanion.data.checkLatest
import io.github.westanke.dshcompanion.data.channelForLanguage
import io.github.westanke.dshcompanion.data.downloadApk
import io.github.westanke.dshcompanion.data.downloadProgressPercent
import io.github.westanke.dshcompanion.data.downloadText
import io.github.westanke.dshcompanion.data.isNewerVersion
import io.github.westanke.dshcompanion.data.parseSha256Sums
import io.github.westanke.dshcompanion.data.pickApkAsset
import io.github.westanke.dshcompanion.data.pickShaSumsAsset
import io.github.westanke.dshcompanion.data.summarizeReleaseNotes
import io.github.westanke.dshcompanion.data.verifySha256
import java.io.File
import kotlinx.coroutines.launch

/**
 * 更新检查的一次性流程状态。
 *
 * 用 sealed interface 而不是几个独立 `var`：下载 / 校验 / 安装是**多步互斥**的流程，
 * 散装布尔很容易写成「下载中 + 已就绪同时为真」。收敛成 sealed 之后 `when` 必须穷尽，
 * 漏掉分支编译就不过 —— 少一个状态比多一个状态难发现得多。
 */
private sealed interface UpdateUiState {
    /** 还没点过「检查更新」。 */
    data object Idle : UpdateUiState
    /** 正在向发布接口请求。 */
    data object Checking : UpdateUiState
    /** 接口说当前就是最新。 */
    data object UpToDate : UpdateUiState

    /** 有新版，还没下载。同时充当「最近一次可用计划」，供下载失败后重试用。 */
    data class Available(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val shaUrl: String,
    ) : UpdateUiState

    /** 下载中。拿不到总长度时 `percent` 保持 0 —— 宁可显示空条，也不显示假的 0%。 */
    data class Downloading(val apkName: String, val percent: Int) : UpdateUiState

    /** 已下载且 SHA-256 校验通过，等用户点「安装」。 */
    data class Ready(val version: String, val apkFile: File) : UpdateUiState

    /** 需要用户去系统设置授权「安装未知来源应用」。 */
    data class NeedInstallPermission(val version: String, val apkFile: File) : UpdateUiState

    /**
     * 失败。[messageRes] 是带占位符的主文案（走资源，保证随界面语言）；[detail] 是
     * 网络 / 校验的原始细节（如 `HTTP 403`），不翻译，拼在主文案后面。
     */
    data class Failed(
        val messageRes: Int,
        val detail: String = "",
    ) : UpdateUiState
}

/**
 * 设置页「关于」里的更新检查区块。
 *
 * ## 状态放哪
 *
 * 放在本 composable 的 `remember` 里，**不进 `HarnessViewModel`**：更新检查不依赖连接、
 * 不写偏好、不是全局状态 —— 它是一次性的本地操作，离开设置页就该清空（下次进来重新点）。
 * 放进 ViewModel 只会让状态多活一次不必要的重组，还把它混进「连接 / 偏好」那一团里。
 *
 * ## 文案为什么在组合期预取
 *
 * 点击回调里再调 `context.getString` 会触发 `LocalContextGetResourceValueCall` lint error
 * —— 它拿不到重组期的配置变化，切语言 / 换主题时不会跟着刷新。所以全部预取成局部 val。
 *
 * ## 失败一律可见
 *
 * 下载或校验失败**绝不静默**：校验失败先删掉已下载文件，再在界面明确报错（见 `Failed` 分支）。
 * 宁可让用户多看一行字，也不能让他以为「已经更新好了」。
 */
@Composable
internal fun UpdateCheckSection(context: Context) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var lastPlan by remember { mutableStateOf<UpdateUiState.Available?>(null) }

    // —— 文案：组合期一次取完，后面所有回调都复用这些局部 val ——
    val currentVersionLabel =
        stringResource(R.string.update_check_current_version, "v${BuildConfig.VERSION_NAME}")
    val latestLabel = stringResource(R.string.update_check_latest)
    val releaseNotesLabel = stringResource(R.string.update_check_release_notes)
    val downloadLabel = stringResource(R.string.update_check_download)
    val readyLabel = stringResource(R.string.update_check_ready)
    val installLabel = stringResource(R.string.update_check_install)
    val permissionRequiredLabel = stringResource(R.string.update_check_permission_required)
    val openSettingsLabel = stringResource(R.string.update_check_open_settings)
    val retryLabel = stringResource(R.string.update_check_retry)

    /** 发起一次检查；有新版则记下「计划」，没新版则提示已是最新。 */
    fun startCheck() {
        state = UpdateUiState.Checking
        scope.launch {
            try {
                // `getApplicationLocales().toLanguageTags()` 的空 tag 表示「未指定 = 跟随系统」，
                // 交给 `effectiveLanguageTag` 回落到系统语言（与设置页三档判据一致）。
                val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                val release = checkLatest(channelForLanguage(tags))
                // 发布物缺 APK 或缺校验清单时**先失败**，不要退化成「不更新」——
                // 静默跳过会让用户以为应用是最新的，而事实是发布物没做好。
                val apk = pickApkAsset(release.assets)
                    ?: throw UpdateException("no .apk asset", httpCode = -1)
                val sums = pickShaSumsAsset(release.assets)
                    ?: throw UpdateException("no SHA256SUMS asset", httpCode = -1)
                if (!isNewerVersion(release.tagName, BuildConfig.VERSION_NAME)) {
                    state = UpdateUiState.UpToDate
                    return@launch
                }
                val plan = UpdateUiState.Available(
                    version = release.tagName,
                    notes = summarizeReleaseNotes(release.body),
                    apkUrl = apk.downloadUrl,
                    apkName = apk.name,
                    shaUrl = sums.downloadUrl,
                )
                lastPlan = plan
                state = plan
            } catch (e: UpdateException) {
                // 403/429 多半是 GitHub 限流：直接告诉用户「等一会儿」，而不是甩一个 HTTP 码。
                state = if (e.httpCode == 403 || e.httpCode == 429) {
                    UpdateUiState.Failed(R.string.update_check_rate_limited)
                } else {
                    UpdateUiState.Failed(R.string.update_check_check_failed, e.message ?: "")
                }
            } catch (e: Exception) {
                state = UpdateUiState.Failed(R.string.update_check_check_failed, e.message ?: "")
            }
        }
    }

    /** 下载 APK → 校验 SHA-256 → 进入「待安装」。失败必删文件、必报错。 */
    fun startDownload(plan: UpdateUiState.Available) {
        state = UpdateUiState.Downloading(plan.apkName, 0)
        scope.launch {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(updatesDir, plan.apkName)
            try {
                downloadApk(plan.apkUrl, target) { downloaded, total ->
                    // 进度回调跑在 IO 线程上，改 Compose 状态必须回主线程。
                    scope.launch {
                        state = UpdateUiState.Downloading(
                            plan.apkName,
                            downloadProgressPercent(downloaded, total),
                        )
                    }
                }
                state = UpdateUiState.Downloading(plan.apkName, 100)

                // 校验：下载 SHA256SUMS.txt → 按 APK 文件名取期望哈希 → 逐字节比对。
                // 校验不过就删掉文件：一个「来源存疑」的 APK 留在缓存里，比没有更危险。
                val sumsText = downloadText(plan.shaUrl)
                val expected = parseSha256Sums(sumsText, plan.apkName)
                    ?: throw UpdateException("no checksum entry", httpCode = -1)
                if (!verifySha256(target, expected)) {
                    throw UpdateException("sha mismatch", httpCode = -1)
                }
                state = UpdateUiState.Ready(plan.version, target)
            } catch (e: Exception) {
                runCatching { target.delete() }
                // 区分「没下下来」（HTTP / 网络）与「下下来了但校验不过」：
                // 前者是传输问题，后者是发布物本身有问题，用户该看到的提示不一样。
                state = if (e is UpdateException && e.httpCode > 0) {
                    UpdateUiState.Failed(R.string.update_check_download_failed, e.message ?: "")
                } else {
                    UpdateUiState.Failed(R.string.update_check_verify_failed)
                }
            }
        }
    }

    /** 调起系统安装器；没授权「安装未知来源应用」则引导去设置页。 */
    fun install(file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            state = UpdateUiState.NeedInstallPermission(lastPlan?.version ?: "", file)
            return
        }
        val launched = runCatching {
            context.startActivity(buildInstallIntent(context, file))
        }.isSuccess
        if (!launched) {
            state = UpdateUiState.Failed(R.string.update_check_install_failed)
        }
    }

    /** 重试：下载 / 校验失败时直接续上次的下载，别把用户打回「重新检查」再点一遍。 */
    fun retry() {
        val plan = lastPlan
        if (plan != null) startDownload(plan) else startCheck()
    }

    // 未授权安装：从系统设置页返回（RESULT_OK）后自动重试调起安装器，
    // 省掉「授权完还要回来再点一次安装」这一步。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK &&
            context.packageManager.canRequestPackageInstalls()
        ) {
            (state as? UpdateUiState.NeedInstallPermission)?.apkFile?.let { install(it) }
        }
    }

    // ── 行：标题 + 当前版本，点击开始检查 ──────────────────────────────
    // 下载中禁用：半途再点一次会开出第二条下载，互相覆盖同一份 APK。
    val downloading = state is UpdateUiState.Downloading
    SettingRow(
        Icons.Outlined.CloudDownload,
        stringResource(R.string.update_check_title),
        currentVersionLabel,
        modifier = Modifier
            .clickable(enabled = !downloading) { startCheck() }
            .padding(bottom = 12.dp),
        trailing = {
            if (state is UpdateUiState.Checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        },
    )

    // ── 详情区：只有离开「还没点过 / 正在检查」之后才显示 ────────────────
    // 用局部 val 而不是直接 `when (state)`：`state` 是 delegated var，Kotlin 不能对它做
    // 智能转换，`when` 分支里就拿不到 `Available` 的具体字段了。
    val uiState: UpdateUiState = state
    when (val current = uiState) {
        is UpdateUiState.Idle, is UpdateUiState.Checking -> Unit

        is UpdateUiState.UpToDate -> {
            Text(
                latestLabel,
                modifier = Modifier.padding(start = 52.dp, bottom = 18.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        is UpdateUiState.Available -> {
            Column(Modifier.padding(start = 52.dp, bottom = 18.dp)) {
                Text(
                    stringResource(R.string.update_check_new_version, current.version),
                    style = MaterialTheme.typography.bodyLarge,
                )
                current.notes.takeIf { it.isNotEmpty() }?.let { notes ->
                    Text(
                        releaseNotesLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                UpdateActionButton(downloadLabel) { startDownload(current) }
            }
        }

        is UpdateUiState.Downloading -> {
            Column(Modifier.padding(start = 52.dp, bottom = 18.dp)) {
                Text(
                    stringResource(
                        R.string.update_check_downloading,
                        current.apkName,
                        current.percent,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 没有总长度（Gitee 资产不带 size）时进度条保持空，而不是显示 0% 的假进度。
                if (current.percent > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .padding(top = 8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((current.percent / 100f).coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }

        is UpdateUiState.Ready -> {
            Column(Modifier.padding(start = 52.dp, bottom = 18.dp)) {
                Text(
                    stringResource(R.string.update_check_new_version, current.version),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    readyLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UpdateActionButton(installLabel) { install(current.apkFile) }
            }
        }

        is UpdateUiState.NeedInstallPermission -> {
            Column(Modifier.padding(start = 52.dp, bottom = 18.dp)) {
                Text(
                    permissionRequiredLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                UpdateActionButton(openSettingsLabel) {
                    runCatching { permissionLauncher.launch(buildInstallSourcesIntent(context)) }
                }
            }
        }

        is UpdateUiState.Failed -> {
            Column(Modifier.padding(start = 52.dp, bottom = 18.dp)) {
                val message = if (current.detail.isEmpty()) {
                    stringResource(current.messageRes)
                } else {
                    stringResource(current.messageRes, current.detail)
                }
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                UpdateActionButton(retryLabel) { retry() }
            }
        }
    }
}

/** 更新区块里的窄操作按钮：圆角小按钮，贴在版本行缩进位置下面。 */
@Composable
private fun UpdateActionButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = CircleShape,
    ) {
        Text(text)
    }
}
