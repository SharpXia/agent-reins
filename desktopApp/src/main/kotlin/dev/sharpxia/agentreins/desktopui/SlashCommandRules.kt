package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.projection.CommandListItem

/**
 * wp-6 桌面 composer 规则纯函数层（判定与目录与 UI 解耦，desktopApp jvmTest 回归锚——
 * 同 TimelineScroll.kt / MessageActions.kt 分层纪律）：
 * - 斜杠命令目录：客户端自建初版六命令（iOS wp-14 SlashCommandCatalog 全量复刻——命令名/
 *   描述/带参位逐条对齐，HubViews.swift:775-800 同口径），筛选 = 前缀匹配 + 参数书写段退出；
 * - Enter 键裁决（2026-11-21 回车语义轮重排，与 Catalyst HubViews.ComposerKeyHandlers
 *   共用一张无歧义优先级表，首个命中生效）：
 *   0. IME 组合中（composition != null）→ 全键让行默认（调用侧门，纯函数层不涉）；
 *   1. Shift+Enter（含 Shift+Cmd/Ctrl）→ Newline 强制换行（Shift 是显式换行意图）；
 *   2. 真空白草稿（文本空白且无附件）→ Ignored（消费 no-op 不发不换行；先于 ⌘ 判定——
 *      真空白+⌘Enter 不落默认换行，此序不变）。2026-11-21 债清轮：空白兜底收窄为真空白——
 *      附件独发（文本空白+附件在场）不再按空白消费；
 *   3. Cmd/Ctrl+Enter + draftHasContent（文本非空白 或 附件在场）→ Send（强制发送，天然
 *      满足多行 ⌘/Ctrl+Enter；2026-11-21 走查修复②：Catalyst 分流③补 .control 对齐，此前
 *      Catalyst 只认 ⌘=Ctrl+Enter 多行换行的分叉已消；同日债清轮：附件独发 ⌘Enter 对齐
 *      Catalyst——其 ⌘Return 快捷键通道跟发送按钮 draftHasContent 含附件本就可发，桌面
 *      键盘通道同步跟附件语义，原「桌面键盘恒 no-op」知情差异消除；2026-11-08 拍板的
 *      空白判据=draftHasContent 含附件本身不动）；
 *   4. 非空 + 面板可见 → Send（原样发送当前输入不走补全；面板可见⇔草稿无换行故必单行）；
 *   5. 非空白单行 → Send；非空白多行 → Newline（裸 Enter 不消费，让 TextField 默认插换行）；
 *      附件独发裸 Enter → Newline（同多行矩阵让 TextField 默认插换行，不误发）。
 *   相对旧口径的行为变更 = 多行裸 Enter 从 Send 改 Newline（回车语义轮）+ 附件独发 ⌘Enter
 *   从 Ignored 改 Send、裸 Enter 从 Ignored 改 Newline（债清轮，双端对齐）。
 * - 面板键盘导航（同轮桌面补齐，镜像 iOS HubViews.slashActiveIndex/slashMoveSelection）：
 *   ↑↓ 环绕移动高亮、Tab 补全高亮（无键盘动作即首行）命令+空格；重置口径 = Tab/发送/预填
 *   后显式清空（iOS 只 clamp 不重置——两端口径差异在案）。
 *
 * 目录描述是客户端提示、不构成 Gateway 行为承诺（iOS 同纪律）：选中后的发送只是普通
 * message.send 文本（桥侧 chat.send 原样透传 message，Hub 命令面零改动、无协议风险）。
 */

/** wp-6：斜杠命令条目——客户端自建初版目录条目（iOS SlashCommand 同构）。 */
internal data class SlashCommand(
    /** 含前导 "/"（如 "/compact"）；发送文本即此字符串（带参命令再续写参数）。 */
    val name: String,
    /** 客户端目录描述（初版自建，非 Gateway 权威文档）。 */
    val summary: String,
    /** true = 需接参数：选中预填输入框续写，不直接发送。 */
    val takesArgument: Boolean,
    /** #22 修复轮（2026-11-08）：首参静态选项闭集（Gateway catalog args 有界视图，交互卡数据面）。 */
    val choices: List<dev.sharpxia.agentreins.hub.projection.CommandChoice>? = null,
    /** 选项所属参数名（交互卡副标题展示）。 */
    val argName: String? = null,
)

