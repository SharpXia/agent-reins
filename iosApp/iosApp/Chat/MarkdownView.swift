import SwiftUI
import MarkdownUI

/// Markdown v2 渲染（表格/嵌套列表/代码块/引用，cmark-gfm 全量）。
/// Compose 版 mikepenz 渲染器的 iOS lazy-list 滚动重绘问题（issue #512）在原生侧不存在。
struct MarkdownView: View {
    let content: String

    var body: some View {
        Markdown(content)
            .markdownTheme(.gitHub)
            .markdownTextStyle {
                FontSize(15)
            }
            .lineSpacing(4)
    }
}
