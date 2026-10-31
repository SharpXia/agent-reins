import SwiftUI
import MarkdownUI

/// Markdown v2 渲染（表格/嵌套列表/代码块/引用，cmark-gfm 全量）。
/// Compose 版 mikepenz 渲染器的 iOS lazy-list 滚动重绘问题（issue #512）在原生侧不存在。
struct MarkdownView: View {
    let content: String

    var body: some View {
        Markdown(MarkdownParseCache.content(content))
            .markdownTheme(.gitHub)
            .markdownTextStyle {
                FontSize(15)
            }
            .lineSpacing(4)
    }
}

/// cmark 解析结果全局缓存（SW 反馈②：滑动新物化长消息时同步解析卡顿）。行滚回视口
/// 重建 view 不再重解析；首次物化仍需解析（不可避免）。只缓存 ≥120 字符的重内容——
/// 流式 delta 都是短串（解析本来就快），不污染缓存。
private enum MarkdownParseCache {
    static let cache: NSCache<NSString, Box> = {
        let c = NSCache<NSString, Box>()
        c.countLimit = 200
        return c
    }()

    final class Box {
        let value: MarkdownContent
        init(_ v: MarkdownContent) { value = v }
    }

    static func content(_ raw: String) -> MarkdownContent {
        guard raw.count >= 120 else { return MarkdownContent(raw) }
        let key = raw as NSString
        if let hit = cache.object(forKey: key) { return hit.value }
        let parsed = MarkdownContent(raw)
        cache.setObject(Box(parsed), forKey: key)
        return parsed
    }
}
