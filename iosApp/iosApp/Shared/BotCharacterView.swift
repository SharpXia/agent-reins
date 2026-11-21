import SwiftUI
import UIKit

// MARK: - 会话角色 BotCharacter（角色系统批次1，2026-11-20）：种子/状态 Swift 镜像派生
//
// shared `botSeedFor`/`botStateFor`（BotSeed.kt，三端单一事实）的 Swift 镜像——本批不直调
// Kotlin 框架面（仓内无 Swift→*Kt 门面调用先例，ObjC 导出名未经门禁实证），走 contractNote
// 拍板的回退案：InkTheme.swift:240-253 镜像先例 + 金标 3 枚真实 ULID 常量双端对锁
// （JVM 侧 = desktopApp BotSeedRulesTest 同表期望值；任一侧派生漂移 DEBUG 即断）。
// 公式与取值与 Kotlin 逐字对齐（数值勿单端改）：h = kotlinHashCode(cid)（Int32 溢出环绕），
// paletteIndex = floorMod(h,10)、bodyIndex = floorMod(h>>8,8)、eyeIndex = floorMod(h>>16,3)、
// poseIndex = floorMod(h>>22,4)、blinkTempoMs = 2600 + floorMod(h>>26,7)×400（>> 为算术右移，
// 负 hash 符号扩展，双端同值）。conversationId = 26 位 Crockford ULID 且化身更替后不变
// （HubModels.kt:72）→ 同会话跨设备恒定；#29 删除重建 = 新 ULID = 新角色（预期）。

/// 会话化身种子（shared BotSeed 同形镜像，仅 Int 字段——DTO 纪律 AgentBridge.kt:233-235）。
struct BotSeed: Equatable {
    /// 0..9 → AgentTintName 十色序（= AvatarPalette 声明序契约）。
    let paletteIndex: Int
    /// 0..7 → AvatarPresetCatalog.all（P1–P8，44 基半径核定十二值表）。
    let bodyIndex: Int
    /// 0..2 → 眼形（0 圆角竖方 / 1 椭圆 / 2 正圆）。
    let eyeIndex: Int
    /// 0..3 → 静息姿态（BotCharacterMotion.restTilt / restEyeX 双端同表）。
    let poseIndex: Int
    /// 眨眼周期毫秒 ∈ [2600, 5000] 步进 400。
    let blinkTempoMs: Int
}

/// 会话六态（shared botStateFor 编码 0..5 的渲染端映射；批次2 接线消费 Int 时走 init(code:)）。
enum BotState: Equatable {
    case idle
    case running
    case thinking
    case waiting
    case error
    case done

    /// 六态稳定编码 0..5（跨端 Int 过界契约，勿重排）。
    var code: Int {
        switch self {
        case .idle: return 0
        case .running: return 1
        case .thinking: return 2
        case .waiting: return 3
        case .error: return 4
        case .done: return 5
        }
    }

    init(code: Int) {
        switch code {
        case 1: self = .running
        case 2: self = .thinking
        case 3: self = .waiting
        case 4: self = .error
        case 5: self = .done
        default: self = .idle
        }
    }
}

private func floorMod(_ v: Int32, _ m: Int32) -> Int32 { ((v % m) + m) % m }

/// 会话化身种子派生（shared botSeedFor 镜像，纯函数无随机源；金标锁见 BotCharacterGoldLock）。
func botSeedFor(_ conversationId: String) -> BotSeed {
    let h = kotlinHashCode(conversationId) // InkTheme.swift:231-236 同源复刻（Int32）
    return BotSeed(
        paletteIndex: Int(floorMod(h, 10)),
        bodyIndex: Int(floorMod(h >> 8, 8)),
        eyeIndex: Int(floorMod(h >> 16, 3)),
        poseIndex: Int(floorMod(h >> 22, 4)),
        blinkTempoMs: Int(2600 + floorMod(h >> 26, 7) * 400),
    )
}

/// 会话六态判定（shared botStateFor 镜像）：WAITING > RUNNING(thinkingNow?THINKING:RUNNING)
/// > ERROR > DONE(瞬时) > IDLE。DONE = 视图侦测 hasActiveRun 真→假（终态非 failed）触发、
/// 900ms 回落（视图层计时；ERROR > DONE：弹跳窗内新失败即转 ERROR）。
func botStateFor(
    hasActiveRun: Bool,
    thinkingNow: Bool,
    awaitingApproval: Bool,
    lastMessageFailed: Bool,
    doneTransient: Bool = false,
) -> BotState {
    if awaitingApproval { return .waiting }
    if hasActiveRun { return thinkingNow ? .thinking : .running }
    if lastMessageFailed { return .error }
    if doneTransient { return .done }
    return .idle
}

/// 金标 3 枚真实 ULID（仓内文档留痕现役标识）+ 期望值表（与 BotSeedRulesTest 同表勿改）+
/// 嘴部/幅度新表锁 [mouthAmplitudeLock]（走查④修，与桌面 BotMouthRulesTest 同判据字面量表）。
/// 进程内首个 BotCharacterView 实例化时 DEBUG 断言一次（Release 零开销）。
private enum BotCharacterGoldLock {
    static let verified: Void = {
        #if DEBUG
        let expected: [(String, BotSeed)] = [
            // multimodal-probe-20261108 探针会话 → h=1736592439
            ("01M6YV6ZSDDADH8J8KYRKWR0C5", BotSeed(paletteIndex: 9, bodyIndex: 4, eyeIndex: 2, poseIndex: 2, blinkTempoMs: 4200)),
            // tf-release-0.5.14 回归设备 → h=1403362355
            ("01M7FZXDT0550C25S5FPYRRTKZ", BotSeed(paletteIndex: 5, bodyIndex: 4, eyeIndex: 2, poseIndex: 2, blinkTempoMs: 5000)),
            // hermes-s6 网关 → h=−872450404（负 hash 桶位路径）
            ("01M7FDA4YK22MP9YWE06PAW83X", BotSeed(paletteIndex: 6, bodyIndex: 6, eyeIndex: 1, poseIndex: 3, blinkTempoMs: 2600)),
        ]
        for (cid, want) in expected {
            let got = botSeedFor(cid)
            assert(got == want, "botSeedFor 金标漂移：\(cid) → \(got)（应为 \(want)）")
        }
        assert(botStateFor(hasActiveRun: true, thinkingNow: true, awaitingApproval: true, lastMessageFailed: true, doneTransient: true) == .waiting)
        assert(botStateFor(hasActiveRun: true, thinkingNow: true, awaitingApproval: false, lastMessageFailed: true) == .thinking)
        assert(botStateFor(hasActiveRun: true, thinkingNow: false, awaitingApproval: false, lastMessageFailed: true) == .running)
        assert(botStateFor(hasActiveRun: false, thinkingNow: false, awaitingApproval: false, lastMessageFailed: true, doneTransient: true) == .error)
        assert(botStateFor(hasActiveRun: false, thinkingNow: false, awaitingApproval: false, lastMessageFailed: false, doneTransient: true) == .done)
        assert(botStateFor(hasActiveRun: false, thinkingNow: false, awaitingApproval: false, lastMessageFailed: false) == .idle)
        mouthAmplitudeLock()
        #endif
    }()

