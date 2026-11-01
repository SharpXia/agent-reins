import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    android {
        namespace = "dev.sharpxia.agentreins.shared"
        compileSdk = 37
        minSdk = 26
        compilerOptions.jvmTarget = JvmTarget.JVM_17
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            // Hub REST 客户端（hub/ 包，P1 起填充）：JSON 编解码 + Bearer 注入
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.websockets)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        // iosMain 由 default hierarchy 在配置后期生成，脚本求值期无访问器（本仓库已知坑）；
        // 依赖加在叶子 sourceSet：K/N compilation 聚合 common→iosMain→叶子 的源码与依赖，效果等同
        val iosArm64Main by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.sqldelight.native.driver)
            }
        }
        val iosSimulatorArm64Main by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.sqldelight.native.driver)
            }
        }
        // iOS 专属依赖同时挂到 iosMain 中间层：叶子编译（Xcode 框架链路）一直可用，
        // 但 compileIosMainKotlinMetadata 走 iosMain 类路径——main（c817869）上
        // :shared:build 因此从未全绿（2026-11-01 A/B 实证：同样报 NativeSqliteDriver
        // unresolved）。hub 化期间 :shared:build 作为出厂闸门，故补齐；运行行为零变化。
        matching { it.name == "iosMain" }.configureEach {
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.sqldelight.native.driver)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // 缓存层（SQLDelight 生成代码 + sqlite-driver）在 JVM 上做真库测试
        val desktopTest by getting {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
    }
}

// M2.5 数据基座（ADR #5）：单库四表。缓存是加速层不是真相源（chat.history / sessions.list
// 始终权威），schema 独立版本号见 CacheStore——破坏性变更 drop 重建，随时可整库重建。
sqldelight {
    databases {
        create("AgentReinsDatabase") {
            packageName.set("dev.sharpxia.agentreins.db")
        }
    }
}
