package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.UiRunPhase
import dev.sharpxia.agentreins.hub.UiToolActivity

/**
 * wp-15 工作过程条判定纯函数（desktopTest 回归锚，TimelineScroll/SlashCommandRules 同纪律）。
 * 数据面 = HubClient.observeWorkActivity（投影库 hubToolEvents + hubRunStatus 两表合流快照）；
 * 口径逐项对齐 iOS HubViews.WorkProcessBar（wp-15 验收 parity）：
 * - tool.* 三态计数只覆盖「已捕获」行——断线窗口/入站限流丢弃不补发（06 §10.2），
 *   计数语义是「已捕获 N 次」而非「本次 run 共 N 次」，UI 不承诺完整；
 * - 阶段帧按 ts 升序推送，末帧即最新；run 终态投影清行（无在途阶段语义），
 *   「run 终态清行」由投影保证，UI 只渲染快照不发明第二套状态。
 */

/** 工具行状态呈现三态（终态先到丢失面：未知状态值保守按在途，不虚构终态——iOS default 分支同口径）。 */
internal enum class ToolStatusState { Running, Completed, Failed }

internal fun toolStatusState(status: String): ToolStatusState = when (status) {
    "completed" -> ToolStatusState.Completed
    "failed" -> ToolStatusState.Failed
    else -> ToolStatusState.Running // running 与未知值一律保守按在途
}

/** 工具三态计数（未知状态值计入 running，与 [toolStatusState] 同口径）。 */
internal data class ToolActivityCounts(
    val running: Int,
    val completed: Int,
    val failed: Int,
)

internal fun toolActivityCounts(tools: List<UiToolActivity>): ToolActivityCounts = ToolActivityCounts(
    running = tools.count { toolStatusState(it.status) == ToolStatusState.Running },
    completed = tools.count { toolStatusState(it.status) == ToolStatusState.Completed },
    failed = tools.count { toolStatusState(it.status) == ToolStatusState.Failed },
)

/** 头部计数摘要：零值段省略、段间 " · "；全零返回空串（有阶段帧时头部只显阶段）。 */
internal fun workCountSummary(counts: ToolActivityCounts): String = buildList {
    if (counts.running > 0) add("进行中 ${counts.running}")
    if (counts.completed > 0) add("完成 ${counts.completed}")
    if (counts.failed > 0) add("失败 ${counts.failed}")
}.joinToString(" · ")

/** 最新阶段帧（Flow 按 ts 升序推送，取末帧）；run 终态投影已清行，此处天然为 null。 */
internal fun latestPhaseFrame(phases: List<UiRunPhase>): UiRunPhase? = phases.lastOrNull()

/** 头部进行中指示判据：活跃 run 且（有在途工具 或 有阶段帧）——否则呈静止快照。 */
internal fun workBarInProgress(runActive: Boolean, runningCount: Int, hasPhase: Boolean): Boolean =
    runActive && (runningCount > 0 || hasPhase)

/** 整条披露条出现判据：只呈现已捕获内容，无工具活动且无阶段帧的会话整条不渲染（零噪声）。 */
internal fun shouldRenderWorkBar(tools: List<UiToolActivity>, phases: List<UiRunPhase>): Boolean =
    tools.isNotEmpty() || phases.isNotEmpty()

/** 工具名兜底：started/终态载荷皆未携带名称（丢失面 + completed 载荷无名称）时兜底呈现，不虚构。 */
internal fun toolDisplayName(toolName: String): String = if (toolName.isEmpty()) "工具调用" else toolName

/**
 * Gateway 启动阶段闭集 → 人读文本（protocol.schema.json ChatEvent status 变体 phase 枚举，
 * 对齐 iOS phaseText 逐值映射）：未知值原样透出（命名替换不改语义，非身份字段不涉 ADR-14）；
 * 空串兜底「运行中」。
 */
internal fun phaseDisplayText(phase: String): String = when (phase) {
    "waiting_for_state" -> "等待会话状态"
    "preparing_workspace" -> "准备工作区"
    "naming_worktree" -> "命名工作树"
    "creating_worktree" -> "创建工作树"
    "running_setup" -> "运行初始化"
    "provisioning_environment" -> "准备运行环境"
    "preparing_context" -> "准备上下文"
    "memory_flushing" -> "整理记忆"
    "starting_model" -> "启动模型"
    "aborted" -> "已中止"
    else -> phase.ifEmpty { "运行中" }
}