    // 嘴部+幅度新表双端机制锁（走查④修，2026-11-21）：此前新表只有「人工并排 diff」纪律
    // 兜底——这里与桌面 BotMouthRulesTest 同判据字面量表，单端改常量/公式/钉点 DEBUG 即断，
    // 不再静默漂移。approx 容差 0.01 容跨平台 Float 三角函数尾差；dyadic 字面量走恒等比对。
    // #if DEBUG 整段编译期剔除（与上方金标锁同口径，Release 零开销）。
    #if DEBUG
    private static func mouthAmplitudeLock() {
        func approx(_ a: CGFloat, _ b: CGFloat) -> Bool { abs(a - b) < 0.01 }
        let neutral = BotSeed(paletteIndex: 0, bodyIndex: 0, eyeIndex: 0, poseIndex: 0, blinkTempoMs: 2600)
        // 窗/幅随窗（扩窗回归锚——改窗必双端同改）
        assert(BotCharacterMotion.windowX == 4.5 && BotCharacterMotion.windowY == 3.5, "眼窗 ±4.5/±3.5 漂移")
        assert(BotCharacterMotion.gazeAmpX == 4.5 && BotCharacterMotion.gazeAmpY == 3.5, "gaze 幅未随窗")
        assert(BotGaze.down == BotGaze(dx: 0, dy: BotCharacterMotion.windowY), "下视窗底未引 windowY")
        // 嘴帧逐态钉点（与桌面 BotMouthRulesTest 同表勿改）
        let mIdle = BotCharacterMotion.mouthFrame(.idle, 0, false)
        assert(approx(mIdle.open, 0.10) && approx(mIdle.curve, 0.55), "IDLE 嘴钉点漂移")
        let mThink = BotCharacterMotion.mouthFrame(.thinking, 0, false)
        assert(approx(mThink.open, 0.45) && approx(mThink.wide, 0.70) && approx(mThink.dx, 1.5), "THINKING 嘴钉点漂移")
        assert(approx(BotCharacterMotion.mouthFrame(.running, 0, false).open, 0.30), "RUNNING 开合下界漂移")
        assert(approx(BotCharacterMotion.mouthFrame(.running, 125, false).open, 0.632), "RUNNING 钉点 t=125 open≈0.632 漂移")
        assert(approx(BotCharacterMotion.mouthFrame(.running, 190, false).open, 0.75), "RUNNING 开合上界漂移")
        let mWait = BotCharacterMotion.mouthFrame(.waiting, 1000, false)
        assert(mWait.open == 0 && approx(mWait.curve, -0.15), "WAITING 钉点应抿嘴（1000 避叹气窗）")
        let mSigh = BotCharacterMotion.mouthFrame(.waiting, 730, false)
        assert(approx(mSigh.open, 0.35) && approx(mSigh.curve, -0.45), "叹气窗峰值漂移")
        let mErr = BotCharacterMotion.mouthFrame(.error, 500, false)
        assert(mErr.open == 0 && approx(mErr.curve, -0.5), "ERROR 钉点漂移")
        assert(approx(BotCharacterMotion.mouthFrame(.error, 262.5, false).curve, -0.75), "ERROR 颤动下探界漂移")
        assert(approx(BotCharacterMotion.mouthFrame(.error, 37.5, false).curve, -0.25), "ERROR 颤动上探界漂移")
        let mDone = BotCharacterMotion.mouthFrame(.done, 225, false)
        assert(approx(mDone.open, 0.85) && approx(mDone.wide, 1.35) && approx(mDone.curve, 0.7), "DONE 大笑钉点漂移")
        let mDoneMid = BotCharacterMotion.mouthFrame(.done, 112.5, false)
        assert(approx(mDoneMid.open, 0.601) && approx(mDoneMid.wide, 1.2475), "DONE 包络中程漂移")
        // 静态钉点/体态端点（与桌面 botStaticPhaseMs/botPose 新表同值）
        assert(BotCharacterMotion.staticPhaseMs(.running) == 125
            && BotCharacterMotion.staticPhaseMs(.waiting) == 1000
            && BotCharacterMotion.staticPhaseMs(.error) == 500
            && BotCharacterMotion.staticPhaseMs(.done) == 225, "静态钉点漂移")
        assert(approx(BotCharacterMotion.breathScale(0), 1.0)
            && approx(BotCharacterMotion.breathScale(313), 1.035)
            && approx(BotCharacterMotion.breathScale(625), 0.98), "呼吸关键帧漂移")
        assert(approx(BotCharacterMotion.pose(neutral, .done, 225, false).scale, 1.16), "DONE 弹跳峰值漂移")
        assert(approx(BotCharacterMotion.pose(neutral, .running, 125, false).offsetY, -3.0), "RUNNING y-bob 峰值漂移")
        assert(approx(BotCharacterMotion.pose(neutral, .thinking, 0, false).rotation, 2.8), "THINKING 体倾漂移（rest −1.2+4.0）")
    }
    #endif
}

