package dev.sharpxia.agentreins.openclaw

import dev.sharpxia.agentreins.model.ChatEntry
import dev.sharpxia.agentreins.model.ConversationState
import dev.sharpxia.agentreins.model.Message
import dev.sharpxia.agentreins.model.ToolCallEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * agent 事件流解析（纯函数，单测以真实网关录制报文为 fixture，见 protocol-notes §6）。
 *
 * 协议事实（2026.9.9 实测）：
 * - item 流是工具卡片主数据源，kind=tool（phase start→update→end，startedAt/endedAt 毫秒）；
 *   另有 kind=preamble 的 commentary 条目（progressText）
 * - command_output 流按 toolCallId 关联工具，output 是**累计快照**（delta/end 都全量）
 * - assistant 流是助理文本（text 全量快照 + delta），thinking 流同为快照式重放——
 *   两者与 item 都按 itemId 幂等重放，解析必须合并去重，不能假设只收到一次
 * - 事件流不含工具参数；参数在 chat.history 的 assistant 消息 content
 *   `{type:"toolCall",id,name,arguments}` 里，结果在 toolResult 消息里——由
 *   [mapHistory] 回填、[mergeHistory] 与事件态合并（事件态保留耗时，历史补参数结果）
 */
object AgentEventParser {

    /** 本端 send 的乐观回显条目前缀（runId 后缀）；历史回填按 runId 与之对账去重。 */
    const val LOCAL_ECHO_PREFIX = "local-"

    /** agent 事件 payload 解码后的强类型流事件。 */
    sealed interface AgentStreamEvent {
        data class ToolItem(
            val itemId: String,
            val toolCallId: String,
            val toolName: String,
            val title: String?,
            val phase: Phase,
            val startedAtMs: Long?,
            val endedAtMs: Long?,
            val status: String?,
        ) : AgentStreamEvent

        /** kind=preamble 的 commentary 条目（agent 的过程说明文本）。 */
        data class CommentaryItem(val itemId: String, val text: String, val phase: Phase) : AgentStreamEvent

        data class CommandOutput(
            val toolCallId: String,
            val output: String?,
            val phase: Phase,
            val exitCode: Int?,
            val durationMs: Long?,
            val status: String?,
        ) : AgentStreamEvent

        data class AssistantText(val itemId: String, val text: String?, val delta: String?) : AgentStreamEvent

        data class Thinking(val text: String) : AgentStreamEvent

        data class Lifecycle(
            val runId: String?,
            val phase: String,
            val startedAtMs: Long?,
            val endedAtMs: Long?,
            val terminalText: String?,
        ) : AgentStreamEvent
    }

    enum class Phase { START, UPDATE, END }

    // ---- decode：payload JsonObject → 强类型事件（未知流返回 null，调用方丢弃） ----

    fun decode(payload: JsonObject): AgentStreamEvent? {
        val data = payload["data"] as? JsonObject ?: return null
        return when (payload.str("stream")) {
            "item" -> decodeItem(data)
            "command_output" -> AgentStreamEvent.CommandOutput(
                toolCallId = data.str("toolCallId") ?: return null,
                output = data.str("output"),
                phase = phaseOf(data.str("phase")),
                exitCode = data.int("exitCode"),
                durationMs = data.long("durationMs"),
                status = data.str("status"),
            )
            "assistant" -> AgentStreamEvent.AssistantText(
                itemId = data.str("itemId") ?: "assistant-${payload.long("ts") ?: 0L}",
                text = data.str("text"),
                delta = data.str("delta"),
            )
            "thinking" -> AgentStreamEvent.Thinking(text = data.str("text") ?: return null)
            "lifecycle" -> AgentStreamEvent.Lifecycle(
                runId = payload.str("runId"),
                phase = data.str("phase") ?: return null,
                startedAtMs = data.long("startedAt"),
                endedAtMs = data.long("endedAt"),
                terminalText = (data["terminalReply"] as? JsonObject)?.str("text"),
            )
            else -> null // run_status / usage / 未知流：M2 不进对话状态
        }
    }

