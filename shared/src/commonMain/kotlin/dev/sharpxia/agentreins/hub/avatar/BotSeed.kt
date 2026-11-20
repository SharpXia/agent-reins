package dev.sharpxia.agentreins.hub.avatar

/**
 * 会话化身种子/状态纯函数（BotCharacter 角色系统批次1，2026-11-20）——三端单一事实源：
 *
 * - `botSeedFor(conversationId)`：同 cid 恒同参数（纯函数无随机源、免持久化）；conversationId
 *   = 26 位 Crockford ULID 且化身更替后不变（HubModels.kt:72 契约背书）→ 同会话跨设备恒定，
 *   #29 删除重建 = 新 ULID = 新角色（属预期）。仅 Int 字段（DTO 纪律 AgentBridge.kt:233-235，
 *   枚举/List 不过 ObjC）；iOS 侧不直调本文件（Swift 镜像 botSeedFor + 金标常量双端对锁，
 *   InkTheme.swift:240-253 镜像先例），桌面/单测直消费。
 * - `botStateFor(...)`：会话六态判定，优先级 WAITING > RUNNING(thinkingNow?THINKING:RUNNING)
 *   > ERROR > DONE(瞬时) > IDLE。返回 Int 编码（同 DTO 纪律），渲染端各自映射枚举。
 *
 * 取位公式（评审拍板，数值勿改；`shr` = Int 算术右移，负数符号扩展，双端同值）：
 * - paletteIndex = floorMod(h, 10)      → AgentTint 十色（AvatarSpec.kt:14-25 声明序 =
 *   AvatarPalette/AgentTintName 契约序；与 AgentTint.forSeed 的 abs 取位**语义同源但函数
 *   不同**——此处按规格走 floorMod，负 hash 桶位不同，勿混用）
 * - bodyIndex    = floorMod(h shr 8, 8)  → AVATAR_PRESETS P1–P8 十二值表（AvatarSpec.kt:112-151，
 *   44 基半径下调形核定，零重调形）
 * - eyeIndex     = floorMod(h shr 16, 3) → 眼形三态（0 圆角竖方 / 1 椭圆 / 2 正圆）
 * - poseIndex    = floorMod(h shr 22, 4) → 静息姿态四档（倾角 −1.2°/0°/+1.2° × 静息眼位 x=∓0.8）
 * - blinkTempoMs = 2600 + floorMod(h shr 26, 7) × 400 ∈ [2600, 5000]
 *
 * 金标 3 枚真实 ULID（仓内文档留痕的现役标识）：JVM 单测（desktopApp BotSeedRulesTest）与
 * Swift DEBUG 断言（BotCharacterView.swift）共用同一期望值表锁双端；任一侧实现漂移即断。
 */
data class BotSeed(
    /** 0..9 → AgentTint 十色序（= AvatarPalette 声明序）。 */
    val paletteIndex: Int,
    /** 0..7 → AVATAR_PRESETS P1–P8（bodyIndex 声明序即契约）。 */
    val bodyIndex: Int,
    /** 0..2 → 眼形（0 圆角竖方 / 1 椭圆 / 2 正圆）。 */
    val eyeIndex: Int,
    /** 0..3 → 静息姿态（见渲染端 REST_TILT/REST_EYE_X 双端同表）。 */
    val poseIndex: Int,
    /** 眨眼周期毫秒 ∈ [2600, 5000] 步进 400。 */
    val blinkTempoMs: Int,
)

/** 眨眼周期基值/步进（2600 + k×400，k ∈ 0..6）。 */
const val BOT_BLINK_TEMPO_BASE_MS = 2600
const val BOT_BLINK_TEMPO_STEP_MS = 400

/** 取位模数（渲染端/测试/ Swift 镜像共用锚，勿单端改值）：十色 / 八身 / 三眼形 / 四姿态。 */
const val AGENT_TINT_COUNT = 10
const val BODY_PRESET_COUNT = 8
const val EYE_SHAPE_COUNT = 3
const val POSE_PRESET_COUNT = 4

