import SwiftUI
import UIKit
import composeApp

// ── v1.4 全屏图片查看器（TG 级，接 v1.2.0 多模态渲染链） ──
// 三入口统一承载：
//   a. 消息行附件图片块（接收方经 #24 拉取的那条渲染链路，HubMediaLoader 内圈）；
//   b. Markdown ![...](url) 图块（生图回复，RemoteImageLoader）；
//   c. composer pending 缩略图（v1.3 发送链 openPendingPreview 接入点）。
// 手势面（验收）：双指捏合缩放（锚定手势中心，0.5x–6x，松手 <1x 回弹 1x）+ 单指平移
//（>1x 看细节，橡皮筋边缘回弹）+ 双击 1x↔2.5x 切换（带动画，锚点=被点内容处）+
// 1x 下拉关闭（跟手位移 + 淡出）。同消息多图左右翻页（TabView page 风格），翻页复位缩放。
//
// 手势捕获边界（纪律，改这里先读）：
// - 缩放/平移在 scale>1.001 才启用（.gesture(_:isEnabled:)）——1x 时让位 TabView 横滑翻页；
// - 下拉关闭用 .simultaneousGesture 挂页内容、只认纵向主导位移（横向零状态变更）→
//   TabView 翻页不被劫持；放大态下拉禁用（平移接管）；
// - 放大态横滑 = 平移（边缘回弹），翻页需先回 1x——TG 同款折衷。
//
// API 说明：捏合用 MagnifyGesture（iOS 17+）而非 MagnificationGesture——SDK swiftinterface
// 实证 MagnificationGesture.Value 是裸 CGFloat（无 location/startLocation），锚定手势中心
// 无从谈起；MagnifyGesture 是同一手势的更名形态，Value 带 startLocation。行为契约不变。

/// 查看器页数据源（三入口统一模型；id 供 TabView ForEach 身份）。
struct ImageViewerPage: Identifiable {
    enum Source {
        /// hub 媒体（#24 Bearer 内圈，经 HubMediaLoader——消息行附件 / 已就绪草稿槽）。
        case hub(mediaId: String)
        /// 外链 https（Markdown 图块 / source=external 附件，经 RemoteImageLoader）。
        case remote(URL)
        /// 本地已有位图（pending 草稿槽未得 mediaId 时的 chip 预览）。
        case inline(UIImage)
    }

    let id: String
    let source: Source
    let fileName: String?

    /// hub 媒体页（mediaId 即页身份——同一 mediaId 跨入口命中同一内存缓存）。
    static func hub(_ mediaId: String, fileName: String?) -> ImageViewerPage {
        ImageViewerPage(id: "hub:\(mediaId)", source: .hub(mediaId: mediaId), fileName: fileName)
    }

    /// 外链页（绝对 URL 串即页身份）。
    static func remote(_ url: URL) -> ImageViewerPage {
        ImageViewerPage(id: "url:\(url.absoluteString)", source: .remote(url), fileName: nil)
    }

    /// 本地位图页（调用方给稳定 id，防 TabView 身份漂移）。
    static func inline(_ image: UIImage, id: String) -> ImageViewerPage {
        ImageViewerPage(id: id, source: .inline(image), fileName: nil)
    }

    /// 消息行附件 → 查看器页集 + 附件序→页序映射（nil = 该附件不内联渲染——hub 缺
    /// mediaId / http 链接 chip / 形状漂移降级条，均无查看入口；与 MessageAttachmentView
    /// 的路由口径一一对应，不得比渲染面宽）。
    static func pagesAndIndexMap(forAttachments attachments: [UiAttachment]) -> ([ImageViewerPage], [Int?]) {
        var pages: [ImageViewerPage] = []
        var map: [Int?] = []
        map.reserveCapacity(attachments.count)
        for a in attachments {
            if a.source == "hub", let mediaId = a.mediaId, MediaIdCheck.isUlid(mediaId) {
                map.append(pages.count)
                pages.append(.hub(mediaId, fileName: a.fileName))
            } else if let raw = a.url, let url = URL(string: raw), url.scheme?.lowercased() == "https" {
                map.append(pages.count)
                pages.append(.remote(url))
            } else {
                map.append(nil)
            }
        }
        return (pages, map)
    }
}

