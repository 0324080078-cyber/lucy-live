plugins {
    id("com.android.application") version "8.5.2"
    kotlin("android") version "1.9.24"
}

android {
    namespace = "com.zeypher.fakecam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zeypher.fakecam"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    compileOnly(project(":xposedapi"))
}
