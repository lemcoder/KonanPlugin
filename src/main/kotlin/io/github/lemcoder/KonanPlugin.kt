package io.github.lemcoder

import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.kotlin.KotlinGradlePlugin
import io.github.lemcoder.kotlin.UsesKonanDistribution
import io.github.lemcoder.model.ConfigField
import io.github.lemcoder.wiring.AgpWiring
import io.github.lemcoder.wiring.GradlePluginId
import io.github.lemcoder.wiring.registerBindings
import io.github.lemcoder.wiring.registerConfigReport
import io.github.lemcoder.wiring.registerLibraries
import io.github.lemcoder.wiring.withPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project

abstract class KonanPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val konan = project.extensions.create("konan", KonanExtension::class.java)
        // Every task that runs Kotlin/Native reads konanHome from config; checked when it runs, so a build
        // that never runs one needs none.
        project.tasks.withType(UsesKonanDistribution::class.java).configureEach {
            konanHome.convention(konan.config.readConfigField(ConfigField.KONAN_HOME, path))
        }
        project.registerConfigReport(konan.config)
        // kotlinNativeDistribution() names the project's own Kotlin/Native version once the Kotlin plugin is known.
        for (kotlin in listOf(GradlePluginId.KOTLIN_JVM, GradlePluginId.KOTLIN_MULTIPLATFORM, GradlePluginId.KOTLIN_ANDROID)) {
            project.withPlugin(kotlin) { konan.config.kotlinPluginVersion = KotlinGradlePlugin.version(project) }
        }
        konan.androidApiLevel.convention(AgpWiring.minSdk(project))
        project.registerLibraries(konan)
        project.registerBindings(konan)
    }
}
