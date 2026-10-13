package io.github.lemcoder.kotlin

import io.github.lemcoder.model.Architecture
import io.github.lemcoder.model.ConfigValue
import io.github.lemcoder.model.HostOs
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.TargetFamily
import io.github.lemcoder.toolchain.Xcode
import io.github.lemcoder.util.ExecCaptureResult
import io.github.lemcoder.util.execCapture
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Task
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.process.ExecOperations
import java.io.File
import java.util.Properties
import java.util.jar.JarFile

/**
 * A Kotlin/Native distribution — `kotlin-native-prebuilt-*` — and the tools in it the plugin runs: clang
 * and llvm-ar through `run_konan`, with the target's sysroot, and cinterop's compiler jar. The one
 * place the distribution's layout is known.
 */
internal class KonanDistribution(
    val home: File,
    /** Where [home] came from, for errors: `/opt/x (Gradle property konan.config.konanHome)`. */
    val description: String = home.path,
) {
    /** [konanHome] checked, and checked to be a distribution. Call it when the task runs. */
    constructor(konanHome: ConfigValue) : this(konanHome.require(), konanHome.describe()) {
        if (!runKonan.isFile) {
            throw InvalidUserDataException("konan.config.konanHome $description is not a Kotlin/Native distribution: it has no ${runKonan.relativeTo(home)}.")
        }
    }

    /** The Kotlin/Native version, as its compiler jar records it. */
    val version: String by lazy {
        JarFile(compilerJar).use { it.manifest?.mainAttributes?.getValue("Implementation-Version") }
            ?: throw InvalidUserDataException("konan.config.konanHome $description has no version in ${compilerJar.relativeTo(home)}.")
    }

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

    /**
     * [target]'s sysroot at Android API [apiLevel], or the highest below it the NDK has: the one `run_konan`
     * links against, at another level. Kotlin/Native's level, 21, predates platform libraries such as
     * `libaaudio`; its NDK stops at 29.
     */
    fun androidSysroot(target: NativeTarget, apiLevel: Int, exec: ExecOperations): File {
        val default = clangCommand(target, ClangDriver.C, exec).firstOrNull { it.startsWith("--sysroot=") }
            ?.let { File(it.removePrefix("--sysroot=")) }
            ?: error("run_konan passes no --sysroot for ${target.konanName}")
        // <ndk>/android-<level>/arch-<arch>
        val ndk = default.parentFile.parentFile
        val available = ndk.list().orEmpty()
            .mapNotNull { it.removePrefix("android-").takeIf { _ -> it.startsWith("android-") }?.toIntOrNull() }
            .filter { ndk.resolve("android-$it").resolve(default.name).isDirectory }
        val level = available.filter { it <= apiLevel }.maxOrNull()
            ?: error("Kotlin/Native's NDK has no API level up to $apiLevel for ${target.konanName}; it has ${available.sorted().joinToString()}")
        return ndk.resolve("android-$level").resolve(default.name)
    }

    /**
     * Downloads what [target] needs — its sysroot and toolchain under `~/.konan/dependencies` — when
     * missing. `run_konan` uses them but never fetches them; the compiler does when it links, so a trivial
     * program is linked in [scratch]. A target Kotlin/Native itself has not built for yet would otherwise
     * fail with "'string.h' file not found".
     */
    fun ensureDependencies(target: NativeTarget, exec: ExecOperations, scratch: File) {
        val dependencies = home.parentFile.resolve("dependencies").absolutePath
        val missing = clangCommand(target, ClangDriver.C, exec)
            .mapNotNull { argument -> argument.indexOf(dependencies).takeIf { it >= 0 }?.let { File(argument.substring(it)) } }
            .filterNot { it.exists() }
        if (missing.isEmpty()) return
        scratch.mkdirs()
        val main = scratch.resolve("main.kt").apply { writeText("fun main() {}\n") }
        val compiler = home.resolve(if (HostOs.current == HostOs.WINDOWS) "bin/kotlinc-native.bat" else "bin/kotlinc-native")
        val result = exec.execCapture {
            commandLine(compiler.absolutePath, "-target", target.konanName, "-p", "program", "-o", scratch.resolve("main").absolutePath, main.absolutePath)
        }
        check(result is ExecCaptureResult.Success) { "Downloading Kotlin/Native's dependencies for ${target.konanName} failed:\n${result.output}" }
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
        /**
         * Where the Kotlin plugin installs Kotlin/Native [version] for this host:
         * `kotlin-native-prebuilt-<host>-<version>` under `$KONAN_DATA_DIR`, else `~/.konan`. Computed, not
         * searched for — it may not exist yet.
         */
        fun defaultHome(version: String = defaultVersion): File {
            val os = when (HostOs.current) {
                HostOs.MACOS -> "macos"
                HostOs.LINUX -> "linux"
                HostOs.WINDOWS -> "windows"
            }
            val arch = if (HostOs.architecture == Architecture.ARM64) "aarch64" else "x86_64"
            val data = System.getenv("KONAN_DATA_DIR")?.let(::File) ?: File(System.getProperty("user.home"), ".konan")
            return data.resolve("kotlin-native-prebuilt-$os-$arch-$version")
        }

        private val properties by lazy {
            Properties().apply {
                KonanDistribution::class.java.getResourceAsStream("/io/github/lemcoder/index/kotlin-native.properties")
                    .use { load(checkNotNull(it)) }
            }
        }

        /**
         * The versions bindings can be generated with: the index exporter is compiled against each one's
         * compiler and runs only in that distribution. `kotlinNativeVersions` in the plugin build.
         */
        val supportedVersions: List<String> by lazy { properties.getProperty("supported").split(',') }

        /** The version used where the project's Kotlin version is unknown. `kotlinNativeVersion` in the plugin build. */
        val defaultVersion: String by lazy { properties.getProperty("version") }

        /** The distribution of [version] at [defaultHome], if installed. */
        fun installed(version: String = defaultVersion): KonanDistribution? =
            defaultHome(version).takeIf { it.isDirectory }?.let(::KonanDistribution)
    }
}

/** The clang drivers `run_konan` runs. */
internal enum class ClangDriver(val toolName: String) { C("clang"), CXX("clang++") }

/**
 * A task that runs Kotlin/Native's tools. The plugin sets [konanHome] from `konan.config` for every such
 * task; it is checked when the task runs.
 */
interface UsesKonanDistribution : Task {
    /** Not an input: where the distribution is installed is the machine's business. */
    @get:Internal val konanHome: Property<ConfigValue>
}

/** The distribution [UsesKonanDistribution.konanHome] names, checked. Call it when the task runs. */
internal fun UsesKonanDistribution.distribution(): KonanDistribution = KonanDistribution(konanHome.get())
