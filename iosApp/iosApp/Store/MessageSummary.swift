import Foundation

/// 通知摘要（v1.3 轮，2026-11-10）：消息正文 → 本地通知 body 的公共纯函数。
/// 口径镜像服务端 `internal/push/summary.go` `MessageSummary`（两端同规则，逐条对齐）：
/// 剥离 markdown（围栏代码块/图片/水平线整段去除；链接/行内代码/强调保留内容）、
/// 合并空白、按字素截断 ≤140 字符（截断以「…」收尾且计入 140）；剥离后为空且带附件
/// →「[图片]」（N≥2 =「[图片]×N」，纯附件消息含生图输出行）。
///
/// 语义边界（与服务端 ADR-10 推送面口径一致）：剥离是**表层标记清理**，不做内容理解；
/// 剥离失败时原文照出（截断兜底），不因摘要失败丢弃通知。
enum MessageSummary {

    /// 摘要长度上限（按 Character 计；中文一字一 Character，≤140 字符）。
    static let maxCharacters = 140

    /// 纯附件消息的正文占位（产品口径文案，与服务端 attachmentPlaceholder 同源）。
    static let attachmentPlaceholder = "[图片]"

    /// 正文 + 附件数 → 摘要。空结果 = 无可摘要内容（调用方回退静态文案）。
    static func make(content: String, attachments: Int) -> String {
        let text = collapseWhitespace(stripMarkdown(content))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty {
            return attachments > 0 ? attachmentTag(attachments) : ""
        }
        return truncate(text)
    }

    /// 纯附件占位：1 张 =「[图片]」，N≥2 =「[图片]×N」（服务端 attachmentTag 同式）。
    static func attachmentTag(_ n: Int) -> String {
        n <= 1 ? attachmentPlaceholder : "\(attachmentPlaceholder)×\(n)"
    }

    /// 按 Character 截断至 ≤140；截断发生时以「…」收尾（计入 140 内，总长仍 ≤140）。
    static func truncate(_ s: String) -> String {
        guard s.count > maxCharacters else { return s }
        return String(s.prefix(maxCharacters - 1)) + "…"
    }

    /// 空白合并（\s+ → 单空格；服务端 reWS 同口径）。
    static func collapseWhitespace(_ s: String) -> String {
        regex(#"\s+"#).stringByReplacingMatches(in: s, range: full(s), withTemplate: " ")
    }

    // ── markdown 剥离（顺序敏感，与服务端 stripMarkdown 逐条对应：围栏最先防代码内容
    // 被误剥；图片先于链接——「![..](..)」内嵌「[..](..)」形态） ──

    static func stripMarkdown(_ s: String) -> String {
        var t = s
        t = regex("(?s)```.*?```").stringByReplacingMatches(in: t, range: full(t), withTemplate: " ")
        t = regex(#"!\[[^\]]*\]\([^)]*\)"#).stringByReplacingMatches(in: t, range: full(t), withTemplate: " ")
        t = regex(#"\[([^\]]*)\]\([^)]*\)"#).stringByReplacingMatches(in: t, range: full(t), withTemplate: "$1")
        t = regex(#"`+([^`]*)`+"#).stringByReplacingMatches(in: t, range: full(t), withTemplate: "$1")
        t = regex(#"\*\*([^*]+)\*\*|__([^_]+)__"#).stringByReplacingMatches(in: t, range: full(t), withTemplate: "$1$2")
        t = regex(#"\*([^*\n]+)\*|_([^_\n]+)_"#).stringByReplacingMatches(in: t, range: full(t), withTemplate: "$1$2")
        t = regex(#"~~([^~]+)~~"#).stringByReplacingMatches(in: t, range: full(t), withTemplate: "$1")
        t = regex(#"(?m)^[ \t]*#{1,6}[ \t]+"#).stringByReplacingMatches(in: t, range: full(t), withTemplate: "")
        t = regex(#"(?m)^[ \t]*>[ \t]?"#).stringByReplacingMatches(in: t, range: full(t), withTemplate: "")
        t = regex(#"(?m)^[ \t]*(?:[-*+]|\d+[.)])[ \t]+"#).stringByReplacingMatches(in: t, range: full(t), withTemplate: "")
        t = regex(#"(?m)^[ \t]*(?:[-*_][ \t]*){3,}$"#).stringByReplacingMatches(in: t, range: full(t), withTemplate: " ")
        return t
    }

    /// 包级缓存正则（NSRegularExpression 构造有成本；模式串即缓存键）。
    private static var cache: [String: NSRegularExpression] = [:]

    private static func regex(_ pattern: String) -> NSRegularExpression {
        if let hit = cache[pattern] { return hit }
        // 服务端同模式串已验证可编译；构造失败（理论不可达）退化为恒不匹配正则——剥离
        // 失败原文照出（截断兜底），不因摘要失败丢弃通知。
        let r = (try? NSRegularExpression(pattern: pattern, options: []))
            ?? (try! NSRegularExpression(pattern: #"\b\B"#, options: []))
        cache[pattern] = r
        return r
    }

    private static func full(_ s: String) -> NSRange {
        NSRange(location: 0, length: (s as NSString).length)
    }
}
