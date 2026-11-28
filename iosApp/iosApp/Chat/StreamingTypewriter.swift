import SwiftUI

// MARK: - 节拍纯表（与桌面 desktopui/ConversationScreen.kt typewriterTickMs/typewriterStep 对锁）

/// 打字机节拍（2026-11-28 流式逐字轮）。与桌面常量同值同义——回归值表锁在桌面
/// StreamingTypewriterPacingTest，单端改值必须双端同步。
///
/// 旧档「max(1, 积压/90) 字 / 16ms」废止：积压大时一 tick 跳多字（2s 窗 300 字积压 = 每
/// 16ms 跳 3~4 字）、抽干后空等到下一窗，且无等待光标——「流式输出粗糙」的来源。新口径
/// = **恒逐字**：每 tick 至多放 1 个字素（String.count），靠缩短 tick 提速而非加步长。
/// - tick = 追平视界 / 积压（毫秒整数除法），钳 [帧率档 16ms, 慢速档 32ms]：积压 ≤62 字走
///   慢速档（≈31 字/秒，可辨识的逐字节拍），≥125 字压到帧率档（≈60 字/秒，60Hz 每帧一字），
///   之间线性过渡——持续快流下显示速率贴帧率档上限不整块倾泻，积压消退时 tick 渐次放慢
///   到慢速档（尾段减速收尾）；
/// - 滞后上限：积压超 300 字步进升档 ceil(积压/300)——持续快流（>60 字/秒）下显示滞后
///   有界，此时每帧多字肉眼已不可分辨；
/// - final 续打收敛：行收敛 final 时剩余积压按 ceil(积压/75) 步进——积压按指数消退，尾 75 字
///   恒逐字收口；总 tick ≈ 75×(1+ln(积压/75))（150 字尾包 ≈112 tick≈1.8s、300 字 ≈156
///   tick≈2.5s），尾包不再一次跳出。
enum TypewriterPacing {
    /// 慢速档 tick（毫秒）：小积压 ≈31 字/秒。
    static let baseTickMs: Int = 32
    /// 帧率档 tick（毫秒）：≈60 字/秒，逐字揭示的速度上限。
    static let minTickMs: Int = 16
    /// 追平视界（毫秒）：贴桥 2s 合并窗（delta-coalesce DEFAULT_COALESCE_WINDOW_MS）。
    static let horizonMs: Int = 2_000
    /// 滞后上限（字素）：积压超限步进升档。
    static let lagLimitChars = 300
    /// final 续打收敛 tick 数。
    static let catchUpTicks = 75
    /// 等待期光标闪烁半周期（纳秒）。
    static let blinkNs: UInt64 = 530_000_000
    /// 首个流式行从 0 逐字的积压上限：首窗文本超此量（快流大窗）仍贴齐，防迟到物化整段重打。
    static let firstChunkTypingLimit = 300

    /// 本 tick 时长（纳秒）：视界/积压（毫秒整数除法，与桌面 typewriterTickMs 逐值相等），
    /// 钳 [帧率档, 慢速档]；非正积压按 1。
    static func tickNs(backlog: Int) -> UInt64 {
        let ms = horizonMs / max(backlog, 1)
        return UInt64(Swift.min(Swift.max(ms, minTickMs), baseTickMs)) * 1_000_000
    }

    /// 本 tick 步进（字素）：恒 1，仅积压超上限时 ceil(积压/上限) 升档；catchUp = final
    /// 续打阶段（上限换 catchUpTicks，尾包有界收敛）。
    static func step(backlog: Int, catchUp: Bool) -> Int {
        let limit = catchUp ? catchUpTicks : lagLimitChars
        return max(1, (backlog + limit - 1) / limit)
    }
}

// MARK: - 流式 → final 续打交接账本

/// 打字机交接账本（主线程访问：SwiftUI 渲染与 .task 同在主 actor，未加隔离标注是因
/// HubMessageRow.init 需在首帧定续打起点——@MainActor 标注会让非隔离的 init 不可调）。
///
/// 两条职责，按会话一键（同会话同时至多一条在途流式行）：
/// 1. **final 续打**：流式行被 final 权威行替换时行身份（dstream:… → messageId）随之更换，
///    SwiftUI 视为全新行，旧打字机连同剩余积压一并离场——旧口径尾包一次性跳出。流式行每
///    tick 把进度记入本账本，final 行首帧查得「同会话 + 未过期 + 前缀吻合 + 确有剩余」即从
///    记录进度续打（TTL 5s：行滚出视口后再物化、快照/重进等迟到物化一律贴齐不续打）；
/// 2. **首窗逐字 / 重物化不重打**：流式行由首个 delta 创建，旧口径「挂载即贴齐」使每条
///    回复的首个 2s 窗恒整块瞬显。现按 messageId 辨别——本会话账本未见该行 = 首次出现，
///    从 0 逐字；已见（滚动往返重新物化、runActive 瞬态翻转重建分支）= 贴齐不重打。
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

    private var entries: [String: Entry] = [:]

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

    /// 登出清账（换账号不残留）。
    func resetForLogout() {
        entries.removeAll()
    }
}

// MARK: - 打字机视图

/// 流式行 typewriter（grokbot 逐字体感）。数据侧 2s 合并大块到达（bridge delta-coalesce
/// 窗），渲染侧从「已揭示前缀」向新内容**逐字**放出，等待新字符时尾部光标闪烁。
///
/// 节拍与步进见 TypewriterPacing。语义边界：
/// - 首次出现的流式行从 0 逐字（首窗不瞬显）；重物化/非前缀改写/内容收缩直接贴齐；
/// - 光标（MarkdownCursor）恒挂已揭示前缀末尾：逐字期间常亮，积压放空等待期 530ms 翻转；
/// - carryOverFrom 非 nil = final 行续打流式行剩余积压（TypewriterHandoff 注），打完经
///   onCaughtUp 交回常规 final 渲染；账本每 tick 记账，续打中行滚出再回来仍接续。
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
        record(shown)
        if shown >= target {
            if carryOverFrom != nil { onCaughtUp?() }
            return
        }
        // dt 记账：按真实流逝时间折算本 tick 应放字数（tick 抖动不改变平均速率）；
        // 封顶 2——卡顿/后台回来不倾泻。
        var credit: Double = 0
        var lastTick = DispatchTime.now().uptimeNanoseconds
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
            let now = DispatchTime.now().uptimeNanoseconds
            credit = min(credit + Double(now - lastTick) / Double(tick), 2)
            lastTick = now
            var n = Int(credit)
            credit -= Double(n)
            n = min(max(n, TypewriterPacing.step(backlog: backlog, catchUp: carryOverFrom != nil)), backlog)
            shown += n
            revealedCount = shown
            cursorVisible = true
            record(shown)
            if shown >= target {
                if carryOverFrom != nil {
                    onCaughtUp?() // final 行续打完成，交回常规渲染
                    return
                }
                credit = 0
                lastTick = DispatchTime.now().uptimeNanoseconds
            }
        }
    }
}
