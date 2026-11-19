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
    private static let frameKey = "mac.windowFrame.v1"
    /// 存档有效性下限：窄于最小可用窗（App.swift minWidth/minHeight = 720/480）的帧不恢复
    /// 不保存，防异常几何污染存档。
    private static let minValidSize: CGFloat = 480
    #endif

    // MARK: - UISceneDelegate（iOS 构建为空壳；逻辑仅 Catalyst）

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        #if targetEnvironment(macCatalyst)
        guard let windowScene = scene as? UIWindowScene else { return }
        // 窗口 sizing 能力显式恢复（2026-11-19 窗口锁死修复轮）：delegateClass 注入接管了
        // scene 连接路径，SwiftUI WindowGroup 的 windowResizability 设置不再落地——
        // sizeRestrictions min==max 即窗口锁死（拖边缘不能缩放 / zoom 按钮灰致双击标题栏
        // 无效 / 窗口停在 defaultSize，三症状同源）。UIKit 文档化 API（UISceneSizeRestrictions，
        // iOS 13+ / allowsFullScreen Catalyst 16+）显式放开：min=App.swift 内容下限，max=
        // CGFLOAT_MAX（系统按屏幕 clamp）。windowingBehaviors 的 closable/miniaturizable
        // 系统默认已开，不动。
        if let restrictions = windowScene.sizeRestrictions {
            restrictions.minimumSize = CGSize(width: 720, height: 480)
            restrictions.maximumSize = CGSize(width: CGFloat.greatestFiniteMagnitude,
                                              height: CGFloat.greatestFiniteMagnitude)
            restrictions.allowsFullScreen = true
        }
        guard let frame = Self.loadSavedFrame() else { return }
        // 有效性校验（评审修正①同款）：非退化尺寸 + 与任一屏幕相交——全离屏帧不恢复，
        // 防窗口落到可视区外（拔显示器/分辨率变化后的陈旧存档）。
        guard frame.width >= Self.minValidSize, frame.height >= Self.minValidSize,
              Self.onScreen(frame) else { return }
        windowScene.requestGeometryUpdate(
            UIWindowScene.GeometryPreferences.Mac(systemFrame: frame)
        ) { error in
            NSLog("[MacWindowFrame] restore failed: \(error.localizedDescription)")
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
