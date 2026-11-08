package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.projection.CommandListItem

/**
 * wp-6 桌面 composer 规则纯函数层（判定与目录与 UI 解耦，desktopApp jvmTest 回归锚——
 * 同 TimelineScroll.kt / MessageActions.kt 分层纪律）：
 * - 斜杠命令目录：客户端自建初版六命令（iOS wp-14 SlashCommandCatalog 全量复刻——命令名/
 *   描述/带参位逐条对齐，HubViews.swift:775-800 同口径），筛选 = 前缀匹配 + 参数书写段退出；
 * - Enter 键裁决：桌面补齐的键盘发送语义（iOS 竖排 TextField 无此项，桌面独立定义）——
 *   Enter（无 Shift）发送、Shift+Enter 换行、Cmd/Ctrl+Enter 兜底发送、空白草稿不发送。
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
 * wp-6：Enter 键裁决——Enter（无 Shift）发送、Shift+Enter 换行、Cmd/Ctrl+Enter 兜底发送；
 * 空白草稿一律不发送（与发送键 isNotBlank 防线同口径），但普通/修饰键回车仍消费掉（防在
 * 草稿误插换行）。Shift 优先于 Cmd/Ctrl（Shift+Cmd+Enter = 换行，Shift 是「换行」显式意图）。
 */
internal fun resolveEnterKey(
    isShiftPressed: Boolean,
    isMetaOrCtrlPressed: Boolean,
    draft: String,
): EnterKeyBehavior = when {
    isShiftPressed -> EnterKeyBehavior.Newline // Shift（含 Shift+Cmd/Ctrl）= 显式换行意图
    isMetaOrCtrlPressed && draft.isNotBlank() -> EnterKeyBehavior.Send // Cmd/Ctrl+Enter 兜底发送
    !isMetaOrCtrlPressed && draft.isNotBlank() -> EnterKeyBehavior.Send // 裸 Enter 发送
    else -> EnterKeyBehavior.Ignored // 空白草稿（任意修饰组合）不发送，回车消费防误插换行
}
