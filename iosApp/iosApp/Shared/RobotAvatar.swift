import SwiftUI
import UIKit
import composeApp

// MARK: - AgentExpression（规格 avatar-spec-20261029 §2.1/§9）

/// 表情枚举：case 顺序与 Kotlin `AgentExpression` 逐字一致（跨端契约，勿重排）；
/// rawValue = Kotlin 枚举名（桥 `BridgeAgent.expression` 送来的就是它）。
/// Swift 端不做任何 hash/派生——默认表情由 Kotlin 侧解析后经桥送达，此处只做 rawValue 映射。
enum AgentExpression: String, CaseIterable {
    case calm = "CALM"
    case happy = "HAPPY"
    case excited = "EXCITED"
    case thinking = "THINKING"
    case confused = "CONFUSED"
    case sleepy = "SLEEPY"
    case sad = "SAD"
    case smug = "SMUG"
    case surprised = "SURPRISED"
    case love = "LOVE"

    /// 编辑网格中文名（规格 §4）。
    var zhLabel: String {
        switch self {
        case .calm: return "平静"
        case .happy: return "开心"
        case .excited: return "兴奋"
        case .thinking: return "思考"
        case .confused: return "困惑"
        case .sleepy: return "犯困"
        case .sad: return "难过"
        case .smug: return "得意"
        case .surprised: return "惊讶"
        case .love: return "喜爱"
        }
    }
}

// MARK: - 黏土脸几何/解析（face-sculpt §2/§4：与 Kotlin AvatarSpec.kt 逐公式镜像，Swift 侧只此一份）

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

// MARK: - 固定常量色（face-sculpt §3：墨色二选一 + 旧规格附加元素固定值，沿用不动）

private let inkDark = Color(hex: 0x1F2329)
private let inkLight = Color(hex: 0xF5F7FA)
private let avatarBlush = Color.white.opacity(0.20)
private let avatarDrop = Color(hex: 0x8FD3F4)
private let avatarHeart = Color(hex: 0xFF7BAC)

/// 眼/嘴/眉墨色（face-sculpt §3，两端逐字一致）：R/G/B 各 round 到 0-255 整数，
/// 0.299R + 0.587G + 0.114B ≥ 153 → #1F2329（深墨），否则 → #F5F7FA（浅墨）。
func inkForFace(_ face: Color) -> Color {
    var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
    UIColor(face).getRed(&r, green: &g, blue: &b, alpha: &a)
    let R = Int((r * 255).rounded())
    let G = Int((g * 255).rounded())
    let B = Int((b * 255).rounded())
    return 0.299 * Double(R) + 0.587 * Double(G) + 0.114 * Double(B) >= 153 ? inkDark : inkLight
}

// MARK: - 绘制基元（blob 渲染仍在用的三种）

/// 100×100 逻辑坐标点 → 渲染坐标（逐点显式乘 s，不用 scale 变换）。
private func pt(_ x: CGFloat, _ y: CGFloat, _ s: CGFloat) -> CGPoint {
    CGPoint(x: x * s, y: y * s)
}

private extension GraphicsContext {
    /// circle 填充。
    mutating func pCircle(cx: CGFloat, cy: CGFloat, r: CGFloat, s: CGFloat, _ color: Color) {
        fill(
            Path(ellipseIn: CGRect(x: (cx - r) * s, y: (cy - r) * s, width: 2 * r * s, height: 2 * r * s)),
            with: .color(color)
        )
    }

    /// 多边形填充（点列表 ≥ 3）。
    mutating func pPoly(_ points: [(CGFloat, CGFloat)], s: CGFloat, _ color: Color) {
        var p = Path()
        p.move(to: pt(points[0].0, points[0].1, s))
        for (x, y) in points.dropFirst() { p.addLine(to: pt(x, y, s)) }
        p.closeSubpath()
        fill(p, with: .color(color))
    }

    /// 圆帽圆角描边（w 为 100×100 空间的逻辑线宽，显式乘 s）。
    mutating func pStroke(w: CGFloat, s: CGFloat, _ color: Color, build: (inout Path) -> Void) {
        var p = Path()
        build(&p)
        stroke(p, with: .color(color), style: StrokeStyle(lineWidth: w * s, lineCap: .round, lineJoin: .round))
    }
}

