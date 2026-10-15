package io.github.lemcoder.dsl.builds

/**
 * How a library is built, when the build script says: one block — `cmake { }`, `make { }`, `cargo { }`,
 * `zig { }`, `external { }` or `prebuilt { }`. A library, a binding's `jni` and its `ffm` each offer the
 * blocks that make sense for them; calling none leaves the build to the plugin, calling two is an error.
 *
 * Every block but `prebuilt { }` runs the project's own build system once per target, told the plugin's
 * contract (`KONAN_LIB_NAME`, `KONAN_OUTPUT_DIR`, `KONAN_TARGET`, `KONAN_ABI`, and what the owner adds).
 */
sealed interface BuildSettings

/** A block run by the project's own build system, once per target: all but `prebuilt { }`. */
sealed interface ExternalBuildSettings : BuildSettings

/** The block's name as a build script calls it, for messages. */
internal val BuildSettings.blockName: String
    get() = when (this) {
        is CMakeSettings -> "cmake { }"
        is MakeSettings -> "make { }"
        is CargoSettings -> "cargo { }"
        is ZigSettings -> "zig { }"
        is StepsSettings -> "external { }"
        is PrebuiltSettings -> "prebuilt { }"
    }
