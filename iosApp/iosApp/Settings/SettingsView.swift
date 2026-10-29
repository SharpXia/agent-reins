import SwiftUI
import composeApp

/// 设置 tab：连接/外观/提示音/触感/审批/调试。
struct SettingsView: View {
    @Environment(\.inkColors) private var c
    @Environment(NavCoordinator.self) private var nav
    @State private var store = AppStore.shared
    @State private var soundOn = true
    @State private var hapticsOn = true
    @State private var approvalsOn = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("设置").ink(.headingMd).foregroundColor(c.ink)
                    .padding(.leading, 16).padding(.top, 12).padding(.bottom, 8)

                SectionHeader(text: "连接")
                SandCard {
                    SandRow(
                        connTitle,
                        subtitle: store.conn.connectedUrl
                            ?? (store.conn.url.isEmpty ? "未配置网关地址" : store.conn.url)
                    ) {
                        nav.push(.connection)
                    }
                    .padding(.horizontal, 0)
                }
                .padding(.horizontal, 16)

                SectionHeader(text: "外观")
                SandCard {
                    ForEach(Array(["SYSTEM", "LIGHT", "DARK"].enumerated()), id: \.element) { index, mode in
                        themeRow(mode: mode, label: themeLabel(mode))
                        if index < 2 { SandDivider(indent: 24) }
                    }
                }
                .padding(.horizontal, 16)

                SectionHeader(text: "提示音")
                SandCard {
                    SandToggleRow(
                        title: "音效",
                        subtitle: "关闭后所有事件提示音静默（含连接与审批音），触感不受影响",
                        checked: $soundOn
                    ) { on in
                        store.bridge.toggleSound(enabled: on)
                    }
                    SandDivider(indent: 16)
                    SandRow("通知音",
                            subtitle: store.misc.notificationSoundName?.label ?? "无") {
                        nav.push(.sounds)
                    }
                }
                .padding(.horizontal, 16)

                SectionHeader(text: "触感")
                SandCard {
                    SandToggleRow(
                        title: "触感反馈",
                        subtitle: "发送、滑动操作与确认时的轻微振动（iOS）",
                        checked: $hapticsOn
                    ) { on in
                        store.bridge.toggleHaptics(enabled: on)
                    }
                }
                .padding(.horizontal, 16)

                SectionHeader(text: "审批")
                SandCard {
                    SandToggleRow(
                        title: "审批面板",
                        subtitle: "开启后连接会申请 approvals 权限（网关 owner 需重新批准一次设备）",
                        checked: $approvalsOn
                    ) { on in
                        store.bridge.toggleApprovals(enabled: on)
                    }
                }
                .padding(.horizontal, 16)

                SectionHeader(text: "调试")
                SandCard {
                    SandRow("设计令牌", subtitle: "墨水系令牌与组件样本页") {
                        nav.push(.themeDemo)
                    }
                }
                .padding(.horizontal, 16)

