package io.github.hakunm.deepseekharness.ui

import io.github.hakunm.deepseekharness.data.ChatQuestion
import io.github.hakunm.deepseekharness.data.QuestionOption
import io.github.hakunm.deepseekharness.ui.chat.isAnswered
import io.github.hakunm.deepseekharness.ui.chat.buildAnswers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 问答卡的两条纯逻辑：isAnswered（提交按钮能不能点亮）与 buildAnswers（回传什么）。
 *
 * 为什么值得单独测：这两条规则一旦错，**表现是安静的** —— 按钮永远点不亮，
 * 用户只会以为卡了；答案收错了服务端也不会报错，只会把错的答案记下来。
 * 而它们又都是「看着很明显」的规则，改动时最容易顺手改坏。
 */
class QuestionAnswerTest {

    private fun choice(id: String, vararg labels: String) = ChatQuestion(
        id = id,
        question = "q-" + id,
        options = labels.map { QuestionOption(it) },
    )

    private fun freeText(id: String) = ChatQuestion(id = id, question = "q-" + id)

    // ── isAnswered ──

    @Test
    fun choiceQuestionNeedsAtLeastOneSelection() {
        val q = choice("q1", "A", "B")

        assertTrue(isAnswered(q, setOf("A"), ""))
        assertFalse(isAnswered(q, emptySet(), ""))
        // 有选项的题**不看** custom —— 界面也不会给它自由文本框。
        assertFalse(isAnswered(q, emptySet(), "我自己写"))
    }

    @Test
    fun freeTextQuestionOnlyLooksAtCustom() {
        val q = freeText("q1")

        assertTrue(isAnswered(q, emptySet(), "写点什么"))
        // 全是空白不算答 —— 否则提交上去就是一个空答案。
        assertFalse(isAnswered(q, emptySet(), "   \n  "))
        assertFalse(isAnswered(q, emptySet(), ""))
    }

    // ── buildAnswers ──

    @Test
    fun keepsEveryQuestionInOrderAndEchoesTheId() {
        val answers = buildAnswers(
            questions = listOf(choice("q1", "A"), freeText("q2")),
            selected = mapOf("q1" to setOf("A")),
            custom = mapOf("q2" to "补一句"),
        )

        assertEquals(listOf("q1", "q2"), answers.map { it.id })
        assertEquals(listOf("A"), answers[0].selected)
        assertEquals("补一句", answers[1].custom)
    }

    @Test
    fun selectedFollowsTheOptionOrderNotTheClickOrder() {
        // 集合没有顺序，setOf("C","A") 只是「两个都选了」；
        // 回传必须按**选项原顺序**，否则同一份勾选每次提交的顺序都可能不同。
        val answers = buildAnswers(
            questions = listOf(choice("q1", "A", "B", "C")),
            selected = mapOf("q1" to setOf("C", "A")),
            custom = emptyMap(),
        )

        assertEquals(listOf("A", "C"), answers.single().selected)
    }

    @Test
    fun unknownSelectionIsDropped() {
        // 只认这题**真实存在**的 label：界面上不该出现别的值，出现了也不能回传。
        val answers = buildAnswers(
            questions = listOf(choice("q1", "A")),
            selected = mapOf("q1" to setOf("A", "幽灵选项")),
            custom = emptyMap(),
        )

        assertEquals(listOf("A"), answers.single().selected)
    }

    @Test
    fun freeTextQuestionSendsEmptySelectedAndTrimmedCustom() {
        val answers = buildAnswers(
            questions = listOf(freeText("q1")),
            selected = emptyMap(),
            custom = mapOf("q1" to "  前后都有空白  "),
        )

        val answer = answers.single()
        assertTrue(answer.selected.isEmpty())
        assertEquals("前后都有空白", answer.custom)
    }

    @Test
    fun blankFreeTextBecomesEmptyStringSoItIsNotSerialized() {
        // QuestionAnswer.custom 的默认值是空串，而 kotlinx 默认不编码等于默认值的字段 ——
        // 于是「没填自由文本」时请求体里干脆没有 custom 这个键（见 PendingQuestionsTest）。
        val answers = buildAnswers(
            questions = listOf(choice("q1", "A"), freeText("q2")),
            selected = mapOf("q1" to setOf("A")),
            custom = mapOf("q2" to "   "),
        )

        assertEquals(listOf("A"), answers[0].selected)
        assertEquals("", answers[1].custom)
    }
}
