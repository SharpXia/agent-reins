package dev.sharpxia.agentreins.hub

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.hub.projection.DefaultEventProjector
import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * FLOW-4 债清偿（2026-11-22）：hubCommands 终态行年龄剪枝用例——
 * ① 7 天窗外四终态行剪除（read.mark/message.send 高频推进型一并入剪；message.send 终态也在被剪
 *    范围，7 天窗即其映射学习保留语义——消费面 resolveConversation② 在分钟级，窗后无消费场景）；
 * ② 窗内终态行保留；③ 非终态行窗龄无关一律不动（retryUnfinished 与探针消费面）；
 * ④ 运行期挂点节流：每 32 次终态转移触发一次剪枝（command.acked 终态帧驱动，步长内不动）。
 * 判据口径：createdAt 为 epoch 毫秒数字串（DefaultCommandClient.nowText 唯一写入口径），
 * 典序比较即数值序；updatedAt 会被事件路径覆写成 ISO 串，不参与判据。
 */
class HubCommandsPruneTest {

    private fun newDb(): HubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HubDatabase.Schema.create(driver)
        return HubDatabase(driver)
    }

    /** 直插命令行（createdAt 手工给定 = epoch 毫秒数字串；updatedAt 同值，不影响剪枝判据）。 */
    private fun seed(
        db: HubDatabase,
        key: String,
        type: String,
        state: String,
        createdAt: String,
        conversationId: String? = CONV,
    ) {
        db.hubQueries.insertCommand(
            idempotencyKey = key, commandId = null, conversationId = conversationId,
            runId = null, approvalId = null, type = type,
            payloadJson = if (type == "read.mark") "1" else "{\"idempotencyKey\":\"$key\"}",
            state = state, errorCode = null, createdAt = createdAt, updatedAt = createdAt,
        )
    }

    // ── ①②③ 年龄剪枝语义：窗外终态剪、窗内终态留、非终态不动（固定钟，零真实时钟依赖） ──

    @Test
    fun agePruneRemovesAgedTerminalKeepsRecentAndNonTerminal() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        val now = 1_764_000_000_000L // 固定钟（epoch 毫秒）
        val aged = (now - 8L * DAY_MS).toString()  // 8 天前：窗外
        val fresh = (now - 1L * DAY_MS).toString() // 1 天前：窗内

        // 窗外终态 220 条（>200）：四终态轮转，read.mark/message.send 交错（高频推进型一并入剪）
        repeat(220) { i ->
            seed(db, "aged-$i", if (i % 2 == 0) "read.mark" else "message.send", TERMINAL_STATES[i % TERMINAL_STATES.size], aged)
        }
        // 窗内终态 30 条：保留（含 message.send——窗口即其保留语义）
        repeat(30) { i ->
            seed(db, "fresh-$i", if (i % 2 == 0) "read.mark" else "message.send", TERMINAL_STATES[i % TERMINAL_STATES.size], fresh)
        }
        // 非终态 10 条：窗龄无关不剪（窗龄 8 天仍留——retryUnfinished 消费面优先级高于清理）
        repeat(10) { i ->
            seed(db, "live-$i", "message.send", NON_TERMINAL_STATES[i % NON_TERMINAL_STATES.size], aged)
        }
        assertEquals(250, db.hubQueries.countTerminalCommands().executeAsOne().toInt())
        assertEquals(10, db.hubQueries.selectNonTerminalCommands().executeAsList().size)

        p.pruneAgedTerminalCommands(now)
        assertEquals(30, db.hubQueries.countTerminalCommands().executeAsOne().toInt()) // 窗外终态 220 条全剪
        assertEquals(10, db.hubQueries.selectNonTerminalCommands().executeAsList().size) // 非终态不动
        assertNull(db.hubQueries.selectCommandByKey("aged-0").executeAsOneOrNull()) // read.mark 窗外已剪
        assertNull(db.hubQueries.selectCommandByKey("aged-1").executeAsOneOrNull()) // message.send 窗外已剪
        assertEquals("COMPLETED", db.hubQueries.selectCommandByKey("fresh-0").executeAsOne().state) // 窗内终态保留
        assertNotNull(db.hubQueries.selectCommandByKey("fresh-1").executeAsOneOrNull())
        assertEquals("PENDING_LOCAL", db.hubQueries.selectCommandByKey("live-0").executeAsOne().state)
    }

    // ── ④ 运行期挂点节流：每 32 次终态转移触发一次（步长内窗外终态行不动） ──

    @Test
    fun terminalTransitionsThrottlePruneEvery32() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        // 窗外行 createdAt 取 epoch 极早值（1970）：节流路径的剪枝用真实钟缺省 nowMs，
        // 极早值对任意合理真实钟都窗外，用例不依赖测试机时钟。
        seed(db, "aged-terminal", "read.mark", "COMPLETED", "1000")
        seed(db, "aged-live", "message.send", "RUNNING", "1000")

        repeat(31) { i -> p.applyEvent(ackedEvent(i, "aged-terminal")) } // 步长内：不剪
        assertEquals(1, db.hubQueries.countTerminalCommands().executeAsOne().toInt())
        assertNotNull(db.hubQueries.selectCommandByKey("aged-terminal").executeAsOneOrNull())

        p.applyEvent(ackedEvent(31, "aged-terminal")) // 第 32 次终态转移：节流点触发剪枝
        assertEquals(0, db.hubQueries.countTerminalCommands().executeAsOne().toInt())
        assertNull(db.hubQueries.selectCommandByKey("aged-terminal").executeAsOneOrNull())
        assertNotNull(db.hubQueries.selectCommandByKey("aged-live").executeAsOneOrNull()) // 非终态不受影响
    }

    /** command.acked 终态帧（COMPLETED；sessionKey=null → 映射学习早退，仅走状态翻转与计数）。 */
    private fun ackedEvent(index: Int, key: String) = EventEnvelope(
        id = "acked-$index", seq = 1L, type = EventEnvelope.COMMAND_ACKED,
        gatewayId = GW, sessionKey = null, runId = null, ts = T0,
        data = buildJsonObject {
            put("commandId", "cmd-$index"); put("idempotencyKey", key); put("commandType", "read.mark")
            put("status", "COMPLETED"); put("previousStatus", "GATEWAY_ACKED")
        },
    )

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val CONV = "01JCONV00000000000000000000"
        private const val GW = "01JGW000000000000000000000"
        private const val T0 = "2026-11-22T00:00:00.000Z"
        private val TERMINAL_STATES = listOf("COMPLETED", "FAILED", "CANCELLED", "INDETERMINATE")
        private val NON_TERMINAL_STATES = listOf("PENDING_LOCAL", "PERSISTED", "WAITING_BRIDGE", "DELIVERING", "GATEWAY_ACKED", "RUNNING")
    }
}
