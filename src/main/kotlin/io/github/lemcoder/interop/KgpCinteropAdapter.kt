package io.github.lemcoder.interop

import io.github.lemcoder.KonanPluginExtension
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/**
 * The KGP side of [kotlinNativeInterop]. Its own object so that KGP's types are referenced only from code
 * that runs once the Kotlin Multiplatform plugin is applied.
 */
internal object KgpCinteropAdapter {
    fun declare(project: Project, name: String, settings: KotlinNativeInteropSettings) {
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        val konanConfig = project.extensions.findByType(KonanPluginExtension::class.java)

        kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
            val target = this
            val wanted = settings.targets.getOrElse(emptyList())
            if (wanted.isNotEmpty() && target.konanTarget !in wanted) return@configureEach

            target.compilations.getByName("main").cinterops.create(name) {
                definitionFile.set(settings.defFile)
                settings.packageName.orNull?.let { packageName = it }
                includeDirs(settings.includeDirs)
                compilerOpts(settings.compilerOptions.getOrElse(emptyList()))

                if (konanConfig != null) {
                    // Where runKonanClang puts this target's archive; cinterop ignores a missing directory.
                    val archives = project.layout.projectDirectory.dir(konanConfig.outputDir.get()).dir(target.konanTarget.name)
                    extraOpts("-libraryPath", archives.asFile.absolutePath)
                    project.tasks.named(interopProcessingTaskName).configure {
                        dependsOn(project.provider {
                            if (target.konanTarget in konanConfig.targets.getOrElse(emptyList())) listOf("runKonanClang") else emptyList()
                        })
                    }
                }
            }
        }
    }
}
