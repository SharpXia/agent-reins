package dev.sharpxia.agentreins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.sharpxia.agentreins.ui.AppRoot
import dev.sharpxia.agentreins.ui.theme.InkTheme
import dev.sharpxia.agentreins.ui.theme.ThemeMode

/**
 * App 根（M3.1）：主题入口 + 导航壳。页面路由/底栏在 ui/AppRoot，
 * 数据层依旧 AppModel 单实例（prebuilt 由调用方 lambda 每次重组重新求值——
 * 必须 remember 固定单实例，否则 dev 入口每次重组新建 AppModel：重复 connect +
 * 状态源分裂）。
 */
@Composable
fun App(prebuilt: AppModel? = null) {
    val prebuiltModel = remember { prebuilt }
    val model = prebuiltModel ?: remember { AppModel() }
    InkTheme(
        darkTheme = when (model.themeMode) {
            ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        },
    ) {
        AppRoot(model)
    }
}
