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
