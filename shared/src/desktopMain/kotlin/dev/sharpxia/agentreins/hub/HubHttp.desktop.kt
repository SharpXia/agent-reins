package dev.sharpxia.agentreins.hub

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Desktop：CIO 引擎，显式直连不走系统代理。实测（macOS + Temurin 21）JVM 启动时会把系统代理
 * 同步成 http.proxyHost 等属性（例外列表通常不含 Tailscale 网段 100.64.0.0/10），CIO 默认
 * ProxySelector 随之把 Hub 的数值 IP 劫持到代理、代理够不到内网即上游 502。
 * CIO 无法表达「禁用代理」（Proxy.NO_PROXY 会被引擎以 UNKNOWN 拒绝，proxy=null 则跟随上述属性），
 * 故清掉属性快照——launcher 只在进程启动时同步一次，进程内不会回填。属性清空同样惠及同进程
 * 其它 Ktor 客户端（前端只连 Hub 与内网网关，代理对本进程没有合法用途）。
 */
actual fun hubHttpClient(json: Json): HttpClient {
    clearJvmProxyProperties()
    return HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        expectSuccess = false
    }
}

private fun clearJvmProxyProperties() {
    listOf(
        "java.net.useSystemProxies",
        "http.proxyHost", "http.proxyPort",
        "https.proxyHost", "https.proxyPort",
        "socksProxyHost", "socksProxyPort",
    ).forEach { System.clearProperty(it) }
}
