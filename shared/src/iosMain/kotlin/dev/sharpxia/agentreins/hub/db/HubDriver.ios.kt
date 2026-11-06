package dev.sharpxia.agentreins.hub.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
actual fun createHubDriver(): SqlDriver? {
    val first = runCatching { NativeSqliteDriver(HubDatabase.Schema, "agentreins-hub.db") }.getOrNull()
    if (first != null) return first
    // 0.5.4 真机死库愈合：旧守卫曾把 PRAGMA user_version 写成 7（≠ Schema.version 1），
    // SQLiter 打开即「migration 失败关连接」且发生在任何守卫代码之前——只能删库重开
    // （投影可弃，O-01 随重建重取；与 openHubDatabase 的 drop 重建同一纪律）。
    runCatching {
        val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: return@runCatching
        val fm = NSFileManager.defaultManager
        val dbDir = "$base/databases"
        val names = fm.contentsOfDirectoryAtPath(dbDir, null) ?: return@runCatching
        for (name in names) {
            val n = name as? String ?: continue
            if (n.startsWith("agentreins-hub.db")) fm.removeItemAtPath("$dbDir/$n", null)
        }
    }
    return runCatching { NativeSqliteDriver(HubDatabase.Schema, "agentreins-hub.db") }.getOrNull()
}
