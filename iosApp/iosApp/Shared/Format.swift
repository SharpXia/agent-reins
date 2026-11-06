import AVFoundation
import Foundation
import UIKit

// MARK: - 格式化工具

/// DateFormatter construction is relatively expensive and these helpers are called for
/// every visible message row. Keep the formatters alive for the lifetime of the app so a
/// bridge snapshot or a scroll pass does not allocate one per row.
private enum ChatDateFormatters {
    static let time = make("HH:mm")
    static let crossDayTime = make("M-d HH:mm")
    static let day = make("M月d日")
    static let dayWithYear = make("yyyy年M月d日")

    private static func make(_ format: String) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.dateFormat = format
        return formatter
    }
}

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
    if cal.isDateInToday(date) { return ChatDateFormatters.time.string(from: date) }
    return ChatDateFormatters.crossDayTime.string(from: date)
}

// ISO8601 解析器（带小数秒/不带两把；构造昂贵，进程级复用——同 ChatDateFormatters 纪律）。
private let isoFractional: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return f
}()
private let isoPlain: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime]
    return f
}()

/// 消息时间（ISO8601/epoch 毫秒串 → 当天 HH:mm / 跨天 M-d HH:mm）。
/// 服务端快照行 timestamp（RFC3339Nano，Go 序列化带纳秒小数+时区偏移）、事件路径信封 ts
/// 同走此入口；解析失败或空返回 ""（行渲染侧据此省略，不虚构时间）。
func formatMessageTimeISO(_ raw: String?) -> String {
    guard let raw, !raw.isEmpty else { return "" }
    var date = raw.contains(".")
        ? isoFractional.date(from: raw)
        : nil
    if date == nil { date = isoPlain.date(from: raw) }
    if let date { return formatMessageTime(Int64(date.timeIntervalSince1970 * 1000)) }
    if let ms = Int64(raw) { return formatMessageTime(ms) }
    return ""
}

/// 日分隔文案：今天 / 昨天 / M月d日（跨年补年份）。
func dayHeaderLabel(_ atMs: Int64) -> String {
    let date = Date(timeIntervalSince1970: Double(atMs) / 1000)
    let cal = Calendar.current
    if cal.isDateInToday(date) { return "今天" }
    if cal.isDateInYesterday(date) { return "昨天" }
    let year = cal.component(.year, from: date)
    let thisYear = cal.component(.year, from: Date())
    return year != thisYear
        ? ChatDateFormatters.dayWithYear.string(from: date)
        : ChatDateFormatters.day.string(from: date)
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

// MARK: - 通知音 / 触感（Hub 面尚未接通知音；资源与试听面保留，待设置页重建时启用）

/// 通知音枚举（"none" = 静音；rawValue 为持久化名）。
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

    /// bundle 内资源名（sfx_<key>.wav）。
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

    /// 试听（ambient 类别：混音、随静音键）。
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
