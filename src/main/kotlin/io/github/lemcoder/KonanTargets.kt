package io.github.lemcoder

import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget

/**
 * Helpers over Kotlin/Native's own [KonanTarget], which this plugin uses as its target type so a
 * build can hand over `kotlinNativeTarget.konanTarget` directly and new targets arrive with the
 * Kotlin version rather than with a plugin release.
 */

/** Resolves a Kotlin/Native target name, e.g. `"android_arm64"`. */
fun konanTargetOf(name: String): KonanTarget = KonanTarget.predefinedTargets[name]
    ?: error(
        "Unknown Kotlin/Native target '$name'. Known: " +
            KonanTarget.predefinedTargets.keys.sorted().joinToString()
    )

/** The build host, as a target. */
fun hostKonanTarget(): KonanTarget = HostManager.host

/** Every Android ABI. */
fun androidKonanTargets(): List<KonanTarget> =
    KonanTarget.predefinedTargets.values.filter { it.family == Family.ANDROID }

internal val KonanTarget.isAndroid: Boolean get() = family == Family.ANDROID

/** Task-name segment, e.g. `Android_arm64` in `linkJvmInteropMymathAndroid_arm64`. */
val KonanTarget.taskSuffix: String get() = name.replaceFirstChar { it.uppercase() }

/** `jniLibs/` subdirectory: Android ABI names, otherwise the target name. */
val KonanTarget.abiDir: String
    get() = when (name) {
        "android_arm64" -> "arm64-v8a"
        "android_arm32" -> "armeabi-v7a"
        "android_x64" -> "x86_64"
        "android_x86" -> "x86"
        else -> name
    }

/** Shared-library file name for [baseName], e.g. `libfoo.dylib` / `foo.dll`. */
internal fun KonanTarget.sharedLibraryName(baseName: String): String =
    // Family carries the prefix and suffix, but the suffix has no leading dot.
    "${family.dynamicPrefix}$baseName.${family.dynamicSuffix}"
