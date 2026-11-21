package dev.sharpxia.agentreins.hub.avatar

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 会话级机器人形象覆盖档——契约 v1.5.2（`AgentReinService/api/hub-api-v1.yaml` 端点 #32 PUT
 * / #33 DELETE /v1/conversations/{conversationId}/avatar-profile + #34 GET
 * /v1/me/conversation-avatar-profiles + WSS 控制帧 conversation.avatar.updated；冻结 tag
 * hub-api-v1.5.2）。v1.5.0 用户级形象档（[AvatarProfile.kt]）的**会话维度镜像**：每会话
 * （侧栏一张卡）单独换形象跨设备同步；会话形象 = 对 seed=hash(conversationId) 派生默认的
 * 「覆盖档」——档形复用 [AvatarProfile]（造型/配色/动效/updatedAt 全同形），本文件只承载
 * 会话维度新增的清单条目 / 帧解析 / 逐键收敛。
 *
 * 渲染语义（两党校准）：覆盖档在（键在场）→ 用之；无覆盖（键缺席）→ 调用方走 seed 派生
 * 默认渲染——**store 不造默认值**（无覆盖不是一张「默认档」，是「没有」）。
 */

/** 会话覆盖档列表条目（端点 #34，v1.5.2）：三字段全 required。updatedAt ≡ profile.updatedAt（同一服务端权威值——列表层便利键非第二事实源，水合整体替换不必展开比较）。 */
@Serializable
data class ConversationAvatarProfileEntry(
    val conversationId: String,
    val profile: AvatarProfile,
    val updatedAt: String,
)

/**
 * WSS 控制帧 `conversation.avatar.updated` 载荷（v1.5.2，hub-wss-frames-v1 同日注册）：帧形
 * `{"type":"conversation.avatar.updated","data":{"conversationId":…,"profile":<AvatarProfile|null>}}`。
 * [profile] = null 是**合法清除帧**（端点 #33 广播，非脏帧——该会话覆盖已清除，回落 seed
 * 派生默认）；非事件域（不入事件类型闭集/eventlog/seq/投影，零推送触发）。
 */
data class ConversationAvatarFrame(val conversationId: String, val profile: AvatarProfile?)

/**
 * `conversation.avatar.updated` 帧载荷抽取（镜像 [parseAvatarUpdatedFrame] 宽松纪律 + null 清除帧扩形）：
 * - data.profile 为 JSON object → 解析为覆盖档（收侧宽松解析在 [AvatarProfile] 序列化器内完成）；
 * - data.profile 为 JSON null → **合法清除帧**（profile = null 原样交付，调用方按键移除）；
 * - 缺 data / 缺 conversationId（空串同）/ **profile 键整个缺席** / profile 形状漂移（非
 *   object 非 null、档解析失败）→ null（静默忽略，不断连——契约 required 三键，缺键 = 漂移
 *   非「清除」；清除的权威形态是显式 null）。
 */
fun parseConversationAvatarUpdatedFrame(obj: JsonObject): ConversationAvatarFrame? {
    val data = obj["data"] as? JsonObject ?: return null
    val conversationId = (data["conversationId"] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        ?: return null
    if ("profile" !in data) return null // required 键缺席 = 形状漂移（≠ 清除；显式 null 才是清除）
    return when (val profileEl = data["profile"]) {
        is JsonNull -> ConversationAvatarFrame(conversationId, null)
        is JsonObject -> runCatching {
            AvatarProfileJson.decodeFromJsonElement(AvatarProfile.serializer(), profileEl).sanitized()
        }.getOrNull()?.let { ConversationAvatarFrame(conversationId, it) }
        else -> null
    }
}

// ── 逐键收敛（per-key last-write-wins，updatedAt 大者胜；镜像 convergeAvatarProfile 单值口径） ──

/**
 * 会话覆盖档收敛落态（PUT 响应 / DELETE 成功 / 推送帧三通道唯一纯函数）：
 * - [profile] 非 null：per-key updatedAt 大者胜——同值幂等跳过（发起设备自收帧因 updatedAt
 *   与 PUT 响应相等而保持现值，防广播回环）；现值较新（陈旧帧乱序到达）保持现值。
 * - [profile] = null（DELETE 成功 / 清除帧）：键移除——无 updatedAt 守卫（纯收敛信号，
 *   重复应用无副作用；帧内广播保序 + 权威经 #34 回拉兜底收敛）。
 * - 未水合期（[current] = null）：清除帧保持 null 不虚假升格 emptyMap（null = 尚未水合的
 *   独立状态）；profile 帧则就地建表落键。
 * 键不在场/幂等跳过时返回 [current] 同实例（StateFlow 同值不重推）。
 */
fun mergeConversationAvatarProfile(
    current: Map<String, AvatarProfile>?,
    conversationId: String,
    profile: AvatarProfile?,
): Map<String, AvatarProfile>? {
    if (conversationId.isEmpty()) return current
    if (profile == null) {
        val map = current ?: return null
        if (conversationId !in map) return map
        return map - conversationId
    }
    val existing = current?.get(conversationId)
    val merged = convergeAvatarProfile(existing, profile) ?: return current
    if (merged == existing) return current // 同 updatedAt 幂等跳过 / 陈旧帧保持现值
    return (current ?: emptyMap()) + (conversationId to merged)
}

/**
 * #34 清单水合落表（权威 = REST 读面全量清单，**整体替换**——快照整体覆盖先例口径）：键缺席
 * = 该会话覆盖已清除（清库行非墓碑，列表即不再含）；空清单 = emptyMap（非 null——已水合
 * 无覆盖）。与在途 PUT/DELETE 的毫秒级并发窗理论上可被陈旧清单覆盖，由下个 sync.ready 回拉
 * 自愈（低频用户偏好，不承诺秒级实时——契约同步承诺口径）。
 */
fun conversationAvatarMapFromEntries(entries: List<ConversationAvatarProfileEntry>): Map<String, AvatarProfile> =
    entries.associate { it.conversationId to it.profile }

// ── JSON 便捷面（桥面交付：iOS 桥以 JSON 串跨边界，DTO 纪律同 [AvatarProfile.encodeJson]） ──

private val ConversationAvatarMapSerializer = MapSerializer(String.serializer(), AvatarProfile.serializer())

/** 覆盖档映射编码为 JSON 对象串 `{"<conversationId>":<AvatarProfile>,…}`（桥面交付形态）。 */
fun Map<String, AvatarProfile>.encodeConversationAvatarMapJson(): String =
    AvatarProfileJson.encodeToString(ConversationAvatarMapSerializer, this)
