package io.github.lemcoder.toolchain

import io.github.lemcoder.model.NativeTarget

/**
 * Zig's target triple for a Kotlin/Native target, pinned to what Kotlin/Native's own sysroots expect,
 * so an archive Zig builds links wherever konan's would: glibc 2.19 on x86-64 Linux and 2.25 on arm64,
 * macOS 12.0 and iOS 14.0
 * on Apple. Android is mapped, but needs a libc file for the NDK's bionic, which Zig does not bundle.
 */
internal fun NativeTarget.zigTriple(): String = when (this) {
    NativeTarget.MACOS_ARM64 -> "aarch64-macos.12.0"
    NativeTarget.MACOS_X64 -> "x86_64-macos.12.0"
    NativeTarget.IOS_ARM64 -> "aarch64-ios.14.0"
    NativeTarget.IOS_SIMULATOR_ARM64 -> "aarch64-ios.14.0-simulator"
    NativeTarget.IOS_X64 -> "x86_64-ios.14.0-simulator"
    NativeTarget.LINUX_X64 -> "x86_64-linux-gnu.2.19"
    NativeTarget.LINUX_ARM64 -> "aarch64-linux-gnu.2.25"
    NativeTarget.MINGW_X64 -> "x86_64-windows-gnu"
    NativeTarget.ANDROID_ARM64 -> "aarch64-linux-android"
    NativeTarget.ANDROID_X64 -> "x86_64-linux-android"
    NativeTarget.ANDROID_ARM32 -> "arm-linux-androideabi"
    NativeTarget.ANDROID_X86 -> "x86-linux-android"
    else -> error("No Zig target for $konanName")
}
