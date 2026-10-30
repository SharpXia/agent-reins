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

/// agent 头像：黏土 blob Canvas 薄壳（RobotAvatar 渲染，face-sculpt §1/§2）。
/// 首字符圆底旧实现整段删除、不留双轨；name 参数仅为保签名兼容。
/// faceFactors 必须由调用点解析后显式传入（渲染器零解析）。
struct AgentAvatar: View {
    let tint: AgentTintColors
    let name: String
    var size: CGFloat = 42
    var expression: AgentExpression = .calm
    var faceFactors: [Double] = ClayFace.defaultFactors

    var body: some View {
        RobotAvatar(palette: tint.name, expression: expression, size: size, faceFactors: faceFactors)
            // 复审修复②：RobotAvatar 是 Canvas，不产生 accessibility 元素，原首字符 Text
            // 自带的读屏内容随替换消失——聚合成单元素并补「agent 名 + 表情中文名」标签
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(name)，表情\(expression.zhLabel)")
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

/// 生成中三点跳动（TypingDots）。
struct TypingDots: View {
    @Environment(\.inkColors) private var c
    @State private var phase = false

    var body: some View {
        HStack(spacing: 4) {
            ForEach(0..<3, id: \.self) { i in
                Circle()
                    .fill(c.textTertiary)
                    .frame(width: 5, height: 5)
                    .offset(y: phase ? -3 : 0)
                    .animation(
                        .easeInOut(duration: 0.45)
                        .repeatForever(autoreverses: true)
                        .delay(Double(i) * 0.15),
                        value: phase
                    )
            }
        }
        .onAppear { phase = true }
    }
}

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

/// 连接空态（ConnectionEmptyState 四态）。
struct ConnectionEmptyState: View {
    @Environment(\.inkColors) private var c
    let conn: BridgeConn

    var body: some View {
        VStack(spacing: 4) {
            switch conn.kind {
            case "connecting":
                ProgressView().tint(c.ink)
                Text("连接中…").ink(.headingXs).foregroundColor(c.ink).padding(.top, 12)
            case "pairing":
                ProgressView().tint(c.ink)
                Text("等待 owner 批准设备…").ink(.headingXs).foregroundColor(c.ink).padding(.top, 12)
                if let requestId = conn.requestId {
                    Text("requestId: \(requestId)").ink(.textSm).foregroundColor(c.textTertiary)
                }
            case "error":
                Text("自动连接失败").ink(.headingXs).foregroundColor(c.ink)
                Text(conn.message ?? "未知错误").ink(.textSm).foregroundColor(c.dangerText)
                    .multilineTextAlignment(.center)
                Text("回前台或到「设置 → 连接」重试").ink(.textSm).foregroundColor(c.textTertiary)
            default:
                Text("未连接网关").ink(.headingXs).foregroundColor(c.ink)
                Text("到「设置 → 连接」扫码配对或填入地址与 API Key")
                    .ink(.textMd).foregroundColor(c.textTertiary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(24)
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
