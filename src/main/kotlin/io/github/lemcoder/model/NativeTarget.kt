package io.github.lemcoder.model

/**
 * A platform the plugin builds native code for: the plugin's own target type, so its API does not depend
 * on the Kotlin compiler's. Each target carries its Kotlin/Native name ([konanName]) — the spelling
 * Kotlin/Native's tools, directory layouts and task names use — and every other ecosystem's name for
 * it is derived here or next to that ecosystem's code.
 *
 * Mirrors Kotlin/Native's predefined targets one for one; a test fails when a Kotlin version adds one
 * this list lacks. From a KGP target, use `NativeTarget.of(kotlinNativeTarget.konanTarget)`.
 */
enum class NativeTarget(
    val konanName: String,
    val family: TargetFamily,
    val architecture: Architecture,
    /** The Android ABI, as `jniLibs/` and the NDK name it. */
    val androidAbi: String? = null,
) {
    ANDROID_ARM32("android_arm32", TargetFamily.ANDROID, Architecture.ARM32, "armeabi-v7a"),
    ANDROID_ARM64("android_arm64", TargetFamily.ANDROID, Architecture.ARM64, "arm64-v8a"),
    ANDROID_X64("android_x64", TargetFamily.ANDROID, Architecture.X64, "x86_64"),
    ANDROID_X86("android_x86", TargetFamily.ANDROID, Architecture.X86, "x86"),
    IOS_ARM64("ios_arm64", TargetFamily.IOS, Architecture.ARM64),
    IOS_SIMULATOR_ARM64("ios_simulator_arm64", TargetFamily.IOS, Architecture.ARM64),
    IOS_X64("ios_x64", TargetFamily.IOS, Architecture.X64),
    LINUX_ARM32_HFP("linux_arm32_hfp", TargetFamily.LINUX, Architecture.ARM32),
    LINUX_ARM64("linux_arm64", TargetFamily.LINUX, Architecture.ARM64),
    LINUX_X64("linux_x64", TargetFamily.LINUX, Architecture.X64),
    MACOS_ARM64("macos_arm64", TargetFamily.OSX, Architecture.ARM64),
    MACOS_X64("macos_x64", TargetFamily.OSX, Architecture.X64),
    MINGW_X64("mingw_x64", TargetFamily.MINGW, Architecture.X64),
    TVOS_ARM64("tvos_arm64", TargetFamily.TVOS, Architecture.ARM64),
    TVOS_SIMULATOR_ARM64("tvos_simulator_arm64", TargetFamily.TVOS, Architecture.ARM64),
    TVOS_X64("tvos_x64", TargetFamily.TVOS, Architecture.X64),
    WATCHOS_ARM32("watchos_arm32", TargetFamily.WATCHOS, Architecture.ARM32),
    WATCHOS_ARM64("watchos_arm64", TargetFamily.WATCHOS, Architecture.ARM64),
    WATCHOS_DEVICE_ARM64("watchos_device_arm64", TargetFamily.WATCHOS, Architecture.ARM64),
    WATCHOS_SIMULATOR_ARM64("watchos_simulator_arm64", TargetFamily.WATCHOS, Architecture.ARM64),
    WATCHOS_X64("watchos_x64", TargetFamily.WATCHOS, Architecture.X64),
    ;

    val isAndroid: Boolean get() = family == TargetFamily.ANDROID

    /** Task-name segment, e.g. `Android_arm64` in `linkJniMymathAndroid_arm64`. */
    internal val taskSuffix: String get() = konanName.capitalized()

    /** Directory name for this target's JNI library: its Android ABI under `jniLibs/`, else [konanName]. */
    internal val libraryDirectoryName: String get() = androidAbi ?: konanName

    /** Shared-library file name for [baseName], e.g. `libfoo.dylib`, `foo.dll`. */
    internal fun sharedLibraryName(baseName: String): String = "${family.sharedLibraryPrefix}$baseName.${family.sharedLibrarySuffix}"

    companion object {
        /** The build host, as a target. */
        val host: NativeTarget by lazy {
            when (HostOs.current) {
                HostOs.MACOS -> if (HostOs.architecture == Architecture.ARM64) MACOS_ARM64 else MACOS_X64
                HostOs.LINUX -> if (HostOs.architecture == Architecture.ARM64) LINUX_ARM64 else LINUX_X64
                HostOs.WINDOWS -> MINGW_X64
            }
        }

        /** Every Android ABI. */
        val android: List<NativeTarget> get() = entries.filter { it.isAndroid }

        /** The target Kotlin/Native calls [konanName], if the plugin knows it. */
        fun byKonanName(konanName: String): NativeTarget? = entries.firstOrNull { it.konanName == konanName }
    }
}

/** The operating-system family of a [NativeTarget], which decides its binary conventions. */
enum class TargetFamily(
    internal val sharedLibraryPrefix: String,
    internal val sharedLibrarySuffix: String,
    val isApple: Boolean,
) {
    ANDROID("lib", "so", isApple = false),
    LINUX("lib", "so", isApple = false),
    MINGW("", "dll", isApple = false),
    OSX("lib", "dylib", isApple = true),
    IOS("lib", "dylib", isApple = true),
    TVOS("lib", "dylib", isApple = true),
    WATCHOS("lib", "dylib", isApple = true),
}

enum class Architecture { ARM32, ARM64, X64, X86 }
