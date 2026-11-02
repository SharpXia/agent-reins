import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Hub 化 P4 后的 composeApp：iOS 侧只剩 AgentBridge（SwiftUI 壳 ↔ shared/hub 的桥，iosMain）；
// commonMain 只剩 Android/Desktop 的占位根 App()。网关直连时代的 Compose UI 层已整体退役，
// Android/Desktop 的 Hub 界面待基于 shared/hub 另起实现。
kotlin {
    android {
        namespace = "dev.sharpxia.agentreins.ui"
        compileSdk = 37
        minSdk = 26
        compilerOptions.jvmTarget = JvmTarget.JVM_17
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // Kotlin 2.4 起 apple framework 不再默认创建，需显式声明（静态库，免动态签名问题）
    // SwiftUI 壳经 AgentBridge 消费 shared 类型（UiConversation/UiMessage/DeviceInfo），shared 必须 export。
    iosArm64 {
        binaries.framework {
            baseName = "composeApp"
            isStatic = true
            export(project(":shared"))
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "composeApp"
            isStatic = true
            export(project(":shared"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            // export() 要求 api 依赖（SwiftUI 壳的桥接需要 shared 类型可见）
            api(project(":shared"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
