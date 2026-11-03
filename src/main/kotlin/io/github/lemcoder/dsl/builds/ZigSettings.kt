package io.github.lemcoder.dsl.builds

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * `zig build` for a library, once per target with `-Dtarget` set to the Zig triple that matches
 * Kotlin/Native's sysroot. The `build.zig` must install a static library ([artifactName]).
 */
abstract class ZigSettings : ExternalBuildSettings {
    init {
        optimize.convention(ZigOptimize.RELEASE_FAST)
    }

    /** The directory holding `build.zig`. Defaults to the library's `sourceDirectory`. */
    abstract val projectDirectory: DirectoryProperty

    /**
     * `-Doptimize`. Defaults to [ZigOptimize.RELEASE_FAST]: Zig's `DEBUG` and `RELEASE_SAFE` compile C with
     * UBSan traps, whose runtime the Kotlin/Native and JNI links do not have.
     */
    abstract val optimize: Property<ZigOptimize>

    /** Further `zig build` arguments, e.g. your own `-D` options. */
    abstract val arguments: ListProperty<String>

    /** The static library `build.zig` installs. Defaults to the library's name. */
    abstract val artifactName: Property<String>
}

/** Zig's optimisation modes, as `zig build -Doptimize` spells them. */
enum class ZigOptimize(val zigName: String) {
    DEBUG("Debug"),
    RELEASE_SAFE("ReleaseSafe"),
    RELEASE_FAST("ReleaseFast"),
    RELEASE_SMALL("ReleaseSmall"),
}
