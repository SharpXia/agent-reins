import SwiftUI
import UIKit
import composeApp

// ── v1.2.0 多模态 v1（设计稿 docs/multimodal-design-20261108.md §6 iOS 行） ──
// 本文件承载 iOS 消息面媒体三件套：① 图片压缩/准备（上传前，R9 客户端压缩纪律）；
// ② hub 媒体加载器（端点 #24 Bearer 分支——凭据只在 KMP TokenManager，Swift 层不持有，
// 经 HubStore → AgentBridge.hubFetchMedia 取字节，自定义 loader 替代 AsyncImage，后者无法
// 携带 Authorization 头）；③ 附件/远程图渲染视图（占位 + 失败 + 重试 + http 链接 chip 降级）。

// ── 字节互转（Kotlin ByteArray ↔ Swift Data） ──

func dataFromKotlinByteArray(_ bytes: KotlinByteArray) -> Data {
    let n = Int(bytes.size)
    var data = Data(capacity: n)
    for i in 0..<n {
        data.append(UInt8(bitPattern: bytes.get(index: Int32(i))))
    }
    return data
}

func kotlinByteArray(from data: Data) -> KotlinByteArray {
    let arr = KotlinByteArray(size: Int32(data.count))
    // 逐字节过桥是既有的 KMP ByteArray 约束（无批量构造面）；典型上传量 = 压缩后
    // 4096px jpeg ≈ 1–4MB，一次性成本可接受。压缩帽见 MediaImageProcessing。
    data.enumerated().forEach { offset, byte in
        arr.set(index: Int32(offset), value: Int8(bitPattern: byte))
    }
    return arr
}

// ── 上传前图片准备（R9：客户端压缩——降采样 ≤4096px、jpeg 质量递减、≤10MB 帽） ──

/// 压缩后产物：data 必为 jpeg（mime 随之固定）；width/height = 最终像素尺寸；
/// fileName 保留原始名（仅元数据，服务端对象键由 ULID+嗅探扩展名构成，永不进路径）。
struct PreparedUploadImage {
    let data: Data
    let mime: String
    let width: Int
    let height: Int
    let fileName: String
}

enum MediaImageProcessing {
    /// 长边上限（R9 口径）。
    static let maxPixelSize: Int = 4096
    /// 上传体积帽（R9 口径；服务端 20MB 硬帽不变，客户端先压）。
    static let maxUploadBytes = 10 * 1024 * 1024
    /// 质量阶梯（逐档降档直至 ≤帽）。
    private static let qualities: [CGFloat] = [0.85, 0.6, 0.4]
    /// 质量阶梯到头仍超帽时的像素阶梯。
    private static let pixelLadder: [Int] = [4096, 2048, 1024]

    /// 入口：源图 Data → 压缩产物。源图无法解码（坏数据/非图）返回 nil，调用方呈失败态。
    static func prepare(_ source: Data, fileName: String) -> PreparedUploadImage? {
        guard let src = CGImageSourceCreateWithData(source as CFData, nil),
              CGImageSourceGetCount(src) > 0 else { return nil }
        for pixel in pixelLadder {
            if let encoded = encode(source: src, maxPixel: pixel) {
                for quality in qualities {
                    if let data = encoded.image.jpegData(compressionQuality: quality), data.count <= maxUploadBytes {
                        return PreparedUploadImage(
                            data: data, mime: "image/jpeg",
                            width: encoded.width, height: encoded.height,
                            fileName: fileName,
                        )
                    }
                }
                // 该像素档全质量超帽（罕见：4096 大照片 0.4 档仍 >10MB）→ 降像素重试
            }
        }
        // pixelLadder 全档失败（理论不可达）：保底返回最小档首档质量产物（超帽交服务端 400 裁决）
        guard let tiny = encode(source: src, maxPixel: 1024),
              let data = tiny.image.jpegData(compressionQuality: qualities[0]) else { return nil }
        return PreparedUploadImage(
            data: data, mime: "image/jpeg", width: tiny.width, height: tiny.height, fileName: fileName,
        )
    }

    /// 解码降采样（CGImageSource thumbnail，避免全尺寸位图驻留内存）；返回 (图, 像素尺寸)。
    private static func encode(source: CGImageSource, maxPixel: Int) -> (image: UIImage, width: Int, height: Int)? {
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
            kCGImageSourceCreateThumbnailWithTransform: true,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, opts as CFDictionary) else { return nil }
        return (UIImage(cgImage: cg), cg.width, cg.height)
    }

    /// 展示用解码降采样（消息行/缩略图统一 2048px 上限，控内存）。
    static func decodeForDisplay(_ data: Data, maxPixel: Int = 2048) -> UIImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
            kCGImageSourceCreateThumbnailWithTransform: true,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }
}

// ── mediaId 预检（ULID 字形；服务端 404 HUB-BIZ-002 为权威，此处只防显然病态的路径串） ──

