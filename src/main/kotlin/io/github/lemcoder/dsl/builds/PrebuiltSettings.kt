package io.github.lemcoder.dsl.builds

import io.github.lemcoder.model.NativeTarget
import org.gradle.api.provider.MapProperty
import java.io.File

/**
 * Libraries built elsewhere — a vendor drop, another repository's CI — one per target, placed where the
 * plugin's own build would leave them, under the name consumers look for: a library's archive as
 * `<target>/lib<name>.a`, so a Windows `name.lib` works as it is; the library FFM loads as
 * `lib<libraryName>` with the target's suffix.
 */
abstract class PrebuiltSettings : BuildSettings {
    /** The library for each target. */
    abstract val libraries: MapProperty<NativeTarget, File>

    /** The library built for [target]. */
    fun library(target: NativeTarget, file: File) = libraries.put(target, file)
}
