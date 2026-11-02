package dev.sharpxia.agentreins

import dev.sharpxia.agentreins.hub.DefaultHubClient
import dev.sharpxia.agentreins.hub.HubAuthState
import dev.sharpxia.agentreins.hub.HubRuntime
import dev.sharpxia.agentreins.hub.UiConversation
import dev.sharpxia.agentreins.hub.UiMessage
import dev.sharpxia.agentreins.hub.auth.DeviceInfo
import dev.sharpxia.agentreins.hub.db.createHubDriver
import dev.sharpxia.agentreins.hub.db.openHubDatabase
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * SwiftUI 壳 ↔ shared/hub 桥（Hub 化 P4 定稿：网关直连面已整体退役，本桥只剩 Hub 面）。
 *
 * 职责：账号态订阅、登录/恢复/登出、投影库 Flow → Swift 回调、命令透传、设备管理。
 * 所有回调在主线程派发（scope = Dispatchers.Main）。
 *
 * DTO 纪律（跨 Kotlin/Swift 边界）：[UiConversation]/[UiMessage]/[DeviceInfo] 是 shared 的
 * 只读模型，字段均为 String/Long/Int/Boolean/可空 String，Swift 直接消费，不另建中间 DTO。
 */
class AgentBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val hubRuntime = HubRuntime(scope)
    private var hubClient: DefaultHubClient? = null
    private var hubConvJob: Job? = null
    private var hubMsgJob: Job? = null

    /** 投影库句柄进程内唯一（登出清表后复用同一连接，重登从空库 bootstrap）。 */
    private val hubDb: HubDatabase? by lazy { openHubDatabase(createHubDriver()) }

    // ---------- 账号态 ----------

    /**
     * Hub 账号态订阅（主线程回调）：kind = idle/working/needsLogin/loggedIn；
     * message = NeedsLogin.reason；user = LoggedIn.userDisplay；deviceId = LoggedIn.deviceId。
     * 进入 loggedIn 时自动组装并启动 Hub 同步栈（幂等）。
     */
    fun hubObserve(onState: (kind: String, message: String?, user: String?, deviceId: String?) -> Unit) {
        scope.launch {
            hubRuntime.state.collect { st ->
                when (st) {
                    HubAuthState.Idle -> onState("idle", null, null, null)
                    HubAuthState.Working -> onState("working", null, null, null)
                    is HubAuthState.NeedsLogin -> onState("needsLogin", st.reason, null, null)
                    is HubAuthState.LoggedIn -> {
                        ensureHubStarted()
                        onState("loggedIn", null, st.userDisplay, st.deviceId)
                    }
                }
            }
        }
    }

    private fun ensureHubStarted() {
        if (hubClient == null) {
            val client = DefaultHubClient(scope, hubRuntime, hubDb)
            hubClient = client
            client.start()
        }
    }

    /** 启动恢复：Keychain 有 refreshToken 则静默刷新进 loggedIn，否则 needsLogin。 */
    fun hubRestore(baseUrl: String) = hubRuntime.restore(baseUrl)

    fun hubLogin(baseUrl: String, username: String, password: String, deviceName: String) =
        hubRuntime.login(baseUrl, username, password, deviceName)

    /**
     * 登出闭环（方案 §6-P4 验收⑤）= 撤销当前设备（服务端 Token 即时失效）+ 清 Keychain 凭据
     * + 停同步栈 + 清全部本地投影数据（七表）。重登后从空库 bootstrap。
     */
    fun hubLogout() {
        hubConvJob?.cancel(); hubConvJob = null
        hubMsgJob?.cancel(); hubMsgJob = null
        val client = hubClient
        hubClient = null
        client?.stop()
        client?.wipeLocalData()
        hubRuntime.logout()
    }

    // ---------- 投影库数据面（Flow → Swift 回调） ----------

    /** 会话列表订阅（投影库变更即回调；重复调用先取消旧订阅）。 */
    fun hubWatchConversations(onList: (List<UiConversation>) -> Unit) {
        hubConvJob?.cancel()
        hubConvJob = scope.launch {
            hubClient?.observeConversations()?.collect { onList(it) }
        }
    }

    /** 指定会话消息订阅（打开会话页调用；重复调用切订阅）。 */
    fun hubWatchMessages(gatewayId: String, sessionKey: String, onList: (List<UiMessage>) -> Unit) {
        hubMsgJob?.cancel()
        hubMsgJob = scope.launch {
            hubClient?.observeMessages(gatewayId, sessionKey)?.collect { onList(it) }
        }
    }

    /** 关闭会话页时取消消息订阅（避免后台继续推已离开会话的列表）。 */
    fun hubStopWatchingMessages() {
        hubMsgJob?.cancel(); hubMsgJob = null
    }

    // ---------- 命令（幂等队列） ----------

    /** 发送消息（幂等队列 + 乐观行；进度经 command.acked/message.created 事件翻转）。 */
    fun hubSend(conversationId: String, gatewayId: String, sessionKey: String, text: String) {
        scope.launch { runCatching { hubClient?.commands?.sendMessage(conversationId, gatewayId, sessionKey, text) } }
    }

    /** 会话渲染完整后上报已读（readUpTo = 已渲染最大 seq）。 */
    fun hubMarkRead(conversationId: String, gatewayId: String, sessionKey: String, readUpTo: Long) {
        scope.launch { runCatching { hubClient?.markRead(conversationId, gatewayId, sessionKey, readUpTo) } }
    }

    // ---------- 设备管理 ----------

    /** 设备清单（设备管理页）。 */
    fun hubDevices(onList: (List<DeviceInfo>) -> Unit) {
        scope.launch {
            runCatching {
                val stack = hubRuntime.authStack ?: return@launch
                val page = stack.auth.withBearer { stack.api.listDevices() }
                onList(page.devices)
            }
        }
    }

    /** 撤销设备（可撤任意自有设备含当前；撤当前即远端登出，本地随后清凭据重登录）。 */
    fun hubRevokeDevice(deviceId: String, onDone: () -> Unit) {
        scope.launch {
            runCatching {
                val stack = hubRuntime.authStack ?: return@launch
                stack.auth.withBearer { stack.api.deleteDevice(deviceId) }
            }
            onDone()
        }
    }
}
