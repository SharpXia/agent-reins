import SwiftUI

// MARK: - 墨水系设计令牌（M3 InkColors/InkType/InkShapes 逐值移植）

// 选中面/按钮面/accent 面四条铁律（2026-11-21 观感整治轮评审定稿+同日 tint 债清账轮补④；
// 违反即复现「白字压近白底」同款 bug）：
// 1. `.tint` 只作交互控件着色，不作隐式文本色来源——行内文字一律显式令牌
//    （.tint(colors.ink) 把 app accent 劫持成近白/近黑单色，beta-0.5.22 侧栏选中胶囊
//    与行内浅灰白字同色的根因即「系统选中面吃 accent + 行内文字钉死 tertiary」叠加）。
// 2. 系统选中面（List(selection:)/sidebar 胶囊）必须作用域覆盖 selectionFill 令牌，
//    不得裸吃全局 tint——防未来全局 tint 漂移时选中面随行漂移。
// 3. 新圆钮一律走统一面组件 `inkIconButtonFace`（Components.swift），禁就地
//    `Circle().fill(...)`——就地拼面是半透明材质/雾面残留的根源（c01acea 只换了
//    两处看不见的背景层，真身 glyph 半透明盘与默认 bezel 漏网）。
// 4. accent 语义面（链接/交互强调）一律用 InkColors.accent 显式令牌，禁新增
//    Color.accentColor 字面量——全局 tint 维持 ink 单色不动，字面量会渲染成近白/近黑
//    失去区分度（2026-11-21 tint 债清账轮：存量字面量已清零；Markdown 链接是 Text
//    .link run 的隐式着色，由 LiteMarkdownBody 作用域 .tint(accent) 收口）。

/// 颜色令牌：与 composeApp `ui/theme/InkColors.kt` 同源同值。
/// FuncColors 四件套在 Swift 里折平为带前缀的属性（infoText/infoBorder/...）。
/// 选中面六令牌（selectionFill/onSelection/onSelectionMuted/controlFace/onControlFace/
/// onControlFaceMuted）为本仓自有（2026-11-21 观感整治轮扩容），Kotlin 侧无对应——全部
/// 不透明实色，明暗成对，对比度为本轮 python3 WCAG 2.x 相对亮度公式实测。
struct InkColors {
    let isDark: Bool
    let ink: Color
    let textSecondary: Color
    let textTertiary: Color
    let textDisabled: Color
    let paper: Color
    let elevated: Color
    let surfaceTertiary: Color
    let surfaceDisabled: Color
    let inverse: Color
    let borderPrimary: Color
    let borderSecondary: Color
    let borderTertiary: Color
    let scrim: Color
    let infoText: Color
    let infoBorder: Color
    let infoRing: Color
    let infoSubtle: Color
    let dangerText: Color
    let dangerBorder: Color
    let dangerRing: Color
    let dangerSubtle: Color
    let successText: Color
    let successBorder: Color
    let successRing: Color
    let successSubtle: Color
    let warningText: Color
    let warningBorder: Color
    let warningRing: Color
    let warningSubtle: Color
    let shimmerBase: Color
    let awaiting: Color
    let awaitingSubtle: Color
    // ── accent 语义（链接/交互强调，2026-11-21 tint 债清账轮扩容）──
    /// accent 语义面专用：Markdown 链接、JSON 卡折叠钮、斜杠面板命令名/键盘高亮、
    /// 模型卡当前项、形象设置选中。全局 tint 维持 ink（单色语言）不动，系统 accent 仍
    /// 被劫持为近白/近黑——本令牌是 accent 语义的唯一合法来源，此后禁新增
    /// Color.accentColor 字面量（存量已随本轮清零，见各消费点）。
    /// 取色 = 既有 info/awaiting 蓝族（不引入新色相，与单色语言协调的克制色）：
    /// dark 与 infoText 同值 0x4D9BFF；light 自 infoText 0x0B6DD3 加深至 0x0A61BC——
    /// 0x0B6DD3 在链接实际落点（助手气泡面 surfaceTertiary 叠 paper ≈ 0xEFEFEF、JSON
    /// 卡面双层 surfaceTertiary ≈ 0xE3E3E3）只有 4.41:1 / 3.95:1，不达 WCAG AA 小文本
    /// 4.5:1。WCAG 2.x 相对亮度公式 python3 实测：
    /// light 0x0A61BC 对 paper 5.93 / 气泡面 5.30 / 卡面 4.74 / elevated 6.09；
    /// dark  0x4D9BFF 对 paper 6.64 / 气泡面 6.11 / 卡面 5.51 / elevated 5.91
    /// ——全表面 ≥4.5（描边/薄底等非文本用法 ≥3 同过）。
    let accent: Color
    // ── 选中面（sidebar 胶囊 + 行内文字对）──
    /// 系统选中胶囊面（List(selection:) 作用域 .tint 钉住此令牌）。今日与全局 tint 同值
    /// （light=ink:45 / dark=ink:83），作用是钉住令牌防全局 tint 漂移，非本轮变色项。
    let selectionFill: Color
    /// 选中行主文字（标题）。light 0xFCFCFC 对 selectionFill 16.61:1 / dark 0x121212 对 17.18:1。
    let onSelection: Color
    /// 选中行弱化文字（时间戳/网关名/预览/对账中）。light 8.13:1 / dark 5.27:1。
    let onSelectionMuted: Color
    // ── 按钮面（统一圆钮 inkIconButtonFace 用）──
    /// 圆钮实色面（替代半透明材质/雾面 bezel）。light 0xFFFFFF / dark 0x2A2A2A。
    let controlFace: Color
    /// 圆钮面上主图形（glyph）。light 对 controlFace 17.04:1 / dark 12.26:1。
    let onControlFace: Color
    /// 圆钮面上弱化图形（预留成对令牌）。light 5.10:1 / dark 6.04:1。
    let onControlFaceMuted: Color

