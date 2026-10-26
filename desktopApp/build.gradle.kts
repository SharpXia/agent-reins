plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":composeApp"))
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    // M25Probe 自建 in-memory 缓存库用
    implementation(libs.sqldelight.sqlite.driver)
}

compose.desktop.application {
    mainClass = "dev.sharpxia.agentreins.MainKt"
}

// M1 网关冒烟：source .env 后 ./gradlew :desktopApp:smokeGateway（凭据走环境变量，不入仓库）
tasks.register<JavaExec>("smokeGateway") {
    group = "verification"
    description = "Connect to the OpenClaw gateway and run one send/receive round (env: OPENCLAW_GATEWAY_URL/OPENCLAW_API_KEY)."
    mainClass = "dev.sharpxia.agentreins.GatewaySmokeKt"
    classpath = sourceSets["main"].runtimeClasspath
}

// M2.5 数据基座探针：source .env 后 ./gradlew :desktopApp:probeM25（分页/幂等键退休/缓存落库）
tasks.register<JavaExec>("probeM25") {
    group = "verification"
    description = "Verify M2.5 protocol assumptions against the live gateway (pagination, idempotencyKey retire, cache persistence)."
    mainClass = "dev.sharpxia.agentreins.M25ProbeKt"
    classpath = sourceSets["main"].runtimeClasspath
}
