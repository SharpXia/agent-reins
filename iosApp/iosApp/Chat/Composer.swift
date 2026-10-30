import SwiftUI
import composeApp

/// ComposerShell（M3.4）：引用条 QuoteBar + 圆角输入壳 + 对话操作 + 单按钮三态
/// （有内容=发送；空+运行中=终止；空+空闲=禁用）。
struct ComposerView: View {
    @Environment(\.inkColors) private var c
    @Environment(\.colorScheme) private var scheme
    @State private var store = AppStore.shared
    @FocusState private var focused: Bool

    /// 本地草稿态（双向桥：会话切换时从快照装载，输入时 fire-and-forget 推回），
    /// 不直接绑快照——避免流式回包时快照值覆盖光标位置。
    @State private var draft = ""
    @State private var draftSessionKey = ""

    var body: some View {
        VStack(spacing: 0) {
            if let quoted = store.misc.quotedText {
                QuoteBarView(quoted: quoted)
            }
            HStack(alignment: .bottom, spacing: 6) {
                TextField("发送消息…", text: $draft, axis: .vertical)
                    .focused($focused)
                    .lineLimit(1...4)
                    .ink(.textMd)
                    .foregroundColor(c.ink)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(RoundedRectangle(cornerRadius: 22).fill(c.surfaceTertiary))
                    .onChange(of: draft) { _, newValue in
                        store.bridge.setDraft(text: newValue)
                    }
                    .onChange(of: store.conv.sessionKey) { _, key in
                        // 切会话装载该会话草稿（Kotlin 侧 select 已落旧草稿/读新草稿）
                        if key != draftSessionKey {
                            draftSessionKey = key
                            draft = store.misc.draft
                        }
                    }
                    .onAppear {
                        draftSessionKey = store.conv.sessionKey
                        draft = store.misc.draft
                    }

                // 对话操作（M3.4）：点击即弹菜单（真机反馈④——onTapGesture 会吃掉点击、
                // contextMenu 只认长按，只剩触感没有弹框；Menu 是原生点击弹出口）。
                Menu {
                    composerActions
                } label: {
                    Circle()
                        .fill(c.surfaceTertiary)
                        .frame(width: 34, height: 34)
                        .overlay(
                            Image(systemName: "slider.horizontal.3")
                                .font(.system(size: 13))
                                .foregroundColor(c.textSecondary)
                        )
                }
                .buttonStyle(.plain)

                actionButton
            }
            .padding(.horizontal, 12)
            .padding(.top, 8)
            .padding(.bottom, 8)
        }
    }

    // 单按钮三态（M4 反馈①）：运行中且空 = 终止；有内容 = 发送（运行中也发，网关排队）；
    // 空+空闲 = 禁用。
    @ViewBuilder
    private var actionButton: some View {
        if store.conv.runActive && draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            Button {
                Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
                store.bridge.stopRun()
            } label: {
                RoundedRectangle(cornerRadius: 3)
                    .fill(c.paper)
                    .frame(width: 12, height: 12)
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(c.dangerText))
            }
            .buttonStyle(.plain)
            .padding(.bottom, 6)
        } else {
            let enabled = !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            Button {
                guard enabled else { return }
                Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
                store.bridge.setDraft(text: draft)
                store.bridge.sendDraft()
                draft = ""
                focused = false
            } label: {
                Image(systemName: "arrow.up")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(enabled ? c.paper : c.textDisabled)
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(enabled ? c.inverse : c.surfaceTertiary))
            }
            .buttonStyle(.plain)
            .disabled(!enabled)
            .padding(.bottom, 6)
        }
    }

    @ViewBuilder
    private var composerActions: some View {
        Button {
            store.bridge.stopByCommand()
        } label: {
            Label("终止 (/stop)", systemImage: "xmark")
        }
        Button {
            if store.timeline.agentId != nil {
                store.bridge.createSessionInConversation()
            } else {
                store.bridge.startFresh()
            }
        } label: {
            Label("新开会话", systemImage: "arrow.counterclockwise")
        }
        Button {
            store.bridge.compactContext()
        } label: {
            Label("压缩上下文", systemImage: "arrow.down.right.and.arrow.up.left")
        }
        Button {
            store.bridge.sendAgentStatus()
        } label: {
            Label("当前 agent 状态", systemImage: "slider.horizontal.3")
        }
    }
}

/// 引用条（M3.4 QuoteBar）：被引用消息随下一条发出。
struct QuoteBarView: View {
    @Environment(\.inkColors) private var c
    @State private var store = AppStore.shared
    let quoted: String

    var body: some View {
        HStack(spacing: 8) {
            Rectangle()
                .fill(c.inverse)
                .frame(width: 3, height: 28)
                .clipShape(RoundedRectangle(cornerRadius: 2))
            Text(quoted.split(separator: "\n").first.map(String.init) ?? "")
                .ink(.textSm)
                .foregroundColor(c.textTertiary)
                .lineLimit(2)
            Spacer(minLength: 0)
            Button {
                store.bridge.clearQuote()
            } label: {
                Text("取消引用").ink(.textXs).foregroundColor(c.infoText)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: InkShape.md).fill(c.surfaceTertiary))
        .padding(.horizontal, 12)
        .padding(.top, 4)
    }
}
