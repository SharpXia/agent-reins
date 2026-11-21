package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.sharpxia.agentreins.hub.DefaultHubClient
import dev.sharpxia.agentreins.hub.HubException
import dev.sharpxia.agentreins.hub.avatar.AGENT_TINT_COUNT
import dev.sharpxia.agentreins.hub.avatar.AgentTint
import dev.sharpxia.agentreins.hub.avatar.AVATAR_PRESETS
import dev.sharpxia.agentreins.hub.avatar.AvatarMotion
import dev.sharpxia.agentreins.hub.avatar.AvatarPalette
import dev.sharpxia.agentreins.hub.avatar.AvatarProfile
import dev.sharpxia.agentreins.hub.avatar.BODY_PRESET_COUNT
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_IDLE
import dev.sharpxia.agentreins.hub.avatar.UPDATED_AT_EPOCH
import dev.sharpxia.agentreins.hub.avatar.botSeedFor
import dev.sharpxia.agentreins.hub.avatar.randomPresetExcluding
import kotlin.random.Random
import kotlinx.coroutines.launch

/**
 * 会话 seed 派生档镜像（会话级换形象轮 2026-11-21）：与 BotCharacter 无覆盖渲染口径逐参数
 * 同源——faceFactors = AVATAR_PRESETS[seed.bodyIndex]（BotCharacter.kt effectiveFactors
 * 同式取表）、paletteId = AvatarPalette.entries[seed.paletteIndex]（声明序 = AgentTint 序
 * 契约，渲染侧 AgentTint.entries 同下标取色）。供会话换装 dialog 的「无覆盖当前形象」预览
 * 与随机排除键（无覆盖排 seed 派生键）消费；渲染挂载点（侧栏卡/聊天页角色）不消费本函数
 * ——照传 seed + null override 即现状派生零变化。updatedAt 恒 epoch 哨兵（派生档无服务端
 * 权威时间，不入收敛比较）；[motion] 只填契约形状（渲染动效仍走全局形象档，会话档
 * motionLevel 现阶段无渲染消费者）。JVM 回归锚 ConversationAvatarRulesTest（镜像锁）。
 */
internal fun conversationSeedDerivedProfile(conversationId: String, motion: AvatarMotion): AvatarProfile {
    val seed = botSeedFor(conversationId)
    return AvatarProfile(
        faceFactors = AVATAR_PRESETS[seed.bodyIndex.coerceIn(0, BODY_PRESET_COUNT - 1)].faceFactors,
        paletteId = AvatarPalette.entries[seed.paletteIndex.coerceIn(0, AGENT_TINT_COUNT - 1)],
        motionLevel = motion,
        updatedAt = UPDATED_AT_EPOCH,
    )
}

/**
 * 会话换装 dialog（契约 v1.5.2 端点 #32 PUT / #33 DELETE /v1/conversations/{id}/avatar-profile，
 * 会话级形象覆盖档轮 2026-11-21；iOS/Mac 同规格并行）：镜像设置页 AvatarPreferencesCard 的
 * 交互语言但语义为**会话级**——入口 = 侧栏会话卡右键菜单「换形象」+ 聊天页角色点按；
 * - 标题带会话名；96dp 实时预览（seed 照传：眼形/姿态/眨音仍由会话身份派生，造型/配色经
 *   faceFactorsOverride/paletteIndexOverride 注入——与挂载点渲染同通道）；
 * - 8 款预设网格（44dp 缩略静态渲染）与 🎲 换一个、十色圆点**只进本地预览草稿**（randomDraft，
 *   remember 态不入仓不入偏好）；🎲 排除当前展示键（shown 档键——有覆盖排覆盖键、无覆盖排
 *   seed 派生键、草稿在途排草稿键 =「连摇必换形」，randomPresetExcluding 单源直消费）、
 *   色/动效继承 shown；预设 = 造型+推荐配色一并进草稿（设置页推荐语义同源）；色点并入草稿
 *   （无草稿以当前形象为基出草稿，随确认一次 PUT）；
 * - 「用这个形象」= #32 先 PUT 成功再关 dialog（失败显式错误不关可重试——shared 门面
 *   putConversationAvatarProfile 先成功再收敛进 conversationAvatarProfiles，挂载点 StateFlow
 *   帧驱动自动刷新）；「恢复默认」= #33 DELETE（204 后键移除，渲染回落 seed 派生默认——
 *   store 不造默认值；无覆盖时点击亦无害，204 幂等终态），成功后关；「放弃」只清预览；
 * - 动效不进本 sheet（仍全局形象档）：预览动效门控与 PUT 载荷 motionLevel 均取全局现档
 *   （[globalMotion]）——落库记录与实际渲染行为一致。
 */
