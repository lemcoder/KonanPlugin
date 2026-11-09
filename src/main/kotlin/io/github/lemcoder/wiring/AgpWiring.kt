package io.github.lemcoder.wiring

import com.android.build.api.variant.AndroidComponents
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Property
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
     *
     * [generate] is anything `dependsOn` takes. Compiling needs only the sources; the libraries are
     * packaging's, through [addJniLibs].
     */
    fun addSources(
        project: Project,
        kind: SourceKind,
        sources: Provider<Directory>,
        generate: Any,
        enabled: () -> Boolean,
    ) {
        val relative = sources.get().asFile.relativeTo(project.projectDir).path
        val ordering = {
            project.tasks.matching { it.name == "preBuild" }.configureEach { if (enabled()) dependsOn(generate) }
            project.tasks
                .matching { (it.name.startsWith("compile") && (it.name.contains("Kotlin") || it.name.contains("Java"))) || it.name == "compileJava" }
                .configureEach { if (enabled()) dependsOn(generate) }
        }
        project.withPlugin(GradlePluginId.ANDROID_BASE) {
            project.extensions.findByType(AndroidComponentsExtension::class.java)?.onVariants { variant ->
                if (!enabled()) return@onVariants
                project.file(relative).mkdirs()
                when (kind) {
                    SourceKind.KOTLIN -> if (!project.hasPlugin(GradlePluginId.KOTLIN_MULTIPLATFORM)) {
                        variant.sources.kotlin?.addStaticSourceDirectory(relative)
                        requireKotlinCompile(project, variant.name)
                    }
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
     * Fails configuring [variant]'s build when nothing compiles the Kotlin added to it — `android { enableKotlin = false }`,
     * or AGP without built-in Kotlin or `kotlin-android` — instead of letting `javac` report the bindings missing.
     * Judged by the compile task itself, which every way of compiling Kotlin names alike; it is registered after
     * the variant callbacks, so the check waits for the variant's `pre<Variant>Build` to be configured.
     */
    private fun requireKotlinCompile(project: Project, variant: String) {
        val capitalized = variant.replaceFirstChar { it.uppercase() }
        project.tasks.matching { it.name == "pre${capitalized}Build" }.configureEach {
            if ("compile${capitalized}Kotlin" !in project.tasks.names) throw GradleException(
                "${project.path}: JNI bindings are Kotlin, and variant '$variant' compiles none " +
                    "(android { enableKotlin = false }?). Enable Kotlin, or call ffm() in the binding for Java bindings."
            )
        }
    }

    /**
     * The lowest `minSdk` among the module's variants, once AGP has computed them; absent outside Android.
     * The API level Android links target, so they see every platform library the app may call.
     */
    /**
     * The NDK the module's Android Gradle plugin uses — `android.ndkVersion`, else AGP's default — into
     * [ndk]. Unset when there is none: no AGP, or its NDK is not installed, which AGP reports by throwing.
     */
    fun ndkDirectory(project: Project, ndk: Property<String>) {
        fun from(components: AndroidComponents?) {
            val directory = components?.sdkComponents?.ndkDirectory ?: return
            ndk.set(project.provider { runCatching { directory.orNull?.asFile?.absolutePath }.getOrNull() })
        }
        project.withPlugin(GradlePluginId.ANDROID_BASE) { from(project.extensions.findByType(AndroidComponentsExtension::class.java)) }
        project.withPlugin(GradlePluginId.ANDROID_KMP_LIBRARY) { from(project.extensions.findByType(KotlinMultiplatformAndroidComponentsExtension::class.java)) }
    }

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
     * a second registration of the same one fails the merge with "Duplicate resources". [builds] are the
     * Android targets' builds only, as anything `dependsOn` takes.
     */
    fun addJniLibs(project: Project, jniLibsRoot: Provider<Directory>, builds: Any, enabled: () -> Boolean) {
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
            .configureEach { if (enabled()) dependsOn(builds) }
    }
}
