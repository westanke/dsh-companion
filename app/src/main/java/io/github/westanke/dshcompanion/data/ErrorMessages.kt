package io.github.westanke.dshcompanion.data

import androidx.annotation.StringRes
import io.github.westanke.dshcompanion.R

/**
 * 出错文案的本地化表：**按错误码查**，语言跟随 App 界面语言。
 *
 * ## 为什么要有它
 *
 * 出错时用户会看到两类原文，两类都不是给人看的：
 * - 插件 REST 层返回的 `error.message` 是**英文**（例如 `The path is outside every
 *   authorized root.`），而 `HarnessViewModel.messageOf` 以前直接拼成
 *   `PATH_OUTSIDE_ROOT: The path is outside every authorized root.`；
 * - 内核失败帧 `chat.turn.failed` 里的 `reason.message` 同样是英文。
 *
 * 于是中文界面下也全是英文加错误码。这里按 `code` 换成对应语言的句子，
 * **不依赖服务端知道 App 用什么语言** —— 语言是本地的事。
 *
 * ## 认不出来怎么办
 *
 * **不硬猜**：返回 `null`，调用方保留原始文本。理由是猜错比露原文更糟 ——
 * 用户至少还能把原文搜一搜或贴给开发者；编一句像模像样的话只会把人带偏。
 *
 * ## 为什么多个码共用一条
 *
 * 74 个码里有一大批是同一个意思的不同分支（`PATH_NOT_DIRECTORY` /
 * `PATH_NOT_FILE` / `WORKSPACE_PATH_NOT_DIRECTORY` 都是「这个路径类型不对」）。
 * 给用户看的是**该怎么办**，不是码的编号，所以这里按语义合并。
 */
@StringRes
fun localizedErrorRes(code: String?): Int? = when (normalizeErrorCode(code)) {
    null -> null

    // ———— 鉴权 · 设备 ————
    "AUTH_REQUIRED", "TOKEN_INVALID" -> R.string.err_auth_required
    "PAIRING_INVALID" -> R.string.err_pairing_invalid
    "DEVICE_NOT_FOUND" -> R.string.err_device_not_found
    "ROOT_FORBIDDEN", "ROOT_INVALID", "ROOT_NOT_FOUND" -> R.string.err_root_unusable
    "SCOPE_REQUIRED", "SCOPE_INVALID", "FORBIDDEN" -> R.string.err_scope_missing
    "ADMIN_LOOPBACK_REQUIRED" -> R.string.err_admin_loopback
    "SETTINGS_READ_ONLY" -> R.string.err_settings_read_only

    // ———— 路径 · 文件 ————
    "PATH_OUTSIDE_ROOT", "PATH_OUTSIDE_ROOTS" -> R.string.err_path_outside_root
    "PATH_NOT_FOUND", "TRASH_NOT_FOUND" -> R.string.err_path_missing
    "PATH_NOT_DIRECTORY", "PATH_NOT_FILE", "WORKSPACE_PATH_NOT_DIRECTORY" -> R.string.err_path_wrong_kind
    "PATH_ALREADY_EXISTS" -> R.string.err_path_exists
    "PATH_INVALID", "PATH_NOT_ABSOLUTE", "PATH_REQUIRED", "MOVE_INVALID", "CREATE_CONDITION_REQUIRED",
    "FILE_TYPE_UNSUPPORTED", "LINK_TRAVERSAL_FORBIDDEN",
    -> R.string.err_path_invalid
    "ETAG_MISMATCH", "ETAG_REQUIRED", "COPY_VERIFY_FAILED", "TRASH_VERIFY_FAILED", "RESTORE_CONFLICT",
    -> R.string.err_file_changed
    // 客户端自己抛的码（App 侧早退点从「静默 return」改成报错之后才有它们）。
    // 不登记的话 localizedError 返回 null、界面显示英文原文 —— 而「先选一个授权根」
    // 这句话值得给用户看中文。
    "NO_ROOT_SELECTED" -> R.string.err_no_root_selected

    // ———— 会话 · 聊天 ————
    "SESSION_NOT_FOUND" -> R.string.err_session_missing
    "WORKSPACE_NOT_FOUND" -> R.string.err_workspace_missing
    "SESSION_FORK_OUTSIDE_ROOT", "WORKSPACE_OUTSIDE_ROOT" -> R.string.err_workspace_outside_root
    "SESSION_TITLE_INVALID", "WORKSPACE_TITLE_INVALID" -> R.string.err_title_invalid
    "MESSAGE_EMPTY" -> R.string.err_message_empty
    "COMMAND_NOT_FOUND", "COMMAND_INVALID", "COMMAND_FAILED", "COMMANDS_UNAVAILABLE" -> R.string.err_command
    "APPROVAL_NOT_PENDING" -> R.string.err_approval_stale
    "QUESTION_NOT_PENDING", "QUESTION_NOT_ANSWERABLE", "QUESTION_ANSWER_INVALID", "QUESTION_REPLY_QUEUED",
    "USER_QUESTIONS_UNAVAILABLE",
    -> R.string.err_question_unavailable
    "RATE_LIMITED" -> R.string.err_rate_limited
    "CURSOR_INVALID", "RANGE_INVALID", "NUMBER_INVALID" -> R.string.err_paging

    // ———— 模型 · 供应商 ————
    "MODEL_UNAVAILABLE" -> R.string.err_model_unavailable
    "MODEL_DOES_NOT_SUPPORT_IMAGES" -> R.string.err_model_no_images
    "MODEL_SELECTION_INVALID" -> R.string.err_model_invalid
    "PROVIDER_BASE_URL_REQUIRED", "PROVIDER_ID_INVALID", "PROVIDER_MODELS_REQUIRED",
    "PROVIDER_NOT_CONFIGURABLE", "PROVIDER_NOT_FOUND", "PROVIDER_PROTOCOL_INVALID", "PROVIDER_REQUIRED",
    "PROVIDER_EXISTS", "CUSTOM_PROVIDERS_UNAVAILABLE",
    -> R.string.err_provider
    "AGENT_PRESET_INVALID" -> R.string.err_agent_preset
    "MODEL_CONFIG_INVALID" -> R.string.err_model_invalid

    // ———— 远程访问 · 监听 ————
    "LISTENER_HOST_INVALID", "LISTENER_PORT_INVALID" -> R.string.err_listener_invalid
    "LISTENER_CHANGE_PENDING" -> R.string.err_listener_pending
    "REMOTE_ENABLED" -> R.string.err_remote_enabled

    // ———— 账号 ————
    "ACCOUNT_SIGNED_OUT" -> R.string.err_signed_out

    // ———— 其余（请求体 / 协议 / 内部）：给一句人话 + 附上码，便于反馈 ————
    "BODY_INVALID", "JSON_INVALID", "BODY_TOO_LARGE", "METHOD_NOT_ALLOWED", "ROUTE_NOT_FOUND",
    "INTERNAL_ERROR", "HTTP_ERROR",
    -> R.string.err_request_generic

    else -> null
}

/**
 * 归一化错误码：内核有时给 `DSH_SESSION/MODEL_UNAVAILABLE` 这类带前缀的码，
 * 我们只认最后一段；大小写不敏感，空白去掉。
 */
internal fun normalizeErrorCode(code: String?): String? =
    code?.trim()?.takeIf { it.isNotEmpty() }?.substringAfterLast('/')?.uppercase()
