import io.github.lemcoder.GenerateJvmInteropTask

plugins {
    kotlin("jvm") version "2.3.10"
    id("io.github.lemcoder.konanplugin")
    application
}

repositories {
    mavenCentral()
}

// ---------------------------------------------------------------------------
// Locate a Kotlin/Native distribution. The JVM/JNI cinterop generator and the
// `kotlinx.cinterop` runtime both live in its compiler-embeddable jar.
// ---------------------------------------------------------------------------
val konanHome: File = run {
    System.getenv("KONAN_HOME")?.let { return@run file(it) }
    val konanDir = file("${System.getProperty("user.home")}/.konan")
    konanDir.listFiles { f -> f.isDirectory && f.name.startsWith("kotlin-native-prebuilt-") }
        ?.maxByOrNull { it.name }
        ?: error("No Kotlin/Native distribution found under $konanDir. Set KONAN_HOME.")
}
val embeddableJar = konanHome.resolve("konan/lib/kotlin-native-compiler-embeddable.jar")

// Host Kotlin/Native target. Adjust if you run on another OS/arch.
val hostTarget = "macos_arm64"

// The native stub library name is derived from the .def package: "sample.mymath" -> "samplemymathstubs".
val stubLibBaseName = "samplemymathstubs"

konanConfig {
    konanPath.set(konanHome.absolutePath)
    targets.set(listOf(hostTarget))
    headerDir.set("src/main/c")
    defFile.set("mymath.def")
    additionalCompilerArgs.set(listOf("-std=c99"))
}

// Make the generated Kotlin bindings part of the main source set.
val generatedKotlinDir = layout.buildDirectory.dir("generated/jvmInterop/kotlin")
kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir(generatedKotlinDir)
    compilerOptions {
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
    }
}

dependencies {
    // kotlinx.cinterop + loadKonanLibrary live in the embeddable jar.
    compileOnly(files(embeddableJar))
    runtimeOnly(files(embeddableJar))
}

// ---------------------------------------------------------------------------
// JVM cinterop needs jni.h / jni_md.h. The JVM that runs Gradle is often a JBR
// without an `include` dir, so locate a JDK that actually ships JNI headers.
// Override with -Pjni.home=/path/to/jdk if auto-detection fails.
// ---------------------------------------------------------------------------
val jniIncludeDirs: List<File> = run {
    val sub = when {
        org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "darwin"
        org.gradle.internal.os.OperatingSystem.current().isWindows -> "win32"
        else -> "linux"
    }
    fun includeOf(home: File): List<File>? {
        for (root in listOf(home, home.resolve("Contents/Home"))) {
            if (root.resolve("include/jni.h").isFile) {
                return listOf(root.resolve("include"), root.resolve("include/$sub"))
            }
        }
        return null
    }

    val candidates = buildList {
        (findProperty("jni.home") as String?)?.let { add(file(it)) }
        System.getenv("JNI_HOME")?.let { add(file(it)) }
        System.getProperty("java.home")?.let { add(file(it)) }
        val macVms = file("${System.getProperty("user.home")}/Library/Java/JavaVirtualMachines")
        macVms.listFiles()?.let { addAll(it) }
        file("/Library/Java/JavaVirtualMachines").listFiles()?.let { addAll(it) }
    }
    candidates.firstNotNullOfOrNull { includeOf(it) }
        ?: error("Could not find a JDK containing include/jni.h. Pass -Pjni.home=/path/to/jdk")
}

tasks.named<GenerateJvmInteropTask>("generateJvmInterop") {
    jdkIncludeDirs.setFrom(jniIncludeDirs)
}

tasks.named("compileKotlin") {
    dependsOn("generateJvmInterop")
}

// ---------------------------------------------------------------------------
// Compile + link the generated JNI stubs together with the C implementation
// into a shared library lib<stub>.{dylib,so,dll}.
// Uses the host clang; for cross-compilation route this through run_konan instead.
// ---------------------------------------------------------------------------
val nativeLibsDir = layout.buildDirectory.dir("nativeLibs")

val linkStubs by tasks.registering(Exec::class) {
    dependsOn("generateJvmInterop")
    val cDir = layout.buildDirectory.dir("generated/jvmInterop/c")
    inputs.dir(cDir)
    inputs.dir("src/main/c")
    val outFile = nativeLibsDir.map { it.file(System.mapLibraryName(stubLibBaseName)) }
    outputs.file(outFile)

    doFirst { nativeLibsDir.get().asFile.mkdirs() }
    executable = "clang"
    argumentProviders.add {
        buildList {
            add("-shared"); add("-fPIC")
            add("-I"); add(file("src/main/c").absolutePath)
            jniIncludeDirs.forEach { add("-I"); add(it.absolutePath) }
            add(file("src/main/c/mymath.c").absolutePath)
            add(cDir.get().file("$stubLibBaseName.c").asFile.absolutePath)
            add("-o"); add(outFile.get().asFile.absolutePath)
        }
    }
}

application {
    mainClass.set("MainKt")
}

tasks.named<JavaExec>("run") {
    dependsOn(linkStubs)
    // loadKonanLibrary() resolves the stub library via java.library.path.
    jvmArgs("-Djava.library.path=${nativeLibsDir.get().asFile.absolutePath}")
}
