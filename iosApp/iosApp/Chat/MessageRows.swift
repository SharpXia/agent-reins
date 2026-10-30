import SwiftUI
import composeApp

/// 对话流行分发（ChatRow → 各行视图）。
struct ChatRowView: View {
    let row: ChatRow
    let tintFor: (String?) -> AgentTintColors
    let lastUserEntryId: String?
    let showTailSpinner: Bool
    let onBoundaryAppear: (String) -> Void

    var body: some View {
        switch row {
        case let .dayHeader(_, atMs):
            DayHeaderView(atMs: atMs)
        case let .entry(_, entry):
            EntryView(entry: entry, tintFor: tintFor, isLastUser: entry.id == lastUserEntryId,
                      showSpinner: entry.id == lastUserEntryId && showTailSpinner)
        case let .toolGroup(_, items):
            ToolGroupCard(items: items)
        case let .turnBlock(_, thinking, tools):
            TurnBlockView(thinking: thinking, tools: tools)
        case let .boundary(_, sessionKey, label):
            BoundaryView(label: label).onAppear { onBoundaryAppear(sessionKey) }
        case let .compactMark(_, _, atMs):
            CompactMarkView(atMs: atMs)
        }
    }
}

struct DayHeaderView: View {
    @Environment(\.inkColors) private var c
    let atMs: Int64

    var body: some View {
        Text(dayHeaderLabel(atMs))
            .ink(.textXs)
            .foregroundColor(c.textTertiary)
            .frame(maxWidth: .infinity, alignment: .center)
            .padding(.vertical, 6)
    }
}

/// conversation 分区头（新开主 session 的延续标记，居中小胶囊）。
struct BoundaryView: View {
    @Environment(\.inkColors) private var c
    let label: String

    var body: some View {
        Text(label)
            .ink(.textXs)
            .foregroundColor(c.textTertiary)
            .padding(.horizontal, 10)
            .padding(.vertical, 3)
            .background(Capsule().fill(c.surfaceTertiary))
            .frame(maxWidth: .infinity, alignment: .center)
            .padding(.vertical, 2)
    }
}

/// 压缩上下文标记。
struct CompactMarkView: View {
    @Environment(\.inkColors) private var c
    let atMs: Int64

    var body: some View {
        Text("已压缩上下文 · \(formatRelativeTime(atMs))")
            .ink(.textXs)
            .italic()
            .foregroundColor(c.textTertiary)
            .padding(.horizontal, 10)
            .padding(.vertical, 3)
            .background(Capsule().fill(c.surfaceTertiary))
            .frame(maxWidth: .infinity, alignment: .center)
            .padding(.vertical, 2)
    }
}

/// 条目分发：消息 / 思考卡。
struct EntryView: View {
    @Environment(\.inkColors) private var c
    @State private var store = AppStore.shared
    let entry: BridgeEntry
    let tintFor: (String?) -> AgentTintColors
    let isLastUser: Bool
    let showSpinner: Bool

    var body: some View {
        switch entry.kind {
        case "message":
            MessageItemView(entry: entry, tint: tintFor(entry.sessionId),
                            isLastUser: isLastUser, showSpinner: showSpinner)
        case "thinking":
            ThinkingCardView(entry: entry)
        case "tool":
            ToolCallCardView(card: entry)
        default:
            EmptyView()
        }
    }
}

/// 消息（用户 tint 气泡 / 助手墨水平文 / 系统行内注）+ 右下角发送时间 + 长按操作。
struct MessageItemView: View {
    @Environment(\.inkColors) private var c
    @State private var store = AppStore.shared
    let entry: BridgeEntry
    let tint: AgentTintColors
    let isLastUser: Bool
    let showSpinner: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            switch entry.role {
            case "user":
                HStack(alignment: .bottom, spacing: 6) {
                    if showSpinner {
                        ProgressView()
                            .tint(tint.strong)
                            .scaleEffect(0.6)
                            .frame(width: 14, height: 14)
                    }
                    Spacer(minLength: 0)
                    userBubble
                }
            case "assistant":
                assistantBody
            default:
                Text(entry.content)
                    .ink(.textSm)
                    .italic()
                    .foregroundColor(c.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 2)
            }
            if entry.role != "system" {
                Text(formatMessageTime(entry.timestampMs))
                    .ink(.textXs)
                    .foregroundColor(c.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
    }

    // 用户 tint 气泡：右上小圆角（Grok bubbleUser 形态）
    private var userBubble: some View {
        Text(entry.content)
            .ink(.textLg)
            .foregroundColor(c.ink)
            .padding(.horizontal, 13)
            .padding(.vertical, 9)
            .background(
                UnevenRoundedRectangle(topLeadingRadius: InkShape.lg, bottomLeadingRadius: InkShape.lg, bottomTrailingRadius: InkShape.lg, topTrailingRadius: 5)
                    .fill(tint.bubble)
            )
            .frame(maxWidth: UIScreen.main.bounds.width * 0.82, alignment: .trailing)
            .contextMenu { actions }
    }

    @ViewBuilder
    private var assistantBody: some View {
        Group {
            if entry.streaming {
                ShimmerText(content: entry.content)
            } else if !entry.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                MarkdownView(content: entry.content)
            }
        }
        .contextMenu { actions }
    }

