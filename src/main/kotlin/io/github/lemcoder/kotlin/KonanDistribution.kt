package io.github.lemcoder.kotlin

import io.github.lemcoder.model.HostOs
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.TargetFamily
import io.github.lemcoder.toolchain.Xcode
import io.github.lemcoder.util.ExecCaptureResult
import io.github.lemcoder.util.execCapture
import org.gradle.process.ExecOperations
import java.io.File

/**
 * A Kotlin/Native distribution — `kotlin-native-prebuilt-*` — and the tools in it the plugin runs: clang
 * and llvm-ar through `run_konan`, with the target's sysroot, and cinterop's compiler jar. The one
 * place the distribution's layout is known.
 */
internal class KonanDistribution(val home: File) {
    /** `run_konan`, which runs the distribution's LLVM tools the way the Kotlin/Native compiler does. */
    val runKonan: File get() = home.resolve(if (HostOs.current == HostOs.WINDOWS) "bin/run_konan.bat" else "bin/run_konan")

    /** The compiler, cinterop included, as one jar: what the index exporter runs against. */
    val compilerJar: File get() = home.resolve("konan/lib/kotlin-native-compiler-embeddable.jar")

    /** Native libraries the compiler jar loads, libclang among them. */
    val nativeLibraryDirectory: File get() = home.resolve("konan/nativelib")

    /** clang (or [driver]) for [target], with Kotlin/Native's sysroot and flags for it. */
    fun clang(target: NativeTarget, arguments: List<String>, driver: ClangDriver = ClangDriver.C): List<String> =
        listOf(runKonan.absolutePath, "clang", driver.toolName, target.konanName) + arguments

    /**
     * What clang needs, beyond what `run_konan` sets, to link a shared library or executable for
     * [target] — the one place these are known, for the plugin's own links and the toolchain it hands
     * to external builds:
     * - Linux: `lld`. The GCC toolchain's own `ld.bfd` is a Linux binary, so a cross link fails without it.
     * - Android: the NDK's compiler-rt, which Kotlin/Native's LLVM lacks, and no unwinder, which the NDK
     *   sysroot lacks.
     * - macOS: Xcode's compiler-rt, which Apple-framework code needs and Kotlin/Native's LLVM lacks.
     */
    fun linkArguments(target: NativeTarget, exec: ExecOperations): List<String> = when (target.family) {
        TargetFamily.LINUX -> listOf("-fuse-ld=lld")
        TargetFamily.ANDROID -> listOfNotNull(ndkResourceDirectory()?.let { "-resource-dir=${it.absolutePath}" }, "--unwindlib=none")
        TargetFamily.OSX -> listOfNotNull(Xcode.macosCompilerRt(exec)?.absolutePath)
        else -> emptyList()
    }

    /**
     * The clang command line `run_konan` builds for [target] — the compiler and every flag it adds — without
     * running a JVM per compile: `run_konan` echoes the command it runs, so one `--version` call yields it.
     */
    fun clangCommand(target: NativeTarget, driver: ClangDriver, exec: ExecOperations): List<String> {
        val result = exec.execCapture { commandLine(clang(target, listOf("--version"), driver)) }
        check(result is ExecCaptureResult.Success) { "run_konan could not run ${driver.toolName} for ${target.konanName}:\n${result.output}" }
        val line = result.output.lineSequence().firstOrNull { it.endsWith(" --version") && it.contains(" -target ") }
            ?: error("run_konan did not echo its ${driver.toolName} command for ${target.konanName}:\n${result.output}")
        return line.removeSuffix(" --version").split(' ').filter { it.isNotEmpty() }
    }

    /** llvm-ar, for archiving. */
    fun llvmAr(arguments: List<String>): List<String> = listOf(runKonan.absolutePath, "llvm", "llvm-ar") + arguments

    /** The Android NDK clang resource directory, which holds compiler-rt, from the distribution's dependencies. */
    fun ndkResourceDirectory(): File? {
        val dependencies = home.parentFile.resolve("dependencies")
        val ndk = dependencies.listFiles { f -> f.isDirectory && f.name.contains("android_ndk") }?.firstOrNull() ?: return null
        return ndk.resolve("lib64/clang").listFiles { f -> f.isDirectory }?.maxByOrNull { it.name }
    }

    companion object {
        private const val PREBUILT_PREFIX = "kotlin-native-prebuilt-"

        private val konanDirectory: File get() = File(System.getProperty("user.home"), ".konan")

        const val MISSING: String =
            "No Kotlin/Native distribution found. The Kotlin plugin downloads one when it first compiles " +
                "a native target, so build one of those first, or set konan.konanHome or KONAN_HOME."

        /** `$KONAN_HOME`, else the newest distribution under `~/.konan`; null when none is installed yet. */
        fun findHome(): File? {
            System.getenv("KONAN_HOME")?.let { return File(it) }
            return konanDirectory.listFiles { f -> f.isDirectory && f.name.startsWith(PREBUILT_PREFIX) }?.maxByOrNull { it.name }
        }

        /**
         * [configured], else [findHome]. Called when a task runs: a convention resolved earlier — when the
         * configuration cache stores — may predate the Kotlin plugin's download of the distribution.
         */
        fun resolve(configured: File?): KonanDistribution = KonanDistribution(configured ?: findHome() ?: error(MISSING))

        /**
         * The version the index exporter was compiled against, and so the one it must run in: it links
         * against that version's compiler jar. `kotlinNativeVersion` in the plugin build.
         */
        val pinnedVersion: String by lazy {
            java.util.Properties().apply {
                KonanDistribution::class.java.getResourceAsStream("/io/github/lemcoder/index/kotlin-native.properties")
                    .use { load(checkNotNull(it)) }
            }.getProperty("version")
        }

        /** The installed distribution of [pinnedVersion], if any. */
        fun pinned(): KonanDistribution? =
            konanDirectory.listFiles { f -> f.isDirectory && f.name.startsWith(PREBUILT_PREFIX) && f.name.endsWith("-$pinnedVersion") }
                ?.singleOrNull()
                ?.let(::KonanDistribution)
    }
}

/** The clang drivers `run_konan` runs. */
internal enum class ClangDriver(val toolName: String) { C("clang"), CXX("clang++") }
