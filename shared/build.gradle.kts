import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
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
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }
        // iosMain 由 default hierarchy 在配置后期生成，脚本求值期无访问器（本仓库已知坑）；
        // 依赖加在叶子 sourceSet：K/N compilation 聚合 common→iosMain→叶子 的源码与依赖，效果等同
        val iosArm64Main by getting {
            dependencies { implementation(libs.ktor.client.darwin) }
        }
        val iosSimulatorArm64Main by getting {
            dependencies { implementation(libs.ktor.client.darwin) }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
