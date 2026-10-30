import SwiftUI
import composeApp

// MARK: - 工具调用卡（M3.5 墨水化 + Canvas-lite 全屏查看器）

/// 折叠态 = 名称 + 摘要 + 耗时/执行中 + 状态；展开态 = 参数/结果。
struct ToolCallCardView: View {
    @Environment(\.inkColors) private var c
    @State private var expanded = false
    @State private var fullscreen = false
    let card: BridgeEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { withAnimation(.easeInOut(duration: 0.18)) { expanded.toggle() } } label: {
                HStack(spacing: 8) {
                    Image(systemName: "wrench.and.screwdriver")
                        .font(.system(size: 12))
                        .foregroundColor(c.textTertiary)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(card.toolName.isEmpty ? "工具调用" : card.toolName)
                            .ink(.textSm).foregroundColor(c.ink).lineLimit(1)
                        if let title = card.title, !title.isEmpty {
                            Text(title).ink(.textXs).foregroundColor(c.textTertiary).lineLimit(1)
                        }
                    }
                    Spacer(minLength: 8)
                    if card.state == "running" {
                        Text("执行中").ink(.textXs).foregroundColor(c.awaiting)
                    } else {
                        Text(formatDuration(card.durationMs)).ink(.textXs).foregroundColor(c.textTertiary)
                    }
                    stateIcon
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 11))
                        .foregroundColor(c.textTertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if expanded {
                Divider().overlay(c.borderSecondary)
                MonoBlockView(
                    label: "参数",
                    text: prettyJson(card.argumentsJson) ?? "（事件流未提供，来自对话历史的参数）"
                )
                let resultText = card.resultJson ?? "…"
                MonoBlockView(label: "结果", text: resultText, onFullscreen: resultText.count > 400 ? { fullscreen = true } : nil)
                if card.exitCode >= 0 {
                    Text("exit code: \(card.exitCode)")
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundColor(card.exitCode == 0 ? c.textTertiary : c.dangerText)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: InkShape.md).fill(c.elevated))
        .fullScreenCover(isPresented: $fullscreen) {
            FullscreenOutputView(title: card.toolName.isEmpty ? "工具输出" : card.toolName,
                                 text: card.resultJson ?? "…")
        }
    }

    @ViewBuilder
    private var stateIcon: some View {
        switch card.state {
        case "running": ProgressView().tint(c.awaiting).scaleEffect(0.5).frame(width: 15, height: 15)
        case "succeeded": Image(systemName: "checkmark").font(.system(size: 11)).foregroundColor(c.successText)
        default: Image(systemName: "xmark").font(.system(size: 11)).foregroundColor(c.dangerText)
        }
    }
}

/// 回合块（真机反馈③）：思考过程 + 过程文本 + 工具调用统一收进一个折叠条，摆在对应
/// 回合最终输出上方。展开 = 思考全文 → 过程文本（如「让我用网页搜索…」）→ 工具卡逐条
/// （复用 ToolCallCardView，各自可再展开参数/结果）。
struct TurnBlockView: View {
    @Environment(\.inkColors) private var c
    @State private var expanded = false
    let thinking: [BridgeEntry]
    let tools: [BridgeEntry]
    var interim: [BridgeEntry] = []

    var body: some View {
        let failed = tools.filter { $0.state == "failed" }.count
        VStack(alignment: .leading, spacing: 8) {
            Button { withAnimation(.easeInOut(duration: 0.18)) { expanded.toggle() } } label: {
                HStack(spacing: 8) {
                    Image(systemName: "brain.head.profile")
                        .font(.system(size: 12))
                        .foregroundColor(c.textTertiary)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(headerTitle).ink(.textSm).foregroundColor(c.ink)
                        if failed > 0 {
                            Text("\(failed) 次工具失败").ink(.textXs).foregroundColor(c.dangerText)
                        }
                    }
                    Spacer(minLength: 8)
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 11))
                        .foregroundColor(c.textTertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if expanded {
                Divider().overlay(c.borderSecondary)
                ForEach(thinking, id: \.id) { t in
                    Text(t.text)
                        .ink(.textSm)
                        .italic()
                        .foregroundColor(c.textTertiary)
                        .lineSpacing(3)
                }
                ForEach(interim, id: \.id) { m in
                    MarkdownView(content: m.content)
                }
                ForEach(tools, id: \.id) { tool in
                    ToolCallCardView(card: tool)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: InkShape.md).fill(c.elevated))
    }

    private var headerTitle: String {
        var parts: [String] = []
        if !thinking.isEmpty { parts.append("思考过程") }
        if !tools.isEmpty { parts.append("\(tools.count) 次工具调用") }
        if !interim.isEmpty { parts.append("\(interim.count) 段过程") }
        return parts.isEmpty ? "思考过程" : parts.joined(separator: " · ")
    }
}

/// 连续 ≥2 张已完成工具卡的折叠组。
struct ToolGroupCard: View {
    @Environment(\.inkColors) private var c
    @State private var expanded = false
    let items: [BridgeEntry]

