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
    val first = runCatching {
        AndroidSqliteDriver(HubDatabase.Schema, context, "agentreins-hub.db")
    }.getOrNull()
    if (first != null) return first
    // 同 iOS 愈合路径（0.5.4 真机死库教训）：PRAGMA 被 0.5.4 旧守卫污染（=7 ≠ Schema.version 1）时
    // OpenHelper 打开即迁移失败关库——删库重开（投影可弃，O-01 随重建重取）。
    runCatching { context.deleteDatabase("agentreins-hub.db") }
    return runCatching {
        AndroidSqliteDriver(HubDatabase.Schema, context, "agentreins-hub.db")
    }.getOrNull()
}
