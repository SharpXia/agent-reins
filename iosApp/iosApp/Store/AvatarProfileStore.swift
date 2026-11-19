import Foundation
import SwiftUI

// MARK: - 用户级机器人形象档（v1.5.0 个性化形象轮；契约 AvatarProfile schema 同形）

/// 形象档模型（桥面以 JSON 串跨边界——DTO 纪律：Kotlin 枚举/List 不直接过 ObjC 互携，
/// 编解码两端各自宽松）。与 shared `AvatarProfile`（Kotlin）/ 契约 components 同形：
/// faceFactors 12 控制点半径系数 ∈[0.70,1.10] / paletteId 十色闭集 / motionLevel
/// {off,standard} / updatedAt Rfc3339Millis（服务端权威，last-write-wins 比较键）。
struct AvatarProfileModel: Equatable {
    var faceFactors: [Double]
    var paletteId: String
    var motionLevel: String
    var updatedAt: String

    static let epoch = "1970-01-01T00:00:00.000Z"
    static let defaultFactors: [Double] = Array(repeating: 1.0, count: 12)

    /// 默认档常量（双端同源：正圆 + GRAY + standard + epoch 哨兵 = 从未设置）。
    static let `default` = AvatarProfileModel(
        faceFactors: AvatarProfileModel.defaultFactors,
        paletteId: "GRAY",
        motionLevel: "standard",
        updatedAt: AvatarProfileModel.epoch,
    )

    private struct Wire: Decodable {
        let faceFactors: [Double]?
        let paletteId: String?
        let motionLevel: String?
        let updatedAt: String?
    }

    private struct RequestWire: Encodable {
        let faceFactors: [Double]
        let paletteId: String
        let motionLevel: String
    }

    /// 缓存形态（UserDefaults `hub.avatarProfile.v1`）：四字段恒在（含 updatedAt）——
    /// 与桌面 DesktopAvatarPreferences（shared encodeJson，AvatarProfile.kt
    /// @EncodeDefault(Mode.ALWAYS)）parity；updatedAt 缺位会把跨重启的 last-write-wins
    /// 比较键折回 epoch 哨兵、自收帧同值幂等跳过保护失效（跨端复核意见① 修法）。
    private struct CacheWire: Encodable {
        let faceFactors: [Double]
        let paletteId: String
        let motionLevel: String
        let updatedAt: String
    }

    /// 宽松解析（收侧，设计稿 §dataModel 规则 4）：未知字段忽略；长度 ≠ 12 或任一项越界
    /// [0.70,1.10] → 回退正圆；未知 paletteId → GRAY、未知 motionLevel → standard。
    init?(json: String) {
        guard let data = json.data(using: .utf8),
              let wire = try? JSONDecoder().decode(Wire.self, from: data) else { return nil }
        // faceFactors：长度 ≠ 12 / 越界 / 非数 → 回退正圆（与 ClayFace.parse 失败回退同纪律）
        let factors = wire.faceFactors ?? []
        let inRange = factors.count == 12 && factors.allSatisfy { $0.isFinite && $0 >= 0.70 && $0 <= 1.10 }
        self.faceFactors = inRange ? factors : AvatarProfileModel.defaultFactors
        self.paletteId = AvatarProfileModel.knownPalette(wire.paletteId)
        self.motionLevel = AvatarProfileModel.knownMotion(wire.motionLevel)
        self.updatedAt = wire.updatedAt ?? AvatarProfileModel.epoch
    }

    init(faceFactors: [Double], paletteId: String, motionLevel: String, updatedAt: String) {
        self.faceFactors = faceFactors
        self.paletteId = paletteId
        self.motionLevel = motionLevel
        self.updatedAt = updatedAt
    }

    /// 渲染面十色映射（未知回落 GRAY——宽松解析同纪律）。
    var paletteName: AgentTintName { AgentTintName(rawValue: paletteId) ?? .GRAY }

    /// 动效门控（unknown → standard = 开；off 唯一关值）。
    var motionEnabled: Bool { motionLevel != "off" }

