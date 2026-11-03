package io.github.lemcoder.dsl.builds

import io.github.lemcoder.model.NativeTarget

/**
 * Rust's target triple for a Kotlin/Native target — what `cargo build --target` and `rustup target add`
 * take. Targets Rust ships no standard library for are left out.
 */
internal fun NativeTarget.rustTriple(): String = when (this) {
    NativeTarget.MACOS_ARM64 -> "aarch64-apple-darwin"
    NativeTarget.MACOS_X64 -> "x86_64-apple-darwin"
    NativeTarget.IOS_ARM64 -> "aarch64-apple-ios"
    NativeTarget.IOS_SIMULATOR_ARM64 -> "aarch64-apple-ios-sim"
    NativeTarget.IOS_X64 -> "x86_64-apple-ios"
    NativeTarget.LINUX_X64 -> "x86_64-unknown-linux-gnu"
    NativeTarget.LINUX_ARM64 -> "aarch64-unknown-linux-gnu"
    NativeTarget.LINUX_ARM32_HFP -> "armv7-unknown-linux-gnueabihf"
    NativeTarget.MINGW_X64 -> "x86_64-pc-windows-gnu"
    NativeTarget.ANDROID_ARM64 -> "aarch64-linux-android"
    NativeTarget.ANDROID_X64 -> "x86_64-linux-android"
    NativeTarget.ANDROID_ARM32 -> "armv7-linux-androideabi"
    NativeTarget.ANDROID_X86 -> "i686-linux-android"
    else -> error("No Rust target with a standard library for $konanName")
}