// MARK: - 眼神交互（批次4）：附加眼位 + 对焦眨眼（桌面 BotCharacter.kt 逐参数镜像）
//
// gaze = 附加眼位（100 逻辑单位，组件内恒 clamp 眼窗）；interactive=true 时叠加到相位眼位。
// 对焦眨眼：gaze 值跳变 >4 逻辑单位（欧氏距离，前值缺省 (0,0)）触发 90/110ms 包络一次，
// 冷却 250ms；motionEnabled=false 只 snap 眼位不播眨眼（A3「功能性反馈非装饰」同口径）。
// 触发源双端同观感：hover 注视（Catalyst/Mac）、输入聚焦下视、新消息一次性下瞥 400ms
// （下视值 = BotGaze.down = (0, +windowY) 窗底，2026-11-21 扩窗后随常量 3.5）；注视锚/
// 视野半径双端同表（GAZE_* 常量）。

/// 附加眼位（批次4；100×100 逻辑单位，渲染端 clamp 眼窗 ±4.5/±3.5——2026-11-21 扩窗）。
struct BotGaze: Equatable {
    let dx: CGFloat
    let dy: CGFloat

    /// 下视窗底（输入聚焦/新消息下瞥共用值；引用 BotCharacterMotion.windowY 与桌面
    /// BotCharacter.kt BotGaze.DOWN=BOT_EYE_WINDOW_Y 同口径——扩窗后字面量 2.5 到不了窗底，
    /// 会与桌面「聚焦下视/新消息下瞥到窗底」观感分叉）。
    static let down = BotGaze(dx: 0, dy: BotCharacterMotion.windowY)
}

/// 设置页档预览的中性种子（批次5）：botSeedFor("") = h=0 → 眼形圆角竖方/静息姿态 0/眨音
/// 2600ms——档（12 值造型+色）经 faceFactorsOverride/paletteIndexOverride 注入，seed 只余
/// 档外身份维度（眼形/姿态/眨音），设置页取中性值不携会话身份。
let botSettingsPreviewSeed: BotSeed = botSeedFor("")

/// 指针位置 → 注视 gaze（批次4；锚=悬停容器内角色中心近似 (16,14)、视野半径 120、窗幅
/// 4.5×3.5——2026-11-21 随眼窗扩容；常量私有在 BotCharacterMotion，双端同表）。[location] 相对挂 onContinuousHover
/// 的容器坐标（桌面 onPointerEvent(Move) 同参镜像）。
func botGazeToward(_ location: CGPoint) -> BotGaze {
    let nx = max(-1, min(1, (location.x - BotCharacterMotion.gazeAnchorX) / BotCharacterMotion.gazeRadius))
    let ny = max(-1, min(1, (location.y - BotCharacterMotion.gazeAnchorY) / BotCharacterMotion.gazeRadius))
    return BotGaze(dx: BotCharacterMotion.gazeAmpX * nx, dy: BotCharacterMotion.gazeAmpY * ny)
}

// MARK: - 黏土 blob 几何底座（原 RobotAvatar.swift 迁入——批次5 旧渲染器整文件退役，
// 设置页 faceKey/12 值 blob 渲染仍消费；与 Kotlin AvatarProfile.kt 逐公式镜像，勿单端改）

/// 黏土脸辅助：12 控制点极坐标、face 串解析/编码。规则与 Kotlin 一字对齐——
/// split(",") 得 12 段 → 逐段 Double 解析 → 逐值 clamp [0.70,1.10]；
/// 段数 ≠ 12 或任一段非数（含 nan/inf，逐字「非数」）→ 整体回退正圆（12×1.0）。
enum ClayFace {
    /// 控制点数 / 基准半径（100×100 坐标系，圆心 (50,50)）/ 半径系数上下限。
    static let pointCount = 12
    static let r = 44.0
    static let minValue = 0.70
    static let maxValue = 1.10

    /// 正圆系数表（全 1.0）：face 串为空/解析失败时的唯一兜底。
    static let defaultFactors: [Double] = Array(repeating: 1.0, count: pointCount)

    private static func clamp(_ f: Double) -> Double { min(max(f, minValue), maxValue) }

    /// 控制点坐标（face-sculpt §2）：x = 50 + 44·f·sin(θ_i)，y = 50 − 44·f·cos(θ_i)
    /// （θ_i = i×30°，0 号在 12 点方向、顺时针；与 Kotlin facePoint 逐字一致）。
    static func point(_ i: Int, _ f: Double) -> CGPoint {
        let theta = Double(i % pointCount) * (2.0 * Double.pi / Double(pointCount))
        let radius = r * clamp(f)
        return CGPoint(x: 50.0 + radius * sin(theta), y: 50.0 - radius * cos(theta))
    }

    /// 解析黏土脸系数串：12 段逗号分隔小数，逐值 clamp 到 [0.70,1.10]。
    /// 段数 ≠ 12 或任一段解析失败（trim 后仍非有限数）→ nil（调用方按正圆处理）。
    static func parse(_ raw: String) -> [Double]? {
        let parts = raw.split(separator: ",", omittingEmptySubsequences: false)
        guard parts.count == pointCount else { return nil }
        var out = [Double]()
        out.reserveCapacity(pointCount)
        for part in parts {
            guard let v = Double(part.trimmingCharacters(in: .whitespaces)), v.isFinite else { return nil }
            out.append(clamp(v))
        }
        return out
    }

    /// 编码黏土脸系数串：恒 12 段、各两位小数（如 `1.00,0.92,…`），与 Kotlin encodeFaceFactors
    /// 往返一致（先 clamp 后 ×100 取整限 [70,110]，两位整数补零拼小数，不用 %-format 防本地化污染）。
    static func encode(_ fs: [Double]) -> String {
        let cents = (0..<pointCount).map { i -> Int in
            let v = clamp(i < fs.count ? fs[i] : 1.0)
            return min(max(Int((v * 100).rounded()), 70), 110)
        }
        return cents.map { c -> String in
            let frac = String(c < 100 ? c : c - 100)
            let padded = frac.count < 2 ? "0" + frac : frac
            return c < 100 ? "0.\(padded)" : "1.\(padded)"
        }.joined(separator: ",")
    }
}

/// 12 控制点插值载体（VectorArithmetic）：SwiftUI 对 animatableData 逐帧线性插值。
struct FaceFactorsVector: VectorArithmetic {
    var values: [Double]

