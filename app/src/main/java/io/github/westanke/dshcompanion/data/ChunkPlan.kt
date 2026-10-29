package io.github.westanke.dshcompanion.data

/**
 * 分块上传的**纯计划逻辑**：给定文件大小、服务端块大小与已收块，算出要传哪些块。
 *
 * 为什么单独拎出来：这段逻辑（除不尽、越界下标、服务端给 0 块大小、断点续传跳过已收块）
 * 是分块上传里唯一能**脱离网络与 Android 环境**验证的部分。放在 `attachFile` 里
 * 就只能靠真机试，而真机试不出「块大小 0 会除零」这种边角。测试见 `ChunkPlanTest`。
 */
internal data class ChunkPlan(
    /** 实际使用的块大小（服务端给的值，非法时退回契约默认值）。 */
    val chunkSize: Long,
    /** 总块数（0 = 空文件）。 */
    val count: Int,
    /** 还没传的块下标（升序）。断点续传就是「只传这个列表」。 */
    val pending: List<Int>,
) {
    fun offsetOf(index: Int): Long = index.toLong() * chunkSize
}

/**
 * 计算分块计划。
 *
 * - [chunkSize] ≤ 0 时退回 [DEFAULT_UPLOAD_CHUNK_SIZE]：契约说块大小由服务端定，
 *   但一个 0 会让后续每次 `offset = index * chunkSize` 全部重叠成 0，宁可退回默认值。
 * - [alreadyReceived] 里越界或负数下标直接忽略：那是服务端数据不干净，不该让客户端崩。
 * - 空文件（size ≤ 0）返回 `count = 0`：调用方据此直接走 complete，不发任何块。
 */
internal fun planChunks(
    size: Long,
    chunkSize: Long,
    alreadyReceived: Collection<Int> = emptyList(),
): ChunkPlan {
    val effective = if (chunkSize > 0) chunkSize else DEFAULT_UPLOAD_CHUNK_SIZE
    if (size <= 0) return ChunkPlan(effective, 0, emptyList())
    val count = (((size - 1) / effective) + 1).toInt()
    val received = alreadyReceived.filter { it in 0 until count }.toSet()
    return ChunkPlan(effective, count, (0 until count).filterNot { it in received })
}

/** 取第 [index] 块的字节（末块可能短于块大小）。越界返回空数组，由调用方当成计划错误。 */
internal fun chunkSlice(bytes: ByteArray, plan: ChunkPlan, index: Int): ByteArray {
    if (index < 0 || index >= plan.count) return ByteArray(0)
    val start = plan.offsetOf(index)
    if (start >= bytes.size) return ByteArray(0)
    val end = minOf(start + plan.chunkSize, bytes.size.toLong())
    return bytes.copyOfRange(start.toInt(), end.toInt())
}

/**
 * 上传进度的人话摘要（`3/7 块`）。
 *
 * 返回结构化数字而不是拼好的字符串：文案要中英双语（`values/strings.xml` 与
 * `values-en/strings.xml`），在数据层拼死字符串就等于放弃本地化。
 */
internal data class ChunkProgress(val sent: Int, val total: Int) {
    val fraction: Float get() = if (total <= 0) 0f else sent.toFloat() / total
}
