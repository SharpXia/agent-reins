import SwiftUI
import UIKit
import composeApp

/// Hub 账号态与数据面（P4 定稿）：自持 AgentBridge（shared/hub 的唯一 Swift 入口），订阅
/// hubObserve；baseUrl 为非敏感配置存 UserDefaults，口令只经登录请求体、refreshToken 由
/// KMP 侧落 Keychain，Swift 层不持有任何凭据。
@Observable
final class HubStore {
    static let shared = HubStore()

    /// idle / working / needsLogin / loggedIn（与 AgentBridge.hubObserve 的 kind 一致）。
    private(set) var kind: String = "idle"
    /// NeedsLogin.reason（登录失败/凭据失效的人读原因）。
    private(set) var message: String?
    private(set) var userDisplay: String?
    private(set) var deviceId: String?
    /// 启动恢复在途（Keychain 有凭据时静默刷新），用于首屏显示进度而非登录表单。
    private(set) var isRestoring = false

    // ---------- P3：投影库数据面（KMP Flow → Swift 回调） ----------

    /// 会话列表（投影库 Flow 推送；label 空的渠道线程由 UI 兜底命名）。
    var conversations: [UiConversation] = []
    /// 当前打开会话的消息列表（写入经 75ms 合帧，见 [ingestMessages]）。
    var messages: [UiMessage] = []
    /// 当前打开的会话。
    var selected: UiConversation?
    /// Dev 探针用 Bool 驱动 push（KMP 类无 Hashable，NavigationPath.append 不可靠）。
    var chatPush = false

    // ---------- wp-9：待审批面（审批卡 + 设置页计数入口共用） ----------

    /// 待审批列表（投影库 Flow 推送，status=pending 行；approval.resolved 翻终态后行自动离场，
    /// 卡片状态收敛由该 Flow 驱动，本地不维护第二份审批状态）。
    private(set) var approvals: [UiApproval] = []
    /// 决策提交在途的 approvalId（按钮禁用 + 提交中态，防同卡双击双键）。
    private(set) var decisionsInFlight: Set<String> = []
    /// 已取得 PERSISTED 回执、等待 approval.resolved 收敛的 approvalId——卡片呈「已提交」等待态。
    private(set) var decisionsSubmitted: Set<String> = []
    /// 决策通道不可用（Hub FAILED——如 B-4 预检未通过 / HUB-BIZ-001，或网络不可达未提交）的
    /// approvalId——卡片显式呈现降级态 + 重试，不静默（wp-9 scope 硬要求）。
    private(set) var decisionFailures: Set<String> = []

    // ---------- wp-11：网关维度 + #16 新建会话入口 ----------

    /// 网关清单（投影库 hubGateways Flow 推送：快照整体覆盖 + gateway.online/offline 事件翻转；
    /// agents 为 AgentRef 结构化清单，wp-11 从 agentsJson 透出）。空清单 = 尚未 bootstrap。
    private(set) var gateways: [UiGateway] = []

    /// 新建会话提交在途（入口禁用，防重复提交同 agent 双键）。
    private(set) var creatingConversation = false

    /// 新建会话提交失败（hubCreateConversation 回执 null = REST 未成功）——入口页显式降级态 +
    /// 可重试，不静默（与 wp-9 决策失败同纪律）；成功态不清此值，下次提交前置 nil。
    private(set) var createConversationError: String?

    /// 新建会话已提交成功（PERSISTED 回执已得）：入口页呈「已提交」等待态——卡片出现以校准
    /// 收敛为准（成功即触发一次校准，秒级；兜底 R4 周期），行在卡出现后自动翻成置灰。
    private(set) var createdConversationFor: Set<String> = []

    // ---------- wp-12：历史翻页（加载更早） ----------

    /// 「加载更早」按钮可见性：初始由「会话 lastSeq > 已载行数」乐观判定（≤50 行会话不出按钮——
    /// 快照尾页已含全部历史），首次翻页后以服务端 MessagesPage.hasMore 为权威（wp-12 scope：
    /// hasMore 驱动按钮可见性），翻到头锁存隐藏。
    private(set) var canLoadEarlier = false