// MARK: - 黏土 blob 头像（face-sculpt §1–§3）

/// 旧机器人的天线/天线球/双耳/圆角方形头框/暗色面罩全部移除；
/// 脸 = 一块 tint 纯色黏土 blob（faceFill = 该 tint 的 strong，无描边无外框）；
/// 眼、嘴、眉、腮红与各表情附加元素（汗滴/心/Z/放射线/思考泡）坐标沿用旧几何表一字不改。
/// 100×100 逻辑坐标（原点左上、y 向下）；s = 渲染尺寸/100，每个坐标与每条线宽显式乘 s。
/// z 序：脸 blob→腮红→眼→眉→嘴→附加。渲染尺寸 < 40 走 compact（§5.5 折算表不变：blob→眼→嘴）。
/// 渲染器不做任何 face 串解析——调用点一律显式传已解析的 [faceFactors]（空/缺省 = 全 1.0 正圆）。
struct RobotAvatar: View {
    let palette: AgentTintName
    let expression: AgentExpression
    var size: CGFloat = 42
    var faceFactors: [Double] = ClayFace.defaultFactors
    /// Continuous/entrance motion is opt-out for anchored editors (for example the
    /// face-sculpt handles, which are rendered by a sibling overlay and must stay
    /// registered to the blob outline).
    var motionEnabled: Bool = true
    @Environment(\.inkColors) private var c
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var hasAppeared = false

    var body: some View {
        let faceFill = agentTint(palette, isDark: c.isDark).strong
        return ZStack {
            Canvas { ctx, canvasSize in
                let s = min(canvasSize.width, canvasSize.height) / 100
                let ink = inkForFace(faceFill)
                if size < 40 {
                    drawCompact(&ctx, expression: expression, faceFill: faceFill, ink: ink, s: s)
                } else {
                    drawFull(&ctx, expression: expression, faceFill: faceFill, ink: ink, s: s)
                }
            }
            // Canvas does not interpolate enum-driven drawing changes. Giving each
            // expression its own identity lets the eyes and mouth cross-fade into
            // their new geometry instead of snapping or only scaling the whole face.
            .id(expression.rawValue)
            .transition(
                .asymmetric(
                    insertion: .opacity
                        .combined(with: .offset(y: 3))
                        .combined(with: .scale(scale: 0.82)),
                    removal: .opacity
                        .combined(with: .offset(y: -3))
                        .combined(with: .scale(scale: 1.08))
                )
            )
        }
        .frame(width: size, height: size)
        .animation(motionEnabled && !reduceMotion ? .easeInOut(duration: 0.18) : nil,
                   value: expression.rawValue)
        // Keep the entrance and idle motion outside Canvas. The face geometry is
        // rendered once per state; only the composited view is transformed while
        // it breathes, so streaming message updates do not trigger a redraw loop.
        .opacity(!motionEnabled || reduceMotion || hasAppeared ? 1 : 0)
        .scaleEffect(!motionEnabled || reduceMotion || hasAppeared ? 1 : 0.86)
        .onAppear {
            guard !hasAppeared else { return }
            if !motionEnabled || reduceMotion {
                hasAppeared = true
            } else {
                withAnimation(.spring(response: 0.42, dampingFraction: 0.82)) {
                    hasAppeared = true
                }
            }
        }
        .modifier(AvatarIdleMotion(enabled: motionEnabled && !reduceMotion))
    }

    // ---------- blob（face-sculpt §2：过 12 点的闭合均匀 Catmull-Rom，标准张力 1/6） ----------

