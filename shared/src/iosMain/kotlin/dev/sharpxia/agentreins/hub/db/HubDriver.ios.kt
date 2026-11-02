package dev.sharpxia.agentreins.hub.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dev.sharpxia.agentreins.hubdb.HubDatabase

actual fun createHubDriver(): SqlDriver? = runCatching {
    // 名字相对路径落在 App 沙箱内（与旧缓存库同约定）
    NativeSqliteDriver(HubDatabase.Schema, "agentreins-hub.db")
}.getOrNull()
