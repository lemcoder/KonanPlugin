package io.github.lemcoder.jvm

import java.io.File

/** Pure helpers for the JVM/JNI interop leg: toolchain discovery, naming, and the cinterop strip transform. */
internal object JvmInteropSupport {

    // --- toolchain discovery -------------------------------------------------

    /** The Kotlin/Native distribution, or null when none is installed yet. */
    fun findKonanHome(): File? {
        System.getenv("KONAN_HOME")?.let { return File(it) }
        val konanDir = File(System.getProperty("user.home"), ".konan")
        return konanDir.listFiles { f -> f.isDirectory && f.name.startsWith("kotlin-native-prebuilt-") }
            ?.maxByOrNull { it.name }
    }

    /** As [findKonanHome], failing with the message a build needs when there is none. */
    fun detectKonanHome(): File = findKonanHome() ?: error(MISSING_KONAN)

    const val MISSING_KONAN: String =
        "No Kotlin/Native distribution found. The Kotlin plugin downloads one when it first compiles " +
            "a native target, so build one of those first, or set konanConfig.konanPath or KONAN_HOME."

    private fun osIncludeSubDir(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> "darwin"
            os.contains("windows") -> "win32"
            else -> "linux"
        }
    }

    /** Returns [include, include/<os>] for [jdkHome] if it ships jni.h, trying the macOS `Contents/Home` layout too. */
    fun jniIncludeDirsOf(jdkHome: File): List<File>? {
        val sub = osIncludeSubDir()
        for (root in listOf(jdkHome, jdkHome.resolve("Contents/Home"))) {
            if (root.resolve("include/jni.h").isFile) {
                return listOf(root.resolve("include"), root.resolve("include/$sub"))
            }
        }
        return null
    }

    /**
     * JDK homes worth checking for `include/jni.h`, most specific first. Not just the running JVM:
     * IDE-bundled JBRs — Android Studio's, which is often the Gradle daemon — strip the headers.
     */
    private fun jdkCandidates(): List<File> = buildList {
        System.getenv("JNI_HOME")?.let { add(File(it)) }
        System.getenv("JAVA_HOME")?.let { add(File(it)) }
        System.getProperty("java.home")?.let { add(File(it)) }
        File(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines").listFiles()?.let { addAll(it) }
        File("/Library/Java/JavaVirtualMachines").listFiles()?.let { addAll(it) }
        File("/usr/lib/jvm").listFiles()?.let { addAll(it) }
    }

    /** Scans the running JVM and installed JDKs for one that ships jni.h. */
    fun detectJniIncludeDirs(): List<File> = jdkCandidates().firstNotNullOfOrNull { jniIncludeDirsOf(it) }
        ?: error("No JDK with include/jni.h found. Set the interop's jniHome.")

    /** The home of the JDK [detectJniIncludeDirs] would use. */
    fun detectJniHome(): File = jdkCandidates()
        .flatMap { listOf(it, it.resolve("Contents/Home")) }
        .firstOrNull { it.resolve("include/jni.h").isFile }
        ?: error("No JDK with include/jni.h found. Set the interop's jniHome.")

    /**
     * Xcode's compiler-rt builtins for macOS. Konan's `essentials` LLVM ships none for the host, so a
     * stub that pulls in Apple-framework code (e.g. ggml's Metal backend) is otherwise short
     * `___isPlatformVersionAtLeast`. [developerDir] is `xcode-select -p`, when that succeeded.
     */
    fun appleCompilerRt(developerDir: File?): File? {
        val roots = listOfNotNull(
            developerDir?.resolve("Toolchains/XcodeDefault.xctoolchain/usr/lib/clang"),
            File("/Library/Developer/CommandLineTools/usr/lib/clang"),
        )
        return roots.filter { it.isDirectory }
            .flatMap { it.listFiles()?.sortedByDescending { v -> v.name }?.toList() ?: emptyList() }
            .map { it.resolve("lib/darwin/libclang_rt.osx.a") }
            .firstOrNull { it.isFile }
    }

    /** Android NDK clang resource dir (holds compiler-rt builtins), discovered under the konan dependencies. */
    fun ndkResourceDir(konanHome: File): File? {
        val deps = File(konanHome.parentFile, "dependencies")
        val ndk = deps.listFiles { f -> f.isDirectory && f.name.contains("android_ndk") }?.firstOrNull() ?: return null
        return File(ndk, "lib64/clang").listFiles { f -> f.isDirectory }?.maxByOrNull { it.name }
    }

    // --- naming --------------------------------------------------------------

    /** cinterop's stub library base name: package parts joined, plus "stubs" (e.g. `io.example.m` -> `ioexamplemstubs`). */
    fun stubBaseName(packageName: String): String = packageName.split('.').joinToString("") + "stubs"
}