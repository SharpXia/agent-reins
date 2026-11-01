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
        }
        if !baseUrl.isEmpty {
            isRestoring = true
            bridge.hubRestore(baseUrl: baseUrl)
        }
    }

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
