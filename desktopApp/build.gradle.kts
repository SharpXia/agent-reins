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
    // 桌面 Hub 面（wp-6）：material3 组件（composeApp 内的 material3 是 implementation 不透出，自取同版本）
    implementation(libs.compose.material3)
    // hub-sync 验收通道自建 in-memory 投影库用
    implementation(libs.sqldelight.sqlite.driver)
    // hub 验收通道（hub-login/hub-sync/hub-wsraw）直接组装 hub 客户端栈——shared 用
    // implementation 暴露，传递依赖不透出，这里自取同版本
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.kotlinx.serialization.json)
    // wp-10 消息操作规则纯函数单测（jvmTest，随 :desktopApp:build 的 check 生命周期执行）
    testImplementation(kotlin("test"))
}

compose.desktop.application {
    mainClass = "dev.sharpxia.agentreins.MainKt"
}
