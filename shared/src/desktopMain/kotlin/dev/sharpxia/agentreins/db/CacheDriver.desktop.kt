package dev.sharpxia.agentreins.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual fun createAppDriver(): SqlDriver? = runCatching {
    val dir = File(System.getProperty("user.home"), ".agentreins").apply { mkdirs() }
    JdbcSqliteDriver("jdbc:sqlite:${File(dir, "agentreins.db").absolutePath}")
}.getOrNull()