    @ViewBuilder
    private var actions: some View {
        Button {
            UIPasteboard.general.string = entry.content
        } label: {
            Label("复制", systemImage: "doc.on.doc")
        }
        Button {
            store.bridge.quoteMessage(text: entry.content)
        } label: {
            Label("引用", systemImage: "arrowshape.turn.up.left")
        }
        if entry.role == "user" {
            Button {
                Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
                store.bridge.resendText(text: entry.content)
            } label: {
                Label("重发", systemImage: "arrow.counterclockwise")
            }
            Button {
                store.bridge.fork(entryId: entry.entryId, id: entry.id)
            } label: {
                Label("分支（从这条重新开话）", systemImage: "arrow.triangle.branch")
            }
        }
    }
}

/// 流式文本 shimmer（shimmerBase → textTertiary 微光扫过）。
struct ShimmerText: View {
    @Environment(\.inkColors) private var c
    let content: String
    @State private var phase: CGFloat = 0

    var body: some View {
        Text(content)
            .ink(.textLg)
            .foregroundColor(.clear)
            .overlay(
                LinearGradient(
                    colors: [c.shimmerBase, c.textTertiary, c.shimmerBase],
                    startPoint: .leading, endPoint: .trailing
                )
                .frame(width: 500)
                .offset(x: phase)
                .mask(
                    Text(content).ink(.textLg)
                )
            )
            .onAppear {
                withAnimation(.linear(duration: 1.5).repeatForever(autoreverses: false)) {
                    phase = 700
                }
            }
    }
}

/// 思考卡：瞬态条目，尾部 6 行；run 终局整卡消失。
struct ThinkingCardView: View {
    @Environment(\.inkColors) private var c
    let entry: BridgeEntry

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            TypingDots()
            HStack(alignment: .top, spacing: 10) {
                Rectangle()
                    .fill(c.borderTertiary)
                    .frame(width: 2, height: 20)
                VStack(alignment: .leading, spacing: 2) {
                    Text("思考中").ink(.textXs).foregroundColor(c.textDisabled)
                    Text(thinkingTail(entry.text))
                        .ink(.textSm)
                        .italic()
                        .foregroundColor(c.textTertiary)
                }
            }
        }
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 生成中指示。
struct TypingIndicator: View {
    @Environment(\.inkColors) private var c

    var body: some View {
        HStack(spacing: 8) {
            TypingDots()
            Text("生成中…").ink(.textSm).italic().foregroundColor(c.textTertiary)
        }
        .padding(.vertical, 4)
    }
}

/// outbox 待发气泡：灰阶弱化 + 未发出/核对中 + 重试/删除。
struct PendingOutboxItem: View {
    @Environment(\.inkColors) private var c
    @Environment(\.colorScheme) private var scheme
    @State private var store = AppStore.shared
    let cmd: BridgeOutboxCmd

    var body: some View {
        let tint = store.tint(
            store.session(store.conv.sessionKey)?.agentId
                ?? sessionKeyAgentId(store.conv.sessionKey) ?? "main",
            isDark: scheme == .dark
        )
        VStack(alignment: .trailing, spacing: 2) {
            Text(cmd.text)
                .ink(.textLg)
                .foregroundColor(c.textTertiary)
                .padding(.horizontal, 13)
                .padding(.vertical, 9)
                .background(
                    UnevenRoundedRectangle(topLeadingRadius: InkShape.lg, bottomLeadingRadius: InkShape.lg, bottomTrailingRadius: InkShape.lg, topTrailingRadius: 5)
                        .fill(tint.bubble.opacity(0.55))
                )
                .frame(maxWidth: 320, alignment: .trailing)
            HStack(spacing: 2) {
                Text(cmd.unconfirmed ? "核对中" : "未发出")
                    .ink(.textXs)
                    .foregroundColor(cmd.unconfirmed ? c.textTertiary : c.dangerText)
                Button {
                    Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
                    store.bridge.retryQueued()
                } label: {
                    Image(systemName: "arrow.counterclockwise")
                        .font(.system(size: 12))
                        .foregroundColor(c.textTertiary)
                        .frame(width: 28, height: 28)
                }
                .buttonStyle(.plain)
                Button {
                    Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
                    store.bridge.deleteQueued(idempotencyKey: cmd.idempotencyKey)
                } label: {
                    Image(systemName: "trash")
                        .font(.system(size: 12))
                        .foregroundColor(c.textTertiary)
                        .frame(width: 28, height: 28)
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
    }
}
