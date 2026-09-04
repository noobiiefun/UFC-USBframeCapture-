plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ufc.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ufc.app"
        minSdk = 26        // USB Host API stabil mulai Android 8+
        targetSdk = 36
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
        freeCompilerArgs += listOf("-Xcontext-receivers")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Coroutines + StateFlow untuk StatusRepository
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Pustaka AndroidUSBCamera (AUSBC) - Fork ernestp stabil untuk Android 16+
    // Modul libausbc mencakup UVC capture, encoding, dan pusher (RTMP)
    implementation("com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0")

    // HTTP server ringan untuk endpoint /status
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Mesin Streaming RTMP Real (Standar Industri)
    implementation("com.github.pedroSG94.RootEncoder:rtmp:2.8.1")
}
