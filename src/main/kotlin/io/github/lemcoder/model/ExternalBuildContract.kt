package io.github.lemcoder.model

import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

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

    /** A JDK that ships `include/jni.h`. */
    JAVA_HOME("JAVA_HOME"),
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

/** What an external build is told for one [target]: where to leave its output, and its contract. */
internal class ExternalBuildContext(
    val target: NativeTarget,
    val outputDirectory: Provider<Directory>,
    val environment: Map<ExternalBuildVariable, Provider<String>>,
) {
    operator fun get(variable: ExternalBuildVariable): Provider<String> = environment.getValue(variable)
}
