package io.github.lemcoder.kotlin

import io.github.lemcoder.dsl.bindings.BindingSpec
import io.github.lemcoder.dsl.libraries.NativeArtifacts
import io.github.lemcoder.model.NativeTarget
import java.io.File
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

/**
 * Everything that touches the Kotlin Gradle plugin's types. Called only from `plugins.withId` callbacks
 * or once the plugin is known to be applied: in a project without KGP, a reference elsewhere would be a
 * NoClassDefFoundError.
 */
internal object KotlinGradlePlugin {
    private val JVM_PLATFORMS = setOf(KotlinPlatformType.jvm, KotlinPlatformType.androidJvm)

    /** The Kotlin plugin's version, which is the Kotlin/Native version it downloads. */
    fun version(project: Project): String = project.getKotlinPluginVersion()

    /** Generated bindings are sources of a `kotlin("jvm")` project's main source set. */
    fun addJvmSources(project: Project, sources: Provider<Directory>) {
        project.extensions.getByType(KotlinJvmProjectExtension::class.java).sourceSets.getByName("main").kotlin.srcDir(sources)
    }

    /** …and of the main compilation of every JVM and Android target of a multiplatform project. */
    fun addKmpJvmSources(project: Project, sources: Provider<Directory>, generate: TaskProvider<*>, enabled: () -> Boolean) {
        // Decided when the sources are read, not now: targets are usually declared before konan { }. A
        // provider of our own carries no producer, so the compile's dependency is spelled out too.
        val lazySources = project.provider { if (enabled()) listOf(sources.get()) else emptyList() }
        val lazyGenerate = project.provider { if (enabled()) listOf(generate) else emptyList() }
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).targets
            .matching { it.platformType in JVM_PLATFORMS }
            .configureEach {
                compilations.matching { it.name == "main" }.configureEach {
                    defaultSourceSet.kotlin.srcDir(lazySources)
                    compileTaskProvider.configure { dependsOn(lazyGenerate) }
                }
            }
    }

    /** Whether a multiplatform project has a `jvm()` target. */
    fun hasKmpPlainJvmTarget(project: Project): Boolean =
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).targets.any { it.platformType == KotlinPlatformType.jvm }

    /** Whether a multiplatform project has an Android target. */
    fun hasKmpAndroidTarget(project: Project): Boolean =
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).targets.any { it.platformType == KotlinPlatformType.androidJvm }

    /**
     * Makes every Android target's compilations wait for [task] when [enabled]: their Kotlin reads the
     * Java the task generates, which reaches them through AGP's variant API with no producer attached.
     */
    fun dependOnForKmpAndroid(project: Project, task: TaskProvider<*>, enabled: () -> Boolean) {
        val lazyTask = project.provider { if (enabled()) listOf(task) else emptyList() }
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).targets
            .matching { it.platformType == KotlinPlatformType.androidJvm }
            .configureEach { compilations.configureEach { compileTaskProvider.configure { dependsOn(lazyTask) } } }
    }

    /** An `implementation` dependency of a multiplatform project's `androidMain`. */
    fun addKmpAndroidDependency(project: Project, notation: Provider<String>) {
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).sourceSets
            .matching { it.name == "androidMain" }
            .configureEach { dependencies { implementation(notation) } }
    }

    /**
     * Java sources of every JVM target's main compilation in a multiplatform project: the Gradle source set
     * Kotlin creates for it, `<target>Main`, compiled by `compile<Target>MainJava`. Before Kotlin 2.1.20 a
     * `jvm()` target has none unless the build calls `withJava()`, so the plugin calls it; its Java is then
     * the project's `main` source set, compiled by `compileJava`.
     */
    fun addKmpJvmJava(project: Project, sources: Provider<Directory>) {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val javaByDefault = KotlinVersion.fromString(version(project)) >= KotlinVersion(2, 1, 20)
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).targets
            .matching { it.platformType == KotlinPlatformType.jvm }
            .configureEach {
                @Suppress("DEPRECATION")
                if (!javaByDefault) (this as KotlinJvmTarget).withJava()
                val name = if (javaByDefault) "${targetName}Main" else SourceSet.MAIN_SOURCE_SET_NAME
                sourceSets.matching { it.name == name }.configureEach { java.srcDir(sources) }
            }
    }

    /** Java sources of a `kotlin("jvm")` project's main source set, compiled by the java plugin it applies. */
    fun addJvmJava(project: Project, sources: Provider<Directory>) {
        project.extensions.getByType(SourceSetContainer::class.java).getByName(SourceSet.MAIN_SOURCE_SET_NAME).java.srcDir(sources)
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

/** `2.1.20`, `2.2.0-RC` as comparable numbers; a pre-release counts as its release. */
private fun KotlinVersion.Companion.fromString(version: String): KotlinVersion {
    val parts = version.split('.', '-').take(3).map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    return KotlinVersion(parts[0], parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
}
