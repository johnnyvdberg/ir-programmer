plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

android {
    namespace = "io.github.g20ir"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.g20ir"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.6"
    }

    // Release signing comes from the environment (GitHub Actions secrets), never from the repo:
    //   G20_KEYSTORE (path), G20_KEYSTORE_PASSWORD, G20_KEY_ALIAS, G20_KEY_PASSWORD
    // Without them the release APK falls back to the local debug key.
    val keystore = System.getenv("G20_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("G20_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("G20_KEY_ALIAS")
                keyPassword = System.getenv("G20_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Pure-Kotlin adb client: the button helper connects to the Shield's own adb daemon.
    implementation("dev.mobile:dadb:1.2.10")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
