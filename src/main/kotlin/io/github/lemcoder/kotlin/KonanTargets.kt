package io.github.lemcoder.kotlin

import io.github.lemcoder.model.NativeTarget
import org.jetbrains.kotlin.konan.target.KonanTarget

/**
 * Kotlin/Native's own target as the plugin's — the only conversion between the two, e.g.
 * `targets(NativeTarget.of(kotlinNativeTarget.konanTarget))`. Fails for a target the plugin does not
 * know yet: [NativeTarget] then needs the new entry.
 */
fun NativeTarget.Companion.of(konanTarget: KonanTarget): NativeTarget =
    byKonanName(konanTarget.name)
        ?: error("Kotlin/Native target ${konanTarget.name} is not known to this version of the Konan plugin.")