    static var zero: FaceFactorsVector {
        FaceFactorsVector(values: Array(repeating: 0, count: ClayFace.pointCount))
    }

    var magnitudeSquared: Double { values.reduce(0) { $0 + $1 * $1 } }

    static func + (lhs: FaceFactorsVector, rhs: FaceFactorsVector) -> FaceFactorsVector {
        var out = lhs
        for i in 0..<out.values.count { out.values[i] += i < rhs.values.count ? rhs.values[i] : 0 }
        return out
    }

    static func - (lhs: FaceFactorsVector, rhs: FaceFactorsVector) -> FaceFactorsVector {
        var out = lhs
        for i in 0..<out.values.count { out.values[i] -= i < rhs.values.count ? rhs.values[i] : 0 }
        return out
    }

    mutating func scale(by rhs: Double) {
        values = values.map { $0 * rhs }
    }
}

/// 黏土 blob 形状：过 12 点闭合均匀 Catmull-Rom（标准张力 1/6），全 1.0 = 正圆。
struct ClayFaceShape: Shape {
    var factors: FaceFactorsVector

    var animatableData: FaceFactorsVector {
        get { factors }
        set { factors = newValue }
    }

    func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / 100
        let fs = factors.values
        let pts = (0..<ClayFace.pointCount).map {
            ClayFace.point($0, $0 < fs.count ? fs[$0] : 1.0)
        }
        var p = Path()
        p.move(to: pt(pts[0].x, pts[0].y, s))
        for i in 0..<ClayFace.pointCount {
            let p0 = pts[(i + ClayFace.pointCount - 1) % ClayFace.pointCount]
            let p1 = pts[i]
            let p2 = pts[(i + 1) % ClayFace.pointCount]
            let p3 = pts[(i + 2) % ClayFace.pointCount]
            p.addCurve(
                to: pt(p2.x, p2.y, s),
                control1: pt(p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6, s),
                control2: pt(p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6, s)
            )
        }
        p.closeSubpath()
        return p
    }
}

// MARK: - 固定常量色（face-sculpt §3 墨色二选一）

private let inkDark = Color(hex: 0x1F2329)
private let inkLight = Color(hex: 0xF5F7FA)

/// 眼墨色（face-sculpt §3，两端逐字一致）：R/G/B 各 round 到 0-255 整数，
/// 0.299R + 0.587G + 0.114B ≥ 153 → #1F2329（深墨），否则 → #F5F7FA（浅墨）。
func inkForFace(_ face: Color) -> Color {
    var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
    UIColor(face).getRed(&r, green: &g, blue: &b, alpha: &a)
    let R = Int((r * 255).rounded())
    let G = Int((g * 255).rounded())
    let B = Int((b * 255).rounded())
    return 0.299 * Double(R) + 0.587 * Double(G) + 0.114 * Double(B) >= 153 ? inkDark : inkLight
}

/// 100×100 逻辑坐标点 → 渲染坐标（逐点显式乘 s，不用 scale 变换）。
private func pt(_ x: CGFloat, _ y: CGFloat, _ s: CGFloat) -> CGPoint {
    CGPoint(x: x * s, y: y * s)
}

// MARK: - 六态相位函数（桌面 desktopApp BotCharacter.kt 逐公式镜像，双端同表勿单端改值）
//
// 全部为 (seed, state, t, compact) 的纯时间函数（t = ms since 态进入）；motionEnabled=false
// 钉逐态特征帧（staticPhaseMs）。体态三原语 rotate ±5°(+THINKING +4°)/scale 0.95–1.16/
// y-bob ±3.0（2026-11-21 幅度加大量：32/36pt 挂载档亚像素位移不可辨，主载荷换缩放/旋转/
// 嘴形）；<40pt compact：眼放大 13×18、幅度 ×1.6、眼窗恒 clamp ±4.5/±3.5（新幅度表全通道
// 极值 IDLE/THINKING/RUNNING/ERROR dx 4.48≤4.5、IDLE dy 3.2≤3.5、THINKING dy [−3.36,+1.76]
// ⊂±3.5——零钉边，改幅度必按全幅口径含 rest±0.8/圈心项重算）。

private enum BotCharacterMotion {
    static let compactThreshold: CGFloat = 40
    static let compactAmp: CGFloat = 1.6

    // 眼：全量 11×15（圆角=半宽）、compact 放大 13×18；中心 (39,45)/(61,45)；眼窗 ±4.5/±3.5
    //（2026-11-21 扩容，旧 ±3.0/±2.5 下 compact 新幅度半周期被 clamp 钉边）。
    static let eyeW: CGFloat = 11
    static let eyeH: CGFloat = 15
    static let eyeWCompact: CGFloat = 13
    static let eyeHCompact: CGFloat = 18
    static let eyeCxL: CGFloat = 39
    static let eyeCxR: CGFloat = 61
    static let eyeCy: CGFloat = 45
    static let windowX: CGFloat = 4.5
    static let windowY: CGFloat = 3.5

    // 开心弧（下半圆环）：全量 11×7 / compact 13×8，圆帽描边 3。
    static let arcW: CGFloat = 11
    static let arcH: CGFloat = 7
    static let arcWCompact: CGFloat = 13
    static let arcHCompact: CGFloat = 8
    static let arcStroke: CGFloat = 3