    var body: some View {
        let failed = items.filter { $0.state == "failed" }.count
        let totalMs = items.reduce(Int64(0)) { $0 + max(0, $1.durationMs) }
        VStack(alignment: .leading, spacing: 8) {
            Button { withAnimation(.easeInOut(duration: 0.18)) { expanded.toggle() } } label: {
                HStack(spacing: 8) {
                    Image(systemName: "wrench.and.screwdriver")
                        .font(.system(size: 12))
                        .foregroundColor(c.textTertiary)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("\(items.count) 次工具调用").ink(.textSm).foregroundColor(c.ink)
                        Text(failed > 0 ? "\(failed) 次失败" : "全部成功")
                            .ink(.textXs)
                            .foregroundColor(failed > 0 ? c.dangerText : c.textTertiary)
                    }
                    Spacer(minLength: 8)
                    Text(formatDuration(totalMs)).ink(.textXs).foregroundColor(c.textTertiary)
                    if failed > 0 {
                        Image(systemName: "xmark").font(.system(size: 11)).foregroundColor(c.dangerText)
                    } else {
                        Image(systemName: "checkmark").font(.system(size: 11)).foregroundColor(c.successText)
                    }
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 11)).foregroundColor(c.textTertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if expanded {
                Divider().overlay(c.borderSecondary)
                ForEach(items, id: \.id) { card in
                    ToolCallCardView(card: card)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: InkShape.md).fill(c.elevated))
    }
}

/// 等宽参数/结果块（限高滚动 + 可选全屏入口）。
struct MonoBlockView: View {
    @Environment(\.inkColors) private var c
    let label: String
    let text: String
    var onFullscreen: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).ink(.textXs).foregroundColor(c.textTertiary)
            VStack(spacing: 0) {
                ScrollView {
                    Text(text)
                        .font(.system(size: 11, design: .monospaced))
                        .lineSpacing(2)
                        .foregroundColor(c.ink)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(8)
                }
                .frame(maxHeight: 220)
                if let onFullscreen {
                    Button(action: onFullscreen) {
                        Text("全屏查看")
                            .ink(.textSm)
                            .foregroundColor(c.infoText)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 7)
                            .background(c.borderTertiary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .background(RoundedRectangle(cornerRadius: InkShape.sm).fill(c.surfaceTertiary))
            .clipShape(RoundedRectangle(cornerRadius: InkShape.sm))
        }
    }
}

/// Canvas-lite 全屏查看器：等宽滚动 + 复制 + 关闭。
struct FullscreenOutputView: View {
    @Environment(\.inkColors) private var c
    @Environment(\.dismiss) private var dismiss
    let title: String
    let text: String

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(title).ink(.headingSm).foregroundColor(c.ink)
                Spacer()
                Button("复制全部") {
                    UIPasteboard.general.string = text
                }
                .ink(.textMd)
                .foregroundColor(c.ink)
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(c.ink)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
            Divider().overlay(c.borderSecondary)
            ScrollView {
                Text(text)
                    .font(.system(size: 11, design: .monospaced))
                    .lineSpacing(3)
                    .foregroundColor(c.ink)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
            }
            .background(RoundedRectangle(cornerRadius: InkShape.md).fill(c.surfaceTertiary))
            .padding(12)
        }
        .background(c.paper)
    }
}