    /// 翻到头锁存：服务端已告知无更早行（hasMore=false）或空页无法推进游标，本会话期不再出
    /// 入口、顶部呈「已到最早消息」收尾条。随会话切换重置。
    private(set) var earlierExhausted = false

    /// 翻页请求在途（按钮禁用 + 进度态；REST 列表类限流 60 次/分/设备，不做并发翻页）。
    private(set) var loadingEarlier = false

    /// 翻页失败降级态（REST 未成功/通道不可用）：顶部横幅 + 可重试，不静默（wp-9 同纪律）。
    private(set) var loadEarlierError: String?

    /// 翻页游标（按 oldestSeq 翻页，08 §4.3 keyset）：最近一次成功页的 oldestSeq；nil = 回落
    /// 当前最老已载行 seq（首翻或页未带 oldestSeq）。会话切换/登出重置。
    /// 会话期中途快照重建（HUB-SYNC-001/002 → engine.rebuild → applySnapshot 清行只回灌尾页）
    /// 会使旧游标失效——loadEarlier 取 before 前检测游标倒退（< 最老已载行 seq）即弃用回落，
    /// 防以旧游标取数跳过重建后尾页首行之下的缺口带（wp-12 快照重建防护）。
    private var earlierCursor: Int64?

    /// 翻页落地滚动恢复锚（触发翻页时记当前最老一行 messageId；行合入列表后由会话页滚动到
    /// 该行，保住阅读位置）。restoreArmed = 已取得成功回执、等待列表落地后消费。
    private var restoreAnchorMessageId: String?
    private var restoreArmed = false

    private var devicesCache: [DeviceInfo]? = nil

    /// 订阅会话列表（loggedIn 后调用一次；重复调用由桥侧换订阅）。
    func watchConversations() { bridge.hubWatchConversations(onList: { [weak self] list in self?.conversations = list }) }

    /// 订阅待审批列表（进入 loggedIn 时挂载；重复调用由桥侧换订阅）。
    func watchApprovals() {
        bridge.hubWatchApprovals { [weak self] list in
            guard let self else { return }
            self.approvals = list
            // 收敛清理：行已离场（approval.resolved）的 id，提交/失败瞬态一并清，防集合无界增长
            let ids = Set(list.map { $0.approvalId })
            self.decisionsSubmitted.formIntersection(ids)
            self.decisionFailures.formIntersection(ids)
        }
    }

    /// 订阅网关清单（wp-11：与 watchConversations 同模式，纯投影库面、随库变更推送）。
    func watchGateways() {
        bridge.hubWatchGateways { [weak self] list in
            guard let self else { return }
            self.gateways = list
            // 收敛清理：已兜底成卡的 (gatewayId, agentId) 移出「已提交」瞬态（行翻置灰由主线卡判据接管）
            let cardKeys = Set(self.conversations.map { Self.entryKey(gatewayId: $0.gatewayId, agentId: $0.agentId) })
            self.createdConversationFor.formIntersection(cardKeys)
        }
    }

    // ---------- wp-11：#16 新建会话入口 ----------

    /// 主线卡判据（#16 聚合语义 2026-11-04 拍板的客户端判定面）：该 (gatewayId, agentId) 在
    /// 会话列表已有卡片 = 已有主线（服务端 DISTINCT ON 聚合保证一 agent 至多一卡）。
    func hasMainCard(gatewayId: String, agentId: String) -> Bool {
        conversations.contains { $0.gatewayId == gatewayId && $0.agentId == agentId }
    }

    private static func entryKey(gatewayId: String, agentId: String) -> String { "\(gatewayId)|\(agentId)" }

