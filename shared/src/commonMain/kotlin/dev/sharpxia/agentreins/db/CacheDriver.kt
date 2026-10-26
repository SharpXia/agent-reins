package dev.sharpxia.agentreins.db

import app.cash.sqldelight.db.SqlDriver

/** 缓存 schema 独立版本（A1：缓存非真相源，破坏性变更 drop 重建）。 */
internal const val CACHE_SCHEMA_VERSION = 1L

/** 平台 SQLite driver 工厂；存储不可用（如 Android 库进程无 context）返回 null → Noop 降级。 */
expect fun createAppDriver(): SqlDriver?

/**
 * 打开缓存库：建表 + 引导守卫（P0 修复）。裸 CREATE TABLE 在二次启动必抛
 * "table already exists"，吞掉即静默 Noop——必须以 PRAGMA user_version 判定是否已初始化：
 * - 0 且无表：首启建表；0 但已有表（平台 driver 可能自管建表）：直接放行
 * - 与 [CACHE_SCHEMA_VERSION] 不符：drop 重建（缓存随时可整库重建）
 * 任何失败返回 null → Noop 降级（缓存丢失不伤在线功能）。
 */
fun openCacheDatabase(driver: SqlDriver?): AgentReinsDatabase? {
    driver ?: return null
    return runCatching {
        when (pragmaUserVersion(driver)) {
            CACHE_SCHEMA_VERSION -> Unit
            0L -> {
                // 有表即认为驱动已代建（平台 driver 自管 schema）；四表不全=首启建表中断
                // （P3-a）→ 缓存可弃，drop 干净重建
                if (!allTablesExist(driver)) {
                    dropCacheTables(driver)
                    AgentReinsDatabase.Schema.create(driver).value
                }
                setUserVersion(driver, CACHE_SCHEMA_VERSION)
            }
            else -> {
                dropCacheTables(driver)
                AgentReinsDatabase.Schema.create(driver).value
                setUserVersion(driver, CACHE_SCHEMA_VERSION)
            }
        }
        AgentReinsDatabase(driver)
    }.getOrNull()
}

private fun pragmaUserVersion(driver: SqlDriver): Long =
    driver.executeQuery(
        null,
        "PRAGMA user_version",
        { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        },
        0,
    ).value

private fun setUserVersion(driver: SqlDriver, version: Long) {
    driver.execute(null, "PRAGMA user_version = $version", 0)
}

private val CACHE_TABLES = listOf("cachedSessions", "cachedMessages", "outboxCommands", "kv")

private fun allTablesExist(driver: SqlDriver): Boolean = CACHE_TABLES.all { table ->
    driver.executeQuery(
        null,
        "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='$table'",
        { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(cursor.next().value && (cursor.getLong(0) ?: 0L) > 0L)
        },
        0,
    ).value
}

private fun dropCacheTables(driver: SqlDriver) {
    CACHE_TABLES.forEach {
        driver.execute(null, "DROP TABLE IF EXISTS $it", 0)
    }
}
