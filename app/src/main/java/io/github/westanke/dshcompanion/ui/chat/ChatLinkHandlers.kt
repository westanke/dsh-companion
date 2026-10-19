package io.github.westanke.dshcompanion.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.UriHandler
import io.github.westanke.dshcompanion.HarnessViewModel
import io.github.westanke.dshcompanion.openSessionFile
import io.github.westanke.dshcompanion.R
import io.github.westanke.dshcompanion.debug.DiagLog
import io.github.westanke.dshcompanion.data.ChatLinkTarget

/**
 * 会话里 Markdown 链接的**统一分发器**（从 `ChatDetail.kt` 拆出，职责本就独立）。
 *
 * Markdown 里的链接会被注解成 `LinkAnnotation.Url`，由 Compose 的 Text 通过
 * `LocalUriHandler` 打开。没有这个 provider 时用的是系统默认 handler，它只会把
 * uri 丢给 Intent —— 而消息里的链接大量是**服务端绝对路径**，Intent 处理不了就
 * 静默失败，用户看到的现象是「点了没反应」。
 *
 * 分流规则：
 * - 服务端绝对路径 → `roots/resolve` 正路（`openSessionFile`）；
 * - **相对路径** → 拿**会话 cwd**（`cwdAbsolute`）当基准拼成绝对路径，再走同一条正路。
 *   之前一律「无法定位」是因为那时没有基准；现在基准就在会话数据里，而模型在消息里
 *   引用的相对路径几乎总是相对会话工作目录（用户 2026-10-19 要求能打开）。
 *   拼完仍由 `roots/resolve` 兜底：不存在 / 越界会得到与其他路径一致的错误，
 *   不会打开「别的文件」；
 * - http(s) → 系统浏览器；
 * - 其余（锚点 / 未知 scheme / 没有基准的相对路径）→ 明确告知不支持，绝不猜。
 */
@Composable
internal fun rememberSessionLinkHandler(
    viewModel: HarnessViewModel,
    cwdAbsolute: String?,
    /**
     * 点击服务端路径时要做什么。
     *
     * **不能只调 `openSessionFile`**：那个函数只把文件加载进 state，
     * 而展示它的面板由调用方（ChatDetail）的局部状态控制 —— 只加载不打开面板，
     * 用户看到的就是「点了完全没反应」（2026-10-19 真机实测：
     * `LINK openUri=…` 日志有了、resolve 也成功了，屏幕却一动不动）。
     * 所以把「打开什么」交给调用方，由它一并把面板打开。
     */
    onOpenSessionFile: (String) -> Unit,
): UriHandler {
    val context = LocalContext.current
    // 提示文案在组合期取（跟随界面语言重组），handler 依赖这些字符串而非 context：
    // 切语言时字符串变化 → handler 重建，回调里不会用到旧 Configuration 的语言。
    val linkOpenFailed = stringResource(R.string.link_open_failed)
    val linkRelativePath = stringResource(R.string.link_relative_path)
    val linkAnchorUnsupported = stringResource(R.string.link_anchor_unsupported)
    val linkSchemeUnsupported = stringResource(R.string.link_scheme_unsupported)
    return remember(
        linkOpenFailed, linkRelativePath, linkAnchorUnsupported, linkSchemeUnsupported,
        cwdAbsolute, onOpenSessionFile,
    ) {
        object : UriHandler {
            override fun openUri(uri: String) {
                // 入口先记一条：真机上「点链接没反应」时，第一步要分清
                // 「handler 没被调用」还是「调用了但处理失败」——
                // 2026-10-19 排查交付物链接就是这么二分出来的。
                DiagLog.i("LINK", "openUri=$uri")
                when (val target = ChatLinkTarget.classify(uri)) {
                    is ChatLinkTarget.ServerPath -> onOpenSessionFile(target.path)

                    is ChatLinkTarget.Web -> openWeb(context, target.url, linkOpenFailed)

                    is ChatLinkTarget.Unsupported -> when {
                        // 相对路径：拿会话 cwd 当基准拼成绝对路径，走同一条正路。
                        target.reason == ChatLinkTarget.Reason.RELATIVE_PATH && cwdAbsolute != null -> {
                            val joined = if (cwdAbsolute.endsWith("/")) {
                                cwdAbsolute + target.raw.trimStart('/')
                            } else {
                                "$cwdAbsolute/${target.raw.trimStart('/')}"
                            }
                            onOpenSessionFile(joined)
                        }

                        else -> toast(
                            context,
                            when (target.reason) {
                                // 没有会话 cwd（老 bridge / 会话未同步）时才说「无法定位」，
                                // 并指一条出路（去「本会话的文件」）。
                                ChatLinkTarget.Reason.RELATIVE_PATH, ChatLinkTarget.Reason.EMPTY ->
                                    linkRelativePath
                                ChatLinkTarget.Reason.ANCHOR ->
                                    linkAnchorUnsupported
                                ChatLinkTarget.Reason.UNKNOWN_SCHEME ->
                                    linkSchemeUnsupported
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun openWeb(context: Context, url: String, linkOpenFailed: String) {
    val opened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.isSuccess
    if (!opened) toast(context, linkOpenFailed)
}
