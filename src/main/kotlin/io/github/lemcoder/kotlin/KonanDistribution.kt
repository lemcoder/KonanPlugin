package io.github.lemcoder.kotlin

import io.github.lemcoder.model.HostOs
import io.github.lemcoder.model.NativeTarget
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

    /** clang for [target], with Kotlin/Native's sysroot and flags for it. */
    fun clang(target: NativeTarget, arguments: List<String>): List<String> =
        listOf(runKonan.absolutePath, "clang", "clang", target.konanName) + arguments

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
