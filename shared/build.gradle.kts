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
