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
    implementation("org.webrtc:google-webrtc:1.0.32006")
    implementation("com.github.DecartAI:decart-android:0.2.0")
    implementation("com.github.pedroSG94:rtmp-rtsp-stream-client-java:2.5.0")
}
