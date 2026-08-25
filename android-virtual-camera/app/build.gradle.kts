plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.zeypher.lucycam"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.zeypher.lucycam"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    buildFeatures { aidl = true }
    kotlinOptions {
        // Match Android's default Java bytecode target (1.8) to avoid the
        // "Inconsistent JVM-target compatibility" error in :compileDebugKotlin.
        jvmTarget = "1.8"
    }
}

// androidx.core 1.15.0 (pulled transitively) requires compileSdk 35, but we're on 34.
// Pin to the last 34-compatible core so the AAR metadata check passes without bumping AGP/SDK.
configurations.all {
    resolutionStrategy {
        force("androidx.core:core:1.13.1")
        force("androidx.core:core-ktx:1.13.1")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // org.webrtc.* comes from Decart's transitive dep (io.github.webrtc-sdk:android),
    // which is the LiveKit-compatible build. Do NOT add org.webrtc:google-webrtc —
    // it duplicates org.webrtc.* classes and is incompatible with LiveKit.
    implementation("io.github.webrtc-sdk:android:125.6422.04")
    implementation("com.github.DecartAI:decart-android:0.2.0")
    implementation("com.github.pedroSG94:rtmp-rtsp-stream-client-java:2.5.0")
}
