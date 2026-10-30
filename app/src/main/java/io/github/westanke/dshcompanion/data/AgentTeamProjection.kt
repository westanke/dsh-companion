package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 智能体团队（Agent Teams）的**只读投影**：成员名册、共享任务板、子会话目录。
 *
 * 对齐官方 Web 端 `dsh-experimental-client-ui-agent-team` 的定位 —— 手机端同样
 * 「只旁观、不手动管理」：建成员 / 派任务 / 私信都由 Lead agent 自己持有的模型工具
 * （spawn_teammate / send_message / team_task_*）完成，人只通过给 Lead 发自然语言
 * 间接指挥。因此本文件**只有读模型，没有任何写操作**。
 *
 * 数据来源与 [SessionAnalytics] 完全一致：REST `GET /chat/sessions/{id}/history`
 * 首屏响应体里的 `projections.values`（键名 `agentTeam` / `subagentCatalog`），
 * 不新增连接、不新增端点、桥一行不动。字段形状的权威出处：
 *
 * - 成员 / 任务：官方包 `dsh-experimental-agent-team` 的 `lib/types/types.d.ts`
 *   （`TeamMemberProjection` / `TeamTaskView` / `TeamProjection`）。
 * - 子会话目录：官方包 `dsh-subagent` 的 `lib/types/projection-types.d.ts`
 *   （`SubagentCatalogEntry`）。
 *
 * 桥的合约 fixture（`dsh-remote-bridge/tests/mobile-gateway-ws.spec.ts` 一带）
 * 只塞了 `{id}` 级别的极简对象，所以解析必须逐字段容错：认不出就给占位，绝不崩。
 */

/**
 * 团队成员名册一行（官方 `TeamMemberProjection` + Lead 行特有的 role）。
 *
 * 投影里没有「正在运行 / 空闲」这种活动态 —— 那是 Web 端拿 Session status 叠加出来的，
 * 手机端这版不追，只展示持久生命周期 [phase]。
 */
internal data class TeamMemberRow(
    val id: String,
    val name: String,
    val role: String,
    val phase: String,
    val error: String?,
) {
    val isLead: Boolean get() = role == "lead"
    val isFailed: Boolean get() = phase == "failed"
}

/**
 * 共享任务板一行（官方 `TeamTaskView`，已删除任务不会出现在投影里）。
 *
 * [ownerName] 是任务服务用成员名册 join 出来的人名；投影没有 ownerId（View 层不带），
 * 所以这里也不暴露。[ready] 为 false 通常是 [blockedBy] 里的前置任务还没完成。
 */
internal data class TeamTaskRow(
    val id: String,
    val subject: String,
    val description: String,
    val status: String,
    val blockedBy: List<String>,
    val writeScopes: List<String>,
    val ownerName: String?,
    val ready: Boolean,
)

/** 子会话目录一行（官方 `SubagentCatalogEntry`）。 */
internal data class SubagentRow(
    val id: String,
    val mode: String?,
    val label: String?,
    val createdAt: Long?,
)

/** 整个团队面板的读模型；任何一块缺失就是空表，绝不补假数据。 */
internal data class AgentTeamProjection(
    val members: List<TeamMemberRow>,
    val tasks: List<TeamTaskRow>,
    val failure: String?,
    val subagents: List<SubagentRow>,
) {
    /**
     * 有没有可展示的团队。
     *
     * 注意：只有一个 Lead、零任务时**仍然算团队**（每个普通 root 会话隐式就是一个
     * 一人团队，官方语义如此），面板打开后展示「就你一个光杆司令 + 怎么招兵买马」。
     * 完全没有 agentTeam 投影（老内核 / 非团队会话）才是 null。
     */
    val hasTeam: Boolean get() = members.isNotEmpty()

    val teammateCount: Int get() = members.count { !it.isLead }
    val activeTaskCount: Int get() = tasks.count { it.status == "pending" || it.status == "in_progress" }
}

/**
 * 从 history 投影读出整个团队面板；`agentTeam` 与 `subagentCatalog` 都没有时返回 null。
 *
 * `subagentCatalog` 可能单独存在（跑过一次性子代理但没有团队事件），此时仍返回
 * 非空投影、成员表为空 —— 与 [SessionAnalytics] 的「两计数都 0 就当没有」刻意不同：
 * 统计面板追求不占位，团队面板要把子会话目录这部分价值留住。
 */
internal fun agentTeamProjectionOf(history: ChatHistory?): AgentTeamProjection? {
    val values = history?.projectionValues() ?: return null
    val team = values.objectValue("agentTeam")
    val members = team?.jsonObjectArray("members")?.map { member ->
        TeamMemberRow(
            id = member.stringValue("id").orEmpty(),
            // 名字缺了退回 id（fixture 里就只有 {id:"lead"}），总比一行空白强。
            name = member.stringValue("name") ?: member.stringValue("id").orEmpty(),
            role = member.stringValue("role") ?: "teammate",
            phase = member.stringValue("phase") ?: "active",
            error = member.stringValue("error")?.takeIf(String::isNotBlank),
        )
    }.orEmpty()
    val tasks = team?.jsonObjectArray("tasks")?.map { task ->
        TeamTaskRow(
            id = task.stringValue("id").orEmpty(),
            subject = task.stringValue("subject")?.takeIf(String::isNotBlank)
                ?: task.stringValue("id").orEmpty(),
            description = task.stringValue("description").orEmpty(),
            status = task.stringValue("status") ?: "pending",
            blockedBy = task.stringList("blockedBy"),
            writeScopes = task.stringList("writeScopes"),
            ownerName = task.stringValue("ownerName")?.takeIf(String::isNotBlank),
            // 缺省 true：官方语义里 ready 是「依赖未满足」的显式 false，没有理由默认卡任务。
            ready = (task["ready"]?.jsonPrimitive?.booleanOrNull) ?: true,
        )
    }.orEmpty()
    val subagents = (values["subagentCatalog"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.map { entry ->
            SubagentRow(
                // 桥 fixture 用过 sessionId（合约测试里 `{sessionId:"sub-1"}`），官方类型是 id；
                // 两个都认，优先官方键。
                id = entry.stringValue("id") ?: entry.stringValue("sessionId").orEmpty(),
                mode = entry.stringValue("mode"),
                label = entry.stringValue("label")?.takeIf(String::isNotBlank),
                createdAt = (entry["createdAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
            )
        }
        ?.filter { it.id.isNotBlank() }
        .orEmpty()
    val failure = team?.stringValue("failure")?.takeIf(String::isNotBlank)
    // 一个空壳 agentTeam（members/tasks 都空、也没有 failure）与「没有投影」同等待遇：
    // 面板走 unavailable 空态，不展示三行空荡荡的小标题。failure 单独存在时仍要留住。
    if (members.isEmpty() && tasks.isEmpty() && subagents.isEmpty() && failure == null) return null
    return AgentTeamProjection(
        members = members,
        tasks = tasks,
        failure = failure,
        subagents = subagents,
    )
}

/** 取对象数组字段，元素不是对象时跳过（内核字段随版本演化，不容错就整面板崩）。 */
private fun JsonObject.jsonObjectArray(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** 投影里字符串数组的容错读法：非字符串元素、null 元素一律跳过。 */
private fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.filter(String::isNotBlank)
        .orEmpty()
