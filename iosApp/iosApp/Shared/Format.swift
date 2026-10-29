import AVFoundation
import Foundation
import UIKit
import composeApp

// MARK: - 格式化工具（Format.kt 移植）

/// 耗时：485ms / 3.2s / 1m12s；无数据显示 —。
func formatDuration(_ ms: Int64) -> String {
    if ms < 0 { return "—" }
    if ms < 1_000 { return "\(ms)ms" }
    if ms < 60_000 { return "\(ms / 1_000).\((ms % 1_000) / 100)s" }
    return "\(ms / 60_000)m\((ms % 60_000) / 1_000)s"
}

/// 相对时间：刚刚 / 5分钟前 / 3小时前 / 2天前；0 时间戳返回空。
func formatRelativeTime(_ epochMs: Int64) -> String {
    if epochMs <= 0 { return "" }
    let diff = Int64(Date.now.timeIntervalSince1970 * 1000) - epochMs
    let minute: Int64 = 60_000
    switch diff {
    case ..<minute: return "刚刚"
    case ..<(60 * minute): return "\(diff / minute)分钟前"
    case ..<(24 * 60 * minute): return "\(diff / (60 * minute))小时前"
    default: return "\(diff / (24 * 60 * minute))天前"
    }
}

/// 消息发送时间：当天 HH:mm，跨天 M-d HH:mm。
func formatMessageTime(_ epochMs: Int64) -> String {
    if epochMs <= 0 { return "" }
    let date = Date(timeIntervalSince1970: Double(epochMs) / 1000)
    let cal = Calendar.current
    let fmt: (String) -> String = { template in
        let f = DateFormatter()
        f.dateFormat = template
        return f.string(from: date)
    }
    if cal.isDateInToday(date) { return fmt("HH:mm") }
    return fmt("M-d HH:mm")
}

/// 日分隔文案：今天 / 昨天 / M月d日（跨年补年份）。
func dayHeaderLabel(_ atMs: Int64) -> String {
    let date = Date(timeIntervalSince1970: Double(atMs) / 1000)
    let cal = Calendar.current
    if cal.isDateInToday(date) { return "今天" }
    if cal.isDateInYesterday(date) { return "昨天" }
    let fmt: (String) -> String = { template in
        let f = DateFormatter()
        f.dateFormat = template
        return f.string(from: date)
    }
    let year = cal.component(.year, from: date)
    let thisYear = cal.component(.year, from: Date())
    return year != thisYear ? fmt("yyyy年M月d日") : fmt("M月d日")
}

// MARK: - 会话 surface 判别（SessionClassifier 移植）

enum SessionCategory {
    case mainline, derived, system, channel, unknown
}

/// key `agent:<agentId>:<surface…>` 判别，与 Kotlin SessionClassifier 同语义。
func classifySession(_ key: String) -> SessionCategory {
    guard key.hasPrefix("agent:") else { return .unknown }
    let surface = String(key.dropFirst("agent:".count))
    let idx = surface.firstIndex(of: ":").map { surface.distance(from: surface.startIndex, to: $0) }
    let s = idx.map { String(surface.suffix(from: surface.index(surface.startIndex, offsetBy: $0 + 1))) } ?? surface
    if s.isEmpty { return .unknown }
    if s == "main" || s.hasPrefix("ar-") { return .mainline }
    if s.hasPrefix("subagent:") { return .derived }
    if s.hasPrefix("cron:") || s.hasPrefix("boot:") || s.hasPrefix("dashboard:") { return .system }
    if s.contains(":direct:") { return .channel }
    return .unknown
}

/// CHANNEL 会话的渠道桶（surface 首段）。
func channelBucket(of key: String) -> String? {
    guard classifySession(key) == .channel else { return nil }
    guard key.hasPrefix("agent:") else { return nil }
    let surface = String(key.dropFirst("agent:".count))
    guard let idx = surface.firstIndex(of: ":") else { return nil }
    let bucket = String(surface[surface.index(after: idx)...]).prefix { $0 != ":" }
    return bucket.isEmpty ? nil : String(bucket)
}

/// conversation id（ConversationRepository.convId 同格式）。
func convId(_ agentId: String, channel: String? = nil) -> String {
    channel == nil ? "conv:\(agentId)" : "conv:\(agentId):\(channel!)"
}

/// session key → agentId。
func sessionKeyAgentId(_ key: String) -> String? {
    guard key.hasPrefix("agent:") else { return nil }
    let rest = key.dropFirst("agent:".count)
    guard let idx = rest.firstIndex(of: ":") else { return nil }
    let agent = String(rest[..<idx])
    return agent.isEmpty ? nil : agent
}

