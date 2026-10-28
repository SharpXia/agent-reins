package dev.sharpxia.agentreins.ui

import dev.sharpxia.agentreins.model.Message
import dev.sharpxia.agentreins.model.ToolCallEvent
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/** buildChatRows / dayHeaderLabel：日分隔纯函数（M2.6）。时区固定 UTC 保证可复现。 */
class ChatRowsTest {

    private val utc = TimeZone.UTC

    private fun msg(id: String, ts: Long?) = Message(
        id = id, sessionId = "s", role = Message.Role.USER, content = id, timestampMs = ts,
    )

    @Test
    fun emptyEntriesYieldNoRows() {
        assertTrue(buildChatRows(emptyList(), utc).isEmpty())
    }

    @Test
    fun sameDayMessagesGetSingleHeader() {
        // 2026-10-26 10:00 与 15:00 UTC 同日
        val rows = buildChatRows(listOf(msg("a", day(2026, 10, 26, 10)), msg("b", day(2026, 10, 26, 15))), utc)
        assertEquals(
            listOf(ChatRow.DayHeader::class, ChatRow.Entry::class, ChatRow.Entry::class),
            rows.map { it::class },
        )
    }

    @Test
    fun crossDayInsertsHeaderBetween() {
        val rows = buildChatRows(
            listOf(msg("a", day(2026, 10, 25, 23)), msg("b", day(2026, 10, 26, 1))),
            utc,
        )
        assertEquals(4, rows.size)
        assertTrue(rows[0] is ChatRow.DayHeader)
        assertTrue(rows[2] is ChatRow.DayHeader)
        assertEquals("a", (rows[1] as ChatRow.Entry).entry.id)
        assertEquals("b", (rows[3] as ChatRow.Entry).entry.id)
    }

    @Test
    fun nullTimestampAttachesToPreviousGroup() {
        val rows = buildChatRows(
            listOf(msg("a", day(2026, 10, 25, 23)), msg("b", null), msg("c", day(2026, 10, 26, 1))),
            utc,
        )
        // [DayHeader, a, b(null)] [DayHeader, c]——null 时间戳的 b 不打断分组、不产生分隔
        assertEquals(5, rows.size)
        assertTrue(rows[0] is ChatRow.DayHeader && rows[3] is ChatRow.DayHeader)
    }

    @Test
    fun toolCallEntriesCarryNoTimestamp() {
        val tool = ToolCallEvent(id = "t", toolName = "exec")
        val rows = buildChatRows(listOf(msg("a", day(2026, 10, 26, 10)), tool), utc)
        // [DayHeader, a, tool]
        assertEquals(3, rows.size)
        assertTrue(rows[2] is ChatRow.Entry)
    }

    @Test
    fun consecutiveCompletedToolsCollapseIntoGroup() {
        val tools = listOf(
            ToolCallEvent(id = "t1", toolName = "exec", state = ToolCallEvent.State.SUCCEEDED),
            ToolCallEvent(id = "t2", toolName = "exec", state = ToolCallEvent.State.SUCCEEDED),
        )
        val rows = buildChatRows(listOf(msg("a", null)) + tools, utc)
        // [a, ToolGroup(t1,t2)]——null 时间戳无日分隔；≥2 张连续已完成卡成组
        assertEquals(2, rows.size)
        val group = rows[1] as ChatRow.ToolGroup
        assertEquals(listOf("t1", "t2"), group.items.map { it.id })
    }

    @Test
    fun runningToolStaysStandaloneAndBreaksGroup() {
        val rows = buildChatRows(
            listOf(
                ToolCallEvent(id = "t1", toolName = "exec", state = ToolCallEvent.State.SUCCEEDED),
                ToolCallEvent(id = "t2", toolName = "exec", state = ToolCallEvent.State.RUNNING),
                ToolCallEvent(id = "t3", toolName = "exec", state = ToolCallEvent.State.SUCCEEDED),
            ),
            utc,
        )
        // RUNNING 独立成行并打断组：t3 单独一张普通卡
        assertEquals(3, rows.size)
        assertEquals("t2", (rows[1] as ChatRow.Entry).entry.id)
        assertTrue(rows[0] is ChatRow.Entry && rows[2] is ChatRow.Entry)
    }

