import SwiftUI

// MARK: - 节拍纯表（与桌面 desktopui/ConversationScreen.kt typewriterTickMs/typewriterStep 对锁）

/// 打字机节拍（2026-11-28 流式逐字轮）。与桌面常量同值同义——回归值表锁在桌面
/// StreamingTypewriterPacingTest，单端改值必须双端同步。
///
/// 旧档「max(1, 积压/90) 字 / 16ms」废止：积压大时一 tick 跳多字（2s 窗 300 字积压 = 每
/// 16ms 跳 3~4 字）、抽干后空等到下一窗，且首窗挂载即贴齐、无等待光标——「流式输出粗糙」
/// 的来源（探针实证：快 agent 一条 800 字回复在首个 2s 合并窗内整段到达并紧接 final，
/// 旧口径下全程零逐字）。新口径 = **恒逐字**：每 tick 至多放 1 个字素（String.count），
/// 靠缩短 tick 提速而非加步长。
/// - tick = 追平视界 / 积压（毫秒整数除法），钳 [帧率档 16ms, 慢速档 32ms]：积压 ≤62 字走
///   慢速档（≈31 字/秒，可辨识的逐字节拍），≥125 字压到帧率档（≈60 字/秒，60Hz 每帧一字），
///   之间线性过渡——积压消退时 tick 渐次放慢到慢速档（尾段减速收尾）；
/// - 滞后上限：流式期积压 ≤750 字恒逐字（一次性到达的整条常见回复完整逐字放完）；超限
///   步进升档 ceil(积压/750)——持续快流（>60 字/秒）下显示滞后有界，此时每帧多字肉眼
///   已不可分辨；
/// - final 续打：行收敛 final 时剩余积压 ≤750 字**仍严格逐字**（一次性回复的续打即正文
///   本体，不得快进）；超出部分按 ceil((积压−750)/60) 在 ~60×(1+ln) tick 内追平后回到逐字
///   ——长回复尾包有界收敛，不一次跳出；
/// - 首窗：首次出现的流式行首窗 ≤1200 字从 0 逐字（超此量=迟到物化的长内容，贴齐不重打）。
enum TypewriterPacing {
    /// 慢速档 tick（毫秒）：小积压 ≈31 字/秒。
    static let baseTickMs: Int = 32
    /// 帧率档 tick（毫秒）：≈60 字/秒，逐字揭示的速度上限。
    static let minTickMs: Int = 16
    /// 追平视界（毫秒）：贴桥 2s 合并窗（delta-coalesce DEFAULT_COALESCE_WINDOW_MS）。
    static let horizonMs: Int = 2_000
    /// 流式期滞后上限（字素）：积压超限步进升档 ceil(积压/上限)。
    static let lagLimitChars = 750
    /// final 续打期严格逐字的积压上限（字素）。
    static let carrySingleLimit = 750
    /// final 续打期超出 [carrySingleLimit] 部分的追平除数（tick 量级）。
    static let carryDrainTicks = 60
    /// 等待期光标闪烁半周期（纳秒）。
    static let blinkNs: UInt64 = 530_000_000
    /// 首个流式行从 0 逐字的积压上限：首窗文本超此量仍贴齐，防迟到物化整段重打。
    static let firstChunkTypingLimit = 1_200

    /// 本 tick 时长（纳秒）：视界/积压（毫秒整数除法，与桌面 typewriterTickMs 逐值相等），
    /// 钳 [帧率档, 慢速档]；非正积压按 1。
    static func tickNs(backlog: Int) -> UInt64 {
        let ms = horizonMs / max(backlog, 1)
        return UInt64(Swift.min(Swift.max(ms, minTickMs), baseTickMs)) * 1_000_000
    }

    /// 流式期本 tick 步进（字素）：恒 1，仅积压超 [lagLimitChars] 时 ceil(积压/上限) 升档；
    /// 非正积压按 0。
    static func step(backlog: Int) -> Int {
        max(1, (max(backlog, 0) + lagLimitChars - 1) / lagLimitChars)
    }

    /// final 续打期本 tick 步进（字素）：积压 ≤ [carrySingleLimit] 恒 1；超出部分按
    /// ceil(超出/[carryDrainTicks]) 追平（积压按指数消退，回到上限内即逐字收口）。
    static func carryStep(backlog: Int) -> Int {
        let excess = backlog - carrySingleLimit
        guard excess > 0 else { return 1 }
        return max(1, (excess + carryDrainTicks - 1) / carryDrainTicks)
    }
}

// MARK: - 打字机交接账本

