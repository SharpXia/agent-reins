package dev.sharpxia.agentreins.db

import app.cash.sqldelight.db.SqlDriver
import dev.sharpxia.agentreins.model.ChatEntry
import dev.sharpxia.agentreins.model.Session
import dev.sharpxia.agentreins.openclaw.Outbox

/** CacheStore 的 SQLDelight 实现（单库四表，见 AgentReins.sq）。全部写操作 swallow 异常降级——缓存层故障不能拖垮在线功能。 */
class SqlCacheStore private constructor(private val queries: dev.sharpxia.agentreins.db.AgentReinsQueries) : CacheStore {

    override val available: Boolean = true

    override fun loadSessions(gatewayId: String): List<Session> = runCatching {
        queries.selectSessions(gatewayId).executeAsList().map { row ->
            Session(
                id = row.key,
                title = row.title,
                preview = row.preview,
                lastActiveAtMs = row.updatedAtMs,
                unread = row.unread.toInt(),
                pinned = row.pinned != 0L,
                archived = row.archived != 0L,
                kind = row.kind,
            )
        }
    }.getOrDefault(emptyList())

    override fun saveSessions(gatewayId: String, sessions: List<Session>) {
        runCatching {
            // 清空 + 重写同一事务：失败回滚，不会把旧列表清光（P3 完整性）
            queries.transaction {
                queries.clearSessions(gatewayId)
                sessions.forEach { s ->
                    queries.replaceAllSession(
                        gatewayId = gatewayId,
                        key = s.id,
                        title = s.title,
                        preview = s.preview,
                        updatedAtMs = s.lastActiveAtMs,
                        unread = s.unread.toLong(),
                        pinned = if (s.pinned) 1L else 0L,
                        archived = if (s.archived) 1L else 0L,
                        kind = s.kind,
                    )
                }
            }
        }
    }

    override fun loadMessages(gatewayId: String, sessionKey: String): List<ChatEntry> = runCatching {
        queries.selectMessages(gatewayId, sessionKey).executeAsList()
            .mapNotNull { EntryCodec.decode(it.payload) }
    }.getOrDefault(emptyList())

    override fun saveMessages(gatewayId: String, sessionKey: String, entries: List<ChatEntry>) = runCatching {
        queries.transaction {
            entries.forEachIndexed { idx, entry ->
                queries.replaceMessage(
                    gatewayId = gatewayId,
                    sessionKey = sessionKey,
                    entryId = entry.id,
                    ord = idx.toLong(),
                    payload = EntryCodec.encode(entry),
                    tsMs = (entry as? dev.sharpxia.agentreins.model.Message)?.timestampMs,
                )
            }
        }
    }.getOrDefault(Unit)

    override fun loadOutbox(gatewayId: String): List<Outbox.Command> = runCatching {
        queries.selectOutboxCommands(gatewayId).executeAsList().map { row ->
            Outbox.Command(
                idempotencyKey = row.idempotencyKey,
                sessionKey = row.sessionKey,
                text = row.text,
                status = Outbox.Command.Status.valueOf(row.status),
                retryCount = row.retryCount.toInt(),
                lastError = row.lastError,
                createdAtMs = row.createdAtMs,
            )
        }
    }.getOrDefault(emptyList())

    override fun upsertOutbox(command: Outbox.Command, gatewayId: String) {
        runCatching {
            queries.insertOutboxCommand(
                idempotencyKey = command.idempotencyKey,
                gatewayId = gatewayId,
                sessionKey = command.sessionKey,
                text = command.text,
                status = command.status.name,
                retryCount = command.retryCount.toLong(),
                lastError = command.lastError,
                createdAtMs = command.createdAtMs,
            )
        }
    }

    override fun deleteOutbox(idempotencyKey: String, gatewayId: String) {
        runCatching { queries.deleteOutboxCommand(idempotencyKey, gatewayId) }
    }

    override fun kvGet(gatewayId: String, key: String): String? = runCatching {
        queries.getKv(gatewayId, key).executeAsOneOrNull()
    }.getOrNull()

    override fun kvPut(gatewayId: String, key: String, value: String) {
        runCatching { queries.putKv(gatewayId, key, value) }
    }

    companion object {
        /** v1.0 恒为单网关常量（多网关 UI 仍是非目标，A2 只留列缝）。 */
        const val DEFAULT_GATEWAY_ID = "default"

        /** 打开缓存库；driver 为 null 或建库失败一律 Noop 降级。 */
        fun open(driver: SqlDriver?): CacheStore {
            val db = openCacheDatabase(driver) ?: return NoopCacheStore
            return SqlCacheStore(db.agentReinsQueries)
        }
    }
}
