package io.github.lemcoder.interop

import io.github.lemcoder.hostKonanTarget
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation

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
    val registry = project.jvmInteropRegistry()
    val key = "${target.name}/$name"
    val container = registry.containerFor(key) { dir -> defaultSourceSet.kotlin.srcDir(dir) }
    configure.execute(container)
}

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