    static func light() -> InkColors {
        InkColors(
            isDark: false,
            ink: Color(hex: 0x1C1C1C),
            textSecondary: Color(hex: 0x1C1C1C, alpha: 0xAD),
            textTertiary: Color(hex: 0x1C1C1C, alpha: 0x8F),
            textDisabled: Color(hex: 0x1C1C1C, alpha: 0x66),
            paper: Color(hex: 0xFCFCFC),
            elevated: Color(hex: 0xFFFFFF),
            surfaceTertiary: Color(hex: 0x1C1C1C, alpha: 0x0F),
            surfaceDisabled: Color(hex: 0x1C1C1C, alpha: 0x0A),
            inverse: Color(hex: 0x1C1C1C),
            borderPrimary: Color(hex: 0x1C1C1C, alpha: 0x1F),
            borderSecondary: Color(hex: 0x1C1C1C, alpha: 0x14),
            borderTertiary: Color(hex: 0x1C1C1C, alpha: 0x0F),
            scrim: Color(hex: 0x1C1C1C, alpha: 0x66),
            infoText: Color(hex: 0x0B6DD3),
            infoBorder: Color(hex: 0x1680F3),
            infoRing: Color(hex: 0x1E86FC),
            infoSubtle: Color(hex: 0x1680F3, alpha: 0x17),
            dangerText: Color(hex: 0xCD2834),
            dangerBorder: Color(hex: 0xE72E3B),
            dangerRing: Color(hex: 0xE72E3B),
            dangerSubtle: Color(hex: 0xFB2B3E, alpha: 0x17),
            successText: Color(hex: 0x00854A),
            successBorder: Color(hex: 0x10AB61),
            successRing: Color(hex: 0x10AB61),
            successSubtle: Color(hex: 0x14C16F, alpha: 0x17),
            warningText: Color(hex: 0xAA6300),
            warningBorder: Color(hex: 0xDA830E),
            warningRing: Color(hex: 0xDA830E),
            warningSubtle: Color(hex: 0xF59312, alpha: 0x17),
            shimmerBase: Color(hex: 0x1C1C1C, alpha: 0x40),
            awaiting: Color(hex: 0x0B6DD3),
            awaitingSubtle: Color(hex: 0x1680F3, alpha: 0x17),
            accent: Color(hex: 0x0A61BC),              // 链接/交互强调；paper 5.93:1 / 气泡面 5.30 / 卡面 4.74
            selectionFill: Color(hex: 0x1C1C1C),       // = ink（今日与全局 tint 同值）
            onSelection: Color(hex: 0xFCFCFC),         // 16.61:1 on selectionFill
            onSelectionMuted: Color(hex: 0xB3B3B3),    //  8.13:1 on selectionFill
            controlFace: Color(hex: 0xFFFFFF),
            onControlFace: Color(hex: 0x1C1C1C),       // 17.04:1 on controlFace
            onControlFaceMuted: Color(hex: 0x6E6E6E)   //  5.10:1 on controlFace
        )
    }

