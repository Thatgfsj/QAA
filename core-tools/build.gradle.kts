plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.thatgfsj.qaa.core.tools"
    compileSdk = 35

    defaultConfig {
        minSdk = 27
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 模块依赖图 TOOL→INF（docs/ARCHITECTURE.md 第 2 节）。
    // 本模块禁止直接持有 GenieX 对象（职责表）。
    implementation(project(":core-inference"))
    implementation(libs.kotlinx.coroutines.core)
}
