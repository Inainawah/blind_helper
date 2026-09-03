import java.io.FileInputStream
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// local.properties 已被根目錄 .gitignore 排除，不會進 git，
// 家屬模式「查看詳情」地圖用的 Maps SDK for Android 金鑰放在這裡：
// local.properties 加一行 GOOGLE_MAPS_SDK_FOR_ANDROID_KEY=你的金鑰
val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        load(FileInputStream(localFile))
    }
}

android {
    namespace = "com.example.blindguideapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.blindguideapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        manifestPlaceholders["MAPS_API_KEY"] =
            localProperties.getProperty("GOOGLE_MAPS_SDK_FOR_ANDROID_KEY", "")
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
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Android 核心依賴項
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // 架構元件
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose 依賴項
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // 開發工具
  debugImplementation(libs.androidx.compose.ui.tooling)
  // 儀器化測試
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // 本地測試：jUnit、協程、Android 測試執行器
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // 儀器化測試：jUnit 規則與執行器
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // 導航元件
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // CameraX 相機元件
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)

  // TensorFlow Lite 機器學習
  implementation(libs.tensorflow.lite)
  implementation(libs.tensorflow.lite.support)

  // 補上手機端 GPU 加速與神經網路 API 支援
  implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
  implementation("org.tensorflow:tensorflow-lite:2.14.0")
  implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

  // 網路連線與 JSON 序列化
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

  // FusedLocationProviderClient，供三階段轉彎提示模組（navigation 套件）使用
  implementation(libs.play.services.location)

  // 家屬模式「查看詳情」路線地圖
  implementation(libs.play.services.maps)
  implementation(libs.maps.compose)
}