    /// 新建会话（#16）：复用 hubCreateConversation（幂等键 Hub 生成，label 可空）。回执 null =
    /// 提交失败（REST 未成功），显式降级态可重试；成功即记「已提交」等待态——卡片出现以校准
    /// 收敛为准（DefaultHubClient 成功后即触发一次校准，秒级），本表行在该 agent 成卡后自动翻置灰。
    func createConversation(gatewayId: String, agentId: String) {
        let key = Self.entryKey(gatewayId: gatewayId, agentId: agentId)
        guard !creatingConversation, !hasMainCard(gatewayId: gatewayId, agentId: agentId) else { return }
        creatingConversation = true
        createConversationError = nil
        bridge.hubCreateConversation(gatewayId: gatewayId, agentId: agentId, label: nil) { [weak self] commandId in
            guard let self else { return }
            self.creatingConversation = false
            if commandId == nil || commandId!.isEmpty {
                self.createConversationError = "提交未送达（可能为网络不可达），可重试"
            } else {
                self.createdConversationFor.insert(key)
            }
        }
    }

    /// 审批决定（wp-9）：approve=批准 / reject=拒绝，走幂等队列 approval.decide（新意图新键）。
    /// 回执 false 时卡片呈「决策通道不可用」降级态（按钮恢复可用，可重试）；true 呈「已提交」
    /// 等待 approval.resolved 收敛离场。
    func decideApproval(_ approvalId: String, approve: Bool) {
        guard !decisionsInFlight.contains(approvalId) else { return }
        decisionsInFlight.insert(approvalId)
        bridge.hubDecideApproval(approvalId: approvalId, decision: approve ? "approve" : "reject") { [weak self] ok in
            guard let self else { return }
            self.decisionsInFlight.remove(approvalId)
            if ok.boolValue {
                self.decisionFailures.remove(approvalId)
                self.decisionsSubmitted.insert(approvalId)
            } else {
                self.decisionFailures.insert(approvalId)
            }
        }
    }

    /// 打开会话：切消息订阅（旧订阅由桥侧取消）。
    func open(_ conv: UiConversation) {
        selected = conv
        resetMessagesStream()
        resetPagingState()
        messages = []
        bridge.hubWatchMessages(conversationId: conv.conversationId) { [weak self] list in
            self?.ingestMessages(list)
        }
    }

    func closeConversation() {
        selected = nil
        resetMessagesStream()
        resetPagingState()
        messages = []
        chatPush = false
        bridge.hubStopWatchingMessages()
    }

    /// Dev 探针直达会话（Bool push 通道，见 chatPush 注释）。
    func devOpen(_ conv: UiConversation) {
        selected = conv
        resetMessagesStream()
        resetPagingState()
        messages = []
        chatPush = true
        bridge.hubWatchMessages(conversationId: conv.conversationId) { [weak self] list in
            self?.ingestMessages(list)
        }
    }

    // ---------- wp-7③：messages Flow ~75ms 合帧节流 ----------

    /// 合帧间隔 ~75ms（≈13fps 上限；OpenClaw 渠道侧 75ms 聚合先例）。
    ///
    /// 动机：上游 Bridge 合并器 2s 窗产 delta 大块（bridge/relay/delta-coalesce.ts
    /// `DEFAULT_COALESCE_WINDOW_MS = 2_000`），一批事件落库是多个事务，sqldelight Flow
    /// 逐事务重推整表 → SwiftUI 逐次整列表 diff + MarkdownView 重渲，形成渲染突刺。
    /// 合帧后 75ms 窗内多次回调只留最新一份、窗界一次性落 `messages`；窗空闲时零延迟
    /// 直通（leading），用户自己的乐观行/会话首帧快照不吃延迟。
    private static let messagesCoalesceInterval: Duration = .milliseconds(75)

    /// 合帧窗内待落盘的最新一份列表。
    private var pendingMessages: [UiMessage]? = nil
    /// 在途合帧 flush 任务（@MainActor：HubStore 非 actor 类，KMP 回调在主队列，
    /// flush 必须显式回主 actor 才能写 @Observable 状态）。
    private var messagesFlushTask: Task<Void, Never>? = nil
    /// 上次 flush 时刻（单调钟，leading 判定用；nil = 从未 flush，下一帧零延迟）。
    private var lastMessagesFlushAt: ContinuousClock.Instant? = nil
    /// 会话代际：切换会话/登出自增，作废在途 flush（旧会话列表不得串进新会话）。
    private var messagesEpoch = 0

