package dev.sharpxia.agentreins.hub

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Desktop：CIO 引擎（JVM 默认不读系统代理，与既有直连客户端行为一致）。 */
actual fun hubHttpClient(json: Json): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(json) }
    expectSuccess = false
}
