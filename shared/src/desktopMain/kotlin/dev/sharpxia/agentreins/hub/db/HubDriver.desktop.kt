package dev.sharpxia.agentreins.hub.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual fun createHubDriver(): SqlDriver? = runCatching {
    val dir = File(System.getProperty("user.home"), ".agentreins").apply { mkdirs() }
    JdbcSqliteDriver("jdbc:sqlite:${File(dir, "agentreins-hub.db").absolutePath}")
}.getOrNull()
