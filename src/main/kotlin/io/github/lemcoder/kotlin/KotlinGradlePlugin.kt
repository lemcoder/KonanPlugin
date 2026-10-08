package io.github.lemcoder.kotlin

import io.github.lemcoder.dsl.BindingSpec
import io.github.lemcoder.model.NativeArtifacts
import io.github.lemcoder.model.NativeTarget
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import java.io.File
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
    fun addKmpJvmSources(project: Project, sources: Provider<Directory>, generate: TaskProvider<*>, onJvmTargets: () -> Boolean) {
        // Decided when the sources are read, not now: targets are usually declared before konan { }. A
        // provider of our own carries no producer, so the jvm() compile's dependency is spelled out too.
        val jvmSources = project.provider { if (onJvmTargets()) listOf(sources.get()) else emptyList() }
        val jvmGenerate = project.provider { if (onJvmTargets()) listOf(generate) else emptyList() }
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).targets
            .matching { it.platformType in JVM_PLATFORMS }
            .configureEach {
                val jvm = platformType == KotlinPlatformType.jvm
                compilations.matching { it.name == "main" }.configureEach {
                    defaultSourceSet.kotlin.srcDir(if (jvm) jvmSources else sources)
                    if (jvm) compileTaskProvider.configure { dependsOn(jvmGenerate) }
                }
            }
    }

    /**
     * Java sources of every JVM target's main compilation in a multiplatform project: the Gradle source set
     * Kotlin creates for it, `<target>Main`, compiled by `compile<Target>MainJava`.
     */
    fun addKmpJvmJava(project: Project, sources: Provider<Directory>) {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).targets
            .matching { it.platformType == KotlinPlatformType.jvm }
            .configureEach {
                val name = "${targetName}Main"
                sourceSets.matching { it.name == name }.configureEach { java.srcDir(sources) }
            }
    }

    /** Resources of a `kotlin("jvm")` project's main source set. */
    fun addJvmResources(project: Project, resources: Provider<File>) {
        project.extensions.getByType(KotlinJvmProjectExtension::class.java).sourceSets.getByName("main").resources.srcDir(resources)
    }

    /** …and of the main compilation of every JVM target of a multiplatform project — not Android's, which packages jniLibs. */
    fun addKmpJvmResources(project: Project, resources: Provider<File>) {
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).targets
            .matching { it.platformType == KotlinPlatformType.jvm }
            .configureEach { compilations.matching { it.name == "main" }.configureEach { defaultSourceSet.resources.srcDir(resources) } }
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
