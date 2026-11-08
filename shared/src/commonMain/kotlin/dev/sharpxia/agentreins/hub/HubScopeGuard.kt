package dev.sharpxia.agentreins.hub

import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * 根 scope 兜底异常处理器（挂机崩溃修复轮，2026-11-08）。
 *
 * 背景：同步栈的协程树在根 scope（SupervisorJob，无 CoroutineExceptionHandler）上起跑，
 * 任何子协程的未捕获异常都会走 kotlinx `propagateExceptionFinalResort` → Kotlin/Native
 * `terminateWithUnhandledException` → 进程 abort（0.5.4 真机 9 连 SIGABRT 同族：Ktor
 * Darwin WS didCompleteWithError 在回调线程构造异常，接收循环/连接循环路径上的逃逸点
 * 一处未堵即全进程陪葬）。WS 接收循环的定向修复见 [DefaultHubWsClient]；本处理器是
 * **最后防线**——定向修复漏掉的新逃逸点在此降级为「记录 + 吞掉」，IM 常驻进程稳定性
 * 优先于崩溃暴露（崩溃栈仍在调试器/控制台可见）。
 */
val hubRootScopeGuard: CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, throwable ->
        println("AgentReins/hub: 未捕获协程异常已兜底降级（防 abort）: ${throwable.message}")
        throwable.printStackTrace()
    }