/** floorMod：结果恒落 [0, m)（Kotlin % 对负数留负号，这里收敛为非负桶位）。 */
private fun floorMod(v: Int, m: Int): Int = ((v % m) + m) % m

/**
 * 会话化身种子派生（纯函数）：见 [BotSeed] 头注取位公式。同 cid 逐参数恒定，
 * 无 id 面（设置预览/兜底）不走本函数、走用户形象档默认档。
 */
fun botSeedFor(conversationId: String): BotSeed {
    val h = kotlinHashCode(conversationId)
    return BotSeed(
        paletteIndex = floorMod(h, 10),
        bodyIndex = floorMod(h shr 8, 8),
        eyeIndex = floorMod(h shr 16, 3),
        poseIndex = floorMod(h shr 22, 4),
        blinkTempoMs = BOT_BLINK_TEMPO_BASE_MS + floorMod(h shr 26, 7) * BOT_BLINK_TEMPO_STEP_MS,
    )
}

// ── 会话六态稳定编码（0..5；跨端 Int 过界，渲染端各自映射枚举） ──

const val BOT_STATE_IDLE = 0
const val BOT_STATE_RUNNING = 1
const val BOT_STATE_THINKING = 2
const val BOT_STATE_WAITING = 3
const val BOT_STATE_ERROR = 4
const val BOT_STATE_DONE = 5

/**
 * 会话六态判定（纯函数，优先级 WAITING > RUNNING/THINKING > ERROR > DONE > IDLE）：
 *
 * - WAITING：审批挂起（谓词零新状态——iOS hub.approvals 按 conversationId 过滤、桌面
 *   pendingApprovals 同式，批次2/3 接线）。
 * - RUNNING/THINKING：有活跃 run；thinkingNow 细分只在聊天页，列表行两档合一呈 RUNNING
 *   （workActivity 按会话订阅纪律，逐行细分违例）。
 * - ERROR：末条消息失败且无活跃 run（聊天页全覆盖信号；列表行投影暂无 lastRunOutcome，
 *   属可选后续，不入本五批）。
 * - DONE：瞬时——视图侦测 hasActiveRun 真→假且终态非 failed 时置 [doneTransient]=true
 *   持续 900ms 后回落 IDLE（回落由视图层计时驱动，纯函数无时钟）。
 * - [doneTransient] 与更高优先级并存时被压制（ERROR > DONE：弹跳窗内新失败即转 ERROR）。
 *
 * motionEnabled=false 的静态定格、<40pt compact 折算均属渲染端职责，不入本函数。
 */
fun botStateFor(
    hasActiveRun: Boolean,
    thinkingNow: Boolean,
    awaitingApproval: Boolean,
    lastMessageFailed: Boolean,
    doneTransient: Boolean = false,
): Int = when {
    awaitingApproval -> BOT_STATE_WAITING
    hasActiveRun -> if (thinkingNow) BOT_STATE_THINKING else BOT_STATE_RUNNING
    lastMessageFailed -> BOT_STATE_ERROR
    doneTransient -> BOT_STATE_DONE
    else -> BOT_STATE_IDLE
}

/** 金标 3 枚真实 ULID（仓内文档留痕现役标识；期望值表双端对锁——勿改值/勿换 ULID）。 */
val BOT_SEED_GOLD_ULIDS: List<String> = listOf(
    // multimodal-probe-20261108 探针会话（docs/multimodal-design-20261108.md:5）
    "01M6YV6ZSDDADH8J8KYRKWR0C5",
    // tf-release-0.5.14 回归设备（docs/tf-release-0.5.14-20261116.md:17）
    "01M7FZXDT0550C25S5FPYRRTKZ",
    // hermes-s6 网关（docs/hermes-s6-onboard-20261115.md:19）
    "01M7FDA4YK22MP9YWE06PAW83X",
)
