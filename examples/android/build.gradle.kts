// One Android app over three native libraries, each built its own way and bound its own way:
//
//   cmath     C,   compiled by Kotlin/Native's clang  -> JNI bindings + libcmathstubs.so
//   rustmath  Rust, built by cargo (staticlib)       -> JNI bindings + librustmathstubs.so
//   cppmath   C++, compiled by Kotlin/Native's clang -> FFM bindings (jextract, on PanamaPort) + libcppmath.so
//
// Needs konan.config.cargo and konan.config.jextract, e.g. in ~/.gradle/gradle.properties, and the Rust
// target: rustup target add aarch64-linux-android.

import io.github.lemcoder.model.NativeTarget

plugins {
    id("com.android.application") // AGP 9 has built-in Kotlin support
    id("io.github.lemcoder.konanplugin")
}

konan {
    // Where the Kotlin plugin installs the Kotlin/Native version this plugin needs; or set
    // konan.config.konanHome in ~/.gradle/gradle.properties.
    config { konanHome.set(kotlinNativeDistribution()) }

    // 64-bit only: FFM's jextract writes LP64 layouts. arm64 is phones and Apple-silicon emulators.
    val abi = NativeTarget.ANDROID_ARM64

    libraries {
        create("cmath") {
            targets(abi)
            sourceDirectory.set(file("native/c"))
        }
        create("rustmath") {
            targets(abi)
            sourceDirectory.set(file("native/rust"))
            cargo { } // native/rust/Cargo.toml, crate-type = ["staticlib"]
        }
        create("cppmath") {
            targets(abi)
            sourceDirectory.set(file("native/cpp"))
        }
    }

    bindings {
        create("cmath") {
            includeDirs.from("native/c")
            library(libraries.getByName("cmath"))
        }
        create("rustmath") {
            includeDirs.from("native/rust")
            library(libraries.getByName("rustmath"))
        }
        create("cppmath") {
            includeDirs.from("native/cpp")
            library(libraries.getByName("cppmath"))
            ffm() // PanamaPort bindings in place of JNI, for this binding only
        }
    }
}

android {
    namespace = "com.example.nativeapp"
    compileSdk = 37          // PanamaPort's AAR metadata requires it

    defaultConfig {
        applicationId = "com.example.nativeapp"
        minSdk = 26          // PanamaPort's floor
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        // jextract's output uses pattern matching in switch.
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// jextract's Java needs a JDK 21 compiler, whatever JDK runs Gradle.
kotlin { jvmToolchain(21) }
