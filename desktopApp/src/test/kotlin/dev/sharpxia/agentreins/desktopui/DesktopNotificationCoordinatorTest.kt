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
 * 归属降级、回复差异面（基线/非当前打开；2026-11-07 起 run 完成不再推送、抑制窗已移除）、
 * reset、osascript 脚本构造与静默降级。
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
        mutedIds: () -> Set<String> = { emptySet() },
        enabled: () -> Boolean = { true },
    ) = DesktopNotificationCoordinator(
        notifier = notifier,
        conversationKeyResolver = resolver,
        mutedConversationIds = mutedIds,
        notificationsEnabled = enabled,
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
            DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "conv-1", completed = true, runId = null),
            c.classify(envelope(EventEnvelope.RUN_COMPLETED)),
        )
        assertEquals(
            DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "conv-1", completed = false, runId = null),
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
    fun runTerminalCompletedSilentFailedNotifies() {
        // 2026-11-07 用户拍板：run 完成不再推送（heartbeat 周期 run 的完成通知是噪声，实际
        // 回复由 unread 增量路径承载）；失败仍提醒（异常需要知道）。
        val notifier = FakeNotifier()
        val c = coordinator(notifier)
        c.handleConversationsPush(listOf(conv("conv-1", 0), conv("conv-2", 0)))
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "conv-1", completed = true, runId = null))
        assertTrue(notifier.received.isEmpty(), "completed 不再推送")
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "conv-2", completed = false, runId = null))
        assertEquals(1, notifier.received.size)
        val failed = notifier.received.single()
        assertEquals(DesktopNotificationCoordinator.Kind.RUN_FAILED, failed.kind)
        assertEquals("运行失败", failed.title)
        assertEquals("「会话conv-2」的任务运行失败", failed.body)
    }

    @Test
    fun runTerminalSilentlyDroppedWhenUnmapped() {
        // 未映射会话：无归属、无法静音判定与跳转，显式降级不提醒（与审批不同，iOS 同纪律）
        val notifier = FakeNotifier()
        val c = coordinator(notifier)
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "", completed = true, runId = null))
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "", completed = false, runId = null))
        assertTrue(notifier.received.isEmpty())
    }

    // ── wp-4 修法二：run 终态「本端发起」门（两端协调器同口径 = RunOriginRegistry.isRunLocal） ──

    @Test
    fun runTerminalSuppressedWhenOriginGateDenies() {
        // 他端发起 / compact 触发：门拒绝 → 静默（未读增量仍由会话列表承载）
        val notifier = FakeNotifier()
        val c = coordinator(notifier)
        c.runOriginGate = { _, _ -> false }
        c.handleConversationsPush(listOf(conv("conv-1", 0)))
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "conv-1", completed = true, runId = "r1"))
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "conv-1", completed = false, runId = "r2"))
        assertTrue(notifier.received.isEmpty())
        // 审批不受发起方门影响（审批仍具行动性，iOS 同口径）
        c.handleEvent(DesktopNotificationCoordinator.Classified.ApprovalRequested("conv-1"))
        assertEquals(1, notifier.received.size)
    }

    @Test
    fun runTerminalCompletedSilentAndReplyNotSuppressed() {
        // 门放行 + completed：完成通知退场，且原 3s 抑制窗已移除——紧随的 assistant final
        // unread 增量必须照常提醒（回归锚：移除抑制窗不得吞真实回复）
        val notifier = FakeNotifier()
        val c = coordinator(notifier)
        c.runOriginGate = { gatewayId, runId -> gatewayId == "gw1" && runId == "r1" }
        c.handleConversationsPush(listOf(conv("conv-1", 0)))
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "conv-1", completed = true, runId = "r1"))
        assertTrue(notifier.received.isEmpty(), "completed 不再推送")
        c.handleConversationsPush(listOf(conv("conv-1", 2)))
        assertEquals(1, notifier.received.size)
        assertEquals(DesktopNotificationCoordinator.Kind.REPLY, notifier.received.single().kind)
    }

    @Test
    fun gateNotSetKeepsLegacyBehavior() {
        // 门未装配（旧装配/单测兼容）：不抑制，仅按归属降级（回归锚：wp-4 前行为不漂移；
        // completed 2026-11-07 起整体静默与门无关，用 failed 验证门未装配时放行）
        val notifier = FakeNotifier()
        val c = coordinator(notifier)
        c.handleConversationsPush(listOf(conv("conv-1", 0)))
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "conv-1", completed = true, runId = "r1"))
        assertTrue(notifier.received.isEmpty(), "completed 整体静默与门无关")
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "conv-1", completed = false, runId = "r2"))
        assertEquals(1, notifier.received.size)
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
    fun runCompletedSilentReplyNotifiesImmediately() {
        // 2026-11-07：run 完成通知退场、3s 抑制窗移除——紧随 completion 的 assistant final
        // unread 增量立即提醒（不吞真实回复）；其他会话回复不受影响
        val notifier = FakeNotifier()
        val c = coordinator(notifier)
        c.handleConversationsPush(listOf(conv("conv-1", 0), conv("conv-2", 0)))
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "conv-1", completed = true, runId = null))
        assertTrue(notifier.received.isEmpty(), "completed 不再推送")
        c.handleConversationsPush(listOf(conv("conv-1", 2), conv("conv-2", 0)))
        assertEquals(1, notifier.received.size)
        assertEquals(DesktopNotificationCoordinator.Kind.REPLY, notifier.received.single().kind)
        assertEquals("conv-1", notifier.received.single().conversationId)
    }

    @Test
    fun runFailedDoesNotSuppressReply() {
        val notifier = FakeNotifier()
        val clock = FakeClock()
        val c = coordinator(notifier, clock = clock)
        c.handleConversationsPush(listOf(conv("conv-1", 0)))
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "conv-1", completed = false, runId = null))
        clock.advanceMillis(100)
        c.handleConversationsPush(listOf(conv("conv-1", 1)))
        assertEquals(2, notifier.received.size) // 失败通知 + 回复通知都到
    }

    // ── reset（登录态离场） ──

    @Test
    fun resetRebaselinesSilently() {
        val notifier = FakeNotifier()
        val c = coordinator(notifier)
        c.handleConversationsPush(listOf(conv("conv-1", 5)))
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "conv-1", completed = true, runId = null))
        c.handleReset() // 登录态离场重置（生产经 Msg.Reset 由消费协程调用；此处直调验证语义）
        // 重登快照从零重记基线：旧 unread 同值不算「到达」，不误报；completed 也不推送
        c.handleConversationsPush(listOf(conv("conv-1", 5)))
        assertTrue(notifier.received.isEmpty(), "重登基线推送不得提醒")
        // 基线后的 unread 增量正常提醒
        c.handleConversationsPush(listOf(conv("conv-1", 7)))
        assertEquals(1, notifier.received.size)
        assertEquals(DesktopNotificationCoordinator.Kind.REPLY, notifier.received.single().kind)
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
            c.onEventEnvelope(envelope(EventEnvelope.RUN_FAILED))
            assertTrue(latch.await(5, TimeUnit.SECONDS), "消费协程未在超时内派发通知")
            assertEquals(1, notifier.received.size)
            assertEquals(DesktopNotificationCoordinator.Kind.RUN_FAILED, notifier.received.single().kind)
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
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "conv-1", completed = true, runId = null))
        c.handleConversationsPush(listOf(conv("conv-1", 3)))
        assertTrue(notifier.received.isEmpty())
    }

    // ── wp-9：按会话静音（吞回复/终态、不吞审批） + 通知总开关 ──

    @Test
    fun mutedConversationSwallowsReplyAndRunTerminalButNotApproval() {
        // wp-9 验收②核心：静音会话不再弹回复/完成通知但审批仍弹（失败终态同吞）
        val notifier = FakeNotifier()
        var muted = setOf("conv-1")
        val c = coordinator(notifier, mutedIds = { muted })
        c.handleConversationsPush(listOf(conv("conv-1", 0), conv("conv-2", 0))) // 基线
        c.handleConversationsPush(listOf(conv("conv-1", 2), conv("conv-2", 0)))
        assertTrue(notifier.received.isEmpty(), "静音会话的回复通知应被吞")
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "conv-1", completed = true, runId = null))
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "conv-1", completed = false, runId = null))
        assertTrue(notifier.received.isEmpty(), "静音会话的运行终态通知（完成/失败）应被吞")
        // 审批不受静音影响（run 前置门，出现即需注意）
        c.handleEvent(DesktopNotificationCoordinator.Classified.ApprovalRequested("conv-1"))
        assertEquals(1, notifier.received.size)
        assertEquals(DesktopNotificationCoordinator.Kind.APPROVAL, notifier.received.single().kind)
        // 未静音会话的回复照常（静音只作用于命中会话）
        c.handleConversationsPush(listOf(conv("conv-1", 3), conv("conv-2", 1)))
        assertEquals(2, notifier.received.size)
        assertEquals(DesktopNotificationCoordinator.Kind.REPLY, notifier.received[1].kind)
        assertEquals("conv-2", notifier.received[1].conversationId)
    }

    @Test
    fun unmutingRestoresReplyNotificationsLive() {
        // 静音集为决策时实时读取：设置页/右键解除静音后，下一条回复即时恢复提醒
        val notifier = FakeNotifier()
        var muted = setOf<String>("conv-1")
        val c = coordinator(notifier, mutedIds = { muted })
        c.handleConversationsPush(listOf(conv("conv-1", 0)))
        c.handleConversationsPush(listOf(conv("conv-1", 2)))
        assertTrue(notifier.received.isEmpty(), "静音期间回复应被吞")
        muted = emptySet()
        c.handleConversationsPush(listOf(conv("conv-1", 4)))
        assertEquals(1, notifier.received.size)
        assertEquals(DesktopNotificationCoordinator.Kind.REPLY, notifier.received.single().kind)
    }

    @Test
    fun mutedRunTerminalDoesNotBlockLaterReply() {
        // 静音吞终态（completed 现已整体静默，与抑制窗无关）：静音解除后回复照常提醒
        val notifier = FakeNotifier()
        var muted = setOf("conv-1")
        val c = coordinator(notifier, mutedIds = { muted })
        c.handleConversationsPush(listOf(conv("conv-1", 0)))
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "conv-1", completed = true, runId = null))
        assertTrue(notifier.received.isEmpty())
        muted = emptySet()
        c.handleConversationsPush(listOf(conv("conv-1", 1)))
        assertEquals(1, notifier.received.size)
        assertEquals(DesktopNotificationCoordinator.Kind.REPLY, notifier.received.single().kind)
    }

    @Test
    fun masterSwitchOffSilencesAllKindsIncludingApproval() {
        // 总开关关闭 = notify 单点门放空全部 kind（含审批）；打开后下一条通知即时恢复
        val notifier = FakeNotifier()
        var enabled = false
        val c = coordinator(notifier, enabled = { enabled })
        c.handleConversationsPush(listOf(conv("conv-1", 0)))
        c.handleConversationsPush(listOf(conv("conv-1", 2))) // 回复
        c.handleEvent(DesktopNotificationCoordinator.Classified.RunTerminal("gw1", "conv-1", completed = true, runId = null))
        c.handleEvent(DesktopNotificationCoordinator.Classified.ApprovalRequested("conv-1")) // 审批也吞
        assertTrue(notifier.received.isEmpty(), "总开关关闭应放空全部通知")
        enabled = true
        c.handleEvent(DesktopNotificationCoordinator.Classified.ApprovalRequested("conv-1"))
        assertEquals(1, notifier.received.size)
        assertEquals(DesktopNotificationCoordinator.Kind.APPROVAL, notifier.received.single().kind)
    }

    @Test
    fun masterSwitchOffBeatsUnmutedReplyAndMuteSetStaysEffective() {
        // 组合面：总开关关 + 未静音 → 吞；总开关开 + 静音 → 吞（两个门独立）
        val notifier = FakeNotifier()
        var enabled = false
        var muted = setOf<String>()
        val c = coordinator(notifier, mutedIds = { muted }, enabled = { enabled })
        c.handleConversationsPush(listOf(conv("conv-1", 0)))
        c.handleConversationsPush(listOf(conv("conv-1", 2)))
        assertTrue(notifier.received.isEmpty())
        enabled = true; muted = setOf("conv-1")
        c.handleConversationsPush(listOf(conv("conv-1", 4)))
        assertTrue(notifier.received.isEmpty(), "总开关开但静音命中仍应吞")
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
