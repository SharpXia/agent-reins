package dev.sharpxia.agentreins.hub.db

import app.cash.sqldelight.db.SqlDriver
import dev.sharpxia.agentreins.hubdb.HubDatabase

/** Hub 投影库 schema 版本（投影可整库重建；升版即 drop 重建，O-01 持久化最小集随重建后从服务端重取）。
 *  5 = v1.1 additive（wp-11）：hubConversations.gatewayId（ConversationRow required 透出，
 *  #16 新建会话入口 (gatewayId, agentId) 判据与网关维度展示）。
 *  4 = v1.1 additive（wp-5 流式投影修复）：hubMessages.reason（事件/快照双路径共用失败原因列）
 *  + hubConversations.activeRunId（run.* 事件捕获，wp-8 Stop 取值）。
 *  3 = 契约 v1.1.0 去 session 化表结构（conversationId 主键 + hubConvKeys；1 = 0.5.0 的 sessionKey 主键代）。
 *  0.5.1/0.5.2 曾改表未升版本号——守卫放行旧结构，v3 SQL 打 v1 表抛异常经 uncaught handler SIGABRT，
 *  即真机「点开会话即崩」根因；本版本号今后与 Hub.sq 结构变更必须同步升。 */
internal const val HUB_DB_VERSION = 5L

/** 平台 SQLite driver 工厂；存储不可用返回 null → 调用方降级（同步引擎不可用须向 UI 呈现）。 */
expect fun createHubDriver(): SqlDriver?

/**
 * 打开 Hub 投影库：建表 + 版本守卫（与旧缓存库 CacheDriver 同模式——裸 CREATE TABLE 二次启动
 * 必抛 "table already exists"，必须以 PRAGMA user_version 判定；不符即 drop 重建）。
 * 任何失败返回 null。
 */
fun openHubDatabase(driver: SqlDriver?): HubDatabase? {
    driver ?: return null
    return runCatching {
        when (pragmaUserVersion(driver)) {
            HUB_DB_VERSION -> Unit
            0L -> {
                if (!allTablesExist(driver)) {
                    dropHubTables(driver)
                    HubDatabase.Schema.create(driver).value
                }
                setUserVersion(driver, HUB_DB_VERSION)
            }
            else -> {
                dropHubTables(driver)
                HubDatabase.Schema.create(driver).value
                setUserVersion(driver, HUB_DB_VERSION)
            }
        }
        HubDatabase(driver)
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

private val HUB_TABLES = listOf(
    "hubGateways", "hubConversations", "hubConvKeys", "hubMessages",
    "hubCommands", "hubReadCursors", "hubApprovals", "hubSyncState",
)

private fun allTablesExist(driver: SqlDriver): Boolean = HUB_TABLES.all { table ->
    driver.executeQuery(
        null,
        "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='$table'",
        { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(cursor.next().value && (cursor.getLong(0) ?: 0L) > 0L)
        },
        0,
    ).value
}

private fun dropHubTables(driver: SqlDriver) {
    HUB_TABLES.forEach { driver.execute(null, "DROP TABLE IF EXISTS $it", 0) }
}
