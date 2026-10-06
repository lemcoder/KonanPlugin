// Kotlin/Native consumer of the same C library, through KGP's own cinterop (no JNI).

import io.github.lemcoder.model.NativeTarget

plugins {
    kotlin("multiplatform") version "2.4.20"
    id("io.github.lemcoder.konanplugin")
}

repositories { mavenCentral() }

kotlin {
    macosArm64 {
        binaries.executable { entryPoint = "main" }
    }
}

konan {
    // native/*.c -> build/konan/libraries/mymath/macos_arm64/libmymath.a.
    val mymath = libraries.create("mymath") { targets(NativeTarget.MACOS_ARM64) }
    // Every Kotlin/Native target the library is built for gets KGP's cinterop for the .def, with the
    // library's archive embedded in the klib.
    bindings {
        create("mymath") {
            defFile.set(file("src/nativeInterop/cinterop/mymath.def"))
            includeDirs.from("native")
            library(mymath)
        }
    }
}
