// A Kotlin Multiplatform library for iOS, macOS, Linux and Android over one C library. The konan plugin
// builds native/*.c for every target below and binds src/nativeInterop/cinterop/mymath.def to each side:
// KGP's cinterop for the Kotlin/Native targets, generated JNI bindings plus a stub .so per ABI for Android.

import io.github.lemcoder.model.NativeTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("io.github.lemcoder.konanplugin")
}

kotlin {
    android {
        namespace = "com.example.shared"
        compileSdk = 36
        minSdk = 24 // also the API level the plugin's Android links use
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "Shared" }
    }
    // Desktop: a demo executable each. linuxX64 cross-compiles from macOS; Kotlin/Native brings the sysroot.
    listOf(macosArm64(), linuxX64()).forEach {
        it.binaries.executable { entryPoint = "com.example.shared.main" }
    }

    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

konan {
    // The Kotlin/Native version this project's Kotlin plugin installs; or set konan.config.konanHome in
    // ~/.gradle/gradle.properties.
    config { konanHome.set(kotlinNativeDistribution()) }

    // native/*.c -> libmymath.a for the iPhone, the simulator, macOS, Linux and the Android ABIs.
    val mymath = libraries.create("mymath") {
        targets(
            NativeTarget.IOS_ARM64, NativeTarget.IOS_SIMULATOR_ARM64,
            NativeTarget.MACOS_ARM64, NativeTarget.LINUX_X64,
            NativeTarget.ANDROID_ARM64, NativeTarget.ANDROID_X64,
        )
    }

    // One binding, consumed by both: Kotlin/Native targets get a cinterop klib with libmymath.a embedded
    // (so it ends up in Shared.framework and the executables); the Android target gets JNI bindings in androidMain and
    // libmymathstubs.so per ABI in the AAR's jniLibs.
    bindings {
        create("mymath") {
            includeDirs.from("native")
            library(mymath)
        }
    }
}
