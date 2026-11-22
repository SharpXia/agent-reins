import CryptoKit
import Foundation

// ── 需求4（2026-11-23 批次一）：媒体磁盘缓存层 ──
// HubMediaLoader / RemoteImageLoader 的二级存储：NSCache 驱逐或进程重启后，行内缩略图不再
// 重走网络下载原图（此前字节仅在解码窗口内存持有）。目录 = Caches/media-thumbs/（Caches
// 本就不入 iCloud 备份、系统磁盘紧张时可整目录回收——属预期行为，缺页只退化为重下）；
// 登出整目录清空（HubStore.logout() → clearAll：媒体字节是账号派生数据，撤销设备换账号
// 不残留呈现，与 HubStore 各账号派生缓存「随登出清空」纪律同位——确认评审收口）；
// 文件名 = SHA256(key) 十六进制（键可为 mediaId 或 stableCacheKey(URL) 串，防特殊字符与
// 路径穿越，文件系统只见定长哈希名）；trim 按 mtime LRU（磁盘命中即 touch
// modificationDate），256MB 惰性清理。线程安全：串行 utility 队列承全部磁盘 IO，不占主
// 线程（两个 loader 是 @MainActor，磁盘读写由其任务内 detached 下放，见 MediaSupport.swift）。

/// 外链 URL 的稳定缓存键：剥离已知时效/签名 query 参数并重排序归一——同一资源换签名窗
/// （expires/X-Amz-Expires 轮转）映射同一键，磁盘命中跨签名周期延续。白名单外参数全保留：
/// 未知签名方案退化为「不命中」而非「错命中」（错误方向安全）。仓内 shared/AgentReinService
/// 全量 grep 无 presign 生成（2026-11-23 批次一复核轮结论）——今天无签名 URL；但外链内容
/// 不受仓内控制，按白名单实施。
/// 双端对锁（自检轮 R1-2/R1-10/R1-20）：白名单闭集与桌面 MediaCache.kt STRIP_QUERY_PARAMS
/// 逐项一致——2026-11-23 起取并集对齐（本侧补 oss-expires_at；桌面差集 x-amz-security-token/
/// oss-access-key-id/oss-security-token 由桌面侧同轮补齐）。单端增删必须双端同步改，
/// 否则同 URL 两端归一键漂移。
private let volatileQueryKeys: Set<String> = [
    // 时效参数（各拼写变体）
    "expires", "expire", "expiry", "expires_at", "expiresat",
    // AWS SigV2/SigV3 presign 家族
    "x-amz-date", "x-amz-expires", "x-amz-signature", "x-amz-credential",
    "x-amz-algorithm", "x-amz-signedheaders", "x-amz-security-token",
    // 通用签名 / 令牌
    "signature", "sig", "token", "access_token",
    // 阿里云 OSS 签名家族（oss-expires_at = 对锁桌面侧独有拼写，2026-11-23 并集对齐）
    "oss-expires", "oss-expires_at", "oss-signature", "oss-access-key-id", "oss-security-token",
]

/// 剥离时效/签名 query 参数后的归一 URL 串。无 query 或无参数命中白名单时原样返回
///（不重排——无参数被剥离时键不变，既有缓存条目零失效；自检轮 R3-8 起内存键亦同此键，
/// 原「内存键=原串」口径已撤）。解析失败原样返回（退化不命中）。
func stableCacheKey(_ raw: String) -> String {
    guard var comps = URLComponents(string: raw),
          let queryItems = comps.queryItems, !queryItems.isEmpty else { return raw }
    let kept = queryItems.filter { !volatileQueryKeys.contains($0.name.lowercased()) }
    guard kept.count != queryItems.count else { return raw }
    // 同名重复参数须全项 tiebreak（自检轮 R4-7）：Swift 标准库 sort 不保证稳定序，仅按名
    // 排序时同名异值参数的相对次序随实现漂移——归一键可轮转换键（签名轮换若同时重排同名
    // 参数则缓存失效重下）。名同再比值（nil 值按空串先序），对齐桌面 MediaCache.kt
    // stableCacheKey 的 compareBy(参数名, 全项) 定序——iOS 比解码值、桌面比原始串，同名
    // 且解码值相等、仅百分号编码异形的极端对仍可能漂序，退化为不命中（错误方向安全）。
    comps.queryItems = kept.sorted { ($0.name.lowercased(), $0.value ?? "") < ($1.name.lowercased(), $1.value ?? "") }
    return comps.string ?? raw
}

