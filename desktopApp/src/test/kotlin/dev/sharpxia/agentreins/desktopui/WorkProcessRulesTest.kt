package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.UiRunPhase
import dev.sharpxia.agentreins.hub.UiToolActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * wp-15 工作过程条判定纯函数回归锚（TimelineScroll/SlashCommandRules 同纪律）：
 * 口径逐项对齐 iOS HubViews.WorkProcessBar（wp-15 desktop parity 验收）。
 */
class WorkProcessRulesTest {

    private fun tool(status: String, name: String = "shell.exec") = UiToolActivity(
        gatewayId = "gw1", runId = "r1", toolCallKey = "tk-$name-$status",
        toolName = name, summary = "ls -la", status = status, firstTs = "t1", lastTs = "t2",
    )

    private fun phase(name: String, detail: String? = null, ts: String = "t") = UiRunPhase(
        gatewayId = "gw1", runId = "r1", phase = name, detail = detail, ts = ts,
    )

    // ── 三态计数（未知状态值保守计入 running） ──

    @Test
    fun toolActivityCountsSeparatesThreeStates() {
        val counts = toolActivityCounts(
            listOf(tool("running"), tool("running"), tool("completed"), tool("failed")),
        )
        assertEquals(2, counts.running)
        assertEquals(1, counts.completed)
        assertEquals(1, counts.failed)
    }

    @Test
    fun unknownStatusCountsAsRunning() {
        // 终态先到丢失面：未知值不虚构终态，保守按在途（iOS default 分支同口径）
        assertEquals(ToolStatusState.Running, toolStatusState("running"))
        assertEquals(ToolStatusState.Running, toolStatusState("weird-state"))
        assertEquals(ToolStatusState.Completed, toolStatusState("completed"))
        assertEquals(ToolStatusState.Failed, toolStatusState("failed"))
        assertEquals(1, toolActivityCounts(listOf(tool("weird-state"))).running)
    }

    // ── 头部计数摘要（零值段省略，段间 " · "） ──

    @Test
    fun countSummaryOmitsZeroSegments() {
        assertEquals(
            "进行中 2 · 完成 1 · 失败 1",
            workCountSummary(ToolActivityCounts(running = 2, completed = 1, failed = 1)),
        )
        assertEquals("完成 3", workCountSummary(ToolActivityCounts(running = 0, completed = 3, failed = 0)))
        assertEquals("", workCountSummary(ToolActivityCounts(running = 0, completed = 0, failed = 0)))
    }

    // ── 阶段帧（末帧即最新）与出现判据 ──

    @Test
    fun latestPhaseFrameTakesLast() {
        assertNull(latestPhaseFrame(emptyList()))
        val p1 = phase("preparing_workspace", ts = "t1")
        val p2 = phase("starting_model", ts = "t2")
        assertEquals(p2, latestPhaseFrame(listOf(p1, p2)))
    }

    @Test
    fun shouldRenderWorkBarRequiresCapturedContent() {
        // 无工具活动且无阶段帧整条不渲染（零噪声，历史会话不虚构）
        assertFalse(shouldRenderWorkBar(emptyList(), emptyList()))
        assertTrue(shouldRenderWorkBar(listOf(tool("running")), emptyList()))
        assertTrue(shouldRenderWorkBar(emptyList(), listOf(phase("starting_model"))))
    }

    @Test
    fun workBarInProgressNeedsRunActiveAndSignal() {
        assertFalse(workBarInProgress(runActive = false, runningCount = 1, hasPhase = true))
        assertFalse(workBarInProgress(runActive = true, runningCount = 0, hasPhase = false))
        assertTrue(workBarInProgress(runActive = true, runningCount = 1, hasPhase = false))
        assertTrue(workBarInProgress(runActive = true, runningCount = 0, hasPhase = true))
    }

    // ── 兜底呈现（不虚构） ──

    @Test
    fun toolDisplayNameFallsBackWithoutInventing() {
        assertEquals("工具调用", toolDisplayName(""))
        assertEquals("shell.exec", toolDisplayName("shell.exec"))
    }

    @Test
    fun phaseDisplayTextCoversClosedSetWithFallback() {
        // 闭集逐值映射（protocol.schema.json ChatEvent status 变体 phase 枚举，iOS phaseText 对齐）
        assertEquals("等待会话状态", phaseDisplayText("waiting_for_state"))
        assertEquals("准备工作区", phaseDisplayText("preparing_workspace"))
        assertEquals("命名工作树", phaseDisplayText("naming_worktree"))
        assertEquals("创建工作树", phaseDisplayText("creating_worktree"))
        assertEquals("运行初始化", phaseDisplayText("running_setup"))
        assertEquals("准备运行环境", phaseDisplayText("provisioning_environment"))
        assertEquals("准备上下文", phaseDisplayText("preparing_context"))
        assertEquals("整理记忆", phaseDisplayText("memory_flushing"))
        assertEquals("启动模型", phaseDisplayText("starting_model"))
        assertEquals("已中止", phaseDisplayText("aborted"))
        // 未知值原样透出（命名替换不改语义）；空串兜底「运行中」
        assertEquals("future_phase", phaseDisplayText("future_phase"))
        assertEquals("运行中", phaseDisplayText(""))
    }
}
