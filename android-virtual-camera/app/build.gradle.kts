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
