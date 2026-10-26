package dev.sharpxia.agentreins

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
import dev.sharpxia.agentreins.ui.ConnectionScreen
import dev.sharpxia.agentreins.ui.SessionsScreen

/** M0/M1：单 Activity/Application 双页；导航升级（Compose Navigation）在 M2 随对话流引入。 */
private enum class AppScreen { SESSIONS, CONNECTION }

@Composable
fun App(prebuilt: AppModel? = null) {
    val model = prebuilt ?: remember { AppModel() }
    var current by remember { mutableStateOf(AppScreen.SESSIONS) }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
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
            },
        ) { padding ->
            when (current) {
                AppScreen.SESSIONS -> SessionsScreen(model, Modifier.padding(padding))
                AppScreen.CONNECTION -> ConnectionScreen(model, Modifier.padding(padding))
            }
        }
    }
}