/// 打字机交接账本（主线程访问：SwiftUI 渲染与 .task 同在主 actor，未加隔离标注是因
/// HubMessageRow.init 需在首帧定打字起点——@MainActor 标注会让非隔离的 init 不可调）。
///
/// 三条职责：
/// 1. **final 续打**：流式行被 final 权威行替换时行身份（dstream:… → messageId）随之更换，
///    SwiftUI 视为全新行，旧打字机连同剩余积压一并离场——旧口径尾包一次性跳出。流式行每
///    tick 把进度记入本账本（按会话一键：同会话同时至多一条在途流式行），final 行首帧查得
///    「同会话 + 未过期 + 前缀吻合 + 确有剩余」即从记录进度续打（TTL 5s：行滚出视口后再
///    物化、快照/重进等迟到物化一律贴齐不续打）；
/// 2. **首窗逐字 / 重物化不重打**（流式行）：流式行由首个 delta 创建，旧口径「挂载即贴齐」
///    使每条回复的首个 2s 窗恒整块瞬显。按 messageId 辨别——本会话账本未见该行 = 首次出现，
///    从 0 逐字；已见（滚动往返重新物化、runActive 瞬态翻转重建分支）= 贴齐不重打；
/// 3. **实时新到的 final 行逐字**（探针实证的主路径，2026-11-28）：服务端事件表实查——
///    OpenClaw 网关近 3 天仅 2 条 message.delta 对 278 条 message.created，main 会话的回复
///    是 run.started → **整条 message.created** → run.completed，根本没有流式增量；快 agent 的
///    一次性回复也是合并窗 flush 与 final 同批落库、流式行在 UI 上零帧存活。两条路径下流式
///    行从未浮现，只给流式行做打字机 = 这类回复全程零逐字（「流式输出粗糙」的真根因）。
///    现由消息流入口登记实时新到（observeMessages：store 每次落定已打开会话的消息列表——
///    首帧 = 打开会话的历史加载不算，其后首次出现的助手 final 行且消息时间戳在近
///    freshFinalWindow 内），行视图据登记从 0 逐字打出；历史行（首载/翻页/重进）与迟到
///    物化（登记 30s 未起跑即过期）不重打。判定落在数据入口而非视图构造：视图值在真正
///    挂载前可被构造多次，构造期「先记已见再判定」会让真正挂载的那次读到已见（探针实证
///    首版因此零逐字）。
final class TypewriterHandoff {
    static let shared = TypewriterHandoff()

    struct Entry {
        var messageId: String
        var shown: Int
        /// 记录时刻的完整内容（续打前缀校验用；Swift String 写时复制，零拷贝）。
        var content: String
        var at: Date
    }

    /// 续打有效期（秒）：行身份更换同帧发生，迟到物化不续打。
    static let carryTTL: TimeInterval = 5
    /// 「实时新到」时间窗（秒）：消息时间戳（服务端权威）距现在不超过此值才算实时——历史行
    /// 不重打；取 2 分钟容纳设备与服务端的时钟偏差。
    static let freshFinalWindow: TimeInterval = 120
    /// 实时新到 final 行逐字的内容上限：超长整条（历史回灌/特大粘贴）贴齐不重打。
    static let freshFinalTypingLimit = 20_000
    /// 「实时新到」登记的有效期（秒）：登记后行迟迟未物化（用户不在该会话/滚在历史区）
    /// 则过期贴齐，防迟到物化时冷不丁重打一条旧消息。
    static let freshFinalPendingTTL: TimeInterval = 30

    private var entries: [String: Entry] = [:]
    /// 实时新到的 final 行登记（messageId → 登记时刻），打字机起跑即消耗。
    private var pendingFresh: [String: Date] = [:]
    /// 消息流观测状态（当前会话的已知消息 id）：首帧 = 历史加载，其后新出现的助手 final 行
    /// = 实时新到。切会话即复位（回来的首帧又是历史）。
    private var observedConversationId: String?
    private var knownMessageIds: Set<String> = []
    private var observedInitialFrame = false

    private static let tsFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private static let tsFormatterNoFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    /// 消息流观测输入（store 每次落定消息列表时逐行映射；只带判定所需字段）。
    struct ArrivalCandidate {
        let messageId: String
        let role: String
        let state: String
        let ts: String?
        let contentCount: Int
    }

    /// 该会话账本是否未见此行（= 首次出现的流式行）。
    func isNewStream(conversationId: String, messageId: String) -> Bool {
        guard !conversationId.isEmpty, !messageId.isEmpty else { return false }
        return entries[conversationId]?.messageId != messageId
    }

    /// 打字进度记账（每 tick）。
    func record(conversationId: String, messageId: String, shown: Int, content: String) {
        guard !conversationId.isEmpty else { return }
        entries[conversationId] = Entry(messageId: messageId, shown: shown, content: content, at: Date())
    }

