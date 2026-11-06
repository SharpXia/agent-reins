package dev.sharpxia.agentreins.hub

import dev.sharpxia.agentreins.hub.db.HUB_DB_VERSION
import dev.sharpxia.agentreins.hub.db.openHubDatabase
import dev.sharpxia.agentreins.hubdb.HubDatabase
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * beta-0.5.4 真机死库根因回归：守卫曾把自定义值写进 PRAGMA user_version（≠生成
 * Schema.version=1），SQLiter 层第二次打开触发「无迁移可跑」migration → 连接被关 →
 * 数据面整体死透。修复后：PRAGMA 恒等于 Schema.version，版本记账走 hubSyncState.db_version。
 * 用文件-backed driver（IN_MEMORY 每连接独立，无法覆盖二次打开路径）。
 */
class HubDriverOpenGuardTest {
    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File = File.createTempFile("hubdrv", "").let { f ->
        f.delete()
        val d = File(f.absolutePath)
        d.mkdirs()
        dirs.add(d)
        d
    }

    private fun driver(dir: File) =
        app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver("jdbc:sqlite:${File(dir, "agentreins-hub.db").absolutePath}")

    private fun scalar(driver: app.cash.sqldelight.db.SqlDriver, sql: String): String? =
        runCatching {
            driver.executeQuery(null, sql, { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 0).value
        }.getOrNull()

    /** 首次打开建库记账；第二次打开不得重建（数据幸存）且 PRAGMA/记账行稳定。 */
    @Test
    fun secondOpenKeepsDataAndStaysConsistent() {
        val dir = tempDir()
        val d1 = driver(dir)
        val db1 = openHubDatabase(d1)
        assertNotNull(db1)
        assertEquals(HubDatabase.Schema.version.toString(), scalar(d1, "PRAGMA user_version"))
        assertEquals(HUB_DB_VERSION.toString(), scalar(d1, "SELECT value FROM hubSyncState WHERE key='db_version'"))
        db1.hubQueries.insertGateway(
            gatewayId = "gw-1", name = "n", online = 1L, lastSeenAt = null, agentsJson = "[]",
        )
        d1.close()

        val d2 = driver(dir)
        val db2 = openHubDatabase(d2)
        assertNotNull(db2)
        // 第二次打开不重建：首启写入的行必须幸存
        assertEquals("gw-1", scalar(d2, "SELECT gatewayId FROM hubGateways LIMIT 1"))
        assertEquals(HubDatabase.Schema.version.toString(), scalar(d2, "PRAGMA user_version"))
        assertEquals(HUB_DB_VERSION.toString(), scalar(d2, "SELECT value FROM hubSyncState WHERE key='db_version'"))
        d2.close()
    }

    /** 0.5.4 污染文件（PRAGMA=7、无记账行）：打开即对齐重建，不再输出死库。 */
    @Test
    fun pollutedPragmaFileIsHealedOnOpen() {
        val dir = tempDir()
        val d1 = driver(dir)
        // 直接手工污染：无 hubSyncState 表、PRAGMA 停在 7（0.5.4 旧守卫遗留形态）
        d1.execute(null, "PRAGMA user_version = $HUB_DB_VERSION", 0)
        assertNull(scalar(d1, "SELECT value FROM hubSyncState WHERE key='db_version'"))

        val db = openHubDatabase(d1)
        assertNotNull(db)
        assertEquals(HubDatabase.Schema.version.toString(), scalar(d1, "PRAGMA user_version"))
        assertEquals(HUB_DB_VERSION.toString(), scalar(d1, "SELECT value FROM hubSyncState WHERE key='db_version'"))
        d1.close()

        // 愈合后二次打开保持稳定
        val d2 = driver(dir)
        assertNotNull(openHubDatabase(d2))
        assertEquals(HubDatabase.Schema.version.toString(), scalar(d2, "PRAGMA user_version"))
        d2.close()
    }

    /** wipeLocalData 清行（表在、PRAGMA 健康）：重开免重建，仅补记账行。 */
    @Test
    fun wipedVersionRowIsRefilledWithoutRebuild() {
        val dir = tempDir()
        val d1 = driver(dir)
        val db1 = openHubDatabase(d1)
        assertNotNull(db1)
        db1.hubQueries.insertGateway(
            gatewayId = "gw-keep", name = "n", online = 1L, lastSeenAt = null, agentsJson = "[]",
        )
        // 模拟 wipeLocalData：清行不删表（含 db_version 记账行）
        d1.execute(null, "DELETE FROM hubSyncState", 0)
        assertNull(scalar(d1, "SELECT value FROM hubSyncState WHERE key='db_version'"))
        d1.close()

        val d2 = driver(dir)
        val db2 = openHubDatabase(d2)
        assertNotNull(db2)
        // 免重建：wipe 前的行幸存（drop 重建会清掉它）
        assertEquals("gw-keep", scalar(d2, "SELECT gatewayId FROM hubGateways LIMIT 1"))
        assertEquals(HUB_DB_VERSION.toString(), scalar(d2, "SELECT value FROM hubSyncState WHERE key='db_version'"))
        d2.close()
    }
}