    static func dark() -> InkColors {
        InkColors(
            isDark: true,
            ink: Color(hex: 0xF5F5F5),
            textSecondary: Color(hex: 0xF5F5F5, alpha: 0xA8),
            textTertiary: Color(hex: 0xF5F5F5, alpha: 0x8A),
            textDisabled: Color(hex: 0xF5F5F5, alpha: 0x61),
            paper: Color(hex: 0x121212),
            elevated: Color(hex: 0x1E1E1E),
            surfaceTertiary: Color(hex: 0xF5F5F5, alpha: 0x0A),
            surfaceDisabled: Color(hex: 0xF5F5F5, alpha: 0x05),
            inverse: Color(hex: 0xF5F5F5),
            borderPrimary: Color(hex: 0xF5F5F5, alpha: 0x1A),
            borderSecondary: Color(hex: 0xF5F5F5, alpha: 0x0F),
            borderTertiary: Color(hex: 0xF5F5F5, alpha: 0x0A),
            scrim: Color(hex: 0x000000, alpha: 0xB3),
            infoText: Color(hex: 0x4D9BFF),
            infoBorder: Color(hex: 0x116DD2),
            infoRing: Color(hex: 0x1884FC),
            infoSubtle: Color(hex: 0x1884FC, alpha: 0x12),
            dangerText: Color(hex: 0xFF6463),
            dangerBorder: Color(hex: 0xCF2B35),
            dangerRing: Color(hex: 0xCF2B35),
            dangerSubtle: Color(hex: 0xFC2C3E, alpha: 0x12),
            successText: Color(hex: 0x00BA69),
            successBorder: Color(hex: 0x0E9F5A),
            successRing: Color(hex: 0x0E9F5A),
            successSubtle: Color(hex: 0x14C370, alpha: 0x12),
            warningText: Color(hex: 0xE28600),
            warningBorder: Color(hex: 0xC8770C),
            warningRing: Color(hex: 0xC8770C),
            warningSubtle: Color(hex: 0xF69412, alpha: 0x12),
            shimmerBase: Color(hex: 0xF5F5F5, alpha: 0x38),
            awaiting: Color(hex: 0x4D9BFF),
            awaitingSubtle: Color(hex: 0x1884FC, alpha: 0x12),
            accent: Color(hex: 0x4D9BFF),              // =infoText 同值；paper 6.64:1 / 气泡面 6.11 / 卡面 5.51
            selectionFill: Color(hex: 0xF5F5F5),       // = ink（今日与全局 tint 同值）
            onSelection: Color(hex: 0x121212),         // 17.18:1 on selectionFill
            onSelectionMuted: Color(hex: 0x666666),    //  5.27:1 on selectionFill
            controlFace: Color(hex: 0x2A2A2A),
            onControlFace: Color(hex: 0xEDEDED),       // 12.26:1 on controlFace
            onControlFaceMuted: Color(hex: 0xA8A8A8)   //  6.04:1 on controlFace
        )
    }
}

extension Color {
    init(hex: UInt32, alpha: UInt8 = 0xFF) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: Double(alpha) / 255
        )
    }
}

// MARK: - 字阶（InkType 移植：size/lineHeight 8 级）

enum InkTextStyle {
    case textXs, textSm, textMd, textLg
    case headingXs, headingSm, headingMd, headingLg
    case mono

    var size: CGFloat {
        switch self {
        case .textXs: return 12
        case .textSm: return 13
        case .textMd: return 14
        case .textLg, .headingXs: return 15
        case .headingSm: return 17
        case .headingMd: return 22
        case .headingLg: return 26
        case .mono: return 13
        }
    }

    /// 目标行高 − 系统默认行高（≈1.2×size）的补差，交给 lineSpacing。
    var lineSpacing: CGFloat {
        let target: CGFloat
        switch self {
        case .textXs: target = 16
        case .textSm: target = 18
        case .textMd: target = 20
        case .textLg, .headingXs: target = 22
        case .headingSm: target = 24
        case .headingMd: target = 28
        case .headingLg: target = 32
        case .mono: target = 18
        }
        return max(0, target - size * 1.2)
    }

