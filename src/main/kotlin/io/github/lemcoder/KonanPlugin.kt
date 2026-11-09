package io.github.lemcoder

import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.dsl.bindings.registerBindings
import io.github.lemcoder.dsl.config.ConfigField
import io.github.lemcoder.dsl.config.registerConfigReport
import io.github.lemcoder.dsl.libraries.registerLibraries
import io.github.lemcoder.kotlin.KotlinGradlePlugin
import io.github.lemcoder.kotlin.UsesKonanDistribution
import io.github.lemcoder.wiring.AgpWiring
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

abstract class KonanPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val konan = project.extensions.create("konan", KonanExtension::class.java)
        // Every task that runs Kotlin/Native reads konanHome from config; checked when it runs, so a build
        // that never runs one needs none.
        project.tasks.withType(UsesKonanDistribution::class.java).configureEach {
            konanHome.convention(konan.config.readConfigField(ConfigField.KONAN_HOME, path))
            // The Kotlin plugin installs the distribution kotlinNativeDistribution() names in a task of its
            // own in recent versions (2.3.0 has it, 2.2.10 not); earlier ones download while configuring (#16).
            // project.provider, not konanHome.map: Gradle asks a mapped property for its producer, finds none,
            // and never evaluates the mapping.
            val home = konanHome
            dependsOn(project.provider {
                val installs = home.get().raw?.let(::File)?.absoluteFile == konan.config.kotlinNativeDistribution().absoluteFile
                if (installs && DOWNLOAD_KOTLIN_NATIVE in project.tasks.names) listOf(project.tasks.named(DOWNLOAD_KOTLIN_NATIVE)) else emptyList()
            })
        }
        project.registerConfigReport(konan.config)
        // kotlinNativeDistribution() names the project's own Kotlin/Native version. Told by the `kotlin` extension,
        // not a plugin id: AGP 9's built-in Kotlin registers it without applying `kotlin-android`. Asked lazily, so
        // the plugins may be applied in either order; findByName touches no KGP type.
        konan.config.kotlinPluginVersion = {
            if (project.extensions.findByName("kotlin") != null) KotlinGradlePlugin.version(project) else null
        }
        konan.androidApiLevel.convention(AgpWiring.minSdk(project))
        AgpWiring.ndkDirectory(project, konan.config.androidGradlePluginNdk)
        project.registerLibraries(konan)
        project.registerBindings(konan)
    }

    private companion object {
        const val DOWNLOAD_KOTLIN_NATIVE = "downloadKotlinNativeDistribution"
    }
}