    /// 消息列表入口（KMP 回调线程 = 主队列）：只暂存最新一份，缺 flush 任务则排一个。
    private func ingestMessages(_ list: [UiMessage]) {
        pendingMessages = list
        guard messagesFlushTask == nil else { return }
        let epoch = messagesEpoch
        let interval = Self.messagesCoalesceInterval
        let since = lastMessagesFlushAt.map { $0.duration(to: .now) } ?? interval
        let delay = max(.zero, interval - since)
        messagesFlushTask = Task { @MainActor [weak self] in
            if delay > .zero { try? await Task.sleep(for: delay) }
            // 代际不符 = 途中切换了会话/登出，本帧作废（reset 已清任务句柄，勿在此置 nil）
            guard let self, epoch == self.messagesEpoch, !Task.isCancelled else { return }
            self.messagesFlushTask = nil
            guard let latest = self.pendingMessages else { return }
            self.pendingMessages = nil
            self.lastMessagesFlushAt = .now
            self.messages = latest
            self.refreshCanLoadEarlier()
        }
    }

    /// 切换会话/登出时清空合帧在途态：代际自增作废旧 flush，清空后首帧快照零延迟直通。
    private func resetMessagesStream() {
        messagesEpoch += 1
        messagesFlushTask?.cancel()
        messagesFlushTask = nil
        pendingMessages = nil
        lastMessagesFlushAt = nil
    }

    func send(_ text: String) {
        guard let c = selected else { return }
        bridge.hubSend(conversationId: c.conversationId, text: text)
    }

    /// 当前打开会话的实时行（wp-8②）：selected 是打开时刻的快照，hasActiveRun/activeRunId 会随
    /// run.* 事件后续翻转，Stop 按钮态一律从 conversations 列表现值取，不得用快照。
    var liveSelected: UiConversation? {
        guard let c = selected else { return nil }
        return conversations.first { $0.conversationId == c.conversationId }
    }

    /// Stop（wp-8②）：取消当前活跃 run（run.cancel）。activeRunId 取会话实时行（wp-5 v4 投影）；
    /// 缺失 = 映射未学习场景（DefaultEventProjector.setRun 在 resolveConversation 失败时不落库），
    /// 不发命令——UI 呈「结束中」提示态，等 Gateway 终态事件权威收敛，不假设 runId 恒有值。
    func stopRun() {
        guard let runId = liveSelected?.activeRunId else { return }
        bridge.hubCancelRun(runId: runId)
    }

    /// 渲染完成后上报已读（取已渲染最大 seq）。
    func markReadUpTo(_ seq: Int64) {
        guard let c = selected else { return }
        bridge.hubMarkRead(conversationId: c.conversationId, readUpTo: seq)
    }

    // ---------- wp-12：历史翻页（加载更早） ----------

    /// 会话打开/关闭/登出时重置翻页态（游标与锁存随会话期，不跨会话残留）。
    private func resetPagingState() {
        earlierExhausted = false
        earlierCursor = nil
        loadingEarlier = false
        loadEarlierError = nil
        restoreAnchorMessageId = nil
        restoreArmed = false
        canLoadEarlier = false
    }

    /// 重算「加载更早」入口可见性：翻到头锁存后恒隐藏；否则按「会话 lastSeq（实时行，随 run/
    /// 消息事件推进）> 已载行数」乐观判定——快照尾页 50 行 + 长会话（>50 行）即出按钮，首翻后
    /// 由服务端 hasMore 接管（loadEarlier 回执）。列表未落地（空）时不出口：翻页游标取自最老
    /// 已载行，空列表无游标可依（尾页由快照承担）。
    private func refreshCanLoadEarlier() {
        guard !earlierExhausted, let c = selected, !messages.isEmpty else {
            canLoadEarlier = false
            return
        }
        let lastSeq = liveSelected?.lastSeq ?? c.lastSeq
        canLoadEarlier = lastSeq > Int64(messages.count)
    }

