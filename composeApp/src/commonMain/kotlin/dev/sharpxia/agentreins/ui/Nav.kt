package dev.sharpxia.agentreins.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.savedstate.read
import dev.sharpxia.agentreins.AppModel
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import dev.sharpxia.agentreins.ui.components.ConnectionEmptyState
import dev.sharpxia.agentreins.ui.theme.InkTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

/**
 * M3.1 IA 导航：4-tab（会话/Agents/搜索/设置）+ push 转场（slide+fade，iOS push 观感）。
 * 二级页（chat/agent/connection）经 NavHost push，tab 状态保留；聊天页隐藏底栏
 * （M2.6 惯例：键盘 union 布局 + 一级导航不进对话域）。
 */
object Routes {
    const val TAB_SESSIONS = "tab:sessions"
    const val TAB_SEARCH = "tab:search"
    const val TAB_SETTINGS = "tab:settings"
    const val CHAT = "chat/{sessionKey}"
    const val AGENT = "agent/{agentId}"
    const val CONNECTION = "connection"
    const val THEME_DEMO = "themeDemo"
    const val SETTINGS_SOUNDS = "settings/sounds"

    fun chat(sessionKey: String) = "chat/${encodeSegment(sessionKey)}"
    fun agent(agentId: String) = "agent/${encodeSegment(agentId)}"

    /**
     * 路由段 percent-encode（navigation 会 URL-decode 参数）：会话 key 含冒号（安全）、
     * 但 `/` 会断段、`?#` 会进 query/fragment——白名单字符集外全部转义。
     */
    private fun encodeSegment(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt().toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.~!$&'()*+,;=:@") {
                append(c)
            } else {
                append('%').append(b.toInt().and(0xFF).toString(16).padStart(2, '0').uppercase())
            }
        }
    }
}

