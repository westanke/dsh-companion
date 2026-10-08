package io.github.hakunm.deepseekharness.data

/**
 * 智能体正在运行时，提交一条消息应当走哪条路。
 *
 * DSH 的 `prompt` 只认两种 `mode`：
 * - `queue` —— 排队，等当前这一轮结束后再处理；
 * - `steer` —— 插话，作为追加引导送进**正在运行**的那一轮。
 *
 * 空闲时两者没有区别（没有正在运行的一轮可插话），所以空闲一律用 [QUEUE]。
 */
enum class BusySendMode {
    QUEUE,
    STEER;

    /** 另一个模式。Cmd/Ctrl+Enter 走的就是它。 */
    fun opposite(): BusySendMode = if (this == QUEUE) STEER else QUEUE
}

/**
 * 决定「按下去之后发什么」的纯逻辑。
 *
 * 抽成独立对象而不是写在 Composer 里，是因为这段判断有几个容易搞错的组合
 * （空闲、繁忙+默认、繁忙+反向、没有正在运行的会话），而这些组合值得被单测覆盖 ——
 * 埋在一个 1900 行的 Compose 文件里就只能靠手点来验证了。
 */
object ComposerSendPolicy {
    /**
     * @param busy 当前会话是否正在运行（决定有没有「插话」这个选项）
     * @param preferred 用户设置的「繁忙时默认行为」
     * @param alternate 用户是否按下了备用组合键（Cmd/Ctrl+Enter）
     */
    fun resolve(busy: Boolean, preferred: BusySendMode, alternate: Boolean = false): BusySendMode {
        // 空闲时没有可插话的轮次，`steer` 没有意义，一律按排队提交（等价于普通发送）。
        if (!busy) return BusySendMode.QUEUE
        return if (alternate) preferred.opposite() else preferred
    }

    /** 是否可以提交：内容非空、有写权限、且当前没有等待审批的阻塞。 */
    fun canSubmit(text: String, canWrite: Boolean): Boolean = text.isNotBlank() && canWrite
}