                Text("AgentReins · OpenClaw 移动端")
                    .ink(.textXs)
                    .foregroundColor(c.textDisabled)
                    .padding(.horizontal, 16)
                    .padding(.top, 24)
                    .padding(.bottom, 32)
            }
        }
        .background(c.paper)
        .navigationBarHidden(true)
        .onAppear {
            soundOn = store.misc.soundEnabled
            hapticsOn = store.misc.hapticsEnabled
            approvalsOn = store.conn.approvalsEnabled
        }
    }

    private var connTitle: String {
        switch store.conn.kind {
        case "connected": return "已连接"
        case "connecting": return "连接中…"
        case "pairing": return "等待设备批准"
        case "error": return "连接错误"
        default: return "未连接"
        }
    }

    private func themeLabel(_ mode: String) -> String {
        switch mode {
        case "LIGHT": return "浅色"
        case "DARK": return "深色"
        default: return "跟随系统"
        }
    }

    private func themeRow(mode: String, label: String) -> some View {
        Button {
            store.bridge.applyTheme(mode: mode)
        } label: {
            HStack {
                Image(systemName: store.misc.themeMode == mode ? "circle.inset.filled" : "circle")
                    .font(.system(size: 17))
                    .foregroundColor(store.misc.themeMode == mode ? c.ink : c.textTertiary)
                Text(label).ink(.textLg).foregroundColor(c.ink)
                Spacer()
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// 提示音子页：5 件套选择（点击即选中并试听）+ 静音。
struct SoundsView: View {
    @Environment(\.inkColors) private var c
    @Environment(\.dismiss) private var dismiss
    @State private var store = AppStore.shared

    private var options: [AppSoundName?] {
        [nil] + AppSoundName.allCases
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 4) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "chevron.backward")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundColor(c.ink)
                            .frame(width: 40, height: 40)
                    }
                    .buttonStyle(.plain)
                    Text("提示音").ink(.headingMd).foregroundColor(c.ink)
                    Spacer()
                }
                .padding(.horizontal, 8)

                SectionHeader(text: "新回复提示音（点击选择并试听）")
                SandCard {
                    ForEach(Array(options.enumerated()), id: \.offset) { index, sound in
                        soundRow(sound)
                        if index < options.count - 1 { SandDivider(indent: 16) }
                    }
                }
                .padding(.horizontal, 16)

                SectionHeader(text: "说明")
                SandCard {
                    Text("「新回复」指非当前会话收到 Agent 消息；审批请求固定使用上行风铃，" +
                         "连接成功固定使用 Blip，不受本页选择影响。设置页的音效总开关关闭后，" +
                         "本页选择保留、仅试听可出声（明确点按不计入事件音）。")
                        .ink(.textXs)
                        .foregroundColor(c.textTertiary)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 11)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 16)
            }
        }
        .background(c.paper)
        .navigationBarHidden(true)
    }

    private func soundRow(_ sound: AppSoundName?) -> some View {
        let selected = store.misc.notificationSoundName?.rawValue == sound?.rawValue
            || (sound == nil && store.misc.notificationSound == "none")
        return Button {
            if let sound {
                Feedback.play(sound: sound)
                store.bridge.setNotificationSound(name: sound.rawValue)
            } else {
                store.bridge.setNotificationSound(name: "none")
            }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(sound?.label ?? "无").ink(.textLg).foregroundColor(c.ink)
                    Text(sound?.subtitle ?? "静音：不播放任何提示音")
                        .ink(.textXs).foregroundColor(c.textTertiary)
                }
                Spacer()
                if selected {
                    Image(systemName: "checkmark").font(.system(size: 13)).foregroundColor(c.ink)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// 连接配置页：状态徽标 + 表单 + 扫码配对 + 连接/断开。
struct ConnectionView: View {
    @Environment(\.inkColors) private var c
    @Environment(\.dismiss) private var dismiss
    @State private var store = AppStore.shared
    var showsBack: Bool = false

    @State private var url = ""
    @State private var apiKey = ""
    @State private var fallbackRaw = ""
    @State private var setupCode = ""
    @State private var showScanner = false
    @State private var loadedSnapshot = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if showsBack {
                HStack(spacing: 4) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "chevron.backward")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundColor(c.ink)
                            .frame(width: 36, height: 36)
                    }
                    .buttonStyle(.plain)
                    Text("连接 OpenClaw 网关").ink(.headingSm).foregroundColor(c.ink)
                    Spacer()
                }
                .padding(.horizontal, 8)
            } else {
                Text("连接 OpenClaw 网关").ink(.headingMd).foregroundColor(c.ink)
                    .padding(.horizontal, 16)
            }

            statusChip
                .padding(.horizontal, 16)

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    field("网关地址", text: $url, placeholder: "ws://<你的网关IP>:18789",
                          echo: { store.conn.url }) {
                        store.bridge.setUrl(url: $0)
                    }
                    field("Fallback 地址（逗号分隔，主地址失败时按序切换）", text: $fallbackRaw,
                          placeholder: "wss://gw.example.com:443",
                          echo: { store.conn.alternateUrls.joined(separator: ", ") }) {
                        store.bridge.setAlternateUrls(rawList: $0)
                    }
                    if store.conn.lastError != nil && store.conn.alternateUrls.isEmpty {
                        Text("⚠️ 主地址连接失败且未配置 Fallback——请填入可用的远程地址（wss://…），主地址不可达时会自动切换。不填则只试主地址。")
                            .ink(.textSm)
                            .foregroundColor(c.warningText)
                            .padding(10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(RoundedRectangle(cornerRadius: InkShape.md).fill(c.warningSubtle))
                            .padding(.horizontal, 16)
                    }
                    field("API Key", text: $apiKey, placeholder: "", secure: true,
                          echo: { store.conn.apiKey }) {
                        store.bridge.setApiKey(apiKey: $0)
                    }
                    if store.conn.hasPairedCredential {
                        Text("本机已配对过设备，Key 留空也可直连")
                            .ink(.textSm).foregroundColor(c.textTertiary)
                            .padding(.horizontal, 16)
                    }
                    Divider().overlay(c.borderSecondary).padding(.horizontal, 16)

                    HStack(spacing: 8) {
                        field("Setup Code（扫码或粘贴 oc-pair://…）", text: $setupCode, placeholder: "",
                              echo: { store.conn.setupCode }) {
                            store.bridge.setSetupCode(code: $0)
                        }
                        Button {
                            showScanner = true
                        } label: {
                            Text("扫码").ink(.textMd).foregroundColor(c.ink)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 12)
                        }
                        .buttonStyle(.plain)
                        .disabled(store.conn.busy)
                    }
                    Text("在网关机器执行 openclaw qr 生成配对二维码，扫码即连；若网关要求确认，批准后自动续连")
                        .ink(.textSm).foregroundColor(c.textTertiary)
                        .padding(.horizontal, 16)

                    connectButton
                        .padding(.top, 4)

                    if store.conn.kind == "connected" {
                        // connectedUrl 与 Connected 状态分两拍到达，瞬时 nil 回退主地址展示
                        let u = store.conn.connectedUrl ?? store.conn.url
                        Text("\(connectionSourceLabel(u)) · \(u)")
                            .ink(.textSm).foregroundColor(c.textTertiary)
                            .padding(.horizontal, 16)
                        Button {
                            store.bridge.disconnect()
                        } label: {
                            Text("断开")
                                .ink(.textLg)
                                .foregroundColor(c.ink)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 13)
                                .background(RoundedRectangle(cornerRadius: InkShape.xl).fill(c.surfaceTertiary))
                        }
                        .buttonStyle(.plain)
                        .padding(.horizontal, 16)
                    }

                    if let err = store.conn.lastError {
                        Text(err)
                            .ink(.textSm)
                            .foregroundColor(c.dangerText)
                            .padding(10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(RoundedRectangle(cornerRadius: InkShape.md).fill(c.dangerSubtle))
                            .padding(.horizontal, 16)
                    }

                    if store.conn.kind == "pairing" {
                        Text("等待 owner 批准设备配对" +
                             (store.conn.requestId.map { "（requestId=\($0)）" } ?? "") +
                             "——在网关机器执行 openclaw devices approve，批准后自动继续连接")
                            .ink(.textSm).foregroundColor(c.textTertiary)
                            .padding(.horizontal, 16)
                    }

                    Text("v1.0 连接界面仅支持 OpenClaw 网关；多后端适配层（AgentClient）已预留。")
                        .ink(.textSm).foregroundColor(c.textTertiary)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 24)
                }
                .padding(.top, 4)
            }
        }
        .background(c.paper)
        .navigationBarHidden(true)
        .onAppear {
            guard !loadedSnapshot else { return }
            loadedSnapshot = true
            url = store.conn.url
            apiKey = store.conn.apiKey
            fallbackRaw = store.conn.alternateUrls.joined(separator: ", ")
            setupCode = store.conn.setupCode
        }
        .sheet(isPresented: $showScanner) {
            QrScannerView { payload in
                showScanner = false
                if let payload { store.bridge.onQrScan(payload: payload) }
            }
        }
    }

    private var statusChip: some View {
        let (dot, label): (Color, String) = {
            switch store.conn.kind {
            case "connected": return (c.inverse, "已连接" + (store.conn.gatewayName.map { " · \($0)" } ?? ""))
            case "connecting": return (c.successText, "连接中…")
            case "pairing": return (c.successText, "等待批准…")
            case "error": return (c.dangerText, "连接失败" + (store.conn.message.map { "：\($0)" } ?? ""))
            default: return (c.textTertiary, "未连接")
            }
        }()
        return HStack(spacing: 6) {
            Circle().fill(dot).frame(width: 8, height: 8)
            Text(label).ink(.textSm).foregroundColor(c.ink)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Capsule().fill(c.surfaceTertiary))
    }

    /// 连接按钮三态（真机反馈：禁用态几乎不可见=「按钮消失」）——
    /// 忙碌=高对比禁用+spinner；可连=实底；不可连=半透明但清晰可辨。已连接态整颗隐藏（断开键接管）。
    @ViewBuilder
    private var connectButton: some View {
        let canConnect = !store.conn.setupCode.isEmpty ||
            (!store.conn.url.isEmpty && (!store.conn.apiKey.isEmpty || store.conn.hasPairedCredential))
        let connected = store.conn.kind == "connected"
        let connecting = store.conn.busy || store.conn.kind == "connecting" || store.conn.kind == "pairing"
        if !connected {
            let label: String = {
                if store.conn.kind == "pairing" { return "等待批准…" }
                if connecting { return "连接中…" }
                if !store.conn.setupCode.isEmpty { return "扫码配对" }
                return "连接"
            }()
            Button {
                Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
                store.bridge.connect()
            } label: {
                HStack(spacing: 8) {
                    if connecting {
                        ProgressView().tint(c.paper)
                    }
                    Text(label)
                        .ink(.textLg)
                        .foregroundColor(c.paper)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 13)
                .background(
                    RoundedRectangle(cornerRadius: InkShape.xl)
                        .fill(c.inverse.opacity(canConnect && !connecting ? 1.0 : (connecting ? 0.65 : 0.35)))
                )
            }
            .buttonStyle(.plain)
            .disabled(!canConnect || connecting)
            .padding(.horizontal, 16)
        }
    }

    /// 表单字段：onAppear 装载快照值时 onChange 会立即触发——回写 model 会把显示值
    /// 污染进候选列表（真机反馈：fallback 未配置却出现幽灵地址）。echoCheck 与模型
    /// 当前值相等时跳过回写。
    private func field(_ label: String, text: Binding<String>, placeholder: String,
                       secure: Bool = false, echo: @escaping () -> String,
                       onCommit: @escaping (String) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).ink(.textSm).foregroundColor(c.textTertiary)
                .padding(.horizontal, 16)
            Group {
                if secure {
                    SecureField(placeholder, text: text)
                } else {
                    TextField(placeholder, text: text)
                }
            }
            .ink(.textMd)
            .foregroundColor(c.ink)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: InkShape.md).fill(c.surfaceTertiary))
            .padding(.horizontal, 16)
            .onChange(of: text.wrappedValue) { _, v in
                if v == echo() { return }
                onCommit(v)
            }
        }
    }
}