    /// 对每段 P_i → P_{i+1}（索引 mod 12，前邻 P_{i−1}、后邻 P_{i+2}）逐段 addCurve：
    /// cp1 = P_i + (P_{i+1} − P_{i−1}) / 6；cp2 = P_{i+1} − (P_{i+2} − P_i) / 6。
    /// 相邻段在共享点处的出/入切向共线且等长 → 整条 C¹ 连续，任何系数组合下无折线段/尖角；
    /// 所有因子相等时为正圆（半径 44·f）。
    private func faceBlobPath(_ factors: [Double], _ s: CGFloat) -> Path {
        let pts = (0..<ClayFace.pointCount).map {
            ClayFace.point($0, $0 < factors.count ? factors[$0] : 1.0)
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

    // ---------- 骨架（blob 脸 + 腮红；外框/面罩/天线/双耳按 face-sculpt §1 移除） ----------

    private func drawFace(_ ctx: inout GraphicsContext, faceFactors: [Double], faceFill: Color, s: CGFloat) {
        ctx.fill(faceBlobPath(faceFactors, s), with: .color(faceFill))
        // 腮红（白 α0.20，坐标沿用）
        ctx.pCircle(cx: 30.5, cy: 66, r: 4.5, s: s, avatarBlush)
        ctx.pCircle(cx: 69.5, cy: 66, r: 4.5, s: s, avatarBlush)
    }

    // ---------- 完整版表情（五官坐标沿用旧几何表，一字不改；眼→眉→嘴→附加 z 序） ----------

    private func drawFull(_ ctx: inout GraphicsContext, expression: AgentExpression, faceFill: Color, ink: Color, s: CGFloat) {
        drawFace(&ctx, faceFactors: faceFactors, faceFill: faceFill, s: s)
        switch expression {
        case .calm:
            // 眼
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, ink)
            ctx.pCircle(cx: 64, cy: 48, r: 5, s: s, ink)
            // 嘴：平横线
            ctx.pStroke(w: 4, s: s, ink) { p in
                p.move(to: pt(43, 66, s)); p.addLine(to: pt(57, 66, s))
            }

        case .happy:
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, ink)
            ctx.pCircle(cx: 64, cy: 48, r: 5, s: s, ink)
            // 嘴：上弯笑弧（二次贝塞尔）
            ctx.pStroke(w: 4, s: s, ink) { p in
                p.move(to: pt(39, 63, s)); p.addQuadCurve(to: pt(61, 63, s), control: pt(50, 74, s))
            }

        case .excited:
            ctx.pCircle(cx: 36, cy: 48, r: 5.5, s: s, ink)
            ctx.pCircle(cx: 64, cy: 48, r: 5.5, s: s, ink)
            ctx.pStroke(w: 4, s: s, ink) { p in
                p.move(to: pt(39, 62, s)); p.addQuadCurve(to: pt(61, 62, s), control: pt(50, 76, s))
            }
            // 附加：头顶两侧放射线（色值沿用旧几何表：faceFill）
            ctx.pStroke(w: 3, s: s, faceFill) { p in
                p.move(to: pt(18, 14, s)); p.addLine(to: pt(24, 19, s))
            }
            ctx.pStroke(w: 3, s: s, faceFill) { p in
                p.move(to: pt(82, 14, s)); p.addLine(to: pt(76, 19, s))
            }

        case .thinking:
            // 眼：错位上瞟
            ctx.pCircle(cx: 37, cy: 45, r: 5, s: s, ink)
            ctx.pCircle(cx: 63, cy: 47, r: 5, s: s, ink)
            ctx.pStroke(w: 4, s: s, ink) { p in
                p.move(to: pt(48, 67, s)); p.addLine(to: pt(58, 65, s))
            }
            // 附加：思考泡泡点 ×2（渐大，faceFill，漂浮在头部轮廓外）
            ctx.pCircle(cx: 72, cy: 18, r: 2.5, s: s, faceFill)
            ctx.pCircle(cx: 79, cy: 11, r: 3.5, s: s, faceFill)

        case .confused:
            // 眼：一大一小
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, ink)
            ctx.pCircle(cx: 64, cy: 49, r: 4, s: s, ink)
            // 嘴：S 形波浪线（三次贝塞尔）
            ctx.pStroke(w: 4, s: s, ink) { p in
                p.move(to: pt(42, 66, s))
                p.addCurve(to: pt(58, 66, s), control1: pt(47, 70, s), control2: pt(53, 62, s))
            }
            // 附加：汗滴（DROP：圆 + 三角拼合，固定色 #8FD3F4）
            ctx.pCircle(cx: 80, cy: 31, r: 4, s: s, avatarDrop)
            ctx.pPoly([(80, 22), (76.2, 29.5), (83.8, 29.5)], s: s, avatarDrop)

        case .sleepy:
            // 眼：闭眼横线
            ctx.pStroke(w: 4, s: s, ink) { p in
                p.move(to: pt(31, 48, s)); p.addLine(to: pt(41, 48, s))
            }
            ctx.pStroke(w: 4, s: s, ink) { p in
                p.move(to: pt(59, 48, s)); p.addLine(to: pt(69, 48, s))
            }
            // 嘴：小圆嘴
            ctx.pCircle(cx: 50, cy: 67, r: 3, s: s, ink)
            // 附加：Z 字 ×2（faceFill，圆帽折线）
            ctx.pStroke(w: 3, s: s, faceFill) { p in
                p.move(to: pt(66, 18, s)); p.addLine(to: pt(76, 18, s))
                p.addLine(to: pt(66, 28, s)); p.addLine(to: pt(76, 28, s))
            }
            ctx.pStroke(w: 2.5, s: s, faceFill) { p in
                p.move(to: pt(80, 32, s)); p.addLine(to: pt(86, 32, s))
                p.addLine(to: pt(80, 38, s)); p.addLine(to: pt(86, 38, s))
            }

        case .sad:
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, ink)
            ctx.pCircle(cx: 64, cy: 48, r: 5, s: s, ink)
            // 眉：内高外低
            ctx.pStroke(w: 3, s: s, ink) { p in
                p.move(to: pt(30, 39, s)); p.addLine(to: pt(40, 43, s))
            }
            ctx.pStroke(w: 3, s: s, ink) { p in
                p.move(to: pt(70, 39, s)); p.addLine(to: pt(60, 43, s))
            }
            // 嘴：下弯哭弧
            ctx.pStroke(w: 4, s: s, ink) { p in
                p.move(to: pt(41, 70, s)); p.addQuadCurve(to: pt(59, 70, s), control: pt(50, 61, s))
            }
            // 附加：右眼下泪滴（DROP，固定色 #8FD3F4）
            ctx.pCircle(cx: 66, cy: 60, r: 3, s: s, avatarDrop)
            ctx.pPoly([(66, 52), (63.2, 57.5), (68.8, 57.5)], s: s, avatarDrop)