    /// 加载更早（wp-12）：按 oldestSeq 翻页（REST #8 before=游标，行经 upsertMessage 合并进
    /// 投影库，observeMessages Flow 自动推送合并后 seq 升序全序列）。游标 = 最近成功页的
    /// oldestSeq，回落当前最老已载行 seq；成功后服务端 hasMore 接管按钮可见性，翻到头锁存隐藏。
    /// 失败（回执 nil）显式降级态可重试，不静默。限流纪律：一次一请求（loadingEarlier 互斥）。
    func loadEarlier() {
        guard let c = selected, canLoadEarlier, !loadingEarlier, !earlierExhausted else { return }
        let oldestLoaded = messages.first?.seq
        // 游标倒退检测（wp-12 快照重建防护）：投影库可在会话期中途整体重建（HUB-SYNC-001/002 →
        // DefaultSyncEngine.rebuild → applySnapshot 清行只回灌快照尾页），earlierCursor 仍持重建前
        // 旧值、低于重建后最老已载行 seq——以旧游标取数将整段跳过尾页首行之下的缺口带，时间线
        // 以「完整历史」呈现静默缺洞（重开会话才自愈）。检测到倒退即弃用旧游标，回落最老已载行
        // seq 从已载下沿继续向上补齐。化身更替清行后按钮随空列表隐藏（refreshCanLoadEarlier 守卫），
        // 不依赖此防护，命中场景即会话期重建路径。
        if let cursor = earlierCursor, let oldestLoaded, cursor < oldestLoaded {
            earlierCursor = nil
        }
        let before = earlierCursor ?? oldestLoaded
        guard let before, before >= 1 else { return }
        loadingEarlier = true
        loadEarlierError = nil
        // 阅读位置恢复锚 = 触发前最老一行（新页将合入其上方，落地后滚回该行，视口不跳）。
        restoreAnchorMessageId = messages.first?.messageId
        restoreArmed = false
        bridge.hubLoadEarlier(conversationId: c.conversationId, before: before) { [weak self] page in
            guard let self else { return }
            self.loadingEarlier = false
            guard let page else {
                self.restoreAnchorMessageId = nil
                self.restoreArmed = false
                self.loadEarlierError = "加载更早失败（网络或服务不可达），可重试"
                return
            }
            // 游标链推进（按 oldestSeq 翻页）；空页无法推进 = 终止（防同游标循环重试）。
            // Kotlin Long? 经 ObjC 互携为 KotlinLong?，此处取 int64Value 归一为 Int64。
            self.earlierCursor = page.oldestSeq?.int64Value
            if page.count == 0 || !page.hasMore {
                self.earlierExhausted = true
                self.restoreAnchorMessageId = nil
                self.restoreArmed = false
            } else {
                self.restoreArmed = true
            }
            self.loadEarlierError = nil
            self.refreshCanLoadEarlier()
        }
    }

    /// 翻页落地滚动恢复（会话页在 messages 行数变化时调用）：返回触发前最老一行 id 供滚回
    /// （锚行必须已在合并后列表中）；未在途/行已随化身更替消失时返回 nil 并清锚。
    func takeRestoreScrollTarget() -> String? {
        guard restoreArmed, let anchor = restoreAnchorMessageId else { return nil }
        restoreAnchorMessageId = nil
        restoreArmed = false
        guard messages.contains(where: { $0.messageId == anchor }) else { return nil }
        return anchor
    }

    // ---------- v1.1 新端点（openclaw 原生指令） ----------

    /// 会话重命名（conversation.rename；结果经 session.updated 事件回流列表）。
    func rename(_ conv: UiConversation, to label: String, onDone: ((Bool) -> Void)? = nil) {
        bridge.hubRenameConversation(conversationId: conv.conversationId, label: label, onDone: { ok in onDone?(ok.boolValue) })
    }

    /// 压缩上下文（conversation.compact；化身更替后 conversationId 不变）。
    func compact(_ conv: UiConversation, onDone: ((Bool) -> Void)? = nil) {
        bridge.hubCompactConversation(conversationId: conv.conversationId, onDone: { ok in onDone?(ok.boolValue) })
    }

    /// 设备清单（设备管理页；每次进入拉新）。
    func refreshDevices() {
        bridge.hubDevices { [weak self] list in self?.devicesCache = list }
    }

    var devices: [DeviceInfo]? { devicesCache }

    func revokeDevice(_ deviceId: String) { bridge.hubRevokeDevice(deviceId: deviceId) { [weak self] in self?.refreshDevices() } }

