import SwiftUI

/// Hub 登录页（P1 两步流：登录 → 设备注册）。口令只随登录请求体发出，提交后立即清空，不落本地。
struct LoginView: View {
    @Environment(\.inkColors) private var c
    @State private var hub = HubStore.shared
    @State private var baseUrl: String = HubStore.shared.baseUrl
    @State private var username = ""
    @State private var password = ""

    private var working: Bool { hub.kind == "working" }

    private var canSubmit: Bool {
        !working && !baseUrl.trimmingCharacters(in: .whitespaces).isEmpty
            && !username.isEmpty && !password.isEmpty
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("http://<HUB-IP>:8080/v1", text: $baseUrl)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .accessibilityIdentifier("hub.baseUrl")
                } header: {
                    Text("Hub 服务器")
                } footer: {
                    Text("开发期为明文 http（仅 Debug 包放行），正式部署切 https 后自动失效。")
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
                        hub.login(baseUrl: baseUrl, username: username, password: password)
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
}
