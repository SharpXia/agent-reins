package dev.sharpxia.agentreins.hub

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Android：OkHttp 引擎，默认不读系统代理（OkHttp 需显式配置才走代理）。 */
actual fun hubHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) { json(json) }
    expectSuccess = false
}
