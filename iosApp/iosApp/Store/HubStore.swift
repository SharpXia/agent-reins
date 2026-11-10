import SwiftUI
import UIKit
import PhotosUI
import composeApp

// ── v1.3 发送链：会话草稿模型（文件域类型——HubViews 与本 store 共用） ──

/// 草稿附件槽（会话草稿层持有，非瞬时视图态）：upload 非 nil = 已就绪（mediaId 可随
/// 发送提交）；uploading = 上传在途；error = 失败（点按重试，发送时自动补传）；
/// sourceData = 已压缩字节（补传复用，不落盘）。
struct DraftAttachment: Identifiable {
    let id: UUID
    /// PhotosPickerItem.itemIdentifier（撤选去重用；nil = 非 picker 来源）。
    let pickerItemIdentifier: String?
    /// chip 预览缩略图（512px 解码）。
    var preview: UIImage?
    /// 展示元数据文件名（仅随 AttachmentRef 回显；服务端永不进存储路径）。
    var fileName: String
    var upload: UiMediaUpload?
    var error: String?
    var uploading: Bool
    var sourceData: Data?
}

/// 会话草稿（可存活层持有）：未发送文本 + pending 附件。放在 store 而非视图 @State——
/// 视图销毁/切后台/滚动往返不丢（验收：未发送状态下 pending 图与文本不得丢失）。
struct ConversationDraft {
    var text: String = ""
    var attachments: [DraftAttachment] = []
}

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

    /// #2'（2026-11-07 二轮，x总改版）：thinking 帧在途判定——当前打开会话最新阶段帧
    /// phase=thinking（run 终态投影清行，帧存在即在途）。数据链：Gateway thinking 流 →
    /// 桥折帧 run.status{thinking} → 投影 hubRunStatus → observeWorkActivity。驱动回复
    /// 占位行「正在思考…」（否则「<agent> 正在处理中…」）。
    var isThinkingNow: Bool {
        workActivity?.phases.last?.phase == "thinking"
    }

    // ---------- wp-15：工具活动捕获（数据面保留：thinking 判定 + 通知/未来复用） ----------

    /// 当前打开会话的工作过程快照（投影库 Flow 推送：hubToolEvents 捕获行 + hubRunStatus
    /// 阶段帧合流，AgentBridge.hubWatchWorkActivity）。两表为本地累积捕获态——断线窗口内的
    /// tool.*/run.status 事件不补发（06 §10.2 已知丢失面），快照只承载已捕获部分，UI 不承诺
    /// 完整；重连后已捕获部分保留。离开会话页清空（随 open/close 成对）。
    private(set) var workActivity: UiWorkActivity?

    // ---------- wp-10：连接相位面（顶部连接提示条数据源） ----------

    /// 同步连接相位（AgentBridge.hubWatchPhase 推送，phase.name）：catching / linked /
    /// reconnecting / rebuilding / superseded。linked = 已连接且已追平，是 HubRootView 顶部
    /// 连接提示条唯一消失相位；catching = 引导/追平在途（弱提示）；reconnecting / rebuilding /
    /// superseded = 连接降级（警示）。纯观察面，登出复位 catching。
    private(set) var syncPhase: String = "catching"

    /// #3（2026-11-07 二轮，用户拍板）：提醒条去抖显示——非 linked 相位**持续超 2.5s** 才显示。
    /// 动机：崩溃防护改为离开前台即断开（见 App.swift scenePhase），每次解锁/回前台都有一段
    /// 1–2s 的快速重连（catching→linked），逐次弹条是噪声；首次登录 bootstrap、真断线等慢同步
    /// 超过阈值照常显示。linked/登出立即隐藏；相位在非 linked 间翻转时已显示的不闪隐藏。
    private(set) var syncBannerVisible = false

    private var bannerDebounceTask: Task<Void, Never>?

    private static let bannerDebounceNanos: UInt64 = 2_500_000_000

    private func notePhaseForBanner(_ phase: String) {
        bannerDebounceTask?.cancel()
        bannerDebounceTask = nil
        if phase == "linked" || !isLoggedIn {
            syncBannerVisible = false
            return
        }
        bannerDebounceTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: Self.bannerDebounceNanos)
            guard let self, !Task.isCancelled, self.syncPhase != "linked", self.isLoggedIn else { return }
            self.syncBannerVisible = true
        }
    }

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

    /// 翻页游标（按 oldestSeq 翻页，08 §4.3 keyset）：最近一次成功页的 oldestSeq；nil = 首翻
    /// （或页未带 oldestSeq）——由 Kotlin 侧按 DB 最老已载行 seq 原始口径（含被显示过滤的行）
    /// 解析游标与 stale 回落（2026-11-10 加载更早修复轮：游标口径自本层下沉数据源头。旧实现
    /// 在此层用「显示首行 seq」当游标下沿做倒退检测，整页被过滤的心跳带落库后显示下沿不动，
    /// 游标被判倒退重置 → 同带反复重取，「加载更早」永远拉不进更早历史）。会话切换/登出重置。
    private var earlierCursor: Int64?

    /// 上滑自动翻页阻断（2026-11-10 加载更早修复轮）：自动触发的一页请求失败（nil 回执）即
    /// 停止后续自动触发（防断网期滚动逐帧空转重试 + 触发限流），手动「加载更早」按钮保留为
    /// 重试兜底——任一次翻页成功即解锁。随会话切换/登出重置。
    private var autoEarlierBlocked = false

    /// 翻页落地滚动恢复锚（触发翻页时记当前最老一行 messageId；行合入列表后由会话页滚动到
    /// 该行，保住阅读位置）。restoreArmed = 已取得成功回执、等待列表落地后消费。
    private var restoreAnchorMessageId: String?
    private var restoreArmed = false

    private var devicesCache: [DeviceInfo]? = nil

    // ---------- #21：动态斜杠命令目录（v1.1 additive，2026-11-07；纯读增强面） ----------

    /// 动态命令目录缓存（conversationId → REST #21 回执）。纯内存、随登出清空；TTL 5 分钟
    /// （拍板口径），过期后下次 "/" 起手重拉。失败不缓存——静态六继续兜底，下次触发重试。
    private(set) var commandsCache: [String: CommandsList] = [:]

    /// 缓存拉取时刻（TTL 判定基准；与 commandsCache 同键同生命周期）。
    private var commandsFetchedAt: [String: Date] = [:]

    /// 在途去重：同会话并发触发只发一次请求（列表类限流每设备 60 次/分钟，不做并发重放）。
    private var commandsInFlight: Set<String> = []

    /// 拉取失败标记（conversationId）：失败后维持静态六；同「/ 输入段」内不逐键重试——
    /// 否则离线时打 "/hello" 会逐键各发一请求（撞限流口径）。退出 "/" 起手再重新进入 =
    /// 新触发段，允许重试（≈「下次聚焦重试」口径，重进会话页同样重开触发段）。
    private var commandsFailed: Set<String> = []

    /// 「/ 输入段」判定基准：上一次触发时草稿是否以 "/" 起手（非 "/" → "/" 的翻转 = 新段）。
    private var commandsSlashSession: [String: Bool] = [:]

    private static let commandsCacheTTL: TimeInterval = 300

    // ---------- #22：会话模型目录（v1.1 additive，2026-11-08；斜杠交互卡数据面） ----------

    /// 模型目录缓存（conversationId → REST #22 回执）。纯内存、随登出清空；TTL 5 分钟
    /// （与命令目录同拍板口径），过期后下次触发重拉。失败不缓存——交互卡不渲染（静默
    /// 降级，与命令目录同纪律），下次触发重试。
    private(set) var modelsCache: [String: ModelsList] = [:]

    /// 缓存拉取时刻（TTL 判定基准；与 modelsCache 同键同生命周期）。
    private var modelsFetchedAt: [String: Date] = [:]

    /// 在途去重：同会话并发触发只发一次请求（列表类限流每设备 60 次/分钟）。
    private var modelsInFlight: Set<String> = []

    /// 触发面 = 交互卡出现判定为真时（会话页 timeline 组合侧调用）；缓存新鲜即空转。
    /// 失败静默吞（onResult null 不入缓存），与 fetchCommandsIfNeeded 同纪律。
    func fetchModelsIfNeeded(conversationId: String) {
        guard isLoggedIn else { return }
        if let fetchedAt = modelsFetchedAt[conversationId],
           Date().timeIntervalSince(fetchedAt) < Self.commandsCacheTTL { return }
        guard !modelsInFlight.contains(conversationId) else { return }
        modelsInFlight.insert(conversationId)
        bridge.hubModels(conversationId: conversationId) { [weak self] list in
            guard let self else { return }
            self.modelsInFlight.remove(conversationId)
            guard let list else { return }
            self.modelsCache[conversationId] = list
            self.modelsFetchedAt[conversationId] = Date()
        }
    }

    /// 草稿以 "/" 起手时按需拉取动态目录（触发面 = composer onChange(of: draft)；缓存新鲜即空转，
    /// 其余输入零干扰不触发）。失败（onResult null）静默吞——维持静态六目录，不弹错误 UI
    /// （尽力而为增强面，与 hubReportApnsToken 同纪律）。
    func fetchCommandsIfNeeded(conversationId: String, draft: String) {
        let slashDraft = draft.hasPrefix("/")
        guard isLoggedIn, slashDraft else {
            commandsSlashSession[conversationId] = false
            return
        }
        commandsSlashSession[conversationId] = true
        fetchCommands(conversationId: conversationId, ignoreFailed: false)
    }

    /// 2026-11-08 修复轮：无条件触发拉取（交互卡挂载时调用——用户可能不经 "/" 草稿直接
    /// 发出交互命令，choices 数据面需要目录在位；缓存新鲜即在途去重照常生效）。不触碰
    /// 「/ 输入段」标记（ensure 不是 slash 会话，不改变失败重试节奏）。
    func ensureCommands(conversationId: String) {
        guard isLoggedIn else { return }
        fetchCommands(conversationId: conversationId, ignoreFailed: true)
    }

    /// 拉取核心（TTL + 在途去重 + 失败标记，与原 fetchCommandsIfNeeded 同逻辑）；仅
    /// slashDraft 语义拆出供两条触发面共用。ignoreFailed=true（ensure 面）跳过失败标记
    /// 门（交互卡在场时值得一次重试机会），false（草稿面）维持原「同段不逐键重试」纪律。
    private func fetchCommands(conversationId: String, ignoreFailed: Bool) {
        if let fetchedAt = commandsFetchedAt[conversationId],
           Date().timeIntervalSince(fetchedAt) < Self.commandsCacheTTL { return }
        guard !commandsInFlight.contains(conversationId) else { return }
        if commandsFailed.contains(conversationId), !ignoreFailed { return }
        commandsInFlight.insert(conversationId)
        bridge.hubCommands(conversationId: conversationId) { [weak self] list in
            guard let self else { return }
            self.commandsInFlight.remove(conversationId)
            guard let list else {
                self.commandsFailed.insert(conversationId)
                return
            }
            self.commandsFailed.remove(conversationId)
            self.commandsCache[conversationId] = list
            self.commandsFetchedAt[conversationId] = Date()
        }
    }

    // ---------- wp-11：设备管理错误态（与同页审批卡显式降级纪律一致） ----------

    /// 设备清单拉取失败（hubDevices devices null = 未登录/网络不可达/Hub FAILED）：设置页设备
    /// 节显式呈现「错误 + 重试」，不再永「加载中…」（wp-11 设备管理错误态）。成功即清；
    /// 已有缓存清单时刷新失败，错误行与缓存行并存（缓存仍可用，重试按钮补拉）。
    private(set) var devicesError: String?

    /// 撤销设备失败（hubRevokeDevice ok false = 撤销未送达）：设置页设备节显式降级文案 +
    /// 可重试（再点「撤销」），不静默（wp-11 设备管理错误态；此前 onDone 恒调、失败被吞）。
    /// 成功即清。
    private(set) var revokeFailure: String?

    // ---------- wp-13：通知面（iOS 本地通知 + 按会话静音） ----------

    /// 主 tab 选择（HubRootView 绑定）：通知点按需把用户从任意 tab 带回会话栈，tab 态必须在
    /// store 而非视图 @State（wp-13 从 HubRootView 迁入，语义不变）。
    var selectedTab: Int = 0

    /// 按会话静音集（本地偏好，UserDefaults 持久化 + 内存镜像保 @Observable 响应式；
    /// 跨登出保留——静音是本地用户偏好，非账号数据）。
    private(set) var mutedConversations: Set<String> = HubStore.loadMutedConversations()

    /// （v1.3 通知摘要轮：回复通知触发面自会话列表 unread 差异迁移到 message.created 事件
    /// 旁路——摘要需要消息正文与附件数（剥 markdown / 纯附件「[图片]×N」），卡片 preview
    /// 只是裸 content 给不出附件计数；见 noteReplyMessage。旧 unread 差异面随之退役。）

    private let notifications = NotificationCoordinator.shared

    /// 订阅会话列表（loggedIn 后调用一次；重复调用由桥侧换订阅）。
    func watchConversations() {
        bridge.hubWatchConversations { [weak self] list in
            guard let self else { return }
            self.conversations = list
        }
    }

    /// 订阅待审批列表（进入 loggedIn 时挂载；重复调用由桥侧换订阅）。
    /// （wp-13 审批通知不走本 Flow：快照带回的积压 pending 行会被差异面误判为新事件，
    /// 触发面用桥侧 approval.requested 事件旁路，见 noteApprovalRequested。）
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

    /// 订阅连接相位（wp-10 连接提示条数据面；登录态建立时机调用——与 watchApprovals 同模式，
    /// 重复调用由桥侧换订阅。linked = 已连接且已追平，提示条唯一消失相位）。
    func watchPhase() {
        // 防御性小写归一（beta-0.5.4 真机缺陷教训）：桥契约是小写字符串，任何上游漂移
        // 在此归一，避免横条恒琥珀+恒无文字（switch 全落 default）。
        bridge.hubWatchPhase { [weak self] phase in
            let p = phase.lowercased()
            self?.syncPhase = p
            self?.notePhaseForBanner(p)
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
        watchWorkActivity(conv)
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
        workActivity = nil
        bridge.hubStopWatchingMessages()
    }

    /// Dev 探针直达会话（Bool push 通道，见 chatPush 注释）。
    func devOpen(_ conv: UiConversation) { openViaPush(conv) }

    /// push 通道打开会话（chatPush Bool 直达）：Dev 探针与 wp-13 通知点按共用同一导航面。
    func openViaPush(_ conv: UiConversation) {
        selected = conv
        resetMessagesStream()
        resetPagingState()
        messages = []
        chatPush = true
        watchWorkActivity(conv)
        bridge.hubWatchMessages(conversationId: conv.conversationId) { [weak self] list in
            self?.ingestMessages(list)
        }
    }

    /// wp-15：工作过程订阅（open/openViaPush 共用；随 hubStopWatchingMessages 成对取消，
    /// 离开会话页不再推送且内存快照清空由 closeConversation 承担）。订阅前置 nil——
    /// 防上一会话的快照在新会话首帧推送前闪现（Flow 首推有延迟）。
    private func watchWorkActivity(_ conv: UiConversation) {
        workActivity = nil
        bridge.hubWatchWorkActivity(conversationId: conv.conversationId) { [weak self] snap in
            self?.workActivity = snap
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
            self.maybeBackfillEmptyTimeline()
        }
    }

    /// 空时间线自动回灌（2026-11-10 加载更早修复轮「进会话首屏不空」）：快照尾页整带被显示
    /// 过滤（心跳带）时时间线空但库内有缺口——首帧判定即自动翻一页（TG 式接管，防「暂无
    /// 消息」误导 + 空屏无入口）。每会话期至多自动试一次（emptyBackfillTried，resetPagingState
    /// 重置）；回灌落地行合入后 messages 非空，UI 分支翻转为时间线（新 ScrollView onAppear
    /// 自然锚底）。若首回灌页仍整带被过滤，空态下的「加载更早」按钮保留为手动兜底。
    private var emptyBackfillTried = false

    private func maybeBackfillEmptyTimeline() {
        guard !emptyBackfillTried, selected != nil, messages.isEmpty, canLoadEarlier else { return }
        emptyBackfillTried = true
        requestEarlierAuto()
    }

    /// 切换会话/登出时清空合帧在途态：代际自增作废旧 flush，清空后首帧快照零延迟直通。
    private func resetMessagesStream() {
        messagesEpoch += 1
        messagesFlushTask?.cancel()
        messagesFlushTask = nil
        pendingMessages = nil
        lastMessagesFlushAt = nil
    }

    /// #5（2026-11-06 UX 轮）：own-send 强制贴底信号——send() 置位，timeline 在下一帧
    /// 消息变化时消费（无论当时阅读位置）；仅 onChange 回调读写，不参与 body 依赖。
    private var ownSendScrollPending = false

    func send(_ text: String) {
        guard let c = selected else { return }
        ownSendScrollPending = true
        // #3（2026-11-08）：排队态已下沉为消息级落库状态——KMP 侧 DefaultCommandClient 在
        // 乐观行插行时按发送时刻会话 run 态落 queued/sending（run 终态 releaseQueuedRows
        // 统一释放），Swift 侧只读 m.uiState 渲染，不再维护会话级水位判定（旧 isQueuedRow
        // 水位=0 时把全会话用户行误染排队——#4 历史行误变的根因，已随水位机制一并退役）。
        bridge.hubSend(conversationId: c.conversationId, text: text)
    }

    // ---------- v1.2.0 多模态 v1（设计稿 docs/multimodal-design-20261108.md §6 iOS 行） ----------

    /// 发送带附件消息（与 [send] 同通道同贴底语义；附件引用 = 端点 #23 上传产物，
    /// ≤4 项、mediaId 必带）。契约「message 与 attachments 至少一项非空」由调用方保证
    ///（composer 侧 refs 非空或文本非空才可发）。
    func sendWithAttachments(_ text: String, refs: [AttachmentRef]) {
        guard let c = selected, !refs.isEmpty else { return }
        ownSendScrollPending = true
        bridge.hubSendWithAttachments(conversationId: c.conversationId, text: text, attachments: refs)
    }

    /// 媒体上传（端点 #23 直调，不走幂等队列——见桥 [AgentBridge.hubUploadMedia] 注释）。
    /// onDone upload 非 null = 成功（mediaId 随 sendWithAttachments 提交；草稿挂 chip 用）；
    /// null = 失败（未登录/网络不可达/类型大小拒绝 400 HUB-BIZ-004 等），error = 人读原因，
    /// 调用方须显式呈现失败 + 可重试，不静默。
    func uploadMedia(
        conversationId: String,
        data: Data,
        mime: String,
        fileName: String?,
        width: Int?,
        height: Int?,
        onDone: @escaping (UiMediaUpload?, String?) -> Void,
    ) {
        let bytes = kotlinByteArray(from: data)
        bridge.hubUploadMedia(
            conversationId: conversationId,
            bytes: bytes,
            mime: mime,
            fileName: fileName,
            width: width.map { KotlinInt(int: Int32($0)) },
            height: height.map { KotlinInt(int: Int32($0)) },
        ) { upload, error in
            onDone(upload, error)
        }
    }

    /// 媒体内容拉取（端点 #24 Bearer 分支；凭据只在 KMP 内圈，本方法即 Swift 侧唯一出口——
    /// 消息行媒体加载器 [HubMediaLoader] 专用）。onDone data 非 null = 成功（原始字节）；
    /// null = 失败，error = 人读原因（加载器只分「有图/失败」两态）。
    func fetchMedia(mediaId: String, onDone: @escaping (Data?, String?) -> Void) {
        bridge.hubFetchMedia(mediaId: mediaId) { bytes, error in
            onDone(bytes.map(dataFromKotlinByteArray), error)
        }
    }

    // ---------- v1.3 发送链（TG 水准）：会话草稿可存活层 + 补传发送 ----------

    /// 附件上限（契约 v1.2.1：MessageSendRequest.attachments maxItems 9——2026-11-10 放宽，
    /// 对齐移动端相册多选口径；PhotosPicker maxSelectionCount 同值）。
    static let maxDraftAttachments = 9

    /// 会话草稿（conversationId → 草稿）。发送成功清空；登出清空。
    private(set) var drafts: [String: ConversationDraft] = [:]

    /// 草稿附件上传任务（slotId → 任务）：选图期在途任务与发送期补传任务同一登记——
    /// 发送补传对「上传中」的槽**等待复用任务产物**，不重复发起 #23（同槽双传浪费
    /// 20 次/分限流配额且产生孤儿媒体对象）。
    private var draftUploadTasks: [UUID: Task<UiMediaUpload?, Never>] = [:]

    /// 发送在途标记（conversationId）：发送按钮 loading 态 + 防重复点击（验收③）。
    private(set) var sendingDraftFor: Set<String> = []

    /// 最近一次发送失败原因（composer 显式降级展示，不静默；下次发送尝试清空）。
    private(set) var draftSendError: [String: String] = [:]

    /// 草稿读取（无草稿 = 空草稿，不落键）。
    func draft(for conversationId: String) -> ConversationDraft {
        drafts[conversationId] ?? ConversationDraft()
    }

    /// 草稿文本写入（composer TextField 双向绑定 set 侧；全空时收键防字典无界增长，
    /// 同步清残留的发送失败提示）。
    func setDraftText(_ text: String, conversationId: String) {
        var d = draft(for: conversationId)
        d.text = text
        if d.text.isEmpty && d.attachments.isEmpty {
            drafts.removeValue(forKey: conversationId)
            draftSendError.removeValue(forKey: conversationId)
        } else {
            drafts[conversationId] = d
        }
    }

    /// 草稿是否可发送（验收②：纯图无文本允许发送）。
    func draftHasContent(_ conversationId: String) -> Bool {
        let d = draft(for: conversationId)
        return !d.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !d.attachments.isEmpty
    }

    /// PhotosPicker 选中项入列（追加语义，再次打开选择器可续选；槽位满 9 后静默忽略溢出，
    /// 选择器侧 maxSelectionCount 同口径兜底）。同 identifier 去重（onChange 对同批选中重放）。
    @MainActor
    func addPickedImages(conversationId: String, items: [PhotosPickerItem]) {
        var d = draft(for: conversationId)
        let known = Set(d.attachments.compactMap(\.pickerItemIdentifier))
        for item in items {
            guard let pid = item.itemIdentifier, !known.contains(pid),
                  d.attachments.count < Self.maxDraftAttachments else { continue }
            let slot = DraftAttachment(
                id: UUID(), pickerItemIdentifier: pid, preview: nil,
                fileName: Self.draftFileName(), upload: nil, error: nil, uploading: true, sourceData: nil,
            )
            d.attachments.append(slot)
            drafts[conversationId] = d
            processDraftSlot(conversationId, slotId: slot.id, item: item)
        }
    }

    /// 槽位 ✕ 撤除（撤除在途/失败/就绪槽均可；在途 #23 请求不撤回，回调落空槽为 no-op）。
    /// 草稿随之全空时同步清残留发送失败提示。
    func removeDraftAttachment(_ conversationId: String, slotId: UUID) {
        guard var d = drafts[conversationId],
              let idx = d.attachments.firstIndex(where: { $0.id == slotId }) else { return }
        d.attachments.remove(at: idx)
        if d.text.isEmpty && d.attachments.isEmpty {
            drafts.removeValue(forKey: conversationId)
            draftSendError.removeValue(forKey: conversationId)
        } else {
            drafts[conversationId] = d
        }
        draftUploadTasks.removeValue(forKey: slotId)
    }

    /// 失败槽点按重试：用已压缩字节重走 #23（服务端限流 20 次/分/设备，重试节流由用户
    /// 点按节律天然承担）。任务进 draftUploadTasks 登记——发送期补传可等待复用。
    func retryDraftUpload(_ conversationId: String, slotId: UUID) {
        guard let slot = draft(for: conversationId).attachments.first(where: { $0.id == slotId }),
              slot.upload == nil, !slot.uploading, let data = slot.sourceData else { return }
        let w = slot.preview.map { Int($0.size.width * $0.scale) }
        let h = slot.preview.map { Int($0.size.height * $0.scale) }
        let fileName = slot.fileName
        draftUploadTasks[slotId] = Task { @MainActor () -> UiMediaUpload? in
            await self.performUpload(conversationId, slotId: slotId, data: data,
                                     mime: "image/jpeg", fileName: fileName, width: w, height: h)
        }
    }

    /// 发送草稿（验收②③④）：逐槽补传（就绪复用 / 在途等待 / 失败补传）→ 全部 mediaId
    /// 就绪后**一条消息**发出（content=文本 + attachments=mediaId 数组；纯文本无附件走
    /// v1.1 同形 hub.send 通道，字节级不变）。任一槽失败 = 整体不发送（不发半截消息）：
    /// pending 区与文本原样保留、失败槽呈可重试态、composer 显式降级提示——不静默。
    /// 发送在途置 sendingDraftFor（按钮 loading + 防重复点击）；全部成功清空已发送内容
    ///（保留发送期间新输入的差量）。onResult(true) 后调用方清 picker 选中态。
    func sendComposerDraft(_ conv: UiConversation, onResult: ((Bool, String?) -> Void)? = nil) {
        let cid = conv.conversationId
        guard isLoggedIn else { onResult?(false, "未登录"); return }
        guard !sendingDraftFor.contains(cid) else { return }
        guard let snapshot = drafts[cid], draftHasContent(cid) else { return }
        let text = snapshot.text.trimmingCharacters(in: .whitespacesAndNewlines)
        sendingDraftFor.insert(cid)
        draftSendError[cid] = nil
        let slotIds = snapshot.attachments.map(\.id)
        Task { @MainActor in
            defer { self.sendingDraftFor.remove(cid) }
            var refs: [AttachmentRef] = []
            var failure: String? = nil
            for slotId in slotIds {
                // 发送期间被 ✕ 撤除的槽 → 跳过（用户已裁决该图不随本条消息发出）；
                // await 前后各查一次（上传等待期间撤除同判）。
                guard self.drafts[cid]?.attachments.contains(where: { $0.id == slotId }) == true else { continue }
                let upload = await self.ensureSlotUploaded(cid, slotId: slotId)
                guard let slotAfter = self.drafts[cid]?.attachments.first(where: { $0.id == slotId }) else { continue }
                if let upload {
                    refs.append(Self.attachmentRef(slot: slotAfter, upload: upload))
                } else {
                    failure = "第 \(refs.count + 1) 张图片上传失败" + ((slotAfter.error).map { "：\($0)" } ?? "")
                    break
                }
            }
            if let failure {
                self.draftSendError[cid] = failure
                onResult?(false, failure)
                return
            }
            // 文本与全部 pending 图一条消息发出（ownSend 贴底信号与 hub.send 同语义）
            self.ownSendScrollPending = true
            if refs.isEmpty {
                self.bridge.hubSend(conversationId: cid, text: text)
            } else {
                self.bridge.hubSendWithAttachments(conversationId: cid, text: text, attachments: refs)
            }
            self.clearSentDraft(cid, sentSlotIds: Set(slotIds), sentText: snapshot.text)
            for slotId in slotIds { self.draftUploadTasks.removeValue(forKey: slotId) }
            onResult?(true, nil)
        }
    }

    /// 发送成功后的清空（验收③「全部成功后清空 pending 区」）：只清本次快照内容——
    /// 发送期间新输入的文本差量与新增槽位保留（不吞并行输入）。
    private func clearSentDraft(_ cid: String, sentSlotIds: Set<UUID>, sentText: String) {
        var remaining = draft(for: cid)
        remaining.attachments.removeAll { sentSlotIds.contains($0.id) }
        var curText = remaining.text
        // 发送期间用户在快照文本之后续写 → 剥离已发出的前缀；前缀不符（罕见：编辑了前段）
        // 保守清空，不残留在已发内容之后的无上下文片段。
        if curText.hasPrefix(sentText) {
            curText = String(curText.dropFirst(sentText.count))
        } else {
            curText = ""
        }
        remaining.text = curText
        if remaining.text.isEmpty && remaining.attachments.isEmpty {
            drafts.removeValue(forKey: cid)
        } else {
            drafts[cid] = remaining
        }
    }

    /// 单槽上传就绪（发送补传核心）：就绪复用 → 在途任务等待（不重复发起 #23）→
    /// 未传/失败的补传。无压缩字节（读图/压缩失败的槽）返回 nil（发送整体失败，槽呈
    /// 失败态由用户 ✕ 或重试裁决）。
    @MainActor
    private func ensureSlotUploaded(_ cid: String, slotId: UUID) async -> UiMediaUpload? {
        guard let slot = draft(for: cid).attachments.first(where: { $0.id == slotId }) else { return nil }
        if let upload = slot.upload { return upload }
        if slot.uploading, let task = draftUploadTasks[slotId] {
            return await task.value
        }
        guard let data = slot.sourceData else { return nil }
        let w = slot.preview.map { Int($0.size.width * $0.scale) }
        let h = slot.preview.map { Int($0.size.height * $0.scale) }
        let fileName = slot.fileName
        let task = Task { @MainActor () -> UiMediaUpload? in
            await self.performUpload(cid, slotId: slotId, data: data,
                                     mime: "image/jpeg", fileName: fileName, width: w, height: h)
        }
        draftUploadTasks[slotId] = task
        return await task.value
    }

    /// 选图期槽处理（store 承接原视图侧链）：拷入字节 → 压缩（R9）→ 预览/字节落位 →
    /// #23 上传 → 就绪/失败落位。失败呈显式失败态（不静默），可点重试/发送时补传。
    @MainActor
    private func processDraftSlot(_ conversationId: String, slotId: UUID, item: PhotosPickerItem) {
        let task = Task { @MainActor () -> UiMediaUpload? in
            guard let raw = try? await item.loadTransferable(type: Data.self), !raw.isEmpty else {
                self.failSlot(conversationId, slotId: slotId, "无法读取所选图片")
                return nil
            }
            guard let prepared = MediaImageProcessing.prepare(raw, fileName: Self.draftFileName()) else {
                self.failSlot(conversationId, slotId: slotId, "无法识别的图片格式")
                return nil
            }
            self.mutateSlot(conversationId, slotId: slotId) {
                $0.preview = MediaImageProcessing.decodeForDisplay(prepared.data, maxPixel: 512)
                $0.sourceData = prepared.data
                $0.fileName = prepared.fileName
            }
            return await self.performUpload(conversationId, slotId: slotId, data: prepared.data,
                                            mime: prepared.mime, fileName: prepared.fileName,
                                            width: prepared.width, height: prepared.height)
        }
        draftUploadTasks[slotId] = task
    }

    /// #23 上传执行（选图/重试/发送补传三路共用；完成落 upload/error 态，不动用户已见槽序）。
    /// width/height 透传为 nil 时不带表单字段（契约可选；不发明 0 值）。槽位状态变更全部在
    /// 本 @MainActor await 侧落位——桥回调只 resume continuation（回调线程不承诺 actor 隔离，
    /// 不在回调闭包内直触 @Observable 状态）。
    @MainActor
    private func performUpload(_ conversationId: String, slotId: UUID, data: Data, mime: String,
                               fileName: String, width: Int?, height: Int?) async -> UiMediaUpload? {
        mutateSlot(conversationId, slotId: slotId) { $0.uploading = true; $0.error = nil }
        let result: (upload: UiMediaUpload?, error: String?) = await withCheckedContinuation { continuation in
            self.uploadMedia(
                conversationId: conversationId,
                data: data, mime: mime, fileName: fileName,
                width: width, height: height,
            ) { upload, error in
                continuation.resume(returning: (upload, error))
            }
        }
        mutateSlot(conversationId, slotId: slotId) {
            $0.uploading = false
            if let upload = result.upload {
                $0.upload = upload
                $0.error = nil
            } else {
                // 显式失败态（HUB-BIZ-004 类型/大小拒绝、网络不可达等），点按重试
                $0.error = result.error ?? "上传失败"
            }
        }
        return result.upload
    }

    /// 槽位失败落位（读图/压缩失败早退；不整槽删除——用户可见失败态再裁决）。
    @MainActor
    private func failSlot(_ conversationId: String, slotId: UUID, _ message: String) {
        mutateSlot(conversationId, slotId: slotId) {
            $0.uploading = false
            $0.error = message
        }
    }

    /// 槽位原位变更（槽可能已被 ✕ 撤除：无槽即 no-op；在途 #23 回调落空槽自然吞掉）。
    @MainActor
    private func mutateSlot(_ conversationId: String, slotId: UUID, _ mutate: (inout DraftAttachment) -> Void) {
        guard var d = drafts[conversationId],
              let idx = d.attachments.firstIndex(where: { $0.id == slotId }) else { return }
        mutate(&d.attachments[idx])
        drafts[conversationId] = d
    }

    /// 草稿槽 → AttachmentRef（mediaId 必带 + mimeType/fileName/sizeBytes/width/height
    /// 展示回显字段）。UiMediaUpload.sizeBytes 为非空 Kotlin Long（Swift Int64）；
    /// AttachmentRef.sizeBytes 为可空 Long?（Swift KotlinLong?）——装箱传入。
    private static func attachmentRef(slot: DraftAttachment, upload: UiMediaUpload) -> AttachmentRef {
        AttachmentRef(
            mediaId: upload.mediaId,
            mimeType: upload.mimeType,
            fileName: slot.fileName,
            sizeBytes: KotlinLong(longLong: upload.sizeBytes),
            width: upload.width,
            height: upload.height,
        )
    }

    private static func draftFileName() -> String {
        "image-\(Int(Date().timeIntervalSince1970)).jpg"
    }

    // ---------- 崩溃防护 + 本地清理（2026-11-07 UX 轮） ----------

    /// 后台/锁屏暂停同步栈（App.swift scenePhase 离开 active 驱动）：桥侧停连接循环并优雅
    /// 收尾 WSS，防挂起竞态下 Ktor Darwin 会话错误回调 abort（0.5.4 真机锁屏解锁 SIGABRT）。
    func suspendSync() {
        bridge.hubSuspendSync()
    }

    /// 回前台恢复同步栈（与 suspendSync 成对；幂等）。
    func resumeSync() {
        bridge.hubResumeSync()
    }

    /// 本地删除单条消息（脏数据清理出口）：仅清投影库行，Flow 即推删除后列表；无服务端命令，
    /// 服务端权威数据经快照/校准可能回流（语义=本地清理，非服务端撤回）。
    func deleteLocalMessage(_ m: UiMessage) {
        guard let c = selected else { return }
        bridge.hubDeleteLocalMessage(conversationId: c.conversationId, messageId: m.messageId)
    }

    /// 消费 own-send 贴底信号（timeline 专用；读后即清，仅触发一次滚动）。
    func consumeOwnSendScrollPending() -> Bool {
        defer { ownSendScrollPending = false }
        return ownSendScrollPending
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

    /// wp-12：持续已读上报基线（桌面 ConversationScreen reportedReadUpTo 同型；会话期状态，
    /// 随 open/close/登出经 resetPagingState 重置）。只推进不回退：同值重复不重发（渲染侧
    /// onChange 逐消息批触发，基线吞掉同值避免幂等队列噪声命令），列表回退（会话期快照重建
    /// 清行只回灌尾页）不回退游标——游标是「看到哪」的单调水位，回退只会制造假未读。
    private var reportedReadUpTo: Int64 = 0

    /// 上报已读（会话页渲染侧持续驱动，seq = 已渲染最大真实 seq）：只推进不回退（wp-12）。
    /// 每次推进 = 幂等队列 read.cursor 新意图新键（ADR-07）；桥侧失败静默（尽力而为增强面）。
    func markReadUpTo(_ seq: Int64) {
        guard seq > reportedReadUpTo, let c = selected else { return }
        reportedReadUpTo = seq
        bridge.hubMarkRead(conversationId: c.conversationId, readUpTo: seq)
    }

    // ---------- wp-13：通知面（iOS 本地通知 + 按会话静音） ----------

    // 触发面分工（scope：approval.requested / assistant final 且非当前打开 / run 终态完成失败）：
    // - 回复：会话列表 Flow 差异（已知卡片 unread 增量）——「非当前打开」是 Swift UI 态，
    //   静音判定与点按跳转也都需要 conversationId，只在此可得；校准延迟带来的延迟提醒可接受。
    // - 审批、run 终态：AgentBridge eventTap（投影把 run 三种终态压成同一 hasActiveRun=0，
    //   Flow 面无法还原事件类型；审批走事件旁路则免 bootstrap 快照积压行的假阳性，见
    //   noteApprovalRequested）。conversationId 由桥侧 hubConvKeys 解析（未映射 = 空串），
    //   Swift 侧按归属可得性显式降级，不编造归属。

    /// #4（2026-11-07 二轮）：本地通知派发门槛——仅前台（后台由 APNs 单通道承载，前台 APNs
    /// 横幅已在 NotificationCoordinator.willPresent 全量抑制：前台=本地/后台=远端，无双响）；
    /// 重建（rebuilding）期间的会话列表推送是快照重放，旧消息 unread 差异不算「新回复」。
    private var shouldNotifyLocally: Bool {
        UIApplication.shared.applicationState == .active && syncPhase != "rebuilding"
    }

    /// message.created（assistant final）事件（桥 eventTap 回调，主线程；v1.3 通知摘要轮）。
    /// 回复通知触发面（替代旧会话列表 unread 差异）：摘要需要消息正文与附件数——事件载荷是
    /// 唯一可得面（卡片 preview 是裸 content，纯附件行剥不出「[图片]×N」计数）。判定链与
    /// 旧触发面同口径：只对已知卡片提醒（列表未及校准/非主线会话不提醒，与旧「只对已知
    /// 卡片」语义一致）、非当前打开（消息在时间线实时可见）、按会话静音、前台 + 非重建期
    ///（#4 派发门槛；后台由 APNs 承载——「前台=本地/后台=APNs」通道切分不动）。
    /// 断线窗口的回复经重连补投事件触发（cursor 去重后对客户端确属新事件）；bootstrap
    /// 快照积压行不经事件路径，天然不带假阳性。
    private func noteReplyMessage(conversationId: String, content: String, attachmentCount: Int) {
        guard isLoggedIn, !conversationId.isEmpty else { return }
        guard let c = conversations.first(where: { $0.conversationId == conversationId }) else { return }
        guard selected?.conversationId != conversationId else { return }   // 当前打开不提醒（scope 语义）
        guard !mutedConversations.contains(conversationId) else { return } // 按会话静音
        guard shouldNotifyLocally else { return }                          // #4：后台/重建期不派发本地通知
        let summary = MessageSummary.make(content: content, attachments: attachmentCount)
        notifications.replyArrived(conversationId: conversationId, label: displayLabel(c), summary: summary)
    }

    /// approval.requested 事件（桥 eventTap 回调，主线程）。触发面用事件旁路而非审批 Flow
    /// 差异：快照不经过 applyEvent，bootstrap 带回的积压 pending 行天然不提醒；断线补投的
    /// 事件经 cursor/幂等去重后仍会触发（对客户端确属新事件）。conversationId 空串 = 未映射
    /// 会话：无归属、无法静音判定——审批仍具行动性（设置页待审批清单可见），降级为无归属
    /// 提醒（不编造会话名，与 UiApproval conversationId 同纪律）。
    private func noteApprovalRequested(conversationId: String) {
        guard isLoggedIn else { return }
        let attributed = !conversationId.isEmpty
        if attributed, mutedConversations.contains(conversationId) { return }
        guard shouldNotifyLocally else { return } // #4：后台/重建期不派发本地通知（后台 APNs 承载）
        let label: String? = attributed
            ? conversations.first { $0.conversationId == conversationId }.map(displayLabel)
            : nil
        notifications.approvalRequested(
            conversationId: attributed ? conversationId : nil,
            label: label
        )
    }

    /// run 终态事件（桥 eventTap 回调，主线程）。conversationId 空串 = 未映射会话：无归属、
    /// 无法静音判定与跳转，显式降级不提醒（不编造归属，与 UiApproval conversationId 同纪律）。
    /// 2026-11-07 用户拍板：run 完成不再推送——heartbeat 周期 run 的「运行完成」是噪声，
    /// 实际回复由 unread 增量路径（noteConversationsPush → replyArrived）承载；原「完成+回复」
    /// 双响 3s 抑制窗随之移除（窗若保留会吞掉完成后的真实回复通知）。失败仍提醒（异常需要
    /// 知道，且失败 run 无回复可依赖）。
    private func noteRunTerminal(conversationId: String, runId: String, status: String) {
        guard isLoggedIn, !conversationId.isEmpty else { return }
        guard !mutedConversations.contains(conversationId) else { return }
        guard shouldNotifyLocally else { return } // #4：后台/重建期不派发本地通知（后台 APNs 承载）
        guard status == "failed" else { return } // completed 等其余值不推送（桥只发两种）
        let label = conversations.first { $0.conversationId == conversationId }
            .map(displayLabel) ?? "会话"
        notifications.runFailed(conversationId: conversationId, label: label)
    }

    /// 通知点按跳会话（coordinator 回调；可能在非主线程，回主队列再动 UI 态）。
    /// 卡片不在列表现值中（投影延迟/会话已删）时只打开 App 不跳转，不编造会话。
    private func handleNotificationTap(conversationId: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.isLoggedIn else { return }
            guard let conv = self.conversations.first(where: { $0.conversationId == conversationId }) else { return }
            self.selectedTab = 0
            self.openViaPush(conv)
        }
    }

    /// 卡片显示名（与 HubChatView.title 同式：label 空 → agentId → 「会话」兜底）。
    private func displayLabel(_ c: UiConversation) -> String {
        if !c.label.isEmpty { return c.label }
        return c.agentId.isEmpty ? "会话" : c.agentId
    }

    /// 按会话静音开关（本地偏好存储；跨登出保留）。
    func setMuted(_ muted: Bool, conversationId: String) {
        let key = Self.mutedKeyPrefix + conversationId
        if muted {
            UserDefaults.standard.set(true, forKey: key)
            mutedConversations.insert(conversationId)
        } else {
            UserDefaults.standard.removeObject(forKey: key)
            mutedConversations.remove(conversationId)
        }
    }

    private static let mutedKeyPrefix = "hub.muted."

    private static func loadMutedConversations() -> Set<String> {
        let defaults = UserDefaults.standard
        let names = defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix(mutedKeyPrefix) }
        return Set(names.compactMap { name in
            defaults.bool(forKey: name) ? String(name.dropFirst(mutedKeyPrefix.count)) : nil
        })
    }

    // ---------- wp-12：历史翻页（加载更早） ----------

    /// 会话打开/关闭/登出时重置翻页态与已读基线（游标、锁存与只推进水位均随会话期，
    /// 不跨会话残留——wp-12 持续已读上报基线同批重置，重开会话首帧照报）。
    private func resetPagingState() {
        earlierExhausted = false
        earlierCursor = nil
        autoEarlierBlocked = false
        loadingEarlier = false
        loadEarlierError = nil
        restoreAnchorMessageId = nil
        restoreArmed = false
        canLoadEarlier = false
        emptyBackfillTried = false
        reportedReadUpTo = 0
    }

    /// 乐观行占位 seq（DefaultCommandClient message.send 插行 seq=Long.MAX_VALUE，message.created
    /// 回执后才取得服务端 seq）——已读上报与翻页判据均不得把该行计入（桌面
    /// ConversationScreen.OPTIMISTIC_SEQ_SENTINEL 同值同语义，wp-12 两端 parity）。
    static let optimisticSeqSentinel = Int64.max

    /// 重算「加载更早」入口可见性：翻到头锁存后恒隐藏；否则按「会话 lastSeq（实时行，随 run/
    /// 消息事件推进）> 已载**真实 final 行数**」乐观判定（wp-12 修正：分母不再用 messages.count
    /// ——合成行（role=""，压缩/重置标记）与乐观行（占位 seq）计入 count 会虚高分母，合成行
    /// 密集会话首翻前少出按钮；真实 final 行数只数 state="final" 的服务端行）——快照尾页 50 行
    /// + 长会话（>50 行）即出按钮，首翻后由服务端 hasMore 接管（loadEarlier 回执）。
    /// 2026-11-10 加载更早修复轮：撤「messages 非空」守卫——快照尾页整页被显示过滤（心跳带）
    /// 时时间线为空但库内有行，入口仍须可出（游标由 Kotlin 按 DB 最老已载行原始口径解析，
    /// 不再依赖显示首行）；库内无行时 Kotlin 返回 null 呈降级态，optimistic 判据 lastSeq>0
    /// 对真空会话（lastSeq=0）恒 false 不出按钮。
    private func refreshCanLoadEarlier() {
        guard !earlierExhausted, let c = selected else {
            canLoadEarlier = false
            return
        }
        let lastSeq = liveSelected?.lastSeq ?? c.lastSeq
        canLoadEarlier = lastSeq > Int64(realLoadedRowCount)
    }

    /// 已载真实服务端 final 行数（翻页乐观判据分母）：state="final" 且非合成行（role 非空）
    /// 且非乐观行（真实 seq）。streaming 占位行（seq 尚未服务端定档）与 failed 行不计——
    /// 分母低估只会让按钮偏保守早出（首翻后 hasMore 权威接管），偏保守方向安全。
    private var realLoadedRowCount: Int {
        messages.filter {
            $0.state == "final" && !$0.role.isEmpty && $0.seq != Self.optimisticSeqSentinel
        }.count
    }

    /// 上滑近顶自动翻页触发（2026-11-10 加载更早修复轮，TG 式体验）：会话页滚动近顶时由
    /// HubChatView 滚动几何回调调用。串行节流全在 [loadEarlier] 守卫内收敛（loadingEarlier
    /// 互斥 / exhausted 锁存 / 失败阻断 / canLoadEarlier 判据），滚动中逐帧重复触发为幂等空转。
    func requestEarlierAuto() {
        loadEarlier(auto: true)
    }

    /// 加载更早（wp-12）：按 oldestSeq 翻页（REST #8 before=游标，行经 upsertMessage 合并进
    /// 投影库，observeMessages Flow 自动推送合并后 seq 升序全序列）。游标 = 最近成功页的
    /// oldestSeq（即新 DB 下沿）；nil = 首翻，由 Kotlin 按 DB 最老已载行 seq **原始口径**
    /// （含被显示过滤的行）解析，stale 游标（会话期快照重建）同样在 Kotlin 侧回落——显示
    /// 首行不再参与游标判定（2026-11-10 加载更早修复轮：口径自本层下沉数据源头，旧显示口径
    /// 在整页被过滤的心跳带处游标重置、同带反复重取，「加载更早」拉不进更早历史）。成功后
    /// 服务端 hasMore 接管按钮可见性，翻到头锁存隐藏。失败（回执 nil）显式降级态可重试，
    /// 不静默；[auto] 触发失败另置 autoEarlierBlocked 停自动链，手动按钮重试成功即解锁。
    /// 限流纪律：一次一请求（loadingEarlier 互斥）。
    func loadEarlier(auto: Bool = false) {
        guard let c = selected, canLoadEarlier, !loadingEarlier, !earlierExhausted else { return }
        guard !auto || !autoEarlierBlocked else { return }
        #if DEBUG
        print("[paging] loadEarlier(auto=\(auto)) dispatch: cursor=\(String(describing: earlierCursor)) oldestDisplayed=\(messages.first?.seq ?? -1)")
        #endif
        loadingEarlier = true
        loadEarlierError = nil
        // 阅读位置恢复锚 = 触发前最老一行（新页将合入其上方，落地后滚回该行，视口不跳）；
        // 时间线空（尾页整带被过滤）时无锚，落地不滚回（新显示行随 Flow 自然出现）。
        restoreAnchorMessageId = messages.first?.messageId
        restoreArmed = false
        // before=nil 交由 Kotlin 按 DB 最老已载行原始口径解析；Int64 → KotlinLong? 装箱
        // （ObjC 互携可空 Long?，与 AttachmentRef.sizeBytes 同式，见 attachmentRef 注）。
        let before = earlierCursor.map { KotlinLong(longLong: $0) }
        bridge.hubLoadEarlier(conversationId: c.conversationId, before: before) { [weak self] page in
            guard let self else { return }
            self.loadingEarlier = false
            guard let page else {
                if auto { self.autoEarlierBlocked = true }
                self.restoreAnchorMessageId = nil
                self.restoreArmed = false
                self.loadEarlierError = "加载更早失败（网络或服务不可达），可重试"
                return
            }
            // 游标链推进（服务端 oldestSeq = 新 DB 下沿）；空页无法推进 = 终止（防同游标循环重试）。
            // Kotlin Long? 经 ObjC 互携为 KotlinLong?，此处取 int64Value 归一为 Int64。
            self.earlierCursor = page.oldestSeq?.int64Value
            self.autoEarlierBlocked = false
            #if DEBUG
            print("[paging] loadEarlier done(auto=\(auto)): count=\(page.count) hasMore=\(page.hasMore) oldestSeq=\(page.oldestSeq?.int64Value ?? -1) latching=\(page.count == 0 || !page.hasMore)")
            #endif
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

    /// 设备清单（设备管理页；每次进入拉新）。devices null = 拉取失败 → devicesError 显式
    /// 呈现（wp-11 设备管理错误态，不静默）；非 null 成功即清错误态。
    func refreshDevices() {
        bridge.hubDevices { [weak self] list, error in
            guard let self else { return }
            if let list {
                self.devicesCache = list
                self.devicesError = nil
            } else {
                self.devicesError = error ?? "设备清单加载失败"
            }
        }
    }

    var devices: [DeviceInfo]? { devicesCache }

    /// 撤销设备。ok false = 撤销未送达（网络/服务不可达）→ revokeFailure 显式降级，可再点
    /// 「撤销」重试；成功即清失败态并刷新清单（wp-11 设备管理错误态）。
    func revokeDevice(_ deviceId: String) {
        bridge.hubRevokeDevice(deviceId: deviceId) { [weak self] ok in
            guard let self else { return }
            if ok.boolValue {
                self.revokeFailure = nil
                self.refreshDevices()
            } else {
                self.revokeFailure = "撤销未送达（网络或服务不可达），可重试"
            }
        }
    }

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
        // wp-13：通知点按 → 跳对应会话；run 终态与审批请求事件（桥 eventTap）→ 通知面。
        notifications.onTapConversation = { [weak self] conversationId in
            self?.handleNotificationTap(conversationId: conversationId)
        }
        bridge.hubWatchRunTerminal { [weak self] conversationId, runId, status in
            self?.noteRunTerminal(conversationId: conversationId, runId: runId, status: status)
        }
        bridge.hubWatchApprovalRequested { [weak self] conversationId in
            self?.noteApprovalRequested(conversationId: conversationId)
        }
        // v1.3 通知摘要轮：assistant final 权威行 → 回复通知（摘要见 MessageSummary）。
        // 桥回调 Int 参数经 ObjC 互携为 KotlinInt 装箱（函数类型参数位）——解箱为 Int。
        bridge.hubWatchReplyMessage { [weak self] conversationId, content, attachmentCount in
            self?.noteReplyMessage(
                conversationId: conversationId,
                content: content,
                attachmentCount: Int(attachmentCount.int32Value),
            )
        }
        bridge.hubObserve { [weak self] kind, message, user, deviceId in
            guard let self else { return }
            self.kind = kind
            self.message = message
            self.userDisplay = user
            self.deviceId = deviceId
            if kind != "working" { self.isRestoring = false }
            // wp-9：登录态建立（hubClient 就绪）即挂待审批订阅；重复回调由桥侧换订阅。
            // 不挂在视图 .task（与 watchConversations 不同）：审批计数在设置页也要活，须跟登录态走。
            if kind == "loggedIn" {
                self.watchApprovals()
                // wp-10：登录态建立（hubClient 就绪）即挂连接相位订阅——提示条随相位翻转，
                // 断连/重建可见、追平（linked）消失。重复回调由桥侧换订阅。
                self.watchPhase()
                // wp-13：登录态建立即请求通知授权（系统只在首次未决定时弹窗；拒绝后静默降级）。
                self.notifications.requestAuthorizationIfNeeded()
                // wp-17：同一时机注册远程通知；token 由 AppDelegate 注册回调取到后经
                // [reportApnsToken] 上报（REST #20，幂等覆盖写）。
                self.notifications.registerForRemoteNotifications()
            }
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

    /// wp-17：APNs 设备 token 上报（AppDelegate 注册回调 → 本方法 → 桥）。必须走本实例自持的
    /// bridge：桥内 hubClient 与登录态同生命周期，另起 AgentBridge 实例没有登录栈，上报会被
    /// 静默丢弃。失败（未登录/网络不可达/撤销设备 403）在桥侧静默吞掉——尽力而为的增强面。
    /// 登出不主动上报空串退订（设备已撤销，上报必 403），见 App.swift AppDelegate 注释。
    func reportApnsToken(_ token: String) {
        bridge.hubReportApnsToken(token: token)
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
        workActivity = nil
        creatingConversation = false
        createConversationError = nil
        createdConversationFor = []
        devicesCache = nil
        devicesError = nil
        revokeFailure = nil
        // #21：动态命令目录随登出清空（重登后从空缓存 bootstrap，下次 "/" 起手重拉）
        commandsCache = [:]
        commandsFetchedAt = [:]
        commandsInFlight = []
        commandsFailed = []
        commandsSlashSession = [:]
        // #22：模型目录缓存随登出清空（同命令目录纪律）
        modelsCache = [:]
        modelsFetchedAt = [:]
        modelsInFlight = []
        chatPush = false
        // wp-10：连接相位复位（同步栈已停转，重登后 watchPhase 重新挂订阅）
        syncPhase = "catching"
        syncBannerVisible = false
        bannerDebounceTask?.cancel()
        bannerDebounceTask = nil
        // v1.3 发送链：会话草稿与发送在途态随登出作废（草稿属账号数据面，不跨登出残留）
        drafts = [:]
        draftUploadTasks = [:]
        sendingDraftFor = []
        draftSendError = [:]
        // mutedConversations 为本地偏好，跨登出保留。
        bridge.hubLogout()
    }
}
