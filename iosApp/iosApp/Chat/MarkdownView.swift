import SwiftUI
import MarkdownUI

let markdownCacheMinimumLength = 120

/// Markdown v2 渲染（表格/嵌套列表/代码块/引用，cmark-gfm 全量）。
/// Compose 版 mikepenz 渲染器的 iOS lazy-list 滚动重绘问题（issue #512）在原生侧不存在。
struct MarkdownView: View {
    let content: String

    var body: some View {
        // Store snapshots replace the whole entries array while a run is active. The
        // resulting parent-row invalidation is cheap for this wrapper, but MarkdownUI's
        // block tree is not: parsing and rebuilding it for every unchanged row can easily
        // consume a frame while the user is scrolling. Keep the parse behind an
        // EquatableView so SwiftUI only evaluates it when the source text changes.
        CachedMarkdownContent(content: content)
            .equatable()
    }
}

/// The expensive part of a Markdown row is intentionally isolated in an equatable child.
/// `MarkdownView` itself is still allowed to be recreated by a changing chat snapshot; the
/// child prevents the MarkdownUI view tree (and the parser cache lookup) from being rebuilt
/// for the unchanged rows around the streaming tail.
private struct CachedMarkdownContent: View, Equatable {
    let content: String

    var body: some View {
        Markdown(MarkdownParseCache.content(content))
            .markdownTheme(.gitHub)
            .markdownTextStyle {
                FontSize(15)
            }
            .lineSpacing(4)
    }

    static func == (lhs: CachedMarkdownContent, rhs: CachedMarkdownContent) -> Bool {
        lhs.content == rhs.content
    }
}

/// cmark 解析结果全局缓存（SW 反馈②：滑动新物化长消息时同步解析卡顿）。行滚回视口
/// 重建 view 不再重解析；首次物化仍需解析（不可避免）。只缓存 ≥120 字符的重内容——
/// 流式 delta 都是短串（解析本来就快），不污染缓存。
private enum MarkdownParseCache {
    static let cache: NSCache<NSString, Box> = {
        let c = NSCache<NSString, Box>()
        // Keep enough history for a long transcript, while bounding the AST memory held
        // after the user has visited many conversations. The cost is an estimate based on
        // source size; NSCache may evict earlier under system memory pressure as well.
        c.countLimit = 128
        c.totalCostLimit = 12 * 1024 * 1024
        return c
    }()

    final class Box {
        let value: MarkdownContent
        init(_ v: MarkdownContent) { value = v }
    }

    static func content(_ raw: String) -> MarkdownContent {
        guard raw.utf8.count >= markdownCacheMinimumLength else { return MarkdownContent(raw) }
        let key = raw as NSString
        if let hit = cache.object(forKey: key) { return hit.value }
        let parsed = MarkdownContent(raw)
        cache.setObject(Box(parsed), forKey: key, cost: min(raw.utf8.count, Int.max))
        return parsed
    }

    /// Warm the cache incrementally on the main actor before a long message enters the
    /// viewport. MarkdownContent's AST is not Sendable, so this deliberately avoids a
    /// detached task; yielding between messages keeps the first visible frame responsive
    /// without crossing actor boundaries with the parsed tree.
    @MainActor
    static func prewarm(_ rawContents: [String]) async {
        var seen = Set<String>()
        seen.reserveCapacity(rawContents.count)
        for raw in rawContents {
            guard !Task.isCancelled else { return }
            guard raw.utf8.count >= markdownCacheMinimumLength,
                  seen.insert(raw).inserted else { continue }

            autoreleasepool {
                _ = content(raw)
            }
            // Let scrolling/layout run between cmark parses. A tiny delay also prevents
            // a large history page from monopolizing the main actor for one long burst.
            try? await Task.sleep(nanoseconds: 2_000_000)
        }
    }
}

/// Internal bridge used by ChatView's incremental prewarm task. Keeping the cache private
/// prevents view callers from depending on its storage policy while still allowing the list
/// to schedule parsing from already available message data.
@MainActor
func prewarmMarkdown(_ contents: [String]) async {
    await MarkdownParseCache.prewarm(contents)
}