    @Test
    fun failedToolCountsAsTerminalForGrouping() {
        val rows = buildChatRows(
            listOf(
                ToolCallEvent(id = "t1", toolName = "exec", state = ToolCallEvent.State.SUCCEEDED),
                ToolCallEvent(id = "t2", toolName = "exec", state = ToolCallEvent.State.FAILED),
            ),
            utc,
        )
        // FAILED 也是终态：两张连续终态卡同样成组
        assertEquals(1, rows.size)
        assertEquals(2, (rows[0] as ChatRow.ToolGroup).items.size)
    }

    @Test
    fun messageBreaksToolGrouping() {
        val rows = buildChatRows(
            listOf(
                ToolCallEvent(id = "t1", toolName = "exec", state = ToolCallEvent.State.SUCCEEDED),
                msg("a", null),
                ToolCallEvent(id = "t2", toolName = "exec", state = ToolCallEvent.State.SUCCEEDED),
            ),
            utc,
        )
        // 消息打断贪食段：两张工具卡各自独立
        assertEquals(3, rows.size)
        assertTrue(rows.all { it is ChatRow.Entry })
    }

    @Test
    fun labelsTodayYesterdayAndDate() {
        // today 取当前真实时钟，因此用相对日期断言：昨天-今天 跨日给「昨天」
        val today = Clock.System.now()
            .toLocalDateTime(utc).date
        val yesterday = today.minus(1, kotlinx.datetime.DateTimeUnit.DAY)
        assertEquals("今天", dayHeaderLabel(day(today.year, today.monthNumber, today.dayOfMonth, 12), utc))
        assertEquals(
            "昨天",
            dayHeaderLabel(day(yesterday.year, yesterday.monthNumber, yesterday.dayOfMonth, 12), utc),
        )
    }

    // ---- M4 P3：conversation 拼接时间线 ----

    @Test
    fun conversationRowsOldestFirstWithBoundaryPerSection() {
        val rows = buildConversationRows(
            listOf(
                TimelineSection("agent:main:ar-new", "新会话 10-29", listOf(msg("n1", day(2026, 10, 29, 2)))),
                TimelineSection("agent:main:main", null, listOf(msg("m1", day(2026, 10, 28, 2)))),
            ),
            utc,
        )
        // 渲染序 = 最旧在最上；每区以 Boundary 开头（main 无 label 兜底「常驻对话」）
        assertTrue(rows.first() is ChatRow.Boundary)
        assertEquals("常驻对话", (rows.first() as ChatRow.Boundary).label)
        val labels = rows.filterIsInstance<ChatRow.Boundary>().map { it.label }
        assertEquals(listOf("常驻对话", "新会话 10-29"), labels)
        // 条目顺序：m1（旧区）在 n1（新区）前
        val entryIds = rows.filterIsInstance<ChatRow.Entry>().map { it.entry.id }
        assertEquals(listOf("m1", "n1"), entryIds)
    }

    @Test
    fun boundaryKeysFollowRenderOrder() {
        val rows = buildConversationRows(
            listOf(
                TimelineSection("agent:main:ar-a", "A", emptyList()),
                TimelineSection("agent:main:ar-b", "B", emptyList()),
            ),
            utc,
        )
        assertEquals(
            listOf("agent:main:ar-b", "agent:main:ar-a"),
            rows.filterIsInstance<ChatRow.Boundary>().map { it.sessionKey },
        )
    }

    private fun day(y: Int, m: Int, d: Int, hour: Int): Long =
        kotlinx.datetime.Instant.parse("%04d-%02d-%02dT%02d:00:00Z".format(y, m, d, hour)).toEpochMilliseconds()
}