    /// final 行续打起点：nil = 不续打（无记录/过期/前缀不吻合/无剩余积压）。
    func carryStart(conversationId: String, content: String) -> Int? {
        guard !conversationId.isEmpty, var e = entries[conversationId] else { return nil }
        guard Date().timeIntervalSince(e.at) < Self.carryTTL else {
            entries.removeValue(forKey: conversationId) // 过期即清（长内容串不滞留）
            return nil
        }
        e.shown = min(e.shown, e.content.count)
        guard e.shown > 0, e.shown < content.count, content.hasPrefix(e.content) else { return nil }
        return e.shown
    }

    /// 消息流观测（store 每次落定会话消息列表时调用）：已打开会话里**首次出现**的助手
    /// final 行登记为实时新到。首帧（打开会话的历史加载）只记已知不算新到；消息时间戳
    /// 须在近 [freshFinalWindow] 内（翻页回灌的旧行不算）。
    func observeMessages(conversationId: String, _ list: [ArrivalCandidate]) {
        guard !conversationId.isEmpty else { return }
        if observedConversationId != conversationId {
            observedConversationId = conversationId
            knownMessageIds = []
            observedInitialFrame = false
        }
        var fresh: [ArrivalCandidate] = []
        for c in list where knownMessageIds.insert(c.messageId).inserted {
            if observedInitialFrame, c.role == "assistant", c.state == "final" { fresh.append(c) }
        }
        observedInitialFrame = true
        for c in fresh { noteFreshArrival(c) }
    }

    private func noteFreshArrival(_ c: ArrivalCandidate) {
        guard !c.messageId.isEmpty, c.contentCount > 0, c.contentCount <= Self.freshFinalTypingLimit,
              let ts = c.ts,
              let date = Self.tsFormatter.date(from: ts) ?? Self.tsFormatterNoFraction.date(from: ts),
              abs(Date().timeIntervalSince(date)) <= Self.freshFinalWindow else { return }
        pendingFresh[c.messageId] = Date()
    }

    /// 实时新到的 final 行打字起点：0 = 从 0 逐字；nil = 贴齐整段。非消耗——视图值在真正
    /// 挂载前可被构造多次，登记项由打字机起跑时消耗（consumeFreshFinal）。
    func freshFinalStart(messageId: String) -> Int? {
        guard let at = pendingFresh[messageId] else { return nil }
        guard Date().timeIntervalSince(at) < Self.freshFinalPendingTTL else {
            pendingFresh[messageId] = nil // 登记后迟迟未物化：过期贴齐
            return nil
        }
        return 0
    }

    /// 打字机起跑即消耗登记：此后的重物化由进度账本接续（5s 内）或贴齐，不从头重打。
    func consumeFreshFinal(messageId: String) {
        pendingFresh[messageId] = nil
    }

    /// 登出清账（换账号不残留）。
    func resetForLogout() {
        entries.removeAll()
        pendingFresh.removeAll()
        observedConversationId = nil
        knownMessageIds = []
        observedInitialFrame = false
    }
}

// MARK: - 打字机视图

/// 打字机视图：已揭示前缀逐字放出的助手行（流式行与实时新到的 final 行共用）。数据侧 2s
/// 合并大块到达（bridge delta-coalesce 窗）或整条一次性到达，渲染侧一律从已揭示前缀向新
/// 内容**逐字**放出，等待新字符时尾部光标闪烁。
///
/// 节拍与步进见 TypewriterPacing。语义边界：
/// - 流式行：首次出现从 0 逐字（首窗不瞬显）；重物化/非前缀改写/内容收缩直接贴齐；
/// - 光标（MarkdownCursor）恒挂已揭示前缀末尾：逐字期间常亮，积压放空等待期 530ms 翻转；
/// - carryOverFrom 非 nil = final 行打字（续打流式行剩余积压，或实时新到整条从 0 打出——
///   TypewriterHandoff 注），打完光标闪一拍后经 onCaughtUp 交回常规 final 渲染；账本每
///   tick 记账，打字中行滚出再回来仍接续。
struct StreamingTypewriter: View {
    let content: String
    var messageId: String = ""
    var conversationId: String = ""
    /// final 续打起点（已揭示字素数）；nil = 常规流式行。
    var carryOverFrom: Int? = nil
    /// v1.4 查看器（入口 b）回调穿线：流式期已成块的 https 图块点按上抛（nil = 不接）。
    var onOpenImage: (([URL], Int) -> Void)? = nil
    /// 续打完成回调（仅 carryOverFrom 非 nil 时触发一次）。
    var onCaughtUp: (() -> Void)? = nil

    /// 已揭示字素数；nil = 未初始化（挂载即贴齐现内容，不重打）。
    @State private var revealedCount: Int?
    /// 上一帧内容（区分 append 前缀增长与改写跳变）。
    @State private var lastSeen: String
    @State private var cursorVisible = true

