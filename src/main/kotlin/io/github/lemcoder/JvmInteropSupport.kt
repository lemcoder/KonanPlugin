package io.github.lemcoder

import java.io.File

/** Pure helpers for the JVM/JNI interop leg: toolchain discovery, naming, and the cinterop strip transform. */
internal object JvmInteropSupport {

    // --- toolchain discovery -------------------------------------------------

    fun detectKonanHome(): File {
        System.getenv("KONAN_HOME")?.let { return File(it) }
        val konanDir = File(System.getProperty("user.home"), ".konan")
        return konanDir.listFiles { f -> f.isDirectory && f.name.startsWith("kotlin-native-prebuilt-") }
            ?.maxByOrNull { it.name }
            ?: error("No Kotlin/Native distribution under $konanDir. Set jvmInterop.konanPath or KONAN_HOME.")
    }

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

    /** Scans the running JVM and installed JDKs for one that ships jni.h. */
    fun detectJniIncludeDirs(): List<File> {
        val candidates = buildList {
            System.getenv("JNI_HOME")?.let { add(File(it)) }
            System.getProperty("java.home")?.let { add(File(it)) }
            File(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines").listFiles()?.let { addAll(it) }
            File("/Library/Java/JavaVirtualMachines").listFiles()?.let { addAll(it) }
            File("/usr/lib/jvm").listFiles()?.let { addAll(it) }
        }
        return candidates.firstNotNullOfOrNull { jniIncludeDirsOf(it) }
            ?: error("No JDK with include/jni.h found. Set jvmInterop.jniHome.")
    }

    /** Android NDK clang resource dir (holds compiler-rt builtins), discovered under the konan dependencies. */
    fun ndkResourceDir(konanHome: File): File? {
        val deps = File(konanHome.parentFile, "dependencies")
        val ndk = deps.listFiles { f -> f.isDirectory && f.name.contains("android_ndk") }?.firstOrNull() ?: return null
        return File(ndk, "lib64/clang").listFiles { f -> f.isDirectory }?.maxByOrNull { it.name }
    }

    fun hostTarget(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val isArm = arch.contains("aarch64") || arch.contains("arm64")
        return when {
            os.contains("mac") || os.contains("darwin") -> if (isArm) "macos_arm64" else "macos_x64"
            os.contains("windows") -> "mingw_x64"
            else -> if (isArm) "linux_arm64" else "linux_x64"
        }
    }

    // --- naming --------------------------------------------------------------

    /** cinterop's stub library base name: package parts joined, plus "stubs" (e.g. `io.example.m` -> `ioexamplemstubs`). */
    fun stubBaseName(packageName: String): String = packageName.split('.').joinToString("") + "stubs"

    fun sharedLibExt(target: String): String = when {
        target.startsWith("mingw") -> ".dll"
        target.startsWith("macos") || target.startsWith("ios") || target.startsWith("tvos") || target.startsWith("watchos") -> ".dylib"
        else -> ".so"
    }

    /** Maps a Kotlin/Native target to the Android ABI directory used in `jniLibs/`; non-Android targets map to the target name. */
    fun abiDir(target: String): String = when (target) {
        "android_arm64" -> "arm64-v8a"
        "android_arm32" -> "armeabi-v7a"
        "android_x64" -> "x86_64"
        "android_x86" -> "x86"
        else -> target
    }

    // --- strip transform -----------------------------------------------------

    private val JVM_NAME = Regex("""@file:JvmName\("([^"]+)"\)""")
    private val EXTERN = Regex("""(?m)^\s*(?:private\s+)?external\s+fun\s+(kniBridge\d+)\s*\(([^)]*)\)\s*:\s*(\S+)\s*$""")
    private val LOAD_LIB = Regex("""loadKonanLibrary\("([^"]+)"\)""")
    // Captures each friendly wrapper's name/params/return and the kniBridge it delegates to, so we can keep
    // the original C-derived signature as documentation next to the otherwise opaque bridge.
    private val WRAPPER = Regex("""(?s)fun\s+(\w+)\(([^)]*)\)\s*:\s*([^{]+?)\s*\{[^}]*?kniBridge(\d+)""")

    /**
     * Rewrites a cinterop-generated JVM `.kt` into a runtime-free form: keeps `@file:JvmName` + package +
     * the `external fun kniBridgeN` declarations (so the JNI symbol mapping is preserved), erases the
     * `NativePtr` typealias to `Long`, replaces `loadKonanLibrary` with `System.loadLibrary`, and drops
     * the `kotlinx.cinterop` wrappers/imports entirely.
     */
    fun stripCinterop(src: String): String {
        val jvmName = JVM_NAME.find(src)?.groupValues?.get(1)
        val pkg = src.lineSequence().firstOrNull { it.trimStart().startsWith("package ") }?.trim()
        val libName = LOAD_LIB.find(src)?.groupValues?.get(1)
            ?: error("Generated .kt has no loadKonanLibrary(...) call; cannot determine native library name.")

        // bridge index -> original C-derived signature (for documentation).
        val origByBridge = WRAPPER.findAll(src).associate { m ->
            val name = m.groupValues[1]
            val params = m.groupValues[2].trim()
            val ret = m.groupValues[3].trim()
            m.groupValues[4] to "$name($params): $ret"
        }

        val externs = EXTERN.findAll(src).map { m ->
            val bridge = m.groupValues[1]
            val idx = bridge.removePrefix("kniBridge")
            val params = m.groupValues[2].replace("NativePtr", "Long")
            val ret = m.groupValues[3].replace("NativePtr", "Long")
            val doc = origByBridge[idx]?.let { "/** C: $it */\n" } ?: ""
            "${doc}external fun $bridge($params): $ret"
        }.toList()

        return buildString {
            jvmName?.let { appendLine("""@file:JvmName("$it")""") }
            appendLine("""@file:Suppress("FunctionName", "unused")""")
            pkg?.let { appendLine(it) }
            appendLine()
            appendLine("// AUTO-GENERATED runtime-free JNI bridges. Do not edit; regenerated by generateJvmInterop.")
            appendLine("// Pointers are passed as raw addresses (Long). Write your idiomatic expect/actual on top.")
            appendLine()
            appendLine("""private val nativeLibrary: Unit = System.loadLibrary("$libName")""")
            appendLine()
            externs.forEach { appendLine(it) }
        }
    }
}