// MARK: - JSON pretty print

func prettyJson(_ raw: String?) -> String? {
    guard let raw else { return nil }
    guard let data = raw.data(using: .utf8),
          let obj = try? JSONSerialization.jsonObject(with: data),
          let pretty = try? JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted, .sortedKeys]),
          let text = String(data: pretty, encoding: .utf8) else { return raw }
    return text
}

// MARK: - 桥接快照的 Swift 侧辅助

extension BridgeConv {
    static func empty() -> BridgeConv {
        BridgeConv(sessionKey: "", entries: [], hasMoreHistory: false, historyLoaded: false,
                   runActive: false, loadingOlder: false, queuedCount: 0, pendingOutbox: [],
                   sendSignal: 0, readOnly: false)
    }
}

extension BridgeLists {
    static func empty() -> BridgeLists {
        BridgeLists(conversations: [], sessions: [], agents: [])
    }
}

extension BridgeTimeline {
    static func empty() -> BridgeTimeline {
        BridgeTimeline(agentId: nil, sections: [], sectionLoading: nil, compactMarks: [:])
    }
}

extension BridgeMisc {
    static func empty() -> BridgeMisc {
        BridgeMisc(pendingApproval: nil, autoOpenKey: nil, draft: "", quotedText: nil,
                   creatingSession: false, themeMode: "SYSTEM", soundEnabled: true,
                   hapticsEnabled: true, notificationSound: "DOUBLE_TICK", searchHits: [], searching: false)
    }

    var notificationSoundName: AppSoundName? {
        AppSoundName(rawValue: notificationSound)
    }
}

extension BridgeConn {
    static func empty() -> BridgeConn {
        BridgeConn(kind: "disconnected", message: nil, requestId: nil, gatewayName: nil,
                   connectedUrl: nil, busy: false, lastError: nil, hasPairedCredential: false,
                   url: "", apiKey: "", alternateUrls: [], setupCode: "",
                   approvalsEnabled: false, onboardingDone: false, showOnboarding: false,
                   devAutoSelectKey: nil)
    }
}

extension BridgeTimeline {
    /// NSDictionary<String, ComposeAppLong> → [String: Int64]。
    var marks: [String: Int64] {
        var out: [String: Int64] = [:]
        for (k, v) in compactMarks { out[k] = v.int64Value }
        return out
    }
}

extension BridgeApproval: Identifiable {}
extension BridgeConversation: Identifiable {}
extension BridgeSession: Identifiable {}

/// 通知音枚举（与 Kotlin AppSound 名字对齐；"none" = 静音）。
enum AppSoundName: String, CaseIterable {
    case BLIP, TICK, DOUBLE_TICK, CHIME_A, CHIME_B

    var label: String {
        switch self {
        case .BLIP: return "Blip"
        case .TICK: return "Tick"
        case .DOUBLE_TICK: return "Double Tick"
        case .CHIME_A: return "Chime A"
        case .CHIME_B: return "Chime B"
        }
    }

    var subtitle: String {
        switch self {
        case .BLIP: return "上行 blip"
        case .TICK: return "短滴答"
        case .DOUBLE_TICK: return "双滴答 · 默认"
        case .CHIME_A: return "上行风铃"
        case .CHIME_B: return "下行风铃"
        }
    }

    /// bundle 内资源名（sfx_<key>.wav，与 Kotlin SoundPlayer.ios.kt 映射一致）。
    var fileKey: String {
        switch self {
        case .BLIP: return "blip"
        case .TICK: return "tick"
        case .DOUBLE_TICK: return "dbltick"
        case .CHIME_A: return "chimea"
        case .CHIME_B: return "chimeb"
        }
    }
}

// MARK: - 音效试听 / 触感（事件音由 Kotlin AppModel 侧负责，Swift 只做 UI 触发面）

enum Feedback {
    static func hapticTap(enabled: Bool) {
        guard enabled else { return }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    static func hapticSuccess(enabled: Bool) {
        guard enabled else { return }
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    private static var players: [String: AVAudioPlayer] = [:]

    /// 设置页试听（ambient 类别：混音、随静音键）。
    static func play(sound: AppSoundName) {
        if players[sound.fileKey] == nil {
            guard let path = Bundle.main.path(forResource: "sfx_\(sound.fileKey)", ofType: "wav") else { return }
            players[sound.fileKey] = try? AVAudioPlayer(contentsOf: URL(fileURLWithPath: path))
        }
        guard let p = players[sound.fileKey] else { return }
        p.currentTime = 0
        p.play()
    }
}
