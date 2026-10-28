package dev.sharpxia.agentreins.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.AppModel
import dev.sharpxia.agentreins.ui.components.SandCard
import dev.sharpxia.agentreins.ui.components.SandDivider
import dev.sharpxia.agentreins.ui.components.SectionHeader
import dev.sharpxia.agentreins.ui.components.sandPressFill
import dev.sharpxia.agentreins.ui.theme.InkTheme

/**
 * Onboarding-lite（M3.6）：全新安装的引导页——一句话讲清这是什么 + 连接 CTA。
 * 连接成功（status → Connected）后 showOnboarding 翻 false，主界面接管并由
 * autoOpenKey 直达主会话。文案自写，不复刻 Grok 原文。
 */
@Composable
fun OnboardingScreen(model: AppModel) {
    var connecting by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(InkTheme.colors.paper),
    ) {
        if (!connecting) {
            // 垂直配重（review R2-P1-8）：品牌区偏上、卡片+CTA 沉底，不留大段死黑。
            // 内容固定不滚动（最矮机型 iPhone SE 亦足够容纳）。
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(56.dp))
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(InkTheme.shapes.xl.dp))
                        .background(InkTheme.colors.ink),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("A", style = InkTheme.type.headingLg, color = InkTheme.colors.paper)
                }
                Spacer(Modifier.height(20.dp))
                Text("AgentReins", style = InkTheme.type.headingLg, color = InkTheme.colors.ink)
                Spacer(Modifier.height(8.dp))
                Text(
                    "把你的 OpenClaw 网关装进口袋。\n随时查看会话、审批工具调用、跟进 Agent 动向。",
                    style = InkTheme.type.textMd,
                    color = InkTheme.colors.textTertiary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.weight(1f))
                SandCard {
                    Column {
                        OnboardingPoint("加密配对", "扫码或粘贴 setup code 完成设备级签名握手")
                        SandDivider(indent = 16)
                        OnboardingPoint("实时对话", "流式回复、Markdown 排版、工具输出全屏查看")
                        SandDivider(indent = 16)
                        OnboardingPoint("断线不丢", "消息离线排队，重连自动补发")
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { connecting = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = InkTheme.colors.ink,
                        contentColor = InkTheme.colors.paper,
                    ),
                    shape = RoundedCornerShape(InkTheme.shapes.md.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("连接网关", style = InkTheme.type.textLg)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "自托管网关 · 数据不经过第三方",
                    style = InkTheme.type.textXs,
                    color = InkTheme.colors.textDisabled,
                )
                Spacer(Modifier.height(16.dp))
            }
        } else {
            // 连接页自带返回头（review R3-P1-1：不再外层自绘返回键，避免双按钮堆叠）
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                ConnectionScreen(model, Modifier.fillMaxSize(), onBack = { connecting = false })
            }
        }
    }
}

@Composable
private fun OnboardingPoint(title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(InkTheme.colors.ink),
        )
        Column {
            Text(title, style = InkTheme.type.textLg, color = InkTheme.colors.ink)
            Text(subtitle, style = InkTheme.type.textXs, color = InkTheme.colors.textTertiary)
        }
    }
}

/** 设置 → 提示音：5 件套选择（点击即选中并试听）+ 静音项。触感开关在设置 tab 内。 */
@Composable
fun SoundsSettingsScreen(model: AppModel, onBack: () -> Unit) {
    val sounds = rememberSoundPlayer()
    Column(
        Modifier
            .fillMaxSize()
            .background(InkTheme.colors.paper)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = InkTheme.colors.ink)
            }
            Text("提示音", style = InkTheme.type.headingMd, color = InkTheme.colors.ink)
        }
        SectionHeader("新回复提示音（点击选择并试听）")
        SandCard(Modifier.padding(horizontal = 16.dp)) {
            val options: List<AppSound?> = listOf(null) + AppSound.entries
            options.forEachIndexed { index, sound ->
                SandSoundRow(
                    title = sound?.label ?: "无",
                    // 副标题只描述音色；固定用途语义统一放下方说明卡（review R2-P1-7）
                    subtitle = when (sound) {
                        null -> "静音：不播放任何提示音"
                        AppSound.BLIP -> "上行 blip"
                        AppSound.TICK -> "短滴答"
                        AppSound.DOUBLE_TICK -> "双滴答 · 默认"
                        AppSound.CHIME_A -> "上行风铃"
                        AppSound.CHIME_B -> "下行风铃"
                    },
                    selected = FeedbackPrefs.notificationSound.value == sound,
                    onClick = {
                        model.setNotificationSound(sound)
                        if (sound != null) sounds.play(sound)
                    },
                )
                if (index < options.lastIndex) SandDivider(indent = 16)
            }
        }
        SectionHeader("说明")
        SandCard(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "「新回复」指非当前会话收到 Agent 消息；审批请求固定使用上行风铃，" +
                    "连接成功固定使用 Blip，不受本页选择影响。设置页的音效总开关关闭后，" +
                    "本页选择保留、仅试听可出声（明确点按不计入事件音）。",
                style = InkTheme.type.textXs,
                color = InkTheme.colors.textTertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun SandSoundRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .sandPressFill(interaction, 0)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = InkTheme.type.textLg, color = InkTheme.colors.ink)
            Text(subtitle, style = InkTheme.type.textXs, color = InkTheme.colors.textTertiary)
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选",
                tint = InkTheme.colors.ink,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
