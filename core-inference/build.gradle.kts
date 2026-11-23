plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.thatgfsj.qaa.core.inference"
    compileSdk = 35

    defaultConfig {
        minSdk = 27
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // JVM 单测走 JUnit5（@TempDir 临时目录，Issue #6）。
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 模块依赖图 INF→GENIEX（docs/ARCHITECTURE.md 第 2 节）。
    // 本模块禁止 Compose / Android UI（职责表）。
    implementation(libs.geniex.android)
    implementation(libs.kotlinx.coroutines.core)

    // JVM 单测（Issue #6）：JUnit5 @TempDir + 协程 runTest。
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}
