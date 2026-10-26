package dev.sharpxia.agentreins.openclaw

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.sharpxia.agentreins.model.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** sessions.list 行解析 / 分页信封 / 合并与过滤（A6/A7）测试。 */
class SessionListMapperTest {

    private fun row(
        key: String,
        label: String? = null,
        updatedAt: Long? = null,
        pinned: Boolean = false,
        archived: Boolean = false,
        unread: Any? = null,
        kind: String? = null,
    ): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("key", key)
        label?.let { put("label", it) }
        updatedAt?.let { put("updatedAt", it) }
        put("pinned", pinned)
        put("archived", archived)
        when (unread) {
            is Int -> put("unread", unread)
            is Boolean -> put("unread", unread)
        }
        kind?.let { put("kind", it) }
    }

    // ---- 行解析 ----

    @Test
    fun parseRowMapsRichFields() {
        val s = SessionListMapper.parseRow(
            row(
                key = "agent:main:main", label = "Main", updatedAt = 123L,
                pinned = true, archived = true, unread = 3, kind = "main",
            ),
        )!!
        assertEquals("agent:main:main", s.id)
        assertEquals("Main", s.title)
        assertEquals(123L, s.lastActiveAtMs)
        assertTrue(s.pinned)
        assertTrue(s.archived)
        assertEquals(3, s.unread)
        assertEquals("main", s.kind)
    }

    @Test
    fun parseRowFallsBackTitleAndZeroDefaults() {
        val s = SessionListMapper.parseRow(row(key = "agent:coding:subagent:x"))!!
        assertEquals("x", s.title) // label 缺 → key 尾段
        assertEquals(0, s.unread)
        assertFalse(s.pinned)
        assertFalse(s.archived)
        assertNull(s.kind)
    }

    @Test
    fun parseRowUnreadBooleanForm() {
        assertEquals(1, SessionListMapper.parseRow(row(key = "k", unread = true))!!.unread)
        assertEquals(0, SessionListMapper.parseRow(row(key = "k", unread = false))!!.unread)
    }

    @Test
    fun parseRowWithoutKeyRejected() {
        assertNull(SessionListMapper.parseRow(buildJsonObject { put("label", "no-key") }))
    }

    // ---- 分页信封 ----

    @Test
    fun parsePageReadsCursor() {
        val payload = buildJsonObject {
            put(
                "sessions",
                kotlinx.serialization.json.JsonArray(
                    listOf(row(key = "a"), row(key = "b")),
                ),
            )
            put("hasMore", true)
            put("nextOffset", 100)
        }
        val page = SessionListMapper.parsePage(payload)
        assertEquals(2, page.sessions.size)
        assertTrue(page.hasMore)
        assertEquals(100L, page.nextOffset)
    }

    @Test
    fun parsePageLastPageHasNoCursor() {
        val payload = buildJsonObject {
            put("sessions", kotlinx.serialization.json.JsonArray(listOf(row(key = "a"))))
            put("hasMore", false)
        }
        val page = SessionListMapper.parsePage(payload)
        assertFalse(page.hasMore)
        assertNull(page.nextOffset)
    }

    // ---- 合并与排序 ----

    @Test
    fun mergePinnedFirstThenRecency() {
        val fresh = listOf(
            Session(id = "a", lastActiveAtMs = 100),
            Session(id = "b", lastActiveAtMs = 300, pinned = true),
            Session(id = "c", lastActiveAtMs = 200),
        )
        val merged = SessionListMapper.merge(fresh, emptyList())
        assertEquals(listOf("b", "c", "a"), merged.map { it.id })
    }

    @Test
    fun mergeKeepsCachedMissing() {
        // 分页中断时 cached 未覆盖项保留（防丢会话）
        val fresh = listOf(Session(id = "a", lastActiveAtMs = 100))
        val cached = listOf(Session(id = "old", lastActiveAtMs = 50))
        val merged = SessionListMapper.merge(fresh, cached, keepMissing = true)
        assertEquals(listOf("a", "old"), merged.map { it.id })
        // 全量刷新（keepMissing=false）不保留
        assertEquals(listOf("a"), SessionListMapper.merge(fresh, cached, keepMissing = false).map { it.id })
    }

    // ---- 归档过滤 ----

    @Test
    fun visibleForListFiltersArchivedByDefault() {
        val list = listOf(
            Session(id = "a"),
            Session(id = "b", archived = true),
        )
        assertEquals(listOf("a"), SessionListMapper.visibleForList(list).map { it.id })
        assertEquals(listOf("a", "b"), SessionListMapper.visibleForList(list, includeArchived = true).map { it.id })
    }
}