private data class TabSpec(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val TABS = listOf(
    TabSpec(Routes.TAB_SESSIONS, "会话", Icons.Filled.Home),
    TabSpec(Routes.TAB_SEARCH, "搜索", Icons.Filled.Search),
    TabSpec(Routes.TAB_SETTINGS, "设置", Icons.Filled.Settings),
)

@Composable
fun AppRoot(model: AppModel) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // 对话域（chat 路由）隐藏底栏——一级导航不进对话（M2.6 真机反馈）
    val chatMode = currentRoute == Routes.CHAT

    // Onboarding-lite：从未连接过的全新安装先过引导页（存有凭据的升级用户直接进主界面）。
    // 引导域不进 NavHost（此时主图尚未挂载）——连接页由引导页内部状态切换。
    if (model.showOnboarding) {
        OnboardingScreen(model = model)
        return
    }

    // 导航事件（createNewSession 完成直达）：组合协程=主线程消费，navigate 不再被
    // 后台线程调用（真机反馈⑤-1：跨线程 navigate 毁导航栈 → 列表可滑但导航全失效）
    LaunchedEffect(Unit) {
        model.navigationEvents.collect { key ->
            navController.navigate(Routes.chat(key)) {
                popUpTo(Routes.TAB_SESSIONS) { saveState = true }
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        containerColor = InkTheme.colors.paper,
        bottomBar = {
            // 键盘/对话域滑出底栏（M3.6 过场细节：跟随动效而非硬切）
            androidx.compose.animation.AnimatedVisibility(
                visible = !imeVisible && !chatMode,
                enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
            ) {
                InkBottomBar(navController, currentRoute)
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.TAB_SESSIONS,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            enterTransition = {
                slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(280))
            },
            exitTransition = {
                slideOutHorizontally(tween(280)) { -it / 4 } + fadeOut(tween(280))
            },
            popEnterTransition = {
                slideInHorizontally(tween(280)) { -it / 4 } + fadeIn(tween(280))
            },
            popExitTransition = {
                slideOutHorizontally(tween(280)) { it / 3 } + fadeOut(tween(280))
            },
        ) {
            composable(Routes.TAB_SESSIONS) {
                // dev 通道自动进会话（desktop/iOS 壳 devAutoSelectKey）+ Onboarding 直达
                // （autoOpenKey）：M3.1 起 select() 不驱动路由；放在图内组合保证
                // NavController 就绪（冷启动 navigate 崩修复）。消费后清 autoOpenKey，
                // 防从 chat 返回列表时被再次吸进同一会话。
                LaunchedEffect(Unit) {
                    snapshotFlow { model.selectedSession }
                        .filterNotNull()
                        .take(1)
                        .collect { key ->
                            if (key == model.devAutoSelectKey || key == model.autoOpenKey) {
                                navController.navigate(Routes.chat(key))
                            }
                            // 无论匹配与否都消费（review R3-P2-3）：用户抢先手动进会话时
                            // autoOpenKey 会搁浅，之后断线重连被陈旧重放
                            model.consumeAutoOpen()
                        }
                }
                SessionsScreen(
                    model = model,
                    onOpenSession = { key -> navController.navigate(Routes.chat(key)) },
                    onOpenAgent = { agentId -> navController.navigate(Routes.agent(agentId)) },
                )
            }
            composable(Routes.TAB_SEARCH) {
                SearchScreen(
                    model = model,
                    onOpenSession = { key -> navController.navigate(Routes.chat(key)) },
                )
            }
            composable(Routes.TAB_SETTINGS) {
                SettingsScreen(
                    model = model,
                    onOpenConnection = { navController.navigate(Routes.CONNECTION) },
                    onOpenThemeDemo = { navController.navigate(Routes.THEME_DEMO) },
                    onOpenSounds = { navController.navigate(Routes.SETTINGS_SOUNDS) },
                )
            }
            composable(Routes.THEME_DEMO) {
                dev.sharpxia.agentreins.ui.components.ThemeDemoScreen()
            }
            composable(Routes.SETTINGS_SOUNDS) {
                SoundsSettingsScreen(model, onBack = { navController.popBackStack() })
            }
            composable(Routes.CHAT) { entry ->
                val key = entry.arguments?.read { getStringOrNull("sessionKey") } ?: ""
                ChatRoute(model = model, sessionKey = key, onBack = { navController.popBackStack() })
            }

            composable(Routes.AGENT) { entry ->
                val agentId = entry.arguments?.read { getStringOrNull("agentId") } ?: ""
                AgentDetailScreen(
                    model = model,
                    agentId = agentId,
                    onOpenSession = { key -> navController.navigate(Routes.chat(key)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.CONNECTION) {
                Box(Modifier.fillMaxSize()) {
                    ConnectionScreen(model, Modifier.fillMaxSize(), onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

/** 墨水风底栏（毛玻璃 M3.6 接 haze）。 */
@Composable
private fun InkBottomBar(navController: NavHostController, currentRoute: String?) {
    // 二级页归属来源 tab 的选中态（review R2-P0-1）：settings/sounds、themeDemo、
    // connection 都从设置域推入；agent 详情（抽屉编辑入口）归属会话 tab（R1-P1-1）。
    val selectedRoute = when (currentRoute) {
        Routes.TAB_SESSIONS, Routes.TAB_SEARCH, Routes.TAB_SETTINGS -> currentRoute
        Routes.SETTINGS_SOUNDS, Routes.THEME_DEMO, Routes.CONNECTION -> Routes.TAB_SETTINGS
        Routes.AGENT -> Routes.TAB_SESSIONS
        else -> null
    }
    NavigationBar(containerColor = InkTheme.colors.elevated) {
        TABS.forEach { tab ->
            NavigationBarItem(
                selected = selectedRoute == tab.route,
                onClick = {
                    navController.navigate(tab.route) {
                        popUpTo(Routes.TAB_SESSIONS) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label, style = InkTheme.type.textXs) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = InkTheme.colors.ink,
                    selectedTextColor = InkTheme.colors.ink,
                    unselectedIconColor = InkTheme.colors.textTertiary,
                    unselectedTextColor = InkTheme.colors.textTertiary,
                    indicatorColor = InkTheme.colors.surfaceTertiary,
                ),
            )
        }
    }
}


/**
 * 对话页 + iOS 边缘返回（M2.6 交互原样保留，导航层由 NavHost 承担）：
 * 底层铺真实会话列表视差露出，左缘起手横拖超过 30% 即返回。
 */
@Composable
private fun ChatRoute(model: AppModel, sessionKey: String, onBack: () -> Unit) {
    var backDragPx by remember { mutableStateOf(0f) }
    var edgeDragArmed by remember { mutableStateOf(false) }
    var screenWidthPx by remember { mutableStateOf(1f) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHapticPlayer()
    val edgeWidthPx = with(LocalDensity.current) { 28.dp.toPx() }
    val commitFraction = 0.3f

    LaunchedEffect(Unit) {
        // 路由参数直达（Agent 域/搜索）：确保模型层选中态与路由一致
        if (model.selectedSession != sessionKey) {
            model.sessions.firstOrNull { it.id == sessionKey }?.let { model.select(it) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 底层：真实列表（拖拽时视差平移并压暗）
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = backDragPx * 0.3f
                    val progress = if (screenWidthPx > 1f) backDragPx / (screenWidthPx * commitFraction) else 0f
                    alpha = if (backDragPx > 0f) (0.55f + 0.45f * (1f - progress.coerceIn(0f, 1f))) else 1f
                },
        ) {
            if (model.sessions.isNotEmpty()) {
                SessionsScreen(model = model, onOpenSession = {})
            } else {
                ConnectionEmptyState(model)
            }
        }
        // 顶层：对话页 + 左缘手势
        Box(
            Modifier
                .fillMaxSize()
                .shadow(if (backDragPx > 0f) 12.dp else 0.dp)
                .offset { IntOffset(backDragPx.roundToInt(), 0) }
                .onSizeChanged { screenWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { start -> edgeDragArmed = start.x <= edgeWidthPx },
                        onDragEnd = {
                            if (!edgeDragArmed) return@detectHorizontalDragGestures
                            edgeDragArmed = false
                            if (backDragPx > screenWidthPx * commitFraction) {
                                haptics.tap()
                                model.select(null)
                                onBack()
                                backDragPx = 0f
                            } else {
                                val from = backDragPx
                                scope.launch {
                                    animate(from, 0f, animationSpec = spring()) { v, _ -> backDragPx = v }
                                }
                            }
                        },
                        onDragCancel = { edgeDragArmed = false; backDragPx = 0f },
                    ) { change, dragAmount ->
                        if (edgeDragArmed) {
                            backDragPx = (backDragPx + dragAmount).coerceIn(0f, screenWidthPx)
                            change.consume()
                        }
                    }
                },
        ) {
            ChatScreen(model, sessionKey, Modifier.fillMaxSize(), onBack = onBack)
        }
    }
}
