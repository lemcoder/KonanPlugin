package io.github.lemcoder

/**
 * A Kotlin/Native target the plugin can cross-compile for.
 *
 * [konanName] is the identifier `run_konan clang <target>` expects; it is also the directory name
 * used under `konanConfig.outputDir`.
 */
enum class KonanTarget(val konanName: String) {
    ANDROID_ARM32("android_arm32"),
    ANDROID_ARM64("android_arm64"),
    ANDROID_X86("android_x86"),
    ANDROID_X64("android_x64"),

    IOS_ARM64("ios_arm64"),
    IOS_SIMULATOR_ARM64("ios_simulator_arm64"),
    IOS_X64("ios_x64"),

    MACOS_ARM64("macos_arm64"),
    MACOS_X64("macos_x64"),

    LINUX_ARM64("linux_arm64"),
    LINUX_X64("linux_x64"),

    MINGW_X64("mingw_x64"),

    TVOS_ARM64("tvos_arm64"),
    TVOS_SIMULATOR_ARM64("tvos_simulator_arm64"),
    TVOS_X64("tvos_x64"),

    WATCHOS_ARM64("watchos_arm64"),
    WATCHOS_SIMULATOR_ARM64("watchos_simulator_arm64"),
    WATCHOS_X64("watchos_x64"),
    ;

    val isAndroid: Boolean get() = konanName.startsWith("android")

    /** `jniLibs/` subdirectory for this target. Android targets map to ABI names, others to [konanName]. */
    val abiDir: String
        get() = when (this) {
            ANDROID_ARM64 -> "arm64-v8a"
            ANDROID_ARM32 -> "armeabi-v7a"
            ANDROID_X64 -> "x86_64"
            ANDROID_X86 -> "x86"
            else -> konanName
        }

    /** Shared-library suffix for this target, e.g. `.so` / `.dylib` / `.dll`. */
    val sharedLibExtension: String
        get() = when {
            konanName.startsWith("mingw") -> ".dll"
            konanName.startsWith("macos") || konanName.startsWith("ios") ||
                konanName.startsWith("tvos") || konanName.startsWith("watchos") -> ".dylib"
            else -> ".so"
        }

    /**
     * Task-name segment, e.g. `Android_arm64` in `linkJvmInteropAndroid_arm64`. Public so a build can
     * depend on a single target's link task rather than the umbrella — useful for a job that needs the
     * host stub only and has no cross-compilation toolchain installed.
     */
    val taskSuffix: String get() = konanName.replaceFirstChar { it.uppercase() }

    companion object {
        // `values()` rather than `entries`: the kotlin-dsl plugin pins the language version to 1.8.
        val ANDROID: List<KonanTarget> = values().filter { it.isAndroid }

        /** Resolves a Kotlin/Native target name (e.g. `"android_arm64"`) to its enum constant. */
        fun fromKonanName(name: String): KonanTarget = values().firstOrNull { it.konanName == name }
            ?: error("Unknown Kotlin/Native target '$name'. Known: ${values().joinToString { it.konanName }}")

        /** Host target of the machine running the build. */
        fun host(): KonanTarget {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            val isArm = arch.contains("aarch64") || arch.contains("arm64")
            return when {
                os.contains("mac") || os.contains("darwin") -> if (isArm) MACOS_ARM64 else MACOS_X64
                os.contains("windows") -> MINGW_X64
                else -> if (isArm) LINUX_ARM64 else LINUX_X64
            }
        }
    }
}
