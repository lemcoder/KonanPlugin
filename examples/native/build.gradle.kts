// Kotlin/Native consumer of the SAME C library through cinterop (no JNI).
// The plugin cross-compiles native/*.c to libmymath.a, and kotlinNativeInterop declares the cinterop
// for every Kotlin/Native target: KGP then builds the interop klib with this project's Kotlin version.

import io.github.lemcoder.interop.kotlinNativeInterop
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    kotlin("multiplatform") version "2.4.20"
    id("io.github.lemcoder.konanplugin")
}

repositories { mavenCentral() }

val host = KonanTarget.MACOS_ARM64

// Build native/*.c -> build/native/macos_arm64/libmymath.a. No jvmInterop block and no Android
// target, so the JNI leg stays off — this example binds through K/N cinterop instead.
konanConfig {
    targets(host)
    libName.set("mymath")
}

kotlin {
    macosArm64 {
        binaries.executable { entryPoint = "main" }
    }
}

// The .def names staticLibraries = libmymath.a; the adapter points cinterop at konanConfig's archive
// for each target and runs it after runKonanClang.
kotlinNativeInterop("mymath") {
    defFile.set(file("src/nativeInterop/cinterop/mymath.def"))
    includeDirs.from("native")
}
