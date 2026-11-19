import SwiftUI
import UIKit

/// Mac Catalyst 窗口帧存取（UxPlan item1；评审修正①② + UX 复审 high 修复已吸收）：
/// - Catalyst 不自动记忆窗口帧（SwiftUI WindowGroup 无此状态恢复；web 检索 fpposchmann.de /
///   vbutko.com 两篇 Catalyst 专文同口径）——恢复走文档化路径
///   `windowScene.requestGeometryUpdate(.mac(systemFrame:))`，保存读
///   `effectiveGeometry.systemFrame`；直改 UIWindow.frame 不是文档化/可靠路径，不采用。
/// - **接线方式（UX 复审 high 修复）**：SwiftUI App 生命周期绕过 Info.plist 的
///   UISceneDelegateClassName（复审最小复现实证：plist 声明不生效，双平台同）——本 delegate
///   由 AppDelegate.application(_:configurationForConnectingSceneSession:options:)（App.swift，
///   Catalyst 门控）以 `config.delegateClass = MacWindowSceneDelegate.self` 注入；类本体双平台
///   无条件定义（iOS 侧方法体为空壳），iOS 构建不实现 configurationForConnecting，零差异。
/// - 保存时机：sceneWillResignActive（⌘Tab/关窗/⌘Q 前置路径）+ sceneDidEnterBackground +
///   sceneDidDisconnect 兜底；live resize 期间长驻 active 由根视图几何防抖保存兜
///   （MacWindowFrameSaver，挂 RootView）。sceneWillTerminate 不覆盖强退，不作依赖。
/// - 存档键 UserDefaults["mac.windowFrame.v1"]：x/y/w/h（system 坐标，含菜单栏偏移）。
final class MacWindowSceneDelegate: NSObject, UIWindowSceneDelegate {

    #if targetEnvironment(macCatalyst)
    /// v2（beta-0.5.20 修复轮）：v1 存档是「窗口锁死时代」的产物（980×640 小帧非用户
    /// 偏好），键升级一次性作废——新装首启走 defaultSize 1280×820，本版起重新积累。
    private static let frameKey = "mac.windowFrame.v2"
    /// 存档有效性下限：窄于最小可用窗（App.swift minWidth/minHeight = 720/480）的帧不恢复
    /// 不保存，防异常几何污染存档。
    private static let minValidSize: CGFloat = 480
    /// 窗口 sizing 下限（与 App.swift RootView frame(minWidth/minHeight) 同测度）。
    private static let minWindowSize = CGSize(width: 720, height: 480)

    /// 显式钉窗口 sizeRestrictions（min 下限放开 max）：SwiftUI 退出 sizing 管辖
    /// （App.swift 已撤 windowResizability）后，这里是窗口 resizable 的唯一权威面。
    /// willConnect 首钉 + didBecomeActive 重钉（防 SwiftUI/系统后续改写，日志留痕）。
    @MainActor
    static func applySizeRestrictions(_ windowScene: UIWindowScene) {
        guard let restrictions = windowScene.sizeRestrictions else {
            NSLog("[MacWindowFrame] sizeRestrictions unavailable")
            return
        }
        NSLog("[MacWindowFrame] sizeRestrictions before: \\(restrictions.minimumSize) → set min \\(minWindowSize) max ∞")
        restrictions.minimumSize = minWindowSize
        restrictions.maximumSize = CGSize(width: CGFloat.greatestFiniteMagnitude,
                                          height: CGFloat.greatestFiniteMagnitude)
        restrictions.allowsFullScreen = true
    }