    private fun decodeItem(data: JsonObject): AgentStreamEvent? {
        val itemId = data.str("itemId") ?: return null
        val phase = phaseOf(data.str("phase"))
        val toolCallId = data.str("toolCallId")
        return if (toolCallId != null) {
            AgentStreamEvent.ToolItem(
                itemId = itemId,
                toolCallId = toolCallId,
                toolName = data.str("name").orEmpty(),
                title = data.str("title"),
                phase = phase,
                startedAtMs = data.long("startedAt"),
                endedAtMs = data.long("endedAt"),
                status = data.str("status"),
            )
        } else {
            AgentStreamEvent.CommentaryItem(
                itemId = itemId,
                text = data.str("progressText") ?: data.str("title").orEmpty(),
                phase = phase,
            )
        }
    }

    private fun phaseOf(raw: String?): Phase = when (raw) {
        "start" -> Phase.START
        "end" -> Phase.END
        else -> Phase.UPDATE
    }

    // ---- apply：状态归并（幂等——同一事件重复应用结果不变） ----

    fun apply(state: ConversationState, event: AgentStreamEvent): ConversationState = when (event) {
        is AgentStreamEvent.ToolItem -> applyToolItem(state, event)
        is AgentStreamEvent.CommentaryItem -> state.copy(
            entries = upsert(state.entries, Message(
                id = event.itemId,
                sessionId = state.sessionKey,
                role = Message.Role.SYSTEM,
                content = event.text,
            )),
        )
        is AgentStreamEvent.CommandOutput -> applyCommandOutput(state, event)
        is AgentStreamEvent.AssistantText -> applyAssistantText(state, event)
        is AgentStreamEvent.Thinking -> state.copy(thinkingText = event.text)
        is AgentStreamEvent.Lifecycle -> when (event.phase) {
            "start" -> state.copy(activeRunId = event.runId ?: state.activeRunId)
            "end" -> state.copy(
                activeRunId = null,
                entries = state.entries.map { e ->
                    (e as? Message)?.takeIf { it.streaming }?.copy(streaming = false) ?: e
                },
            )
            else -> state
        }
    }

    private fun applyToolItem(state: ConversationState, e: AgentStreamEvent.ToolItem): ConversationState {
        val existing = state.entries.filterIsInstance<ToolCallEvent>().firstOrNull { it.id == e.toolCallId }
        val duration = e.endedAtMs?.let { end -> (e.startedAtMs ?: existing?.startedAtMs)?.let { start -> end - start } }
        val entry = (existing ?: ToolCallEvent(
            id = e.toolCallId,
            sessionId = state.sessionKey,
            toolName = e.toolName,
        )).copy(
            toolName = e.toolName.ifBlank { existing?.toolName.orEmpty() },
            title = e.title ?: existing?.title,
            startedAtMs = e.startedAtMs ?: existing?.startedAtMs,
            durationMs = duration ?: existing?.durationMs,
            state = when {
                e.phase != Phase.END -> existing?.state ?: ToolCallEvent.State.RUNNING
                else -> terminalState(e.status)
            },
        )
        return state.copy(entries = upsert(state.entries, entry))
    }

    private fun applyCommandOutput(state: ConversationState, e: AgentStreamEvent.CommandOutput): ConversationState {
        val existing = state.entries.filterIsInstance<ToolCallEvent>().firstOrNull { it.id == e.toolCallId }
        val entry = (existing ?: ToolCallEvent(
            id = e.toolCallId,
            sessionId = state.sessionKey,
            toolName = "",
        )).copy(
            resultJson = e.output ?: existing?.resultJson, // 快照语义：全量覆盖
            exitCode = e.exitCode ?: existing?.exitCode,
            durationMs = existing?.durationMs ?: e.durationMs, // item 流的 endedAt-startedAt 优先
            state = if (e.phase == Phase.END) terminalState(e.status) else existing?.state ?: ToolCallEvent.State.RUNNING,
        )
        return state.copy(entries = upsert(state.entries, entry))
    }

    private fun applyAssistantText(state: ConversationState, e: AgentStreamEvent.AssistantText): ConversationState {
        val existing = state.entries.filterIsInstance<Message>().firstOrNull { it.id == e.itemId }
        val content = when {
            e.text != null -> e.text
            e.delta != null -> (existing?.content ?: "") + e.delta
            else -> existing?.content.orEmpty()
        }
        return state.copy(entries = upsert(state.entries, Message(
            id = e.itemId,
            sessionId = state.sessionKey,
            role = Message.Role.ASSISTANT,
            content = content,
            timestampMs = existing?.timestampMs,
            streaming = true,
        )))
    }

