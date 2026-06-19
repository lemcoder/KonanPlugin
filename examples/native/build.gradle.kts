// Kotlin/Native consumer of the SAME C library via standard cinterop (no JNI).
// The plugin's role here is only the cross-compiler: it produces libmymath.a;
// the K/N `cinterops {}` block does the binding, exactly as it does today.

plugins {
    kotlin("multiplatform") version "2.3.10"
    id("io.github.lemcoder.konanplugin")
}

repositories { mavenCentral() }

val konanHome: String = System.getenv("KONAN_HOME")
    ?: file("${System.getProperty("user.home")}/.konan")
        .listFiles { f -> f.isDirectory && f.name.startsWith("kotlin-native-prebuilt-") }
        ?.maxByOrNull { it.name }?.absolutePath
    ?: error("No Kotlin/Native distribution found. Set KONAN_HOME.")

val host = "macos_arm64"

// Build native/*.c -> build/native/macos_arm64/libmymath.a
konanConfig {
    konanPath.set(konanHome)
    targets.set(listOf(host))
    sourceDir.set("native")
    headerDir.set("native")
    libName.set("mymath")
    outputDir.set("build/native")
    additionalCompilerArgs.set(listOf("-std=c99"))
}

kotlin {
    macosArm64 {
        compilations.getByName("main").cinterops.create("mymath") {
            defFile("src/nativeInterop/cinterop/mymath.def")
            includeDirs("native")
            // Where the static library referenced by the .def lives.
            extraOpts("-libraryPath", layout.buildDirectory.dir("native/$host").get().asFile.absolutePath)
        }
        binaries.executable { entryPoint = "main" }
    }
}

// cinterop links the static lib, so build it first.
tasks.matching { it.name.startsWith("cinteropMymath") }.configureEach {
    dependsOn("runKonanClang")
}
