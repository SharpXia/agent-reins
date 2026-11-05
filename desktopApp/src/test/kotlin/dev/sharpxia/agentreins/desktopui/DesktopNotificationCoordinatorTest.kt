package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.UiConversation
import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * wp-18 macOS 本地通知规则单测（desktopApp jvmTest）：触发面清单（审批/回复/run 终态）、
 * 归属降级、回复差异面（基线/非当前打开/抑制窗）、reset、osascript 脚本构造与静默降级。
 * 口径与 iOS wp-13（HubStore.noteConversationsPush / noteApprovalRequested / noteRunTerminal
 * + NotificationCoordinator）逐一对应，回归锚 = 桌面↔iOS 通知触发面 parity 不漂移。
 */
class DesktopNotificationCoordinatorTest {

    private class FakeNotifier : DesktopNotificationCoordinator.DesktopNotifier {
        val received = mutableListOf<DesktopNotificationCoordinator.Request>()
        var throwOnNotify = false

        override fun notify(request: DesktopNotificationCoordinator.Request) {
            if (throwOnNotify) throw IllegalStateException("boom")
            received += request
        }
    }

    private fun conv(
        conversationId: String,
        unread: Int,
        label: String = "会话$conversationId",
        agentId: String = "agent-$conversationId",
    ) = UiConversation(
        conversationId = conversationId, gatewayId = "gw1", label = label, agentId = agentId,
        preview = "p", lastSeq = unread.toLong(), lastActiveAt = "t", unread = unread,
        hasActiveRun = false, reconciling = false,
    )

    /** 可控单调时钟（纳秒计），供 3s 抑制窗推进断言。 */
    private class FakeClock {
        var now: Long = 0L
        fun nanos(): Long = now
        fun advanceMillis(ms: Long) { now += ms * 1_000_000 }
    }

    private fun coordinator(
        notifier: FakeNotifier,
        resolver: (String, String) -> String = { _, _ -> "" },
        clock: FakeClock = FakeClock(),
    ) = DesktopNotificationCoordinator(
        notifier = notifier,
        conversationKeyResolver = resolver,
        nowNanos = clock::nanos,
    )

    private fun envelope(
        type: String,
        sessionKey: String? = "sk-1",
        gatewayId: String = "gw1",
        runId: String? = null,
    ) = EventEnvelope(
        id = "01TEST00000000000000000000", seq = 1L, type = type, gatewayId = gatewayId,
        sessionKey = sessionKey, runId = runId, ts = "2026-11-06T00:00:00Z",
        data = kotlinx.serialization.json.buildJsonObject {},
    )

    // ── 信封分类 ──

    @Test
    fun classifyMapsApprovalAndRunTerminal() {
        val c = coordinator(FakeNotifier(), resolver = { _, _ -> "conv-1" })
        assertEquals(
            DesktopNotificationCoordinator.Classified.ApprovalRequested("conv-1"),
            c.classify(envelope(EventEnvelope.APPROVAL_REQUESTED)),
        )
        assertEquals(
            DesktopNotificationCoordinator.Classified.RunTerminal("conv-1", completed = true),
            c.classify(envelope(EventEnvelope.RUN_COMPLETED)),
        )
        assertEquals(
            DesktopNotificationCoordinator.Classified.RunTerminal("conv-1", completed = false),
            c.classify(envelope(EventEnvelope.RUN_FAILED)),
        )
    }

    @Test
    fun classifySkipsAbortedUnknownAndSessionKeylessEnvelopes() {
        val c = coordinator(FakeNotifier(), resolver = { _, _ -> "conv-1" })
        // run.aborted 是用户主动 Stop 的自致结果，不提醒（iOS 同口径）
        assertNull(c.classify(envelope(EventEnvelope.RUN_ABORTED)))
        assertNull(c.classify(envelope(EventEnvelope.MESSAGE_CREATED)))
        // run/approval 域必带 sessionKey；缺失 = 非通知触发面
        assertNull(c.classify(envelope(EventEnvelope.RUN_COMPLETED, sessionKey = null)))
    }

