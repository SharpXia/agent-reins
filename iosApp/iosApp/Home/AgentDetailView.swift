import SwiftUI
import composeApp

/// Onboarding-lite（M3.6）：全新安装引导页——品牌区 + 三点卡 + 连接 CTA → 内嵌连接页。
struct OnboardingView: View {
    @Environment(\.inkColors) private var c
    @State private var connecting = false

    var body: some View {
        Group {
            if !connecting {
                hero
            } else {
                ConnectionView(showsBack: false)
                    .overlay(alignment: .topLeading) {
                        Button { connecting = false } label: {
                            Image(systemName: "chevron.backward")
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundColor(c.ink)
                                .frame(width: 40, height: 40)
                                .padding(.leading, 8)
                        }
                        .buttonStyle(.plain)
                    }
            }
        }
        .background(c.paper)
    }

    private var hero: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 56)
            RoundedRectangle(cornerRadius: InkShape.xl)
                .fill(c.ink)
                .frame(width: 72, height: 72)
                .overlay(Text("A").ink(.headingLg).foregroundColor(c.paper))
            Text("AgentReins").ink(.headingLg).foregroundColor(c.ink).padding(.top, 20)
            Text("把你的 OpenClaw 网关装进口袋。\n随时查看会话、审批工具调用、跟进 Agent 动向。")
                .ink(.textMd)
                .foregroundColor(c.textTertiary)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
            Spacer()
            SandCard {
                point("加密配对", "扫码或粘贴 setup code 完成设备级签名握手")
                SandDivider(indent: 16)
                point("实时对话", "流式回复、Markdown 排版、工具输出全屏查看")
                SandDivider(indent: 16)
                point("断线不丢", "消息离线排队，重连自动补发")
            }
            Button {
                connecting = true
            } label: {
                Text("连接网关")
                    .ink(.textLg)
                    .foregroundColor(c.paper)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .background(RoundedRectangle(cornerRadius: InkShape.md).fill(c.ink))
            }
            .buttonStyle(.plain)
            .padding(.top, 24)
            Text("自托管网关 · 数据不经过第三方")
                .ink(.textXs)
                .foregroundColor(c.textDisabled)
                .padding(.top, 10)
            Spacer(minLength: 16)
        }
        .padding(.horizontal, 24)
    }

    private func point(_ title: String, _ subtitle: String) -> some View {
        HStack(alignment: .center, spacing: 10) {
            Circle().fill(c.ink).frame(width: 6, height: 6)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).ink(.textLg).foregroundColor(c.ink)
                Text(subtitle).ink(.textXs).foregroundColor(c.textTertiary)
            }
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
    }
}

/// Agent 域页：身份头（改名/选色）+ 该 agent 的会话组（M4 sessions 治理落点）。
struct AgentDetailView: View {
    @Environment(\.inkColors) private var c
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss
    @Environment(NavCoordinator.self) private var nav
    @State private var store = AppStore.shared
    @State private var showEdit = false
    let agentId: String

    private var agent: BridgeAgent? {
        store.lists.agents.first { $0.agentId == agentId }
    }

    /// 该 agent 的全部会话（未归档；AgentRegistry.sessionsOf 同语义）。
    private var sessions: [BridgeSession] {
        store.lists.sessions
            .filter { ($0.agentId ?? sessionKeyAgentId($0.id)) == agentId && !$0.archived }
            .sorted { ($0.lastActiveAtMs) > ($1.lastActiveAtMs) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
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
                Text("Agent").ink(.headingSm).foregroundColor(c.ink)
                Spacer()
            }
            .padding(.horizontal, 8)

            identityHeader

            Text("会话 · \(sessions.count)")
                .ink(.textSm)
                .foregroundColor(c.textTertiary)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)

