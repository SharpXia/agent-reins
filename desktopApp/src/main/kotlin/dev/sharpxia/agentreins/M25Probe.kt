package dev.sharpxia.agentreins

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.db.SqlCacheStore
import dev.sharpxia.agentreins.model.Message
import dev.sharpxia.agentreins.model.Session
import dev.sharpxia.agentreins.openclaw.OpenClawClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * M2.5 数据基座网关探针（仅本机手动运行：`source .env && ./gradlew :desktopApp:probeM25`）。
 *
 * 对真实网关验证三项 M2.5 协议假设（输出不含任何凭据）：
 * ① sessions.list 全量分页：refreshSessions 循环拉取后会话数应远超单页 100；
 * ② sessions.send 自定义 idempotencyKey：受理后该 turn 进 history → outbox 退休
 *    （outboxPendingCount 归零）——退休对账的协议前提；
 * ③ 缓存落库：列表/消息缓存行数与会话状态一致。
 */
fun main() {
    val url = System.getenv("OPENCLAW_GATEWAY_URL") ?: error("OPENCLAW_GATEWAY_URL not set")
    val key = System.getenv("OPENCLAW_API_KEY") ?: error("OPENCLAW_API_KEY not set")
    runBlocking {
        val cache = SqlCacheStore.open(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val client = OpenClawClient(this, cache)
        val statusLog = launch {
            client.status.collect { println("[probe] status -> ${it::class.simpleName}") }
        }
        try {
            println("[probe] connecting …")
            client.connect(GatewayConfig(url, key))

            // ① 全量分页
            val sessions = client.sessions.value
            println("[probe] sessions total=${sessions.size} (单页 100；>100 即分页循环生效)")
            if (sessions.size <= 100) {
                println("[probe] FAIL: 会话数未超过单页上限，分页循环可能未生效")
                return@runBlocking
            }
            val main = sessions.firstOrNull { it.id == "agent:main:main" }
                ?: sessions.firstOrNull()
                ?: error("no session found")

            // ② 发送 + 退休对账：outbox 行应在 history 出现该 turn 后被删除
            val sendText = "agentreins-m25-probe: 请用 exec 执行 `echo probe-ok` 后结束（自动探针）"
            client.send(main.id, sendText)
            println("[probe] sent with idempotencyKey, waiting for history retire …")
            var pendingCount = -1
            var waited = 0
            while (waited < 120_000) {
                pendingCount = client.outboxPendingCount.value
                if (pendingCount == 0) break
                delay(2_000); waited += 2_000
            }
            if (pendingCount != 0) {
                println("[probe] FAIL: outbox 未退休 pending=$pendingCount（idempotencyKey 可能未被网关回写 history）")
                return@runBlocking
            }
            println("[probe] outbox retire PASS（该 turn 已进 history，幂等键对账成功）")

            // 消息确实在历史里（assistant 已回复）
            client.loadHistory(main.id)
            val conv = client.conversation(main.id).value
            val userMsg = conv.messages.lastOrNull { it.role == Message.Role.USER }
            println("[probe] last user msg: ${userMsg?.content?.take(60)}")

            // ③ 缓存落库
            val cachedMsgs = cache.loadMessages(SqlCacheStore.DEFAULT_GATEWAY_ID, main.id)
            val cachedSessions = cache.loadSessions(SqlCacheStore.DEFAULT_GATEWAY_ID)
            println("[probe] cache: sessions=${cachedSessions.size} messages(${main.id})=${cachedMsgs.size}")
            if (cachedSessions.isEmpty() || cachedMsgs.isEmpty()) {
                println("[probe] FAIL: 缓存未落库")
                return@runBlocking
            }
            val cachedMain = cachedSessions.firstOrNull { it.id == main.id }
            println("[probe] cached main session row: unread=${cachedMain?.unread} kind=${cachedMain?.kind} pinned=${cachedMain?.pinned}")

            // ④ 草稿 kv
            cache.kvPut(SqlCacheStore.DEFAULT_GATEWAY_ID, "draft:${main.id}", "probe-draft")
            val draft = cache.kvGet(SqlCacheStore.DEFAULT_GATEWAY_ID, "draft:${main.id}")
            println("[probe] draft kv roundtrip: $draft")
            if (draft != "probe-draft") {
                println("[probe] FAIL: kv 草稿读写异常")
                return@runBlocking
            }
            println("[probe] PASS")
        } finally {
            statusLog.cancel()
            client.disconnect()
        }
        Unit
    }
}
