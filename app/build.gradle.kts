plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.thatgfsj.qaa"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thatgfsj.qaa"
        minSdk = 27
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // 契约：仅 arm64-v8a（docs/ARCHITECTURE.md 第 1 节）
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 与 GenieX 官方示例一致：原生库解压到磁盘供其运行时按路径加载。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 双前端模块（占位依赖，启动路由属 Issue #10）
    implementation(project(":feature-normal-ui"))
    implementation(project(":feature-terminal-ui"))

    // 契约：GenieX Android SDK 0.10.0（Maven Central，附带 arm64-v8a 原生库，Issue #4）
    implementation(libs.geniex.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
}