            if sessions.isEmpty {
                EmptyStateView(icon: "person.crop.circle", title: "该 agent 暂无可见会话",
                               subtitle: "派生/系统会话仅在运行中出现")
                Spacer()
            } else {
                List {
                    ForEach(sessions, id: \.id) { s in
                        sessionRow(s)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .background(c.paper)
        .navigationBarHidden(true)
        .onAppear {
            // SW 反馈③④走查：AGENTREINS_DEV_SHEET=edit|sculpt 自动弹身份/捏脸 sheet
            if let v = ProcessInfo.processInfo.environment["AGENTREINS_DEV_SHEET"],
               v == "edit" || v == "sculpt" {
                showEdit = true
            }
        }
        .sheet(isPresented: $showEdit) {
            AgentEditSheet(agentId: agentId)
        }
    }

    private var identityHeader: some View {
        let tint = store.tint(agentId, isDark: scheme == .dark)
        let name = agent?.name ?? agentId
        return HStack(spacing: 12) {
            AgentAvatar(tint: tint, name: name, size: 52,
                        expression: store.agentExpression(agentId),
                        faceFactors: store.agentFaceFactors(agentId))
            VStack(alignment: .leading, spacing: 3) {
                Text(name).ink(.headingMd).foregroundColor(c.ink)
                // pill 与 agentId 内联一行（SW 反馈③：原 overlay 绝对定位压住文字=视觉重叠）
                HStack(spacing: 6) {
                    AgentTitlePill(name: "agent", tint: tint.strong, pillFill: tint.pill)
                    Text(agentId).ink(.textSm).foregroundColor(c.textTertiary).lineLimit(1)
                }
            }
            Spacer()
            Image(systemName: "pencil")
                .font(.system(size: 13))
                .foregroundColor(c.textTertiary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .contentShape(Rectangle())
        .onTapGesture { showEdit = true }
    }

    private func sessionRow(_ s: BridgeSession) -> some View {
        Button {
            // 主线/渠道行 → 对应 conversation（渠道对话唯一入口）；派生/系统行保持 per-session
            switch classifySession(s.id) {
            case .mainline:
                nav.push(.conversation(convId: convId(agentId), anchor: s.id))
            case .channel:
                nav.push(.conversation(convId: convId(agentId, channel: channelBucket(of: s.id) ?? "channel"),
                                       anchor: s.id))
            default:
                nav.push(.chat(sessionKey: s.id, readOnly: false))
            }
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    if s.pinned {
                        Image(systemName: "pin.fill").font(.system(size: 9)).foregroundColor(c.textTertiary)
                    }
                    Text(s.title ?? s.id)
                        .ink(.textLg)
                        .foregroundColor(c.ink)
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
}

/// 身份定制 Sheet：实时预览 + 别名输入 + 10 色选择 + 2×5 表情网格（规格 §10，即点即存）。
struct AgentEditSheet: View {
    @Environment(\.inkColors) private var c
    @Environment(\.dismiss) private var dismiss
    @State private var store = AppStore.shared
    @State private var name = ""
    @State private var loaded = false
    @State private var showSculpt = false
    let agentId: String

    var body: some View {
        InkSheet(title: "编辑 Agent") {
            // 复审修复①：新增预览+表情网格后内容总高约 506pt，超过 InkSheet 固定
            // [.medium, .large] 的 medium（≈437pt@iPhone 18 Pro、≈333pt@SE），纯 VStack 会把
            // 底部「取消/保存」与网格下半行裁掉且无法滚动——包 ScrollView 保底可滚达
            VStack(spacing: 0) {
                ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    // 实时预览（§10.1）：标题下、显示名输入框上方，64pt，当前 tint × 表情 × face；
                    // 表情/脸均经 store 解析入口（读桥已解析值），未定制时直读原始串会判不出派生值
                    HStack {
                        Spacer()
                        RobotAvatar(palette: store.agentTintName(agentId),
                                    expression: store.agentExpression(agentId),
                                    size: 64,
                                    faceFactors: store.agentFaceFactors(agentId))
                        Spacer()
                    }
                    TextField("显示名", text: $name)
                        .ink(.textMd)
                        .foregroundColor(c.ink)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                        .background(RoundedRectangle(cornerRadius: InkShape.md).fill(c.surfaceTertiary))
                        .padding(.horizontal, 20)
                        .onAppear {
                            guard !loaded else { return }
                            loaded = true
                            name = store.lists.agents.first { $0.agentId == agentId }?.name ?? agentId
                            if ProcessInfo.processInfo.environment["AGENTREINS_DEV_SHEET"] == "sculpt" {
                                showSculpt = true
                            }
                        }
                    Text("身份色").ink(.textSm).foregroundColor(c.textTertiary)
                        .padding(.horizontal, 20)
                    HStack(spacing: 8) {
                        ForEach(AgentTintName.allCases, id: \.self) { tintName in
                            let t = agentTint(tintName, isDark: c.isDark)
                            let selected = store.lists.agents.first { $0.agentId == agentId }?.tint == tintName.rawValue
                            Circle()
                                .fill(t.strong)
                                .frame(width: 30, height: 30)
                                .overlay(
                                    Circle().strokeBorder(
                                        selected ? c.ink : c.borderPrimary,
                                        lineWidth: selected ? 3 : 1
                                    )
                                )
                                .onTapGesture {
                                    store.bridge.setAgentTint(agentId: agentId, tint: tintName.rawValue)
                                    store.bridge.setAgentName(agentId: agentId, name: name)
                                }
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 10)
                    Text("表情").ink(.textSm).foregroundColor(c.textTertiary)
                        .padding(.horizontal, 20)
                    expressionGrid
                    // 捏脸（face-sculpt §5）：独立 large detent sheet，即点即存
                    HStack {
                        Spacer()
                        Button("捏脸") { showSculpt = true }
                            .ink(.textMd).foregroundColor(c.ink)
                        Spacer()
                    }
                    .padding(.vertical, 4)
                    .padding(.bottom, 8)
                }
                }
                Divider().overlay(c.borderSecondary)
                // 取消/保存固定底栏（不随内容滚动，矮视口也永远可见）
                HStack {
                    Spacer()
                    Button("取消") { dismiss() }
                        .ink(.textLg).foregroundColor(c.ink)
                    Button("保存") {
                        store.bridge.setAgentName(agentId: agentId, name: name)
                        dismiss()
                    }
                    .ink(.textLg).foregroundColor(c.ink)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
        }
        .sheet(isPresented: $showSculpt) {
            FaceSculptSheet(agentId: agentId)
        }
    }

    // 表情网格（§10.3）：2 行 × 5，顺序 = AgentExpression 声明序；44pt 缩略 ≥ 40 天然完整版
    // （RobotAvatar 无 forceFull 类逃生口）；选中描边与色点同款（未选 1pt borderPrimary / 选中 3pt ink）；
    // 点击即存并同句带动名防丢（与色板行同构，无确定按钮、无二次确认）
    private var expressionGrid: some View {
        let rows = stride(from: 0, to: AgentExpression.allCases.count, by: 5).map {
            Array(AgentExpression.allCases[$0..<min($0 + 5, AgentExpression.allCases.count)])
        }
        return VStack(spacing: 6) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 0) {
                    ForEach(row, id: \.self) { expr in
                        expressionCell(expr)
                            .frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private func expressionCell(_ expr: AgentExpression) -> some View {
        let selected = expr == store.agentExpression(agentId)
        return VStack(spacing: 4) {
            RobotAvatar(palette: store.agentTintName(agentId), expression: expr, size: 44,
                        faceFactors: store.agentFaceFactors(agentId))
                .frame(width: 50, height: 50)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(selected ? c.ink : c.borderPrimary, lineWidth: selected ? 3 : 1)
                )
            Text(expr.zhLabel).ink(.textXs).foregroundColor(c.textTertiary)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            store.bridge.setAgentExpression(agentId: agentId, expression: expr.rawValue)
            store.bridge.setAgentName(agentId: agentId, name: name)
        }
    }
}

/// 捏脸窗口（face-sculpt §5，SwiftUI 侧 = large detent sheet）：
/// 大号实时预览（当前 tint × 当前表情的 blob 脸）+ 轮廓上 12 个手柄小圆点（位置 = facePoint）。
/// 拖拽从具体手柄开始并锁定索引：θ = atan2(dx, −dy)（12 点方向为 0°、顺时针，与 Compose
/// 同口径），f_i = clamp(拖距/44, 0.70, 1.10)。每个控制点独立调整，拖动一个点不会把相邻
/// 点一起推移；拖动中逐帧重绘（§2 样条，禁止折线），并经 AppStore.setAgentFace(ClayFace.encode(…))
/// 即点即存。
/// 「重置」= face 归位 ""（规范默认正圆，与 Compose 一致）；「完成」= 关闭。
private struct FaceSculptSheet: View {
    @Environment(\.inkColors) private var c
    @Environment(\.dismiss) private var dismiss
    @State private var store = AppStore.shared
    let agentId: String
    // 拖动期间以本地状态逐帧驱动（不等 model 回流），写入与桥同步落库
    @State private var factors: [Double]
    @State private var activeHandle: Int?
    @State private var previewScale: CGFloat = 1

    init(agentId: String) {
        self.agentId = agentId
        _factors = State(initialValue: AppStore.shared.agentFaceFactors(agentId))
        _activeHandle = State(initialValue: nil)
    }

    var body: some View {
        VStack(spacing: 0) {
            Text("捏脸")
                .ink(.headingMd)
                .foregroundColor(c.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            // 预览区吃满剩余空间；取可用区最小边（留 24pt 边距放手柄），下限 220 保证可拖
            GeometryReader { geo in
                let side = max(220, min(420, min(geo.size.width, geo.size.height) - 24))
                previewBox(side: side)
                    .frame(width: side, height: side)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            Text("拖动任一手柄独立调整脸型")
                .ink(.textXs)
                .foregroundColor(c.textTertiary)
                .frame(maxWidth: .infinity)
                .padding(.bottom, 4)
            HStack {
                Spacer()
                Button("重置") {
                    withAnimation(.interactiveSpring(response: 0.24, dampingFraction: 0.8)) {
                        factors = ClayFace.defaultFactors
                        previewScale = 1
                    }
                    activeHandle = nil
                    Feedback.hapticSuccess(enabled: store.misc.hapticsEnabled)
                    store.setAgentFace(agentId, face: "")
                }
                .ink(.textLg).foregroundColor(c.ink)
                Button("完成") { dismiss() }
                    .ink(.textLg).foregroundColor(c.ink)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .background(c.paper)
        .presentationDetents([.large])
    }

    // 大预览 + 手柄叠加层。统一手势按按下位置选择最近手柄并锁定索引，命中区半径 34pt；
    // 不会因手柄的视觉/布局重叠而固定命中后一个点，也不会拖动途中跳到邻点。
    private func previewBox(side: CGFloat) -> some View {
        ZStack {
            RobotAvatar(palette: store.agentTintName(agentId),
                        expression: store.agentExpression(agentId),
                        size: side,
                        faceFactors: factors,
                        motionEnabled: false)
                .scaleEffect(previewScale)
                .animation(.interactiveSpring(response: 0.18, dampingFraction: 0.78), value: previewScale)
            // Keep the sibling handles in the same transform as the blob. Scaling only
            // the Canvas makes the visible outline drift away from the drag anchors.
            handlesOverlay(side: side)
                .scaleEffect(previewScale)
                .animation(.interactiveSpring(response: 0.18, dampingFraction: 0.78), value: previewScale)
        }
        .coordinateSpace(name: "facePreview")
    }

    /// 12 个手柄：可视点保持 8pt，命中区由统一手势按最近控制点扩大到 34pt；
    /// 这样相邻手柄的视觉间距再小，也不会因为 SwiftUI 子视图重叠而误命中固定的后一个点。
    private func handlesOverlay(side: CGFloat) -> some View {
        ZStack {
            ForEach(0..<ClayFace.pointCount, id: \.self) { i in
                let f = i < factors.count ? factors[i] : 1.0
                let p = ClayFace.point(i, f)
                let highlighted = activeHandle == i
                Circle()
                    .fill(c.ink)
                    .frame(width: highlighted ? 12 : 8, height: highlighted ? 12 : 8)
                    .overlay(Circle().fill(.white).frame(width: highlighted ? 5 : 3.6,
                                                           height: highlighted ? 5 : 3.6))
                    .frame(width: 44, height: 44)
                    .position(x: p.x / 100 * side, y: p.y / 100 * side)
                    .animation(.easeOut(duration: 0.12), value: highlighted)
            }
        }
        .frame(width: side, height: side)
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 0, coordinateSpace: .named("facePreview"))
                .onChanged { value in
                    if activeHandle == nil {
                        activeHandle = nearestHandle(to: value.startLocation, side: side)
                        guard activeHandle != nil else { return }
                        Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
                        withAnimation(.interactiveSpring(response: 0.18, dampingFraction: 0.72)) {
                            previewScale = 1.012
                        }
                    }
                    guard let index = activeHandle else { return }
                    applyDrag(value.location, index: index, side: side)
                }
                .onEnded { _ in
                    guard activeHandle != nil else { return }
                    withAnimation(.interactiveSpring(response: 0.22, dampingFraction: 0.78)) {
                        previewScale = 1
                    }
                    Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
                    activeHandle = nil
                }
        )
    }

    private func nearestHandle(to location: CGPoint, side: CGFloat) -> Int? {
        guard side > 0 else { return nil }
        let scale = side / 100
        var nearest: (index: Int, distance: CGFloat)?
        for i in 0..<ClayFace.pointCount {
            let factor = i < factors.count ? factors[i] : 1
            let p = ClayFace.point(i, factor)
            let center = CGPoint(x: p.x * scale, y: p.y * scale)
            let dx = location.x - center.x
            let dy = location.y - center.y
            let distance = (dx * dx + dy * dy).squareRoot()
            if nearest == nil || distance < nearest!.distance {
                nearest = (i, distance)
            }
        }
        guard let nearest, nearest.distance <= 34 else { return nil }
        return nearest.index
    }

    /// 锁定的拖点 → 100×100 逻辑坐标 → 半径系数，逐帧写库。角度仅用于显示/命中，
    /// 不再在拖动过程中重新选最近索引，避免拖过分界线时跳到邻点。
    private func applyDrag(_ pos: CGPoint, index i: Int, side: CGFloat) {
        guard side > 0 else { return }
        let scale = side / 100
        let dx = pos.x / scale - 50
        let dy = pos.y / scale - 50
        let fNew = min(max((dx * dx + dy * dy).squareRoot() / ClayFace.r, ClayFace.minValue), ClayFace.maxValue)
        var next = factors
        guard i < next.count, abs(next[i] - fNew) > 0.001 else { return }
        next[i] = fNew
        factors = next
        store.setAgentFace(agentId, face: ClayFace.encode(next))
    }
}
