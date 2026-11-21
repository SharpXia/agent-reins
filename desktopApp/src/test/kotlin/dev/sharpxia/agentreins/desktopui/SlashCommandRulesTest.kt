package dev.sharpxia.agentreins.desktopui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * wp-6 桌面 composer 规则纯函数单测（desktopApp jvmTest 回归锚）。口径与 iOS wp-14
 * （HubViews.swift SlashCommandCatalog.suggestions / SlashCommand / selectSlashCommand）
 * 逐一对应 + 桌面新增 Enter 键裁决（resolveEnterKey）；回归锚 = 桌面↔iOS parity 不漂移。
 */
class SlashCommandRulesTest {

    // ── 目录初版：六命令、带参位、描述与 iOS 逐条对齐 ──

    @Test
    fun catalogIsSixCommandsMatchingIos() {
        assertEquals(6, SlashCommandCatalog.all.size)
        // 命令名（含前导 "/"）+ 顺序 + 带参位逐条对齐 iOS HubViews.swift SlashCommandCatalog.all
        assertEquals(
            listOf(
                Triple("/new", "开启全新会话上下文", false),
                Triple("/compact", "压缩上下文", false),
                Triple("/stop", "停止当前运行", false),
                Triple("/status", "查看会话状态", false),
                Triple("/steer", "运行中插入引导指令（可接文本）", true),
                Triple("/followup", "追加队列消息，本轮结束后执行（可接文本）", true),
            ),
            SlashCommandCatalog.all.map { Triple(it.name, it.summary, it.takesArgument) },
        )
    }

    // ── suggestions 触发与筛选（iOS suggestions(forDraft:) 同口径） ──

    @Test
    fun nonSlashDraftNeverTriggersPanel() {
        // 普通消息、URL 等零干扰不触发
        assertTrue(SlashCommandCatalog.suggestions("你好").isEmpty())
        assertTrue(SlashCommandCatalog.suggestions("https://example.com/a").isEmpty())
        assertTrue(SlashCommandCatalog.suggestions("").isEmpty())
        // 斜杠不在起手位（URL 中段）不触发
        assertTrue(SlashCommandCatalog.suggestions("a/b").isEmpty())
    }

    @Test
    fun loneSlashShowsWholeCatalog() {
        assertEquals(SlashCommandCatalog.all, SlashCommandCatalog.suggestions("/"))
    }

    @Test
    fun prefixMatchFiltersInCatalogOrder() {
        // 前缀命中保持目录序：/s 命中 stop/status/steer 三条
        assertEquals(
            listOf("/stop", "/status", "/steer"),
            SlashCommandCatalog.suggestions("/s").map { it.name },
        )
        assertEquals(listOf("/compact"), SlashCommandCatalog.suggestions("/c").map { it.name })
        assertEquals(listOf("/new"), SlashCommandCatalog.suggestions("/n").map { it.name })
        assertEquals(
            listOf("/followup"),
            SlashCommandCatalog.suggestions("/follow").map { it.name },
        )
    }

    @Test
    fun whitespaceExitsCommandSelectionPhase() {
        // 进入参数书写段（含空格/制表/换行）= 面板收起（带参命令续写期不再弹目录）
        assertTrue(SlashCommandCatalog.suggestions("/compact ").isEmpty())
        assertTrue(SlashCommandCatalog.suggestions("/steer\t").isEmpty())
        assertTrue(SlashCommandCatalog.suggestions("/followup\n").isEmpty())
        assertTrue(SlashCommandCatalog.suggestions("/steer 引导文本").isEmpty())
    }

    @Test
    fun noMatchHidesPanelWithoutBlockingFreeInput() {
        // 无命中 = 空（面板隐藏）；不拦自由输入——未知斜杠命令可照常发送，Gateway 侧原生裁决
        assertTrue(SlashCommandCatalog.suggestions("/zz").isEmpty())
        assertTrue(SlashCommandCatalog.suggestions("/Compact").isEmpty()) // 大小写敏感（iOS hasPrefix 同口径）
    }

