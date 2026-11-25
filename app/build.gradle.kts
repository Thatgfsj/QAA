import java.util.Properties

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
        versionCode = 3
        versionName = "0.1.6"
        // 契约：仅 arm64-v8a（docs/ARCHITECTURE.md 第 1 节）
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 发布签名（#113）：默认复用本机 ~/.android/debug.keystore，使 release 包与既有 debug 安装同证书，
    // 从而 `adb install -r` 可原地升级并保留沙盒内的模型/会话/设置（换证书会签名不匹配、只能卸载重装，数据全丢）。
    // 正式证书可通过环境变量或 local.properties 覆盖；两者都不存在时保持未签名产物。
    // 解析顺序：QAA_KEYSTORE_* 环境变量 → local.properties 的 qaa.keystore.* → ~/.android/debug.keystore。
    val localProps = Properties().apply {
        val propsFile = rootProject.file("local.properties")
        if (propsFile.exists()) propsFile.inputStream().use { load(it) }
    }
    fun signingValue(envName: String, propName: String, fallback: String): String =
        System.getenv(envName) ?: localProps.getProperty(propName) ?: fallback

    val releaseKeystore = file(
        signingValue(
            "QAA_KEYSTORE_FILE",
            "qaa.keystore.file",
            "${System.getProperty("user.home")}/.android/debug.keystore",
        ),
    )
    if (releaseKeystore.exists()) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("QAA_KEYSTORE_PASSWORD", "qaa.keystore.password", "android")
                keyAlias = signingValue("QAA_KEY_ALIAS", "qaa.key.alias", "androiddebugkey")
                keyPassword = signingValue("QAA_KEY_PASSWORD", "qaa.key.password", "android")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    // 冒烟模型只在 debug 变体里打包（#124）：资产由 scripts/fetch-smoke-model.sh 注入
    // src/smoke/assets/models/，release 不再内置任何 .gguf（APK 少 43.5 MB），
    // 运行期是否内置由 AndroidAssetSource 实况决定（见 AgentRuntime 的 builtinNames）。
    sourceSets {
        getByName("debug") {
            assets.srcDir("src/smoke/assets")
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
    // harness 端口契约：确认门接口与 AgentLoop 装配（实现由 :app 注入）
    implementation(project(":core-harness"))
    // 装配层端口实现：推理引擎 / 模型管理（Issue #19）与四原语工具执行体。
    implementation(project(":core-inference"))
    implementation(project(":core-tools"))

    // 契约：GenieX Android SDK 0.10.0（Maven Central，附带 arm64-v8a 原生库，Issue #4）
    implementation(libs.geniex.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    // ui_mode 持久化（Issue #7）：DataStore preferences + 生命周期组件
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Terminal 三段式视图的 ViewModel 装配（Issue #10 命令接线）。
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