    /// DEBUG 探针（beta-0.5.20 本地验证轮）：启动稳定后自证窗口 sizing 链——窗口数、初始
    /// 帧、sizeRestrictions 现值，再主动 requestGeometryUpdate 到 1400×900（与用户拖拽同
    /// 一条系统路径）读回 effectiveGeometry 判「可缩放」真伪。结果写沙箱容器 Caches
    /// probe-report.json（宿主直读，绕开无屏幕录制/辅助功能权限的验证盲区）。#if DEBUG：
    /// TF Release 包零残留。
    #if DEBUG
    @MainActor
    static func verifyWindowBehavior(_ windowScene: UIWindowScene) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            var report: [String: Any] = [:]
            report["windowCount"] = windowScene.windows.count
            report["effectiveFrame"] = NSStringFromRect(windowScene.effectiveGeometry.systemFrame)
            if let r = windowScene.sizeRestrictions {
                report["restrictions"] = ["min": NSStringFromSize(r.minimumSize),
                                          "max": NSStringFromSize(r.maximumSize),
                                          "allowsFullScreen": r.allowsFullScreen]
            } else {
                report["restrictions"] = "nil"
            }
            report["windowingBehaviors"] = [
                "closable": windowScene.windowingBehaviors?.isClosable as Any,
                "miniaturizable": windowScene.windowingBehaviors?.isMiniaturizable as Any,
            ]
            let target = CGRect(x: 120, y: 120, width: 1400, height: 900)
            windowScene.requestGeometryUpdate(
                UIWindowScene.GeometryPreferences.Mac(systemFrame: target)
            ) { error in
                report["resizeRequestError"] = error.localizedDescription
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                let after = windowScene.effectiveGeometry.systemFrame
                report["frameAfterResizeRequest"] = NSStringFromRect(after)
                report["resizeAccepted"] = (abs(after.width - target.width) < 40 && abs(after.height - target.height) < 40)
                if let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first {
                    try? JSONSerialization.data(withJSONObject: report).write(
                        to: dir.appendingPathComponent("probe-report.json"))
                }
                NSLog("[MacWindowFrame] probe: \\(report)")
            }
        }
    }
    #endif
    #endif

    // MARK: - UISceneDelegate（iOS 构建为空壳；逻辑仅 Catalyst）

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        #if targetEnvironment(macCatalyst)
        guard let windowScene = scene as? UIWindowScene else { return }
        // 窗口 sizing 首钉 + 期望帧恢复（beta-0.5.20 修复轮）。App.swift 已撤
        // windowResizability/defaultSize——本地探针实证 SwiftUI 把 sizeRestrictions 持续
        // 重写为其 scene 配置推导值（defaultSize → min==max 钉死即锁死根源），撤除后 sizing
        // 权威在本 delegate：restrictions min 720×480/max 放开 + 期望帧（v2 存档或首启
        // 1280×820 居中）。didBecomeActive 再做一轮权威对齐（时序最稳，见该回调）。
        Self.applySizeRestrictions(windowScene)
        if let frame = Self.preferredFrame() {
            Self.requestFrame(frame, on: windowScene)
        }
        #endif
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
        #if targetEnvironment(macCatalyst)
        // 权威对齐（beta-0.5.20）：SwiftUI 初始 layout 已完成的时点，重钉 restrictions + 把
        // 窗口对齐到期望帧（当前 effective 与期望尺寸偏差大时才请求，避免每次激活都跳位）。
        // 本地探针实证 willConnect 时点的手设会被 SwiftUI 覆盖，此回调时点在其之后。
        if let windowScene = scene as? UIWindowScene {
            Self.applySizeRestrictions(windowScene)
            if let preferred = Self.preferredFrame() {
                let current = windowScene.effectiveGeometry.systemFrame
                if abs(current.width - preferred.width) > 1 || abs(current.height - preferred.height) > 1 {
                    Self.requestFrame(preferred, on: windowScene)
                }
            }
            Self.verifyWindowBehavior(windowScene)
        }
        #endif
    }

    func sceneWillResignActive(_ scene: UIScene) {
        #if targetEnvironment(macCatalyst)
        Self.saveFrame(of: scene)
        #endif
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        #if targetEnvironment(macCatalyst)
        Self.saveFrame(of: scene)
        #endif
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        #if targetEnvironment(macCatalyst)
        Self.saveFrame(of: scene)
        #endif
    }

    #if targetEnvironment(macCatalyst)
    /// 离屏校验（编译门禁实证：NSScreen.frame 在 Mac Catalyst 不可用）——用 UIScreen
    /// 虚拟桌面并集近似：iOS 坐标系与 macOS system 坐标系共享主屏足迹、纵轴镜像
    ///（iOS_y = H_main − sys_y − h），把存档帧换到 iOS 域后与任一屏 bounds 判相交。
    private static func onScreen(_ frame: CGRect) -> Bool {
        let screens = UIScreen.screens
        guard !screens.isEmpty else { return true } // 无屏信息时不拦截（保守恢复）
        let main = screens.first { $0.bounds.minX == 0 && $0.bounds.minY == 0 } ?? screens[0]
        let flipped = CGRect(x: frame.minX, y: main.bounds.height - frame.maxY,
                             width: frame.width, height: frame.height)
        return screens.contains { $0.bounds.intersects(flipped) }
    }

    private static func loadSavedFrame() -> CGRect? {
        guard let dict = UserDefaults.standard.dictionary(forKey: frameKey),
              let x = dict["x"] as? Double, let y = dict["y"] as? Double,
              let w = dict["w"] as? Double, let h = dict["h"] as? Double,
              w > 0, h > 0 else { return nil }
        return CGRect(x: x, y: y, width: w, height: h)
    }

    /// 期望帧（beta-0.5.20）：v2 存档有效（非退化 + 在屏）则恢复存档；否则首启帧
    /// 1280×820 主屏居中（替代已撤的 .defaultSize；坐标近似用 UIScreen iOS 域，系统会
    /// clamp 到可视区）。
    @MainActor
    static func preferredFrame() -> CGRect? {
        if let saved = loadSavedFrame(),
           saved.width >= minValidSize, saved.height >= minValidSize,
           onScreen(saved) {
            return saved
        }
        let screen = UIScreen.main.bounds
        guard screen.width >= 1280 || screen.height >= 820 else {
            return CGRect(x: max(0, (screen.width - 1280) / 2),
                          y: max(0, (screen.height - 820) / 2), width: 1280, height: 820)
        }
        return CGRect(x: (screen.width - 1280) / 2, y: (screen.height - 820) / 2,
                      width: 1280, height: 820)
    }

    /// 请求窗口帧到 system 坐标（失败只留日志，窗口保持系统布局——恢复是增强面不是主路径）。
    @MainActor
    static func requestFrame(_ frame: CGRect, on windowScene: UIWindowScene) {
        windowScene.requestGeometryUpdate(
            UIWindowScene.GeometryPreferences.Mac(systemFrame: frame)
        ) { error in
            NSLog("[MacWindowFrame] request frame failed: \\(error.localizedDescription)")
        }
    }

    /// 存档（主线程调用）：读 effectiveGeometry.systemFrame（Catalyst 17+，部署目标 macOS 15）。
    @MainActor
    static func saveFrame(of scene: UIScene) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let frame = windowScene.effectiveGeometry.systemFrame
        guard frame.width >= minValidSize, frame.height >= minValidSize,
              frame.width.isFinite, frame.height.isFinite else { return }
        UserDefaults.standard.set(
            ["x": Double(frame.minX), "y": Double(frame.minY),
             "w": Double(frame.width), "h": Double(frame.height)],
            forKey: frameKey
        )
    }
    #endif
}

#if targetEnvironment(macCatalyst)

/// 根视图几何防抖保存（live resize 兜底）：窗口内容尺寸变化 → 防抖 600ms → 读
/// effectiveGeometry.systemFrame 存档。覆盖强退组合（⌘Q 直退可能不触发 scene 回调的
/// 平台差异）与 resize 期间长驻 active 的场景；挂 RootView（App.swift），仅 Catalyst 分支。
struct MacWindowFrameSaver: ViewModifier {

    private static var debounceTask: Task<Void, Never>?

    func body(content: Content) -> some View {
        content
            .onGeometryChange(for: CGSize.self, of: { $0.size }) { _ in
                Self.scheduleSave()
            }
    }

    static func scheduleSave() {
        debounceTask?.cancel()
        debounceTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 600_000_000)
            guard !Task.isCancelled else { return }
            let scene = UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .first { $0.keyWindow != nil }
            if let scene { MacWindowSceneDelegate.saveFrame(of: scene) }
        }
    }
}

extension View {
    /// Catalyst-only：挂根视图参与窗口帧存档（见 MacWindowFrameSaver 注）。
    func macWindowFrameSaver() -> some View {
        modifier(MacWindowFrameSaver())
    }
}

#endif