    init(
        content: String,
        messageId: String = "",
        conversationId: String = "",
        carryOverFrom: Int? = nil,
        onOpenImage: (([URL], Int) -> Void)? = nil,
        onCaughtUp: (() -> Void)? = nil
    ) {
        self.content = content
        self.messageId = messageId
        self.conversationId = conversationId
        self.carryOverFrom = carryOverFrom
        self.onOpenImage = onOpenImage
        self.onCaughtUp = onCaughtUp
        // 起点须在首帧定版（不能等 onAppear——final 行会先闪一帧全文再缩回前缀重打）：
        // 续打 → 记录进度；首次出现的流式行且首窗不大 → 0；其余 → nil 贴齐。
        let fresh = TypewriterHandoff.shared.isNewStream(conversationId: conversationId, messageId: messageId)
            && content.count <= TypewriterPacing.firstChunkTypingLimit
        let start = carryOverFrom ?? (fresh ? 0 : nil)
        _revealedCount = State(initialValue: start)
        _lastSeen = State(initialValue: start == nil ? "" : content)
    }

    var body: some View {
        let target = content.count
        let shown = min(revealedCount ?? target, target)
        MarkdownView(
            content: shown >= target ? content : String(content.prefix(shown)),
            streaming: true,
            onOpenImage: onOpenImage,
            cursor: MarkdownCursor(visible: cursorVisible)
        )
        .task(id: content) { await run() }
    }

    private func record(_ shown: Int) {
        TypewriterHandoff.shared.record(
            conversationId: conversationId, messageId: messageId, shown: shown, content: content
        )
    }

    private func snapAndFinish() {
        revealedCount = content.count
        lastSeen = content
        record(content.count)
        if carryOverFrom != nil { onCaughtUp?() }
    }

    /// 揭示循环：内容变化（新 delta）经 .task(id: content) 取消重启，进度跨重启保留。
    private func run() async {
        let target = content.count
        if revealedCount == nil {
            snapAndFinish() // 挂载首帧贴齐，不重打
            return
        }
        guard content.hasPrefix(lastSeen) else {
            snapAndFinish() // 改写跳变（replace 语义/内容收缩/非前缀续写）：直接贴齐
            return
        }
        lastSeen = content
        var shown = min(revealedCount ?? 0, target)
        revealedCount = shown
        cursorVisible = true
        if carryOverFrom != nil {
            // final 行打字起跑：消耗「实时新到」登记——此后重物化由进度账本接续，不从头重打。
            TypewriterHandoff.shared.consumeFreshFinal(messageId: messageId)
        }
        record(shown)
        if shown >= target {
            if carryOverFrom != nil { await settleThenFinish() }
            return
        }
        while !Task.isCancelled {
            let backlog = target - shown
            if backlog <= 0 {
                // 等待新字符：光标闪烁。
                try? await Task.sleep(nanoseconds: TypewriterPacing.blinkNs)
                if Task.isCancelled { break }
                cursorVisible.toggle()
                continue
            }
            let tick = TypewriterPacing.tickNs(backlog: backlog)
            try? await Task.sleep(nanoseconds: tick)
            if Task.isCancelled { break }
            // 每 tick 恒放步长个字（不做 dt 补齐）：系统把短定时器合并拉长时（锁屏/省电/后台
            // ~33ms 一拍），按流逝时间补齐会一拍放出 2 字——违背「一次一个字」；宁慢不跳。
            let step = carryOverFrom != nil
                ? TypewriterPacing.carryStep(backlog: backlog) // final 行打字：≤750 恒逐字，超出部分有界追平
                : TypewriterPacing.step(backlog: backlog)
            shown += min(step, backlog)
            revealedCount = shown
            cursorVisible = true
            record(shown)
            if shown >= target {
                if carryOverFrom != nil {
                    await settleThenFinish() // final 行打完：光标再闪一拍才交回常规渲染
                    return
                }
            }
        }
    }

    /// 收尾：末字落定后光标再闪一拍（灭 265ms → 亮 265ms）才交回常规渲染——一次性到达的
    /// 回复没有「等待新字符」的间隙，这一拍是它唯一可见的闪烁；期间行离场（滚出视口/会话
    /// 切换）任务即取消，不悬挂回调。
    private func settleThenFinish() async {
        cursorVisible = false
        try? await Task.sleep(nanoseconds: TypewriterPacing.blinkNs / 2)
        guard !Task.isCancelled else { return }
        cursorVisible = true
        try? await Task.sleep(nanoseconds: TypewriterPacing.blinkNs / 2)
        guard !Task.isCancelled else { return }
        onCaughtUp?()
    }
}
