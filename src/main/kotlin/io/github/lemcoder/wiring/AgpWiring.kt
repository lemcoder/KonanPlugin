package io.github.lemcoder.wiring

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/**
 * Everything that touches the Android Gradle plugin's types, behind `plugins.withId`. The KMP Android
 * plugin does not apply `com.android.base`, so both ids need handling.
 */
internal object AgpWiring {
    /**
     * An Android module outside Kotlin Multiplatform has no Kotlin compilation to attach to, so the
     * generated sources go in through the variant API — registered at configuration time, since the API
     * rejects callbacks added later. A multiplatform module gets them through its Android target instead.
     */
    fun addKotlinSources(project: Project, sources: Provider<Directory>, generate: TaskProvider<*>, link: TaskProvider<Task>) {
        project.withPlugin(GradlePluginId.ANDROID_BASE) {
            val relative = sources.get().asFile.apply { mkdirs() }.relativeTo(project.projectDir).path
            project.extensions.findByType(AndroidComponentsExtension::class.java)?.onVariants { variant ->
                if (!project.hasPlugin(GradlePluginId.KOTLIN_MULTIPLATFORM)) {
                    variant.sources.kotlin?.addStaticSourceDirectory(relative)
                }
            }
            project.tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(generate, link) }
            project.tasks
                .matching { (it.name.startsWith("compile") && it.name.contains("Kotlin")) || it.name == "compileJava" }
                .configureEach { dependsOn(generate) }
        }
    }

    /**
     * Packages the per-ABI libraries into the APK or AAR. The directory has to exist at configuration
     * time; nothing consumes it as a task output, so packaging needs the ordering spelled out. Added once:
     * a second registration fails the merge with "Duplicate resources".
     */
    fun addJniLibs(project: Project, jniLibsRoot: Provider<Directory>, link: TaskProvider<Task>) {
        val relative = jniLibsRoot.get().asFile.apply { mkdirs() }.relativeTo(project.projectDir).path
        project.withPlugin(GradlePluginId.ANDROID_KMP_LIBRARY) {
            project.extensions.findByType(KotlinMultiplatformAndroidComponentsExtension::class.java)
                ?.onVariants { variant -> variant.sources.jniLibs?.addStaticSourceDirectory(relative) }
        }
        project.withPlugin(GradlePluginId.ANDROID_BASE) {
            project.extensions.findByType(AndroidComponentsExtension::class.java)
                ?.onVariants { variant -> variant.sources.jniLibs?.addStaticSourceDirectory(relative) }
        }
        project.tasks.matching { it.name.contains("JniLibFolders") || it.name.contains("MergeJniLib") }
            .configureEach { dependsOn(link) }
    }
}
