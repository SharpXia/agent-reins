package dev.sharpxia.agentreins.openclaw

import dev.sharpxia.agentreins.model.ConversationState
import dev.sharpxia.agentreins.model.Message
import dev.sharpxia.agentreins.model.ToolCallEvent
import dev.sharpxia.agentreins.openclaw.AgentEventParser.AgentStreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 事件解析器单测：fixture 是真实网关录制的 agent 事件帧（本回合 main 会话 exec 工具 +
 * 另一会话 coding subagent 的广播交错），shape 即协议事实（protocol-notes §6）。
 */
class AgentEventParserTest {

    private val fixtureRoot: JsonObject =
        Json.parseToJsonElement(FixtureData.agentEventFramesJson).jsonObject["fixture"]!!.jsonObject
    private val frames: List<JsonObject> = fixtureRoot["frames"]!!.jsonArray.map { it.jsonObject }
    private val mySession: String = fixtureRoot["mySessionKey"]!!.jsonPrimitive.content
    private val myRunId: String = fixtureRoot["myRunId"]!!.jsonPrimitive.content
    private val history: JsonObject = fixtureRoot["history"]!!.jsonObject

    private fun payload(frame: JsonObject): JsonObject = frame["payload"]!!.jsonObject

    private fun replay(sessionKey: String, source: List<JsonObject> = frames): ConversationState =
        source.filter { payload(it).str("sessionKey") == sessionKey }.fold(
            ConversationState(sessionKey),
        ) { state, frame ->
            val event = AgentEventParser.decode(payload(frame))
            if (event == null) state else AgentEventParser.apply(state, event)
        }

    @Test
    fun 本回合事件流归并出一张已完结工具卡片() {
        val state = replay(mySession)

        val tools = state.toolCalls
        assertEquals(1, tools.size, "item 流 start/update/end 应按 toolCallId 聚合为一张卡片")
        val card = tools.single()
        assertEquals("exec", card.toolName)
        assertEquals(ToolCallEvent.State.SUCCEEDED, card.state)
        // 真实计时：item end 帧 startedAt→endedAt；command_output 的 durationMs(451) 不得覆盖
        assertEquals(1792937619197L, card.startedAtMs)
        assertEquals(485L, card.durationMs)
        assertEquals(0, card.exitCode)
        assertEquals("agentreins-m2-fixture-ok", card.resultJson)
        assertTrue(card.title!!.contains("fixture", ignoreCase = true), "本回合 title 不脱敏")

        assertEquals(null, state.activeRunId, "lifecycle end 后 run 应结束")
        val assistants = state.messages.filter { it.role == Message.Role.ASSISTANT }
        assertEquals(1, assistants.size)
        assertEquals("`agentreins-m2-fixture-ok`", assistants.single().content)
    }

    @Test
    fun 事件按itemId幂等重放_结果不变() {
        val once = replay(mySession)
        // 同一序列整体重放一遍（快照式重放的服务端行为），终态必须一致
        val twice = replay(mySession, frames + frames)
        assertEquals(once, twice)

        // run_status/usage 等未消费流解码为 null 不崩、不进状态
        val ignored = frames.count {
            payload(it).str("sessionKey") == mySession && AgentEventParser.decode(payload(it)) == null
        }
        assertEquals(5, ignored, "usage(2)+run_status(3) 不进对话状态")
    }

    @Test
    fun thinking快照重放取最新且不进条目() {
        val base = replay(mySession)
        val state = AgentEventParser.apply(
            AgentEventParser.apply(
                AgentEventParser.apply(base, AgentStreamEvent.Thinking("第一版思考")),
                AgentStreamEvent.Thinking("第一版思考"),
            ),
            AgentStreamEvent.Thinking("第二版思考（全量快照）"),
        )
        assertEquals("第二版思考（全量快照）", state.thinkingText)
        assertEquals(base.entries, state.entries, "thinking 不产生对话条目")
        // fixture 里的 thinking 重放帧（其它会话广播）也走 decode
        assertTrue(frames.any { payload(it).str("stream") == "thinking" && AgentEventParser.decode(payload(it)) != null })
    }

    @Test
    fun 其它会话广播互不污染() {
        val otherKey = frames.map { payload(it).str("sessionKey") }.first { it != mySession }!!
        val otherState = replay(otherKey)
        // coding subagent 回合：3 张 exec 卡（start+end），3 条 preamble commentary
        assertEquals(3, otherState.toolCalls.size)
        assertTrue(otherState.toolCalls.all { it.toolName == "exec" && it.state == ToolCallEvent.State.SUCCEEDED })
        assertEquals(3, otherState.messages.count { it.role == Message.Role.SYSTEM })

        val mine = replay(mySession)
        val otherIds = otherState.entries.map { it.id }.toSet()
        assertTrue(mine.entries.none { it.id in otherIds }, "两会话按 sessionKey 分流，各自归并互不掺杂")
    }

    @Test
    fun commandOutput先于item到达时建立占位卡片() {
        var state = ConversationState(mySession)
        state = AgentEventParser.apply(
            state,
            AgentStreamEvent.CommandOutput("call_x", "out", AgentEventParser.Phase.END, exitCode = 0, durationMs = 100, status = "completed"),
        )
        assertEquals(1, state.toolCalls.size, "output 早到也应建卡，避免丢结果")
        state = AgentEventParser.apply(
            state,
            AgentStreamEvent.ToolItem(
                "tool:call_x", "call_x", "exec", title = null,
                phase = AgentEventParser.Phase.START, startedAtMs = null, endedAtMs = null, status = "running",
            ),
        )
        assertEquals("exec", state.toolCalls.single().toolName)
        assertEquals("out", state.toolCalls.single().resultJson)
    }

