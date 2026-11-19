package dev.sharpxia.agentreins.hub.avatar

/**
 * AvatarSpec Kotlin 复活（AvatarSpec.kt/AgentTint.kt 旧稿实体已不在仓内，重建源 =
 * avatar-spec-20261029.md STRONG 表 + iOS RobotAvatar.swift:41-91 / InkTheme.swift:202-242
 * 现役代码逐公式镜像；两源有出入以 Swift 现役代码为准）。
 *
 * 供桌面（Compose）渲染器消费的跨端常量面；iOS 侧保持 Swift 原生实现（两端各自落常量，
 * parity 快照对照验收）。**声明序即契约**：AgentTint 十色序照抄 AgentTintName（InkTheme
 * :206-208），AgentExpression 十表情 rawValue 大写名照抄 RobotAvatar.swift:10-37。
 */

/** agent 身份色（AgentTint 移植）：strong 明暗两表 + kotlinHashCode 稳定兜底。 */
enum class AgentTint(val wire: String, val strongLight: Long, val strongDark: Long) {
    GRAY("GRAY", 0x8E8E93, 0x98989D),
    BLUE("BLUE", 0x0069D9, 0x0A84FF),
    RED("RED", 0xD70015, 0xFF453A),
    ORANGE("ORANGE", 0xC93400, 0xFF9F0A),
    YELLOW("YELLOW", 0xB25000, 0xFFD60A),
    GREEN("GREEN", 0x248A3D, 0x30D158),
    CYAN("CYAN", 0x1C7A99, 0x64D2FF),
    PURPLE("PURPLE", 0x8944AB, 0xBF5AF2),
    MAGENTA("MAGENTA", 0xD5236B, 0xFF375F),
    BROWN("BROWN", 0x8B6D4F, 0xAC8E68),
    ;

    /** strong 色 ARGB（按明暗模式取表；isDark = 系统深色模式/桌面暗色主题）。 */
    fun strongArgb(isDark: Boolean): Long = if (isDark) strongDark else strongLight

    companion object {
        /** 契约面映射：AvatarPalette → 渲染 tint（同一十色闭集，wire 名一致）。 */
        fun of(palette: AvatarPalette): AgentTint =
            entries.firstOrNull { it.wire == palette.wire } ?: GRAY

        /** 未注册 seed 的稳定兜底色（与 iOS agentTintFor 同语义同 seed 同色）。 */
        fun forSeed(seed: String): AgentTint {
            val h = kotlinHashCode(seed)
            val mag = if (h == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(h)
            return entries[mag % entries.size]
        }
    }
}

/**
 * Kotlin `String.hashCode()` 复刻（s[0]·31^(n−1)+…，Int32 溢出环绕；逐 UTF-16 码元迭代）——
 * 与 Swift `kotlinHashCode`（InkTheme.swift:231-236）同 seed 同值，兜底色两端口径一致。
 * Kotlin 字面即 String.hashCode，但按镜像纪律显式落实现并互为锚。
 */
fun kotlinHashCode(s: String): Int {
    var h = 0
    for (c in s) { // Kotlin Char 迭代 = 逐 UTF-16 码元（与 Swift s.utf16 同构）
        h = h * 31 + c.code // Int 32 位溢出环绕（Kotlin Int 语义）
    }
    return h
}

/**
 * 表情枚举镜像（RobotAvatar.swift:10-37，rawValue 大写名 = 跨端契约值勿重排/改名）：
 * 桌面渲染器表情映射用；iOS 侧 Swift 枚举为本源。
 */
enum class AgentExpression(val wire: String, val zhLabel: String) {
    CALM("CALM", "平静"),
    HAPPY("HAPPY", "开心"),
    EXCITED("EXCITED", "兴奋"),
    THINKING("THINKING", "思考"),
    CONFUSED("CONFUSED", "困惑"),
    SLEEPY("SLEEPY", "犯困"),
    SAD("SAD", "难过"),
    SMUG("SMUG", "得意"),
    SURPRISED("SURPRISED", "惊讶"),
    LOVE("LOVE", "喜爱"),
}

/**
 * agent 头像状态七态镜像（Components.swift:25-48）：表情是状态语义不是审美维度——
 * configured 用配置表情，其余六态覆盖指定（expressionOverride）。
 */
enum class AgentAvatarStatus(val wire: String, val statusLabel: String?, val expressionOverride: AgentExpression?) {
    CONFIGURED("configured", null, null),
    INITIAL("initial", "刚刚开始", AgentExpression.HAPPY),
    RUNNING("running", "思考中", AgentExpression.THINKING),
    IDLE("idle", "有点困惑", AgentExpression.CONFUSED),
    UNREAD("unread", "有新消息", AgentExpression.LOVE),
    ERROR("error", "连接异常", AgentExpression.CONFUSED),
    OFFLINE("offline", "离线", AgentExpression.SAD),
    ;