/**
 * wp-6：命令目录初版（客户端自建）。scope 命名的 Gateway 原生斜杠六命令；steer/followup
 * 对应 chat.send queueMode 词汇族（protocol.schema.json ChatSendParams.queueMode，
 * research/01:113；research/01:236 注明 sessions.steer 为 queueMode:"interrupt" 废弃别名）。
 * 2026-11-07 动态目录轮：目录已接 Gateway commands.list（REST 端点 #21，scope=text/
 * includeArgs=false 服务端钉死）——动态条目经 [mergedSlashCommands] 合并（动态优先、静态
 * 保底），静态六降级为 Gateway 不可达/未返回时的兜底。
 */
internal object SlashCommandCatalog {
    val all: List<SlashCommand> = listOf(
        SlashCommand(name = "/new", summary = "开启全新会话上下文", takesArgument = false),
        SlashCommand(name = "/compact", summary = "压缩上下文", takesArgument = false),
        SlashCommand(name = "/stop", summary = "停止当前运行", takesArgument = false),
        SlashCommand(name = "/status", summary = "查看会话状态", takesArgument = false),
        SlashCommand(name = "/steer", summary = "运行中插入引导指令（可接文本）", takesArgument = true),
        SlashCommand(
            name = "/followup",
            summary = "追加队列消息，本轮结束后执行（可接文本）",
            takesArgument = true,
        ),
    )

    /**
     * 触发与筛选（iOS suggestions(forDraft:) 同口径）：draft 以 "/" 起手且尚未出现空白
     * （用户仍在选命令、未写参数）时，按整段 draft 对命令名做前缀匹配；draft 为单独 "/"
     * 时展示全目录；无命中返回空 = 面板隐藏，不拦自由输入（用户仍可发送任意文本，含
     * 未知斜杠命令——Gateway 侧原生裁决）。前缀匹配大小写敏感（iOS hasPrefix 同口径）。
     * [catalog] 缺省静态六（既有调用点/单测零改动）；动态目录轮由 ConversationScreen 传合并目录。
     */
    fun suggestions(forDraft: String, catalog: List<SlashCommand> = all): List<SlashCommand> {
        if (!forDraft.startsWith("/")) return emptyList()
        if (forDraft.contains(' ') || forDraft.contains('\t') || forDraft.contains('\n')) {
            return emptyList()
        }
        if (forDraft == "/") return catalog
        return catalog.filter { it.name.startsWith(forDraft) }
    }
}

/** 归一键：剥前导 "/"。静态名自带斜杠、动态 name 无斜杠（Gateway mapCommand 已 slice(1)），剥平再比对。 */
internal fun slashCommandKey(raw: String): String = raw.removePrefix("/")

/**
 * 动态 ∪ 静态六合并目录（2026-11-07 动态目录轮）：Gateway commands.list（端点 #21）有界
 * 透传的动态条目是权威目录，静态六只兜 Gateway 不可达/未返回的场合——按归一键（剥前导
 * "/"）去重，动态优先、静态保底（Gateway 已覆盖的静态行让位，未覆盖的行保留）。展示/发送
 * 文本 = 动态 textAliases[0]（契约钉死 "/" 前缀，防御性补斜杠保面板可触发）缺省 "/"+name。
 * 筛选与 Enter 裁决消费方零改动，只换数据源。
 */
internal fun mergedSlashCommands(dynamic: List<CommandListItem>): List<SlashCommand> {
    if (dynamic.isEmpty()) return SlashCommandCatalog.all
    val merged = LinkedHashMap<String, SlashCommand>()
    dynamic.forEach { item ->
        val display = item.textAliases?.firstOrNull()?.takeIf { it.isNotEmpty() }
            ?.let { if (it.startsWith("/")) it else "/$it" }
            ?: ("/" + item.name)
        // v1.1 additive 2026-11-08 修复轮：首参选项闭集透传（交互卡数据面）——取第一个带
        // choices 的参数（args 有界视图白名单 name/required/choices）。
        val interactiveArg = item.args?.firstOrNull { !it.choices.isNullOrEmpty() }
        merged[slashCommandKey(item.name)] = SlashCommand(
            name = display,
            summary = item.description,
            takesArgument = item.acceptsArgs,
            choices = interactiveArg?.choices,
            argName = interactiveArg?.name,
        )
    }
    SlashCommandCatalog.all.forEach { cmd -> merged.putIfAbsent(slashCommandKey(cmd.name), cmd) }
    return merged.values.toList()
}

