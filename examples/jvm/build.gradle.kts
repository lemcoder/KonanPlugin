plugins {
    kotlin("jvm") version "2.3.10"
    id("io.github.lemcoder.konanplugin")
    application
}

repositories { mavenCentral() }

// Newest installed Kotlin/Native distribution (or set KONAN_HOME).
val konanHome: String = System.getenv("KONAN_HOME")
    ?: file("${System.getProperty("user.home")}/.konan")
        .listFiles { f -> f.isDirectory && f.name.startsWith("kotlin-native-prebuilt-") }
        ?.maxByOrNull { it.name }?.absolutePath
    ?: error("No Kotlin/Native distribution found. Set KONAN_HOME.")

val host = "macos_arm64" // change for your host

// 1) Cross-compile native/*.c -> build/native/<target>/libmymath.a
konanConfig {
    konanPath.set(konanHome)
    targets.set(listOf(host))
    sourceDir.set("native")
    headerDir.set("native")
    libName.set("mymath")
    outputDir.set("build/native")
    additionalCompilerArgs.set(listOf("-std=c99"))
}

// 2) Generate JNI bridges + link the JNI stub shared library, linking the .a above.
jvmInterop {
    headers.set(listOf("mymath.h"))
    packageName.set("example")
    headerDir.set("native")
    targets.set(listOf(host))
    staticLibraryDir.set("build/native")
    staticLibraryName.set("mymath")
    // konanPath / jniHome auto-detected.
}

// Generated bridges become part of the main source set.
kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/jvmInterop/kotlin"))
}

// The JNI stub links the static lib, so it must be built first.
tasks.matching { it.name == "linkJvmInterop${host.replaceFirstChar { it.uppercase() }}" }
    .configureEach { dependsOn("runKonanClang") }

application { mainClass.set("example.MainKt") }

tasks.named<JavaExec>("run") {
    dependsOn("linkJvmInterop")
    // loadLibrary() resolves the stub from java.library.path.
    jvmArgs("-Djava.library.path=${layout.buildDirectory.dir("jvmInterop/jniLibs/$host").get().asFile.absolutePath}")
}
