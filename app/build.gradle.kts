plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.kotlin.kapt") // M1：Room 注解处理（kapt 稳定，KSP 已弃用）
}

android {
  namespace = "com.ink.chat"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.ink.chat"
    minSdk = 23
    targetSdk = 34
    versionCode = 10
    versionName = "0.7.1"
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  signingConfigs {
    create("repoDebug") {
      storeFile = rootProject.file("keystore/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("repoDebug")
    }
    debug {
      signingConfig = signingConfigs.getByName("repoDebug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }

  lint {
    checkReleaseBuilds = false
    abortOnError = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "/META-INF/DEPENDENCIES"
      excludes += "/META-INF/LICENSE*"
      excludes += "/META-INF/NOTICE*"
    }
  }
}

dependencies {
  // —— 骨架（M0）——
  val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
  implementation(composeBom)
  implementation("androidx.activity:activity-compose:1.8.2")
  implementation("androidx.core:core-ktx:1.15.0")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
  implementation("androidx.navigation:navigation-compose:2.7.7")

  // —— M1 启用（网络/数据/DI，版本与 novel-agent 验证组合完全一致）——
  implementation("androidx.room:room-runtime:2.6.1")
  implementation("androidx.room:room-ktx:2.6.1")
  kapt("androidx.room:room-compiler:2.6.1")
  implementation("androidx.datastore:datastore-preferences:1.1.1")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
  implementation("com.google.code.gson:gson:2.11.0")
  implementation("io.insert-koin:koin-android:3.5.6")
  implementation("io.insert-koin:koin-androidx-compose:3.5.6")

  debugImplementation("androidx.compose.ui:ui-tooling")

  // —— 单元测试（M5.6：Markdown / 公式解析纯 JVM 验证）——
  testImplementation("junit:junit:4.13.2")
}