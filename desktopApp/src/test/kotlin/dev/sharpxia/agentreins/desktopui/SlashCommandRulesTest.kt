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
}