@Composable
internal fun ConversationAvatarDialog(
    client: DefaultHubClient,
    conversationId: String,
    conversationLabel: String,
    /** 全局形象档动效现值（HubShell avatarUi.motionLevel 同源）：预览动效门控 + PUT 载荷形状。 */
    globalMotion: AvatarMotion,
    onDismiss: () -> Unit,
) {
    // 远端覆盖档现值（StateFlow 帧驱动直查，勿缓存副本）：null = 未水合、键缺席 = 无覆盖走
    // seed 派生（store 不造默认值语义）
    val overrides by client.conversationAvatarProfiles.collectAsState()
    val overrideProfile = overrides?.get(conversationId)
    val seedDerived = remember(conversationId) { conversationSeedDerivedProfile(conversationId, globalMotion) }
    // 预览草稿（设置页 randomDraft 同语）：UI 本地态，确认才 PUT
    var randomDraft by remember(conversationId) { mutableStateOf<AvatarProfile?>(null) }
    var putting by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 覆盖档在途变更清草稿（设置页 LaunchedEffect(profile) 同口径）：PUT 响应回填/推送帧/
    // #34 水合三路都体现为覆盖档新值——防陈旧草稿遮蔽权威档（服务端 LWW 守卫兜底）
    LaunchedEffect(overrideProfile) { randomDraft = null }

    fun describe(e: Throwable): String =
        (e as? HubException)?.let { "${it.payload.code} ${it.payload.message}" }
            ?: (e.message ?: e::class.simpleName ?: "未知错误")

    fun commitDraft() {
        val target = randomDraft ?: return
        if (putting || clearing) return
        putting = true
        error = null
        scope.launch {
            runCatching { client.putConversationAvatarProfile(conversationId, target) }
                .onSuccess { onDismiss() } // #32 先 PUT 成功再关 dialog
                .onFailure { error = "保存失败：${describe(it)}（可重试）" }
            putting = false
        }
    }

    fun resetToDefault() {
        if (putting || clearing) return
        clearing = true
        error = null
        scope.launch {
            runCatching { client.deleteConversationAvatarProfile(conversationId) }
                .onSuccess { onDismiss() } // #33 成功后关；键移除即渲染回落 seed 派生
                .onFailure { error = "恢复默认失败：${describe(it)}（可重试）" }
            clearing = false
        }
    }

    val shown = randomDraft ?: overrideProfile ?: seedDerived
    val busy = putting || clearing

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "换形象 · ${conversationLabel.ifBlank { "（未命名会话）" }}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "形象仅作用于本会话（跨设备同步）；动效档跟随全局形象档设置。",
                    fontSize = 11.sp,
                    color = Color(0xFF888888),
                )

                // 96dp 实时预览：seed 照传（眼形/姿态/眨音仍由会话身份派生），造型/配色随
                // shown 注入（与挂载点渲染同一条 override 通道）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BotCharacter(
                        seed = botSeedFor(conversationId),
                        state = BOT_STATE_IDLE,
                        size = 96.dp,
                        motionEnabled = globalMotion == AvatarMotion.STANDARD,
                        interactive = false,
                        faceFactorsOverride = shown.faceFactors,
                        paletteIndexOverride = AvatarPalette.entries.indexOf(shown.paletteId),
                    )
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(
                            "造型 · ${AVATAR_PRESETS.firstOrNull { it.faceKey == shown.faceKey }?.zhName ?: "自定义"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "配色 · ${shown.paletteId.wire}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF777777),
                        )
                        when {
                            randomDraft != null -> Text(
                                "预览中 · 未保存",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB45309),
                            )
                            overrideProfile != null -> Text(
                                "当前 · 会话覆盖档",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF777777),
                            )
                            else -> Text(
                                "当前 · 默认派生",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF777777),
                            )
                        }
                    }
                }

                // 随机行：排除当前展示键（draft 感知——有覆盖排覆盖键、无覆盖排 seed 派生键、
                // 草稿在途排草稿键 =「连摇必换形」）；只抽形不动色，结果只进本地预览零 PUT
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("随机换形象", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = {
                            val next = randomPresetExcluding(shown.faceKey, Random.Default)
                            randomDraft = AvatarProfile(next.faceFactors, shown.paletteId, globalMotion, shown.updatedAt)
                        },
                        enabled = !busy,
                    ) { Text("🎲 换一个") }
                }

                // 预设网格 8 款（两行四列；44dp 缩略静态渲染——常驻循环多实例纪律；选中 =
                // faceKey 相等随 shown，草稿/覆盖/派生三态感知）
                Text("造型", style = MaterialTheme.typography.titleSmall)
                listOf(0, 4).forEach { start ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AVATAR_PRESETS.subList(start, start + 4).forEach { preset ->
                            val selected = preset.faceKey == shown.faceKey
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !busy) {
                                        // 预设 = 造型+推荐配色一并进预览草稿（设置页推荐语义同源）
                                        randomDraft = AvatarProfile(
                                            preset.faceFactors,
                                            preset.recommendedPalette,
                                            globalMotion,
                                            shown.updatedAt,
                                        )
                                    }
                                    .padding(4.dp),
                            ) {
                                BotCharacter(
                                    seed = botSeedFor(conversationId),
                                    state = BOT_STATE_IDLE,
                                    size = 44.dp,
                                    motionEnabled = false, // 缩略静态渲染
                                    interactive = false,
                                    faceFactorsOverride = preset.faceFactors,
                                    paletteIndexOverride = AvatarPalette.entries.indexOf(preset.recommendedPalette),
                                )
                                Text(
                                    preset.zhName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) Color(0xFF2B6CB0) else Color(0xFF777777),
                                )
                            }
                        }
                    }
                }

                // 十色圆点（strong 色，明暗表与设置页同口径）：并入预览草稿——草稿在途改草稿
                // 色；无草稿以当前形象为基出草稿，随「用这个形象」一次 PUT
                Text("配色", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AvatarPalette.entries.forEach { p ->
                        val selected = p == shown.paletteId
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(AgentTint.of(p).strongArgb(false)))
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) Color(0xFF2B6CB0) else Color(0xFFCCCCCC),
                                    shape = CircleShape,
                                )
                                .clickable(enabled = !busy) {
                                    randomDraft = (randomDraft ?: overrideProfile ?: seedDerived).copy(paletteId = p)
                                },
                        )
                    }
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }

                // 预览确认行（草稿在途才出现）：「用这个形象」PUT 先成功再关（失败预览保持
                // 可重试）；「放弃」只清预览零扰动
                if (randomDraft != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "预览中 · 未保存",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB45309),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { randomDraft = null }, enabled = !busy) { Text("放弃") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = ::commitDraft, enabled = !busy) {
                            Text(if (putting) "保存中…" else "用这个形象")
                        }
                    }
                }

                // 底行：恢复默认（#33 DELETE；无覆盖点击亦无害——204 幂等终态）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = ::resetToDefault, enabled = !busy) {
                        Text(if (clearing) "清除中…" else "恢复默认", color = Color(0xFFB45309))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}