enum MediaIdCheck {
    /// Crockford 26 位 ULID 字形（服务端签发句柄）。
    static func isUlid(_ s: String) -> Bool {
        let allowed = Set("0123456789ABCDEFGHJKMNPQRSTVWXYZabcdefghjkmnpqrstvwxyz")
        return s.count == 26 && s.allSatisfy { allowed.contains($0) }
    }
}

// ── hub 媒体加载器（端点 #24 Bearer 分支；NSCache + 在途去重） ──

/// 消息行图片内存加载器：唯一取数通道 = HubStore.shared.fetchMedia（KMP withBearer 内圈，
/// Swift 层零凭据）。字节仅在解码窗口内存持有（设计稿 §6/§2.4：不落盘持久化，NSCache 只存
/// 解码后 UIImage，进程级自然回收）；同 mediaId 并发去重（LazyVStack 多行物化同时触发）。
@MainActor
final class HubMediaLoader {
    static let shared = HubMediaLoader()

    private let cache = NSCache<NSString, UIImage>()
    private var inflight: [String: Task<UIImage?, Never>] = [:]

    private init() {
        cache.countLimit = 96
    }

    func image(for mediaId: String) async -> UIImage? {
        if let hit = cache.object(forKey: mediaId as NSString) { return hit }
        if let running = inflight[mediaId] { return await running.value }
        let task = Task<UIImage?, Never> { [weak self] () -> UIImage? in
            guard let self, MediaIdCheck.isUlid(mediaId) else { return nil }
            let image: UIImage? = await withCheckedContinuation { continuation in
                HubStore.shared.fetchMedia(mediaId: mediaId) { data, _ in
                    // 失败（error 非 nil）与 nil 数据同归 nil——调用方只分「有图/失败」两态
                    continuation.resume(returning: data.flatMap { MediaImageProcessing.decodeForDisplay($0) })
                }
            }
            if let image { self.cache.setObject(image, forKey: mediaId as NSString) }
            self.inflight[mediaId] = nil
            return image
        }
        inflight[mediaId] = task
        let image = await task.value
        return image
    }
}

// ── 外部 URL 图片加载器（source=external https；无鉴权头，ATS 默认拦 http——http 由视图降级 chip） ──

@MainActor
final class RemoteImageLoader {
    static let shared = RemoteImageLoader()

    private let cache = NSCache<NSString, UIImage>()
    private var inflight: [String: Task<UIImage?, Never>] = [:]

    private init() {
        cache.countLimit = 96
    }

    func image(for url: URL) async -> UIImage? {
        let key = url.absoluteString
        if let hit = cache.object(forKey: key as NSString) { return hit }
        if let running = inflight[key] { return await running.value }
        let task = Task<UIImage?, Never> { [weak self] () -> UIImage? in
            guard let self else { return nil }
            let fetched = try? await URLSession.shared.data(from: url)
            let image = (fetched?.0).flatMap { MediaImageProcessing.decodeForDisplay($0) }
            if let image { self.cache.setObject(image, forKey: key as NSString) }
            self.inflight[key] = nil
            return image
        }
        inflight[key] = task
        return await task.value
    }
}

// ── 单附件渲染视图（占位 / 失败重试 / 图 / http 链接 chip） ──

/// 消息行单条附件（UiAttachment 投影行 → 视图）：
/// - source=hub：经 HubMediaLoader（#24 Bearer 内圈）拉取内联渲染；失败态可点重试；
/// - source=external：https 内联渲染（无鉴权）；http（含 agent 局域网地址）降级链接 chip
///   （设计稿 §2.2：ATS 默认拦 http + 蜂窝网不可达，不做特例）；非 http(s) 协议同样降级 chip
///   的静态形态（不可点）。
struct MessageAttachmentView: View {
    let attachment: UiAttachment
    /// 行内多附件的统一高度（缩略图视觉基准，方形容器 scaledToFit）。
    static let thumbSize: CGFloat = 160

    var body: some View {
        if attachment.source == "hub", let mediaId = attachment.mediaId {
            HubMediaThumbnail(mediaId: mediaId, fileName: attachment.fileName)
                .contextMenu {
                    // UxPlan item4（评审修正⑦(b)）：拷贝图片——走 HubMediaLoader（NSCache 命中
                    // 或 #24 重取后解码，MediaSupport.swift:119-149），不是压缩管线
                    //（:32-74 为上传前 MediaImageProcessing，不持展示位图）。
                    // 双平台生效：iOS 长按同款，作为有意接受的 iOS 增益（与会话行右键同决策）。
                    Button {
                        Task { @MainActor in
                            if let image = await HubMediaLoader.shared.image(for: mediaId) {
                                UIPasteboard.general.image = image
                            }
                        }
                    } label: {
                        Label("拷贝图片", systemImage: "doc.on.doc")
                    }
                }
        } else if let url = externalURL {
            if url.scheme?.lowercased() == "https" {
                RemoteImageView(url: url)
                    .contextMenu {
                        // UxPlan item4：外链图右键（拷贝图片 / 拷贝图片链接）。
                        Button {
                            Task { @MainActor in
                                if let image = await RemoteImageLoader.shared.image(for: url) {
                                    UIPasteboard.general.image = image
                                }
                            }
                        } label: {
                            Label("拷贝图片", systemImage: "doc.on.doc")
                        }
                        Button {
                            UIPasteboard.general.string = url.absoluteString
                        } label: {
                            Label("拷贝图片链接", systemImage: "link")
                        }
                    }
            } else {
                ExternalLinkChip(url: url)
            }
        } else {
            // 形状漂移行（hub 无 mediaId / external 无 url / 未知 source）：静态降级条目，
            // 不编造媒体内容（与投影「坏 JSON 降级空清单」同纪律的视图侧延伸）。
            fallbackChip
        }
    }

