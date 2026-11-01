import QuartzCore
import UIKit

/// Dev 诊断：对话页滑动性能基准（2026-11-01 滑动卡顿调查引入，保留供回归复测）。
///
/// 只在沙盒 `Documents/scrollbench.json` 存在时启用（模拟器上宿主机直写容器，与
/// dev.json 同一通道）；真机/TestFlight 不存在该文件，计数入口退化为一次布尔判断。
/// 启用后进入对话页 `delay` 秒开测：CADisplayLink 以 `velocity` pt/s 直接驱动列表
/// UIScrollView 上滑 `seconds` 秒→停 0.5s→下滑回底，统计帧间隔（卡顿次数/卡顿时间比）、
/// RunLoop 主线程忙时、各 body/快照计数与耗时，结果追加写 `Documents/scrollbench-result.txt`；
/// `repeats` 轮（第二轮起行已物化过、解析缓存已热）。务必用 Release 构建测——Debug 的
/// -Onone 与 K/N debug framework 会把数字放大到失真。
///
/// 配置示例：`{"delay": 16, "velocity": 1500, "seconds": 6, "repeats": 3}`
final class ScrollBench: NSObject {
    static let shared = ScrollBench()

    enum Counter: String, CaseIterable {
        case chatBody, rowBody, entryBody, messageBody, markdownBody
        case snapConn, snapLists, snapConv, snapTimeline, snapMisc
    }

    private let config: [String: Any]?
    private(set) var recording = false
    private var counts: [Counter: Int] = [:]
    private var timings: [String: (n: Int, total: Double, max: Double)] = [:]
    private var notes: [String: Int] = [:]

    private var link: CADisplayLink?
    private weak var scrollView: UIScrollView?
    private var started = false
    private var phase = 0
    private var phaseStart: CFTimeInterval = 0
    private var lastTs: CFTimeInterval = 0
    private var intervals: [Double] = []
    private var observer: CFRunLoopObserver?
    private var busyStart: CFTimeInterval = 0
    private var busy: [Double] = []
    private var label = ""
    private var round = 0