    // 嘴（嘴部轮 2026-11-21）：二次曲线唇线，中心 (50,67)。定稿口径全腔高制（勿混全高/半高）：
    // 全宽 16（compact 20）、全腔高 10（compact 13）、描边 3（3.5，仅闭/抿唇线）、曲度控制偏
    // ±5（±6，quad 中点实际位移半值）、DONE wide 极值 1.35（→compact 全宽 27）；开/抿分界
    // open<0.04 走圆帽唇线。与桌面 BOT_MOUTH_* 常量表逐字对锁勿单端改。
    static let mouthCx: CGFloat = 50
    static let mouthCy: CGFloat = 67
    static let mouthW: CGFloat = 16
    static let mouthWCompact: CGFloat = 20
    static let mouthCavityH: CGFloat = 10
    static let mouthCavityHCompact: CGFloat = 13
    static let mouthStroke: CGFloat = 3
    static let mouthStrokeCompact: CGFloat = 3.5
    static let mouthCurveCtrl: CGFloat = 5
    static let mouthCurveCtrlCompact: CGFloat = 6
    static let mouthWideMax: CGFloat = 1.35
    static let mouthOpenFillMin: CGFloat = 0.04
    static let mouthIdleOpenBase: CGFloat = 0.10
    static let mouthIdleOpenAmp: CGFloat = 0.12
    static let mouthIdleCurve: CGFloat = 0.55
    static let mouthThinkOpen: CGFloat = 0.45
    static let mouthThinkWide: CGFloat = 0.70
    static let mouthThinkDx: CGFloat = 1.5
    static let mouthRunOpenBase: CGFloat = 0.30
    static let mouthRunOpenAmp: CGFloat = 0.45
    static let mouthWaitCurve: CGFloat = -0.15
    static let mouthSighOpen: CGFloat = 0.35
    static let mouthSighCurve: CGFloat = -0.45
    static let mouthErrorCurve: CGFloat = -0.5
    static let mouthErrorTremble: CGFloat = 0.25
    static let mouthDoneOpen: CGFloat = 0.85
    static let mouthDoneCurve: CGFloat = 0.7
    static let mouthDoneWideAmp: CGFloat = 0.35

    // 静息姿态四档（poseIndex → 倾角° × 静息眼位 x；规格 −1.2°/0°/+1.2° × ∓0.8）。
    static let restTilt: [CGFloat] = [-1.2, 0, 1.2, -1.2]
    static let restEyeX: [CGFloat] = [-0.8, 0.8, -0.8, 0.8]

    static let squintScale: CGFloat = 0.45 // ERROR 眯眼定格 scaleY
    static let blinkCloseMs: CGFloat = 90
    static let blinkOpenMs: CGFloat = 110

    // 眼神交互常量（批次4；桌面 BotCharacter.kt 双端同表勿单端改值）：注视锚=角色中心在
    // 悬停容器内的近似点（角色行首 32/36pt，(16,14) 即眼心邻域）、视野半径 120（饱和后按
    // 窗幅比例取单位方向）；对焦眨眼阈值/冷却（包络复用 blinkCloseMs/blinkOpenMs）。
    // 窗幅 2026-11-21 随眼窗扩容同步 4.5×3.5（=windowX/windowY，hover 注视可达窗沿）。
    static let gazeAnchorX: CGFloat = 16
    static let gazeAnchorY: CGFloat = 14
    static let gazeRadius: CGFloat = 120
    static let gazeAmpX: CGFloat = 4.5
    static let gazeAmpY: CGFloat = 3.5
    static let focusBlinkThreshold: CGFloat = 4
    static let focusBlinkCooldownMs: Double = 250

    struct Pose {
        let scale: CGFloat
        let rotation: CGFloat
        /// y-bob（100×100 逻辑单位；调用点乘 size/100 折点）。
        let offsetY: CGFloat
    }

    struct EyeFrame {
        let dx: CGFloat
        let dy: CGFloat
        let scaleY: CGFloat
        let arc: Bool
    }

    /// 嘴帧（嘴部轮 2026-11-21；桌面 BotMouthFrame 同形镜像）：open ∈[0,1]（0=唇线、
    /// 1=全腔高）/ curve ∈[−1,1]（>0 笑=中点下垂、<0 撇）/ wide 宽度倍率 / dx 水平漂移。
    struct MouthFrame {
        let open: CGFloat
        let curve: CGFloat
        let wide: CGFloat
        let dx: CGFloat
    }

    static func pose(_ seed: BotSeed, _ state: BotState, _ t: CGFloat, _ compact: Bool) -> Pose {
        let amp: CGFloat = compact ? compactAmp : 1
        let rest = restTilt[seed.poseIndex.clamped(0...3)] * amp
        let tau: CGFloat = 2 * .pi
        switch state {
        case .idle:
            return Pose(scale: breathScale(t), rotation: rest, offsetY: 0)
        case .thinking:
            return Pose(scale: 1, rotation: rest + 4.0 * amp, offsetY: 0)
        case .running:
            return Pose(scale: 1, rotation: rest, offsetY: -3.0 * amp * sin(tau * t / 500))
        case .waiting:
            return Pose(
                scale: 1 + 0.08 * amp * (0.5 - 0.5 * cos(tau * t / 1600)),
                rotation: rest + 5.0 * amp * sin(tau * t / 1600),
                offsetY: 0,
            )
        case .error:
            return Pose(scale: 0.95, rotation: rest, offsetY: 0)
        case .done:
            return Pose(scale: 1 + 0.16 * amp * sin(.pi * min(t, 450) / 450), rotation: rest, offsetY: 0)
        }
    }

    static func eyeFrame(_ seed: BotSeed, _ state: BotState, _ t: CGFloat, _ compact: Bool, gaze: BotGaze?) -> EyeFrame {
        let amp: CGFloat = compact ? compactAmp : 1
        let tau: CGFloat = 2 * .pi
        var dx = restEyeX[seed.poseIndex.clamped(0...3)] * amp
        var dy: CGFloat = 0
        var scaleY: CGFloat = 1
        var arc = false
        switch state {
        case .idle:
            dx += 2.0 * amp * sin(tau * t / 7000)                    // lissajous ±2.0@7s（compact 4.48≤4.5）
            dy += 2.0 * amp * sin(2 * tau * t / 7000)                // 3.2≤3.5
            scaleY = 1 - blinkEnvelope(t.truncatingRemainder(dividingBy: CGFloat(max(seed.blinkTempoMs, 1))))
        case .thinking:
            dx += 2.0 * amp * cos(tau * t / 2400)                    // 绕圈 R=(2.0,1.6)、圈心 −0.5（dy 全程进窗）
            dy += -0.5 * amp + 1.6 * amp * sin(tau * t / 2400)
        case .running:
            dx += 2.0 * amp * sweepTriangle(t)                       // 横扫 ±2.0 三角波 380ms/程（compact 4.48）
        case .waiting:
            let u = t.truncatingRemainder(dividingBy: 2200)          // 双连眨：90/110 两组隔 160ms
            if u < 200 {
                scaleY = 1 - blinkEnvelope(u)
            } else if u >= 360 && u < 560 {
                scaleY = 1 - blinkEnvelope(u - 360)
            }
        case .error:
            scaleY = squintScale
            let u = t.truncatingRemainder(dividingBy: 2000)
            if u < 300 { dx += 2.0 * amp * sin(2 * tau * u / 300) }  // 横抖 ±2.0×3@300ms（compact 4.48）
        case .done:
            arc = true
        }
        if let gaze {
            dx += gaze.dx // 附加眼位恒过眼窗 clamp（±4.5/±3.5；AMP=窗，注视可达窗沿）
            dy += gaze.dy
        }
        return EyeFrame(
            dx: dx.clamped(-windowX...windowX),
            dy: dy.clamped(-windowY...windowY),
            scaleY: scaleY,
            arc: arc,
        )
    }

