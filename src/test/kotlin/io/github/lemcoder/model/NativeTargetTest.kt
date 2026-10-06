package io.github.lemcoder.model

import io.github.lemcoder.kotlin.of
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The plugin's targets against Kotlin/Native's, for the version the plugin is built against. A Kotlin
 * bump that adds or retires a target fails here, naming it — add or remove the [NativeTarget] entry.
 */
class NativeTargetTest {
    private val konanTargets = KonanTarget.predefinedTargets.values

    @Test
    fun `every Kotlin-Native target has a NativeTarget, and no other`() {
        assertEquals(konanTargets.map { it.name }.sorted(), NativeTarget.entries.map { it.konanName }.sorted())
    }

    @Test
    fun `families, architectures and library names agree with Kotlin-Native's`() {
        konanTargets.forEach { konan ->
            val target = NativeTarget.of(konan)
            assertEquals(konan.family.name, target.family.name, "family of ${konan.name}")
            assertEquals(konan.architecture.name, target.architecture.name, "architecture of ${konan.name}")
            assertEquals(
                "${konan.family.dynamicPrefix}x.${konan.family.dynamicSuffix}",
                target.sharedLibraryName("x"),
                "shared library of ${konan.name}",
            )
            assertEquals(konan.family == Family.ANDROID, target.androidAbi != null, "Android ABI of ${konan.name}")
        }
    }

    @Test
    fun `the host is Kotlin-Native's host`() {
        assertEquals(HostManager.host.name, NativeTarget.host.konanName)
    }
}
