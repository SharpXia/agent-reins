package dev.sharpxia.agentreins.hub

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 2026-11-06 UX 轮 #3 回归：无信息量行（心跳「无回复」/ 空正文 assistant 行、心跳模板
 * 合成行）不进时间线、不计未读；真实回复行与用户行不受影响。行仍落库（seq 数学/翻页
 * 锚不受影响），过滤发生在显示与未读查询面。
 */
class NoiseRowFilterTest {

    private fun newDb(): HubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HubDatabase.Schema.create(driver)
        return HubDatabase(driver)
    }

    private fun insert(db: HubDatabase, id: String, seq: Long, role: String, content: String) {
        db.hubQueries.upsertMessage(
            messageId = id, conversationId = CONV, seq = seq, role = role, content = content,
            state = "final", reason = null, uiState = null, commandId = null,
            idempotencyKey = null, ts = T0,
        )
    }

    @Test
    fun timelineAndUnreadExcludeNoiseRows() {
        val db = newDb()
        insert(db, "m-1", 1L, "user", "测试")
        insert(db, "m-2", 2L, "assistant", "无回复") // 心跳协议答复 → 隐藏
        insert(db, "m-3", 3L, "assistant", "  ") // 心跳空行 → 隐藏
        insert(db, "m-4", 4L, "", "<!-- Heartbeat template; do not parse -->") // 心跳模板合成行 → 隐藏
        insert(db, "m-5", 5L, "assistant", "真实回复") // 保留
        insert(db, "m-6", 6L, "", "上下文已压缩") // 真合成行 → 保留
        insert(db, "m-7", 7L, "assistant", "无回复是什么？的详细解释") // 含「无回复」但非闭值 → 保留

        val visible = db.hubQueries.selectMessages(CONV).executeAsList()
        assertEquals(listOf("m-1", "m-5", "m-6", "m-7"), visible.map { it.messageId })

        // 未读（readUpTo=0）：仅 m-5 计入（assistant final 非无信息量；合成行/m-7 在 readUpTo
        // 之上且非闭值——m-7 计入；m-1 是用户行不计）
        assertEquals(2L, db.hubQueries.countUnread(CONV, 0L).executeAsOne())

        // 翻页窗口同口径（DESC 呈现）：before=7 → 可见行 m-5、m-6、m-1
        val earlier = db.hubQueries.selectMessagesBefore(CONV, seq = 7L, limit = 50).executeAsList()
        assertEquals(listOf("m-6", "m-5", "m-1"), earlier.map { it.messageId })
    }

    companion object {
        private const val CONV = "01JCONV00000000000000000000"
        private const val T0 = "2026-11-06T08:00:00.000Z"
    }
}