    override init() {
        let path = NSHomeDirectory() + "/Documents/scrollbench.json"
        if let data = FileManager.default.contents(atPath: path),
           let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            config = obj
        } else {
            config = nil
        }
        super.init()
    }

    private func num(_ key: String, _ fallback: Double) -> Double {
        (config?[key] as? NSNumber)?.doubleValue ?? fallback
    }

    func count(_ counter: Counter) {
        if recording { counts[counter, default: 0] += 1 }
    }

    func note(_ name: String) {
        if recording { notes[name, default: 0] += 1 }
    }

    func time<T>(_ name: String, _ body: () -> T) -> T {
        guard recording else { return body() }
        let t0 = CACurrentMediaTime()
        let value = body()
        let dt = CACurrentMediaTime() - t0
        var entry = timings[name] ?? (0, 0, 0)
        entry.n += 1
        entry.total += dt
        entry.max = max(entry.max, dt)
        timings[name] = entry
        return value
    }

    /// 对话列表出现时调用；未启用或已开测则忽略。
    func armIfNeeded() {
        guard config != nil, !started else { return }
        started = true
        DispatchQueue.main.asyncAfter(deadline: .now() + num("delay", 12)) { [weak self] in self?.begin() }
    }

    private func findScrollView() -> UIScrollView? {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
        var best: UIScrollView?
        func walk(_ view: UIView) {
            if let sv = view as? UIScrollView, sv.window != nil, !sv.isHidden,
               sv.bounds.height > 200, sv.contentSize.height > sv.bounds.height * 1.5,
               best == nil || sv.contentSize.height > best!.contentSize.height {
                best = sv
            }
            for sub in view.subviews { walk(sub) }
        }
        for window in windows where !window.isHidden { walk(window) }
        return best
    }

    private func begin() {
        guard let sv = findScrollView() else {
            write("NO SCROLLVIEW FOUND\n")
            return
        }
        scrollView = sv
        counts = [:]
        timings = [:]
        notes = [:]
        intervals = []
        busy = []
        label = "contentH=\(Int(sv.contentSize.height)) boundsH=\(Int(sv.bounds.height)) offset=\(Int(sv.contentOffset.y)) class=\(type(of: sv))"
        recording = true
        installObserver()
        let displayLink = CADisplayLink(target: self, selector: #selector(tick(_:)))
        displayLink.preferredFrameRateRange = CAFrameRateRange(minimum: 80, maximum: 120, preferred: 120)
        displayLink.add(to: .main, forMode: .common)
        link = displayLink
        phase = 1
        phaseStart = CACurrentMediaTime()
        lastTs = 0
    }

    private func installObserver() {
        let obs = CFRunLoopObserverCreateWithHandler(
            kCFAllocatorDefault,
            CFRunLoopActivity.afterWaiting.rawValue | CFRunLoopActivity.beforeWaiting.rawValue,
            true, 0
        ) { [weak self] _, activity in
            guard let self else { return }
            let now = CACurrentMediaTime()
            if activity == .afterWaiting {
                self.busyStart = now
            } else if activity == .beforeWaiting, self.busyStart > 0 {
                self.busy.append(now - self.busyStart)
                self.busyStart = 0
            }
        }
        CFRunLoopAddObserver(CFRunLoopGetMain(), obs, .commonModes)
        observer = obs
    }

    @objc private func tick(_ displayLink: CADisplayLink) {
        guard let sv = scrollView else {
            finish()
            return
        }
        let ts = displayLink.timestamp
        defer { lastTs = ts }
        guard lastTs > 0 else { return }
        let dt = ts - lastTs
        intervals.append(dt)
        let velocity = CGFloat(num("velocity", 1500))
        let seconds = num("seconds", 6)
        let now = CACurrentMediaTime()
        let minY = -sv.adjustedContentInset.top
        let maxY = max(minY, sv.contentSize.height - sv.bounds.height + sv.adjustedContentInset.bottom)
        switch phase {
        case 1:
            let y = max(minY, sv.contentOffset.y - velocity * CGFloat(dt))
            sv.contentOffset.y = y
            if now - phaseStart > seconds || y <= minY { phase = 2; phaseStart = now }
        case 2:
            if now - phaseStart > 0.5 { phase = 3; phaseStart = now }
        case 3:
            let y = min(maxY, sv.contentOffset.y + velocity * CGFloat(dt))
            sv.contentOffset.y = y
            if now - phaseStart > seconds * 3 || y >= maxY - 1 { phase = 4; phaseStart = now }
        default:
            if now - phaseStart > 0.5 { finish() }
        }
    }

    private func percentile(_ values: [Double], _ p: Double) -> Double {
        guard !values.isEmpty else { return 0 }
        let sorted = values.sorted()
        return sorted[min(sorted.count - 1, Int(Double(sorted.count - 1) * p))]
    }

    private func finish() {
        link?.invalidate()
        link = nil
        if let obs = observer {
            CFRunLoopRemoveObserver(CFRunLoopGetMain(), obs, .commonModes)
            observer = nil
        }
        recording = false
        let duration = intervals.reduce(0, +)
        let nominal = percentile(intervals, 0.5)
        let hitched = intervals.filter { $0 > nominal * 1.5 }
        let hitchTime = hitched.reduce(0) { $0 + ($1 - nominal) }
        let busyTotal = busy.reduce(0, +)
        let ms = { (x: Double) in String(format: "%.2f", x * 1000) }
        var out = "=== ScrollBench round \(round) \(Date()) ===\n"
        out += "config=\(config ?? [:])\n\(label)\n"
        out += "frames=\(intervals.count) duration=\(String(format: "%.2f", duration))s nominal=\(ms(nominal))ms\n"
        out += "hitches=\(hitched.count) hitchTime=\(ms(hitchTime))ms hitchRatio=\(String(format: "%.1f", duration > 0 ? hitchTime / duration * 1000 : 0))ms/s\n"
        out += "frame p50=\(ms(percentile(intervals, 0.5))) p90=\(ms(percentile(intervals, 0.9))) p99=\(ms(percentile(intervals, 0.99))) max=\(ms(intervals.max() ?? 0))\n"
        out += "busy total=\(ms(busyTotal))ms (\(String(format: "%.1f", duration > 0 ? busyTotal / duration * 100 : 0))%) chunks=\(busy.count) p50=\(ms(percentile(busy, 0.5))) p95=\(ms(percentile(busy, 0.95))) max=\(ms(busy.max() ?? 0)) over8ms=\(busy.filter { $0 > 0.0083 }.count) over16ms=\(busy.filter { $0 > 0.0167 }.count)\n"
        for counter in Counter.allCases { out += "\(counter.rawValue)=\(counts[counter] ?? 0) " }
        out += "\n"
        for (name, entry) in timings.sorted(by: { $0.value.total > $1.value.total }) {
            let avg = entry.n > 0 ? entry.total / Double(entry.n) * 1000 : 0
            out += "t[\(name)] n=\(entry.n) total=\(ms(entry.total))ms avg=\(String(format: "%.3f", avg))ms max=\(ms(entry.max))ms\n"
        }
        for (name, n) in notes.sorted(by: { $0.value > $1.value }) { out += "note[\(name)]=\(n)\n" }
        write(out)
        round += 1
        if round < Int(num("repeats", 2)), let sv = scrollView {
            // 回到底部稍歇再跑下一轮（第二轮起行已物化过、解析缓存已热）
            sv.contentOffset.y = max(
                -sv.adjustedContentInset.top,
                sv.contentSize.height - sv.bounds.height + sv.adjustedContentInset.bottom
            )
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.begin() }
        }
    }

    private func write(_ text: String) {
        let path = NSHomeDirectory() + "/Documents/scrollbench-result.txt"
        guard let data = text.data(using: .utf8) else { return }
        if let handle = FileHandle(forWritingAtPath: path) {
            handle.seekToEndOfFile()
            handle.write(data)
            try? handle.close()
        } else {
            FileManager.default.createFile(atPath: path, contents: data)
        }
    }
}