    // ── Enter 键裁决（桌面补齐语义） ──

    @Test
    fun plainEnterSendsNonBlankDraft() {
        assertEquals(EnterKeyBehavior.Send, resolveEnterKey(false, false, "你好"))
        assertEquals(EnterKeyBehavior.Send, resolveEnterKey(false, false, "  x  "))
        assertEquals(EnterKeyBehavior.Send, resolveEnterKey(false, true, "/compact")) // Cmd/Ctrl+Enter 兜底发送
    }

    @Test
    fun shiftEnterNewlines() {
        assertEquals(EnterKeyBehavior.Newline, resolveEnterKey(true, false, "第一行"))
        // Shift 显式换行意图优先于 Cmd/Ctrl（Shift+Cmd+Enter = 换行）
        assertEquals(EnterKeyBehavior.Newline, resolveEnterKey(true, true, "第一行"))
    }

    @Test
    fun blankDraftNeverSendsButConsumesEnter() {
        // 与发送键 isNotBlank 防线同口径：空白草稿不发送；回车仍消费（防误插换行）
        assertEquals(EnterKeyBehavior.Ignored, resolveEnterKey(false, false, ""))
        assertEquals(EnterKeyBehavior.Ignored, resolveEnterKey(false, false, "   \n "))
        assertEquals(EnterKeyBehavior.Ignored, resolveEnterKey(false, true, ""))
    }

    // ── 2026-11-21 回车语义轮：多行矩阵 + 面板可见分支（与 Catalyst ComposerKeyHandlers 一张表）──

    @Test
    fun multilinePlainEnterNewlinesInsteadOfSend() {
        // 需求2：已多行时裸 Enter 插入换行（不再发送）——相对旧口径的唯一行为变更
        assertEquals(EnterKeyBehavior.Newline, resolveEnterKey(false, false, "第一行\n第二行"))
        assertEquals(EnterKeyBehavior.Newline, resolveEnterKey(false, false, "第一行\n"))
    }

    @Test
    fun panelVisibleEnterSendsDraftAsIs() {
        // 需求3：面板可见 Enter = 原样发送当前输入（不走补全；面板可见⇔草稿无换行故必单行）
        assertEquals(EnterKeyBehavior.Send, resolveEnterKey(false, false, "/com", slashPanelVisible = true))
        assertEquals(EnterKeyBehavior.Send, resolveEnterKey(false, false, "/", slashPanelVisible = true))
        // 面板不可见同 draft 不受影响（走单行分支，同果但语义独立）
        assertEquals(EnterKeyBehavior.Send, resolveEnterKey(false, false, "/com"))
    }

    @Test
    fun shiftEnterNewlinesEvenWithPanelVisible() {
        // Shift 优先级高于面板分支：任何情况强制换行
        assertEquals(EnterKeyBehavior.Newline, resolveEnterKey(true, false, "/com", slashPanelVisible = true))
        assertEquals(EnterKeyBehavior.Newline, resolveEnterKey(true, true, "/com", slashPanelVisible = true))
    }

    @Test
    fun cmdEnterForceSendsMultilineDraft() {
        // 需求2：Cmd+Enter 多行时强制发送（旧口径 Cmd+非空已发送，多行分支为明示回归锚）
        assertEquals(EnterKeyBehavior.Send, resolveEnterKey(false, true, "第一行\n第二行"))
    }

    @Test
    fun cmdEnterOnBlankDraftIgnored() {
        // 空白兜底先于 ⌘ 判定：空白（含纯换行）+Cmd 不发送、不落默认换行（消费 no-op）
        assertEquals(EnterKeyBehavior.Ignored, resolveEnterKey(false, true, "  \n"))
        assertEquals(EnterKeyBehavior.Ignored, resolveEnterKey(false, true, ""))
    }