    // ── 审批触发 ──

    @Test
    fun approvalNotifiesWithLabelAndAttribution() {
        val notifier = FakeNotifier()
        val c = coordinator(notifier)
        c.handleConversationsPush(listOf(conv("conv-1", 0, label = "工作群")))
        c.handleEvent(DesktopNotificationCoordinator.Classified.ApprovalRequested("conv-1"))
        val r = notifier.received.single()
        assertEquals(DesktopNotificationCoordinator.Kind.APPROVAL, r.kind)
        assertEquals("conv-1", r.conversationId)
        assertEquals("有待审批", r.title)
        assertEquals("「工作群」有新的审批请求", r.body)
    }

    @Test
    fun approvalNotifiesWithoutAttributionWhenUnmapped() {
        // 未映射会话：审批仍具行动性（设置页待审批清单可见），降级为无归属提醒，不编造会话名
        val notifier = FakeNotifier()
        val c = coordinator(notifier)
        c.handleEvent(DesktopNotificationCoordinator.Classified.ApprovalRequested(""))
        val r = notifier.received.single()
        assertNull(r.conversationId)
        assertEquals("有待审批", r.title)
        assertEquals("有新的审批请求", r.body)
    }

    @Test
    fun approvalNotifiesEvenWhenConversationOpen() {
        // 审批是 run 前置门：当前打开不抑制（iOS 同口径）
        val notifier = FakeNotifier()
        val c = coordinator(notifier).apply { openConversationId = "conv-1" }
        c.handleConversationsPush(listOf(conv("conv-1", 0)))
        c.handleEvent(DesktopNotificationCoordinator.Classified.ApprovalRequested("conv-1"))
        assertEquals(1, notifier.received.size)
    }

    // ── run 终态触发 ──