    /** 终态映射：completed/未知 → SUCCEEDED，failed/error/aborted → FAILED。 */
    private fun terminalState(status: String?): ToolCallEvent.State = when (status) {
        "failed", "error", "aborted" -> ToolCallEvent.State.FAILED
        else -> ToolCallEvent.State.SUCCEEDED
    }

    private fun upsert(entries: List<ChatEntry>, entry: ChatEntry): List<ChatEntry> {
        val idx = entries.indexOfFirst { it.id == entry.id }
        return if (idx < 0) entries + entry
        else entries.toMutableList().also { it[idx] = entry }
    }

    // ---- chat.history 回填与合并 ----

    /** chat.history payload → ConversationState（条目按 transcript 顺序）。 */
    fun mapHistory(sessionKey: String, history: JsonObject): ConversationState {
        val messages = history["messages"] as? JsonArray ?: return ConversationState(sessionKey)
        val hasMore = history["hasMore"]?.jsonPrimitive?.booleanOrNull ?: false
        val entries = mutableListOf<ChatEntry>()
        messages.forEachIndexed { idx, el ->
            val m = el as? JsonObject ?: return@forEachIndexed
            // 消息 id：顶层 id（探针/测试形）或 __openclaw.id（真实网关报文）。都缺时合成：
            // 时间戳 + 内容哈希——跨页必须稳定（mergeHistoryOlder 按 id 对账去重，纯页内索引
            // 会在页边界撞/漏），页内必须唯一（纯时间戳在同秒多条消息上撞号，曾致
            // LazyColumn key 重复崩溃，真机实锤）
            val messageId = m.str("id")
                ?: (m["__openclaw"] as? JsonObject)?.str("id")
                ?: run {
                    val ts = m.long("timestamp")
                    val hash = (m.str("role").orEmpty() + "\u0000" + contentText(m["content"])).hashCode()
                    if (ts != null) "msg-$ts-$hash" else "msg-i$idx-$hash"
                }
            when (m.str("role")) {
                "user" -> {
                    val text = contentText(m["content"])
                    if (text.isNotBlank()) {
                        entries += Message(
                            // 本端消息 id 带 runId（idempotencyKey "<runId>:user"），供 mergeHistory 与回显对账
                            id = m.str("idempotencyKey")?.substringBefore(':')?.let { "user-$it" }
                                ?: messageId,
                            sessionId = sessionKey,
                            role = Message.Role.USER,
                            content = text,
                            timestampMs = m.long("timestamp"),
                        )
                    }
                }
                "assistant" -> {
                    val parts = m["content"] as? JsonArray
                    val text = parts
                        ?.mapNotNull { (it as? JsonObject)?.takeIf { p -> p.str("type") == "text" }?.str("text") }
                        ?.joinToString("\n")
                        .orEmpty()
                    if (text.isNotBlank()) {
                        entries += Message(
                            id = messageId,
                            sessionId = sessionKey,
                            role = Message.Role.ASSISTANT,
                            content = text,
                            timestampMs = m.long("timestamp"),
                        )
                    }
                    parts?.forEach { part ->
                        val p = part as? JsonObject ?: return@forEach
                        if (p.str("type") != "toolCall") return@forEach
                        val callId = p.str("id") ?: return@forEach
                        entries += ToolCallEvent(
                            id = callId,
                            sessionId = sessionKey,
                            toolName = p.str("name").orEmpty(),
                            argumentsJson = p["arguments"]?.toString(),
                            state = ToolCallEvent.State.RUNNING,
                        )
                    }
                }
                "toolResult" -> {
                    val callId = m.str("toolCallId") ?: return@forEachIndexed
                    val failed = m["isError"]?.jsonPrimitive?.booleanOrNull == true
                    val at = entries.indexOfFirst { it is ToolCallEvent && it.id == callId }
                    val prior = entries.getOrNull(at) as? ToolCallEvent
                    val entry = ToolCallEvent(
                        id = callId,
                        sessionId = sessionKey,
                        toolName = m.str("toolName") ?: prior?.toolName.orEmpty(),
                        argumentsJson = prior?.argumentsJson,
                        resultJson = contentText(m["content"]).ifBlank { null },
                        state = if (failed) ToolCallEvent.State.FAILED else ToolCallEvent.State.SUCCEEDED,
                    )
                    if (at >= 0) entries[at] = entry else entries += entry
                }
            }
        }
        return ConversationState(sessionKey, entries, hasMoreHistory = hasMore)
    }

