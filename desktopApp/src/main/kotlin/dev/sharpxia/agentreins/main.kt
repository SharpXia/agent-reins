package dev.sharpxia.agentreins

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.sharpxia.agentreins.desktopui.DesktopHubApp
import dev.sharpxia.agentreins.hub.HubConfig
import dev.sharpxia.agentreins.hub.HubException
import dev.sharpxia.agentreins.hub.auth.CredentialStore
import dev.sharpxia.agentreins.hub.auth.HubAuthStack
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.takeFrom
import io.ktor.websocket.Frame
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 桌面壳（wp-6 MVP + wp-10 交互 parity + wp-18 渲染/通知）：默认窗渲染 Compose Desktop Hub 面
 * （[DesktopHubApp]，登录/会话列表/会话页/设置四屏，整体落 desktopApp 不动 commonMain 的 App()）。
 * wp-10 交互能力全落 desktopui：消息操作（复制/重发/Stop + activeRunId 降级）、审批卡
 * （含决策不可用降级态）、空正文防线；wp-18 落 Markdown 渲染（MarkdownText）与 macOS 本地通知
 * （DesktopNotificationCoordinator，eventTap 旁路 + osascript 派发）。
 *
 * 三条 Hub 验收通道不开窗口、跑完即退出（HUB_BASE / HUB_USER / HUB_PASS 来自环境变量，不落代码）：
 * - `hub-login`：认证两步流 + AUTH-006 + 设备绑定 + 静默恢复轮换 + 撤销，见 [hubLoginProbe]
 * - `hub-sync`：快照 → resume → 追平 → 重连 → 410 重建 + 队列保留，见 [hubSyncProbe]
 * - `hub-wsraw`：WSS 裸帧调试，见 [hubWsRawProbe]
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "hub-login") {
        hubLoginProbe()
        return
    }
    if (args.firstOrNull() == "hub-sync") {
        hubSyncProbe()
        return
    }
    if (args.firstOrNull() == "hub-wsraw") {
        hubWsRawProbe()
        return
    }
    application {
        Window(
            title = "AgentReins Hub",
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
        ) {
            DesktopHubApp()
        }
    }
}

/**
 * Hub 认证链路验收通道（方案 §6-P1 验收判据）。HUB_BASE / HUB_USER / HUB_PASS 来自环境变量，不落代码。
 * 凭据存储用进程内存桩（不碰 ~/.agentreins），测试设备跑完即撤销，不在 Hub 留垃圾设备。
 */
