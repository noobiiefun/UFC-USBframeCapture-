plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ufc.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ufc.app"
        minSdk = 26        // USB Host API stabil mulai Android 8+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-skeleton"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Coroutines + StateFlow untuk StatusRepository
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // TODO: sesuaikan versi terbaru dari repo AndroidUSBCamera / fork ernestp
    // Modul ini menyediakan UVC capture + hardware encode + RTMP push (libpush)
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.3.3")
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libpush:3.3.3")

    // HTTP server ringan untuk endpoint /status
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
