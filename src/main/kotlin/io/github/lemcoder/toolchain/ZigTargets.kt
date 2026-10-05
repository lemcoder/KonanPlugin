package io.github.lemcoder.toolchain

import org.jetbrains.kotlin.konan.target.KonanTarget

/**
 * Zig's target triple for a Kotlin/Native target, pinned to what Kotlin/Native's own sysroots expect,
 * so an archive Zig builds links wherever konan's would: glibc 2.19 on Linux, macOS 12.0 and iOS 14.0
 * on Apple. Android is mapped, but needs a libc file for the NDK's bionic, which Zig does not bundle.
 */
internal fun KonanTarget.zigTriple(): String = when (this) {
    KonanTarget.MACOS_ARM64 -> "aarch64-macos.12.0"
    KonanTarget.MACOS_X64 -> "x86_64-macos.12.0"
    KonanTarget.IOS_ARM64 -> "aarch64-ios.14.0"
    KonanTarget.IOS_SIMULATOR_ARM64 -> "aarch64-ios.14.0-simulator"
    KonanTarget.IOS_X64 -> "x86_64-ios.14.0-simulator"
    KonanTarget.LINUX_X64 -> "x86_64-linux-gnu.2.19"
    KonanTarget.LINUX_ARM64 -> "aarch64-linux-gnu.2.19"
    KonanTarget.MINGW_X64 -> "x86_64-windows-gnu"
    KonanTarget.ANDROID_ARM64 -> "aarch64-linux-android"
    KonanTarget.ANDROID_X64 -> "x86_64-linux-android"
    KonanTarget.ANDROID_ARM32 -> "arm-linux-androideabi"
    KonanTarget.ANDROID_X86 -> "x86-linux-android"
    else -> error("No Zig target for Kotlin/Native target $name")
}
