import SwiftUI
import composeApp // UiConversation（KMP 桥数据类；HubViews.swift 同 import）

// MARK: - 会话级换形象 sheet（v1.5.2 会话覆盖档轮；端点 #32 PUT / #33 DELETE
// /v1/conversations/{id}/avatar-profile；交互语言镜像 AvatarSettingsView 但作用域是会话级）
//
// 入口：聊天页头角色点按（HubChatView principal）+ 会话行右键/长按菜单「换形象」
//（HubHomeView / MacSplitRoot 两挂点同文案）。内容：标题带会话名 + 8 款造型预设网格 +
// 🎲 随机换一个（连摇必换——排除当前展示键，draft 感知）+ 十色圆点。
//
// 与用户档设置页（AvatarSettingsView）两处刻意差异：
// ① 挑选不即选即存——预设/随机/色点三路只进本地预览草稿，「用这个形象」= PUT 先成功再关
//    sheet（落表单源在 shared 流经订阅推回，本地零二次写），失败显式错误不关可重试；
// ② 无动效档控制——动效仍是全局形象档（PUT 的 AvatarProfile 需整值，motionLevel 从
//    hub.avatar.profile 透传填入，渲染侧不消费它）。
// 「恢复默认」= DELETE（204 幂等终态）：清覆盖回落会话 seed 派生，成功后关；「放弃」清预览
// 零扰动（现网形象不变，sheet 可继续调或完成钮/下滑随时关闭）。远端在途更新（#34 显式刷新
// 或实时帧）落地后清陈旧预览（AvatarSettingsView onChange 先例同款防遮蔽）。