    @Test
    fun 历史回填还原参数与结果() {
        val state = AgentEventParser.mapHistory(mySession, history)
        val card = state.toolCalls.single()
        assertEquals("call_b1dcf84bff954ff187c79484", card.id)
        assertEquals("exec", card.toolName)
        assertNotNull(card.argumentsJson, "参数来自 assistant 消息的 toolCall content")
        assertTrue(card.argumentsJson!!.contains("echo agentreins-m2-fixture-ok"))
        assertEquals("agentreins-m2-fixture-ok", card.resultJson)
        assertEquals(ToolCallEvent.State.SUCCEEDED, card.state)
        assertEquals(5, state.messages.count { it.role == Message.Role.USER })
        assertEquals(5, state.messages.count { it.role == Message.Role.ASSISTANT })
        // 本端发出的消息以 runId 为 id，供回显对账
        assertTrue(state.messages.any { it.id == "user-$myRunId" })
    }

    @Test
    fun 历史合并补齐参数且去重回显与同文助理消息() {
        val live = replay(mySession)
        // 模拟 client.send 的乐观回显（fixture 用探针发送，事件态本身没有 user 条目）
        val liveWithEcho = live.copy(
            entries = live.entries + Message(
                id = "${AgentEventParser.LOCAL_ECHO_PREFIX}$myRunId",
                sessionId = mySession,
                role = Message.Role.USER,
                content = "AgentReins M2 fixture 录制：请用 exec 工具执行 `echo agentreins-m2-fixture-ok`，把输出原样告诉我，然后结束。",
            ),
        )
        val merged = AgentEventParser.mergeHistory(liveWithEcho, AgentEventParser.mapHistory(mySession, history))

        val card = merged.toolCalls.single()
        assertEquals(485L, card.durationMs, "事件态计时不得被历史回填抹掉")
        assertNotNull(card.argumentsJson, "历史补齐事件流没有的参数")
        assertEquals("agentreins-m2-fixture-ok", card.resultJson)

        val users = merged.messages.filter { it.role == Message.Role.USER }
        assertEquals(5, users.size, "回显与历史 user 消息按 runId 对账，不重复")
        assertTrue(users.none { it.id.startsWith(AgentEventParser.LOCAL_ECHO_PREFIX) })
        assertEquals(5, merged.messages.count { it.role == Message.Role.ASSISTANT }, "同文助理消息不重复")
    }

    @Test
    fun 历史合并保留历史未覆盖的流式条目() {
        val live = replay(mySession).copy(activeRunId = "still-running")
        val midRunAssistant = Message(
            id = "assistant-live",
            sessionId = mySession,
            role = Message.Role.ASSISTANT,
            content = "流式进行中的文本（transcript 尚未收录）",
            streaming = true,
        )
        val merged = AgentEventParser.mergeHistory(
            live.copy(entries = live.entries + midRunAssistant),
            AgentEventParser.mapHistory(mySession, history),
        )
        assertTrue(merged.entries.contains(midRunAssistant), "transcript 未收录的条目保留")
        assertEquals("still-running", merged.activeRunId)
    }

    // —— 向上翻页懒加载（M2 验收反馈）——

    private fun historyJson(hasMore: Boolean, messages: List<JsonObject>): JsonObject = buildJsonObject {
        put("hasMore", hasMore)
        put("totalMessages", messages.size)
        put("messages", buildJsonArray { messages.forEach { add(it) } })
    }

    private fun assistantMsgJson(id: String, text: String): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", text) })
        })
        put("timestamp", 1792930000000L)
        put("__openclaw", buildJsonObject { put("id", id) })
    }

    @Test
    fun mapHistory读取hasMore与openclaw稳定id() {
        // fixture 的 history 无 hasMore 字段 → false（不误开翻页）
        assertEquals(false, AgentEventParser.mapHistory(mySession, history).hasMoreHistory)

        // 真实网关报文没有顶层 id，消息 id 必须取 __openclaw.id（跨页去重依赖其稳定）
        val page = historyJson(hasMore = true, messages = listOf(assistantMsgJson("oc-id-1", "第一页之前的老消息")))
        val state = AgentEventParser.mapHistory(mySession, page)
        assertEquals(true, state.hasMoreHistory)
        assertEquals("oc-id-1", state.messages.single().id)
    }

    @Test
    fun mergeHistoryOlder前插去重且跟随hasMore() {
        val live = replay(mySession)
        val before = live.entries.size
        // 与 live 重叠一条（boundary）+ 一条全新，验证按 id 去重后前插
        val overlap = live.entries.first()
        val overlapJson = assistantMsgJson(overlap.id, "占位文本（id 与 live 重叠即可命中去重）")
        val olderPage = historyJson(hasMore = false, messages = listOf(overlapJson, assistantMsgJson("oc-id-older", "更早的消息")))
        val merged = AgentEventParser.mergeHistoryOlder(live, AgentEventParser.mapHistory(mySession, olderPage))

        assertEquals(before + 1, merged.entries.size, "重叠条目去重，只前插 1 条")
        assertEquals("更早的消息", (merged.entries.first() as Message).content)
        assertEquals(false, merged.hasMoreHistory, "hasMore 以新页为准")

        // 再翻一页但 hasMore=false：不重复前插旧页条目
        val mergedTwice = AgentEventParser.mergeHistoryOlder(merged, AgentEventParser.mapHistory(mySession, olderPage))
        assertEquals(merged.entries.size, mergedTwice.entries.size, "同页重复应用幂等")
    }
}

// —— 测试内小工具（与 AgentEventParser 私有实现解耦） ——

private fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