    // ── 2026-11-21 债清轮：附件独发语义对齐（⌘Enter 跟 draftHasContent，与 Catalyst 发送
    //    按钮 ⌘Return 快捷键通道同判据；2026-11-08 拍板的空白判据本身不动）──

    @Test
    fun cmdEnterSendsAttachmentOnlyDraft() {
        // 附件独发（文本空白+附件在场）+Cmd = 发送——原「桌面键盘恒 no-op」知情差异消除；
        // 空白换行串（isNotBlank 为假）同判，draftHasContent 兜住
        assertEquals(EnterKeyBehavior.Send, resolveEnterKey(false, true, "", hasAttachments = true))
        assertEquals(EnterKeyBehavior.Send, resolveEnterKey(false, true, "  \n", hasAttachments = true))
        // 文本+附件并存 Cmd 本就发送（③ 判据扩后的明示回归锚）
        assertEquals(EnterKeyBehavior.Send, resolveEnterKey(false, true, "你好", hasAttachments = true))
    }

    @Test
    fun attachmentOnlyPlainEnterFollowsNewlineMatrix() {
        // 附件独发裸 Enter = 按多行矩阵换行（不消费让 TextField 默认插换行，不误发——
        // 附件独发只认 ⌘/Ctrl 强发）；Shift ①支优先照旧
        assertEquals(EnterKeyBehavior.Newline, resolveEnterKey(false, false, "", hasAttachments = true))
        assertEquals(EnterKeyBehavior.Newline, resolveEnterKey(false, false, "  \n", hasAttachments = true))
        assertEquals(EnterKeyBehavior.Newline, resolveEnterKey(true, false, "", hasAttachments = true))
    }

    @Test
    fun trulyBlankDraftWithoutAttachmentsNeverSends() {
        // 真空白（文本空白且无附件）判据不变：裸 Enter/⌘Enter 均兜底消费不发送（先于 ⌘ 判定）
        assertEquals(EnterKeyBehavior.Ignored, resolveEnterKey(false, false, "", hasAttachments = false))
        assertEquals(EnterKeyBehavior.Ignored, resolveEnterKey(false, true, "", hasAttachments = false))
    }

    // ── 2026-11-21 面板键位轮：高亮 clamp / 环绕（镜像 iOS slashActiveIndex/slashMoveSelection）──

    @Test
    fun slashActiveIndexClampsAndDefaultsToZero() {
        assertEquals(0, slashActiveIndex(null, 6)) // 未动过键盘 = 首行
        assertEquals(0, slashActiveIndex(0, 6))
        assertEquals(5, slashActiveIndex(5, 6))
        assertEquals(0, slashActiveIndex(-3, 6)) // 负越界收敛首行
        assertEquals(5, slashActiveIndex(99, 6)) // 正越界收敛尾行
    }

    @Test
    fun slashActiveIndexNullWhenPanelHidden() {
        assertEquals(null, slashActiveIndex(null, 0))
        assertEquals(null, slashActiveIndex(2, 0))
        assertEquals(null, slashMovedIndex(0, 1, 0))
    }

    @Test
    fun slashMovedIndexWrapsAround() {
        assertEquals(1, slashMovedIndex(0, 1, 6)) // 末行 ↓ 回首行的前一步
        assertEquals(5, slashMovedIndex(0, -1, 6)) // 首行 ↑ 回末行（环绕）
        assertEquals(0, slashMovedIndex(5, 1, 6)) // 末行 ↓ 回首行（环绕）
        assertEquals(4, slashMovedIndex(5, -1, 6))
        assertEquals(1, slashMovedIndex(null, 1, 6)) // 未动过键盘从首行起步
        assertEquals(0, slashMovedIndex(2, -2, 6))
        assertEquals(2, slashMovedIndex(2, 6, 6)) // 整圈回原位
        assertEquals(0, slashMovedIndex(2, 10, 6)) // 大步长越界仍收敛域内（双取模）
    }
}