/// fullScreenCover(item:) 载荷（Identifiable：每次打开新身份，页视图状态全新）。
struct ImageViewerPayload: Identifiable {
    let id = UUID()
    let pages: [ImageViewerPage]
    let index: Int
}

/// 全屏查看器壳：黑底 + TabView 翻页 + 右上关闭 + 页码指示。
struct ImageViewerView: View {
    let pages: [ImageViewerPage]
    let initialIndex: Int
    /// 关闭回调（下拉关闭与右上按钮共用——由宿主置 nil 的 binding 承担）。
    let onDismiss: () -> Void

    @State private var index: Int
    /// 翻页复位信号：selection 变化即递增，各页 onChange 复位缩放态（验收：翻页缩放重置）。
    @State private var resetToken = 0

    init(pages: [ImageViewerPage], initialIndex: Int, onDismiss: @escaping () -> Void) {
        self.pages = pages
        self.onDismiss = onDismiss
        self.initialIndex = min(max(initialIndex, 0), max(pages.count - 1, 0))
        _index = State(initialValue: self.initialIndex)
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            TabView(selection: $index) {
                ForEach(Array(pages.enumerated()), id: \.element.id) { i, page in
                    ImageViewerPageView(page: page, resetToken: resetToken,
                                        onPullDismiss: onDismiss)
                        .tag(i)
                        .background(Color.black)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .ignoresSafeArea()
            .onChange(of: index) { _, _ in resetToken += 1 }

            // 右上关闭按钮（验收）。黑底白字，半透明圆底。
            Button {
                onDismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 32, height: 32)
                    .background(Circle().fill(.white.opacity(0.18)))
            }
            .padding(.trailing, 16)
            .padding(.top, 8)
            .accessibilityLabel("关闭图片查看器")
        }
        .overlay(alignment: .bottom) {
            if pages.count > 1 {
                Text("\(index + 1) / \(pages.count)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.65))
                    .padding(.horizontal, 10).padding(.vertical, 4)
                    .background(Capsule().fill(.black.opacity(0.45)))
                    .padding(.bottom, 14)
                    .allowsHitTesting(false)
            }
        }
    }
}

/// 单页：按数据源加载位图（占位/失败重试），就位后交给 ZoomablePanView。
private struct ImageViewerPageView: View {
    let page: ImageViewerPage
    let resetToken: Int
    let onPullDismiss: () -> Void

    @State private var image: UIImage?
    @State private var failed = false
    @State private var attempt = 0

    var body: some View {
        ZStack {
            if let image {
                ZoomablePanView(image: image, onPullDismiss: onPullDismiss, resetToken: resetToken)
            } else if failed {
                VStack(spacing: 8) {
                    Image(systemName: "photo.badge.exclamationmark").font(.largeTitle)
                    Text("加载失败 · 点击重试").font(.caption)
                }
                .foregroundStyle(.white.opacity(0.75))
                .contentShape(Rectangle())
                .onTapGesture { attempt += 1 }
                .accessibilityLabel("图片加载失败，点按重试")
            } else {
                // 占位态：hub 页多为缓存命中（消息行缩略图已拉过），remote 页走真实往返。
                ProgressView()
                    .controlSize(.large)
                    .tint(.white.opacity(0.8))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: "\(page.id)#\(attempt)") {
            guard image == nil else { return }
            failed = false
            switch page.source {
            case let .hub(mediaId):
                image = await HubMediaLoader.shared.image(for: mediaId)
            case let .remote(url):
                image = await RemoteImageLoader.shared.image(for: url)
            case let .inline(img):
                image = img
            }
            if image == nil { failed = true }
        }
    }
}

/// 手势核心：捏合缩放（锚定手势中心）+ 单指平移（橡皮筋）+ 双击切换 + 1x 下拉关闭。
private struct ZoomablePanView: View {
    let image: UIImage
    let onPullDismiss: () -> Void
    /// 宿主翻页复位信号（selection 变化递增；本页 onChange 复位缩放态）。
    let resetToken: Int