    /// 逐态嘴形（嘴部轮 2026-11-21；桌面 botMouthFrame 逐公式镜像勿单端改）：
    /// IDLE 呼吸微张笑 / THINKING 小 o+随眼圈 dx 漂移 / RUNNING 升余弦开合@380ms（振荡项落
    /// [0,1] 勿 base±amp 直加）/ WAITING 抿扁+周期叹气 u∈[560,900]@2200ms / ERROR 下弯扁嘴
    /// +抖窗内曲度颤 ±0.25 / DONE 450ms 包络大笑 open 0.85、wide 1.35 极值。
    static func mouthFrame(_ state: BotState, _ t: CGFloat, _ compact: Bool) -> MouthFrame {
        let amp: CGFloat = compact ? compactAmp : 1
        let tau: CGFloat = 2 * .pi
        var open: CGFloat = 0
        var curve: CGFloat = 0
        var wide: CGFloat = 1
        var dx: CGFloat = 0
        switch state {
        case .idle:
            open = mouthIdleOpenBase + mouthIdleOpenAmp * (0.5 - 0.5 * cos(tau * t / 1250))
            curve = mouthIdleCurve
        case .thinking:
            open = mouthThinkOpen
            wide = mouthThinkWide
            dx = mouthThinkDx * amp * cos(tau * t / 2400)
        case .running:
            open = mouthRunOpenBase + mouthRunOpenAmp * (0.5 - 0.5 * cos(tau * t / 380))
        case .waiting:
            curve = mouthWaitCurve
            let u = t.truncatingRemainder(dividingBy: 2200)
            if u >= 560 && u < 900 {
                let sigh = sin(.pi * (u - 560) / 340)                 // 叹气半正弦包络 ∈[0,1]
                open = mouthSighOpen * sigh
                curve = lerp(mouthWaitCurve, mouthSighCurve, sigh)
            }
        case .error:
            curve = mouthErrorCurve
            let u = t.truncatingRemainder(dividingBy: 2000)
            if u < 300 { curve += mouthErrorTremble * sin(2 * tau * u / 300) }
        case .done:
            let env = sin(.pi * min(t, 450) / 450)
            open = mouthDoneOpen * env
            wide = 1 + mouthDoneWideAmp * env
            curve = mouthDoneCurve
        }
        return MouthFrame(
            open: open.clamped(0...1),                                // 值域 coerce 兜底（sin(π) 浮点尾差）
            curve: curve.clamped(-1...1),
            wide: wide.clamped(0.5...mouthWideMax),
            dx: dx,
        )
    }

    /// IDLE 呼吸关键帧（1250ms：1.000@0 → 1.035@313 → 0.980@625 → 1.000@1000 → 持住，线性段；
    /// 加幅有度：IDLE 是六态对比基线）。
    static func breathScale(_ t: CGFloat) -> CGFloat {
        let p = t.truncatingRemainder(dividingBy: 1250)
        if p < 313 { return lerp(1.000, 1.035, p / 313) }
        if p < 625 { return lerp(1.035, 0.980, (p - 313) / 312) }
        if p < 1000 { return lerp(0.980, 1.000, (p - 625) / 375) }
        return 1.000
    }

    /// 眨眼包络（快闭前 90ms 线性 → 慢开后 110ms 线性；RobotAvatar.swift:327-334 同式）。
    static func blinkEnvelope(_ u: CGFloat) -> CGFloat {
        if u <= 0 { return 0 }
        if u < blinkCloseMs { return u / blinkCloseMs }
        if u < blinkCloseMs + blinkOpenMs { return max(0, min(1, 1 - (u - blinkCloseMs) / blinkOpenMs)) }
        return 0
    }

    /// 横扫三角波 ±1（760ms 全程 = 380ms/程）。
    static func sweepTriangle(_ t: CGFloat) -> CGFloat {
        let ph = (t / 760).truncatingRemainder(dividingBy: 1)
        let p = ph < 0 ? ph + 1 : ph
        return 1 - 4 * abs(p - 0.5)
    }

    /// motionEnabled=false 逐态特征帧钉点（ms）：峰值/上偏/眯眼可辨帧（嘴部同步出特征形，
    /// A6「off 档静止」= 嘴出特征形不出动画）。
    static func staticPhaseMs(_ state: BotState) -> Double {
        switch state {
        case .running: return 125 // y-bob 峰值 −3.0 + 嘴开合中程 open≈0.63
        case .waiting: return 1000 // 2026-11-21 800→1000：避开嘴部叹气窗 [560,900] 呈抿嘴 + 脉冲 1.068（compact 1.109）正视不眨
        case .error: return 500   // 横抖窗外、眯眼 + 下弯扁嘴（曲度 −0.5）定格
        case .done: return 225    // 弹跳峰值 1.16 + U 弧 + 开口大笑（开 0.85/宽 1.35）
        default: return 0         // IDLE/THINKING：静息微张笑 / 小 o + 绕圈起点（圈心 −0.5+右视）
        }
    }

    private static func lerp(_ a: CGFloat, _ b: CGFloat, _ x: CGFloat) -> CGFloat { a + (b - a) * x }
}

private extension Int {
    // Swift.min/max 显式限定：extension 成员内无限定名 min/max 会被类型同名静态属性（Int.min/max）遮蔽
    func clamped(_ range: ClosedRange<Int>) -> Int { Swift.min(Swift.max(self, range.lowerBound), range.upperBound) }
}

