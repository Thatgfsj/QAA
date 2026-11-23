plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 硬约束（Issue #3）：纯 Kotlin 库，严禁 Android 框架依赖，保证可 JVM 单测。
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