    var baseUrl: String {
        didSet { UserDefaults.standard.set(baseUrl, forKey: Self.baseUrlKey) }
    }

    /// 构建期注入的默认 REST 地址（gitignored Config.local.xcconfig `HUB_PUBLIC_BASE` →
    /// Info.plist `HubDefaultBaseURL` → 此处读 Bundle；CI 用同名 secret 注入，仓库零域名）。
    /// 自建构建未注入时为空串，此时登录页要求手填地址。
    var defaultBaseUrl: String { Self.injectedDefaultBaseUrl() }

    private static func injectedDefaultBaseUrl() -> String {
        let raw = Bundle.main.object(forInfoDictionaryKey: "HubDefaultBaseURL") as? String ?? ""
        let t = raw.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty, !t.contains("$("), let host = URL(string: t)?.host, !host.isEmpty else { return "" }
        return t
    }

    private static let baseUrlKey = "hub.baseUrl"
    private let bridge = AgentBridge()

    private init() {
        // 未曾手改过服务器地址（UserDefaults 无值）时用注入的默认地址，登录页免填。
        baseUrl = UserDefaults.standard.string(forKey: Self.baseUrlKey) ?? Self.injectedDefaultBaseUrl()
        bridge.hubObserve { [weak self] kind, message, user, deviceId in
            guard let self else { return }
            self.kind = kind
            self.message = message
            self.userDisplay = user
            self.deviceId = deviceId
            if kind != "working" { self.isRestoring = false }
            // wp-9：登录态建立（hubClient 就绪）即挂待审批订阅；重复回调由桥侧换订阅。
            // 不挂在视图 .task（与 watchConversations 不同）：审批计数在设置页也要活，须跟登录态走。
            if kind == "loggedIn" { self.watchApprovals() }
        }
        if !baseUrl.isEmpty {
            isRestoring = true
            bridge.hubRestore(baseUrl: baseUrl)
        }
        #if DEBUG
        // Dev 通道（与网关时代 Documents/dev.json 同模式）：沙盒 Documents/hub-dev.json
        // {"baseUrl","username","password"} 存在即自动登录一次，供模拟器无触控注入的自动化验收。
        // 读取后立即删除文件，口令不滞留沙盒；Release 包不编译此段。
        if let dev = Self.loadDevLogin() {
            try? FileManager.default.removeItem(at: dev.url)
            login(baseUrl: dev.baseUrl ?? baseUrl, username: dev.username, password: dev.password)
        }
        #endif
    }

    #if DEBUG
    private struct DevLogin: Decodable {
        /// 可空：缺省走包内默认地址（与正式登录路径同源，自动化可验证默认地址面）。
        let baseUrl: String?
        let username: String
        let password: String
    }

    private static func loadDevLogin() -> (url: URL, baseUrl: String?, username: String, password: String)? {
        guard let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return nil }
        let url = docs.appendingPathComponent("hub-dev.json")
        guard let data = try? Data(contentsOf: url),
              let d = try? JSONDecoder().decode(DevLogin.self, from: data) else { return nil }
        return (url, d.baseUrl, d.username, d.password)
    }
    #endif

    var isLoggedIn: Bool { kind == "loggedIn" }

    func login(baseUrl: String, username: String, password: String) {
        self.baseUrl = baseUrl
        bridge.hubLogin(
            baseUrl: baseUrl,
            username: username,
            password: password,
            deviceName: UIDevice.current.name
        )
    }

    /// 登出闭环（方案 §6-P4 验收⑤）= 撤销当前设备（服务端 Token 即时失效）+ 清 Keychain
    /// + 清全部本地投影数据（桥侧七表清空）+ 清内存态。重登后从空库 bootstrap。
    func logout() {
        selected = nil
        resetMessagesStream()
        resetPagingState()
        messages = []
        conversations = []
        approvals = []
        decisionsInFlight = []
        decisionsSubmitted = []
        decisionFailures = []
        gateways = []
        creatingConversation = false
        createConversationError = nil
        createdConversationFor = []
        devicesCache = nil
        chatPush = false
        bridge.hubLogout()
    }
}
