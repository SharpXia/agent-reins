package dev.sharpxia.agentreins.hub

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

/**
 * Hub 专用 HttpClient 工厂（引擎按平台：android=OkHttp / desktop=CIO / ios=Darwin）。
 *
 * iOS actual 关闭系统代理继承：Hub 是私有内网（Tailscale）后端，模拟器/真机若配置了系统
 * HTTP 代理（如宿主机 127.0.0.1:7890），NSURLSession 默认经代理转发而代理够不到 100.64/10
 * 网段，表现为 502（2026-11-02 模拟器实测）。P4 切公网 https 时复核此策略。
 */
expect fun hubHttpClient(json: Json): HttpClient
