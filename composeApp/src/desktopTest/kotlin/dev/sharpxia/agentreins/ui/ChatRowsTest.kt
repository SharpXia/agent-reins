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

    private fun day(y: Int, m: Int, d: Int, hour: Int): Long =
        kotlinx.datetime.Instant.parse("%04d-%02d-%02dT%02d:00:00Z".format(y, m, d, hour)).toEpochMilliseconds()
}
