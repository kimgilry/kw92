plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.coinbot.dynamicdemo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.coinbot.dynamicdemo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-demo"
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
