package io.github.lemcoder.dsl

import io.github.lemcoder.model.NativeTarget
import org.gradle.api.provider.MapProperty
import java.io.File

/**
 * A library's archives built elsewhere — a vendor drop, another repository's CI. Each is copied to
 * `<target>/lib<name>.a`, so a Windows `name.lib` works as it is.
 */
abstract class PrebuiltSettings {
    /** The archive for each target. */
    abstract val archives: MapProperty<NativeTarget, File>

    /** The static archive built for [target]. */
    fun archive(target: NativeTarget, file: File) = archives.put(target, file)
}