/** wp-6：composer Enter 键裁决结果（桌面补齐语义）。 */
internal enum class EnterKeyBehavior {
    /** 消费回车并触发发送（与发送键同一通道）。 */
    Send,
    /** 不消费——回车换行走 TextField 默认行为。 */
    Newline,
    /** 消费回车但不发送（空白草稿，防误插换行也防误触发送）。 */
    Ignored,
}

/**
 * wp-6 + 2026-11-21 回车语义轮（同日债清轮附件语义对齐）：Enter 键裁决——分流序与头注释
 * 优先级表逐支对应（与 Catalyst ComposerKeyHandlers 一张表）。注释序号指向头表分支号；
 * 代码求值序与表序等价（②真空白兜底写在末支，因 ③ 走 draftHasContent、④⑤ 各带 isNotBlank
 * 前置、附件独发支自带 hasAttachments，真空白草稿只能落入末支）。③ 强发判据 = draftHasContent
 *（文本 isNotBlank 或有附件，ConversationScreen 发送键 enabled 同口径）；② 兜底判据 = 真空白
 *（文本空白且无附件）。[slashPanelVisible]/[hasAttachments] 缺省 false：既有调用点与旧单测
 *（不含面板/附件用例）零破坏。
 */
internal fun resolveEnterKey(
    isShiftPressed: Boolean,
    isMetaOrCtrlPressed: Boolean,
    draft: String,
    slashPanelVisible: Boolean = false,
    hasAttachments: Boolean = false,
): EnterKeyBehavior = when {
    isShiftPressed -> EnterKeyBehavior.Newline // ① Shift（含 Shift+Cmd/Ctrl）= 显式换行意图
    isMetaOrCtrlPressed && (draft.isNotBlank() || hasAttachments) ->
        EnterKeyBehavior.Send // ③ Cmd/Ctrl+Enter 强制发送（draftHasContent 含附件——附件独发同发）
    slashPanelVisible && draft.isNotBlank() -> EnterKeyBehavior.Send // ④ 面板可见=原样发送（不走补全）
    !draft.contains('\n') && draft.isNotBlank() -> EnterKeyBehavior.Send // ⑤ 单行裸 Enter=发送
    draft.isNotBlank() -> EnterKeyBehavior.Newline // ⑤ 多行裸 Enter=换行（不消费，TextField 默认插换行）
    hasAttachments -> EnterKeyBehavior.Newline // 附件独发裸 Enter=按多行矩阵换行（不消费不误发）
    else -> EnterKeyBehavior.Ignored // ② 真空白草稿（除 Shift 外的修饰组合——Shift+空白已由①支换行让行）消费不发不换行（兜底先于 ⌘ 判定）
}

/**
 * 2026-11-21 面板键位轮（桌面补齐 ↑↓/Tab，此前为零键盘导航）：生效高亮行——镜像 iOS
 * HubViews.slashActiveIndex 的 clamp 口径（越界收敛到尾行、负值收敛到首行）；raw=null
 * （未动过键盘）落第 0 行；count≤0（面板不可见）恒 null。重置口径两端口径差异知情：
 * 桌面调用侧在 Tab 补全/发送/预填后显式重置（iOS 只 clamp 不重置，「↑↓ 保持现状」轮未修）。
 */
internal fun slashActiveIndex(raw: Int?, count: Int): Int? {
    if (count <= 0) return null
    return (raw ?: 0).coerceIn(0, count - 1)
}

/**
 * 2026-11-21 面板键位轮：高亮环绕移动——镜像 iOS slashMoveSelection（从 clamp 后基线走
 * delta 步，首行 ↑ 回末行、末行 ↓ 回首行）；双取模防任意 delta 越界（iOS 单取模公式在
 * delta=±1 时与本品等值）。count≤0 恒 null。
 */
internal fun slashMovedIndex(current: Int?, delta: Int, count: Int): Int? {
    val base = slashActiveIndex(current, count) ?: return null
    return ((base + delta) % count + count) % count
}
