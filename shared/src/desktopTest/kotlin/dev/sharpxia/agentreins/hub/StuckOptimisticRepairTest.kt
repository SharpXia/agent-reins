package dev.sharpxia.agentreins.hub

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.hub.auth.CredentialStore
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 占位乐观行开屏修复 + promoteLocalEcho 内容守卫（desktopTest，2026-11-15 排序混乱修复轮）：
 * 现象面 = 用户行回显缺失/skip 的会话（hermes 实证）：乐观行（seq=Long.MAX 占位、delivered、
 * commandId 经 ack 盖章）永沉时间线底部、服务端权威行正文空。修法 = 开屏对账两步
 * （mergeStuckOptimisticContent 并入正文 + deleteStuckOptimisticBound 清已对位占位行）
 * + promoteLocalEcho 空回显不覆盖本地正文。
 */
class StuckOptimisticRepairTest {

    private fun newDb(): HubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HubDatabase.Schema.create(driver)
        return HubDatabase(driver)
    }

    private fun client(db: HubDatabase, scope: CoroutineScope) =
        DefaultHubClient(scope, HubRuntime(scope, RepairMemoryStore()), db)

    private fun repair(db: HubDatabase) {
        db.hubQueries.transaction {
            db.hubQueries.mergeStuckOptimisticContent(CONV)
            db.hubQueries.deleteStuckOptimisticBound(CONV)
        }
    }

    // ── ① 正文并入空权威行 + 占位行清除（主修复路径） ──

    @Test
    fun mergesPlaceholderIntoEmptyAuthoritativeAndDeletesPlaceholder() = runTest {
        val db = newDb()
        // 权威行（服务端合成行形态：真 seq、正文空、commandId 盖章）+ 占位行（MAX 占位、有正文）
        db.hubQueries.upsertMessage(
            messageId = "auth-1", conversationId = CONV, seq = 4L, role = "user", content = "",
            state = "final", uiState = "delivered", commandId = "cmd-1", idempotencyKey = "idem-1", ts = T0, reason = null, attachments = null,
        )
        db.hubQueries.upsertMessage(
            messageId = "ph-local-1", conversationId = CONV, seq = Long.MAX_VALUE, role = "user", content = "你好",
            state = "final", uiState = "delivered", commandId = "cmd-1", idempotencyKey = "idem-1", ts = T0, reason = null, attachments = null,
        )

        repair(db)
        val rows = client(db, backgroundScope).observeMessages(CONV).first()
        assertEquals(1, rows.size)
        assertEquals(4L, rows.single().seq) // 真位次（不再沉底）
        assertEquals("你好", rows.single().content) // 正文并入
    }

    // ── ② 非空权威内容不被覆盖（服务端已修复形态：权威行有正文，占位行直接清） ──

    @Test
    fun keepsNonEmptyAuthoritativeContentAndDropsPlaceholder() = runTest {
        val db = newDb()
        db.hubQueries.upsertMessage(
            messageId = "auth-1", conversationId = CONV, seq = 7L, role = "user", content = "服务端正文",
            state = "final", uiState = "delivered", commandId = "cmd-1", idempotencyKey = "idem-1", ts = T0, reason = null, attachments = null,
        )
        db.hubQueries.upsertMessage(
            messageId = "ph-local-1", conversationId = CONV, seq = Long.MAX_VALUE, role = "user", content = "本地正文",
            state = "final", uiState = "delivered", commandId = "cmd-1", idempotencyKey = "idem-1", ts = T0, reason = null, attachments = null,
        )

        repair(db)
        val rows = client(db, backgroundScope).observeMessages(CONV).first()
        assertEquals(1, rows.size)
        assertEquals(7L, rows.single().seq)
        assertEquals("服务端正文", rows.single().content)
    }

    // ── ③ 无权威行对位的占位行不动（在途/孤儿行不丢） ──

    @Test
    fun sparesPlaceholderWithoutBoundAuthoritativeRow() = runTest {
        val db = newDb()
        db.hubQueries.upsertMessage(
            messageId = "ph-solo", conversationId = CONV, seq = Long.MAX_VALUE, role = "user", content = "在途消息",
            state = "final", uiState = "delivered", commandId = "cmd-orphan", idempotencyKey = "idem-9", ts = T0, reason = null, attachments = null,
        )

        repair(db)
        val rows = client(db, backgroundScope).observeMessages(CONV).first()
        assertEquals(1, rows.size)
        assertEquals(Long.MAX_VALUE, rows.single().seq)
        assertEquals("在途消息", rows.single().content)
    }

    // ── ④ 无占位行可并入的空权威行不动（不虚构内容） ──

    @Test
    fun sparesEmptyAuthoritativeWithoutPlaceholder() = runTest {
        val db = newDb()
        db.hubQueries.upsertMessage(
            messageId = "auth-2", conversationId = CONV, seq = 5L, role = "user", content = "",
            state = "final", uiState = "delivered", commandId = "cmd-2", idempotencyKey = "idem-2", ts = T0, reason = null, attachments = null,
        )

        repair(db)
        val rows = client(db, backgroundScope).observeMessages(CONV).first()
        assertEquals(1, rows.size)
        assertEquals("", rows.single().content)
    }

    // ── ⑤ promoteLocalEcho 空回显不覆盖本地正文（守卫直测） ──

    @Test
    fun promoteLocalEchoKeepsLocalContentOnEmptyEcho() = runTest {
        val db = newDb()
        db.hubQueries.upsertMessage(
            messageId = "tmp-send", conversationId = CONV, seq = Long.MAX_VALUE, role = "user", content = "草稿正文",
            state = "final", uiState = "sending", commandId = null, idempotencyKey = "idem-5", ts = T0, reason = null, attachments = null,
        )

        db.hubQueries.promoteLocalEcho(
            messageId = "srv-5", content = "", seq = 6L, commandId = "cmd-5", ts = T1, idempotencyKey = "idem-5",
        )
        val row = db.hubQueries.selectMessages(CONV).executeAsList().single()
        assertEquals("草稿正文", row.content) // 空回显不抹
        assertEquals(6L, row.seq) // seq 照常提升（真位次）
        assertEquals("srv-5", row.messageId)
    }

    @Test
    fun promoteLocalEchoTakesNonEmptyEchoContent() = runTest {
        val db = newDb()
        db.hubQueries.upsertMessage(
            messageId = "tmp-send", conversationId = CONV, seq = Long.MAX_VALUE, role = "user", content = "本地占位",
            state = "final", uiState = "sending", commandId = null, idempotencyKey = "idem-6", ts = T0, reason = null, attachments = null,
        )

        db.hubQueries.promoteLocalEcho(
            messageId = "srv-6", content = "回显正文", seq = 8L, commandId = "cmd-6", ts = T1, idempotencyKey = "idem-6",
        )
        val row = db.hubQueries.selectMessages(CONV).executeAsList().single()
        assertEquals("回显正文", row.content)
        assertEquals(8L, row.seq)
    }

    companion object {
        const val CONV = "01JCONV00000000000000000000"
        const val T0 = "2026-11-15T20:00:00.000Z"
        const val T1 = "2026-11-15T20:01:00.000Z"
    }
}

/** 内存凭据仓（测试用；与 GatewaySurfaceTest 的同名私有类重名——本文件用独立命名避免同包顶层类重声明冲突）。 */
private class RepairMemoryStore : CredentialStore {
    private var token: String? = null
    override suspend fun saveRefreshToken(token: String) { this.token = token }
    override suspend fun loadRefreshToken(): String? = token
    override suspend fun clear() { token = null }
}