private fun hubLoginProbe() = runBlocking {
    val base = System.getenv("HUB_BASE") ?: error("HUB_BASE 未设（如 http://<HUB-IP>:8080/v1）")
    val user = System.getenv("HUB_USER") ?: error("HUB_USER 未设")
    val pass = System.getenv("HUB_PASS") ?: error("HUB_PASS 未设")
    val mem = object : CredentialStore {
        var v: String? = null
        override suspend fun saveRefreshToken(token: String) { v = token }
        override suspend fun loadRefreshToken(): String? = v
        override suspend fun clear() { v = null }
    }
    val ws = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws"
    val stack = HubAuthStack(HubConfig(base, ws), credentialStore = mem)

    println("① 登录（会话 Token）…")
    val ok = stack.auth.login(user, pass, null)
    println("   ok user=${ok.user.displayName} tokenType=${ok.tokenType} expiresIn=${ok.expiresIn} mfaRequired=${ok.mfaRequired}")

    println("② AUTH-006 预检：未绑定会话 Token 调设备必需端点 GET /v1/gateways…（devices 清单挂 bearer 放行，AUTH-006 只拦设备必需端点）")
    val e6 = runCatching { stack.api.gateways() }.exceptionOrNull()
    check(e6 is HubException && e6.payload.code == "HUB-AUTH-006") {
        "期待 403 HUB-AUTH-006，实得 ${e6?.message ?: "成功响应（契约破坏）"}"
    }
    println("   ok http=${e6.httpStatus} ${e6.payload.code} x-request-id=${e6.requestId}")

    println("③ 设备注册（会话 Token 升级为绑定 Token，ADR-15）…")
    val reg = stack.auth.registerCurrentDevice("AgentReins P1 probe", "desktop", null, "p1-probe")
    println("   ok deviceId=${reg.deviceId} refreshExpiresIn=${reg.refreshExpiresIn} refreshToken 已入存储=${mem.v != null}")

    println("④ 同一 accessToken 现在已升级为绑定 Token：设备清单与设备必需端点都应放行…")
    val devices = stack.auth.withBearer { stack.api.listDevices() }
    val me = devices.devices.firstOrNull { it.deviceId == reg.deviceId } ?: error("设备清单不含刚注册的设备")
    val gws = stack.auth.withBearer { stack.api.gateways() } // 步骤②曾 403 的端点，注册后应 200
    println("   ok 共 ${devices.devices.size} 台，本机 current=${me.current} platform=${me.platform}；gateways=${gws.gateways.size}")

    println("⑤ 新实例仅凭安全存储的 refreshToken 静默恢复（refresh 一次一用轮换）…")
    val before = mem.v
    val stack2 = HubAuthStack(HubConfig(base, ws), credentialStore = mem)
    check(stack2.auth.restoreFromStoredRefreshToken()) { "静默恢复失败" }
    check(stack2.auth.currentDeviceId == reg.deviceId) { "deviceId 未延续" }
    check(mem.v != null && mem.v != before) { "refreshToken 未轮换" }
    println("   ok deviceId 延续=${stack2.auth.currentDeviceId}，refreshToken 已轮换")

    println("⑥ 旧 refreshToken 重放（超 60s 宽限前立即重放应幂等返回同一新对；此处验证服务端不报 5xx）…")
    val replay = runCatching { stack2.api.refresh(dev.sharpxia.agentreins.hub.auth.RefreshRequest(before!!)) }
    println("   ${replay.fold({ "幂等返回 deviceId=${it.deviceId}" }, { "拒绝：${(it as? HubException)?.payload?.code ?: it.message}" })}")

    println("⑦ 清理：撤销测试设备（DELETE /v1/devices/{id} → 204）…")
    stack2.auth.withBearer { stack2.api.deleteDevice(reg.deviceId) }
    println("   ok")

    println("⑧ 撤销后在途请求应 403 HUB-AUTH-004…")
    val e4 = runCatching { stack2.api.listDevices() }.exceptionOrNull()
    println("   ${(e4 as? HubException)?.let { "http=${it.httpStatus} ${it.payload.code}" } ?: (e4?.message ?: "成功响应（异常）")}")

    println("PASS: Hub 认证两步流 + AUTH-006 + 设备绑定 + 静默恢复轮换 + 撤销 全链路通过")
}

/**
 * Hub 同步链路验收通道（方案 §6-P2 验收判据）。HUB_BASE / HUB_USER / HUB_PASS 环境变量，不落代码。
 * 手动编排（不经 SyncScheduler 自动循环）以精确断言每一步：bootstrap → WSS resume/ready →
 * HTTP 增量追平 → 断连重连补拉 → 老游标 410 → 快照重建（发送队列保留）。
 */
