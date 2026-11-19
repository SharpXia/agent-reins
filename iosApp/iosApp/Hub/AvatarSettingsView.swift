import SwiftUI

// MARK: - 机器人形象设置子页（v1.5.0 个性化形象轮；端点 #30/#31 /v1/me/avatar-profile）

/// 形象设置：顶部 96pt 实时预览（≥40pt 避 compact 折算表，RobotAvatar.swift:172）+ 8 款预设
/// 网格（44pt 缩略、**motionEnabled=false 强制静态**——AvatarIdleMotion 常驻循环多实例性能，
/// LazyVStack 重播教训 :199-201）+ 十色横排圆点（strong 色）+ 动效档 segmented（关闭/标准）
/// +「恢复默认」。保存 = **先 PUT 成功再更新本地**（低频不敏感，免回滚复杂度），失败显式
/// 错误不排队；编辑期间收到远端更新不出发表单，顶栏提示「其他设备已更新形象」+ 一键拉取
/// （设计稿 §syncMechanism 读路径④）。
struct AvatarSettingsView: View {
    @State private var hub = HubStore.shared
    @Environment(\.colorScheme) private var colorScheme
    @State private var draft: AvatarProfileModel?
    @State private var saving = false
    @State private var saveError: String?

    private var columns: [GridItem] = Array(repeating: GridItem(.flexible(), spacing: 12), count: 4)

    /// 呈现档：编辑草稿优先（表单即时呈现所选），否则 store 权威档。
    private var shown: AvatarProfileModel { draft ?? hub.avatar.profile }

    var body: some View {
        List {
            Section("实时预览") {
                HStack(spacing: 16) {
                    RobotAvatar(
                        palette: shown.paletteName,
                        expression: .calm,
                        size: 96,
                        faceFactors: shown.faceFactors,
                        motionEnabled: shown.motionEnabled, // 动效档 standard 才呼吸（A6）
                    )
                    VStack(alignment: .leading, spacing: 4) {
                        Text("造型 · \(presetName(for: shown))").font(.body)
                        Text("配色 · \(shown.paletteId)").font(.caption).foregroundStyle(.secondary)
                        Text("动效 · \(shown.motionEnabled ? "标准" : "关闭")")
                            .font(.caption).foregroundStyle(.secondary)
                        if saving {
                            HStack(spacing: 6) {
                                ProgressView()
                                Text("保存中…").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                .padding(.vertical, 4)
            }

            // 编辑冲突提示（远端更新在途、未覆盖表单）：一键拉取收敛（读路径④）
            if hub.avatar.remotePending != nil {
                Section {
                    HStack {
                        Text("其他设备已更新形象")
                            .font(.callout)
                            .foregroundStyle(.orange)
                        Spacer()
                        Button("拉取") {
                            draft = nil
                            saveError = nil
                            hub.avatar.adoptRemotePending()
                        }
                    }
                }
            }

            Section("造型") {
                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(AvatarPresetCatalog.all) { preset in
                        presetCell(preset)
                    }
                }
                .padding(.vertical, 4)
                Text("挑选预设会同时应用推荐配色；圆点可单独改色。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("配色") {
                HStack(spacing: 12) {
                    ForEach(AgentTintName.allCases, id: \.rawValue) { name in
                        paletteDot(name)
                    }
                }
                .padding(.vertical, 4)
            }

            Section("动效") {
                Picker("动效档", selection: motionBinding) {
                    Text("关闭").tag(false)
                    Text("标准").tag(true)
                }
                .pickerStyle(.segmented)
                .disabled(saving)
                Text("关闭 = 静止（仅入场淡入与表情即时切换）；标准 = 待机呼吸与切换过渡动画。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section {
                Button("恢复默认") {
                    select(AvatarProfileModel(
                        faceFactors: AvatarProfileModel.defaultFactors,
                        paletteId: AgentTintName.GRAY.rawValue,
                        motionLevel: "standard",
                        updatedAt: shown.updatedAt,
                    ))
                }
                .disabled(saving)
            }

            if let saveError {
                Section {
                    Text("保存失败：\(saveError)（可重试）")
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
        }
        .navigationTitle("机器人形象")
    }

    // MARK: - 单元

    /// 预设单元格：44pt 缩略（motionEnabled=false 静态渲染）+ 中文名；选中 = 规范 face 串相等。
    private func presetCell(_ preset: AvatarPreset) -> some View {
        VStack(spacing: 4) {
            RobotAvatar(
                palette: preset.recommendedPalette,
                expression: .calm,
                size: 44,
                faceFactors: preset.faceFactors,
                motionEnabled: false,
            )
            Text(preset.zhName)
                .font(.caption2)
                .foregroundStyle(preset.faceKey == shown.faceKey ? Color.accentColor : Color.secondary)
        }
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .onTapGesture {
            // 预设 = 造型 + 推荐配色一并选（推荐语义；圆点可随后单独改色，两端同口径）
            select(AvatarProfileModel(
                faceFactors: preset.faceFactors,
                paletteId: preset.recommendedPalette.rawValue,
                motionLevel: shown.motionLevel,
                updatedAt: shown.updatedAt,
            ))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("预设\(preset.zhName)")
    }

    /// 十色圆点（strong 色，明暗随系统；选中描边）。
    private func paletteDot(_ name: AgentTintName) -> some View {
        let strong = agentTint(name, isDark: colorScheme == .dark).strong
        let selected = name == shown.paletteName
        return Circle()
            .fill(strong)
            .frame(width: 26, height: 26)
            .overlay(
                Circle().stroke(
                    selected ? Color.accentColor : Color.secondary.opacity(0.4),
                    lineWidth: selected ? 2.5 : 1,
                )
            )
            .onTapGesture {
                select(AvatarProfileModel(
                    faceFactors: shown.faceFactors,
                    paletteId: name.rawValue,
                    motionLevel: shown.motionLevel,
                    updatedAt: shown.updatedAt,
                ))
            }
            .accessibilityLabel("配色\(name.rawValue)")
    }

    /// 动效档 segmented 绑定（standard = 开；off 唯一关值，未知回落 standard）。
    private var motionBinding: Binding<Bool> {
        Binding(
            get: { shown.motionEnabled },
            set: { on in
                select(AvatarProfileModel(
                    faceFactors: shown.faceFactors,
                    paletteId: shown.paletteId,
                    motionLevel: on ? "standard" : "off",
                    updatedAt: shown.updatedAt,
                ))
            },
        )
    }

    private func presetName(for profile: AvatarProfileModel) -> String {
        AvatarPresetCatalog.all.first { $0.faceKey == profile.faceKey }?.zhName ?? "自定义"
    }

    // MARK: - 保存流（先 PUT 成功再更新本地；失败显式错误不排队）

    private func select(_ newProfile: AvatarProfileModel) {
        guard !saving else { return }
        hub.avatar.isEditing = true // 草稿在途：远端更新让位表单（remotePending 提示）
        draft = newProfile
        saving = true
        saveError = nil
        hub.saveAvatarProfile(newProfile) { ok, error in
            saving = false
            hub.avatar.isEditing = false
            if ok {
                draft = nil            // 权威档已经 applyRemote(force:) 落 store
                hub.avatar.clearRemotePending()
            } else {
                draft = nil            // 失败回滚草稿（低频不敏感，免回滚复杂度——不排队）
                saveError = error ?? "保存失败"
            }
        }
    }
}