struct ConversationAvatarSheet: View {
    let conversation: UiConversation
    /// 显式 init：private @State/@Environment 存储属性会把隐式 memberwise init 压成 private
    ///（beta-0.5.24 CI Release 归档实证 :330 inaccessible），调用方在 HubViews/MacSplitRoot。
    init(conversation: UiConversation) {
        self.conversation = conversation
    }
    @State private var hub = HubStore.shared
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.inkColors) private var ink
    @Environment(\.dismiss) private var dismiss
    /// 预览草稿（UI 本地态不入 store）：预设/🎲/色点三路只写这里；确认 PUT 成功才落。
    @State private var previewDraft: AvatarProfileModel?
    @State private var saving = false
    @State private var clearing = false
    @State private var actionError: String?

    private var columns: [GridItem] = Array(repeating: GridItem(.flexible(), spacing: 12), count: 4)

    // MARK: - 呈现档

    /// 会话现权威形象：覆盖档键在场 = 覆盖档；键缺席/未水合 = seed 派生（**仅作 sheet 内
    /// 展示基线与「连摇必换」排除键**——渲染链无覆盖仍走 seed 派生，此处派生值不是「造默认
    /// 档」不入任何存储；motionLevel 透传全局档只为 PUT 整值）。
    private var authoritative: AvatarProfileModel {
        if let ov = hub.conversationAvatarOverride(for: conversation.conversationId) { return ov }
        let seed = botSeedFor(conversation.conversationId)
        return AvatarProfileModel(
            faceFactors: AvatarPresetCatalog.all[min(max(seed.bodyIndex, 0), AvatarPresetCatalog.all.count - 1)].faceFactors,
            paletteId: AgentTintName.allCases[min(max(seed.paletteIndex, 0), AgentTintName.allCases.count - 1)].rawValue,
            motionLevel: hub.avatar.profile.motionLevel,
            updatedAt: AvatarProfileModel.epoch, // 从未设置哨兵；PUT 请求体不入 updatedAt
        )
    }

    /// 呈现档：预览草稿 > 会话现权威形象（预览/网格/色点高亮随 shown，随机轮同口径）。
    private var shown: AvatarProfileModel { previewDraft ?? authoritative }

    /// 覆盖在档（页头/行渲染正吃覆盖档与否；状态行文案用）。
    private var hasOverride: Bool {
        hub.conversationAvatarOverride(for: conversation.conversationId) != nil
    }

    /// 会话名（HubConversationRow.title 同链：label 空 = agentId 兜底 = 「会话」）。
    private var title: String {
        if !conversation.label.isEmpty { return conversation.label }
        return conversation.agentId.isEmpty ? "会话" : conversation.agentId
    }

    var body: some View {
        NavigationStack {
            List {
                previewSection
                shapeSection
                paletteSection
                actionSection
                if let actionError {
                    Section {
                        Text("保存失败：\(actionError)（可重试）")
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(title) // 标题带会话名
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    // Esc/完成关 sheet（NewConversationSheet 先例）：草稿是本地态，随时关闭
                    // 零扰动（在途 PUT 不拦——成功落表经 shared 流照收，dismiss 已关为 no-op）。
                    Button("完成") { dismiss() }
                        .keyboardShortcut(.cancelAction)
                }
            }
        }
        .presentationDetents([.medium, .large])
        // #34 显式刷新（best-effort：登录/sync.ready 已自动水合，此处兜长驻进程陈旧窗口；
        // 失败静默——观察面仍以 shared 流为准）。
        .onAppear { hub.refreshConversationAvatarProfiles() }
        // 远端收敛（#34 刷新/实时帧/他端 PUT）落地后清陈旧预览（AvatarSettingsView onChange
        // 先例同款：防陈旧草稿遮蔽权威档、确认旧形覆盖远端新档）；AvatarProfileModel Equatable。
        .onChange(of: authoritative) { _, _ in
            previewDraft = nil
        }
    }

    // MARK: - 节

    private var previewSection: some View {
        Section("实时预览") {
            HStack(spacing: 16) {
                BotCharacterView(
                    seed: botSeedFor(conversation.conversationId), // 会话身份：眼形/姿态/眨音/动效相位恒随 seed
                    state: .idle,
                    size: 96,
                    motionEnabled: hub.avatar.profile.motionEnabled, // 动效总开关仍全局形象档（本 sheet 不做动效控制）
                    interactive: false,
                    faceFactorsOverride: shown.faceFactors, // 覆盖/预览 12 值造型（seed bodyIndex 让位）
                    paletteIndexOverride: AgentTintName.allCases.firstIndex(of: shown.paletteName),
                )
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.body)
                    Text(hasOverride ? "本会话已自定义 · 跨设备同步" : "默认形象 · 按会话派生")
                        .font(.caption).foregroundStyle(.secondary)
                    Text("动效跟随全局形象档（\(hub.avatar.profile.motionEnabled ? "标准" : "关闭")）")
                        .font(.caption).foregroundStyle(.secondary)
                    if previewDraft != nil {
                        // 预览标（形象随机轮「预览中·未保存」标同语义）：明示当前预览未落档
                        Text("预览中 · 未保存").font(.caption).foregroundStyle(.orange)
                    }
                    if saving || clearing {
                        HStack(spacing: 6) {
                            ProgressView()
                            Text(clearing ? "清除中…" : "保存中…").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .padding(.vertical, 4)
        }
    }

    private var shapeSection: some View {
        Section("造型") {
            // 随机换形象（镜像 AvatarSettingsView 🎲）：排除键 = shown.faceKey（draft 感知，
            // 连摇必换形）；色继承当前展示；结果只进 previewDraft 预览零 PUT。
            HStack {
                Text("随机换形象").font(.callout)
                Spacer()
                Button("🎲 换一个") {
                    let base = shown
                    let preset = randomPresetExcluding(base.faceKey)
                    previewDraft = AvatarProfileModel(
                        faceFactors: preset.faceFactors,
                        paletteId: base.paletteId,
                        motionLevel: hub.avatar.profile.motionLevel, // 动效恒全局档透传（PUT 整值形状，渲染不消费）
                        updatedAt: base.updatedAt,
                    )
                    actionError = nil
                }
                .disabled(saving || clearing)
            }
            LazyVGrid(columns: columns, spacing: 12) {
                ForEach(AvatarPresetCatalog.all) { preset in
                    presetCell(preset)
                }
            }
            .padding(.vertical, 4)
            Text("挑选只改预览；点「用这个形象」确认后本会话生效（其他会话不受影响）。")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    /// 预设单元格（44pt 缩略 motionEnabled=false 强制静态——AvatarIdleMotion 常驻循环多实例
    /// 纪律沿用；种子取会话身份 = 缩略即本会话真实观感，眼形/姿态随 seed）。选中 = 规范
    /// face 串相等（与 shown 比较，预览草稿感知）。
    private func presetCell(_ preset: AvatarPreset) -> some View {
        VStack(spacing: 4) {
            BotCharacterView(
                seed: botSeedFor(conversation.conversationId),
                state: .idle,
                size: 44,
                motionEnabled: false,
                interactive: false,
                faceFactorsOverride: preset.faceFactors,
                paletteIndexOverride: AgentTintName.allCases.firstIndex(of: preset.recommendedPalette),
            )
            Text(preset.zhName)
                .font(.caption2)
                .foregroundStyle(preset.faceKey == shown.faceKey ? ink.accent : Color.secondary)
        }
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .onTapGesture {
            guard !saving, !clearing else { return }
            // 预设 = 造型 + 推荐配色一并入草稿（推荐语义；圆点可随后单独改色，与用户档同口径）。
            previewDraft = AvatarProfileModel(
                faceFactors: preset.faceFactors,
                paletteId: preset.recommendedPalette.rawValue,
                motionLevel: hub.avatar.profile.motionLevel, // 动效恒全局档透传
                updatedAt: shown.updatedAt,
            )
            actionError = nil
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("预设\(preset.zhName)")
    }

    private var paletteSection: some View {
        Section("配色") {
            HStack(spacing: 12) {
                ForEach(AgentTintName.allCases, id: \.rawValue) { name in
                    paletteDot(name)
                }
            }
            .padding(.vertical, 4)
        }
    }

    /// 十色圆点（strong 色随系统明暗；选中描边令牌同 AvatarSettingsView）。本 sheet 无即选
    /// 即存——色点并入预览草稿（给当前展示形配色，随「用这个形象」一次 PUT）。
    private func paletteDot(_ name: AgentTintName) -> some View {
        let strong = agentTint(name, isDark: colorScheme == .dark).strong
        let selected = name == shown.paletteName
        return Circle()
            .fill(strong)
            .frame(width: 26, height: 26)
            .overlay(
                Circle().stroke(
                    selected ? ink.accent : Color.secondary.opacity(0.4),
                    lineWidth: selected ? 2.5 : 1,
                )
            )
            .onTapGesture {
                guard !saving, !clearing else { return }
                previewDraft = AvatarProfileModel(
                    faceFactors: shown.faceFactors,
                    paletteId: name.rawValue,
                    motionLevel: hub.avatar.profile.motionLevel, // 动效恒全局档透传
                    updatedAt: shown.updatedAt,
                )
                actionError = nil
            }
            .accessibilityLabel("配色\(name.rawValue)")
    }

    private var actionSection: some View {
        Section {
            Button("用这个形象") { confirmDraft() }
                .disabled(previewDraft == nil || saving || clearing)
            Button("恢复默认") { restoreDefault() }
                .disabled(saving || clearing)
            Button("放弃") {
                previewDraft = nil // 清预览零扰动：现网形象不变，可继续调或完成钮关闭
                actionError = nil
            }
                .disabled(previewDraft == nil || saving || clearing)
        } footer: {
            Text("「恢复默认」清除本会话自定义，回到按会话派生的默认形象。")
        }
    }

    // MARK: - 动作流（先 PUT/DELETE 成功再关 sheet；失败显式错误不关不排队）

    /// 确认（端点 #32）：PUT 成功 → 权威档经 shared 流落表推回（订阅收帧），关 sheet；
    /// 失败 → 显式错误不关，可重试。
    private func confirmDraft() {
        guard let target = previewDraft, !saving, !clearing else { return }
        saving = true
        actionError = nil
        hub.saveConversationAvatarProfile(conversation.conversationId, target) { ok, error in
            saving = false
            if ok {
                previewDraft = nil
                dismiss()
            } else {
                actionError = error ?? "保存失败"
            }
        }
    }

    /// 恢复默认（端点 #33，204 幂等终态）：清覆盖回落会话 seed 派生，成功后关 sheet；
    /// 失败 → 显式错误不关。无覆盖重复 DELETE 亦 204，重复清除无害。
    private func restoreDefault() {
        guard !saving, !clearing else { return }
        clearing = true
        actionError = nil
        hub.clearConversationAvatarProfile(conversation.conversationId) { ok, error in
            clearing = false
            if ok {
                previewDraft = nil
                dismiss()
            } else {
                actionError = error ?? "清除失败"
            }
        }
    }
}
