import SwiftUI
import UIKit
import composeApp

/// Hub 账号态（P1）：订阅 AgentBridge.hubObserve；baseUrl 为非敏感配置存 UserDefaults，
/// 口令只经登录请求体、refreshToken 由 KMP 侧落 Keychain，Swift 层不持有任何凭据。
@Observable
final class HubStore {
    static let shared = HubStore()

    /// idle / working / needsLogin / loggedIn（与 AgentBridge.hubObserve 的 kind 一致）。
    private(set) var kind: String = "idle"
    /// NeedsLogin.reason（登录失败/凭据失效的人读原因）。
    private(set) var message: String?
    private(set) var userDisplay: String?
    private(set) var deviceId: String?
    /// 启动恢复在途（Keychain 有凭据时静默刷新），用于首屏显示进度而非登录表单。
    private(set) var isRestoring = false
    /// 最近一次登录的 AUTH-006 判定快照（进 loggedIn 时拉取；状态条取证用）。
    private(set) var auth006VerdictSnapshot: String?

    // ---------- P3：投影库数据面（KMP Flow → Swift 回调） ----------

    /// 会话列表（投影库 Flow 推送；label 空的渠道线程由 UI 兜底命名）。
    var conversations: [UiConversation] = []
    /// 当前打开会话的消息列表。
    var messages: [UiMessage] = []
    /// 当前打开的会话。
    var selected: UiConversation?
    /// Dev 探针用 Bool 驱动 push（KMP 类无 Hashable，NavigationPath.append 不可靠）。
    var chatPush = false

    private var devicesCache: [DeviceInfo]? = nil

    /// 订阅会话列表（loggedIn 后调用一次；重复调用由桥侧换订阅）。
    func watchConversations() { bridge.hubWatchConversations(onList: { [weak self] list in self?.conversations = list }) }

    /// 打开会话：切消息订阅（旧订阅由桥侧取消）。
    func open(_ conv: UiConversation) {
        selected = conv
        messages = []
        bridge.hubWatchMessages(gatewayId: conv.gatewayId, sessionKey: conv.sessionKey) { [weak self] list in
            self?.messages = list
        }
    }

    func closeConversation() {
        selected = nil
        messages = []
        chatPush = false
        hubMsgWatchCancel()
    }

    /// Dev 探针直达会话（Bool push 通道，见 chatPush 注释）。
    func devOpen(_ conv: UiConversation) {
        selected = conv
        messages = []
        chatPush = true
        bridge.hubWatchMessages(gatewayId: conv.gatewayId, sessionKey: conv.sessionKey) { [weak self] list in
            self?.messages = list
        }
    }

    private func hubMsgWatchCancel() { /* 桥侧换订阅/登出时取消；此句柄 P4 收紧 */ }

    func send(_ text: String) {
        guard let c = selected else { return }
        bridge.hubSend(conversationId: c.conversationId ?? "", gatewayId: c.gatewayId, sessionKey: c.sessionKey, text: text)
    }

    /// 渲染完成后上报已读（取已渲染最大 seq）。
    func markReadUpTo(_ seq: Int64) {
        guard let c = selected, let id = c.conversationId else { return }
        bridge.hubMarkRead(conversationId: id, gatewayId: c.gatewayId, sessionKey: c.sessionKey, readUpTo: seq)
    }

    /// 设备清单（设备管理页；每次进入拉新）。
    func refreshDevices() {
        bridge.hubDevices { [weak self] list in self?.devicesCache = list }
    }

    var devices: [DeviceInfo]? { devicesCache }

    func revokeDevice(_ deviceId: String) { bridge.hubRevokeDevice(deviceId: deviceId) { [weak self] in self?.refreshDevices() } }

    var baseUrl: String {
        didSet { UserDefaults.standard.set(baseUrl, forKey: Self.baseUrlKey) }
    }

    private static let baseUrlKey = "hub.baseUrl"
    private let bridge: AgentBridge

    private init() {
        bridge = AppStore.shared.bridge
        baseUrl = UserDefaults.standard.string(forKey: Self.baseUrlKey) ?? ""
        bridge.hubObserve { [weak self] kind, message, user, deviceId in
            guard let self else { return }
            self.kind = kind
            self.message = message
            self.userDisplay = user
            self.deviceId = deviceId
            if kind != "working" { self.isRestoring = false }
            if kind == "loggedIn" { self.auth006VerdictSnapshot = self.auth006Verdict }
        }
        if !baseUrl.isEmpty {
            isRestoring = true
            bridge.hubRestore(baseUrl: baseUrl)
        }
        #if DEBUG
        // Dev 通道（与网关时代 Documents/dev.json 同模式）：沙盒 Documents/hub-dev.json
        // {"baseUrl","username","password"} 存在即自动登录一次，供模拟器无触控注入的自动化验收。
        // 读取后立即删除文件，口令不滞留沙盒；Release 包不编译此段。
        if let dev = Self.loadDevLogin() {
            try? FileManager.default.removeItem(at: dev.url)
            login(baseUrl: dev.baseUrl, username: dev.username, password: dev.password)
        }
        #endif
    }

    #if DEBUG
    private struct DevLogin: Decodable {
        let baseUrl: String
        let username: String
        let password: String
    }

    private static func loadDevLogin() -> (url: URL, baseUrl: String, username: String, password: String)? {
        guard let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return nil }
        let url = docs.appendingPathComponent("hub-dev.json")
        guard let data = try? Data(contentsOf: url),
              let d = try? JSONDecoder().decode(DevLogin.self, from: data) else { return nil }
        return (url, d.baseUrl, d.username, d.password)
    }
    #endif

    var isLoggedIn: Bool { kind == "loggedIn" }

    func login(baseUrl: String, username: String, password: String) {
        self.baseUrl = baseUrl
        bridge.hubLogin(
            baseUrl: baseUrl,
            username: username,
            password: password,
            deviceName: UIDevice.current.name
        )
    }

    /// 登出 = 撤销当前设备（服务端 Token 即时失效）+ 清 Keychain。
    func logout() { bridge.hubLogout() }

    /// 最近一次登录的 AUTH-006 契约预检判定（验收取证）。
    var auth006Verdict: String? { bridge.hubAuth006Verdict() }
}