    /// 收敛比较键：规范化 face 串（encode 同形 59 字符）——原始数组不保尾零不可比。
    var faceKey: String { ClayFace.encode(faceFactors) }

    /// 全档 JSON（缓存/落 UserDefaults 形态；四字段恒在含 updatedAt——见 [CacheWire]）。
    var json: String {
        let wire = CacheWire(
            faceFactors: faceFactors, paletteId: paletteId,
            motionLevel: motionLevel, updatedAt: updatedAt,
        )
        guard let data = try? JSONEncoder().encode(wire) else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    /// 请求体 JSON（端点 #31：三可编辑字段全量覆盖提交，updatedAt 不入请求——恒由服务端权威设定）。
    var requestBodyJSON: String {
        let wire = RequestWire(faceFactors: faceFactors, paletteId: paletteId, motionLevel: motionLevel)
        guard let data = try? JSONEncoder().encode(wire) else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    /// updatedAt 新旧判定：self 严格新于 [other] → true（同值 false = 幂等跳过）。
    /// Rfc3339Millis 定长串字典序即时序；漂移形态回退 ISO8601 解析，再失败回退字典序。
    func isNewer(than other: String) -> Bool {
        if updatedAt == other { return false }
        if let a = Self.parse(updatedAt), let b = Self.parse(other) { return a > b }
        return updatedAt > other
    }

    private static func parse(_ s: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }

    /// 十色闭集（声明序 = 跨端契约，照抄 AgentTintName 枚举序——未知值回落 GRAY）。
    private static func knownPalette(_ raw: String?) -> String {
        AgentTintName(rawValue: raw ?? "")?.rawValue ?? AgentTintName.GRAY.rawValue
    }

    private static func knownMotion(_ raw: String?) -> String {
        (raw == "off" || raw == "standard") ? (raw ?? "standard") : "standard"
    }
}

// MARK: - 预设目录（8 款，UI 侧常量表；12 值表与命名/排序与桌面 AVATAR_PRESETS 逐字一致）

struct AvatarPreset: Identifiable, Equatable {
    let id: String
    let zhName: String
    let faceFactors: [Double]
    let recommendedPalette: AgentTintName

    /// 规范化 face 串（encode 同形）：预设选中判定比较键（与 AvatarProfileModel.faceKey 同口径）。
    var faceKey: String { ClayFace.encode(faceFactors) }
}

enum AvatarPresetCatalog {
    /// 设计稿预设表唯一事实（P1–P8）；挑选 = 造型 + 推荐配色一并写入 faceFactors/paletteId。
    /// 服务端/契约不感知预设概念（后续加预设零契约改动）。
    static let all: [AvatarPreset] = [
        AvatarPreset(id: "P1", zhName: "圆满",
                     faceFactors: [1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00],
                     recommendedPalette: .GRAY),
        AvatarPreset(id: "P2", zhName: "卵石",
                     faceFactors: [1.03, 1.02, 1.01, 1.04, 1.00, 0.92, 0.94, 1.07, 1.10, 0.99, 0.91, 0.97],
                     recommendedPalette: .BROWN),
        AvatarPreset(id: "P3", zhName: "方糖",
                     faceFactors: [1.06, 0.96, 1.02, 1.03, 0.92, 1.01, 1.06, 0.96, 1.02, 1.03, 0.92, 1.01],
                     recommendedPalette: .BLUE),
        AvatarPreset(id: "P4", zhName: "胶囊",
                     faceFactors: [0.94, 0.98, 1.06, 1.10, 1.06, 0.98, 0.94, 0.98, 1.06, 1.10, 1.06, 0.98],
                     recommendedPalette: .CYAN),
        AvatarPreset(id: "P5", zhName: "水滴",
                     faceFactors: [1.10, 1.10, 1.04, 1.00, 0.98, 0.97, 0.96, 0.97, 0.98, 1.00, 1.04, 1.10],
                     recommendedPalette: .GREEN),
        AvatarPreset(id: "P6", zhName: "莓果",
                     faceFactors: [1.07, 0.99, 0.93, 1.01, 1.07, 0.99, 0.93, 1.01, 1.07, 0.99, 0.93, 1.01],
                     recommendedPalette: .MAGENTA),
        AvatarPreset(id: "P7", zhName: "蜂窝",
                     faceFactors: [1.05, 0.95, 1.05, 0.95, 1.05, 0.95, 1.05, 0.95, 1.05, 0.95, 1.05, 0.95],
                     recommendedPalette: .PURPLE),
        AvatarPreset(id: "P8", zhName: "南瓜",
                     faceFactors: [0.94, 0.96, 1.01, 1.04, 0.99, 0.93, 0.90, 0.93, 0.99, 1.04, 1.01, 0.96],
                     recommendedPalette: .ORANGE),
    ]
}

// MARK: - 形象档状态仓（@Observable；消费 shared avatarProfile 收敛流 + UserDefaults 缓存）

/// v1.5.0：形象档状态面（HubStore 持有，AgentBridge.hubWatchAvatarProfile 推 JSON 串进来）。
/// 缓存 = UserDefaults 键 `hub.avatarProfile.v1`（JSON 串；**读式 object(forKey:)** 防缺键
/// 误读/bool 陷阱；`hub.<feature>` 键族不撞 `hub.muted.` 前缀枚举回灌；跨登出保留 =
/// streamingPlayback 先例）。收敛 = updatedAt 大者胜（last-write-wins），同值幂等跳过
/// （发起设备自收 avatar.updated 帧防回环）；编辑中远端更新不出发表单 → remotePending
/// 顶栏提示 + 一键拉取（设计稿 §syncMechanism 读路径④）。
@Observable
final class AvatarProfileStore {
    /// 当前呈现档（登录前/断网 = 缓存否则内置默认档；登录回拉/重连回拉/实时帧三路收敛）。
    private(set) var profile: AvatarProfileModel = .default
    /// 编辑期间到达的远端更新（冲突提示数据面；拉取/保存成功后清）。
    private(set) var remotePending: AvatarProfileModel?

    /// 设置页编辑中标记（草稿在途；远端更新让位草稿不覆盖表单）。
    var isEditing = false

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        // 读式 object(forKey:)（object(forKey:) as? String 对 Bool 值返回 nil，天然防误读）
        if let raw = defaults.object(forKey: Self.cacheKey) as? String,
           let cached = AvatarProfileModel(json: raw) {
            profile = cached
        }
    }

    private static let cacheKey = "hub.avatarProfile.v1"

    /// 远端收敛（shared 流推帧 / PUT 权威回填共用；[force] = 本端 PUT 权威响应直接落）。
    func applyRemote(json: String?, force: Bool = false) {
        guard let json, let incoming = AvatarProfileModel(json: json) else { return }
        guard incoming.isNewer(than: profile.updatedAt) else { return } // 同值/旧值幂等跳过
        if isEditing && !force {
            remotePending = incoming // 编辑中不出发表单：顶栏提示 + 一键拉取
            return
        }
        profile = incoming
        persist()
        remotePending = nil
    }

    /// 冲突横幅「拉取」：采纳远端权威值（放弃在途草稿由设置页另行清）。
    func adoptRemotePending() {
        guard let pending = remotePending else { return }
        if pending.isNewer(than: profile.updatedAt) {
            profile = pending
            persist()
        }
        remotePending = nil
    }

    /// 清编辑瞬态（保存成功/一键拉取/登出；profile 本体与 UserDefaults 缓存不动——跨登出保留）。
    func clearRemotePending() {
        remotePending = nil
    }

    /// 本地档显式覆盖（本端 PUT 成功权威回填走 applyRemote(force:)，此处供测试/兜底）。
    func persist() {
        let payload = profile.json
        guard !payload.isEmpty else { return }
        defaults.set(payload, forKey: Self.cacheKey)
    }
}
