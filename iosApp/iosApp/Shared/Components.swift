import composeApp

import SwiftUI

// MARK: - 共享组件（Sand 范式 + 徽标 + 头像 + 打字点）

/// 未读徽标（>99 显示 99+）。
struct UnreadBadge: View {
    @Environment(\.inkColors) private var c
    let count: Int

    var body: some View {
        Text(count > 99 ? "99+" : "\(count)")
            .font(.system(size: 11, weight: .medium))
            .foregroundColor(c.paper)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Capsule().fill(c.inverse))
    }
}

/// agent 标题胶囊（AgentTitlePill）。
struct AgentTitlePill: View {
    let name: String
    let tint: Color
    let pillFill: Color

    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(tint).frame(width: 6, height: 6)
            Text(name).ink(.textXs).foregroundColor(tint)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 3)
        .background(Capsule().fill(pillFill))
    }
}

/// 渠道徽标。
struct ChannelBadge: View {
    @Environment(\.inkColors) private var c
    let channel: String

    var body: some View {
        Text(channel)
            .ink(.textXs)
            .foregroundColor(c.textTertiary)
            .padding(.horizontal, 5)
            .padding(.vertical, 1)
            .background(RoundedRectangle(cornerRadius: 4).fill(c.surfaceTertiary))
    }
}

// TypingDots（生成中三点跳动）已随角色系统批次4（2026-11-20）删除：零调用点死代码
//（余点审计全 app grep 无引用），生成中体感由流式占位行与标题 AnimatedEllipsis 承接。

/// iOS 分组节标题。
struct SectionHeader: View {
    @Environment(\.inkColors) private var c
    let text: String

    var body: some View {
        Text(text)
            .ink(.textSm)
            .foregroundColor(c.textTertiary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
    }
}

/// 分组卡片（SandCard）：elevated 底 + lg 圆角 + 次级描边。
struct SandCard<Content: View>: View {
    @Environment(\.inkColors) private var c
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        VStack { content }
            .frame(maxWidth: .infinity)
            .background(
                RoundedRectangle(cornerRadius: InkShape.lg).fill(c.elevated)
            )
            .overlay(
                RoundedRectangle(cornerRadius: InkShape.lg).strokeBorder(c.borderSecondary, lineWidth: 1)
            )
    }
}

/// 组内分隔线（起点缩进）。
struct SandDivider: View {
    @Environment(\.inkColors) private var c
    var indent: CGFloat = 16

    var body: some View {
        Rectangle().fill(c.borderTertiary).frame(height: 1)
            .padding(.leading, indent)
    }
}

/// Settings 式行：标题/副标题 + 尾部槽 + 可选点击。
struct SandRow<Trailing: View>: View {
    @Environment(\.inkColors) private var c
    let title: String
    var subtitle: String? = nil
    var titleColor: Color? = nil
    let trailing: () -> Trailing
    var onClick: (() -> Void)? = nil

    init(_ title: String, subtitle: String? = nil, titleColor: Color? = nil,
         @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() },
         onClick: (() -> Void)? = nil) {
        self.title = title
        self.subtitle = subtitle
        self.titleColor = titleColor
        self.trailing = trailing
        self.onClick = onClick
    }

    var body: some View {
        Button {
            onClick?()
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).ink(.textLg).foregroundColor(titleColor ?? c.ink)
                        .lineLimit(1)
                    if let subtitle {
                        Text(subtitle).ink(.textSm).foregroundColor(c.textTertiary)
                            .lineLimit(2).multilineTextAlignment(.leading)
                    }
                }
                Spacer(minLength: 8)
                trailing()
                if onClick != nil {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(c.textTertiary)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(onClick == nil)
    }
}

/// 开关行。
struct SandToggleRow: View {
    let title: String
    var subtitle: String? = nil
    @Binding var checked: Bool
    var onChange: ((Bool) -> Void)? = nil

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).ink(.textLg)
                if let subtitle {
                    Text(subtitle).ink(.textXs).multilineTextAlignment(.leading)
                }
            }
            Spacer()
            Toggle("", isOn: $checked)
                .labelsHidden()
                .fixedSize()
                .onChange(of: checked) { _, newValue in onChange?(newValue) }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .onTapGesture {
            withAnimation { checked.toggle() }
            onChange?(checked)
        }
    }
}

/// 错误横幅（点击清除）。
struct ErrorBanner: View {
    @Environment(\.inkColors) private var c
    let message: String
    let onClear: () -> Void

    var body: some View {
        Text(message)
            .ink(.textSm)
            .foregroundColor(c.dangerText)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(RoundedRectangle(cornerRadius: InkShape.md).fill(c.dangerSubtle))
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
            .onTapGesture(perform: onClear)
    }
}

/// 底部 Sheet 容器（SandSheet 等价：标题 + 关闭下拉）。
struct InkSheet<Content: View>: View {
    @Environment(\.inkColors) private var c
    @Environment(\.dismiss) private var dismiss
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Capsule().fill(c.borderPrimary).frame(width: 36, height: 4)
                .frame(maxWidth: .infinity)
                .padding(.top, 8)
            Text(title)
                .ink(.headingSm)
                .foregroundColor(c.ink)
                .padding(.horizontal, 20)
                .padding(.top, 14)
                .padding(.bottom, 6)
            content
        }
        .presentationDragIndicator(.hidden)
        .presentationBackground(c.paper)
        .presentationDetents([.medium, .large])
    }
}