    @Test
    fun runTerminalNotifiesCompletedAndFailed() {
        val notifier = FakeNotifier()
        val clock = FakeClock()
        val c = coordinator(notifier, clock = clock)
        c.handleConversationsPush(listOf(conv("conv-1", 0), conv("conv-2", 0)))
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("conv-1", completed = true))
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("conv-2", completed = false))
        assertEquals(2, notifier.received.size)
        val completed = notifier.received[0]
        assertEquals(DesktopNotificationCoordinator.Kind.RUN_COMPLETED, completed.kind)
        assertEquals("运行完成", completed.title)
        assertEquals("「会话conv-1」的任务已运行完成", completed.body)
        val failed = notifier.received[1]
        assertEquals(DesktopNotificationCoordinator.Kind.RUN_FAILED, failed.kind)
        assertEquals("运行失败", failed.title)
        assertEquals("「会话conv-2」的任务运行失败", failed.body)
    }

    @Test
    fun runTerminalSilentlyDroppedWhenUnmapped() {
        // 未映射会话：无归属、无法静音判定与跳转，显式降级不提醒（与审批不同，iOS 同纪律）
        val notifier = FakeNotifier()
        val c = coordinator(notifier)
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("", completed = true))
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("", completed = false))
        assertTrue(notifier.received.isEmpty())
    }

    // ── 回复差异面（会话列表 Flow 推送） ──

    @Test
    fun replyDiffNotifiesOnUnreadIncreaseOfKnownCard() {
        val notifier = FakeNotifier()
        val c = coordinator(notifier)
        // 首推送 = 基线（登录快照积压不算「到达」）
        c.handleConversationsPush(listOf(conv("conv-1", 0)))
        assertTrue(notifier.received.isEmpty())
        // unread 增量（assistant final 才增 unread）→ 提醒
        c.handleConversationsPush(listOf(conv("conv-1", 2)))
        val r = notifier.received.single()
        assertEquals(DesktopNotificationCoordinator.Kind.REPLY, r.kind)
        assertEquals("conv-1", r.conversationId)
        assertEquals("有新回复", r.title)
        assertEquals("「会话conv-1」有新的回复", r.body)
    }

    @Test
    fun replyDiffSkipsOpenConversationDecreaseAndUnknownCards() {
        val notifier = FakeNotifier()
        val c = coordinator(notifier).apply { openConversationId = "conv-open" }
        c.handleConversationsPush(listOf(conv("conv-open", 0), conv("conv-closed", 3)))
        assertTrue(notifier.received.isEmpty()) // 基线
        // 当前打开：unread 增量不提醒
        c.handleConversationsPush(listOf(conv("conv-open", 5), conv("conv-closed", 3)))
        assertTrue(notifier.received.isEmpty())
        // 未读回退（对端已读合并）：不算「到达」
        c.handleConversationsPush(listOf(conv("conv-open", 2), conv("conv-closed", 1)))
        assertTrue(notifier.received.isEmpty())
        // 非当前打开 + 增量 → 提醒；同时新入列卡片（快照重建）只记录不提醒
        c.handleConversationsPush(listOf(conv("conv-open", 2), conv("conv-closed", 4), conv("conv-new", 7)))
        assertEquals(1, notifier.received.size)
        assertEquals("conv-closed", notifier.received.single().conversationId)
    }

    @Test
    fun replyDiffLabelFallsBackToAgentThenGeneric() {
        val notifier = FakeNotifier()
        val c = coordinator(notifier)
        c.handleConversationsPush(
            listOf(
                conv("c1", 0, label = "", agentId = "dev-agent"),
                conv("c2", 0, label = "", agentId = ""),
            ),
        )
        c.handleConversationsPush(
            listOf(
                conv("c1", 1, label = "", agentId = "dev-agent"),
                conv("c2", 1, label = "", agentId = ""),
            ),
        )
        assertEquals(2, notifier.received.size)
        assertEquals("「dev-agent」有新的回复", notifier.received[0].body)
        assertEquals("「会话」有新的回复", notifier.received[1].body)
    }

    @Test
    fun runCompletedSuppressesReplyWithinThreeSeconds() {
        val notifier = FakeNotifier()
        val clock = FakeClock()
        val c = coordinator(notifier, clock = clock)
        c.handleConversationsPush(listOf(conv("conv-1", 0), conv("conv-2", 0)))
        // run.completed → 完成通知 + 3s 抑制窗（同一 run 结束，防「完成」+「回复」双响）
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("conv-1", completed = true))
        assertEquals(1, notifier.received.size)
        clock.advanceMillis(1000)
        c.handleConversationsPush(listOf(conv("conv-1", 2), conv("conv-2", 0)))
        assertTrue(notifier.received.size == 1, "窗内回复应被吞")
        // 其他会话不受抑制窗影响
        c.handleConversationsPush(listOf(conv("conv-1", 2), conv("conv-2", 1)))
        assertEquals(2, notifier.received.size)
        assertEquals(DesktopNotificationCoordinator.Kind.REPLY, notifier.received[1].kind)
        // 窗过后同会话回复正常提醒
        clock.advanceMillis(2500) // 累计 3.5s > 3s
        c.handleConversationsPush(listOf(conv("conv-1", 4), conv("conv-2", 1)))
        assertEquals(3, notifier.received.size)
        assertEquals("conv-1", notifier.received[2].conversationId)
    }

    @Test
    fun runFailedDoesNotSuppressReply() {
        val notifier = FakeNotifier()
        val clock = FakeClock()
        val c = coordinator(notifier, clock = clock)
        c.handleConversationsPush(listOf(conv("conv-1", 0)))
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("conv-1", completed = false))
        clock.advanceMillis(100)
        c.handleConversationsPush(listOf(conv("conv-1", 1)))
        assertEquals(2, notifier.received.size) // 失败通知 + 回复通知都到
    }

    // ── reset（登录态离场） ──

    @Test
    fun resetRebaselinesAndDropsSuppressWindow() {
        val notifier = FakeNotifier()
        val clock = FakeClock()
        val c = coordinator(notifier, clock = clock)
        c.handleConversationsPush(listOf(conv("conv-1", 5)))
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("conv-1", completed = true))
        clock.advanceMillis(500)
        c.handleReset() // 登录态离场重置（生产经 Msg.Reset 由消费协程调用；此处直调验证语义）
        // 重登快照从零重记基线：旧 unread 同值不算「到达」，不误报
        c.handleConversationsPush(listOf(conv("conv-1", 5)))
        assertTrue(notifier.received.size == 1, "重登基线推送不得提醒")
        // 抑制窗随 reset 清掉：窗内（500ms < 3s）到来的回复不被吞
        c.handleConversationsPush(listOf(conv("conv-1", 7)))
        assertEquals(2, notifier.received.size)
        assertEquals(DesktopNotificationCoordinator.Kind.REPLY, notifier.received[1].kind)
    }

    @Test
    fun emptyPushAfterWipeRebaselines() {
        // 登出清库后全空推送 → 基线清零；重登快照带 unread 只记录不提醒
        val notifier = FakeNotifier()
        val c = coordinator(notifier)
        c.handleConversationsPush(listOf(conv("conv-1", 5)))
        c.handleConversationsPush(emptyList())
        c.handleConversationsPush(listOf(conv("conv-1", 5)))
        assertTrue(notifier.received.isEmpty())
    }

    // ── 端到端：eventTap 入口 → 消费协程 → 通知（异步管道冒烟） ──

    @Test
    fun eventTapPipelineEndToEndDeliversNotification() {
        val notifier = FakeNotifier()
        val latch = CountDownLatch(1)
        val recording = object : DesktopNotificationCoordinator.DesktopNotifier {
            override fun notify(request: DesktopNotificationCoordinator.Request) {
                notifier.notify(request)
                latch.countDown()
            }
        }
        val c = DesktopNotificationCoordinator(
            notifier = recording,
            conversationKeyResolver = { g, sk -> if (g == "gw1" && sk == "sk-1") "conv-1" else "" },
            nowNanos = FakeClock()::nanos,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            c.start(scope)
            c.onEventEnvelope(envelope(EventEnvelope.RUN_COMPLETED))
            assertTrue(latch.await(5, TimeUnit.SECONDS), "消费协程未在超时内派发通知")
            assertEquals(1, notifier.received.size)
            assertEquals(DesktopNotificationCoordinator.Kind.RUN_COMPLETED, notifier.received.single().kind)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun throwingNotifierIsSwallowedByHandler() {
        // 静默降级：通知发送面抛异常不得穿透决策层（消费循环另有 runCatching 兜底）
        val notifier = FakeNotifier().apply { throwOnNotify = true }
        val c = coordinator(notifier)
        c.handleConversationsPush(listOf(conv("conv-1", 0)))
        c.handleEvent(DesktopNotificationCoordinator.Classified.ApprovalRequested("conv-1"))
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("conv-1", completed = true))
        c.handleConversationsPush(listOf(conv("conv-1", 3)))
        assertTrue(notifier.received.isEmpty())
    }

    // ── osascript 封装 ──

    @Test
    fun appleScriptEscapesQuotesAndBackslashes() {
        assertEquals(
            "display notification \"「x \\\"y\\\"」有新的回复\" with title \"有新回复\" sound name \"default\"",
            OsascriptNotifier.buildAppleScript("有新回复", "「x \"y\"」有新的回复"),
        )
        assertEquals(
            "display notification \"C:\\\\path\" with title \"t\" sound name \"default\"",
            OsascriptNotifier.buildAppleScript("t", "C:\\path"),
        )
    }

    @Test
    fun osascriptFailurePathNeverThrows() {
        // 不存在的 osascript 路径：启动失败被吞（静默降级，验收硬要求）
        val notifier = OsascriptNotifier(osascriptPath = "/nonexistent/osascript-wp-18-probe")
        notifier.notify(
            DesktopNotificationCoordinator.Request(
                kind = DesktopNotificationCoordinator.Kind.REPLY,
                conversationId = "conv-1",
                title = "t",
                body = "b",
            ),
        )
    }
}