private extension CGFloat {
    func clamped(_ range: ClosedRange<CGFloat>) -> CGFloat { Swift.min(Swift.max(self, range.lowerBound), range.upperBound) }
}

// MARK: - 渲染器
//
// 单体圆身 + 一对大竖眼 + 一张嘴，无眉无四肢：身体 = 既有 12 点 Catmull-Rom blob 管线原样复用
// （ClayFaceShape，基半径 44，AvatarPresetCatalog[bodyIndex].faceFactors 十二值表，零新几何
// 分支）；眼 = 竖圆角矩形 11×15（圆角=半宽）@ (39,45)/(61,45)，墨色 inkForFace 对比墨，
// 眼窗平移恒 clamp ±4.5/±3.5（2026-11-21 扩窗后安全域复核有余量，见 BotCharacter.kt 头注释）；
// 嘴 = 二次曲线唇线 @ (50,67)（嘴部轮 2026-11-21，逐态嘴形与幅度表同相位，嘴色与眼同帧
// inkForFace 对比墨）。渲染尺寸档 28/32/36/44/96；size < 40 走 compact（沿用
// RobotAvatar.swift:267-268 分支机制）。设置页 44pt 预设缩略仍由调用方强制 motionEnabled=false
//（AvatarIdleMotion 常驻循环多实例纪律，批次5 落位）。
//
// 性能纪律：TimelineView(.animation(minimumInterval: 1/20, paused: !visible || !motionEnabled))
// （RobotAvatar.swift:262 机制）+ onAppear/onDisappear 门控——出屏/关档即停帧，零常驻循环；
// 体态施于组合层 transform（scale/rotate/offset，blob path 不随帧重建）；嘴部只加 1–2 段
// quad 小 path/唇线（成本同级于既有逐帧眼 roundedRect path，无逐帧 blob 重建）；[interactive]
// 为批次4 眼神交互面（注视/下瞥/对焦眨眼三路触发）。
struct BotCharacterView: View {
    let seed: BotSeed
    let state: BotState
    var size: CGFloat = 36
    var motionEnabled: Bool = true
    var interactive: Bool = false
    /// 眼神交互覆盖（批次4）：interactive=true 时叠加到相位眼位（恒 clamp 眼窗）；nil = 无交互。
    var gaze: BotGaze? = nil
    /// 档预览（批次5 设置页）：显式 12 值造型（seed bodyIndex 让位；nil = seed 域）。
    var faceFactorsOverride: [Double]? = nil
    /// 档预览（批次5 设置页）：显式色序号（AgentTintName 序；seed paletteIndex 让位）。
    var paletteIndexOverride: Int? = nil

    @Environment(\.inkColors) private var c
    @State private var enteredAt = Date()
    @State private var visible = false
    /// 对焦眨眼（批次4）：gaze 跳变 >4 单位触发一次 90/110ms 包络，冷却 250ms（off 档不播）。
    @State private var focusBlinkStart: Date?
    @State private var lastGazeValue: BotGaze?
    @State private var lastFocusBlinkAt = Date.distantPast

    var body: some View {
        _ = BotCharacterGoldLock.verified // 金标 DEBUG 断言（进程内一次）
        let compact = size < BotCharacterMotion.compactThreshold
        let paletteIndex = paletteIndexOverride ?? seed.paletteIndex
        let tint = AgentTintName.allCases[min(max(paletteIndex, 0), AgentTintName.allCases.count - 1)]
        let faceFill = agentTint(tint, isDark: c.isDark).strong
        let ink = inkForFace(faceFill)
        let factors = faceFactorsOverride
            ?? AvatarPresetCatalog.all[min(max(seed.bodyIndex, 0), AvatarPresetCatalog.all.count - 1)].faceFactors
        return TimelineView(.animation(minimumInterval: 1.0 / 20.0, paused: !visible || !motionEnabled)) { timeline in
            let t: CGFloat = motionEnabled
                ? max(0, CGFloat(timeline.date.timeIntervalSince(enteredAt)) * 1000)
                : CGFloat(BotCharacterMotion.staticPhaseMs(state))
            let pose = BotCharacterMotion.pose(seed, state, t, compact)
            // 对焦眨眼叠加（min 取更闭的包络；DONE U 弧不吃 scaleY 无碍）——IIFE 形式：
            // ViewBuilder 闭包体内不容 if-赋值语句
            let frame: BotCharacterMotion.EyeFrame = {
                let eye = BotCharacterMotion.eyeFrame(seed, state, t, compact, gaze: interactive ? gaze : nil)
                if motionEnabled, let blinkStart = focusBlinkStart {
                    let u = CGFloat(max(0, timeline.date.timeIntervalSince(blinkStart)) * 1000)
                    return BotCharacterMotion.EyeFrame(
                        dx: eye.dx,
                        dy: eye.dy,
                        scaleY: min(eye.scaleY, 1 - BotCharacterMotion.blinkEnvelope(u)),
                        arc: eye.arc,
                    )
                }
                return eye
            }()
            ZStack {
                ClayFaceShape(factors: FaceFactorsVector(values: factors))
                    .fill(faceFill)
                Canvas { ctx, canvasSize in
                    let s = min(canvasSize.width, canvasSize.height) / 100
                    Self.drawEyes(&ctx, seed: seed, frame: frame, s: s, ink: ink, compact: compact)
                    // 嘴与眼同一帧 ink（inkForFace 单点计算两处消费——深色 palette/暗色模式下
                    // 固定深墨不可见，对比墨纪律）；体态（呼吸/摇/弹）已施于组合层
                    Self.drawMouth(&ctx, frame: BotCharacterMotion.mouthFrame(state, t, compact), s: s, ink: ink, compact: compact)
                }
            }
            .scaleEffect(pose.scale)
            .rotationEffect(.degrees(pose.rotation))
            .offset(y: pose.offsetY * size / 100) // 100 逻辑单位折点； 施于组合层不重绘 path
        }
        .frame(width: size, height: size)
        .onAppear {
            visible = true
            enteredAt = Date()
        }
        .onDisappear { visible = false }
        .onChange(of: state) { _, _ in enteredAt = Date() }
        .onChange(of: gaze) { _, newValue in
            // 对焦眨眼：眼位跳变 >4 逻辑单位触发（前值缺省 (0,0)），冷却 250ms；off 档只 snap
            guard interactive, motionEnabled else { return }
            let current = newValue ?? BotGaze(dx: 0, dy: 0)
            let previous = lastGazeValue ?? BotGaze(dx: 0, dy: 0)
            lastGazeValue = newValue
            let jump = ((current.dx - previous.dx) * (current.dx - previous.dx)
                + (current.dy - previous.dy) * (current.dy - previous.dy)).squareRoot()
            let now = Date()
            guard jump > BotCharacterMotion.focusBlinkThreshold,
                  now.timeIntervalSince(lastFocusBlinkAt) * 1000 >= BotCharacterMotion.focusBlinkCooldownMs else { return }
            lastFocusBlinkAt = now
            focusBlinkStart = now
        }
    }

