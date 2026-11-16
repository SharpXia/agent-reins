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
        // Mac Catalyst（实验性，KT-40442 仍 Backlog）：同一 iosSimulatorArm64 target 上加
        // 「命名框架」承载 Catalyst 消费面——保留默认框架不动，模拟器/真机链路零变化；
        // KGP 的 embedAndSignAppleFrameworkForXcode 在 Catalyst destination（IS_MACCATALYST=YES）
        // 下原生映射到本 target（XcodeEnvironment → AppleSdk.defineNativeTargets：
        // macosx+arm64 → IOS_SIMULATOR_ARM64，2026-11-17 反编译实证）。
        binaries.framework("catalyst") {
            baseName = "composeApp"          // 与默认框架同名产品，Xcode 消费面（-framework composeApp）不变
            isStatic = true                  // 与现行静态框架口径一致
            export(project(":shared"))       // SwiftUI 壳经 AgentBridge 消费 shared 类型，必须 export
            // 2026-11-17 撤下 binaryOptions["macabi"]="true"：K/N 2.4.21 对该选项只有半吊子
            // 支持——klib 依赖缓存成员被编成 MACCATALYST(minos15.0/sdk27.2)，主模块/stdlib
            // 成员仍是 IOSSIMULATOR(14.0/26.5)，同一档内平台混杂（404 vs 3959）；且 macabi
            // 口径编译出的 sqliter cinterop 引用 sqlite3_load_extension（macOS SDK
            // libsqlite3.tbd 不导出），Catalyst ld 直接拒链。撤下后产物与默认框架同为全
            // IOSSIMULATOR，由 scripts/stamp-catalyst-framework.sh 在 Xcode 侧统一原位
            // 改写为 MACCATALYST（iossim 与 macabi 同为 arm64，目标码兼容）。
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

// CMP 1.12.2 会为每个 iOS 框架二进制注册 Compose 资源同步任务（sync<名>ComposeResourcesForIos），
// 其 platform 推断链为 gradle 属性 compose.ios.resources.platform → env PLATFORM_NAME（无 Catalyst
// 概念）：Catalyst destination 下 PLATFORM_NAME=macosx 推断不出 iOS platform，会挂掉整个 embed 链
// （2026-11-17 实跑实证）。本模块无 composeResources（Hub 化后 iOS 只剩 AgentBridge），同步本就是
// 空操作，故仅禁用 catalyst 侧任务；默认框架的 sync 链路（现行 iOS 构建）原样保留。
tasks.matching { it.name == "syncCatalystComposeResourcesForIos" }.configureEach {
    enabled = false
}
