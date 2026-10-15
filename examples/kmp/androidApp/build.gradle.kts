// The Android app: AGP 9 does not combine com.android.application with Kotlin Multiplatform, so the app
// is its own module over :shared. The JNI library comes with the shared AAR's jniLibs.

plugins {
    id("com.android.application") // AGP 9 has built-in Kotlin support
}

android {
    namespace = "com.example.kmpapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.kmpapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
}
