package dev.sharpxia.agentreins.openclaw

import dev.sharpxia.agentreins.model.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SessionOverlay：本地 pinned/archived 覆盖层的解析、叠加与更新（M2.6）。 */
class SessionOverlayTest {

    private fun session(id: String, pinned: Boolean = false, archived: Boolean = false, at: Long = 0) =
        Session(id = id, title = id, pinned = pinned, archived = archived, lastActiveAtMs = at)

    @Test
    fun parseEmptyAndGarbageYieldEmpty() {
        assertTrue(SessionOverlay.parse(null).isEmpty())
        assertTrue(SessionOverlay.parse("").isEmpty())
        assertTrue(SessionOverlay.parse("not json").isEmpty())
        assertTrue(SessionOverlay.parse("""{"a": {"pinned": "oops"}}""").isEmpty())
    }

    @Test
    fun serializeRoundtrip() {
        val overlay = mapOf(
            "agent:main:main" to SessionOverlay.Entry(pinned = true),
            "agent:main:telegram:1" to SessionOverlay.Entry(archived = true, pinned = false),
        )
        assertEquals(overlay, SessionOverlay.parse(SessionOverlay.serialize(overlay)))
    }

    @Test
    fun applyOverridesPinnedAndArchived() {
        val base = listOf(session("a", pinned = true), session("b", archived = true))
        val overlay = mapOf(
            "a" to SessionOverlay.Entry(pinned = false),   // 反向覆盖：网关置顶 → 本地取消
            "b" to SessionOverlay.Entry(archived = false), // 反向覆盖：网关归档 → 本地恢复
        )
        val applied = SessionOverlay.apply(base, overlay)
        assertEquals(false, applied.first { it.id == "a" }.pinned)
        assertEquals(false, applied.first { it.id == "b" }.archived)
    }

    @Test
    fun applyEmptyOverlayReturnsBaseUntouched() {
        val base = listOf(session("a"), session("b"))
        assertEquals(base, SessionOverlay.apply(base, emptyMap()))
    }

    @Test
    fun applyReSortsPinnedFirstThenRecency() {
        val base = listOf(
            session("old", at = 100),
            session("new", at = 300),
            session("mid", at = 200),
        )
        val overlay = mapOf(
            "old" to SessionOverlay.Entry(pinned = true),
            "mid" to SessionOverlay.Entry(archived = true),
        )
        val applied = SessionOverlay.apply(base, overlay)
        assertEquals(listOf("old", "new", "mid"), applied.map { it.id })
    }

    @Test
    fun applyIgnoresOverlayForMissingSessions() {
        val base = listOf(session("a"))
        val overlay = mapOf("gone" to SessionOverlay.Entry(pinned = true))
        val applied = SessionOverlay.apply(base, overlay)
        assertEquals(listOf("a"), applied.map { it.id })
    }

    @Test
    fun toggledMergesFieldsAndPersistsLocalIntent() {
        val empty = emptyMap<String, SessionOverlay.Entry>()
        // 首次写入
        val pinned = SessionOverlay.toggled(empty, "a", pinned = true)
        assertEquals(SessionOverlay.Entry(pinned = true), pinned["a"])
        // 叠加第二字段
        val both = SessionOverlay.toggled(pinned, "a", archived = true)
        assertEquals(SessionOverlay.Entry(pinned = true, archived = true), both["a"])
        // 归零回写（非空条目字段仍在——本地意图持久，不随网关回弹）
        val unarchived = SessionOverlay.toggled(both, "a", archived = false)
        assertEquals(SessionOverlay.Entry(pinned = true, archived = false), unarchived["a"])
        assertEquals(1, unarchived.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun toggledRequiresAtLeastOneField() {
        SessionOverlay.toggled(emptyMap(), "a")
    }

    @Test
    fun toggledCreatesEntryForOtherSessionWithoutTouchingExisting() {
        val overlay = SessionOverlay.toggled(emptyMap(), "a", pinned = true)
        val next = SessionOverlay.toggled(overlay, "b", archived = true)
        assertEquals(SessionOverlay.Entry(pinned = true), next["a"])
        assertEquals(SessionOverlay.Entry(archived = true), next["b"])
        assertEquals(2, next.size)
    }
}
