package io.github.lemcoder.kotlin

import io.github.lemcoder.dsl.BindingSpec
import io.github.lemcoder.model.NativeArtifacts
import io.github.lemcoder.model.NativeTarget
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/**
 * Everything that touches the Kotlin Gradle plugin's types. Called only from `plugins.withId` callbacks
 * or once the plugin is known to be applied: in a project without KGP, a reference elsewhere would be a
 * NoClassDefFoundError.
 */
internal object KotlinGradlePlugin {
    private val JVM_PLATFORMS = setOf(KotlinPlatformType.jvm, KotlinPlatformType.androidJvm)

    /** Generated bindings are sources of a `kotlin("jvm")` project's main source set. */
    fun addJvmSources(project: Project, sources: Provider<Directory>) {
        project.extensions.getByType(KotlinJvmProjectExtension::class.java).sourceSets.getByName("main").kotlin.srcDir(sources)
    }

    /** …and of the main compilation of every JVM and Android target of a multiplatform project. */
    fun addKmpJvmSources(project: Project, sources: Provider<Directory>) {
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).targets
            .matching { it.platformType in JVM_PLATFORMS }
            .configureEach { compilations.matching { it.name == "main" }.configureEach { defaultSourceSet.kotlin.srcDir(sources) } }
    }

    fun hasKmpJvmTarget(project: Project): Boolean =
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).targets.any { it.platformType in JVM_PLATFORMS }

    /**
     * Kotlin/Native goes through KGP's own cinterop, not the plugin's generators: a klib is a compiler
     * artifact and must come from the project's Kotlin version. Declares the `cinterops {}` entry on the
     * main compilation of every Kotlin/Native target — those the library is built for, when there is one,
     * whose archive cinterop then embeds.
     */
    fun declareCinterops(project: Project, binding: BindingSpec, artifacts: NativeArtifacts?) {
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
            val target = this
            val nativeTarget = NativeTarget.of(target.konanTarget)
            if (artifacts != null && nativeTarget !in artifacts.targets.getOrElse(emptyList())) return@configureEach

            target.compilations.getByName("main").cinterops.create(binding.name) {
                definitionFile.set(binding.defFile)
                binding.packageName.orNull?.let { packageName = it }
                includeDirs(binding.includeDirs)
                compilerOpts(binding.compilerOptions.getOrElse(emptyList()))
                if (artifacts != null) {
                    extraOpts(
                        "-staticLibrary", NativeArtifacts.archiveFileName(artifacts.name),
                        "-libraryPath", artifacts.directory(nativeTarget).get().asFile.absolutePath,
                    )
                    project.tasks.named(interopProcessingTaskName).configure { dependsOn(artifacts.builtBy) }
                }
            }
        }
    }
}
