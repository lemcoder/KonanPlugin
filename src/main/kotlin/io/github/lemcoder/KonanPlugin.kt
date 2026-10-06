package io.github.lemcoder

import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.kotlin.KonanDistribution
import io.github.lemcoder.wiring.registerBindings
import io.github.lemcoder.wiring.registerLibraries
import org.gradle.api.Plugin
import org.gradle.api.Project

abstract class KonanPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val konan = project.extensions.create("konan", KonanExtension::class.java)
        // Auto-detected: $KONAN_HOME, else the newest ~/.konan/kotlin-native-prebuilt-*. Absent rather
        // than failing when there is none: the convention is queried whenever the configuration cache is
        // stored, so throwing here breaks configuration of builds that never touch a konan task. The
        // tasks that need it resolve it again when they run, and say what to do.
        konan.konanHome.convention(
            project.layout.dir(project.providers.provider { KonanDistribution.findHome() })
        )
        project.registerLibraries(konan)
        project.registerBindings(konan)
    }
}
