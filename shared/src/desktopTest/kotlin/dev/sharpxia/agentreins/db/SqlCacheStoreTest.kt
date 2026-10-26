package dev.sharpxia.agentreins.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.model.Message
import dev.sharpxia.agentreins.model.Session
import dev.sharpxia.agentreins.model.ToolCallEvent
import dev.sharpxia.agentreins.openclaw.Outbox
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 缓存层（SQLDelight 真库，JVM in-memory）：四表读写往返 + 降级路径。 */
class SqlCacheStoreTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val store = SqlCacheStore.open(driver)

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // ---- cachedSessions ----

    @Test
    fun sessionsRoundTrip() {
        val list = listOf(
            Session(id = "a", title = "Main", lastActiveAtMs = 100, unread = 2, pinned = true, kind = "main"),
            Session(id = "b", title = "Archived", archived = true, lastActiveAtMs = 50),
        )
        store.saveSessions("default", list)
        val loaded = store.loadSessions("default")
        assertEquals(2, loaded.size)
        val a = loaded.first { it.id == "a" }
        assertEquals("Main", a.title)
        assertEquals(2, a.unread)
        assertTrue(a.pinned)
        assertEquals("main", a.kind)
        assertTrue(loaded.first { it.id == "b" }.archived)
    }

    @Test
    fun sessionsScopedByGateway() {
        store.saveSessions("default", listOf(Session(id = "a")))
        assertEquals(0, store.loadSessions("other").size)
    }

    @Test
    fun sessionsSaveReplaces() {
        store.saveSessions("default", listOf(Session(id = "a"), Session(id = "b")))
        store.saveSessions("default", listOf(Session(id = "a", title = "fresh")))
        val loaded = store.loadSessions("default")
        assertEquals(listOf("a"), loaded.map { it.id })
        assertEquals("fresh", loaded.first().title)
    }

    // ---- cachedMessages ----

    @Test
    fun messagesRoundTripPreservesTypes() {
        val entries = listOf(
            Message(id = "m1", sessionId = "s", role = Message.Role.USER, content = "hi", timestampMs = 1),
            ToolCallEvent(id = "t1", sessionId = "s", toolName = "exec", argumentsJson = "{}", state = ToolCallEvent.State.SUCCEEDED),
            Message(id = "m2", sessionId = "s", role = Message.Role.ASSISTANT, content = "hello"),
        )
        store.saveMessages("default", "s", entries)
        val loaded = store.loadMessages("default", "s")
        assertEquals(3, loaded.size)
        assertEquals("m1", loaded[0].id)
        assertTrue(loaded[0] is Message && (loaded[0] as Message).role == Message.Role.USER)
        val tool = loaded[1] as ToolCallEvent
        assertEquals("exec", tool.toolName)
        assertEquals("{}", tool.argumentsJson)
        assertEquals(ToolCallEvent.State.SUCCEEDED, tool.state)
        assertEquals("m2", loaded[2].id)
    }

    @Test
    fun messagesScopedBySessionAndGateway() {
        store.saveMessages("default", "s1", listOf(Message(id = "m1", sessionId = "s1", role = Message.Role.USER, content = "x")))
        assertEquals(0, store.loadMessages("default", "s2").size)
        assertEquals(0, store.loadMessages("other", "s1").size)
    }

    // ---- outboxCommands ----

    @Test
    fun outboxUpsertLoadDelete() {
        val cmd = Outbox.Command(
            idempotencyKey = "out-1", sessionKey = "s", text = "hello",
            status = Outbox.Command.Status.PENDING, createdAtMs = 10,
        )
        store.upsertOutbox(cmd, "default")
        assertEquals(listOf(cmd), store.loadOutbox("default"))

        // 状态迁移 upsert 覆盖（SENT + retry）
        store.upsertOutbox(cmd.copy(status = Outbox.Command.Status.SENT, retryCount = 1, lastError = null), "default")
        val after = store.loadOutbox("default")
        assertEquals(1, after.size)
        assertEquals(Outbox.Command.Status.SENT, after.first().status)
        assertEquals(1, after.first().retryCount)

        store.deleteOutbox("out-1", "default")
        assertEquals(0, store.loadOutbox("default").size)
    }

    @Test
    fun outboxOrderedByCreatedAt() {
        store.upsertOutbox(Outbox.Command(idempotencyKey = "k2", sessionKey = "s", text = "2", createdAtMs = 20), "default")
        store.upsertOutbox(Outbox.Command(idempotencyKey = "k1", sessionKey = "s", text = "1", createdAtMs = 10), "default")
        assertEquals(listOf("k1", "k2"), store.loadOutbox("default").map { it.idempotencyKey })
    }

    // ---- kv ----

    @Test
    fun kvRoundTripAndOverwrite() {
        assertNull(store.kvGet("default", "draft:s1"))
        store.kvPut("default", "draft:s1", "hello")
        assertEquals("hello", store.kvGet("default", "draft:s1"))
        store.kvPut("default", "draft:s1", "hello world")
        assertEquals("hello world", store.kvGet("default", "draft:s1"))
        assertNull(store.kvGet("other", "draft:s1"))
    }

    // ---- 降级 ----

    @Test
    fun nullDriverFallsBackToNoop() {
        val noop = SqlCacheStore.open(null)
        assertTrue(!noop.available)
        noop.saveSessions("default", listOf(Session(id = "a"))) // 不抛
        assertEquals(0, noop.loadSessions("default").size)
    }

    @Test
    fun reopenSameFileDoesNotDegradeToNoop() {
        // P0 回归：裸 CREATE TABLE 在二次启动必抛 "table already exists"，曾致缓存永久
        // 静默降级 Noop。修复 = PRAGMA user_version 引导守卫，同文件二次打开必须可用。
        val dir = java.nio.file.Files.createTempDirectory("agentreins-p0")
        val dbFile = dir.resolve("agentreins.db").toFile()
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        try {
            // 第一次启动：建表 + 写入数据，随后关闭连接（模拟进程退出）
            val d1 = JdbcSqliteDriver(url)
            val first = SqlCacheStore.open(d1)
            assertTrue(first.available, "首次打开应可用")
            first.saveSessions("default", listOf(Session(id = "persisted", title = "冷启动可见")))
            d1.close()

            // 第二次启动（新 driver，同一文件）：必须仍是真库而非 Noop，且数据可读
            val d2 = JdbcSqliteDriver(url)
            val second = SqlCacheStore.open(d2)
            assertTrue(second.available, "二次打开不得降级 Noop（P0）")
            val loaded = second.loadSessions("default")
            assertEquals(listOf("persisted"), loaded.map { it.id })
            assertEquals("冷启动可见", loaded.first().title)
            d2.close()
        } finally {
            dbFile.delete(); dir.toFile().delete()
        }
    }
}
