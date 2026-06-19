// Plain Gradle project (no AGP) that demonstrates producing the Android JNI artifacts with the plugin:
//   - libexamplestubs.so per ABI  (build/jvmInterop/jniLibs/<abi>/)
//   - runtime-free Kotlin bridges  (build/generated/jvmInterop/kotlin/)
// A real Android app/library then consumes these — see README.md.

plugins {
    id("io.github.lemcoder.konanplugin")
}

val konanHome: String = System.getenv("KONAN_HOME")
    ?: file("${System.getProperty("user.home")}/.konan")
        .listFiles { f -> f.isDirectory && f.name.startsWith("kotlin-native-prebuilt-") }
        ?.maxByOrNull { it.name }?.absolutePath
    ?: error("No Kotlin/Native distribution found. Set KONAN_HOME.")

// Device + emulator ABIs. Add android_arm32 / android_x86 if you need 32-bit.
val androidTargets = listOf("android_arm64", "android_x64")

// 1) Cross-compile native/*.c -> build/native/<target>/libmymath.a for each ABI.
konanConfig {
    konanPath.set(konanHome)
    targets.set(androidTargets)
    sourceDir.set("native")
    headerDir.set("native")
    libName.set("mymath")
    outputDir.set("build/native")
    additionalCompilerArgs.set(listOf("-std=c99"))
}

// 2) Generate JNI bridges (once) + link a self-contained stub .so per ABI, linking the .a above.
jvmInterop {
    headers.set(listOf("mymath.h"))
    packageName.set("example")
    headerDir.set("native")
    targets.set(androidTargets)
    staticLibraryDir.set("build/native")
    staticLibraryName.set("mymath")
}

// Each ABI's stub .so links that ABI's static lib, so build it first.
androidTargets.forEach { t ->
    tasks.matching { it.name == "linkJvmInterop${t.replaceFirstChar { c -> c.uppercase() }}" }
        .configureEach { dependsOn("runKonanClang") }
}

// Convenience: produce everything an Android module needs.
tasks.register("assembleAndroidJni") {
    group = "interop"
    description = "Build the JNI stub .so for every ABI and the Kotlin bridges."
    dependsOn("linkJvmInterop")
}
