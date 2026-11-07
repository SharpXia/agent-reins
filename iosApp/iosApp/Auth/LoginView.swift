import SwiftUI

/// Hub 登录页（P1 两步流：登录 → 设备注册）。口令只随登录请求体发出，提交后立即清空，不落本地。
/// 地址免填：默认生产地址写死在包内（HubConfig.DEFAULT → HubStore.baseUrl 兜底），
/// 非默认部署经二级「服务器设置」页覆盖（2026-11-02 用户拍板）。
struct LoginView: View {
    @Environment(\.inkColors) private var c
    @State private var hub = HubStore.shared
    @State private var username = ""
    @State private var password = ""

    private var working: Bool { hub.kind == "working" }

    private var canSubmit: Bool {
        !working && !hub.baseUrl.isEmpty && !username.isEmpty && !password.isEmpty
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    NavigationLink {
                        ServerSettingsView()
                    } label: {
                        LabeledContent("服务器", value: serverHost)
                    }
                } footer: {
                    Text(hub.defaultBaseUrl.isEmpty
                         ? "本构建未内置默认地址，请在「服务器」里填写接入地址。"
                         : "默认接入官方 Hub，无需修改；自建或备用地址在「服务器」里填写。")
                }

                Section("账号") {
                    TextField("用户名", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .textContentType(.username)
                        .accessibilityIdentifier("hub.username")
                    SecureField("密码", text: $password)
                        .textContentType(.password)
                        .accessibilityIdentifier("hub.password")
                }

                if let msg = hub.message, !msg.isEmpty {
                    Section {
                        Text(msg)
                            .font(.footnote)
                            .foregroundColor(c.dangerText)
                            .textSelection(.enabled)
                    }
                }

                Section {
                    Button {
                        hub.login(baseUrl: hub.baseUrl, username: username, password: password)
                        password = ""
                    } label: {
                        HStack {
                            Spacer()
                            if working {
                                ProgressView()
                            } else {
                                Text("登录并注册此设备")
                            }
                            Spacer()
                        }
                    }
                    .disabled(!canSubmit)
                    .accessibilityIdentifier("hub.submit")
                }
            }
            .navigationTitle("登录 AgentReins Hub")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    /// 列表行只显示主机名，完整地址进二级页看。
    private var serverHost: String {
        guard !hub.baseUrl.isEmpty else { return "未设置" }
        return URL(string: hub.baseUrl)?.host ?? hub.baseUrl
    }
}

/// 服务器地址二级页：REST 地址 + 派生的 WSS 地址展示；保存非空即覆盖，恢复默认回写包内置地址。
struct ServerSettingsView: View {
    @State private var hub = HubStore.shared
    @State private var draft = ""

    /// 与 HubRuntime.wsUrlOf 同规则：https→wss（http→ws）+ /ws。
    private var derivedWsUrl: String {
        hub.baseUrl
            .replacingOccurrences(of: "https://", with: "wss://")
            .replacingOccurrences(of: "http://", with: "ws://") + "/ws"
    }

    var body: some View {
        Form {
            Section {
                TextField("https://<HUB-HOST>/v1", text: $draft)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .accessibilityIdentifier("hub.baseUrl")
            } header: {
                Text("REST 地址")
            } footer: {
                Text("含 /v1 前缀，须以 https:// 或 http:// 开头。明文 http 会被系统 ATS 拦截，仅联调专用包可用。")
            }

            Section("WSS 地址（自动派生）") {
                Text(derivedWsUrl)
                    .font(.footnote.monospaced())
                    .textSelection(.enabled)
                    .foregroundStyle(.secondary)
            }

            Section {
                Button("保存") {
                    let t = draft.trimmingCharacters(in: .whitespaces)
                    hub.baseUrl = t.isEmpty ? hub.defaultBaseUrl : t
                }
                .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
                Button("恢复默认", role: .destructive) {
                    guard !hub.defaultBaseUrl.isEmpty else { return }
                    hub.baseUrl = hub.defaultBaseUrl
                    draft = hub.defaultBaseUrl
                }
                .disabled(hub.defaultBaseUrl.isEmpty)
            }
        }
        .navigationTitle("服务器设置")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { draft = hub.baseUrl }
    }
}
