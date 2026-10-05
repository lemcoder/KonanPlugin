package io.github.lemcoder

import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.toolchain.NativeToolchain
import io.github.lemcoder.wiring.registerBindings
import io.github.lemcoder.wiring.registerLibraries
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * The `clean` task as a lazy collection — empty when the base plugin isn't applied, so it is safe to
 * pass to `mustRunAfter` unconditionally.
 */
internal fun Project.cleanTask() = tasks.matching { it.name == "clean" }

abstract class KonanPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val konan = project.extensions.create("konan", KonanExtension::class.java)
        // Auto-detected: $KONAN_HOME, else the newest ~/.konan/kotlin-native-prebuilt-*. Absent rather
        // than failing when there is none: the convention is queried whenever the configuration cache is
        // stored, so throwing here breaks configuration of builds that never touch a konan task. The
        // tasks that need it fail at execution instead, and say what to do.
        konan.konanHome.convention(
            project.layout.dir(project.providers.provider { NativeToolchain.findKonanHome() })
        )
        project.registerLibraries(konan)
        project.registerBindings(konan)
    }
}
