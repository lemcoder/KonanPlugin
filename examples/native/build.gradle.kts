// Kotlin/Native consumer of the SAME C library via standard cinterop (no JNI).
// The plugin's role here is only the cross-compiler: it produces libmymath.a;
// the K/N `cinterops {}` block does the binding, exactly as it does today.

import io.github.lemcoder.KonanTarget

plugins {
    kotlin("multiplatform") version "2.3.10"
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
        compilations.getByName("main").cinterops.create("mymath") {
            defFile("src/nativeInterop/cinterop/mymath.def")
            includeDirs("native")
            // Where the static library referenced by the .def lives.
            extraOpts("-libraryPath", layout.buildDirectory.dir("native/${host.konanName}").get().asFile.absolutePath)
        }
        binaries.executable { entryPoint = "main" }
    }
}

// cinterop links the static lib, so build it first.
tasks.matching { it.name.startsWith("cinteropMymath") }.configureEach {
    dependsOn("runKonanClang")
}
