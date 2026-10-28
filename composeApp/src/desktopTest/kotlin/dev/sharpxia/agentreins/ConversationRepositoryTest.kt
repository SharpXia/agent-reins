package dev.sharpxia.agentreins

import dev.sharpxia.agentreins.model.Session
import dev.sharpxia.agentreins.openclaw.ConversationKind
import dev.sharpxia.agentreins.openclaw.ConversationOverlay
import dev.sharpxia.agentreins.openclaw.ConversationRepository
import dev.sharpxia.agentreins.openclaw.SessionCategory
import dev.sharpxia.agentreins.openclaw.SessionClassifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M4 P1：surface 判别 + conversation 派生（探针实锤的 key 形态作判据）。 */
class ConversationRepositoryTest {

    private fun session(
        id: String,
        unread: Int = 0,
        pinned: Boolean = false,
        lastActiveAtMs: Long? = null,
        preview: String? = null,
        title: String? = null,
        agentId: String? = null,
    ) = Session(
        id = id,
        title = title,
        preview = preview,
        lastActiveAtMs = lastActiveAtMs,
        unread = unread,
        pinned = pinned,
        agentId = agentId,
    )

    // ---- SessionClassifier ----

    @Test
    fun classifyRealGatewayShapes() {
        assertEquals(SessionCategory.MAINLINE, SessionClassifier.classify("agent:main:main"))
        assertEquals(SessionCategory.MAINLINE, SessionClassifier.classify("agent:coding:ar-mvs47pkm"))
        assertEquals(SessionCategory.DERIVED, SessionClassifier.classify("agent:coding:subagent:a4138ea3-18c7"))
        assertEquals(SessionCategory.SYSTEM, SessionClassifier.classify("agent:main:cron:a1a3290b"))
        assertEquals(SessionCategory.SYSTEM, SessionClassifier.classify("agent:main:boot:boot-2026-10-26"))
        assertEquals(SessionCategory.SYSTEM, SessionClassifier.classify("agent:main:dashboard:76ec48c3"))
        assertEquals(SessionCategory.CHANNEL, SessionClassifier.classify("agent:main:telegram:it:direct:5716197848"))
        assertEquals(SessionCategory.CHANNEL, SessionClassifier.classify("agent:main:telegram:default:direct:5716197848"))
        assertEquals(SessionCategory.UNKNOWN, SessionClassifier.classify("agent:main:probe-m3"))
        assertEquals(SessionCategory.UNKNOWN, SessionClassifier.classify("agent:main:arbitrary"))
    }

    @Test
    fun channelBucket() {
        assertEquals("telegram", SessionClassifier.channelBucketOf("agent:main:telegram:it:direct:5716197848"))
        assertNull(SessionClassifier.channelBucketOf("agent:main:main"))
    }

    // ---- ConversationRepository ----

    @Test
    fun mainlineConversationCarriesAllMainSessionsOfAgent() {
        val convs = ConversationRepository.build(
            listOf(
                session("agent:main:main", lastActiveAtMs = 100L),
                session("agent:main:ar-aaa", lastActiveAtMs = 300L, unread = 2),
                session("agent:main:ar-bbb", lastActiveAtMs = 200L),
                session("agent:main:subagent:u1", lastActiveAtMs = 400L),
                session("agent:main:cron:x", lastActiveAtMs = 500L),
            ),
        )
        assertEquals(1, convs.size)
        val conv = convs.single()
        assertEquals("conv:main", conv.id)
        assertEquals(ConversationKind.MAINLINE, conv.kind)
        // 成员 = 全部主线 session，最近活跃降序；subagent/cron 不混入
        assertEquals(listOf("agent:main:ar-aaa", "agent:main:ar-bbb", "agent:main:main"), conv.sessionKeys)
        assertEquals(2, conv.unread)
        assertEquals(300L, conv.lastActiveAtMs)
    }

    @Test
    fun channelSessionsAreSeparateConversations() {
        val convs = ConversationRepository.build(
            listOf(
                session("agent:main:main", lastActiveAtMs = 100L),
                session("agent:main:telegram:it:direct:111", lastActiveAtMs = 200L, unread = 1),
                session("agent:main:telegram:default:direct:111", lastActiveAtMs = 300L),
            ),
        )
        assertEquals(2, convs.size)
        val channel = convs.first { it.kind == ConversationKind.CHANNEL }
        assertEquals("conv:main:telegram", channel.id)
        assertEquals("telegram", channel.channel)
        assertEquals(2, channel.sessionKeys.size)
        assertEquals(1, channel.unread)
    }

    @Test
    fun previewComesFromLatestMember() {
        val convs = ConversationRepository.build(
            listOf(
                session("agent:main:main", lastActiveAtMs = 100L, preview = "旧会话尾巴"),
                session("agent:main:ar-new", lastActiveAtMs = 900L, preview = "最新消息"),
            ),
        )
        assertEquals("最新消息", convs.single().preview)
    }

    @Test
    fun unknownSurfaceStaysOutOfHome() {
        val convs = ConversationRepository.build(listOf(session("agent:main:probe-m3", lastActiveAtMs = 1L)))
        assertTrue(convs.isEmpty())
    }

    @Test
    fun sortedPinnedFirstThenActivity() {
        val convs = ConversationRepository.build(
            listOf(
                session("agent:main:main", lastActiveAtMs = 900L),
                session("agent:b:main", lastActiveAtMs = 800L),
            ),
            overlay = mapOf("conv:b" to ConversationOverlay.Entry(pinned = true)),
        )
        assertEquals(listOf("conv:b", "conv:main"), convs.map { it.id })
    }

    @Test
    fun overlayAppliesHiddenAndArchived() {
        val convs = ConversationRepository.build(
            listOf(session("agent:main:main", lastActiveAtMs = 1L)),
            overlay = mapOf("conv:main" to ConversationOverlay.Entry(hidden = true)),
        )
        assertTrue(convs.single().hidden)
    }

    @Test
    fun overlayToggledRequiresField() {
        val overlay = ConversationOverlay.toggled(emptyMap(), "conv:main", pinned = true)
        assertEquals(true, overlay["conv:main"]?.pinned)
        val updated = ConversationOverlay.toggled(overlay, "conv:main", hidden = true)
        assertEquals(true, updated["conv:main"]?.pinned)
        assertEquals(true, updated["conv:main"]?.hidden)
    }

    @Test
    fun mainlineKeysOfOrdersForAnchoring() {
        val sessions = listOf(
            session("agent:main:main", lastActiveAtMs = 100L, agentId = "main"),
            session("agent:main:ar-a", lastActiveAtMs = 300L, agentId = "main"),
            session("agent:main:subagent:u", lastActiveAtMs = 999L, agentId = "main"),
        )
        assertEquals(listOf("agent:main:ar-a", "agent:main:main"), ConversationRepository.mainlineKeysOf("main", sessions))
    }

    @Test
    fun roundTripOverlaySerialization() {
        val overlay = ConversationOverlay.toggled(emptyMap(), "conv:main", archived = true)
        val parsed = ConversationOverlay.parse(ConversationOverlay.serialize(overlay))
        assertEquals(true, parsed["conv:main"]?.archived)
    }
}
