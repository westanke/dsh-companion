package io.github.westanke.dshcompanion.ui.chat

import io.github.westanke.dshcompanion.data.ChatDisplayItem
import io.github.westanke.dshcompanion.data.ChatItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 滚动冻结快照的**合法性不变式**。
 *
 * ## 为什么只有这一条能被机检
 *
 * 冻结逻辑本体是 `@Composable`，它出错的地方是**状态生命周期**
 * （`remember` 首次组合抓到什么、effect 什么时候重跑），JVM 单测跑不到。
 * 但事故链条里可以被机检的那一环很明确：
 *
 * > **空表不许被当成冻结快照交出去。**
 *
 * 真机事故（2026-10-18）：初版 `remember { mutableStateOf(items) }` 在首次组合
 * （历史还没回来、`items` 为空）就把快照固化成空表，而那个同步快照的 effect
 * 只在 `settled` 变化时跑、之后再没跑过 —— 于是**任何一次滚动**
 * （包括贴底跟随自己触发的 `animateScrollToItem`）都会把空表交给 LazyColumn，
 * 列表被清空 → **Compose 把滚动位置重置为 0** → `isAtBottom` 永久 false →
 * 贴底跟随被自己的守卫锁死。
 *
 * 所以这里钉住的是「快照非空」这一条：它不描述全部行为，
 * 但它覆盖了那次事故的**唯一可机检环节**。其余部分靠真机验证（见测试报告）。
 */
class ChatScrollFreezeTest {

    private fun item(kind: ChatItemKind, text: String) = ChatDisplayItem(
        id = "id-$text",
        seq = 1,
        kind = kind,
        body = text,
    )

    @Test
    fun `空表不构成快照 —— 返回 null 而不是空表`() {
        assertNull(
            "空表绝不能被当成冻结快照交出去：那会把 LazyColumn 清空并重置滚动位置。" +
                "这正是 2026-10-18 真机事故的根因。",
            freezeSnapshot(emptyList()),
        )
    }

    @Test
    fun `非空表原样返回同一个实例`() {
        val items = listOf(item(ChatItemKind.USER, "你好"))
        val snap = freezeSnapshot(items)
        assertEquals(1, snap?.size)
        // 同一个实例：冻结是「引用快照」而不是拷贝，省掉每帧一次深拷贝。
        assertSame(items, snap)
    }

    @Test
    fun `快照语义等价于「有内容才冻」`() {
        val one = listOf(item(ChatItemKind.ASSISTANT, "回复"))
        // 单条也算有效快照 —— 判据是「非空」而不是「够长」。
        assertEquals(1, freezeSnapshot(one)?.size)
        assertNull(freezeSnapshot(emptyList()))
    }
}
