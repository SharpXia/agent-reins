import SwiftUI
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

// MARK: - 固定常量色（规格 §5.4：不随 agent/明暗变化，不进桥、不进存储）

private let avatarVisor = Color(hex: 0x1F2329)
private let avatarEye = Color(hex: 0xF5F7FA)
private let avatarBlush = Color.white.opacity(0.20)
private let avatarDrop = Color(hex: 0x8FD3F4)
private let avatarHeart = Color(hex: 0xFF7BAC)

// MARK: - 绘制基元（规格 §5.1，只允许这五种）

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

    /// 统一圆角 roundRect 填充（禁止逐角）。
    mutating func pRoundRect(x: CGFloat, y: CGFloat, w: CGFloat, h: CGFloat, rx: CGFloat, s: CGFloat, _ color: Color) {
        fill(
            Path(roundedRect: CGRect(x: x * s, y: y * s, width: w * s, height: h * s), cornerRadius: rx * s),
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

// MARK: - Grokbot 机器人头像（规格 §5）

/// 机器人头像：100×100 逻辑坐标（原点左上、y 向下）；s = 渲染尺寸/100，每个坐标与每条线宽显式乘 s。
/// z 序固定（§5.2：天线杆→天线球→双耳→头→面罩→腮红→眼→眉→嘴→附加）。
/// 渲染尺寸 < 40 走 compact（§5.5，只画 头→面罩→眼→嘴）；无 forceFull 类逃生口参数。
/// headFill = 该 tint 的 strong（明暗双表复用 agentTint）。
struct RobotAvatar: View {
    let palette: AgentTintName
    let expression: AgentExpression
    var size: CGFloat = 42
    @Environment(\.inkColors) private var c

    var body: some View {
        let headFill = agentTint(palette, isDark: c.isDark).strong
        return Canvas { ctx, canvasSize in
            let s = min(canvasSize.width, canvasSize.height) / 100
            if size < 40 {
                drawCompact(&ctx, expression: expression, headFill: headFill, s: s)
            } else {
                drawFull(&ctx, expression: expression, headFill: headFill, s: s)
            }
        }
        .frame(width: size, height: size)
    }

    // ---------- 骨架（规格 §5.2，所有表情共享） ----------

    private func drawSkeleton(_ ctx: inout GraphicsContext, headFill: Color, s: CGFloat) {
        // 1 天线杆
        ctx.pStroke(w: 4, s: s, headFill) { p in
            p.move(to: pt(50, 8, s)); p.addLine(to: pt(50, 24, s))
        }
        // 2 天线球
        ctx.pCircle(cx: 50, cy: 7, r: 4.5, s: s, headFill)
        // 3 左耳
        ctx.pRoundRect(x: 4, y: 42, w: 10, h: 16, rx: 5, s: s, headFill)
        // 4 右耳
        ctx.pRoundRect(x: 86, y: 42, w: 10, h: 16, rx: 5, s: s, headFill)
        // 5 头
        ctx.pRoundRect(x: 12, y: 22, w: 76, h: 64, rx: 22, s: s, headFill)
        // 6 面罩
        ctx.pRoundRect(x: 22, y: 34, w: 56, h: 42, rx: 13, s: s, avatarVisor)
        // 7 腮红
        ctx.pCircle(cx: 30.5, cy: 66, r: 4.5, s: s, avatarBlush)
        ctx.pCircle(cx: 69.5, cy: 66, r: 4.5, s: s, avatarBlush)
    }

    // ---------- 完整版表情（规格 §5.3，逐条对齐；眼→眉→嘴→附加 z 序） ----------

    private func drawFull(_ ctx: inout GraphicsContext, expression: AgentExpression, headFill: Color, s: CGFloat) {
        drawSkeleton(&ctx, headFill: headFill, s: s)
        switch expression {
        case .calm:
            // 眼
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, avatarEye)
            ctx.pCircle(cx: 64, cy: 48, r: 5, s: s, avatarEye)
            // 嘴：平横线
            ctx.pStroke(w: 4, s: s, avatarEye) { p in
                p.move(to: pt(43, 66, s)); p.addLine(to: pt(57, 66, s))
            }

        case .happy:
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, avatarEye)
            ctx.pCircle(cx: 64, cy: 48, r: 5, s: s, avatarEye)
            // 嘴：上弯笑弧（二次贝塞尔）
            ctx.pStroke(w: 4, s: s, avatarEye) { p in
                p.move(to: pt(39, 63, s)); p.addQuadCurve(to: pt(61, 63, s), control: pt(50, 74, s))
            }

        case .excited:
            ctx.pCircle(cx: 36, cy: 48, r: 5.5, s: s, avatarEye)
            ctx.pCircle(cx: 64, cy: 48, r: 5.5, s: s, avatarEye)
            ctx.pStroke(w: 4, s: s, avatarEye) { p in
                p.move(to: pt(39, 62, s)); p.addQuadCurve(to: pt(61, 62, s), control: pt(50, 76, s))
            }
            // 附加：头顶两侧放射线（headFill）
            ctx.pStroke(w: 3, s: s, headFill) { p in
                p.move(to: pt(18, 14, s)); p.addLine(to: pt(24, 19, s))
            }
            ctx.pStroke(w: 3, s: s, headFill) { p in
                p.move(to: pt(82, 14, s)); p.addLine(to: pt(76, 19, s))
            }

        case .thinking:
            // 眼：错位上瞟
            ctx.pCircle(cx: 37, cy: 45, r: 5, s: s, avatarEye)
            ctx.pCircle(cx: 63, cy: 47, r: 5, s: s, avatarEye)
            ctx.pStroke(w: 4, s: s, avatarEye) { p in
                p.move(to: pt(48, 67, s)); p.addLine(to: pt(58, 65, s))
            }
            // 附加：思考泡泡点 ×2（渐大，headFill，漂浮在头部轮廓外）
            ctx.pCircle(cx: 72, cy: 18, r: 2.5, s: s, headFill)
            ctx.pCircle(cx: 79, cy: 11, r: 3.5, s: s, headFill)

        case .confused:
            // 眼：一大一小
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, avatarEye)
            ctx.pCircle(cx: 64, cy: 49, r: 4, s: s, avatarEye)
            // 嘴：S 形波浪线（三次贝塞尔）
            ctx.pStroke(w: 4, s: s, avatarEye) { p in
                p.move(to: pt(42, 66, s))
                p.addCurve(to: pt(58, 66, s), control1: pt(47, 70, s), control2: pt(53, 62, s))
            }
            // 附加：汗滴（DROP：圆 + 三角拼合）
            ctx.pCircle(cx: 80, cy: 31, r: 4, s: s, avatarDrop)
            ctx.pPoly([(80, 22), (76.2, 29.5), (83.8, 29.5)], s: s, avatarDrop)

        case .sleepy:
            // 眼：闭眼横线
            ctx.pStroke(w: 4, s: s, avatarEye) { p in
                p.move(to: pt(31, 48, s)); p.addLine(to: pt(41, 48, s))
            }
            ctx.pStroke(w: 4, s: s, avatarEye) { p in
                p.move(to: pt(59, 48, s)); p.addLine(to: pt(69, 48, s))
            }
            // 嘴：小圆嘴
            ctx.pCircle(cx: 50, cy: 67, r: 3, s: s, avatarEye)
            // 附加：Z 字 ×2（headFill，圆帽折线）
            ctx.pStroke(w: 3, s: s, headFill) { p in
                p.move(to: pt(66, 18, s)); p.addLine(to: pt(76, 18, s))
                p.addLine(to: pt(66, 28, s)); p.addLine(to: pt(76, 28, s))
            }
            ctx.pStroke(w: 2.5, s: s, headFill) { p in
                p.move(to: pt(80, 32, s)); p.addLine(to: pt(86, 32, s))
                p.addLine(to: pt(80, 38, s)); p.addLine(to: pt(86, 38, s))
            }

        case .sad:
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, avatarEye)
            ctx.pCircle(cx: 64, cy: 48, r: 5, s: s, avatarEye)
            // 眉：内高外低（headFill）
            ctx.pStroke(w: 3, s: s, headFill) { p in
                p.move(to: pt(30, 39, s)); p.addLine(to: pt(40, 43, s))
            }
            ctx.pStroke(w: 3, s: s, headFill) { p in
                p.move(to: pt(70, 39, s)); p.addLine(to: pt(60, 43, s))
            }
            // 嘴：下弯哭弧
            ctx.pStroke(w: 4, s: s, avatarEye) { p in
                p.move(to: pt(41, 70, s)); p.addQuadCurve(to: pt(59, 70, s), control: pt(50, 61, s))
            }
            // 附加：右眼下泪滴（DROP）
            ctx.pCircle(cx: 66, cy: 60, r: 3, s: s, avatarDrop)
            ctx.pPoly([(66, 52), (63.2, 57.5), (68.8, 57.5)], s: s, avatarDrop)

        case .smug:
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, avatarEye)
            ctx.pCircle(cx: 63, cy: 47, r: 5, s: s, avatarEye)
            // 眉：单边挑眉（headFill）
            ctx.pStroke(w: 3, s: s, headFill) { p in
                p.move(to: pt(57, 36, s)); p.addLine(to: pt(69, 37, s))
            }
            // 嘴：不对称歪嘴（三次贝塞尔）
            ctx.pStroke(w: 4, s: s, avatarEye) { p in
                p.move(to: pt(42, 67, s))
                p.addCurve(to: pt(59, 63, s), control1: pt(48, 71, s), control2: pt(54, 63, s))
            }

        case .surprised:
            ctx.pCircle(cx: 36, cy: 47, r: 6, s: s, avatarEye)
            ctx.pCircle(cx: 64, cy: 47, r: 6, s: s, avatarEye)
            // 眉：上挑（headFill）
            ctx.pStroke(w: 3, s: s, headFill) { p in
                p.move(to: pt(31, 36, s)); p.addLine(to: pt(41, 34, s))
            }
            ctx.pStroke(w: 3, s: s, headFill) { p in
                p.move(to: pt(59, 34, s)); p.addLine(to: pt(69, 36, s))
            }
            // 嘴：大圆嘴
            ctx.pCircle(cx: 50, cy: 67, r: 6, s: s, avatarEye)

        case .love:
            // 眼：两颗心（HEART：双圆 + 三角拼合）
            ctx.pCircle(cx: 32.5, cy: 45.5, r: 4, s: s, avatarHeart)
            ctx.pCircle(cx: 39.5, cy: 45.5, r: 4, s: s, avatarHeart)
            ctx.pPoly([(28.8, 47.5), (43.2, 47.5), (36, 55)], s: s, avatarHeart)
            ctx.pCircle(cx: 60.5, cy: 45.5, r: 4, s: s, avatarHeart)
            ctx.pCircle(cx: 67.5, cy: 45.5, r: 4, s: s, avatarHeart)
            ctx.pPoly([(56.8, 47.5), (71.2, 47.5), (64, 55)], s: s, avatarHeart)
            // 嘴：笑弧
            ctx.pStroke(w: 4, s: s, avatarEye) { p in
                p.move(to: pt(42, 65, s)); p.addQuadCurve(to: pt(58, 65, s), control: pt(50, 72, s))
            }
            // 附加：头顶右上漂浮小爱心（HEART，整组抬高避开头部轮廓）
            ctx.pCircle(cx: 75, cy: 18, r: 2, s: s, avatarHeart)
            ctx.pCircle(cx: 78, cy: 18, r: 2, s: s, avatarHeart)
            ctx.pPoly([(73.4, 19.2), (79.6, 19.2), (76.5, 23.5)], s: s, avatarHeart)
        }
    }

    // ---------- compact（规格 §5.5，size < 40：只画 头→面罩→眼→嘴） ----------

    private func drawCompact(_ ctx: inout GraphicsContext, expression: AgentExpression, headFill: Color, s: CGFloat) {
        ctx.pRoundRect(x: 12, y: 22, w: 76, h: 64, rx: 22, s: s, headFill) // 5 头
        ctx.pRoundRect(x: 22, y: 34, w: 56, h: 42, rx: 13, s: s, avatarVisor) // 6 面罩
        switch expression {
        // 眼嘴折算表（§5.5）：省掉天线、双耳、腮红、眉、附加元素
        case .sleepy:
            ctx.pStroke(w: 4, s: s, avatarEye) { p in
                p.move(to: pt(31, 48, s)); p.addLine(to: pt(41, 48, s))
            }
            ctx.pStroke(w: 4, s: s, avatarEye) { p in
                p.move(to: pt(59, 48, s)); p.addLine(to: pt(69, 48, s))
            }
            ctx.pCircle(cx: 50, cy: 67, r: 3, s: s, avatarEye)

        case .surprised:
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, avatarEye)
            ctx.pCircle(cx: 64, cy: 48, r: 5, s: s, avatarEye)
            ctx.pCircle(cx: 50, cy: 67, r: 4, s: s, avatarEye)

        case .thinking, .confused:
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, avatarEye)
            ctx.pCircle(cx: 64, cy: 48, r: 5, s: s, avatarEye)
            ctx.pStroke(w: 4, s: s, avatarEye) { p in
                p.move(to: pt(43, 66, s)); p.addLine(to: pt(57, 66, s))
            }

        case .sad:
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, avatarEye)
            ctx.pCircle(cx: 64, cy: 48, r: 5, s: s, avatarEye)
            ctx.pStroke(w: 4, s: s, avatarEye) { p in
                p.move(to: pt(41, 70, s)); p.addQuadCurve(to: pt(59, 70, s), control: pt(50, 61, s))
            }

        // CALM / HAPPY / EXCITED / SMUG / LOVE：圆点眼 + HAPPY 笑弧
        default:
            ctx.pCircle(cx: 36, cy: 48, r: 5, s: s, avatarEye)
            ctx.pCircle(cx: 64, cy: 48, r: 5, s: s, avatarEye)
            ctx.pStroke(w: 4, s: s, avatarEye) { p in
                p.move(to: pt(39, 63, s)); p.addQuadCurve(to: pt(61, 63, s), control: pt(50, 74, s))
            }
        }
    }
}