/// 地址来源标签：ws=局域网明文，wss=远程 TLS。
func connectionSourceLabel(_ url: String) -> String {
    if url.hasPrefix("wss://") { return "远程（TLS）" }
    if url.hasPrefix("ws://") { return "局域网（明文）" }
    return "自定义"
}

/// 设计令牌样本页（ThemeDemo 精简版：色板/字阶/组件示意）。
struct ThemeDemoView: View {
    @Environment(\.inkColors) private var c
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 4) {
                    Button { dismiss() } label: {
                        Image(systemName: "chevron.backward")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundColor(c.ink)
                            .frame(width: 40, height: 40)
                    }
                    .buttonStyle(.plain)
                    Text("设计令牌").ink(.headingMd).foregroundColor(c.ink)
                    Spacer()
                }
                .padding(.horizontal, 8)

                SectionHeader(text: "功能色")
                SandCard {
                    ForEach(Array([("info", c.infoText, c.infoSubtle),
                                   ("danger", c.dangerText, c.dangerSubtle),
                                   ("success", c.successText, c.successSubtle),
                                   ("warning", c.warningText, c.warningSubtle),
                                   ("awaiting", c.awaiting, c.awaitingSubtle)].enumerated()),
                           id: \.offset) { _, item in
                        HStack {
                            Circle().fill(item.1).frame(width: 14, height: 14)
                            Text(item.0).ink(.textMd).foregroundColor(c.ink)
                            Spacer()
                            RoundedRectangle(cornerRadius: 4).fill(item.2).frame(width: 48, height: 20)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                    }
                }
                .padding(.horizontal, 16)

                SectionHeader(text: "Agent 身份色")
                SandCard {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 5), spacing: 12) {
                        ForEach(AgentTintName.allCases, id: \.self) { name in
                            let t = agentTint(name, isDark: c.isDark)
                            VStack(spacing: 4) {
                                Circle().fill(t.strong).frame(width: 26, height: 26)
                                Text(name.rawValue.lowercased()).ink(.textXs).foregroundColor(c.textTertiary)
                            }
                        }
                    }
                    .padding(16)
                }
                .padding(.horizontal, 16)

                SectionHeader(text: "字阶")
                SandCard {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("headingLg 26/32").ink(.headingLg).foregroundColor(c.ink)
                        Text("headingMd 22/28").ink(.headingMd).foregroundColor(c.ink)
                        Text("headingSm 17/24").ink(.headingSm).foregroundColor(c.ink)
                        Text("textLg 15/22 正文基准").ink(.textLg).foregroundColor(c.ink)
                        Text("textMd 14/20").ink(.textMd).foregroundColor(c.ink)
                        Text("textSm 13/18").ink(.textSm).foregroundColor(c.ink)
                        Text("textXs 12/16").ink(.textXs).foregroundColor(c.ink)
                        Text("mono 13 mono").font(.system(size: 13, design: .monospaced)).foregroundColor(c.ink)
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 16)
            }
            .padding(.bottom, 32)
        }
        .background(c.paper)
        .navigationBarHidden(true)
    }
}
