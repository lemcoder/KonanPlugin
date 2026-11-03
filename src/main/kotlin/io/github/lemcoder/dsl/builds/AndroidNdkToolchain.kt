package io.github.lemcoder.dsl.builds

import io.github.lemcoder.android.AndroidNdk
import io.github.lemcoder.dsl.config.ConfigValue
import io.github.lemcoder.model.NativeTarget
import org.gradle.api.provider.Provider

/**
 * The Android NDK's compilers for one Android target, as an external build gets them: `CC`, `CXX`, `AR`,
 * `RANLIB`, `ANDROID_NDK_HOME`, and for CMake `android.toolchain.cmake` with `ANDROID_ABI` and
 * `ANDROID_PLATFORM`. Resolved when the build reads them, so an unset or wrong NDK fails the build.
 */
internal object AndroidNdkToolchain {
    fun files(ndk: Provider<ConfigValue>, target: NativeTarget, apiLevel: Provider<Int>): ToolchainFiles {
        val resolved = ndk.map { AndroidNdk.of(it) }
        return ToolchainFiles(
            cc = resolved.zip(apiLevel) { it, api -> it.clang(target, api) },
            cxx = resolved.zip(apiLevel) { it, api -> it.clang(target, api, cxx = true) },
            ar = resolved.map { it.llvmAr },
            ranlib = resolved.map { it.llvmRanlib },
            cmakeToolchainFile = resolved.map { it.cmakeToolchainFile },
            cmakeDefinitions = mapOf(
                "ANDROID_ABI" to apiLevel.map { checkNotNull(target.androidAbi) },
                "ANDROID_PLATFORM" to apiLevel.map { "android-$it" },
            ),
            androidNdk = resolved.map { it.home },
        )
    }
}
