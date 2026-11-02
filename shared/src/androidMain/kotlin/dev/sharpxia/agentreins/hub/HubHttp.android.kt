package dev.sharpxia.agentreins.hub

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.net.Proxy

/**
 * Android：OkHttp 引擎，显式不走任何代理（与 iOS/desktop 同构直连——前端只连 Tailscale 内网 Hub，
 * 任何系统/应用层代理对该网段都只会坏事）。
 */
actual fun hubHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
    engine { proxy = Proxy.NO_PROXY }
    install(ContentNegotiation) { json(json) }
    expectSuccess = false
}
