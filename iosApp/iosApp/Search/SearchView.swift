import SwiftUI
import composeApp

/// 搜索 tab（M3.6）：上=客户端会话即时过滤（离线可用）；下=服务端全文（400ms 去抖）。
struct SearchView: View {
    @Environment(\.inkColors) private var c
    @Environment(NavCoordinator.self) private var nav
    @State private var store = AppStore.shared
    @State private var query = ""
    @State private var debounceTask: Task<Void, Never>?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("搜索").ink(.headingMd).foregroundColor(c.ink)
                .padding(.leading, 16).padding(.top, 12).padding(.bottom, 8)

            searchField
                .padding(.horizontal, 16)

            let local = store.filterSessions(query)
            if query.trimmingCharacters(in: .whitespaces).isEmpty {
                EmptyStateView(icon: "magnifyingglass",
                               title: "搜索会话与消息",
                               subtitle: "会话名即时过滤；联网后可全文搜历史消息")
                Spacer()
            } else if local.isEmpty && store.misc.searchHits.isEmpty && !store.misc.searching {
                EmptyStateView(icon: "magnifyingglass", title: "无匹配结果", subtitle: "换个关键词试试")
                Spacer()
            } else {
                List {
                    if !local.isEmpty {
                        Section {
                            ForEach(local, id: \.id) { s in
                                sessionRow(s)
                            }
                        } header: {
                            SectionHeader(text: "会话").listRowInsets(EdgeInsets())
                        }
                    }
                    if !store.misc.searchHits.isEmpty {
                        Section {
                            ForEach(Array(store.misc.searchHits.enumerated()), id: \.offset) { _, hit in
                                hitRow(hit)
                            }
                        } header: {
                            HStack(spacing: 8) {
                                SectionHeader(text: "消息全文").listRowInsets(EdgeInsets())
                                if store.misc.searching { ProgressView().scaleEffect(0.7) }
                            }
                            .listRowInsets(EdgeInsets())
                        }
                    } else if store.misc.searching {
                        HStack(spacing: 8) {
                            ProgressView().scaleEffect(0.7)
                            Text("搜索消息全文…").ink(.textSm).foregroundColor(c.textTertiary)
                        }
                        .listRowSeparator(.hidden)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .background(c.paper)
        .navigationBarHidden(true)
        .onChange(of: query) { _, newValue in
            // 服务端搜索去抖 400ms（清空/过短立即收）
            debounceTask?.cancel()
            let q = newValue.trimmingCharacters(in: .whitespaces)
            if q.count < 2 {
                store.bridge.searchServer(query: "")
            } else {
                debounceTask = Task {
                    try? await Task.sleep(nanoseconds: 400_000_000)
                    if !Task.isCancelled { store.bridge.searchServer(query: q) }
                }
            }
        }
    }

    private var searchField: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 13))
                .foregroundColor(c.textTertiary)
            TextField("会话名或消息关键词", text: $query)
                .ink(.textMd)
                .foregroundColor(c.ink)
                .autocorrectionDisabled()
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 13))
                        .foregroundColor(c.textTertiary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 10).fill(c.surfaceTertiary))
    }

    private func sessionRow(_ s: BridgeSession) -> some View {
        Button {
            nav.push(.chat(sessionKey: s.id, readOnly: false))
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    if s.pinned {
                        Image(systemName: "pin.fill").font(.system(size: 9)).foregroundColor(c.textTertiary)
                    }
                    Text(s.title ?? s.id).ink(.textLg).foregroundColor(c.ink)
                        .fontWeight(s.unread > 0 ? .semibold : .regular)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    Text(formatRelativeTime(s.lastActiveAtMs)).ink(.textXs).foregroundColor(c.textTertiary)
                }
                Text(s.preview?.isEmpty == false ? s.preview! : s.id)
                    .ink(.textSm).foregroundColor(c.textTertiary).lineLimit(1)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(c.paper)
        .listRowSeparator(.hidden)
    }

    private func hitRow(_ hit: BridgeSearchHit) -> some View {
        Button {
            nav.push(.chat(sessionKey: hit.sessionKey, readOnly: false))
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(store.session(hit.sessionKey)?.title ?? hit.sessionKey)
                        .ink(.textLg).foregroundColor(c.ink).lineLimit(1)
                    Spacer()
                    Text(formatMessageTime(hit.timestampMs)).ink(.textXs).foregroundColor(c.textTertiary)
                }
                Text(hit.snippet)
                    .ink(.textSm).foregroundColor(c.textTertiary)
                    .lineLimit(3)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(c.paper)
        .listRowSeparator(.hidden)
    }
}

/// 空态（图标 + 标题 + 副文）。
struct EmptyStateView: View {
    @Environment(\.inkColors) private var c
    let icon: String
    let title: String
    var subtitle: String? = nil

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 32))
                .foregroundColor(c.textTertiary)
                .padding(.bottom, 2)
            Text(title).ink(.headingSm).foregroundColor(c.ink)
            if let subtitle {
                Text(subtitle).ink(.textSm).foregroundColor(c.textTertiary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 32)
        .padding(.vertical, 48)
    }
}
