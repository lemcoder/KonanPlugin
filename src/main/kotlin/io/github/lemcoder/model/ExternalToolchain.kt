package io.github.lemcoder.model

/** Which compilers an external build uses for a target. */
enum class ExternalToolchain {
    /** Kotlin/Native's when cross-compiling, the system's for the build host. */
    AUTO,

    /**
     * Kotlin/Native's clang with its sysroot for the target, the one the plugin's own builds use: `CC`,
     * `CXX` and `AR` in the environment, a CMake toolchain file, and `CC=…` on make's command line.
     */
    KONAN,

    /** Whatever the build finds itself; the plugin hands no compilers over. */
    SYSTEM;

    /** Whether [target] is built with Kotlin/Native's toolchain. */
    internal fun usesKonan(target: NativeTarget): Boolean = when (this) {
        AUTO -> target != NativeTarget.host
        KONAN -> true
        SYSTEM -> false
    }
}
