package dev.sharpxia.agentreins

import dev.sharpxia.agentreins.model.GatewayStatus
import dev.sharpxia.agentreins.model.Message
import dev.sharpxia.agentreins.model.ToolCallEvent
import dev.sharpxia.agentreins.openclaw.OpenClawClient
import dev.sharpxia.agentreins.openclaw.parsePairingSetupCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 网关冒烟（仅本机手动运行：`./gradlew :desktopApp:smokeGateway`，凭据来自环境变量）。
 * 默认走共享 token + 设备签名；设 OPENCLAW_SETUP_CODE（openclaw qr 产物）则走扫码配对流程。
 * M2 流程：connect → chat.history 回填（断言工具卡片带参数/结果）→ 发消息触发真实 exec
 * 工具调用 → 断言事件流归并出第二张卡（SUCCEEDED + 耗时 + 输出）。输出不包含任何凭据。
 */
fun main() {
    val setupCode = System.getenv("OPENCLAW_SETUP_CODE")
    if (setupCode != null) {
        runBlocking { smoke(setup = setupCode) }
        return
    }
    val url = System.getenv("OPENCLAW_GATEWAY_URL") ?: error("OPENCLAW_GATEWAY_URL not set")
    val key = System.getenv("OPENCLAW_API_KEY") ?: error("OPENCLAW_API_KEY not set")
    runBlocking { smoke(config = GatewayConfig(url, key)) }
}

private suspend fun CoroutineScope.smoke(config: GatewayConfig? = null, setup: String? = null) {
    val label = if (setup != null) "qr-pairing" else "token"
    println("[smoke:$label] connecting …")
    val client = OpenClawClient(this)
    val statusLog = launch {
        client.status.collect { println("[smoke] status -> ${it::class.simpleName}") }
    }
    try {
        when {
            setup != null -> {
                val parsed = parsePairingSetupCode(setup)
                println("[smoke] setup code parsed (expiresAtMs=${parsed.expiresAtMs ?: "n/a"})")
                client.connect(GatewayConfig(baseUrl = parsed.url, bootstrapToken = parsed.bootstrapToken))
            }
            else -> client.connect(config!!)
        }
        val sessions = client.sessions.value
        println("[smoke] sessions=${sessions.size} paired=${client.deviceToken != null}")
        val main = sessions.firstOrNull { it.id == "agent:main:main" }
            ?: sessions.firstOrNull()
            ?: error("no session found")

        // 1) 历史回填：transcript 里的工具调用应还原出参数与结果
        client.loadHistory(main.id)
        val hist = client.conversation(main.id).value
        val complete = hist.toolCalls.filter { it.argumentsJson != null && it.resultJson != null }
        println("[smoke] history entries=${hist.entries.size} toolCards=${hist.toolCalls.size} complete=${complete.size}")
        if (complete.isEmpty()) {
            println("[smoke] FAIL: 历史回填没有参数+结果齐备的工具卡片")
            return
        }
        val histCard = complete.first()

        // 2) 实时工具卡片：触发一次 exec 回合，事件流归并出 SUCCEEDED + 耗时 + 输出
        val flow = client.conversation(main.id)
        // 只认本轮新出现的卡：上一轮的 exec 卡已进 transcript，凭结果文本会误选历史卡（历史无计时）
        val baselineToolIds = flow.value.toolCalls.mapTo(mutableSetOf()) { it.id }
        var liveCard: ToolCallEvent? = null
        var reply: Message? = null
        val watch = launch {
            flow.collect { state ->
                // 回合内可能有失败的内部包装工具（tool_call），以 exec 输出为准锁定目标卡
                state.toolCalls.firstOrNull {
                    it.id !in baselineToolIds && it.resultJson?.contains("agentreins-m2-smoke-ok") == true
                }?.let { liveCard = it }
                state.messages.lastOrNull { it.role == Message.Role.ASSISTANT }?.let { reply = it }
            }
        }
        println("[smoke] sending exec turn to ${main.id} …")
        client.send(main.id, "hello from AgentReins M2 smoke (自动冒烟，请用 exec 工具执行 `echo agentreins-m2-smoke-ok`，把输出告诉我后结束)")

        var waited = 0
        while (waited < 120_000) {
            val card = liveCard
            if (card != null && card.state == ToolCallEvent.State.SUCCEEDED && card.durationMs != null && reply != null) break
            delay(1_000); waited += 1_000
        }
        watch.cancel()
        val card = liveCard
        if (card == null || card.durationMs == null || card.resultJson?.contains("agentreins-m2-smoke-ok") != true) {
            println("[smoke] FAIL: 实时工具卡片不完整 state=${card?.state} duration=${card?.durationMs} result=${card?.resultJson?.take(60)}")
            return
        }
        println("[smoke] live card: ${card.toolName} duration=${card.durationMs}ms exit=${card.exitCode} output=${card.resultJson!!.take(60)}")
        println("[smoke] reply: ${reply?.content?.take(80)}")
        println("[smoke] PASS")
    } finally {
        statusLog.cancel()
        client.disconnect()
    }
}