    /** 实际呈现表情：状态覆盖优先，configured 用传入的配置表情。 */
    fun resolveExpression(configured: AgentExpression): AgentExpression = expressionOverride ?: configured
}

/** 形象预设（UI 侧常量表，服务端/契约不感知预设概念——挑选 = 把 12 值写入 faceFactors）。 */
data class AvatarPreset(
    /** 目录序号（P1–P8，双端排序契约）。 */
    val id: String,
    /** 中文名（双端命名契约）。 */
    val zhName: String,
    /** 12 值表（ClayFace 相位锚生成，clamp[0.70,1.10] 两位小数；encode 往返校验通过）。 */
    val faceFactors: List<Double>,
    /** 推荐配色（挑选预设时同步切色的 UI 建议，可独立改）。 */
    val recommendedPalette: AvatarPalette,
) {
    /** 规范化 face 串（encode 同形）：预设识别/比较键。 */
    val faceKey: String get() = encodeFaceFactors(faceFactors)
}

/**
 * 预设目录（8 款，12 值表 = 设计稿唯一事实，双端各自落常量、排序与命名完全一致）：
 * 对位 GrokBot「身形 8 档、统一径向采样、切换 = 半径线性插值」机制，降采样到 12 点黏土系统。
 * 终值经双端渲染快照调形轮核定（本地无 GUI 探针，视觉终验归 TF beta 实机）。
 */
val AVATAR_PRESETS: List<AvatarPreset> = listOf(
    AvatarPreset(
        "P1", "圆满", List(12) { 1.0 }, AvatarPalette.GRAY,
    ),
    AvatarPreset(
        "P2", "卵石",
        listOf(1.03, 1.02, 1.01, 1.04, 1.00, 0.92, 0.94, 1.07, 1.10, 0.99, 0.91, 0.97),
        AvatarPalette.BROWN,
    ),
    AvatarPreset(
        "P3", "方糖",
        listOf(1.06, 0.96, 1.02, 1.03, 0.92, 1.01, 1.06, 0.96, 1.02, 1.03, 0.92, 1.01),
        AvatarPalette.BLUE,
    ),
    AvatarPreset(
        "P4", "胶囊",
        listOf(0.94, 0.98, 1.06, 1.10, 1.06, 0.98, 0.94, 0.98, 1.06, 1.10, 1.06, 0.98),
        AvatarPalette.CYAN,
    ),
    AvatarPreset(
        "P5", "水滴",
        listOf(1.10, 1.10, 1.04, 1.00, 0.98, 0.97, 0.96, 0.97, 0.98, 1.00, 1.04, 1.10),
        AvatarPalette.GREEN,
    ),
    AvatarPreset(
        "P6", "莓果",
        listOf(1.07, 0.99, 0.93, 1.01, 1.07, 0.99, 0.93, 1.01, 1.07, 0.99, 0.93, 1.01),
        AvatarPalette.MAGENTA,
    ),
    AvatarPreset(
        "P7", "蜂窝",
        listOf(1.05, 0.95, 1.05, 0.95, 1.05, 0.95, 1.05, 0.95, 1.05, 0.95, 1.05, 0.95),
        AvatarPalette.PURPLE,
    ),
    AvatarPreset(
        "P8", "南瓜",
        listOf(0.94, 0.96, 1.01, 1.04, 0.99, 0.93, 0.90, 0.93, 0.99, 1.04, 1.01, 0.96),
        AvatarPalette.ORANGE,
    ),
)