        case .smug:
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, ink)
            ctx.pCircle(cx: 63, cy: 47, r: 5, s: s, ink)
            // 眉：单边挑眉
            ctx.pStroke(w: 3, s: s, ink) { p in
                p.move(to: pt(57, 36, s)); p.addLine(to: pt(69, 37, s))
            }
            // 嘴：不对称歪嘴（三次贝塞尔）
            ctx.pStroke(w: 4, s: s, ink) { p in
                p.move(to: pt(42, 67, s))
                p.addCurve(to: pt(59, 63, s), control1: pt(48, 71, s), control2: pt(54, 63, s))
            }

        case .surprised:
            ctx.pCircle(cx: 36, cy: 47, r: 6, s: s, ink)
            ctx.pCircle(cx: 64, cy: 47, r: 6, s: s, ink)
            // 眉：上挑
            ctx.pStroke(w: 3, s: s, ink) { p in
                p.move(to: pt(31, 36, s)); p.addLine(to: pt(41, 34, s))
            }
            ctx.pStroke(w: 3, s: s, ink) { p in
                p.move(to: pt(59, 34, s)); p.addLine(to: pt(69, 36, s))
            }
            // 嘴：大圆嘴
            ctx.pCircle(cx: 50, cy: 67, r: 6, s: s, ink)

        case .love:
            // 眼：两颗心（HEART：双圆 + 三角拼合，固定色 #FF7BAC）
            ctx.pCircle(cx: 32.5, cy: 45.5, r: 4, s: s, avatarHeart)
            ctx.pCircle(cx: 39.5, cy: 45.5, r: 4, s: s, avatarHeart)
            ctx.pPoly([(28.8, 47.5), (43.2, 47.5), (36, 55)], s: s, avatarHeart)
            ctx.pCircle(cx: 60.5, cy: 45.5, r: 4, s: s, avatarHeart)
            ctx.pCircle(cx: 67.5, cy: 45.5, r: 4, s: s, avatarHeart)
            ctx.pPoly([(56.8, 47.5), (71.2, 47.5), (64, 55)], s: s, avatarHeart)
            // 嘴：笑弧
            ctx.pStroke(w: 4, s: s, ink) { p in
                p.move(to: pt(42, 65, s)); p.addQuadCurve(to: pt(58, 65, s), control: pt(50, 72, s))
            }
            // 附加：头顶右上漂浮小爱心（HEART，整组抬高避开头部轮廓）
            ctx.pCircle(cx: 75, cy: 18, r: 2, s: s, avatarHeart)
            ctx.pCircle(cx: 78, cy: 18, r: 2, s: s, avatarHeart)
            ctx.pPoly([(73.4, 19.2), (79.6, 19.2), (76.5, 23.5)], s: s, avatarHeart)
        }
    }

    // ---------- compact（size < 40：只画 blob→眼→嘴，折算表沿用 §5.5） ----------

    private func drawCompact(_ ctx: inout GraphicsContext, expression: AgentExpression, faceFill: Color, ink: Color, s: CGFloat) {
        ctx.fill(faceBlobPath(faceFactors, s), with: .color(faceFill))
        switch expression {
        // 眼嘴折算表（§5.5）：省掉腮红、眉、附加元素
        case .sleepy:
            ctx.pStroke(w: 4, s: s, ink) { p in
                p.move(to: pt(31, 48, s)); p.addLine(to: pt(41, 48, s))
            }
            ctx.pStroke(w: 4, s: s, ink) { p in
                p.move(to: pt(59, 48, s)); p.addLine(to: pt(69, 48, s))
            }
            ctx.pCircle(cx: 50, cy: 67, r: 3, s: s, ink)

        case .surprised:
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, ink)
            ctx.pCircle(cx: 64, cy: 48, r: 5, s: s, ink)
            ctx.pCircle(cx: 50, cy: 67, r: 4, s: s, ink)

        case .thinking, .confused:
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, ink)
            ctx.pCircle(cx: 64, cy: 48, r: 5, s: s, ink)
            ctx.pStroke(w: 4, s: s, ink) { p in
                p.move(to: pt(43, 66, s)); p.addLine(to: pt(57, 66, s))
            }

        case .sad:
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, ink)
            ctx.pCircle(cx: 64, cy: 48, r: 5, s: s, ink)
            ctx.pStroke(w: 4, s: s, ink) { p in
                p.move(to: pt(41, 70, s)); p.addQuadCurve(to: pt(59, 70, s), control: pt(50, 61, s))
            }

        // CALM / HAPPY / EXCITED / SMUG / LOVE：圆点眼 + HAPPY 笑弧
        default:
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, ink)
            ctx.pCircle(cx: 64, cy: 48, r: 5, s: s, ink)
            ctx.pStroke(w: 4, s: s, ink) { p in
                p.move(to: pt(39, 63, s)); p.addQuadCurve(to: pt(61, 63, s), control: pt(50, 74, s))
            }
        }
    }
}

private struct AvatarIdlePhase: Equatable {
    let scale: CGFloat
    let offsetY: CGFloat
    let rotation: Double
}

/// Subtle periodic micro-motions for agent avatars. Keeping this as view transforms
/// avoids rebuilding the Canvas path on each phase while giving the face a little life:
/// settle → breathe → nod → settle.
private struct AvatarIdleMotion: ViewModifier {
    let enabled: Bool
    private static let phases = [
        AvatarIdlePhase(scale: 1.0, offsetY: 0, rotation: 0),
        AvatarIdlePhase(scale: 1.018, offsetY: -0.7, rotation: 0.6),
        AvatarIdlePhase(scale: 1.006, offsetY: 0.5, rotation: -0.45),
        AvatarIdlePhase(scale: 1.0, offsetY: 0, rotation: 0),
    ]

    @ViewBuilder
    func body(content: Content) -> some View {
        if enabled {
            content.phaseAnimator(Self.phases) { view, phase in
                view
                    .scaleEffect(phase.scale)
                    .offset(y: phase.offsetY)
                    .rotationEffect(.degrees(phase.rotation))
            } animation: { _ in
                .easeInOut(duration: 1.25)
            }
        } else {
            content
        }
    }
}
