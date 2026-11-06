package dev.sharpxia.agentreins.hub.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import dev.sharpxia.agentreins.hubdb.HubDatabase

/** Hub 投影库 schema 版本（投影可整库重建；升版即 drop 重建，O-01 持久化最小集随重建后从服务端重取）。
 *  7 = additive（wp-4 通知归属两跳学习）：hubSessionAgents（session 域两跳归属暂存）
 *  + hubConvKeys.twoHop 标记列（连结行分域消费，app 主会话隔离对位）。
 *  6 = additive（wp-15 工具活动捕获）：hubToolEvents（tool.* 三态本地捕获，「工作过程」披露条数据面）
 *  + hubRunStatus（run.status 阶段帧，wp-15 可选项）。
 *  5 = v1.1 additive（wp-11）：hubConversations.gatewayId（ConversationRow required 透出，
 *  #16 新建会话入口 (gatewayId, agentId) 判据与网关维度展示）。
 *  4 = v1.1 additive（wp-5 流式投影修复）：hubMessages.reason（事件/快照双路径共用失败原因列）
 *  + hubConversations.activeRunId（run.* 事件捕获，wp-8 Stop 取值）。
 *  3 = 契约 v1.1.0 去 session 化表结构（conversationId 主键 + hubConvKeys；1 = 0.5.0 的 sessionKey 主键代）。
 *  0.5.1/0.5.2 曾改表未升版本号——守卫放行旧结构，v3 SQL 打 v1 表抛异常经 uncaught handler SIGABRT，
 *  即真机「点开会话即崩」根因；本版本号今后与 Hub.sq 结构变更必须同步升。 */
internal const val HUB_DB_VERSION = 7L

/** 版本记账行键（hubSyncState；beta-0.5.4 真机死库根因修复——PRAGMA 层归 SQLiter，见 openHubDatabase）。 */
private const val DB_VERSION_KEY = "db_version"

/** 平台 SQLite driver 工厂；存储不可用返回 null → 调用方降级（同步引擎不可用须向 UI 呈现）。 */
expect fun createHubDriver(): SqlDriver?

/**
 * 打开 Hub 投影库：建表 + 版本守卫（不符即 drop 重建）。
 *
 * 双层版本口径（beta-0.5.4 真机死库根因修复）：SQLiter/OpenHelper 打开时按生成
 * `Schema.version`（无 .sqm 恒为 1）比对 PRAGMA user_version，不符即触发 migration——
 * 无迁移可跑 → 「attempted to run migration and failed. closing connection.」→ 连接被关，
 * 数据面整体死透。故 PRAGMA user_version **恒等于 Schema.version**（本守卫不再写自定义值），
 * 本仓自己的版本记账走 hubSyncState.db_version；两处不一致或旧污染文件（PRAGMA 残留 7）
 * 一律 drop 重建（投影可弃，O-01 随重建重取）。任何失败返回 null。
 */
fun openHubDatabase(driver: SqlDriver?): HubDatabase? {
    driver ?: return null
    return runCatching {
        val schemaV = HubDatabase.Schema.version
        val pragma = pragmaUserVersion(driver)
        val tables = allTablesExist(driver)
        val dbV = syncStateDbVersion(driver)
        when {
            dbV == HUB_DB_VERSION && pragma == schemaV -> Unit
            // 行被清（wipeLocalData/重建遗留）但表与 PRAGMA 均健康：免重建，仅补记账行
            dbV == null && tables && pragma == schemaV -> writeDbVersion(driver)
            else -> recreate(driver, schemaV)
        }
        HubDatabase(driver)
    }.getOrNull()
}

private fun recreate(driver: SqlDriver, schemaV: Long) {
    dropHubTables(driver)
    HubDatabase.Schema.create(driver).value
    setUserVersion(driver, schemaV)
    writeDbVersion(driver)
}

private fun pragmaUserVersion(driver: SqlDriver): Long =
    driver.executeQuery(
        null,
        "PRAGMA user_version",
        { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        },
        0,
    ).value

private fun setUserVersion(driver: SqlDriver, version: Long) {
    driver.execute(null, "PRAGMA user_version = $version", 0)
}

private fun syncStateDbVersion(driver: SqlDriver): Long? =
    runCatching {
        driver.executeQuery(
            null,
            "SELECT value FROM hubSyncState WHERE key = '$DB_VERSION_KEY'",
            { cursor ->
                QueryResult.Value(
                    if (cursor.next().value) cursor.getString(0)?.toLongOrNull() else null,
                )
            },
            0,
        ).value
    }.getOrNull()

private fun writeDbVersion(driver: SqlDriver) {
    driver.execute(
        null,
        "INSERT OR REPLACE INTO hubSyncState(key, value) VALUES ('$DB_VERSION_KEY', '$HUB_DB_VERSION')",
        0,
    )
}

private val HUB_TABLES = listOf(
    "hubGateways", "hubConversations", "hubConvKeys", "hubSessionAgents", "hubMessages",
    "hubCommands", "hubReadCursors", "hubApprovals", "hubToolEvents", "hubRunStatus", "hubSyncState",
)

private fun allTablesExist(driver: SqlDriver): Boolean = HUB_TABLES.all { table ->
    driver.executeQuery(
        null,
        "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='$table'",
        { cursor ->
            QueryResult.Value(cursor.next().value && (cursor.getLong(0) ?: 0L) > 0L)
        },
        0,
    ).value
}

private fun dropHubTables(driver: SqlDriver) {
    HUB_TABLES.forEach { driver.execute(null, "DROP TABLE IF EXISTS $it", 0) }
}