    var weight: Font.Weight {
        switch self {
        case .headingXs: return .medium
        case .headingSm: return .semibold
        case .headingMd, .headingLg: return .bold
        default: return .regular
        }
    }

    var font: Font {
        if self == .mono { return .system(size: size, weight: .regular, design: .monospaced) }
        return .system(size: size, weight: weight)
    }
}

struct InkText: ViewModifier {
    let style: InkTextStyle
    func body(content: Content) -> some View {
        content.font(style.font).lineSpacing(style.lineSpacing)
    }
}

extension View {
    /// 墨水字阶统一入口：`Text("x").ink(.textMd)`。
    func ink(_ style: InkTextStyle) -> some View { modifier(InkText(style: style)) }
}

// MARK: - 圆角（InkShapes：4/6/8/12/16/full）

enum InkShape {
    static let xs: CGFloat = 4
    static let sm: CGFloat = 6
    static let md: CGFloat = 8
    static let lg: CGFloat = 12
    static let xl: CGFloat = 16
}

// MARK: - Agent 身份色（AgentTint 移植，含 Kotlin hashCode 复刻保证同 seed 同色）

enum AgentTintName: String, CaseIterable {
    case GRAY, BLUE, RED, ORANGE, YELLOW, GREEN, CYAN, PURPLE, MAGENTA, BROWN
}

struct AgentTintColors {
    let name: AgentTintName
    let strong: Color
    let bubble: Color
    let pill: Color
}

private let strongLight: [AgentTintName: UInt32] = [
    .GRAY: 0x8E8E93, .BLUE: 0x0069D9, .RED: 0xD70015, .ORANGE: 0xC93400,
    .YELLOW: 0xB25000, .GREEN: 0x248A3D, .CYAN: 0x1C7A99, .PURPLE: 0x8944AB,
    .MAGENTA: 0xD5236B, .BROWN: 0x8B6D4F,
]

private let strongDark: [AgentTintName: UInt32] = [
    .GRAY: 0x98989D, .BLUE: 0x0A84FF, .RED: 0xFF453A, .ORANGE: 0xFF9F0A,
    .YELLOW: 0xFFD60A, .GREEN: 0x30D158, .CYAN: 0x64D2FF, .PURPLE: 0xBF5AF2,
    .MAGENTA: 0xFF375F, .BROWN: 0xAC8E68,
]

func agentTint(_ name: AgentTintName, isDark: Bool) -> AgentTintColors {
    let strong = Color(hex: (isDark ? strongDark : strongLight)[name]!)
    return AgentTintColors(
        name: name,
        strong: strong,
        bubble: strong.opacity(isDark ? 0.22 : 0.14),
        pill: strong.opacity(isDark ? 0.18 : 0.12)
    )
}

/// Kotlin `String.hashCode()` 复刻（s[0]*31^(n-1)+…，Int32 溢出环绕）——
/// 未注册 agent 的兜底色必须与 Kotlin 侧 agentTintFor 同 seed 同色。
func kotlinHashCode(_ s: String) -> Int32 {
    var h: Int32 = 0
    for unit in s.utf16 {
        h = h &* 31 &+ Int32(truncatingIfNeeded: unit)
    }
    return h
}

/// 未注册 agent 的稳定兜底色（与 Kotlin agentTintFor 同语义）。
func agentTintFor(_ seed: String) -> AgentTintName {
    let h = kotlinHashCode(seed)
    let mag = h == Int32.min ? Int32.max : abs(h)
    return AgentTintName.allCases[Int(mag) % AgentTintName.allCases.count]
}

// MARK: - 主题环境

private struct InkColorsKey: EnvironmentKey {
    static let defaultValue = InkColors.light()
}

extension EnvironmentValues {
    var inkColors: InkColors {
        get { self[InkColorsKey.self] }
        set { self[InkColorsKey.self] = newValue }
    }
}

/// 墨水主题注入：按当前配色下发令牌（等价 InkTheme Composable）。
struct InkThemeProvider: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        let colors = colorScheme == .dark ? InkColors.dark() : InkColors.light()
        content
            .environment(\.inkColors, colors)
            .tint(colors.ink)
    }
}

extension View {
    func inkTheme() -> some View { modifier(InkThemeProvider()) }
}

/// 视图内取墨水令牌：`@Environment(\.inkColors) var c`。
extension View {
    /// helper for brevity in previews
    func inkPaperBackground(_ c: InkColors) -> some View { background(c.paper) }
}