    private var externalURL: URL? {
        guard let raw = attachment.url, let url = URL(string: raw) else { return nil }
        return url
    }

    private var fallbackChip: some View {
        Label(attachment.fileName?.isEmpty == false ? attachment.fileName! : "附件",
              systemImage: "paperclip")
            .font(.footnote)
            .foregroundStyle(.secondary)
    }
}

/// hub 媒体缩略图（mediaId → #24 拉取 → 内存缓存渲染）。attempt @State 驱动重试（点失败态）。
struct HubMediaThumbnail: View {
    let mediaId: String
    let fileName: String?
    @State private var image: UIImage?
    @State private var failed = false
    @State private var attempt = 0
    @Environment(\.inkColors) private var ink

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12).fill(ink.surfaceTertiary)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .accessibilityLabel(fileName?.isEmpty == false ? "图片附件：\(fileName!)" : "图片附件")
            } else if failed {
                // 失败态：图标 + 重试（点按重拉；网络恢复后用户可自行恢复内容，R12 同口径）
                VStack(spacing: 4) {
                    Image(systemName: "photo.badge.exclamationmark").font(.title3)
                    Text("加载失败 · 点击重试").font(.caption2)
                }
                .foregroundStyle(.secondary)
                .contentShape(Rectangle())
                .onTapGesture { attempt += 1 }
                .accessibilityLabel("图片附件加载失败，点按重试")
            } else {
                // 占位态：拉取在途（#24 往返；LazyVStack 物化即触发）
                ProgressView()
            }
        }
        .frame(maxWidth: MessageAttachmentView.thumbSize)
        .frame(height: MessageAttachmentView.thumbSize)
        .task(id: attempt) {
            guard image == nil else { return }
            failed = false
            if let loaded = await HubMediaLoader.shared.image(for: mediaId) {
                image = loaded
            } else if image == nil {
                failed = true
            }
        }
    }
}

/// external https 图片（生图输出/markdown 图片节点共用）：占位 + 失败重试同 hub 缩略图口径。
struct RemoteImageView: View {
    let url: URL
    @State private var image: UIImage?
    @State private var failed = false
    @State private var attempt = 0
    @Environment(\.inkColors) private var ink

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12).fill(ink.surfaceTertiary)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .accessibilityLabel("图片（\(url.host ?? url.absoluteString)）")
            } else if failed {
                VStack(spacing: 4) {
                    Image(systemName: "photo.badge.exclamationmark").font(.title3)
                    Text("加载失败 · 点击重试").font(.caption2)
                }
                .foregroundStyle(.secondary)
                .contentShape(Rectangle())
                .onTapGesture { attempt += 1 }
                .accessibilityLabel("图片加载失败，点按重试")
            } else {
                ProgressView()
            }
        }
        .frame(maxWidth: MessageAttachmentView.thumbSize)
        .frame(height: MessageAttachmentView.thumbSize)
        .task(id: attempt) {
            guard image == nil else { return }
            failed = false
            if let loaded = await RemoteImageLoader.shared.image(for: url) {
                image = loaded
            } else if image == nil {
                failed = true
            }
        }
    }
}

/// http（及非 https 协议）外链降级 chip：只给链接不给图（设计稿 §2.2 双口径之一）。
struct ExternalLinkChip: View {
    let url: URL
    @Environment(\.inkColors) private var ink

    var body: some View {
        Link(destination: url) {
            HStack(spacing: 5) {
                Image(systemName: "link")
                Text(url.absoluteString)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            .font(.footnote)
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 8).fill(ink.surfaceTertiary))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(ink.borderTertiary, lineWidth: 0.5))
        }
        .accessibilityLabel("外链图片：\(url.absoluteString)")
    }
}

/// 消息行附件横排（≤9 项，v1.2.1 契约上限；气泡内 VStack 之下一行）。
/// v1.4 查看器：可内联渲染的图片块点按上抛 onOpen（参数 = 附件序；HubMessageRow 侧经
/// pagesAndIndexMap 映射进查看器页序）。点按挂在整块容器上——失败态的重试手势更深，
/// 仍优先命中重试，不被查看器入口劫持。
struct MessageAttachmentsRow: View {
    let attachments: [UiAttachment]
    var onOpen: ((Int) -> Void)? = nil

    var body: some View {
        ForEach(Array(attachments.enumerated()), id: \.offset) { idx, a in
            MessageAttachmentView(attachment: a)
                .onTapGesture { onOpen?(idx) }
        }
    }
}