    /**
     * 事件态 ⊕ 历史态：历史条目为准（transcript 顺序），live 事件态补齐耗时/退出码等
     * 历史里没有的字段；本端回显与历史的 user 消息按 runId 对账去重；live 助理文本与
     * 历史同文去重（live itemId 与历史消息 id 不同源）。历史未覆盖的 live 条目（流式中、
     * commentary）原样保留。
     */
    fun mergeHistory(live: ConversationState, history: ConversationState): ConversationState {
        val liveTools = live.entries.filterIsInstance<ToolCallEvent>().associateBy { it.id }
        val usedToolIds = mutableSetOf<String>()
        val usedEchoRunIds = mutableSetOf<String>()
        val matchedAssistantTexts = mutableSetOf<String>()

        val merged = history.entries.map { entry ->
            when (entry) {
                is ToolCallEvent -> {
                    val l = liveTools[entry.id]
                    if (l != null) usedToolIds += entry.id
                    if (l == null) entry
                    else l.copy(
                        toolName = l.toolName.ifBlank { entry.toolName },
                        title = l.title ?: entry.title,
                        argumentsJson = entry.argumentsJson ?: l.argumentsJson,
                        resultJson = entry.resultJson ?: l.resultJson,
                        exitCode = l.exitCode ?: entry.exitCode,
                        startedAtMs = l.startedAtMs ?: entry.startedAtMs,
                        durationMs = l.durationMs ?: entry.durationMs,
                        state = if (l.state == ToolCallEvent.State.RUNNING) entry.state else l.state,
                    )
                }
                is Message -> when {
                    entry.role == Message.Role.USER && entry.id.startsWith(USER_ECHO_PREFIX) &&
                        entry.id.removePrefix(USER_ECHO_PREFIX) in echoRunIds(live) -> {
                        entry.id.removePrefix(USER_ECHO_PREFIX).let { usedEchoRunIds += it }
                        entry
                    }
                    entry.role == Message.Role.ASSISTANT && entry.content.isNotBlank() &&
                        live.entries.any { it is Message && it.role == Message.Role.ASSISTANT && it.content == entry.content } -> {
                        matchedAssistantTexts += entry.content
                        entry
                    }
                    else -> entry
                }
            }
        }

        val leftovers = live.entries.filter { entry ->
            when (entry) {
                is ToolCallEvent -> entry.id !in usedToolIds
                is Message -> when (entry.role) {
                    Message.Role.USER ->
                        entry.id.startsWith(LOCAL_ECHO_PREFIX) &&
                            entry.id.removePrefix(LOCAL_ECHO_PREFIX) !in usedEchoRunIds
                    Message.Role.ASSISTANT ->
                        entry.content.isNotBlank() && entry.content !in matchedAssistantTexts
                    Message.Role.SYSTEM -> true
                }
            }
        }
        return live.copy(
            entries = merged + leftovers,
            thinkingText = live.thinkingText,
            activeRunId = live.activeRunId,
            hasMoreHistory = history.hasMoreHistory,
        )
    }

    /**
     * 事件态 ⊕ 更早的一页历史（向上翻页懒加载）：新页条目按 id 去重后**前插**，
     * hasMore 以新页为准。
     */
    fun mergeHistoryOlder(live: ConversationState, older: ConversationState): ConversationState {
        val existingIds = live.entries.mapTo(mutableSetOf()) { it.id }
        val fresh = older.entries.filter { it.id !in existingIds }
        return live.copy(
            entries = fresh + live.entries,
            hasMoreHistory = older.hasMoreHistory,
        )
    }

    private const val USER_ECHO_PREFIX = "user-"

    private fun echoRunIds(live: ConversationState): Set<String> = live.entries
        .filterIsInstance<Message>()
        .filter { it.id.startsWith(LOCAL_ECHO_PREFIX) }
        .mapTo(mutableSetOf()) { it.id.removePrefix(LOCAL_ECHO_PREFIX) }

    // ---- 小工具 ----

    /** content 为字符串或 [{type,text}] 数组，取全部 text 段拼接。 */
    private fun contentText(content: Any?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.takeIf { p -> p.str("type") == "text" }?.str("text") }
            .joinToString("\n")
        else -> ""
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
