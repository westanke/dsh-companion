package io.github.westanke.dshcompanion

import io.github.westanke.dshcompanion.data.PendingQuestionSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摘题判据：插件推 `answered` / `dismissed` 帧时，就地把它从待答列表里去掉。
 *
 * 为什么不干脆等下一次 `GET /questions`：那两帧是**确定性事实**（题已经不用答了），
 * 等一个网络往返只会让卡片多挂一会儿。电脑端答完后手机上那张卡就该立刻消失。
 */
class RemoveQuestionTest {

    private fun set(callId: String) = PendingQuestionSet(callId = callId, state = "continued")

    @Test
    fun `摘掉指定的那一题`() {
        val list = listOf(set("a"), set("b"), set("c"))
        val out = removeQuestion(list, "b")
        assertEquals(listOf("a", "c"), out.map { it.callId })
    }

    @Test
    fun `callId 对不上时原样返回`() {
        val list = listOf(set("a"), set("b"))
        // 同一个实例返回，避免无意义的重组（卡片不该因为一帧无关通知而闪）
        assertSame(list, removeQuestion(list, "zzz"))
    }

    @Test
    fun `空列表不崩`() {
        val empty = emptyList<PendingQuestionSet>()
        assertSame(empty, removeQuestion(empty, "a"))
        assertTrue(removeQuestion(empty, "a").isEmpty())
    }

    @Test
    fun `重复 callId 一次摘净`() {
        // 理论上同一个 callId 只会出现一次（插件按 callId 去重），
        // 但客户端不该假设对端永远正确。
        val list = listOf(set("a"), set("a"), set("b"))
        assertEquals(listOf("b"), removeQuestion(list, "a").map { it.callId })
    }

    @Test
    fun `viaBridge 默认是 false 且能解析`() {
        // 这个字段由插件新加；老服务端不返回它时必须是默认值而不是解不出来。
        val q = PendingQuestionSet(callId = "x")
        assertEquals(false, q.viaBridge)
        val bridged = PendingQuestionSet(callId = "y", viaBridge = true)
        assertTrue(bridged.viaBridge)
    }
}