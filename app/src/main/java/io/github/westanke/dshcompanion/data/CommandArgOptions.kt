package io.github.westanke.dshcompanion.data

/**
 * `/model` 与 `/permission` 的**二级菜单**（选一个值，而不是让用户手敲）。
 *
 * ## 为什么这两个命令特殊
 *
 * 桥的 `/ws/mobile` 网关把「命令的二级菜单」做成了两个独立帧
 * （`src/host/ws-commands.ts`：`command-options` / `command-select`），并且明写
 * 「目前只服务 `model` 与 `permission`」——其余命令的 `ui.kind` 是 `immediate` 或 `input`，
 * 没有可选项。
 *
 * 但 REST 的 `GET /chat/sessions/{id}/commands` **只返回 name/description/input.hint**，
 * 不返回选项（`src/host/chat-adapter.ts:1084-1088`）。所以 App 侧要复原同一份体验，
 * 只能自己拼选项 —— 而这两份数据本来就都在公开 REST 上：
 *
 * | 命令 | 选项来源 | 提交方式（与桥的 command-select 同语义） |
 * |---|---|---|
 * | `permission` | 会话投影 `permissions`（候选 + 当前值，已由 [permissionSelect] 解析） | `POST /commands` 发 `/permission <值>` |
 * | `model` | `GET /chat/sessions/{id}/models`（分组 + 当前） | `PUT /chat/sessions/{id}/models`（桥侧走 selectModel RPC，不是斜杠命令） |
 *
 * **判据不猜版本**：只要会话投影里有 `permissions`、或 `/models` 返回了分组，菜单就出得来；
 * 拿不到就是空列表、面板不显示，用户退回手敲 —— 不报错、不装死。
 */

/** 用户停在 `/xxx ` 的参数位时为 true —— 即命令名已确定、参数还没开始打。 */
internal fun isAtCommandArgument(value: String): Boolean {
    val head = value.takeWhile { !it.isWhitespace() }
    if (!head.startsWith('/')) return false
    val name = head.removePrefix("/")
    if (name !in COMMANDS_WITH_ARGUMENT_MENU) return false
    // 必须已经有一个空白分隔、且空白之后还没打参数：
    // 前者区分「选命令」与「选参数」，后者保证用户开始手敲就收起菜单。
    return value.length > head.length && value.substring(head.length).isBlank()
}

/** 参数已经开始了（有非空白内容）时不该再显示菜单，交给普通输入。 */
internal fun commandArgument(value: String): String? {
    val head = value.takeWhile { !it.isWhitespace() }
    if (head.isEmpty() || head.length >= value.length) return null
    return value.substring(head.length).trim().takeIf(String::isNotEmpty)
}

/** 有二级菜单的命令名。与桥的 `ws-commands.ts` 里 `loadOptions` 支持的两个完全一致。 */
internal val COMMANDS_WITH_ARGUMENT_MENU: Set<String> = setOf("model", "permission")

/** 一个可选项。`Permission` 带要提交的档位值；`Model` 带 provider/model 二元组。 */
internal sealed interface CommandArgOption {
    val label: String
    val detail: String?
    val selected: Boolean

    data class Permission(
        val value: String,
        override val label: String,
        override val detail: String?,
        override val selected: Boolean,
    ) : CommandArgOption

    data class Model(
        val provider: String,
        val model: String,
        override val label: String,
        override val detail: String?,
        override val selected: Boolean,
    ) : CommandArgOption
}

/**
 * 当前输入该显示哪一组选项；为空表示不显示面板。
 *
 * 顺序上**先判权限再判模型**属于任意选择，两者互斥（命令名不同），不会同时命中。
 */
internal fun commandArgOptions(
    value: String,
    history: ChatHistory?,
    models: SessionModels?,
    catalog: PermissionCatalog? = null,
): List<CommandArgOption> {
    if (!isAtCommandArgument(value)) return emptyList()
    return when (value.takeWhile { !it.isWhitespace() }.removePrefix("/")) {
        "permission" -> permissionCommandOptions(history, catalog)
        "model" -> modelCommandOptions(models)
        else -> emptyList()
    }
}

internal fun permissionCommandOptions(
    history: ChatHistory?,
    catalog: PermissionCatalog? = null,
): List<CommandArgOption> {
    // 投影里没有内联选项时靠 BFF 的目录兜底（与顶栏 Pill 同源）。
    val select = history?.permissionSelect(catalog) ?: return emptyList()
    return select.options.map { option ->
        CommandArgOption.Permission(
            value = option.value,
            label = option.name,
            detail = option.description,
            selected = option.value == select.currentValue,
        )
    }
}

internal fun modelCommandOptions(models: SessionModels?): List<CommandArgOption> {
    val current = models ?: return emptyList()
    return current.groups.flatMap { group ->
        group.models.map { model ->
            CommandArgOption.Model(
                provider = group.id,
                model = model.id,
                label = model.name.ifBlank { model.id },
                // 分组名当副标题：同名模型跨 provider 时靠它区分（实测存在同名）。
                detail = group.name,
                selected = current.current.provider == group.id && current.current.model == model.id,
            )
        }
    }
}
