package io.github.westanke.dshcompanion

import io.github.westanke.dshcompanion.data.DshApiException
import io.github.westanke.dshcompanion.data.DshClient
import io.github.westanke.dshcompanion.data.SettingsNamespaceList
import io.github.westanke.dshcompanion.data.SettingsOp
import io.github.westanke.dshcompanion.debug.DiagLog

/*
 * ============================================================================
 * 通用设置命名空间：读取与提交
 * ============================================================================
 *
 * 从 `HarnessViewModel.kt` 继续按域拆（与 `HarnessPluginsViewModel.kt` 同一手法）：
 * 成员函数改成扩展函数，调用点写法不变。`HarnessViewModel.kt` 已经贴着 800 行预算，
 * 再往里塞两个方法就会被 `ArchitectureGuardTest` 抓住。
 *
 * ## 桥与 App 分批发版：404/403 不是故障
 *
 * 这条通道是**先有对端、后有此页**：老桥（2.5.x 及更早）根本没有
 * `GET /settings/namespaces`。若照常抛异常，`launchOperation` 会把它变成一条
 * Snackbar，用户每次进设置页都被吼一句「HTTP 404」——而那既不是他做错了什么，
 * 也不是他修得了的。
 *
 * 所以：**404（没有路由）与 403（设备没有 settings.read）都按「入口不可用」处理**，
 * 只往诊断日志写一行，界面让入口消失/置灰。真正该给用户看的错误
 * （503 服务不可用、409 冲突、400 操作不合法）仍然照常抛给统一错误通道。
 */

/** 诊断日志分类。与 SEND 分开，事后查「设置通道当时降级了没」不用翻全量。 */
private const val SETTINGS_LOG = "SETTINGS"

/**
 * 快照里的「设置命名空间」：**读失败不能拖垮整次连接**。
 *
 * 与 `loadSnapshot` 里同一排的 `providerSettings()` / `pluginInventory()` 不同，这条通道是
 * 桥的新端点（老桥 404），而且完全可选：它挂了不影响聊天、文件、会话。若让它抛出去，
 * 整次 `refreshDashboard` 失败，用户连设备都连不上 —— 一个「设置表单没数据」升级成
 * 「App 用不了」，代价完全不对等。
 *
 * 所以就地兜住：任何失败都退化成 null（入口不显示），只留一行诊断日志。
 * 404/403 与其余错误的区分在 [refreshSettingsNamespaces] 里做（那里才是用户主动打开入口的时刻）。
 */
internal fun loadSettingsNamespaces(api: DshClient): SettingsNamespaceList? =
    runCatching { api.settingsNamespaces() }
        .onFailure {
            DiagLog.w(
                SETTINGS_LOG,
                "快照里的 settings/namespaces 读取失败（按不可用处理）：" +
                    "${it::class.java.simpleName}: ${it.message}",
            )
        }
        .getOrNull()

/**
 * 拉一遍命名空间列表。
 *
 * 没有 `settings.read` 时**直接不发请求**（发了也必然 403），并把状态清空 ——
 * 入口据此置灰，而不是显示一份永远读不到的数据。
 */
internal fun HarnessViewModel.refreshSettingsNamespaces() = withClient { api ->
    if ("settings.read" !in mutableState.value.device?.scopes.orEmpty()) {
        update { copy(settingsNamespaces = null) }
        return@withClient
    }
    val loaded = try {
        api.settingsNamespaces()
    } catch (error: DshApiException) {
        if (error.isAbsentOnBridge()) {
            // 正常降级：老桥没这条路由 / 设备缺 scope。入口消失，不弹错误对话框。
            DiagLog.w(
                SETTINGS_LOG,
                "settings/namespaces 不可用（HTTP ${error.status} ${error.code}）：按老桥降级，入口隐藏",
            )
            update { copy(settingsNamespaces = null) }
            return@withClient
        }
        throw error
    }
    update { copy(settingsNamespaces = loaded) }
}

/**
 * 提交一个命名空间的修改。
 *
 * [expectedRevision] 是打开详情那一刻的 revision（乐观并发）：桥发现文档被别处改过会回
 * 409 `SETTINGS_CONFLICT`。这种情况**不把错误码原文丢给用户**，而是当场重拉最新值并提示
 * 「设置已在别处被修改，已刷新」—— 官方的 `conflict` 文案就是这个意思。
 *
 * 成功后**用响应替换列表里的同一个命名空间**，而不是整表重拉：桥的 PATCH 返回的就是
 * 更新后的单对象，重拉整表既慢又会让用户当前展开的详情闪一下。
 */
internal fun HarnessViewModel.updateSettingsNamespace(
    ns: String,
    ops: List<SettingsOp>,
    expectedRevision: Int?,
) {
    if (ops.isEmpty()) return
    withClient { api ->
        if ("settings.write" !in mutableState.value.device?.scopes.orEmpty()) {
            notify(appString(R.string.settings_ns_write_required))
            return@withClient
        }
        val updated = try {
            api.updateSettingsNamespace(ns, ops, expectedRevision)
        } catch (error: DshApiException) {
            when (error.code) {
                "SETTINGS_CONFLICT" -> {
                    // 别处改过：重拉最新值再让用户看（现场刷新），并说清楚发生了什么。
                    val refreshed = runCatching { api.settingsNamespaces() }.getOrNull()
                    update {
                        copy(
                            settingsNamespaces = refreshed ?: settingsNamespaces,
                            error = appString(R.string.settings_ns_conflict),
                        )
                    }
                    return@withClient
                }
                "SETTINGS_NAMESPACE_READONLY" -> {
                    // 桥在「只读」这件事上比 App 的 scope 判定更权威（可能只是这个命名空间只读）。
                    notify(appString(R.string.err_settings_namespace_readonly))
                    return@withClient
                }
                else -> throw error
            }
        }
        update {
            val current = settingsNamespaces
            if (current == null) {
                copy(settingsNamespaces = SettingsNamespaceList(writable = true, namespaces = listOf(updated)))
            } else {
                copy(
                    settingsNamespaces = current.copy(
                        namespaces = current.namespaces.map { if (it.ns == updated.ns) updated else it },
                    ),
                )
            }
        }
        notify(appString(R.string.settings_ns_saved))
    }
}

/**
 * 「桥没有这个端点」的判定：404 路由不存在，403 设备没有 scope。
 *
 * 刻意**不含** 503：服务端装了这条路由但暂时不可用，那是真的故障，该让用户知道。
 */
private fun DshApiException.isAbsentOnBridge(): Boolean =
    status == 404 || status == 403 ||
        code in ABSENT_CODES

private val ABSENT_CODES = setOf("ROUTE_NOT_FOUND", "SCOPE_REQUIRED", "SCOPE_INVALID", "FORBIDDEN")