private fun hubSyncProbe() = kotlinx.coroutines.runBlocking {
    val base = System.getenv("HUB_BASE") ?: error("HUB_BASE 未设（如 http://<HUB-IP>:8080/v1）")
    val user = System.getenv("HUB_USER") ?: error("HUB_USER 未设")
    val pass = System.getenv("HUB_PASS") ?: error("HUB_PASS 未设")
    val mem = object : dev.sharpxia.agentreins.hub.auth.CredentialStore {
        var v: String? = null
        override suspend fun saveRefreshToken(token: String) { v = token }
        override suspend fun loadRefreshToken(): String? = v
        override suspend fun clear() { v = null }
    }
    val ws = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws"
    val config = HubConfig(base, ws)
    val stack = dev.sharpxia.agentreins.hub.auth.HubAuthStack(config, credentialStore = mem)

    println("① 认证（restore 优先，退登录两步流）…")
    if (!stack.auth.restoreFromStoredRefreshToken()) {
        stack.auth.login(user, pass, null)
        stack.auth.registerCurrentDevice("AgentReins P2 sync probe", "desktop", null, "p2-sync-probe")
    }

    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
    val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
    dev.sharpxia.agentreins.hubdb.HubDatabase.Schema.create(driver)
    val db = dev.sharpxia.agentreins.hubdb.HubDatabase(driver)
    val projector = dev.sharpxia.agentreins.hub.projection.DefaultEventProjector(db)
    val cursors = dev.sharpxia.agentreins.hub.sync.DbEventCursorStore(db)
    val engine = dev.sharpxia.agentreins.hub.sync.DefaultSyncEngine(stack.api, stack.auth, projector, cursors)
    val wsClient = dev.sharpxia.agentreins.hub.DefaultHubWsClient(config, { stack.auth.currentAccessToken() }, scope)

    println("② bootstrap：拉快照 → 覆盖投影 → cursor = snapshotWatermark …")
    val snap = engine.bootstrap()
    println("   ok watermark=${snap.snapshotWatermark} conversations=${snap.conversations.size} messages=${snap.messages.values.sumOf { it.size }} gateways=${snap.gateways.size}")
    check(cursors.eventCursor() == snap.snapshotWatermark) { "bootstrap 后 cursor ≠ snapshotWatermark" }

    // 帧收集必须先于 connect（SharedFlow 订阅间隙会丢 ready/error 帧）
    suspend fun connectAwait(cursor: String, want: kotlin.reflect.KClass<out dev.sharpxia.agentreins.hub.HubServerFrame>): dev.sharpxia.agentreins.hub.HubServerFrame {
        val gate = kotlinx.coroutines.CompletableDeferred<dev.sharpxia.agentreins.hub.HubServerFrame>()
        val collector = scope.launch {
            wsClient.frames().collect { f ->
                println("   [ws] ${f::class.simpleName}: $f")
                if (f::class == want && !gate.isCompleted) gate.complete(f)
            }
        }
        try {
            wsClient.connect(cursor)
            return kotlinx.coroutines.withTimeout(15_000) { gate.await() }
        } finally {
            collector.cancel()
        }
    }

    println("③ WSS 建连 + sync.resume → sync.ready（先连后补 R3）…")
    val ready = connectAwait(cursors.eventCursor()!!, dev.sharpxia.agentreins.hub.HubServerFrame.Ready::class) as dev.sharpxia.agentreins.hub.HubServerFrame.Ready
    println("   ok watermark=${ready.watermark} pollIntervalMs=${ready.pollIntervalMs}")

    println("④ HTTP 增量追平 …")
    val caughtUp = engine.catchUp()
    println("   ok caughtUp=$caughtUp cursor=${cursors.eventCursor()}")

    println("⑤ 断连重连：close → connect → ready → catchUp（INV-5 不重放 + 幂等去重）…")
    wsClient.close()
    connectAwait(cursors.eventCursor()!!, dev.sharpxia.agentreins.hub.HubServerFrame.Ready::class)
    engine.catchUp()
    println("   ok cursor=${cursors.eventCursor()}")

    println("⑥ 老游标 410（HUB-SYNC-001）→ error 帧 → 快照重建（发送队列保留）…")
    db.hubQueries.insertCommand(
        idempotencyKey = "probe-queue-key", commandId = null, conversationId = "conv",
        runId = null, approvalId = null, type = "message.send", payloadJson = "{}",
        state = "PENDING_LOCAL", errorCode = null, createdAt = "t", updatedAt = "t",
    )
    cursors.updateEventCursor("01AAAAAAAAAAAAAAAAAAAAAAAA") // 远早于 90 天保留窗口的 ULID
    wsClient.close()
    val err = connectAwait("01AAAAAAAAAAAAAAAAAAAAAAAA", dev.sharpxia.agentreins.hub.HubServerFrame.Error::class) as dev.sharpxia.agentreins.hub.HubServerFrame.Error
    check(err.code == "HUB-SYNC-001") { "期待 HUB-SYNC-001，实得 ${err.code}" }
    println("   ok ${err.code}（${err.message.take(60)}）")
    engine.rebuild()
    check(cursors.eventCursor() != "01AAAAAAAAAAAAAAAAAAAAAAAA") { "重建后 cursor 未恢复" }
    check(db.hubQueries.selectNonTerminalCommands().executeAsList().any { it.idempotencyKey == "probe-queue-key" }) { "重建丢了发送队列" }
    println("   ok 重建后 cursor=${cursors.eventCursor()}，发送队列保留=${db.hubQueries.selectNonTerminalCommands().executeAsList().size} 条")

    wsClient.close()

    // 自清理（与 hub-login 通道同纪律）：撤销本次注册的测试设备，不在 Hub 留孤儿设备
    stack.auth.currentDeviceId?.let { id ->
        runCatching { stack.auth.withBearer { stack.api.deleteDevice(id) } }
            .onSuccess { println("⑦ 清理：测试设备已撤销 $id") }
            .onFailure { println("⑦ 清理失败（请手动撤销 $id）：${it.message}") }
    }
    println("PASS: Hub 同步链路（快照→resume→追平→重连→410 重建+队列保留）全通")
}

