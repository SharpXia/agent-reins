package dev.sharpxia.agentreins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Android/Desktop 壳的占位根（Hub 化 P4）。
 *
 * 网关直连时代的 Compose UI 层（AppModel + ui/）已随直连协议整体退役；iOS 为 Hub 主端
 * （iosApp SwiftUI，经 [AgentBridge] 消费 shared/hub）。Android/Desktop 的 Hub 界面
 * 待基于 shared/hub 另起 Compose 实现，不复用旧 AppModel——本占位只保证两端编译绿与可启动。
 */
@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("AgentReins", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Hub 客户端：本平台界面待建（iOS 为当前主端）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
