package io.github.lemcoder.interop

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.jetbrains.kotlin.konan.target.KonanTarget

/**
 * Binds a cinterop `.def` for Kotlin/Native through KGP's own cinterop: an adapter that declares, on the
 * main compilation of every Kotlin/Native target, the `cinterops {}` entry a build would otherwise write
 * by hand. KGP then produces the interop klib with the project's own Kotlin version, per target, and wires
 * it into compilation, commonization, the IDE and publication.
 *
 * Where `konanConfig` builds the library for a target, cinterop sees its archive: `-libraryPath` points at
 * `<outputDir>/<target>` and the cinterop task runs after `runKonanClang`, so a `.def` naming
 * `staticLibraries = lib<libName>.a` embeds it.
 *
 * ```kotlin
 * kotlinNativeInterop("mymath") {
 *     defFile.set(file("src/nativeInterop/cinterop/mymath.def"))
 *     includeDirs.from("native")
 * }
 * ```
 *
 * Does nothing in a project without the Kotlin Multiplatform plugin.
 */
fun Project.kotlinNativeInterop(name: String, configure: Action<KotlinNativeInteropSettings>) {
    // Configured before KGP sees it: parts of KGP's cinterop settings are read eagerly.
    val settings = objects.newInstance(KotlinNativeInteropSettings::class.java)
    configure.execute(settings)
    // Behind withId so KGP's types are only resolved where KGP exists.
    plugins.withId("org.jetbrains.kotlin.multiplatform") { KgpCinteropAdapter.declare(this@kotlinNativeInterop, name, settings) }
}

/** What [kotlinNativeInterop] binds: the same inputs the plugin's other binding generators take. */
abstract class KotlinNativeInteropSettings {
    abstract val defFile: RegularFileProperty

    /** Overrides the `.def`'s `package`. */
    abstract val packageName: Property<String>

    /** Each directory becomes an `-I` for cinterop's clang. */
    abstract val includeDirs: ConfigurableFileCollection

    abstract val compilerOptions: ListProperty<String>

    /** The Kotlin/Native targets to bind for; every one in the project when empty. */
    abstract val targets: ListProperty<KonanTarget>
}