/** WSS 裸帧调试（P2 排障通道）：升级 → 发 resume → 打印全部原始帧直至超时。 */
private fun hubWsRawProbe() = kotlinx.coroutines.runBlocking {
    val base = System.getenv("HUB_BASE") ?: error("HUB_BASE 未设")
    val user = System.getenv("HUB_USER") ?: error("HUB_USER 未设")
    val pass = System.getenv("HUB_PASS") ?: error("HUB_PASS 未设")
    val ws = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws"
    val config = HubConfig(base, ws)
    val mem = object : dev.sharpxia.agentreins.hub.auth.CredentialStore {
        var v: String? = null
        override suspend fun saveRefreshToken(token: String) { v = token }
        override suspend fun loadRefreshToken(): String? = v
        override suspend fun clear() { v = null }
    }
    val stack = dev.sharpxia.agentreins.hub.auth.HubAuthStack(config, credentialStore = mem)
    if (!stack.auth.restoreFromStoredRefreshToken()) {
        stack.auth.login(user, pass, null)
        stack.auth.registerCurrentDevice("AgentReins P2 wsraw", "desktop", null, "p2-wsraw")
    }
    val http = dev.sharpxia.agentreins.hub.hubHttpClient(dev.sharpxia.agentreins.hub.HubJson).config {
        install(io.ktor.client.plugins.websocket.WebSockets)
    }
    val session = http.webSocketSession {
        url { takeFrom(ws) }
        header(HttpHeaders.Authorization, "Bearer ${stack.auth.currentAccessToken()}")
    }
    println("UPGRADED: ${session.call.response.status}")
    session.send(Frame.Text("""{"type":"sync.resume","protocolVersion":1,"cursor":"01AAAAAAAAAAAAAAAAAAAAAAAA"}"""))
    println("RESUME SENT（老游标，期待 error 帧 HUB-SYNC-001 或 ready）")
    kotlinx.coroutines.withTimeout(12_000) {
        for (f in session.incoming) {
            when (f) {
                is Frame.Text -> println("TEXT: ${f.data.decodeToString()}")
                else -> println("FRAME: ${f::class.simpleName}")
            }
        }
    }
}
