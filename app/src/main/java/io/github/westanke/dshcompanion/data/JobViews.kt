package io.github.westanke.dshcompanion.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 后台任务（bridge ≥ 2.4.3 的 `GET/POST /chat/sessions/{sessionId}/jobs/...`）。
 *
 * 数据源与形状依据见姊妹仓 `dsh-remote-bridge`：服务端已逐字段校验过内核
 * `JobView` 的类型（缺 id/kind/label 或未知 status 会在服务端就转成 502，
 * 不会透过来），所以这里的字段按「服务端保证过」的口径写。
 *
 * 三条 wire 约定客户端必须知道：
 * - `owner` / `progress` / `detail` / `finishedAt` 是**可选字段**：内核没给就是没有
 *   （无主任务没有 owner、存活中没有 finishedAt），客户端不该看到 `owner: undefined`；
 * - `JobOutput.next` 是**绝对字节偏移**，增量续读时把它当 `from` 再调即可；
 * - `JobOutput.lossy = true` 表示「请求偏移与返回内容之间有字节被留存策略吃掉」，
 *   这段输出**不是完整的**，必须提示用户，不能当成全部展示。
 *
 * （写注释的教训：上面路径结尾原本写成 jobs 加「斜杠星号」—— Kotlin 块注释
 * 可嵌套，注释文字里出现「斜杠星号」会被当成嵌套层开始，整个文件从那以后
 * 全部错位，报「Unclosed comment」。注释里不写这个两字符序列，
 * 想表达通配路径就用省略号。）
 */

/** 任务生命周期：`running` / `stopping` 存活中，其余为终态。 */
@Serializable
enum class JobStatus {
    @SerialName("running") RUNNING,
    @SerialName("stopping") STOPPING,
    @SerialName("completed") COMPLETED,
    @SerialName("killed") KILLED,
    @SerialName("failed") FAILED,
}

/** 是否处于存活中（可能还在产出）。终态任务不能也不必终止。 */
internal val JobStatus.isLive: Boolean
    get() = this == JobStatus.RUNNING || this == JobStatus.STOPPING

@Serializable
data class JobView(
    val id: String,
    val kind: String,
    /** 生产者给的一行说明（命令行、阶段名……），列表里主要就看它。 */
    val label: String,
    /** 属主会话；无主任务没有这个字段（任何会话都可见）。 */
    val owner: String? = null,
    val status: JobStatus,
    /** 实时进度行（`3/10`、当前阶段）；终态时被清空。 */
    val progress: String? = null,
    /** 终态原因（`exit code: 3`）；人工取消的原因会并进来。 */
    val detail: String? = null,
    /** 注册时刻（epoch ms）。 */
    val startedAt: Long,
    /** 落定时刻（epoch ms）；存活中为 null。 */
    val finishedAt: Long? = null,
)

@Serializable
internal data class JobListEnvelope(val jobs: List<JobView>)

/** 一个后台任务已保留的输出。 */
@Serializable
data class JobOutput(
    val jobId: String,
    val text: String,
    /** 绝对字节偏移；下次增量续读传它。 */
    val next: Long,
    /** true = 这段输出前面有字节被留存策略吃掉了，不是从头开始的完整输出。 */
    val lossy: Boolean,
    /** 读到终态帧时带上；流被提前切断时为 null。 */
    val status: JobStatus? = null,
)

/** 终止结果。 */
@Serializable
data class JobKillResult(
    val jobId: String,
    /**
     * `requested` = 已受理；`already-finished` = 已是终态、无需再杀。
     * 界面应据此后者显示「已结束」而不是「已终止」——两者不是一回事。
     */
    val outcome: String,
)