    // 眼对绘制：eyeIndex 0 圆角竖方（圆角=半宽）/ 1 椭圆 / 2 正圆；眨/眯压 scaleY 宽保持；
    // DONE 双 U 弧（下半椭圆环，圆帽描边 3）。
    private static func drawEyes(
        _ ctx: inout GraphicsContext,
        seed: BotSeed,
        frame: BotCharacterMotion.EyeFrame,
        s: CGFloat,
        ink: Color,
        compact: Bool,
    ) {
        let w = compact ? BotCharacterMotion.eyeWCompact : BotCharacterMotion.eyeW
        let hFull = compact ? BotCharacterMotion.eyeHCompact : BotCharacterMotion.eyeH
        for cx in [BotCharacterMotion.eyeCxL, BotCharacterMotion.eyeCxR] {
            let ecx = cx + frame.dx
            let ecy = BotCharacterMotion.eyeCy + frame.dy
            if frame.arc {
                let rw = (compact ? BotCharacterMotion.arcWCompact : BotCharacterMotion.arcW) / 2
                let rh = (compact ? BotCharacterMotion.arcHCompact : BotCharacterMotion.arcH) / 2
                var p = Path()
                p.addArc(center: .zero, radius: rw, startAngle: .degrees(0), endAngle: .degrees(180), clockwise: true)
                // 单位圆下半弧 → y 压 rh/rw 成半椭圆 → 乘 s → 平移眼心（scale 先于 translate）
                let transform = CGAffineTransform(translationX: ecx * s, y: ecy * s)
                    .scaledBy(x: s, y: s * rh / rw)
                ctx.stroke(
                    p.applying(transform),
                    with: .color(ink),
                    style: StrokeStyle(lineWidth: BotCharacterMotion.arcStroke * s, lineCap: .round),
                )
                continue
            }
            let h = hFull * frame.scaleY
            guard h > 0.01 else { continue } // 眨眼全闭不画（pEye 同纪律）
            let rect = CGRect(x: (ecx - w / 2) * s, y: (ecy - h / 2) * s, width: w * s, height: h * s)
            switch seed.eyeIndex {
            case 0:
                ctx.fill(Path(roundedRect: rect, cornerRadius: min(w, h) / 2 * s), with: .color(ink))
            case 2:
                let d = min(w, h)
                ctx.fill(
                    Path(ellipseIn: CGRect(x: (ecx - d / 2) * s, y: (ecy - d / 2) * s, width: d * s, height: d * s)),
                    with: .color(ink),
                )
            default:
                ctx.fill(Path(ellipseIn: rect), with: .color(ink))
            }
        }
    }

    // 嘴绘制（嘴部轮 2026-11-21；桌面 BotCharacter.kt drawMouth 逐公式镜像）：open < 0.04
    // 闭/抿嘴 = 圆帽二次唇线（控制点 y = 中心 + curve×曲度偏移，quad 中点实际位移半值）；
    // 否则张嘴 = 上下唇两条二次曲线围腔填充（上唇控制 −open×腔高、下唇 +open×腔高 → 腔中点
    // 全高 = open×全腔高）。唇线段数 ≤2、无逐帧 blob 重建；嘴色 = 与眼同帧对比墨（入参 ink，
    // 禁在此固定色）。
    private static func drawMouth(
        _ ctx: inout GraphicsContext,
        frame: BotCharacterMotion.MouthFrame,
        s: CGFloat,
        ink: Color,
        compact: Bool,
    ) {
        let halfW = (compact ? BotCharacterMotion.mouthWCompact : BotCharacterMotion.mouthW) * frame.wide / 2
        let ctrl = compact ? BotCharacterMotion.mouthCurveCtrlCompact : BotCharacterMotion.mouthCurveCtrl
        let cavity = compact ? BotCharacterMotion.mouthCavityHCompact : BotCharacterMotion.mouthCavityH
        let cx = BotCharacterMotion.mouthCx + frame.dx
        let left = CGPoint(x: (cx - halfW) * s, y: BotCharacterMotion.mouthCy * s)
        let right = CGPoint(x: (cx + halfW) * s, y: BotCharacterMotion.mouthCy * s)
        let midX = cx * s
        let ctrlY = BotCharacterMotion.mouthCy + frame.curve * ctrl
        if frame.open < BotCharacterMotion.mouthOpenFillMin {
            var lip = Path()
            lip.move(to: left)
            lip.addQuadCurve(to: right, control: CGPoint(x: midX, y: ctrlY * s))
            ctx.stroke(
                lip,
                with: .color(ink),
                style: StrokeStyle(
                    lineWidth: (compact ? BotCharacterMotion.mouthStrokeCompact : BotCharacterMotion.mouthStroke) * s,
                    lineCap: .round,
                ),
            )
        } else {
            var cavityPath = Path()
            cavityPath.move(to: left)
            cavityPath.addQuadCurve(to: right, control: CGPoint(x: midX, y: (ctrlY - frame.open * cavity) * s))
            cavityPath.addQuadCurve(to: left, control: CGPoint(x: midX, y: (ctrlY + frame.open * cavity) * s))
            cavityPath.closeSubpath()
            ctx.fill(cavityPath, with: .color(ink))
        }
    }
}
