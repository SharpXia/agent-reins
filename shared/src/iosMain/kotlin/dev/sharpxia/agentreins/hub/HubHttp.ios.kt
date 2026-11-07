package dev.sharpxia.agentreins.hub

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** iOS：Darwin 引擎，会话级清空代理字典 → 不继承系统 HTTP/SOCKS 代理，直连 Tailscale 内网 Hub。 */
actual fun hubHttpClient(json: Json): HttpClient = HttpClient(Darwin) {
    engine {
        configureSession {
            connectionProxyDictionary = emptyMap<Any?, Any?>()
        }
    }
    install(ContentNegotiation) { json(json) }
    expectSuccess = false
}
