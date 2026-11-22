import SwiftUI

// MARK: - 形象档设置子页（v1.5.0 个性化形象轮；端点 #30/#31 /v1/me/avatar-profile；
// 角色系统批次5 换装 BotCharacter——档=个人默认造型+动效开关+预设宿主，seed=会话身份不上此页）

/// 形象设置：顶部 96pt 实时预览（≥40pt 避 compact 折算表；档 12 值+色经 override 注入）+ 8 款预设
/// 网格（44pt 缩略、**motionEnabled=false 强制静态**——常驻循环多实例性能纪律沿用）+ 十色横排
/// 圆点（strong 色）+ 动效档 segmented（关闭/标准）+「恢复默认」。保存 = **先 PUT 成功再更新本地**
///（低频不敏感，免回滚复杂度），失败显式错误不排队；编辑期间收到远端更新不出发表单，顶栏提示
///「其他设备已更新形象」+ 一键拉取（设计稿 §syncMechanism 读路径④）。
/// 形象随机轮 2026-11-21 增「随机换形象」：🎲 换一个 = 形状一键随机（排除 shown 档键——draft
/// 感知，「连摇必换形」）、色/动效继承 shown；随机结果只进 [randomDraft] 预览（预览/网格高亮
/// 随 shown），「用这个形象」走既有 select()（先 PUT 成功再落本地；iOS 乐观层=draft 在途保持
/// 目标形），除确认外任何路径（再摇/放弃/手动预设/动效/恢复默认/拉取/远端收敛）零 PUT 且清
/// draft——当前形象零扰动。改色口径（走查②修，双端同改）：色点 draft 在场 = 并入随机草稿
///（给预览形配色，随确认一次 PUT）、无 draft = 既有即选即存。
/// DEBUG 专属「状态走查」节：六态 × 24/32/36/40/96pt（32/36 = 列表行/聊天页头真实挂载档），
/// motion 强制 true；#if DEBUG 编译期剔除，Release 零开销。
struct AvatarSettingsView: View {
    @State private var hub = HubStore.shared
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.inkColors) private var ink
    @State private var draft: AvatarProfileModel?
    @State private var saving = false
    @State private var saveError: String?
    /// 随机预览草稿（形象随机轮）：UI 本地态不入 store/UserDefaults；只注入 shown 驱动预览
    /// 与高亮，确认「用这个形象」才走既有 select/PUT 链。
    @State private var randomDraft: AvatarProfileModel?

    private var columns: [GridItem] = Array(repeating: GridItem(.flexible(), spacing: 12), count: 4)

    /// 呈现档：随机预览草稿 > 编辑草稿（表单即时呈现所选）> store 权威档。
    private var shown: AvatarProfileModel { randomDraft ?? draft ?? hub.avatar.profile }

    var body: some View {
        List {
            Section("实时预览") {
                HStack(spacing: 16) {
                    BotCharacterView(
                        seed: botSettingsPreviewSeed,
                        state: .idle,
                        size: 96,
                        motionEnabled: shown.motionEnabled, // 动效档 standard 才动（A6）
                        interactive: false,
                        faceFactorsOverride: shown.faceFactors, // 档 12 值造型（seed bodyIndex 让位）
                        paletteIndexOverride: AgentTintName.allCases.firstIndex(of: shown.paletteName),
                    )
                    VStack(alignment: .leading, spacing: 4) {
                        Text("造型 · \(presetName(for: shown))").font(.body)
                        Text("配色 · \(shown.paletteId)").font(.caption).foregroundStyle(.secondary)
                        Text("动效 · \(shown.motionEnabled ? "标准" : "关闭")")
                            .font(.caption).foregroundStyle(.secondary)
                        if randomDraft != nil {
                            // 随机预览标（形象随机轮）：明示当前预览未落档，确认才生效
                            Text("预览中 · 未保存").font(.caption).foregroundStyle(ink.warningText)
                        }
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

            // 编辑冲突提示（远端更新在途、未覆盖表单）：一键拉取收敛（读路径④）。
            // 拉取同步清 randomDraft——否则陈旧随机草稿遮蔽拉取后的权威档、确认旧形可覆盖远端新档。
            if hub.avatar.remotePending != nil {
                Section {
                    HStack {
                        Text("其他设备已更新形象")
                            .font(.callout)
                            .foregroundStyle(ink.warningText)
                        Spacer()
                        Button("拉取") {
                            randomDraft = nil
                            draft = nil
                            saveError = nil
                            hub.avatar.adoptRemotePending()
                        }
                    }
                }
            }

            Section("造型") {
                // 随机换形象（形象随机轮 2026-11-21）：只抽形，色/动效继承 shown（draft 感知）；
                // 排除键 = shown.faceKey（连摇必换形），结果只进 randomDraft 预览零 PUT
                HStack {
                    Text("随机换形象").font(.callout)
                    Spacer()
                    Button("🎲 换一个") {
                        let base = shown
                        let preset = randomPresetExcluding(base.faceKey)
                        randomDraft = AvatarProfileModel(
                            faceFactors: preset.faceFactors,
                            paletteId: base.paletteId,
                            motionLevel: base.motionLevel,
                            updatedAt: base.updatedAt,
                        )
                        saveError = nil
                    }
                    .disabled(saving)
                }
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

            // 随机预览确认行（形象随机轮）：确认走既有 select()（先 PUT 成功再落本地）后
            // 随 draft 一并清；放弃清 draft 零扰动。横幅在场强行确认 = 显式覆盖（与既有
            // 即选即存同语义，服务端 LWW/幂等覆盖写兜底）。
            if randomDraft != nil {
                Section {
                    HStack {
                        Text("预览中 · 未保存")
                            .font(.callout)
                            .foregroundStyle(ink.warningText)
                        Spacer()
                        Button("用这个形象") {
                            guard let target = randomDraft else { return }
                            randomDraft = nil
                            select(target)
                        }
                        Button("放弃", role: .cancel) {
                            randomDraft = nil
                        }
                    }
                }
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
                // 铁律②：系统选中段面作用域钉交互强调令牌，不裸吃全局 .tint(colors.ink)
                //（暗色端 ink=0xF5F5F5 近白选中面，「系统固定亮字压近白面」同族隐患，
                // beta-0.5.22 选中胶囊同源；段内 label 对比以系统对 selectedSegmentTintColor
                // 的亮度自适应为准，真机明暗走查项）。
                .tint(ink.accent)
                .disabled(saving)
                Text("关闭 = 静止（仅入场淡入与表情即时切换）；标准 = 待机呼吸与切换过渡动画。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section {
                Button("恢复默认") {
                    randomDraft = nil // 手动恢复默认先清随机草稿（形象随机轮，同预设格口径）
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
                        .foregroundStyle(ink.dangerText)
                }
            }

            #if DEBUG
            // 状态走查（嘴部+幅度轮 2026-11-21，DEBUG 专属 #if DEBUG 编译期剔除）：六态 ×
            // 24/32/36/40/96pt——24/40 = 需求自查两档，32/36 = 聊天页头/列表行真实挂载档，
            // 96 = 设置页预览档；motion 强制 true（走查动效可辨度）。种子取中性
            // botSettingsPreviewSeed，状态直接钉六态（生产不可达此节）。
            Section("状态走查（DEBUG）") {
                ForEach(BotWalkthroughCatalog.states, id: \.name) { item in
                    HStack(spacing: 12) {
                        Text(item.name)
                            .font(.caption2)
                            .frame(width: 64, alignment: .leading)
                        ForEach(BotWalkthroughCatalog.sizes, id: \.self) { sz in
                            BotCharacterView(
                                seed: botSettingsPreviewSeed,
                                state: item.state,
                                size: sz,
                                motionEnabled: true, // 走查强制动效
                                interactive: false,
                            )
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
            #endif
        }
        .navigationTitle("形象档（默认造型与动效）")
        // 拉取/远端直收敛/保存成功三路 profile 变更统一清随机草稿（形象随机轮）：AvatarProfileModel
        // Equatable——否则 shown=randomDraft??… 让权威档继续被陈旧草稿遮蔽、确认旧形覆盖远端新档
        .onChange(of: hub.avatar.profile) { _, _ in
            randomDraft = nil
        }
    }

    // MARK: - 单元

    /// 预设单元格：44pt 缩略（motionEnabled=false 静态渲染）+ 中文名；选中 = 规范 face 串相等。
    private func presetCell(_ preset: AvatarPreset) -> some View {
        VStack(spacing: 4) {
            BotCharacterView(
                seed: botSettingsPreviewSeed,
                state: .idle,
                size: 44,
                motionEnabled: false, // 缩略强制静态（常驻循环多实例纪律沿用）
                interactive: false,
                faceFactorsOverride: preset.faceFactors, // AVATAR_PRESETS 双用：预设目录 12 值即缩略造型
                paletteIndexOverride: AgentTintName.allCases.firstIndex(of: preset.recommendedPalette),
            )
            Text(preset.zhName)
                .font(.caption2)
                // tint 债清账轮（2026-11-21）：Color.accentColor → 令牌——全局 tint 仍=ink，
                // 选中态在暗色下近白与未选中 secondary 拉不开。
                .foregroundStyle(preset.faceKey == shown.faceKey ? ink.accent : Color.secondary)
        }
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .onTapGesture {
            // 预设 = 造型 + 推荐配色一并选（推荐语义；圆点可随后单独改色，两端同口径）。
            // 手动选择先清随机草稿（形象随机轮）：手动控制作用在权威档上，预览不作数
            randomDraft = nil
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
                    // tint 债清账轮（2026-11-21）：字面量 → 令牌（同 preset 标签）。
                    selected ? ink.accent : Color.secondary.opacity(0.4),
                    lineWidth: selected ? 2.5 : 1,
                )
            )
            .onTapGesture {
                // 改色（走查②修，双端同口径）：draft 在场 = 并入随机草稿——给预览形配色，
                // 随「用这个形象」一次 PUT（不再静默洗掉预览形即时保存）；无 draft = 既有
                // 即选即存（作用在权威档上）
                if let current = randomDraft {
                    randomDraft = AvatarProfileModel(
                        faceFactors: current.faceFactors,
                        paletteId: name.rawValue,
                        motionLevel: current.motionLevel,
                        updatedAt: current.updatedAt,
                    )
                } else {
                    select(AvatarProfileModel(
                        faceFactors: shown.faceFactors,
                        paletteId: name.rawValue,
                        motionLevel: shown.motionLevel,
                        updatedAt: shown.updatedAt,
                    ))
                }
            }
            .accessibilityLabel("配色\(name.rawValue)")
    }

    /// 动效档 segmented 绑定（standard = 开；off 唯一关值，未知回落 standard）。
    private var motionBinding: Binding<Bool> {
        Binding(
            get: { shown.motionEnabled },
            set: { on in
                randomDraft = nil // 手动切档先清随机草稿（形象随机轮，同预设格口径）
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

/// 随机换形抽取（形象随机轮 2026-11-21；桌面 shared `randomPresetExcluding` 3 行镜像——
/// 无跨端一致性要求不需金标）：排除传入 faceKey（draft 感知=调用方传 shown 档键）后随机
/// 取一款，空表回退全目录（防御）。只抽形——色/动效由调用方继承 shown。
/// v1.5.2 会话换形象 sheet（ConversationAvatarSheet.swift）同消费——private→internal 单一
/// 真相放宽（MacSplitRoot/HubConversationRow 可见性放宽同先例，纯放宽零行为差）。
func randomPresetExcluding(_ currentFaceKey: String?) -> AvatarPreset {
    let pool = AvatarPresetCatalog.all.filter { $0.faceKey != currentFaceKey }
    return (pool.isEmpty ? AvatarPresetCatalog.all : pool).randomElement()!
}

#if DEBUG
/// 状态走查目录（DEBUG 专属）：六态 × 五档尺寸（24/40 = 需求自查两档，32/36 = 聊天页头/
/// 列表行真实挂载档，96 = 设置页预览档）。
private enum BotWalkthroughCatalog {
    struct Item: Identifiable {
        let name: String
        let state: BotState
        var id: String { name }
    }

    static let states: [Item] = [
        Item(name: "IDLE 待机", state: .idle),
        Item(name: "RUNNING 执行", state: .running),
        Item(name: "THINKING 思考", state: .thinking),
        Item(name: "WAITING 等审批", state: .waiting),
        Item(name: "ERROR 出错", state: .error),
        Item(name: "DONE 完成", state: .done),
    ]

    static let sizes: [CGFloat] = [24, 32, 36, 40, 96]
}
#endif