/// Sheet 动作行。
struct InkSheetAction: View {
    @Environment(\.inkColors) private var c
    let icon: String
    let label: String
    var tint: Color? = nil
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 15))
                    .foregroundColor(tint ?? c.ink)
                    .frame(width: 22)
                Text(label).ink(.textLg).foregroundColor(tint ?? c.ink)
                Spacer()
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - hover 反馈（UxPlan item7，2026-11-21 观感整治轮自 HubViews.swift 整体迁入）

/// 自绘按钮面 hover 反馈（指针设备高亮；触屏无 hover 事件即空转，跨平台无害，故不作
/// Catalyst 门控）——Catalyst 系统控件自动 hover，.buttonStyle(.plain) 自绘面需补。
/// 半透明 primary 覆盖层压在既有底色上，allowsHitTesting 关闭不抢手势。
/// private→internal（2026-11-21 观感整治轮）：单一真相迁本文件，HubViews 六处消费点
///（hoverCircle 回底钮 + hoverHighlight 附件 chip/斜杠面板行/交互卡按钮×2/消息行×2）
/// 与本文件 InkIconButtonFace 同模块复用，禁双份拷贝。
struct HoverFillModifier: ViewModifier {
    @State private var hovering = false
    var cornerRadius: CGFloat

    func body(content: Content) -> some View {
        content
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius)
                    .fill(Color.primary.opacity(hovering ? 0.07 : 0))
                    .allowsHitTesting(false)
            )
            .onHover { hovering = $0 }
    }
}

struct HoverCircleModifier: ViewModifier {
    @State private var hovering = false

    func body(content: Content) -> some View {
        content
            .overlay(
                Circle()
                    .fill(Color.primary.opacity(hovering ? 0.07 : 0))
                    .allowsHitTesting(false)
            )
            .onHover { hovering = $0 }
    }
}

extension View {
    /// 矩形交互面 hover 高亮（斜杠面板行/交互卡按钮/附件 chip）。
    func hoverHighlight(cornerRadius: CGFloat) -> some View {
        modifier(HoverFillModifier(cornerRadius: cornerRadius))
    }

    /// 圆形交互面 hover 高亮（回底悬浮按钮/统一圆钮面）。
    func hoverCircle() -> some View {
        modifier(HoverCircleModifier())
    }
}

// MARK: - 统一圆钮面（2026-11-21 观感整治轮）

/// 圆形图标按钮统一交互面（InkTheme 铁律③）：实色 controlFace 底 + borderSecondary 描边
/// + onControlFace 单色 glyph + hover 内聚。消灭就地 `Circle().fill`/半透明材质/系统
/// 默认 bezel 三类雾面残留（beta-0.5.22 实测）；明暗对比度由令牌对保证
///（onControlFace 对 controlFace：light 17.04:1 / dark 12.26:1）。
///
/// 几何纪律（2026-11-21 独立走查②修正）：**先方形化再贴圆**——SwiftUI `Circle()` 在
/// 非方形帧内是内切椭圆，裸字形 bbox 多非 1:1（chevron.down≈1.6:1/photo≈1.3:1），
/// 等 padding 不改纵横比；故以显式 `diameter` 出方帧，Circle 恒正圆。
/// 禁用态显式（走查⑤）：`.plain`/automatic 风格都不保证禁用淡出（borderless 系经典
/// 缺口），本面读 `\.isEnabled`——禁用=onControlFaceMuted+opacity 0.4，小尺寸可辨识。
/// 注意：isEnabled 经环境传播，面须挂在 Button **label 内**（.disabled 作用域内才读得到）。
/// 参数 iconFont/diameter（回底钮 .title3/38、附件撤除角标 .subheadline/14、composer
/// photo .title3/32、stop/send .title2/34）；shape 现阶段仅 Circle。offset 等布局位移
/// 留在调用点，不进本面。
struct InkIconButtonFace: ViewModifier {
    @Environment(\.inkColors) private var ink
    @Environment(\.isEnabled) private var enabled
    var iconFont: Font
    var diameter: CGFloat

    func body(content: Content) -> some View {
        content
            .font(iconFont)
            .foregroundStyle(enabled ? AnyShapeStyle(ink.onControlFace)
                                     : AnyShapeStyle(ink.onControlFaceMuted))
            .frame(width: diameter, height: diameter)
            .background(Circle().fill(ink.controlFace))
            .overlay(Circle().strokeBorder(ink.borderSecondary, lineWidth: 1))
            .opacity(enabled ? 1 : 0.4)
            .hoverCircle()
    }
}

extension View {
    /// 统一圆钮面挂载（见 InkIconButtonFace 注；调用点在 Button label 内）。
    func inkIconButtonFace(iconFont: Font, diameter: CGFloat) -> some View {
        modifier(InkIconButtonFace(iconFont: iconFont, diameter: diameter))
    }
}