    // 变换态（displayed(p) = anchor + (p − anchor)·scale + offset，pull 为外层附加位移）
    @State private var scale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var anchor: UnitPoint = .center
    @State private var anchorPoint: CGPoint?
    // 手势基值（onChanged 首帧捕获一次）
    @State private var pinchBase: CGFloat?
    @State private var panBase: CGSize?
    @State private var isPinching = false
    // 1x 下拉关闭态（跟手位移 + 淡出）
    @State private var pullOffset: CGSize = .zero
    @State private var pullFade: CGFloat = 1

    /// 捏合区间：手势中可压到 0.5x；松手 <1x 回弹 1x。上限 6x。
    private static let minPinchScale: CGFloat = 0.5
    private static let maxScale: CGFloat = 6
    /// 双击进入倍率（验收）。
    private static let doubleTapScale: CGFloat = 2.5

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .scaleEffect(scale, anchor: anchor)
                .offset(offset)
                .offset(pullOffset)
                .opacity(pullFade)
                .frame(width: size.width, height: size.height)
                .contentShape(Rectangle())
                .gesture(panGesture(size: size), isEnabled: scale > 1.001)
                .simultaneousGesture(pinchGesture(size: size))
                .simultaneousGesture(pullGesture(size: size), isEnabled: scale <= 1.001)
                .simultaneousGesture(doubleTapGesture(size: size))
        }
        .onChange(of: resetToken) { _, _ in
            // 翻页复位（验收）。已在 1x 且无下拉位移时空转。
            guard scale != 1 || offset != .zero || pullOffset != .zero || pullFade != 1 else { return }
            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                scale = 1
                offset = .zero
                anchor = .center
                pullOffset = .zero
                pullFade = 1
            }
        }
    }

    // ── 捏合缩放 ──

    private func pinchGesture(size: CGSize) -> some Gesture {
        MagnifyGesture()
            .onChanged { v in
                if !isPinching {
                    isPinching = true
                    pinchBase = scale
                    // 锚定手势中心（startLocation）。已处于放大态时换锚需补偿位移，
                    // 否则画面跳变：O' = O + (A1 − A2)(1 − s1)（同刻度换锚不动的等价变换）。
                    let newPt = v.startLocation
                    let oldPt = anchorPoint ?? CGPoint(x: size.width / 2, y: size.height / 2)
                    let s1 = scale
                    offset = CGSize(width: offset.width + (oldPt.x - newPt.x) * (1 - s1),
                                    height: offset.height + (oldPt.y - newPt.y) * (1 - s1))
                    anchorPoint = newPt
                    anchor = UnitPoint(x: unit(newPt.x, size.width),
                                       y: unit(newPt.y, size.height))
                }
                let base = pinchBase ?? 1
                scale = min(max(base * v.magnification, Self.minPinchScale), Self.maxScale)
            }
            .onEnded { _ in
                isPinching = false
                pinchBase = nil
                anchorPoint = nil
                if scale < 1 {
                    // 松手 <1x 回弹 1x（验收）。
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                        scale = 1
                        offset = .zero
                        anchor = .center
                    }
                } else if scale > 1 {
                    // 刻度变化后位移出界部分弹回。
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                        offset = clampedOffset(offset, scale: scale, container: size)
                    }
                }
            }
    }

    // ── 单指平移（>1x）：橡皮筋越界阻尼 + 松手回弹进界 ──

    private func panGesture(size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { v in
                if panBase == nil { panBase = offset }
                let base = panBase ?? .zero
                let target = CGSize(width: base.width + v.translation.width,
                                    height: base.height + v.translation.height)
                offset = rubberbanded(target, scale: scale, container: size)
            }
            .onEnded { _ in
                panBase = nil
                withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                    offset = clampedOffset(offset, scale: scale, container: size)
                }
            }
    }

    // ── 1x 下拉关闭：只认纵向主导位移（横向让位 TabView 翻页），跟手 + 淡出 ──

    private func pullGesture(size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { v in
                let t = v.translation
                guard abs(t.height) > abs(t.width) * 1.2 else { return }
                pullOffset = CGSize(width: t.width * 0.15, height: t.height)
                pullFade = max(0, 1 - abs(t.height) / max(size.height * 0.55, 1))
            }
            .onEnded { v in
                let t = v.translation
                let dismiss = abs(t.height) > size.height * 0.18 && abs(t.height) > abs(t.width)
                if dismiss {
                    onPullDismiss()
                    // 不回弹：cover 收起动画期间保持拖离位（TG 同款飞出感）；
                    // 重进即新视图新状态。
                } else {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                        pullOffset = .zero
                        pullFade = 1
                    }
                }
            }
    }

    // ── 双击 1x↔2.5x（带动画；放大锚点 = 被点内容处） ──

    private func doubleTapGesture(size: CGSize) -> some Gesture {
        SpatialTapGesture(count: 2)
            .onEnded { v in
                if scale > 1.001 {
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                        scale = 1
                        offset = .zero
                        anchor = .center
                    }
                } else {
                    let t = v.location
                    let s1 = scale
                    let a1 = anchorPoint ?? CGPoint(x: size.width / 2, y: size.height / 2)
                    // 旧变换下被点视点 T 承载的内容点：c = (T − O − A1)/s1 + A1；
                    // 新变换锚点取 T，位移 O2 = −(c − T)·s2，被点内容点原地不动。
                    let cx = (t.x - offset.width - a1.x) / s1 + a1.x
                    let cy = (t.y - offset.height - a1.y) / s1 + a1.y
                    let s2 = Self.doubleTapScale
                    let target = CGSize(width: -(cx - t.x) * s2, height: -(cy - t.y) * s2)
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                        anchor = UnitPoint(x: unit(t.x, size.width), y: unit(t.y, size.height))
                        anchorPoint = t
                        scale = s2
                        offset = clampedOffset(target, scale: s2, container: size)
                    }
                }
            }
    }

    // ── 几何助手 ──

    /// 内容按容器等比缩放后的显示尺寸（scaledToFit 语义）。
    private func fitSize(container: CGSize) -> CGSize {
        let img = image.size
        guard img.width > 0, img.height > 0,
              container.width > 0, container.height > 0 else { return container }
        let ratio = min(container.width / img.width, container.height / img.height)
        return CGSize(width: img.width * ratio, height: img.height * ratio)
    }

    /// 该刻度下平移硬界（每轴）：内容超出容器的半径；内容不足容器宽时该轴为 0（不漂移）。
    private func panBounds(scale s: CGFloat, container: CGSize) -> CGSize {
        let fit = fitSize(container: container)
        return CGSize(width: max(0, (fit.width * s - container.width) / 2),
                      height: max(0, (fit.height * s - container.height) / 2))
    }

    /// 硬钳（松手回弹/双击落点用）。
    private func clampedOffset(_ o: CGSize, scale s: CGFloat, container: CGSize) -> CGSize {
        let b = panBounds(scale: s, container: container)
        return CGSize(width: min(max(o.width, -b.width), b.width),
                      height: min(max(o.height, -b.height), b.height))
    }

    /// 橡皮筋（拖动中用）：越界部分乘 0.35 阻尼，跟手但有弹性感。
    private func rubberbanded(_ o: CGSize, scale s: CGFloat, container: CGSize) -> CGSize {
        let b = panBounds(scale: s, container: container)
        func band(_ v: CGFloat, bound: CGFloat) -> CGFloat {
            if v > bound { return bound + (v - bound) * 0.35 }
            if v < -bound { return -bound + (v + bound) * 0.35 }
            return v
        }
        return CGSize(width: band(o.width, bound: b.width),
                      height: band(o.height, bound: b.height))
    }

    /// 视点 → UnitPoint（防零除；钳 0…1）。
    private func unit(_ v: CGFloat, _ total: CGFloat) -> CGFloat {
        guard total > 0 else { return 0.5 }
        return min(max(v / total, 0), 1)
    }
}
