package io.github.lemcoder.wiring

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/** The kinds of generated sources the plugin hands to AGP. */
internal enum class SourceKind { KOTLIN, JAVA }

/**
 * Everything that touches the Android Gradle plugin's types, behind `plugins.withId`. The KMP Android
 * plugin does not apply `com.android.base`, so both ids need handling.
 *
 * Registration is eager — the variant API rejects callbacks added later — but each takes an [enabled]
 * check, evaluated in the variant callback, after the build script's configure blocks have run.
 */
internal object AgpWiring {
    /**
     * An Android module outside Kotlin Multiplatform has no Kotlin compilation to attach to, so generated
     * sources go in through the variant API. A multiplatform module gets Kotlin ones through its Android
     * target instead.
     */
    fun addSources(
        project: Project,
        kind: SourceKind,
        sources: Provider<Directory>,
        generate: TaskProvider<*>,
        link: TaskProvider<Task>,
        enabled: () -> Boolean,
    ) {
        val relative = sources.get().asFile.relativeTo(project.projectDir).path
        val ordering = {
            project.tasks.matching { it.name == "preBuild" }.configureEach { if (enabled()) dependsOn(generate, link) }
            project.tasks
                .matching { (it.name.startsWith("compile") && (it.name.contains("Kotlin") || it.name.contains("Java"))) || it.name == "compileJava" }
                .configureEach { if (enabled()) dependsOn(generate) }
        }
        project.withPlugin(GradlePluginId.ANDROID_BASE) {
            project.extensions.findByType(AndroidComponentsExtension::class.java)?.onVariants { variant ->
                if (!enabled()) return@onVariants
                project.file(relative).mkdirs()
                when (kind) {
                    SourceKind.KOTLIN ->
                        if (!project.hasPlugin(GradlePluginId.KOTLIN_MULTIPLATFORM)) variant.sources.kotlin?.addStaticSourceDirectory(relative)
                    SourceKind.JAVA -> variant.sources.java?.addStaticSourceDirectory(relative)
                }
            }
            ordering()
        }
        // The multiplatform Android library plugin: Kotlin sources reach it through its Kotlin target; Java
        // ones need the target's withJava().
        if (kind == SourceKind.JAVA) project.withPlugin(GradlePluginId.ANDROID_KMP_LIBRARY) {
            project.extensions.findByType(KotlinMultiplatformAndroidComponentsExtension::class.java)?.onVariants { variant ->
                if (!enabled()) return@onVariants
                project.file(relative).mkdirs()
                val java = variant.sources.java
                if (java == null) {
                    project.logger.warn("${project.path}: the Android target compiles no Java — call withJava() in kotlin { android { } } for the FFM bindings.")
                } else {
                    java.addStaticSourceDirectory(relative)
                }
            }
            ordering()
        }
    }

    /**
     * The lowest `minSdk` among the module's variants, once AGP has computed them; absent outside Android.
     * The API level Android links target, so they see every platform library the app may call.
     */
    fun minSdk(project: Project): Provider<Int> {
        val levels = mutableSetOf<Int>()
        project.withPlugin(GradlePluginId.ANDROID_BASE) {
            project.extensions.findByType(AndroidComponentsExtension::class.java)?.onVariants { levels += it.minSdk.apiLevel }
        }
        project.withPlugin(GradlePluginId.ANDROID_KMP_LIBRARY) {
            project.extensions.findByType(KotlinMultiplatformAndroidComponentsExtension::class.java)?.onVariants { levels += it.minSdk.apiLevel }
        }
        return project.provider { levels.minOrNull() }
    }

    /**
     * Packages the per-ABI libraries under [jniLibsRoot] into the APK or AAR. Nothing consumes the
     * directory as a task output, so packaging needs the ordering spelled out. Added once per directory:
     * a second registration of the same one fails the merge with "Duplicate resources".
     */
    fun addJniLibs(project: Project, jniLibsRoot: Provider<Directory>, link: TaskProvider<Task>, enabled: () -> Boolean) {
        val relative = jniLibsRoot.get().asFile.relativeTo(project.projectDir).path
        project.withPlugin(GradlePluginId.ANDROID_KMP_LIBRARY) {
            project.extensions.findByType(KotlinMultiplatformAndroidComponentsExtension::class.java)?.onVariants { variant ->
                if (enabled()) variant.sources.jniLibs?.addStaticSourceDirectory(project.file(relative).apply { mkdirs() }.path)
            }
        }
        project.withPlugin(GradlePluginId.ANDROID_BASE) {
            project.extensions.findByType(AndroidComponentsExtension::class.java)?.onVariants { variant ->
                if (enabled()) variant.sources.jniLibs?.addStaticSourceDirectory(project.file(relative).apply { mkdirs() }.path)
            }
        }
        project.tasks.matching { it.name.contains("JniLibFolders") || it.name.contains("MergeJniLib") }
            .configureEach { if (enabled()) dependsOn(link) }
    }
}