final class MediaDiskCache {
    static let shared = MediaDiskCache()

    /// 惰性清理阈值：目录总字节超帽 → 按 modificationDate 升序（最久未用先删）删回帽内。
    private static let capacityBytes = 256 * 1024 * 1024

    /// trim 扫描节流双门（自检轮 R1-15：此前每次写盘都全目录枚举+求和+排序，大量小缩略图
    /// 时写路径 O(n) 扫描常开）——距上次全扫累计 ≥32 次写或净增 ≥16MB 才扫一次。超帽清理
    /// 至多延迟到下一门开（临时超帽 ≤16MB 量级，LRU 语义不变）。重写同键按净增上界计
    /// （文件替换不做差量账），触发略偏保守无害。桌面 MediaCache.kt trimDiskIfOverCap
    /// 同轮对齐，双端参数须一致。
    private static let trimScanWriteInterval = 32
    private static let trimScanBytesDelta = 16 * 1024 * 1024
    /// 双门计数器：只在串行 utility 队列闭包内读写（data 读路径不触），无需加锁。
    private var writesSinceTrimScan = 0
    private var bytesAddedSinceTrimScan = 0

    private let queue = DispatchQueue(label: "hub.media.diskcache", qos: .utility)
    private let directory: URL

    private init() {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        directory = caches.appendingPathComponent("media-thumbs", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    /// 磁盘读取原始字节（命中即 touch mtime 供 LRU）。串行队列同步执行——调用方应在
    /// detached/后台任务中调用（loader 侧约定见文件头注），勿直接占主线程。
    func data(forKey key: String) -> Data? {
        queue.sync {
            let url = Self.fileURL(forKey: key, in: directory)
            guard let data = try? Data(contentsOf: url) else { return nil }
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path)
            return data
        }
    }

    /// 磁盘写入原始字节（异步、原子写；失败静默——缓存层缺页只退化为重下，不向调用方报错）。
    /// trim 按双门节流触发（见 trimScanWriteInterval 注），非每次写都全目录扫描。
    func store(_ data: Data, forKey key: String) {
        queue.async { [directory] in
            let url = Self.fileURL(forKey: key, in: directory)
            do {
                try data.write(to: url, options: .atomic)
                self.writesSinceTrimScan += 1
                self.bytesAddedSinceTrimScan += data.count
                if self.writesSinceTrimScan >= Self.trimScanWriteInterval
                    || self.bytesAddedSinceTrimScan >= Self.trimScanBytesDelta {
                    self.writesSinceTrimScan = 0
                    self.bytesAddedSinceTrimScan = 0
                    Self.trimIfNeeded(in: directory)
                }
            } catch {
                // 写失败（磁盘满/目录被系统回收）静默：下次取数走网络重下。
            }
        }
    }

    /// 登出清空（账号语义的主动清空，与「可被系统回收」的被动回收不同；调用方
    /// HubStore.logout()）：整目录删除后重建，串行队列承 IO。在途写竞态窗至多残留登出前
    /// 已在途的单张图（设备已撤销，后续取数必失败不再增长——知情微窗）。
    func clearAll() {
        queue.async { [directory] in
            try? FileManager.default.removeItem(at: directory)
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        }
    }

    /// 键 → 文件名：SHA256 十六进制定长名。
    private static func fileURL(forKey key: String, in directory: URL) -> URL {
        let digest = SHA256.hash(data: Data(key.utf8))
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(hex)
    }

    /// mtime LRU 惰性清理（写路径双门节流触发，见 trimScanWriteInterval 注）：目录总字节
    /// 超帽 → modificationDate 升序删至帽内。
    private static func trimIfNeeded(in directory: URL) {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey],
        ) else { return }
        var items: [(url: URL, size: Int, mtime: Date)] = []
        var total = 0
        for entry in entries {
            let values = try? entry.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
            guard let size = values?.fileSize, size > 0 else { continue }
            total += size
            items.append((entry, size, values?.contentModificationDate ?? .distantPast))
        }
        guard total > capacityBytes else { return }
        let overflow = total - capacityBytes
        var removed = 0
        for item in items.sorted(by: { $0.mtime < $1.mtime }) {
            try? fm.removeItem(at: item.url)
            removed += item.size
            if removed >= overflow { break }
        }
    }
}
