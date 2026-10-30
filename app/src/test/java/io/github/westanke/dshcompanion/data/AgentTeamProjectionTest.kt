package io.github.westanke.dshcompanion.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能团队只读投影解析（[agentTeamProjectionOf]）。
 *
 * 成员 / 任务形状对齐官方 `dsh-experimental-agent-team/lib/types/types.d.ts`，
 * 子会话目录对齐 `dsh-subagent/lib/types/projection-types.d.ts`；
 * 极简对象 `{id:"lead"}` 的容忍度对齐桥合约测试 fixture。
 */
class AgentTeamProjectionTest {

    private fun historyOf(projections: String?): ChatHistory {
        val element = projections?.let { Json.parseToJsonElement(it) }
        return ChatHistory(events = emptyList(), hasMore = false, projections = element)
    }

    private val fullFixture = """
        {"values":{
          "agentTeam":{
            "members":[
              {"id":"root-1","name":"lead","role":"lead","phase":"active"},
              {"id":"sess-2","name":"侦察兵","role":"teammate","phase":"active"},
              {"id":"sess-3","name":"工程兵","role":"teammate","phase":"failed","error":"spawn rejected"}
            ],
            "tasks":[
              {"id":"task-1","revision":1,"subject":"读代码","description":"把桥读完",
               "status":"completed","blockedBy":[],"writeScopes":["bridge/"],
               "ownerName":"侦察兵","ready":true,"writeScopeWarnings":[]},
              {"id":"task-2","revision":2,"subject":"写面板","description":"",
               "status":"in_progress","blockedBy":["task-1"],"writeScopes":[],
               "ownerName":"工程兵","ready":true,"writeScopeWarnings":[]},
              {"id":"task-3","revision":1,"subject":"验收","description":"真机看一眼",
               "status":"pending","blockedBy":["task-2"],"writeScopes":[],
               "ready":false,"writeScopeWarnings":[]}
            ],
            "failure":"failed to spawn"
          },
          "subagentCatalog":[
            {"id":"sub-1","createdAt":1787111700000,"mode":"one-shot","label":"临时调研"},
            {"id":"sub-2","createdAt":1787111800000,"mode":"continuable","label":"长工"}
          ]
        }}
    """.trimIndent()

    @Test
    fun `parses full roster task board and catalog`() {
        val team = agentTeamProjectionOf(historyOf(fullFixture))
        requireNotNull(team)
        assertEquals(3, team.members.size)
        assertTrue(team.members[0].isLead)
        assertEquals("侦察兵", team.members[1].name)
        assertTrue(team.members[2].isFailed)
        assertEquals("spawn rejected", team.members[2].error)

        assertEquals(3, team.tasks.size)
        assertEquals("写面板", team.tasks[1].subject)
        assertEquals(listOf("task-1"), team.tasks[1].blockedBy)
        assertEquals(listOf("bridge/"), team.tasks[0].writeScopes)
        assertEquals("工程兵", team.tasks[1].ownerName)
        assertFalse(team.tasks[2].ready)
        assertEquals(2, team.activeTaskCount)

        assertEquals("failed to spawn", team.failure)
        assertEquals(2, team.subagents.size)
        assertEquals("one-shot", team.subagents[0].mode)
        assertEquals("长工", team.subagents[1].label)
        assertEquals(1787111800000L, team.subagents[1].createdAt)
    }

    /** 桥 fixture 只有 `{id:"lead"}`：名字 / role / phase 全部要有兜底，不能炸也不能空行。 */
    @Test
    fun `tolerates minimal bridge fixture objects`() {
        val team = agentTeamProjectionOf(
            historyOf(
                """{"values":{"agentTeam":{"members":[{"id":"lead"}],"tasks":[{"id":"t1"}]}}}""",
            ),
        )
        requireNotNull(team)
        assertEquals("lead", team.members[0].name)
        assertEquals("teammate", team.members[0].role)
        assertEquals("active", team.members[0].phase)
        assertEquals("t1", team.tasks[0].subject)
        assertEquals("pending", team.tasks[0].status)
        assertTrue(team.tasks[0].ready)
    }

    /** 只有 subagentCatalog（跑过一次性子代理、没有团队事件）仍要展示子会话目录。 */
    @Test
    fun `catalog alone is enough to show the panel`() {
        val team = agentTeamProjectionOf(
            historyOf("""{"values":{"subagentCatalog":[{"sessionId":"sub-1"}]}}"""),
        )
        requireNotNull(team)
        assertFalse(team.hasTeam)
        assertEquals(1, team.subagents.size)
        assertEquals("sub-1", team.subagents[0].id)
    }

    @Test
    fun `no projections and no history yield null`() {
        assertNull(agentTeamProjectionOf(historyOf(null)))
        assertNull(agentTeamProjectionOf(null))
        assertNull(agentTeamProjectionOf(historyOf("""{"values":{}}""")))
        assertNull(
            agentTeamProjectionOf(
                historyOf("""{"values":{"agentTeam":{"members":[],"tasks":[]}}}"""),
            ),
        )
    }

    /** 数组里混进字符串 / null / 缺 id 的脏元素：跳过，不拖垮整个面板。 */
    @Test
    fun `malformed array elements are skipped`() {
        val team = agentTeamProjectionOf(
            historyOf(
                """{"values":{"subagentCatalog":[{"id":"ok"},"junk",null,{"mode":"one-shot"}]}}""",
            ),
        )
        requireNotNull(team)
        assertEquals(1, team.subagents.size)
        assertEquals("ok", team.subagents[0].id)
    }
}
