package dev.sharpxia.agentreins.hub.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.sharpxia.agentreins.hubdb.HubDatabase

actual fun createHubDriver(): SqlDriver? {
    // shared 库无 androidx 依赖：反射取 Application context；拿不到（编译验证/工具进程）→ null 降级
    val context = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? android.content.Context
    }.getOrNull() ?: return null
    return runCatching {
        AndroidSqliteDriver(HubDatabase.Schema, context, "agentreins-hub.db")
    }.getOrNull()
}
