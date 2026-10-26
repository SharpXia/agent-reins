package dev.sharpxia.agentreins.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual fun createAppDriver(): SqlDriver? = runCatching {
    // 官方文档用法：名字相对路径落在 App 沙箱内（v1.0 不自定目录）
    NativeSqliteDriver(AgentReinsDatabase.Schema, "agentreins.db")
}.getOrNull()
