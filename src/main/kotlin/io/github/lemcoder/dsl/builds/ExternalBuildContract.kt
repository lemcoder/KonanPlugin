package io.github.lemcoder.dsl.builds

import io.github.lemcoder.model.NativeTarget
import java.io.File
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

/** A variable of an external build's contract: what the plugin tells the build, by environment. */
internal sealed interface ExternalBuildVariable {
    /** The environment variable's name — public contract, read by build scripts. */
    val variableName: String
}

/**
 * The contract every external build gets, whatever it makes — a library's archive (`libraries { cmake { } }`),
 * a binding's JNI library (`jni { cmake { } }`) or the library FFM loads (`ffm { cmake { } }`): environment
 * variables, and CMake cache entries. Read the way an AGP project reads `ANDROID_ABI`.
 */
internal enum class ExternalVariable(override val variableName: String) : ExternalBuildVariable {
    /**
     * The library's name: an archive must become `lib<name>.a`, a shared library what the bindings pass
     * to `System.loadLibrary`.
     */
    LIB_NAME("KONAN_LIB_NAME"),

    /** Where this target's library must land, unless the build names a produced library. */
    OUTPUT_DIR("KONAN_OUTPUT_DIR"),

    /** The Kotlin/Native target, e.g. `android_arm64`. */
    TARGET("KONAN_TARGET"),

    /** The target's Android ABI, e.g. `arm64-v8a`; the target's name elsewhere. */
    ABI("KONAN_ABI"),

    /** A library's `sourceDirectory`; libraries only. */
    SOURCE_DIR("KONAN_SOURCE_DIR"),

    /** The directory holding the generated `.c` stub; JNI only. */
    JNI_STUB_DIR("KONAN_JNI_STUB_DIR"),

    /** The directory holding `jni.h`, which the plugin ships, so no JDK is needed; JNI only. */
    JNI_INCLUDE_DIR("KONAN_JNI_INCLUDE_DIR"),
}

/**
 * Kotlin/Native's toolchain for the target, when the build uses it ([ExternalToolchain.KONAN]): the
 * standard names Make, autotools and most build scripts read.
 */
internal enum class ToolchainVariable(override val variableName: String) : ExternalBuildVariable {
    /** A C compiler driver: Kotlin/Native's clang with the target's flags and sysroot. */
    CC("CC"),

    /** The C++ driver, likewise. */
    CXX("CXX"),

    /** llvm-ar. */
    AR("AR"),

    /** llvm-ar's `s`, as `ranlib`: a host ranlib — Apple's, say — drops objects it cannot read. */
    RANLIB("RANLIB"),

    /** A CMake toolchain file naming all three, with the target's system and processor. */
    CMAKE_TOOLCHAIN_FILE("KONAN_CMAKE_TOOLCHAIN_FILE"),

    /** The Android NDK the compilers come from, when it is `konan.config.androidNdk`: what presets read. */
    ANDROID_NDK_HOME("ANDROID_NDK_HOME"),
}

/** What an external build is told for one [target]: where to leave its output, and its contract. */
internal class ExternalBuildContext(
    val target: NativeTarget,
    val outputDirectory: Provider<Directory>,
    val environment: Map<ExternalBuildVariable, Provider<String>>,
    /** Kotlin/Native's compilers for [target], when the build uses them. */
    val toolchain: ToolchainFiles? = null,
) {
    operator fun get(variable: ExternalBuildVariable): Provider<String> = environment.getValue(variable)
}

/** Kotlin/Native's compilers for one target, as files a build can be pointed at. */
internal class ToolchainFiles(
    val cc: Provider<File>,
    val cxx: Provider<File>,
    val ar: Provider<File>,
    val ranlib: Provider<File>,
    val cmakeToolchainFile: Provider<File>,
    /** Cache entries the toolchain file needs, e.g. the NDK's `ANDROID_ABI`; passed with it. */
    val cmakeDefinitions: Map<String, Provider<String>> = emptyMap(),
    /** The Android NDK, when the compilers are its. */
    val androidNdk: Provider<File>? = null,
) {
    /** The [ToolchainVariable]s, under the standard names. */
    val environment: Map<ExternalBuildVariable, Provider<String>> = mapOf(
        ToolchainVariable.CC to cc.map { it.absolutePath },
        ToolchainVariable.CXX to cxx.map { it.absolutePath },
        ToolchainVariable.AR to ar.map { it.absolutePath },
        ToolchainVariable.RANLIB to ranlib.map { it.absolutePath },
        ToolchainVariable.CMAKE_TOOLCHAIN_FILE to cmakeToolchainFile.map { it.absolutePath },
    ) + listOfNotNull(androidNdk?.let { ndk -> ToolchainVariable.ANDROID_NDK_HOME to ndk.map { it.absolutePath } })
}
