import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// KMP UI 库：全部界面代码在 commonMain，由 androidApp / desktopApp / iosApp 壳工程消费
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
    // SwiftUI 壳经 AgentBridge 消费 shared 类型（Session/枚举等），shared 必须 export。
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
            implementation(libs.compose.material.icons.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // 日分隔需要时区日期运算（kotlin.time.Clock 的 Instant → LocalDate）
            implementation(libs.kotlinx.datetime)
            // M3.1：4-tab IA + push 转场（CMP 官方移植）
            implementation(libs.navigation.compose)
            // M3.1：Sheet 基座玻璃顶条（M3.6 底栏毛玻璃同源）
            implementation(libs.haze)
            implementation(libs.haze.materials)
            // M3.3：Markdown v2（表格/嵌套/代码高亮；iOS lazy-list 兼容性落地首验）
            implementation(libs.markdown.renderer)
            implementation(libs.markdown.renderer.m3)
            implementation(libs.markdown.renderer.code)
        }
        // desktop 目标的测试源集（ChatRows 日分隔纯函数测试）
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
