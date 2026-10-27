package dev.sharpxia.agentreins

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import dev.sharpxia.agentreins.ui.ConnectionScreen
import dev.sharpxia.agentreins.ui.SessionsScreen
import dev.sharpxia.agentreins.ui.theme.AgentReinsTheme

/** 单 Activity/Application 双页；导航升级（Compose Navigation）随 M3 引入。 */
private enum class AppScreen { SESSIONS, CONNECTION }

@Composable
fun App(prebuilt: AppModel? = null) {
    // prebuilt 由调用方 lambda 每次重组重新求值——必须 remember 固定单实例
    // （否则 dev 入口每次重组新建 AppModel：重复 connect + 状态源分裂）
    val prebuiltModel = remember { prebuilt }
    val model = prebuiltModel ?: remember { AppModel() }
    var current by remember { mutableStateOf(AppScreen.SESSIONS) }
    // 键盘弹出或进入对话页时隐藏底部导航（iOS 惯例，ChatGPT 同款）：
    // 导航栏垫在键盘/输入栏下会造成双重 padding、消息页也不再需要一级导航
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val chatMode = model.selectedSession != null

    // 设计基线（M2.6）：明暗跟随系统，token 见 ui/theme/Theme.kt
    AgentReinsTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!imeVisible && !chatMode) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        NavigationBarItem(
                            selected = current == AppScreen.SESSIONS,
                            onClick = { current = AppScreen.SESSIONS },
                            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                            label = { Text("会话") },
                        )
                        NavigationBarItem(
                            selected = current == AppScreen.CONNECTION,
                            onClick = { current = AppScreen.CONNECTION },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            label = { Text("连接") },
                        )
                    }
                }
            },
        ) { padding ->
            when (current) {
                AppScreen.SESSIONS -> SessionsScreen(model, Modifier.padding(padding))
                AppScreen.CONNECTION -> ConnectionScreen(model, Modifier.padding(padding))
            }
        }
    }
}
