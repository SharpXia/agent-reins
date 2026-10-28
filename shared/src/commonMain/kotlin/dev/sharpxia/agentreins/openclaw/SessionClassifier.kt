package dev.sharpxia.agentreins.openclaw

/**
 * 会话 surface 判别（M4 P1）。协议事实（2026-10-29 网关探针 300 行实测，scripts/probe-kind-compact.mjs）：
 * `kind` 全部为 "direct"（无判别力）；`channel` 仅 telegram 行有值。
 * 主线/派生的判别只能靠 key 形态 `agent:<agentId>:<surface>`。
 */
enum class SessionCategory {
    /** 主线：常驻 main + app 创建的 ar-*——conversation 时间线的成员。 */
    MAINLINE,
    /** 派生任务：subagent 会话——永不进首页/conversation，仅 agent 主页可见。 */
    DERIVED,
    /** 系统：cron/boot/dashboard——仅 agent 主页可见。 */
    SYSTEM,
    /** 渠道直连：telegram 等 direct 线程——独立 conversation 行，不混入主线。 */
    CHANNEL,
    /** 未识别 surface——白名单放行制：仅 agent 主页可见。 */
    UNKNOWN,
}

object SessionClassifier {

    /** key `agent:<agentId>:<surface…>` 的 surface 段（原样，含冒号）。 */
    fun surfaceOf(key: String): String? =
        if (!key.startsWith("agent:")) null
        else key.removePrefix("agent:").substringAfter(':').takeIf { it.isNotBlank() }

    fun classify(key: String): SessionCategory {
        val surface = surfaceOf(key) ?: return SessionCategory.UNKNOWN
        return when {
            surface == "main" || surface.startsWith("ar-") -> SessionCategory.MAINLINE
            surface.startsWith("subagent:") -> SessionCategory.DERIVED
            surface.startsWith("cron:") ||
                surface.startsWith("boot:") ||
                surface.startsWith("dashboard:") -> SessionCategory.SYSTEM
            surface.contains(":direct:") -> SessionCategory.CHANNEL
            else -> SessionCategory.UNKNOWN
        }
    }

    /** CHANNEL 会话的渠道桶（surface 首段，如 telegram）——同渠道同人归一个 conversation。 */
    fun channelBucketOf(key: String): String? {
        if (classify(key) != SessionCategory.CHANNEL) return null
        return surfaceOf(key)?.substringBefore(':')?.takeIf { it.isNotBlank() }
    }
}
