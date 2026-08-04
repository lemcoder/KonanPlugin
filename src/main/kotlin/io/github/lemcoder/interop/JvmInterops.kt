package io.github.lemcoder.interop

import io.github.lemcoder.hostKonanTarget
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

/**
 * Declares JNI interops for this compilation, mirroring `cinterops` on a Kotlin/Native one:
 *
 * ```kotlin
 * kotlin {
 *     jvm {
 *         compilations["main"].jvmInterops {
 *             create("koinference") {
 *                 defFile(project.file("src/nativeInterop/koinference.def"))
 *                 packageName.set("com.example.native")
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * `KotlinCompilation` is not `ExtensionAware`, so this is an extension function rather than a DSL
 * block the Kotlin plugin owns — import it and it reads the same. Containers are held per compilation
 * by [JvmInteropRegistry], which also registers the tasks and wires the generated sources in.
 */
fun KotlinCompilation<*>.jvmInterops(configure: Action<NamedDomainObjectContainer<JvmInteropSettings>>) {
    configure.execute(jvmInterops)
}

/**
 * The interops declared on this compilation, for reading back what the plugin resolved — where the
 * linked library ended up, say.
 */
val KotlinCompilation<*>.jvmInterops: NamedDomainObjectContainer<JvmInteropSettings>
    get() = project.jvmInteropRegistry()
        .containerFor("${target.name}/$name") { dir -> defaultSourceSet.kotlin.srcDir(dir) }

/**
 * Declares JNI interops whose bindings belong to a source set rather than a single compilation —
 * a `jvmShared` shared by the JVM and Android targets, say, where the idiomatic wrapper lives once
 * and both platforms compile it.
 *
 * ```kotlin
 * kotlin.sourceSets["jvmSharedMain"].jvmInterops {
 *     create("mylib") { packageName.set("com.example.native") }
 * }
 * ```
 */
fun KotlinSourceSet.jvmInterops(configure: Action<NamedDomainObjectContainer<JvmInteropSettings>>) {
    configure.execute(jvmInterops)
}

/** The interops declared on this source set, for reading back what the plugin resolved. */
val KotlinSourceSet.jvmInterops: NamedDomainObjectContainer<JvmInteropSettings>
    get() = jvmInteropProject().jvmInteropRegistry()
        .containerFor("sourceSet/$name") { dir -> kotlin.srcDir(dir) }

/** The project a source set belongs to; KotlinSourceSet does not expose it directly. */
private fun KotlinSourceSet.jvmInteropProject(): Project =
    (this as org.jetbrains.kotlin.gradle.plugin.HasProject).project

/**
 * Declares JNI interops for a module without Kotlin compilations — an AGP application or library.
 * The generated bindings and per-ABI `jniLibs` are wired into the Android variants.
 */
fun Project.jvmInterops(configure: Action<NamedDomainObjectContainer<JvmInteropSettings>>) {
    configure.execute(jvmInteropRegistry().projectContainer())
}

internal fun Project.jvmInteropRegistry(): JvmInteropRegistry =
    extensions.findByType(JvmInteropRegistry::class.java)
        ?: error(
            "The Konan plugin is not applied to $path — apply id(\"io.github.lemcoder.konanplugin\") " +
                "before declaring jvmInterops."
        )

/** Default targets for an interop that does not name any: the host is the only JVM-loadable one. */
internal fun defaultInteropTargets(): List<KonanTarget> = listOf(hostKonanTarget())
