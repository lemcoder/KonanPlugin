package io.github.lemcoder.model

import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import java.io.File

/** A variable of an external build's contract: what the plugin tells the build, by environment. */
internal sealed interface ExternalBuildVariable {
    /** The environment variable's name — public contract, read by build scripts. */
    val variableName: String
}

/** The contract of a build that makes a library's archive (`libraries { zig { } }`, `external { }`). */
internal enum class ExternalLibraryVariable(override val variableName: String) : ExternalBuildVariable {
    /** The library's name: its archive must become `lib<name>.a`. */
    LIB_NAME("KONAN_LIBRARY_NAME"),

    /** Where this target's archive must land, unless the build names a produced library. */
    OUTPUT_DIR("KONAN_LIBRARY_OUTPUT_DIR"),

    /** The library's `sourceDirectory`. */
    SOURCE_DIR("KONAN_LIBRARY_SOURCE_DIR"),

    /** The Kotlin/Native target, e.g. `linux_x64`. */
    TARGET("KONAN_TARGET"),
}

/**
 * The contract of a build that makes a binding's JNI library (`jni { cmake { } }`, `external { }`), read
 * the way an AGP project reads `ANDROID_ABI`.
 */
internal enum class ExternalStubVariable(override val variableName: String) : ExternalBuildVariable {
    /** The directory holding the generated `.c` stub. */
    STUB_DIR("KONAN_JNI_STUB_DIR"),

    /** The library name the generated bindings pass to `System.loadLibrary`. */
    LIB_NAME("KONAN_JNI_LIB_NAME"),

    /** Where the target's library must land, unless the build names a produced library. */
    OUTPUT_DIR("KONAN_JNI_OUTPUT_DIR"),

    /** The Kotlin/Native target, e.g. `android_arm64`. */
    TARGET("KONAN_TARGET"),

    /** The target's Android ABI, e.g. `arm64-v8a`; the target's name elsewhere. */
    ABI("KONAN_JNI_ABI"),

    /** The directory holding `jni.h`, which the plugin ships: no JDK needed. */
    JNI_INCLUDE_DIR("KONAN_JNI_INCLUDE_DIR"),
}

/** The contract of a build that makes the shared library FFM bindings load (`ffm { external { } }`). */
internal enum class ExternalFfmVariable(override val variableName: String) : ExternalBuildVariable {
    /** The library name the bindings pass to `System.loadLibrary`. */
    LIB_NAME("KONAN_FFM_LIB_NAME"),

    /** Where the target's library must land, unless the build names a produced library. */
    OUTPUT_DIR("KONAN_FFM_OUTPUT_DIR"),

    /** The Kotlin/Native target, e.g. `android_arm64`. */
    TARGET("KONAN_TARGET"),

    /** The target's Android ABI, e.g. `arm64-v8a`; the target's name elsewhere. */
    ABI("KONAN_FFM_ABI"),
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
) {
    /** The [ToolchainVariable]s, under the standard names. */
    val environment: Map<ExternalBuildVariable, Provider<String>> = mapOf(
        ToolchainVariable.CC to cc.map { it.absolutePath },
        ToolchainVariable.CXX to cxx.map { it.absolutePath },
        ToolchainVariable.AR to ar.map { it.absolutePath },
        ToolchainVariable.RANLIB to ranlib.map { it.absolutePath },
        ToolchainVariable.CMAKE_TOOLCHAIN_FILE to cmakeToolchainFile.map { it.absolutePath },
    )
}
